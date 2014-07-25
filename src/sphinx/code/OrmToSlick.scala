package com.typesafe.slick.docs
import scala.slick.driver.H2Driver.simple._

object OrmToSlick extends App {
  object Tables{
    //#tableClasses
    type Person = (Int,String,String,Int,Int)
    class Persons(tag: Tag) extends Table[Person](tag, "PERSON") {
      def id = column[Int]("ID", O.PrimaryKey, O.AutoInc)
      def first = column[String]("FIRST")
      def last = column[String]("LAST")
      def age = column[Int]("AGE")
      def addressId = column[Int]("LIVES_AT")
      def * = (id,first,last,age,addressId)
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
      def findByIds(ids: Seq[Int]): Seq[Person] = Seq()
      def findById(ids: Seq[Int]): Seq[Person] = Seq()
    }
    implicit class OrmPersonAddress(person: Person){
      def address: Address = null
    }
    implicit class OrmPrefetch(people: Seq[Person]){
      def prefetch(f: Person => Address) = people
    }
  }
  import Tables._

  val jdbcDriver = "org.h2.Driver"  
  val dbUrl = "jdbc:h2:mem:ormtoslick;DB_CLOSE_DELAY=-1"

  Database.forURL(dbUrl,driver=jdbcDriver) withSession {
    implicit session =>
    addresses.ddl.create
    addresses.insert(0,"station 14","Lausanne")
    addresses.insert(0,"Broadway 1","New York City")

    persons.ddl.create
    persons.insert((0,"Chris","Vogt",999,1))
    persons.insert((0,"John","Vogt",1001,1))
    persons.insert((0,"John","Doe",18,2))

    ;{
      //#ormObjectNavigation
      val people: Seq[Person] = PeopleFinder.findByIds(Seq(2,99,17,234))
      val addresses: Seq[Address] = people.map(_.address)
      //#ormObjectNavigation
    };{
      //#ormPrefetch
      val people: Seq[Person] = PeopleFinder.findByIds(Seq(2,99,17,234)).prefetch(_.address) // tell the ORM to load all related addresses together
      val addresses: Seq[Address] = people.map(_.address)
      //#ormPrefetch
    }
    type PersonTable = Persons // FIXME
    type AddressTable = Addresses // FIXME
    val People = persons
    ;{
      //#slickNavigation
      val peopleQuery: Query[PersonTable,Person,Seq] = People.filter(_.id inSet(Set(2,99,17,234)))
      val addressesQuery: Query[AddressTable,Address,Seq] = peopleQuery.flatMap(_.address)
      //#slickNavigation
      //#slickExecution
      val addresses: Seq[Address] = addressesQuery.run
      //#slickExecution
    }
  }
}
