package com.typesafe.slick.docs
import scala.slick.driver.H2Driver.simple._

object OrmToSlick extends App {
  object Tables{
    //#tableClasses
    type Person = (Int,String,String,Int,Int)
    class Persons(tag: Tag) extends Table[Person](tag, "PERSON") {
      def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)
      def name = column[String]("FIRST")
      def last = column[String]("LAST",O.Default(""))
      def age = column[Int]("AGE",O.Default(-1))
      def addressId = column[Int]("LIVES_AT",O.Default(1))
      def * = (id,name,last,age,addressId)
      def address = foreignKey("lives_at_fk2",addressId,addresses)(_.id)
    }
    lazy val persons = TableQuery[Persons]

    type Address = (Int,String,String)
    class Addresses(tag: Tag) extends Table[Address](tag, "ADDRESS") {
      def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)
      def street = column[String]("STREET")
      def city = column[String]("CITY")
      def * = (id,street,city)
    }
    lazy val addresses = TableQuery[Addresses]
    //#tableClasses

    // fake ORM
    object PeopleFinder{
      def getByIds(ids: Seq[Int]): Seq[Person] = Seq()
      def getById(id: Int): Person = null
    }
    implicit class OrmPersonAddress(person: Person){
      def address: Address = null
    }
    implicit class OrmPrefetch(people: Seq[Person]){
      def prefetch(f: Person => Address) = people
    }
    object session{
      def createQuery(hql: String) = new HqlQuery
      def createCriteria(cls: java.lang.Class[_]) = new Criteria
      def save = ()
    }
    class Criteria{
      def add(r: Restriction) = this
    }
    type Restriction = Criteria
    class HqlQuery{
      def setParameterList(column: String, values: Array[_]): Unit = ()
    }
    object Property{
      def forName(s:String) = new Property
    }
    class Property{
      def in(array: Array[_]): Restriction = new Restriction
      def lt(i: Int) = new Restriction
      def gt(i: Int) = new Restriction
    }
    object Restrictions{
      def disjunction = new Criteria
    }
  }
  import Tables._

  val jdbcDriver = "org.h2.Driver"  
  val dbUrl = "jdbc:h2:mem:ormtoslick;DB_CLOSE_DELAY=-1"

  Database.forURL(dbUrl,driver=jdbcDriver) withSession {
    implicit s =>
    addresses.ddl.create
    addresses.insert(0,"station 14","Lausanne")
    addresses.insert(0,"Broadway 1","New York City")

    persons.ddl.create
    persons.insert((0,"Chris","Vogt",999,1))
    persons.insert((0,"John","Vogt",1001,1))
    persons.insert((0,"John","Doe",18,2))

    ;{
      //#ormObjectNavigation
      val people: Seq[Person] = PeopleFinder.getByIds(Seq(2,99,17,234))
      val addresses: Seq[Address] = people.map(_.address)
      //#ormObjectNavigation
    };{
      //#ormPrefetch
      val people: Seq[Person] = PeopleFinder.getByIds(Seq(2,99,17,234)).prefetch(_.address) // tell the ORM to load all related addresses together
      val addresses: Seq[Address] = people.map(_.address)
      //#ormPrefetch
    }
    type PersonTable = Persons // FIXME
    type AddressTable = Addresses // FIXME
    val Addresses = addresses // FIXME
    val People = persons
    ;{
      //#slickNavigation
      val peopleQuery: Query[PersonTable,Person,Seq] = People.filter(_.id inSet(Set(2,99,17,234)))
      val addressesQuery: Query[AddressTable,Address,Seq] = peopleQuery.flatMap(_.address)
      //#slickNavigation
      //#slickExecution
      val addresses: Seq[Address] = addressesQuery.run
      //#slickExecution
    };{
      type Query = HqlQuery
      //#hqlQuery
      val hql: String = "FROM Person p WHERE p.id in (:ids)";
      val q: Query = session.createQuery(hql);
      q.setParameterList("ids", Array(2,99,17,234));      
      //#hqlQuery
    };{
      //#criteriaQuery
      val id = Property.forName("id");
      val q = session.createCriteria(classOf[Person])
                     .add( id in Array(2,99,17,234) )
      //#criteriaQuery
      //#criteriaQueryComposition
      def byIds(c: Criteria, ids: Array[Int]) = c.add( id in ids )

      val c = byIds(
        session.createCriteria(classOf[Person]),
        Array(2,99,17,234)
      )      
      //#criteriaQueryComposition
    };{
      //#criteriaComposition
      val age = Property.forName("age")
      val q = session.createCriteria(classOf[Person])
                      .add(
      Restrictions.disjunction
        .add(age lt 5)
        .add(age gt 65)
      )
      //#criteriaComposition
    };{
      //#slickQuery
      val q = People.filter(p => p.age < 5 || p.age > 65)
      //#slickQuery
    };{
      //#slickQueryWithTypes
      val q = (People: Query[PersonTable, Person, Seq]).filter(
        (p: PersonTable) => 
          (
            ((p.age: Column[Int]) < 5 || p.age > 65)
            : Column[Boolean]
          )
      )
      //#slickQueryWithTypes
    };{
      //#slickForComprehension
      for( p <- People if p.age < 5 || p.age > 65 ) yield p
      //#slickForComprehension
    };{
      //#slickOrderBy
      ( for( p <- People if p.age < 5 || p.age > 65 ) yield p ).sortBy(_.name)
      //#slickOrderBy
    };{
      //#slickMap
      People.map(p => (p.name, p.age))
      //#slickMap
    };{
      //#ormGetById
      PeopleFinder.getById(5)
      //#ormGetById
    };{
      //#slickRun
      People.filter(_.id === 5).run
      //#slickRun
    };{
      //#ormWriteCaching
      val person = PeopleFinder.getById(5)
      //#ormWriteCaching
    };{
      import scala.language.reflectiveCalls
      val person = new {
        var name: String = ""
        var last: String = ""
      }
      //#ormWriteCaching
      person.name = "Chris"
      person.last = "Vogt"
      session.save      
      //#ormWriteCaching
    };{
      //#slickUpdate
      val personQuery = People.filter(_.id === 5)
      personQuery.map(p => (p.name,p.last)).update("Chris","Vogt")
      //#slickUpdate

      //#slickDelete
      personQuery.delete // deletes person with id 5
      //#slickDelete
    };{
      //#slickInsert
      People.map(p => (p.name,p.last)).insert("Stefan","Zeiger")
      //#slickInsert
    };{
      import scala.language.higherKinds
      //#slickRelationships
      implicit class PersonExtensions[C[_]](q: Query[PersonTable, Person, C]) {
        // specify mapping of relationship to address
        def withAddress = q.join(Addresses).on(_.addressId === _.id)
      }

      //#slickRelationships
      ;{
        //#slickRelationships
        val chrisQuery = People.filter(_.id === 2)
        val stefanQuery = People.filter(_.id === 3)

        val chrisWithAddress: (Person, Address) = chrisQuery.withAddress.first
        val stefanWithAddress: (Person, Address) = stefanQuery.withAddress.first
        //#slickRelationships
      };{
        //#relationshipNavigation
        val chris: Person = PeopleFinder.getById(2)
        val address: Address = chris.address
        //#relationshipNavigation
      };{
        //#slickRelationships2
        val chrisQuery: Query[PersonTable,Person,Seq] = People.filter(_.id === 2)
        val addressQuery: Query[AddressTable,Address,Seq] = chrisQuery.withAddress.map(_._2)
        val address = addressQuery.first
        //#slickRelationships2
      }
    }
  }
}
