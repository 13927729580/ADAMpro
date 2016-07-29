package ch.unibas.dmi.dbis.adam.index

import ch.unibas.dmi.dbis.adam.entity.Entity._
import ch.unibas.dmi.dbis.adam.index.Index.IndexName
import ch.unibas.dmi.dbis.adam.index.structures.IndexTypes
import ch.unibas.dmi.dbis.adam.utils.Logging
import org.apache.spark.rdd.RDD

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
trait IndexGenerator extends Serializable with Logging {
  /**
    *
    * @return
    */
  def indextypename: IndexTypes.IndexType

  /**
    *
    * @param indexname name of index
    * @param entityname name of entity
    * @param data data to index
    * @return
    */
  def index(indexname : IndexName, entityname : EntityName, data: RDD[IndexingTaskTuple[_]]):  Index
}

object IndexGenerator {
  private[index] val MINIMUM_NUMBER_OF_TUPLE = 1000
}