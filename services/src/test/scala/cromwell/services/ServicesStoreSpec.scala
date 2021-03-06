package cromwell.services

import java.io.{ByteArrayOutputStream, PrintStream}
import java.sql.{Connection, JDBCType}
import java.time.OffsetDateTime
import javax.sql.rowset.serial.{SerialBlob, SerialClob, SerialException}

import better.files._
import com.typesafe.config.{Config, ConfigFactory}
import cromwell.core.Tags._
import cromwell.core.WorkflowId
import cromwell.database.migration.liquibase.LiquibaseUtils
import cromwell.database.slick.SlickDatabase
import cromwell.database.sql.SqlConverters._
import cromwell.database.sql.joins.JobStoreJoin
import cromwell.database.sql.tables.{JobStoreEntry, JobStoreSimpletonEntry, WorkflowStoreEntry}
import liquibase.diff.DiffResult
import liquibase.diff.output.DiffOutputControl
import liquibase.diff.output.changelog.DiffToChangeLog
import org.hsqldb.persist.HsqlDatabaseProperties
import org.scalactic.StringNormalizations
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{FlatSpec, Matchers}
import slick.driver.JdbcProfile
import slick.jdbc.meta._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}
import scala.util.Try
import scala.xml._

class ServicesStoreSpec extends FlatSpec with Matchers with ScalaFutures with StringNormalizations {

  import ServicesStoreSpec._

  implicit val ec = ExecutionContext.global

  implicit val defaultPatience = PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  behavior of "ServicesStore"

  it should "deadlock and timeout after 10 seconds" in {
    /*
    Tests that we still need our deadlock workaround in `database.run` to deal with problems in Slick 3.1.
    If/when this test fails, the workaround should be removed.
    */

    class DeadlockingSlickDatabase(config: Config) extends SlickDatabase(config) {

      import dataAccess.driver.api._

      override protected[this] def runTransaction[R](action: DBIO[R]): Future[R] = {
        database.run(action.transactionally) //<-- https://github.com/slick/slick/issues/1274
      }
    }

    // Test based on https://github.com/kwark/slick-deadlock/blob/82525fc/src/main/scala/SlickDeadlock.scala
    val databaseConfig = ConfigFactory.parseString(
      s"""|db.url = "jdbc:hsqldb:mem:$${uniqueSchema};shutdown=false;hsqldb.tx=mvcc"
          |db.driver = "org.hsqldb.jdbcDriver"
          |db.connectionTimeout = 3000
          |db.numThreads = 2
          |driver = "slick.driver.HsqldbDriver$$"
          |""".stripMargin)
    import ServicesStore.EnhancedSqlDatabase
    for {
      database <- new DeadlockingSlickDatabase(databaseConfig).initialized.autoClosed
    } {
      val futures = 1 to 20 map { _ =>
        val workflowUuid = WorkflowId.randomId().toString
        val callFqn = "call.fqn"
        val jobIndex = 1
        val jobAttempt = 1
        val jobSuccessful = false
        val jobStoreEntry = JobStoreEntry(workflowUuid, callFqn, jobIndex, jobAttempt, jobSuccessful, None, None, None)
        val jobStoreJoins = Seq(JobStoreJoin(jobStoreEntry, Seq()))
        // NOTE: This test just needs to repeatedly read/write from a table that acts as a PK for a FK.
        for {
          _ <- database.addJobStores(jobStoreJoins)
          queried <- database.queryJobStores(workflowUuid, callFqn, jobIndex, jobAttempt)
          _ = queried.get.jobStoreEntry.workflowExecutionUuid should be(workflowUuid)
        } yield ()
      }

      intercept[TimeoutException](Await.result(Future.sequence(futures), 10.seconds))
    }
  }

