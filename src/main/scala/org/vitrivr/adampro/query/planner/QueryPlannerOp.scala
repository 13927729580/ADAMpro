package org.vitrivr.adampro.query.planner

import org.vitrivr.adampro.data.entity.Entity
import org.vitrivr.adampro.data.entity.Entity.{AttributeName, EntityName}
import org.vitrivr.adampro.data.index.Index
import org.vitrivr.adampro.process.SharedComponentContext
import org.vitrivr.adampro.query.ast.generic.QueryExpression
import org.vitrivr.adampro.query.ast.internal.{IndexScanExpression, SequentialScanExpression}
import org.vitrivr.adampro.query.query.RankingQuery


/**
  * ADAMpro
  *
  * Ivan Giangreco
  * April 2017
  */
case class ExecutionPath(expr : QueryExpression, scan : String, scantype : String, score : Double)


object QueryPlannerOp {

  /**
    * Returns all scans scored under the optimizer given the accessible scan possibilities (index, entity, etc.), the query
    *
    * @param optimizerName
    * @param entityname
    * @param nnq
    * @param filterExpr
    * @param ac
    * @return
    */
  def scoredScans(optimizerName : String, entityname : EntityName, nnq: RankingQuery)(filterExpr: Option[QueryExpression] = None)(implicit ac: SharedComponentContext): Seq[ExecutionPath] = {
    val optimizer = PlannerRegistry.apply(optimizerName).get

    val indexes = ac.catalogManager.listIndexes(Some(entityname), Some(nnq.attribute)).get.map(Index.load(_)).filter(_.isSuccess).map(_.get).groupBy(_.indextypename).mapValues(_.map(_.indexname))
    val indexScans = indexes.values.toSeq.flatten
      .map(indexname => Index.load(indexname, false).get)
      .map(index => {
        val score = optimizer.getScore(index, nnq)

        ExecutionPath(IndexScanExpression(index)(nnq, None)(filterExpr)(ac), index.indexname, index.indextypename.name, score)
      })

    val entity = Entity.load(entityname).get
    val entityScan = {
        val score = optimizer.getScore(entity, nnq)

        ExecutionPath(SequentialScanExpression(entity)(nnq, None)(filterExpr)(ac), entity.entityname, "sequential", score)
      }

    indexScans ++ Seq(entityScan)
  }


  /**
    * Returns the optimal scan under the optimizer given the accessible scan possibilities (index, entity, etc.), the query
    *
    * @param optimizerName
    * @param entityname
    * @param nnq
    * @param filterExpr
    * @param ac
    * @return
    */
  def getOptimalScan(optimizerName : String, entityname : EntityName, nnq: RankingQuery)(filterExpr: Option[QueryExpression] = None)(implicit ac: SharedComponentContext) : QueryExpression = {
    scoredScans(optimizerName, entityname, nnq)(filterExpr)(ac).sortBy(x => -x.score).head.expr
  }


}
