package scala.slick.driver

import java.util.UUID
import java.sql.{PreparedStatement, ResultSet}
import scala.slick.lifted._
import scala.slick.profile.{SqlProfile, RelationalProfile, Capability}
import scala.slick.ast.{SequenceNode, Library, FieldSymbol, Node, Insert, InsertColumn, Select, ElementSymbol, ColumnOption }
import scala.slick.ast.Util._
import scala.slick.util.MacroSupport.macroSupportInterpolation
import scala.slick.compiler.CompilerState
import scala.slick.jdbc.meta.MTable
import scala.slick.jdbc.{Invoker, JdbcType}
import scala.slick.model.Model

/** Slick driver for PostgreSQL.
  *
  * This driver implements [[scala.slick.driver.JdbcProfile]]
  * ''without'' the following capabilities:
  *
  * <ul>
  *   <li>[[scala.slick.driver.JdbcProfile.capabilities.insertOrUpdate]]:
  *     InsertOrUpdate operations are emulated on the server side with a single
  *     JDBC statement executing multiple server-side statements in a transaction.
  *     This is faster than a client-side emulation but may still fail due to
  *     concurrent updates. InsertOrUpdate operations with `returning` are
  *     emulated on the client side.</li>
  * </ul>
  *
  * Notes:
  *
  * <ul>
  *   <li>[[scala.slick.profile.RelationalProfile.capabilities.typeBlob]]:
  *   The default implementation of the <code>Blob</code> type uses the
  *   database type <code>lo</code> and the stored procedure
  *   <code>lo_manage</code>, both of which are provided by the "lo"
  *   extension in PostgreSQL.</li>
  * </ul>
  */
trait PostgresDriver extends JdbcDriver { driver =>

  override protected def computeCapabilities: Set[Capability] = (super.computeCapabilities
    - JdbcProfile.capabilities.insertOrUpdate
  )

  class ModelBuilder(mTables: Seq[MTable], ignoreInvalidDefaults: Boolean = true)(implicit session: Backend#Session) extends super.ModelBuilder(mTables, ignoreInvalidDefaults){
    override def Table = new Table(_){
      override def schema = super.schema.filter(_ != "public") // remove default schema
      override def Column = new Column(_){
        override def default = meta.columnDef.map((_,tpe)).collect{
          case ("true","Boolean")  => Some(Some(true))
          case ("false","Boolean") => Some(Some(false))
        }.getOrElse{super.default}
      }
      override def Index = new Index(_){
        // FIXME: this needs a test
        override def columns = super.columns.map(_.stripPrefix("\"").stripSuffix("\""))
      }
    }
  }

