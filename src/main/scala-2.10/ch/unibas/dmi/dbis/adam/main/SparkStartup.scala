package ch.unibas.dmi.dbis.adam.main

import ch.unibas.dmi.dbis.adam.config.AdamConfig
import ch.unibas.dmi.dbis.adam.datatypes.bitString.{BitString, MinimalBitString}
import ch.unibas.dmi.dbis.adam.datatypes.feature.FeatureVectorWrapper
import ch.unibas.dmi.dbis.adam.storage.components.{FeatureStorage, IndexStorage, MetadataStorage}
import ch.unibas.dmi.dbis.adam.storage.engine.{CassandraDataStorage, ParquetDataStorage, PostgresDataStorage}
import org.apache.spark.sql.hive.HiveContext
import org.apache.spark.{SparkConf, SparkContext}

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
object SparkStartup {
  val sparkConfig = new SparkConf().setAppName("ADAMtwo")
    .set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    .set("spark.driver.maxResultSize", "12000m")
    .set("spark.kryoserializer.buffer.max", "2047m")
    .set("spark.kryoserializer.buffer", "2047")
    .set("spark.akka.frameSize", "1024")
    .set("spark.scheduler.mode", "FAIR")
    .set("spark.scheduler.allocation.file", "/usr/local/spark/conf/fairscheduler.xml")
    .set("spark.cassandra.connection.host", AdamConfig.cassandraUrl)
    .set("spark.cassandra.connection.port", AdamConfig.cassandraPort)
    .set("spark.cassandra.auth.username", AdamConfig.cassandraUsername)
    .set("spark.cassandra.auth.password", AdamConfig.cassandraPassword)
    .registerKryoClasses(Array(classOf[BitString[_]], classOf[MinimalBitString], classOf[FeatureVectorWrapper]))

  val sc = new SparkContext(sparkConfig)
  val sqlContext = new HiveContext(sc)


  val featureStorage : FeatureStorage = CassandraDataStorage
  val metadataStorage : MetadataStorage = PostgresDataStorage
  val indexStorage: IndexStorage = ParquetDataStorage
}