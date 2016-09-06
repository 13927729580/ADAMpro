package ch.unibas.dmi.dbis.adam.storage.engine.instances

import java.sql.{Connection, DriverManager}
import java.util.Properties

import ch.unibas.dmi.dbis.adam.config.AdamConfig
import ch.unibas.dmi.dbis.adam.entity.AttributeDefinition
import ch.unibas.dmi.dbis.adam.main.AdamContext
import ch.unibas.dmi.dbis.adam.storage.engine.RelationalDatabaseEngine
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row, SaveMode}

import scala.util.{Failure, Success, Try}

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * June 2016
  */
class PostgresqlEngine(private val url: String, private val user: String, private val password: String, protected val schema : String = "public") extends RelationalDatabaseEngine with Serializable  {
  //TODO: check if changing schema breaks tests!

  Class.forName("org.postgresql.Driver")
  init()

  /**
    * Opens a connection to the PostgreSQL database.
    *
    * @return
    */
  protected def openConnection(): Connection = {
    val connection = DriverManager.getConnection(url, props)
    connection.setSchema(schema)
    connection
  }

  protected def init() {
    try {
      val connection = openConnection()

      val createSchemaStmt = s"""CREATE SCHEMA IF NOT EXISTS $schema;""".stripMargin

      connection.createStatement().executeUpdate(createSchemaStmt)
    } catch {
      case e: Exception =>
        log.error("fatal error when setting up relational engine", e)
    }
  }

  lazy val props = {
    val props = new Properties()
    props.put("url", url)
    props.put("user", user)
    props.put("password", password)
    props.put("driver", "org.postgresql.Driver")
    props.put("currentSchema", schema)
    props
  }

  lazy val propsMap = props.keySet().toArray.map(key => key.toString -> props.get(key).toString).toMap

  override def create(tablename: String, fields: Seq[AttributeDefinition])(implicit ac: AdamContext): Try[Option[String]] = {
    try {
      log.debug("postgresql create operation")

      val structFields = fields.map {
        field => StructField(field.name, field.fieldtype.datatype)
      }.toSeq

      val df = ac.sqlContext.createDataFrame(ac.sc.emptyRDD[Row], StructType(structFields))

      val tableStmt = write(tablename, df)

      if (tableStmt.isFailure) {
        return Failure(tableStmt.failed.get)
      }

      //make fields unique
      val uniqueStmt = fields.filter(_.unique).map {
        field =>
          val fieldname = field.name
          s"""ALTER TABLE $tablename ADD UNIQUE ($fieldname)""".stripMargin
      }.mkString("; ")

      //add index to table
      val indexedStmt = fields.filter(_.indexed).map {
        field =>
          val fieldname = field.name
          s"""CREATE INDEX ON $tablename ($fieldname)""".stripMargin
      }.mkString("; ")

      //add primary key
      val pkfield = fields.filter(_.pk)
      assert(pkfield.size <= 1)
      val pkStmt = pkfield.map {
        case field =>
          val fieldname = field.name
          s"""ALTER TABLE $tablename ADD PRIMARY KEY ($fieldname)""".stripMargin
      }.mkString("; ")

      val connection = openConnection()
      connection.createStatement().executeUpdate(uniqueStmt + "; " + indexedStmt + "; " + pkStmt)

      Success(Some(tablename))
    } catch {
      case e: Exception =>
        Failure(e)
    }
  }


  /**
    *
    * @param tablename name of table
    * @return
    */
  override def exists(tablename: String)(implicit ac: AdamContext): Try[Boolean] = {
    log.debug("postgresql exists operation")

    try {
      val existsSql = s"""SELECT EXISTS ( SELECT 1 FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'public' AND c.relname = '$tablename')""".stripMargin
      val results = openConnection().createStatement().executeQuery(existsSql)
      results.next()
      Success(results.getBoolean(1))
    } catch {
      case e: Exception =>
        Failure(e)
    }
  }


  override def read(tablename: String)(implicit ac: AdamContext): Try[DataFrame] = {
    log.debug("postgresql read operation")

    try {
      val df = ac.sqlContext.read.format("jdbc").jdbc(url, tablename, props) //TODO: possibly adjust in here for partitioning
      Success(df.repartition(AdamConfig.defaultNumberOfPartitions))
    } catch {
      case e: Exception =>
        Failure(e)
    }
  }

  override def write(tablename: String, df: DataFrame, mode : SaveMode = SaveMode.Append)(implicit ac: AdamContext): Try[Void] = {
    log.debug("postgresql write operation")

    try {
      df.write.mode(mode).jdbc(url, tablename, props)
      Success(null)
    } catch {
      case e: Exception =>
        Failure(e)
    }
  }

  override def drop(tablename: String)(implicit ac: AdamContext): Try[Void] = {
    log.debug("postgresql drop operation")

    try {
      val dropTableSql = s"""DROP TABLE $tablename""".stripMargin
      openConnection().createStatement().executeUpdate(dropTableSql)
      Success(null)
    } catch {
      case e: Exception =>
        Failure(e)
    }
  }

  override def equals(other: Any): Boolean =
    other match {
      case that: PostgresqlEngine => that.url.equals(url) && that.user.equals(user) && that.password.equals(password)
      case _ => false
    }

  override def hashCode(): Int = {
    val prime = 31
    var result = 1
    result = prime * result + url.hashCode
    result = prime * result + user.hashCode
    result = prime * result + password.hashCode
    result
  }
}