  override def defaultTables(implicit session: Backend#Session) = MTable.getTables(None, None, None, Some(Seq("TABLE"))).list

  override def createModel(tables: Option[Seq[MTable]] = None, ignoreInvalidDefaults: Boolean = true)
                          (implicit session: Backend#Session)
                          : Model
    = new ModelBuilder(tables.getOrElse(defaultTables), ignoreInvalidDefaults).model

  override val columnTypes = new JdbcTypes
  override def createQueryBuilder(n: Node, state: CompilerState): QueryBuilder = new QueryBuilder(n, state)
  override def createUpsertBuilder(node: Insert): InsertBuilder = new UpsertBuilder(node)
  override def createTableDDLBuilder(table: Table[_]): TableDDLBuilder = new TableDDLBuilder(table)
  override def createColumnDDLBuilder(column: FieldSymbol, table: Table[_]): ColumnDDLBuilder = new ColumnDDLBuilder(column)
  override protected lazy val useServerSideUpsert = true
  override protected lazy val useTransactionForUpsert = true
  override protected lazy val useServerSideUpsertReturning = false

  override def defaultSqlTypeName(tmd: JdbcType[_]): String = tmd.sqlType match {
    case java.sql.Types.BLOB => "lo"
    case java.sql.Types.DOUBLE => "DOUBLE PRECISION"
    /* PostgreSQL does not have a TINYINT type, so we use SMALLINT instead. */
    case java.sql.Types.TINYINT => "SMALLINT"
    case _ => super.defaultSqlTypeName(tmd)
  }

  class QueryBuilder(tree: Node, state: CompilerState) extends super.QueryBuilder(tree, state) {
    override protected val concatOperator = Some("||")
    override protected val supportsEmptyJoinConditions = false

    override protected def buildFetchOffsetClause(fetch: Option[Node], offset: Option[Node]) = (fetch, offset) match {
      case (Some(t), Some(d)) => b" limit $t offset $d"
      case (Some(t), None   ) => b" limit $t"
      case (None,    Some(d)) => b" offset $d"
      case _ =>
    }

    override def expr(n: Node, skipParens: Boolean = false) = n match {
      case Library.NextValue(SequenceNode(name)) => b"nextval('$name')"
      case Library.CurrentValue(SequenceNode(name)) => b"currval('$name')"
      case _ => super.expr(n, skipParens)
    }
  }

  class UpsertBuilder(ins: Insert) extends super.UpsertBuilder(ins) {
    override def buildInsert: InsertBuilderResult = {
      val update = "update " + tableName + " set " + softNames.map(n => s"$n=?").mkString(",") + " where " + pkNames.map(n => s"$n=?").mkString(" and ")
      val nonAutoIncNames = nonAutoIncSyms.map(fs => quoteIdentifier(fs.name)).mkString(",")
      val nonAutoIncVars = nonAutoIncSyms.map(_ => "?").mkString(",")
      val cond = pkNames.map(n => s"$n=?").mkString(" and ")
      val insert = s"insert into $tableName ($nonAutoIncNames) select $nonAutoIncVars where not exists (select 1 from $tableName where $cond)"
      new InsertBuilderResult(table, s"begin; $update; $insert; end", softSyms ++ pkSyms)
    }

    override def transformMapping(n: Node) = reorderColumns(n, softSyms ++ pkSyms ++ nonAutoIncSyms ++ pkSyms)
  }

  class TableDDLBuilder(table: Table[_]) extends super.TableDDLBuilder(table) {
    override def createPhase1 = super.createPhase1 ++ columns.flatMap {
      case cb: ColumnDDLBuilder => cb.createLobTrigger(table.tableName)
    }
    override def dropPhase1 = {
      val dropLobs = columns.flatMap {
        case cb: ColumnDDLBuilder => cb.dropLobTrigger(table.tableName)
      }
      if(dropLobs.isEmpty) super.dropPhase1
      else Seq("delete from "+quoteIdentifier(table.tableName)) ++ dropLobs ++ super.dropPhase1
    }
  }

  class ColumnDDLBuilder(column: FieldSymbol) extends super.ColumnDDLBuilder(column) {
    override def appendColumn(sb: StringBuilder) {
      sb append quoteIdentifier(column.name) append ' '
      if(autoIncrement && !customSqlType) {
        sb append (if(sqlType.toUpperCase == "BIGINT") "BIGSERIAL" else "SERIAL")
      } else appendType(sb)
      autoIncrement = false
      appendOptions(sb)
    }

    def lobTrigger(tname: String) =
      quoteIdentifier(tname+"__"+quoteIdentifier(column.name)+"_lob")

    def createLobTrigger(tname: String): Option[String] =
      if(sqlType == "lo") Some(
        "create trigger "+lobTrigger(tname)+" before update or delete on "+
        quoteIdentifier(tname)+" for each row execute procedure lo_manage("+quoteIdentifier(column.name)+")"
      ) else None

    def dropLobTrigger(tname: String): Option[String] =
      if(sqlType == "lo") Some(
        "drop trigger "+lobTrigger(tname)+" on "+quoteIdentifier(tname)
      ) else None
  }

  class JdbcTypes extends super.JdbcTypes {
    override val byteArrayJdbcType = new ByteArrayJdbcType
    override val uuidJdbcType = new UUIDJdbcType

    class ByteArrayJdbcType extends super.ByteArrayJdbcType {
      override val sqlType = java.sql.Types.BINARY
      override val sqlTypeName = "BYTEA"
    }

    class UUIDJdbcType extends super.UUIDJdbcType {
      override def sqlTypeName = "UUID"
      override def setValue(v: UUID, p: PreparedStatement, idx: Int) = p.setObject(idx, v, sqlType)
      override def getValue(r: ResultSet, idx: Int) = r.getObject(idx).asInstanceOf[UUID]
      override def updateValue(v: UUID, r: ResultSet, idx: Int) = r.updateObject(idx, v)
      override def valueToSQLLiteral(value: UUID) = "'" + value + "'"
      override def hasLiteralForm = true
    }
  }
}

object PostgresDriver extends PostgresDriver
