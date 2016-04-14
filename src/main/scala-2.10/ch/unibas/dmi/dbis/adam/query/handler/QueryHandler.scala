package ch.unibas.dmi.dbis.adam.query.handler

import java.util.concurrent.TimeUnit

import ch.unibas.dmi.dbis.adam.config.{AdamConfig, FieldNames}
import ch.unibas.dmi.dbis.adam.entity.Entity._
import ch.unibas.dmi.dbis.adam.entity.Tuple.TupleID
import ch.unibas.dmi.dbis.adam.exception.{QueryNotCachedException, IndexNotExistingException}
import ch.unibas.dmi.dbis.adam.index.Index
import ch.unibas.dmi.dbis.adam.index.Index._
import ch.unibas.dmi.dbis.adam.main.SparkStartup
import ch.unibas.dmi.dbis.adam.query.Result
import ch.unibas.dmi.dbis.adam.query.datastructures.{QueryCacheOptions, QueryLRUCache, QueryExpression, RunDetails}
import ch.unibas.dmi.dbis.adam.query.handler.QueryHints._
import ch.unibas.dmi.dbis.adam.query.progressive._
import ch.unibas.dmi.dbis.adam.query.query.{BooleanQuery, NearestNeighbourQuery}
import ch.unibas.dmi.dbis.adam.storage.engine.CatalogOperator
import com.google.common.cache.{CacheLoader, CacheBuilder}
import org.apache.log4j.Logger
import org.apache.spark.sql.{Row, DataFrame}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

/**
  * adamtwo
  *
  * Ivan Giangreco
  * November 2015
  */
object QueryHandler {
  val log = Logger.getLogger(getClass.getName)

