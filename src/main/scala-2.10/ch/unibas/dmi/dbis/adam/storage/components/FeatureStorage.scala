package ch.unibas.dmi.dbis.adam.storage.components

import ch.unibas.dmi.dbis.adam.config.FieldNames
import ch.unibas.dmi.dbis.adam.entity.Entity.EntityName
import ch.unibas.dmi.dbis.adam.entity.Tuple.TupleID
import ch.unibas.dmi.dbis.adam.main.AdamContext
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SaveMode}

/**
  * adamtwo
  *
  * Ivan Giangreco
  * August 2015
  */
trait FeatureStorage {
  /**
    * Create the entity in the feature storage.
    *
    * @param entityname
    * @return true on success
    */
  def create(entityname: EntityName)(implicit ac: AdamContext): Boolean = {
    val featureSchema = StructType(
      Seq(
        StructField(FieldNames.idColumnName, LongType, false),
        StructField(FieldNames.internFeatureColumnName, ArrayType(FloatType), false)
      )
    )
    val df = ac.sqlContext.createDataFrame(ac.sc.emptyRDD[Row], featureSchema)
    write(entityname, df, SaveMode.Overwrite)
  }

  /**
    * Read entity from feature storage.
    *
    * @param entityname
    * @param filter
    * @return
    */
  def read(entityname: EntityName, filter: Option[Set[TupleID]] = None)(implicit ac : AdamContext): DataFrame

  /**
    * Count the number of tuples in the feature storage.
    *
    * @param entityname
    * @return
    */
  def count(entityname: EntityName)(implicit ac: AdamContext): Int

  /**
    * Write entity to the feature storage.
    *
    * @param entityname
    * @param df
    * @param mode
    * @return true on success
    */
  def write(entityname: EntityName, df: DataFrame, mode: SaveMode = SaveMode.Append)(implicit ac: AdamContext): Boolean

  /**
    * Drop the entity from the feature storage
    *
    * @param entityname
    * @return true on success
    */
  def drop(entityname: EntityName)(implicit ac: AdamContext): Boolean
}