  it should "not deadlock" in {
    // Test based on https://github.com/kwark/slick-deadlock/blob/82525fc/src/main/scala/SlickDeadlock.scala
    val databaseConfig = ConfigFactory.parseString(
      s"""|db.url = "jdbc:hsqldb:mem:$${uniqueSchema};shutdown=false;hsqldb.tx=mvcc"
          |db.driver = "org.hsqldb.jdbcDriver"
          |db.connectionTimeout = 3000
          |db.numThreads = 2
          |driver = "slick.driver.HsqldbDriver$$"
          |""".stripMargin)
    import ServicesStore.EnhancedSqlDatabase
    for {
      database <- new SlickDatabase(databaseConfig).initialized.autoClosed
    } {
      val futures = 1 to 20 map { _ =>
        val workflowUuid = WorkflowId.randomId().toString
        val callFqn = "call.fqn"
        val jobIndex = 1
        val jobAttempt = 1
        val jobSuccessful = false
        val jobStoreEntry = JobStoreEntry(workflowUuid, callFqn, jobIndex, jobAttempt, jobSuccessful, None, None, None)
        val jobStoreJoins = Seq(JobStoreJoin(jobStoreEntry, Seq()))
        // NOTE: This test just needs to repeatedly read/write from a table that acts as a PK for a FK.
        for {
          _ <- database.addJobStores(jobStoreJoins)
          queried <- database.queryJobStores(workflowUuid, callFqn, jobIndex, jobAttempt)
          _ = queried.get.jobStoreEntry.workflowExecutionUuid should be(workflowUuid)
        } yield ()
      }
      Future.sequence(futures).futureValue(Timeout(10.seconds))
    }
  }

  "Slick" should behave like testSchemaManager("slick")

  "Liquibase" should behave like testSchemaManager("liquibase")