  /**
    * Performs a standard query, built up by a nearest neighbour query and a boolean query.
    *
    * @param entityname
    * @param hint         query hint, for the executor to know which query path to take (e.g., sequential query or index query)
    * @param nnq          information for nearest neighbour query
    * @param bq           information for boolean query
    * @param withMetadata whether or not to retrieve corresponding metadata
    * @param id           query id
    * @param cache        caching options
    * @return
    */
  def query(entityname: EntityName, hint: Option[QueryHint], nnq: Option[NearestNeighbourQuery], bq: Option[BooleanQuery], withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())): DataFrame = {
    if (id.isDefined && cache.isDefined && cache.get.useCached) {
      val cached = getFromQueryCache(id)
      if (cached.isSuccess) {
        return cached.get
      }
    }

    if (nnq.isEmpty) {
      val res = BooleanQueryHandler.getData(entityname, bq)

      if (res.isDefined) {
        return res.get
      } else {
        val rdd = SparkStartup.sc.emptyRDD[Row]
        return SparkStartup.sqlContext.createDataFrame(rdd, Result.resultSchema)
      }
    }

    val indexes: Map[IndexTypeName, Seq[IndexName]] = CatalogOperator.listIndexes(entityname).groupBy(_._2).mapValues(_.map(_._1))

    log.debug("try choosing plan based on hints")
    var plan = choosePlan(entityname, indexes, hint, nnq)

    if (!plan.isDefined) {
      log.debug("no query plan chosen, go to fallback")
      plan = choosePlan(entityname, indexes, Some(QueryHints.FALLBACK_HINTS), nnq)
    }

    val res = plan.get(nnq.get, bq, withMetadata, id, cache)

    if (id.isDefined && cache.isDefined && cache.get.putInCache) {
      QueryLRUCache.put(id.get, res)
    }

    res
  }

  /**
    * Chooses the query plan to use based on the given hint, the available indexes, etc.
    *
    * @param entityname
    * @param indexes
    * @param hint
    * @return
    */
  private def choosePlan(entityname: EntityName, indexes: Map[IndexTypeName, Seq[IndexName]], hint: Option[QueryHint], nnq: Option[NearestNeighbourQuery]): Option[(NearestNeighbourQuery, Option[BooleanQuery], Boolean, Option[String], Option[QueryCacheOptions]) => DataFrame] = {
    if (!hint.isDefined) {
      log.debug("no execution plan hint")
      return None
    }

    if (!nnq.isDefined) {
      log.debug("nnq is not defined")
      return None
    }

    hint.get match {
      case iqh: IndexQueryHint => {
        log.debug("index execution plan hint")
        //index scan
        val indexChoice = indexes.get(iqh.structureType)

        if (indexChoice.isDefined) {
          //TODO: use old measurements for choice rather than head
          val index = indexChoice.get.map(indexname => Index.loadIndexMetaData(indexname).get).filter(_.isQueryConform(nnq.get)).head
          return Option(specifiedIndexQuery(index.indexname))
        } else {
          return None
        }
      }
      case SEQUENTIAL_QUERY =>
        log.debug("sequential execution plan hint")
        return Some(sequentialQuery(entityname)) //sequential

      case cqh: ComplexQueryHint => {
        log.debug("compound query hint, re-iterate sub-hints")

        //complex query hint
        val hints = cqh.hints
        var i = 0

        while (i < hints.length) {
          val plan = choosePlan(entityname, indexes, Some(hints(i)), nnq)
          if (plan.isDefined) return plan
        }

        return None
      }
      case _ => None //default
    }
  }


  case class StandardQueryHolder(entityname: EntityName, hint: Option[QueryHint], nnq: Option[NearestNeighbourQuery], bq: Option[BooleanQuery], withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())) extends QueryExpression(id) {
    override protected def run(filter: Option[Set[TupleID]]): DataFrame = {
      val abq = bq.getOrElse(BooleanQuery())
      if (filter.isDefined) {
        abq.append(filter.get)
      }
      query(entityname, hint, nnq, bq, withMetadata, id, cache)
    }
  }

  /**
    * Performs a sequential query, i.e., without using any index structure.
    *
    * @param entityname
    * @param nnq          information for nearest neighbour query
    * @param bq           information for boolean query
    * @param withMetadata whether or not to retrieve corresponding metadata
    * @return
    */
  def sequentialQuery(entityname: EntityName)(nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())): DataFrame = {
    log.debug("sequential query gets filter")

    if (cache.isDefined && cache.get.useCached) {
      val cached = getFromQueryCache(id)
      if (cached.isSuccess) {
        return cached.get
      }
    }

    val filter = if (bq.isDefined) {
      getFilter(entityname, bq.get)
    } else {
      None
    }

    log.debug("sequential query performs kNN query")
    var res = NearestNeighbourQueryHandler.sequential(entityname, nnq, filter)

    if (withMetadata) {
      log.debug("join metadata to results of sequential query")
      res = joinWithMetadata(entityname, res)
    }

    if (id.isDefined && cache.isDefined && cache.get.putInCache) {
      QueryLRUCache.put(id.get, res)
    }

    res
  }

  case class SequentialQueryHolder(entityname: EntityName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())) extends QueryExpression(id) {
    override protected def run(filter: Option[Set[TupleID]]): DataFrame = {
      val abq = bq.getOrElse(BooleanQuery())
      if (filter.isDefined) {
        abq.append(filter.get)
      }
      sequentialQuery(entityname)(nnq, Option(abq), withMetadata, id, cache)
    }
  }


  /**
    * Performs a index-based query. Chooses the index of the entity based on the given type.
    *
    * @param entityname name of the entity
    * @param indextype  type of the index
    * @param nnq
    * @param bq
    * @param withMetadata
    * @return
    */
  def indexQuery(entityname: EntityName, indextype: IndexTypeName)(nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())): DataFrame = {
    if (cache.isDefined && cache.get.useCached) {
      val cached = getFromQueryCache(id)
      if (cached.isSuccess) {
        return cached.get
      }
    }

    val indexes = Index.list(entityname, indextype).map(_._1)

    if (indexes.isEmpty) {
      log.error("requested index of type " + indextype + " but not index of this type was found")
      throw new IndexNotExistingException()
    }

    val res = specifiedIndexQuery(indexes.head)(nnq, bq, withMetadata)

    if (id.isDefined && cache.isDefined && cache.get.putInCache) {
      QueryLRUCache.put(id.get, res)
    }

    res
  }

  case class IndexQueryHolder(entityname: EntityName, indextypename: IndexTypeName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())) extends QueryExpression(id) {
    override protected def run(filter: Option[Set[TupleID]]): DataFrame = {
      val abq = bq.getOrElse(BooleanQuery())
      if (filter.isDefined) {
        abq.append(filter.get)
      }
      indexQuery(entityname, indextypename)(nnq, Option(abq), withMetadata, id, cache)
    }
  }


  /**
    * Performs a index-based query.
    *
    * @param indexname
    * @param nnq          information for nearest neighbour query
    * @param bq           information for boolean query
    * @param withMetadata whether or not to retrieve corresponding metadata
    * @return
    */
  def specifiedIndexQuery(indexname: IndexName)(nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())): DataFrame = {
    if (cache.isDefined && cache.get.useCached) {
      val cached = getFromQueryCache(id)
      if (cached.isSuccess) {
        return cached.get
      }
    }

    val index = Index.loadIndexMetaData(indexname)
    val entityname = index.get.entityname

    log.debug("index query gets filter")
    val filter = if (bq.isDefined) {
      getFilter(entityname, bq.get)
    } else {
      None
    }

    if (!index.get.isQueryConform(nnq)) {
      log.warn("index is not conform with kNN query")
    }

    log.debug("index query performs kNN query")
    var res = NearestNeighbourQueryHandler.indexQuery(indexname, nnq, filter)

    if (withMetadata) {
      log.debug("join metadata to results of index query")
      res = joinWithMetadata(entityname, res)
    }

    if (id.isDefined && cache.isDefined && cache.get.putInCache) {
      QueryLRUCache.put(id.get, res)
    }

    res
  }

  case class SpecifiedIndexQueryHolder(indexname: IndexName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())) extends QueryExpression(id) {
    override protected def run(filter: Option[Set[TupleID]]): DataFrame = {
      val abq = bq.getOrElse(BooleanQuery())
      if (filter.isDefined) {
        abq.append(filter.get)
      }
      specifiedIndexQuery(indexname)(nnq, Option(abq), withMetadata, id, cache)
    }
  }

  /**
    * Performs a progressive query, i.e., all indexes and sequential search are started at the same time and results are returned as soon
    * as they are available. When a precise result is returned, the whole query is stopped.
    *
    * @param entityname
    * @param nnq          information for nearest neighbour query
    * @param bq           information for boolean query
    * @param onComplete   operation to perform as soon as one index returns results
    *                     (the operation takes parameters:
    *                     - status value
    *                     - result (as DataFrame)
    *                     - confidence score denoting how confident you can be in the results
    *                     - further information (key-value)
    * @param withMetadata whether or not to retrieve corresponding metadata
    * @return a tracker for the progressive query
    */
  def progressiveQuery[U](entityname: EntityName)(
    nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], onComplete: (ProgressiveQueryStatus.Value, DataFrame, Float, String, Map[String, String]) => U,
    withMetadata: Boolean, id: Option[String] = None)
  : ProgressiveQueryStatusTracker = {
    //TODO: use cache

    val onCompleteFunction = if (withMetadata) {
      log.debug("join metadata to results of progressive query")
      (pqs: ProgressiveQueryStatus.Value, res: DataFrame, conf: Float, source: String, info: Map[String, String]) => onComplete(pqs, joinWithMetadata(entityname, res), conf, source, info)
    } else {
      onComplete
    }

    log.debug("progressive query gets filter")
    val filter = if (bq.isDefined) {
      getFilter(entityname, bq.get)
    } else {
      None
    }

    log.debug("progressive query performs kNN query")
    NearestNeighbourQueryHandler.progressiveQuery(entityname, nnq, filter, onCompleteFunction)
  }

  case class ProgressiveQueryHolder[U](entityname: EntityName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], onComplete: (ProgressiveQueryStatus.Value, DataFrame, Float, String, Map[String, String]) => U, withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions()))


  /**
    * Performs a timed progressive query, i.e., it performs the query for a maximum of the given time limit and returns then the best possible
    * available results.
    *
    * @param entityname
    * @param nnq          information for nearest neighbour query
    * @param bq           information for boolean query
    * @param timelimit    maximum time to wait
    * @param withMetadata whether or not to retrieve corresponding metadata
    * @return the results available together with a confidence score
    */
  def timedProgressiveQuery(entityname: EntityName)(nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], timelimit: Duration, withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())): (DataFrame, Float, String) = {
    log.debug("timed progressive query gets filter")
    //TODO: use cache

    val filter = if (bq.isDefined) {
      getFilter(entityname, bq.get)
    } else {
      None
    }

    log.debug("timed progressive query performs kNN query")
    val results = NearestNeighbourQueryHandler.timedProgressiveQuery(entityname, nnq, filter, timelimit)
    var res = results.results
    if (withMetadata) {
      log.debug("join metadata to results of timed progressive query")
      res = joinWithMetadata(entityname, res)
    }

    (res, results.confidence, results.source)
  }

  case class TimedProgressiveQueryHolder(entityname: EntityName, nnq: NearestNeighbourQuery, bq: Option[BooleanQuery], timelimit: Duration, withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions()))


  /**
    * Performs a compound query.
    *
    * @param entityname
    * @param nnq          information for nearest neighbour query
    * @param expr         expression to evaluate
    * @param withMetadata whether or not to retrieve corresponding metadata
    */
  def compoundQuery(entityname: EntityName)(nnq: NearestNeighbourQuery, expr: QueryExpression, indexOnly: Boolean, withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())): DataFrame = {
    if (cache.isDefined && cache.get.useCached) {
      val cached = getFromQueryCache(id)
      if (cached.isSuccess) {
        return cached.get
      }
    }

    var res = CompoundQueryHolder(entityname, nnq, expr, indexOnly, withMetadata).evaluate()

    if (withMetadata) {
      log.debug("join metadata to results of index query")
      res = joinWithMetadata(entityname, res)
    }

    if (id.isDefined && cache.isDefined && cache.get.putInCache) {
      QueryLRUCache.put(id.get, res)
    }

    res
  }

  case class CompoundQueryHolder(entityname: EntityName, nnq: NearestNeighbourQuery, expr: QueryExpression, indexOnly: Boolean = false, withMetadata: Boolean, id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())) extends QueryExpression(id) {
    private var run = false

    override protected def run(filter: Option[Set[TupleID]]): DataFrame = {
      val results = if (indexOnly) {
        CompoundQueryHandler.indexOnlyQuery(entityname)(expr, withMetadata)
      } else {
        CompoundQueryHandler.indexQueryWithResults(entityname)(nnq, expr, withMetadata)
      }
      run = true
      results
    }

    /**
      *
      * @return
      */
    def provideRunInfo(): Seq[RunDetails] = {
      if (!run) {
        log.warn("please run compound query before collecting run information")
        return Seq()
      } else {
        val start = getRunDetails(new ListBuffer())
        expr.getRunDetails(start).toSeq
      }
    }

    /**
      *
      * @param info
      * @return
      */
    override private[adam] def getRunDetails(info: ListBuffer[RunDetails]) = {
      super.getRunDetails(info)
    }
  }

  /**
    * Performs a Boolean query on the structured metadata.
    *
    * @param entityname
    * @param bq information for boolean query, if not specified all data is returned
    * @param id
    * @param cache
    */
  def booleanQuery(entityname: EntityName)(bq: Option[BooleanQuery], id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())): DataFrame = {
    val res = BooleanQueryHandler.getData(entityname, bq)

    if (res.isDefined) {
      return res.get
    } else {
      val rdd = SparkStartup.sc.emptyRDD[Row]
      return SparkStartup.sqlContext.createDataFrame(rdd, Result.resultSchema)
    }
  }

  case class BooleanQueryHolder(entityname: EntityName, bq: Option[BooleanQuery], id: Option[String] = None, cache: Option[QueryCacheOptions] = Some(QueryCacheOptions())) extends QueryExpression(id) {
    override protected def run(filter: Option[Set[TupleID]]): DataFrame = {
      val abq = bq.getOrElse(BooleanQuery())
      if (filter.isDefined) {
        abq.append(filter.get)
      }
      booleanQuery(entityname)(Option(abq), id, cache)
    }
  }


  /**
    * Creates a filter that is applied on the nearest neighbour search based on the Boolean query.
    *
    * @param entityname
    * @param bq
    * @return
    */
  private def getFilter(entityname: EntityName, bq: BooleanQuery): Option[Set[TupleID]] = {
    var filter = Set[TupleID]()

    val results = BooleanQueryHandler.getIds(entityname, bq)
    if (results.isDefined) {
      filter ++= results.get.map(r => r.getAs[Long](FieldNames.idColumnName)).collect().toSet
    }

    val prefilter = bq.tidFilter
    if (prefilter.isDefined) {
      if (filter.isEmpty) {
        filter = prefilter.get
      } else {
        filter = filter.intersect(prefilter.get)
      }
    }

    if (!filter.isEmpty) {
      Some(filter)
    } else {
      None
    }
  }


  /**
    * Joins the results from the nearest neighbour query with the metadata.
    *
    * @param entityname
    * @param res
    * @return
    */
  private[handler] def joinWithMetadata(entityname: EntityName, res: DataFrame): DataFrame = {
    val mdRes = BooleanQueryHandler.getData(entityname, res.select(FieldNames.idColumnName).collect().map(r => r.getLong(0)).toSet)

    if (mdRes.isDefined) {
      mdRes.get.join(res, FieldNames.idColumnName) //with metadata
    } else {
      res //no metadata
    }
  }


  /**
    * Gets result from the query cache.
    *
    * @param id
    * @return
    */
  def getFromQueryCache(id: Option[String]): Try[DataFrame] = {
    if (id.isDefined) {
      QueryLRUCache.get(id.get)
    } else {
      Failure(null)
    }
  }
}
