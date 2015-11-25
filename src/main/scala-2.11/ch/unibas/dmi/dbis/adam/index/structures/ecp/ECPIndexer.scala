package ch.unibas.dmi.dbis.adam.index.structures.ecp

import ch.unibas.dmi.dbis.adam.index.Index.{IndexName, IndexTypeName}
import ch.unibas.dmi.dbis.adam.index._
import ch.unibas.dmi.dbis.adam.index.structures.IndexStructures
import ch.unibas.dmi.dbis.adam.main.SparkStartup
import ch.unibas.dmi.dbis.adam.query.distance.DistanceFunction
import ch.unibas.dmi.dbis.adam.entity.Entity.EntityName
import org.apache.spark.rdd.RDD

/**
 * adamtwo
 *
 * Ivan Giangreco
 * October 2015
 */
class ECPIndexer(distance : DistanceFunction) extends IndexGenerator with Serializable {
  override val indextypename: IndexTypeName = IndexStructures.ECP

  /**
   *
   * @param indexname
   * @param entityname
   * @param data
   * @return
   */
  override def index(indexname: IndexName, entityname: EntityName, data: RDD[IndexerTuple]): Index[_ <: IndexTuple] = {
    val n = data.countApprox(5000).getFinalValue().mean.toInt
    val leaders = data.takeSample(true, math.sqrt(n).toInt)

    val indexdata = data.map(datum => {
        val minTID = leaders.map({ l =>
          (l.tid, distance.apply(datum.value, l.value))
        }).minBy(_._2)._1

        LongIndexTuple(datum.tid, minTID)
      })

    import SparkStartup.sqlContext.implicits._
    new ECPIndex(indexname, entityname, indexdata.toDF, ECPIndexMetaData(leaders.toArray.toSeq, distance))
  }
}

object ECPIndexer {
  def apply(properties : Map[String, String] = Map[String, String](), distance : DistanceFunction, data: RDD[IndexerTuple]) : IndexGenerator = new ECPIndexer(distance)
}