  def testSchemaManager(schemaManager: String): Unit = {
    val otherSchemaManager = if (schemaManager == "slick") "liquibase" else "slick"

    it should s"have the same schema as $otherSchemaManager" in {
      for {
        actualDatabase <- databaseForSchemaManager(schemaManager).autoClosed
        expectedDatabase <- databaseForSchemaManager(otherSchemaManager).autoClosed
      } {
        compare(
          actualDatabase.dataAccess.driver, actualDatabase.database,
          expectedDatabase.dataAccess.driver, expectedDatabase.database) { diffResult =>

          import cromwell.database.migration.liquibase.DiffResultFilter._

          /*
          NOTE: Unique indexes no longer need to be filtered, as WE SHOULD NOT BE USING THEM!
          See notes at the bottom of changelog.xml
           */
          val diffFilters = StandardTypeFilters
          val filteredDiffResult = diffResult
            .filterLiquibaseObjects
            .filterTableObjects(oldeTables)
            .filterChangedObjects(diffFilters)

          val totalChanged =
            filteredDiffResult.getChangedObjects.size +
              filteredDiffResult.getMissingObjects.size +
              filteredDiffResult.getUnexpectedObjects.size

          if (totalChanged > 0) {
            val outputStream = new ByteArrayOutputStream
            val printStream = new PrintStream(outputStream, true)
            val diffOutputControl = new DiffOutputControl(false, false, false, Array.empty)
            val diffToChangeLog = new DiffToChangeLog(filteredDiffResult, diffOutputControl)
            diffToChangeLog.print(printStream)
            val changeSetsScoped = XML.loadString(outputStream.toString) \ "changeSet" \ "_"
            val changeSets = changeSetsScoped map stripNodeScope
            fail(changeSets.mkString(
              s"The following changes are in $schemaManager but not in $otherSchemaManager:\n  ",
              "\n  ",
              "\nEnsure that the columns/fields exist, with the same lengths in " +
                s"$schemaManager and $otherSchemaManager and synchronize the two."))
          }
        }
      }
    }

    it should "match expected generated names" in {
      var schemaMetadata: SchemaMetadata = null

      for {
        slickDatabase <- databaseForSchemaManager(schemaManager).autoClosed
      } {
        import slickDatabase.dataAccess.driver.api._
        val schemaMetadataFuture =
          for {
            tables <- slickDatabase.database.run(MTable.getTables(Option("PUBLIC"), Option("PUBLIC"), None, None))
            workingTables = tables
              .filterNot(_.name.name.startsWith("DATABASECHANGELOG"))
              .filterNot(table => oldeTables.contains(table.name.name))
              // NOTE: MetadataEntry column names are perma-busted due to the large size of the table.
              .filterNot(_.name.name == "METADATA_ENTRY")
            columns <- slickDatabase.database.run(DBIO.sequence(workingTables.map(_.getColumns)))
            indexes <- slickDatabase.database.run(DBIO.sequence(workingTables.map(_.getIndexInfo())))
            primaryKeys <- slickDatabase.database.run(DBIO.sequence(workingTables.map(_.getPrimaryKeys)))
            foreignKeys <- slickDatabase.database.run(DBIO.sequence(workingTables.map(_.getExportedKeys)))
          } yield SchemaMetadata(tables, columns.flatten, indexes.flatten.filterNot(isGenerated),
            primaryKeys.flatten.filterNot(isGenerated), foreignKeys.flatten)

        schemaMetadata = schemaMetadataFuture.futureValue
      }

      var misnamed = Seq.empty[String]

      schemaMetadata.primaryKeyMetadata foreach { primaryKey =>
        val actual = primaryKey.pkName.get
        val expected = s"PK_${primaryKey.table.name}"
        if (actual != expected) {
          misnamed :+=
            s"""|  PrimaryKey: $actual
                |  Should be:  $expected
                |""".stripMargin
        }
      }

      schemaMetadata.foreignKeyMetadata foreach { foreignKey =>
        val actual = foreignKey.fkName.get
        val expected = s"FK_${foreignKey.fkTable.name}_${foreignKey.fkColumn}"
        if (actual != expected) {
          misnamed :+=
            s"""|  ForeignKey: $actual
                |  Should be:  $expected
                |""".stripMargin
        }
      }

      schemaMetadata.indexMetadata.groupBy(getIndexName) foreach {
        case (indexName, indexColumns) =>
          val index = indexColumns.head
          val prefix = if (index.nonUnique) "IX" else "UC"
          val tableName = index.table.name
          val sortedColumns = indexColumns.sortBy(_.ordinalPosition)
          val abbrColumns = sortedColumns.map(indexColumn => snakeAbbreviate(indexColumn.column.get))

          val actual = indexName
          val expected = abbrColumns.mkString(s"${prefix}_${tableName}_", "_", "")

          if (actual != expected) {
            misnamed :+=
              s"""|  Index:     $actual
                  |  Should be: $expected
                  |""".stripMargin
          }
      }

      var missing = Seq.empty[String]

      schemaMetadata.columns foreach { column =>
        if (!schemaMetadata.existsTableItem(column)) {
          missing :+= s"  ${tableClassName(column.tableName)}.${column.itemName}"
        }
      }

      schemaMetadata.slickItems foreach { databaseItem =>
        if (!schemaMetadata.existsSlickMapping(databaseItem)) {
          missing :+= s"  ${slickClassName(databaseItem.tableName)}.${databaseItem.itemName}"
        }
      }

      var nullTyped = Seq.empty[String]

      schemaMetadata.columnMetadata foreach { column =>
        if (column.isNullable == Option(false)) {
          val jdbcType = JDBCType.valueOf(column.sqlType)
          if (jdbcType == JDBCType.BLOB || jdbcType == JDBCType.CLOB) {
            nullTyped :+= s"  $jdbcType column ${column.table.name}.${column.name}"
          }
        }
      }

      if (missing.nonEmpty || misnamed.nonEmpty || nullTyped.nonEmpty) {
        var failMessage = ""

        if (misnamed.nonEmpty) {
          failMessage += misnamed.mkString(s"The following items are misnamed in $schemaManager:\n", "\n", "\n")
        }

        if (missing.nonEmpty) {
          failMessage += missing.mkString(
            s"Based on the schema in $schemaManager, please ensure that the following tables/columns exist:\n",
            "\n", "\n")
        }

        if (nullTyped.nonEmpty) {
          failMessage += nullTyped.mkString(
            s"The following columns should not be nullable due to incompatibilities with MySQL:\n", "\n", "\n")
        }

        fail(failMessage)
      }
    }
  }

  "SlickDatabase (hsqldb)" should behave like testWith("database")

