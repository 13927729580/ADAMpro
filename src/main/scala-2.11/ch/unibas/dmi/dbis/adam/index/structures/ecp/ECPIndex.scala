package ch.unibas.dmi.dbis.adam.index.structures.ecp

import ch.unibas.dmi.dbis.adam.datatypes.feature.Feature
import Feature.FeatureVector
import ch.unibas.dmi.dbis.adam.index.Index.{IndexName, IndexTypeName}
import ch.unibas.dmi.dbis.adam.index._
import ch.unibas.dmi.dbis.adam.index.structures.IndexStructures
import ch.unibas.dmi.dbis.adam.main.SparkStartup
import ch.unibas.dmi.dbis.adam.entity.Entity.EntityName
import ch.unibas.dmi.dbis.adam.entity.Tuple.TupleID
import org.apache.spark.TaskContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame

import scala.collection.immutable.HashSet
import scala.collection.mutable.ListBuffer

/**
 * adamtwo
 *
 * Ivan Giangreco
 * October 2015
 */
class ECPIndex(val indexname: IndexName, val entityname: EntityName, protected val df: DataFrame, private[index] val metadata : ECPIndexMetaData)
  extends Index[LongIndexTuple] {

  override val indextypename: IndexTypeName = IndexStructures.ECP
  override val confidence = 0.toFloat

  override protected def rdd : RDD[LongIndexTuple] = df.map(r => r : LongIndexTuple)

  override def scan(data : RDD[LongIndexTuple], q : FeatureVector, options : Map[String, Any], k : Int): HashSet[TupleID] = {
    val centroids = metadata.leaders.map(l => {
      (l.tid, metadata.distance(q, l.value))
    }).sortBy(_._2).map(_._1)

    val results = SparkStartup.sc.runJob(data, (context : TaskContext, tuplesIt : Iterator[LongIndexTuple]) => {
      var results = ListBuffer[TupleID]()
      var i = 0
      while(i < centroids.length && results.length < k){
        results ++= tuplesIt.filter(_.value == centroids(i)).map(_.tid).toSeq
        i += 1
      }
      results.toSeq
    }).flatten

    HashSet(results.toList : _*)
  }
}

object ECPIndex {
  def apply(indexname: IndexName, tablename: EntityName, data: DataFrame, meta: Any): ECPIndex = {
    val indexMetaData = meta.asInstanceOf[ECPIndexMetaData]
    new ECPIndex(indexname, tablename, data, indexMetaData)
  }
}