  "SlickDatabase (mysql)" should behave like testWith("database-test-mysql")

  def testWith(configPath: String): Unit = {
    import ServicesStore.EnhancedSqlDatabase

    lazy val databaseConfig = ConfigFactory.load.getConfig(configPath)
    lazy val dataAccess = new SlickDatabase(databaseConfig).initialized

    lazy val getProduct = {
      import dataAccess.dataAccess.driver.api._
      SimpleDBIO[String](_.connection.getMetaData.getDatabaseProductName)
    }

    it should "(if hsqldb) have transaction isolation mvcc" taggedAs DbmsTest in {
      import dataAccess.dataAccess.driver.api._
      //noinspection SqlDialectInspection
      val getHsqldbTx = sql"""SELECT PROPERTY_VALUE
                              FROM INFORMATION_SCHEMA.SYSTEM_PROPERTIES
                              WHERE PROPERTY_NAME = 'hsqldb.tx'""".as[String].head

      (for {
        product <- dataAccess.database.run(getProduct)
        _ <- product match {
          case HsqlDatabaseProperties.PRODUCT_NAME =>
            dataAccess.database.run(getHsqldbTx) map { hsqldbTx =>
              (hsqldbTx shouldEqual "mvcc") (after being lowerCased)
            }
          case _ => Future.successful(())
        }
      } yield ()).futureValue
    }

    it should "fail to store and retrieve empty clobs in MySQL" taggedAs DbmsTest in {
      /*
      Tests that we still need our empty clob workaround in `SqlConverters.toClob`. The current mysql jdbc driver throws
      an exception when serializing an empty clob. If/when this test fails, the workaround should be removed.
      */
      val emptyClob = new SerialClob(Array.empty[Char])

      val workflowUuid = WorkflowId.randomId().toString
      val callFqn = "call.fqn"
      val jobIndex = 1
      val jobAttempt = 1
      val jobSuccessful = false
      val jobStoreEntry = JobStoreEntry(workflowUuid, callFqn, jobIndex, jobAttempt, jobSuccessful, None, None, None)
      val jobStoreSimpletonEntries = Seq(JobStoreSimpletonEntry("empty", Option(emptyClob), "WdlString"))
      val jobStoreJoins = Seq(JobStoreJoin(jobStoreEntry, jobStoreSimpletonEntries))

      val future = for {
        product <- dataAccess.database.run(getProduct)
        _ = product match {
          case "MySQL" =>
            val exception = intercept[SerialException] {
              Await.result(dataAccess.addJobStores(jobStoreJoins), Duration.Inf)
            }
            exception should be(a[SerialException])
            exception.getMessage should be("Invalid position in SerialClob object set")
          case _ => ()
        }
      } yield ()

      future.futureValue
    }

    it should "fail to store and retrieve empty blobs in MySQL" taggedAs DbmsTest in {
      /*
      Tests that we still need our empty blob workaround in `SqlConverters.toBlob`. The current mysql jdbc driver throws
      an exception when serializing an empty blob. If/when this test fails, the workaround should be removed.
      */
      val emptyBlob = new SerialBlob(Array.empty[Byte])

      val workflowUuid = WorkflowId.randomId().toString
      val workflowStoreEntry = WorkflowStoreEntry(workflowUuid, "{}".toClob, "{}".toClob, "{}".toClob,
        "Testing", OffsetDateTime.now.toSystemTimestamp, Option(emptyBlob), "{}".toClob)

      val workflowStoreEntries = Seq(workflowStoreEntry)

      val future = for {
        product <- dataAccess.database.run(getProduct)
        _ = product match {
          case "MySQL" =>
            val exception = intercept[SerialException] {
              Await.result(dataAccess.addWorkflowStoreEntries(workflowStoreEntries), Duration.Inf)
            }
            exception should be(a[SerialException])
            exception.getMessage should
              be("Invalid arguments: position cannot be less than 1 or greater than the length of the SerialBlob")
          case _ => ()
        }
      } yield ()

      future.futureValue
    }

    it should "store and retrieve empty clobs" taggedAs DbmsTest in {
      val workflowUuid = WorkflowId.randomId().toString
      val callFqn = "call.fqn"
      val jobIndex = 1
      val jobAttempt = 1
      val jobSuccessful = false
      val jobStoreEntry = JobStoreEntry(workflowUuid, callFqn, jobIndex, jobAttempt, jobSuccessful, None, None, None)
      val jobStoreSimpletonEntries = Seq(
        JobStoreSimpletonEntry("empty", "".toClob, "WdlString"),
        JobStoreSimpletonEntry("aEntry", "a".toClob, "WdlString")
      )
      val jobStoreJoins = Seq(JobStoreJoin(jobStoreEntry, jobStoreSimpletonEntries))

      val future = for {
        _ <- dataAccess.addJobStores(jobStoreJoins)
        queried <- dataAccess.queryJobStores(workflowUuid, callFqn, jobIndex, jobAttempt)
        _ = {
          val jobStoreJoin = queried.get
          jobStoreJoin.jobStoreEntry.workflowExecutionUuid should be(workflowUuid)

          val emptyEntry = jobStoreJoin.jobStoreSimpletonEntries.find(_.simpletonKey == "empty").get
          emptyEntry.simpletonValue.toRawString should be("")

          val aEntry = jobStoreJoin.jobStoreSimpletonEntries.find(_.simpletonKey == "aEntry").get
          aEntry.simpletonValue.toRawString should be("a")
        }
        _ <- dataAccess.removeJobStores(Seq(workflowUuid))
      } yield ()
      future.futureValue
    }

    it should "store and retrieve empty blobs" taggedAs DbmsTest in {
      val testWorkflowState = "Testing"

      val emptyWorkflowUuid = WorkflowId.randomId().toString
      val emptyWorkflowStoreEntry = WorkflowStoreEntry(emptyWorkflowUuid, "{}".toClob, "{}".toClob, "{}".toClob,
        testWorkflowState, OffsetDateTime.now.toSystemTimestamp, Option(Array.empty[Byte]).toBlob, "{}".toClob)

      val noneWorkflowUuid = WorkflowId.randomId().toString
      val noneWorkflowStoreEntry = WorkflowStoreEntry(noneWorkflowUuid, "{}".toClob, "{}".toClob, "{}".toClob,
        testWorkflowState, OffsetDateTime.now.toSystemTimestamp, None, "{}".toClob)

      val aByte = 'a'.toByte
      val aByteWorkflowUuid = WorkflowId.randomId().toString
      val aByteWorkflowStoreEntry = WorkflowStoreEntry(aByteWorkflowUuid, "{}".toClob, "{}".toClob, "{}".toClob,
        testWorkflowState, OffsetDateTime.now.toSystemTimestamp, Option(Array(aByte)).toBlob, "{}".toClob)

      val workflowStoreEntries = Seq(emptyWorkflowStoreEntry, noneWorkflowStoreEntry, aByteWorkflowStoreEntry)

      val future = for {
        _ <- dataAccess.addWorkflowStoreEntries(workflowStoreEntries)
        queried <- dataAccess.queryWorkflowStoreEntries(Int.MaxValue, testWorkflowState, testWorkflowState)
        _ = {
          val emptyEntry = queried.find(_.workflowExecutionUuid == emptyWorkflowUuid).get
          emptyEntry.importsZip.toBytesOption should be(None)

          val noneEntry = queried.find(_.workflowExecutionUuid == noneWorkflowUuid).get
          noneEntry.importsZip.toBytesOption should be(None)

          val aByteEntry = queried.find(_.workflowExecutionUuid == aByteWorkflowUuid).get
          aByteEntry.importsZip.toBytesOption.get.toSeq should be(Seq(aByte))
        }
        _ <- dataAccess.removeWorkflowStoreEntry(emptyWorkflowUuid)
        _ <- dataAccess.removeWorkflowStoreEntry(noneWorkflowUuid)
        _ <- dataAccess.removeWorkflowStoreEntry(aByteWorkflowUuid)
      } yield ()
      future.futureValue
    }

    it should "close the database" taggedAs DbmsTest in {
      dataAccess.close()
    }
  }
}

object ServicesStoreSpec {
  // TODO PBE get rid of this after the migration of #789 has run.
  private val oldeTables = Seq(
    "EXECUTION",
    "EXECUTION_EVENT",
    "EXECUTION_INFO",
    "FAILURE_EVENT",
    "RUNTIME_ATTRIBUTES",
    "SYMBOL",
    "WORKFLOW_EXECUTION",
    "WORKFLOW_EXECUTION_AUX"
  )

  // strip the namespace from elems and their children
  private def stripNodeScope(node: Node): Node = {
    node match {
      case elem: Elem => elem.copy(scope = TopScope, child = elem.child map stripNodeScope)
      case other => other
    }
  }

  private def databaseForSchemaManager(schemaManager: String): SlickDatabase = {
    val databaseConfig = ConfigFactory.parseString(
      s"""
         |db.url = "jdbc:hsqldb:mem:$${uniqueSchema};shutdown=false;hsqldb.tx=mvcc"
         |db.driver = "org.hsqldb.jdbcDriver"
         |db.connectionTimeout = 3000
         |driver = "slick.driver.HsqldbDriver$$"
         |liquibase.updateSchema = false
         |""".stripMargin)
    val database = new SlickDatabase(databaseConfig)
    schemaManager match {
      case "liquibase" =>
        database withConnection LiquibaseUtils.updateSchema
      case "slick" =>
        SlickDatabase.createSchema(database)
    }
    database
  }

  private def compare[ReferenceProfile <: JdbcProfile, ComparisonProfile <: JdbcProfile, T]
  (referenceProfile: ReferenceProfile,
   referenceDatabase: ReferenceProfile#Backend#Database,
   comparisonProfile: ComparisonProfile,
   comparisonDatabase: ComparisonProfile#Backend#Database)(block: DiffResult => T)
  (implicit executor: ExecutionContext): T = {

    withConnections(referenceProfile, referenceDatabase, comparisonProfile, comparisonDatabase) {
      LiquibaseUtils.compare(_, _)(block)
    }
  }

  /**
    * Lends a connection to a block of code.
    *
    * @param profile  The slick jdbc profile for accessing the database.
    * @param database The database to use for the connection.
    * @param block    The block of code to run over the connection.
    * @tparam Profile The slick jdbc profile for accessing the database.
    * @tparam T       The return type of the block.
    * @return The return value of the block.
    */
  private def withConnection[Profile <: JdbcProfile, T](profile: Profile, database: Profile#Backend#Database)
                                                       (block: Connection => T): T = {
    /*
     TODO: Should this withConnection() method have a (implicit?) timeout parameter, that it passes on to Await.result?
     If we run completely asynchronously, nest calls to withConnection, and then call flatMap, the outer connection may
     already be closed before an inner block finishes running.
     */
    Await.result(database.run(profile.api.SimpleDBIO(context => block(context.connection))), Duration.Inf)
  }

  /**
    * Lends two connections to a block of code.
    *
    * @param profile1  The slick jdbc profile for accessing the first database.
    * @param database1 The database to use for the first connection.
    * @param profile2  The slick jdbc profile for accessing the second database.
    * @param database2 The database to use for the second connection.
    * @param block     The block of code to run over the first and second connections.
    * @tparam Profile1 The slick jdbc profile for accessing the first database.
    * @tparam Profile2 The slick jdbc profile for accessing the second database.
    * @tparam T        The return type of the block.
    * @return The return value of the block.
    */
  private def withConnections[Profile1 <: JdbcProfile, Profile2 <: JdbcProfile, T]
  (profile1: Profile1, database1: Profile1#Backend#Database, profile2: Profile2, database2: Profile2#Backend#Database)
  (block: (Connection, Connection) => T): T = {
    withConnection(profile1, database1) { connection1 =>
      withConnection(profile2, database2) { connection2 =>
        block(connection1, connection2)
      }
    }
  }

  private val SnakeRegex = "_([a-z])".r

  private def snakeToCamel(value: String): String = {
    SnakeRegex.replaceAllIn(value.toLowerCase, _.group(1).toUpperCase)
  }

  private def snakeAbbreviate(value: String): String = {
    SnakeRegex.findAllMatchIn("_" + value.toLowerCase).map(_.group(1)).mkString("").toUpperCase
  }

  private val SlickPrimaryKeyRegex = """SYS_PK_\d+""".r

  private def isGenerated(primaryKey: MPrimaryKey): Boolean = {
    primaryKey.pkName.get match {
      case SlickPrimaryKeyRegex(_*) => true
      case _ => false
    }
  }

  private val LiquibasePrimaryKeyIndexRegex = """SYS_IDX_PK_[A-Z_]+_\d+""".r
  private val SlickPrimaryKeyIndexRegex = """SYS_IDX_SYS_PK_\d+_\d+""".r
  private val SlickForeignKeyIndexRegex = """SYS_IDX_\d+""".r

  private def isGenerated(index: MIndexInfo): Boolean = {
    index.indexName.get match {
      case LiquibasePrimaryKeyIndexRegex(_*) => true
      case SlickPrimaryKeyIndexRegex(_*) => true
      case SlickForeignKeyIndexRegex(_*) => true
      case _ => false
    }
  }

  private def tableClassName(tableName: String) = s"cromwell.database.sql.tables.$tableName"

  private def slickClassName(tableName: String) =
    s"cromwell.database.slick.tables.${tableName}Component$$${tableName.replace("Entry", "Entries")}"

  private def getIndexName(index: MIndexInfo) = index.indexName.get.replaceAll("(^SYS_IDX_|_\\d+$)", "")

  case class TableClass(tableName: String) {
    private def getClass(name: String): Try[Class[_]] = Try(Class.forName(name))

    private lazy val tableColumns = getClass(tableClassName(tableName)).map(_.getDeclaredFields).getOrElse(Array.empty)
    private lazy val slickMapping = getClass(slickClassName(tableName)).map(_.getDeclaredMethods).getOrElse(Array.empty)

    def existsTableField(name: String): Boolean = tableColumns.exists(_.getName == name)

    def existsSlickMapping(name: String): Boolean = slickMapping.exists(_.getName == name)
  }

  case class DatabaseItem(tableName: String, itemName: String)

  case class SchemaMetadata(tableMetadata: Seq[MTable], columnMetadata: Seq[MColumn], indexMetadata: Seq[MIndexInfo],
                            primaryKeyMetadata: Seq[MPrimaryKey], foreignKeyMetadata: Seq[MForeignKey]) {
    lazy val tables: Seq[TableClass] = tableMetadata.map({ table =>
      val tableName = snakeToCamel(table.name.name).capitalize
      TableClass(tableName)
    }).distinct

    lazy val columns: Seq[DatabaseItem] = columnMetadata.map({ column =>
      val tableName = snakeToCamel(column.table.name).capitalize
      val columnName = snakeToCamel(column.name)
      DatabaseItem(tableName, columnName)
    }).distinct

    lazy val indexes: Seq[DatabaseItem] = indexMetadata.map({ index =>
      val tableName = snakeToCamel(index.table.name).capitalize
      val indexName = snakeToCamel(getIndexName(index))
      DatabaseItem(tableName, indexName)
    }).distinct

    lazy val foreignKeys: Seq[DatabaseItem] = foreignKeyMetadata.map({ foreignKey =>
      val tableName = snakeToCamel(foreignKey.fkTable.name).capitalize
      val indexName = snakeToCamel(foreignKey.fkName.get)
      DatabaseItem(tableName, indexName)
    }).distinct

    lazy val slickItems: Seq[DatabaseItem] = columns ++ indexes ++ foreignKeys

    def existsTableItem(tableItem: DatabaseItem): Boolean = {
      tables.find(_.tableName == tableItem.tableName).exists(_.existsTableField(tableItem.itemName))
    }

    def existsSlickMapping(tableItem: DatabaseItem): Boolean = {
      tables.find(_.tableName == tableItem.tableName).exists(_.existsSlickMapping(tableItem.itemName))
    }
  }
}
