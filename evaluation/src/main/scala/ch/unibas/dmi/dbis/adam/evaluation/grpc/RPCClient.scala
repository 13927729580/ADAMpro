package ch.unibas.dmi.dbis.adam.evaluation.grpc


import ch.unibas.dmi.dbis.adam.evaluation.{AdamParEvalUtils, EvaluationResultLogger}
import ch.unibas.dmi.dbis.adam.http.grpc.AdamDefinitionGrpc.AdamDefinitionBlockingStub
import ch.unibas.dmi.dbis.adam.http.grpc.AdamSearchGrpc.{AdamSearchBlockingStub, AdamSearchStub}
import ch.unibas.dmi.dbis.adam.http.grpc._
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * June 2016
  */
class RPCClient(channel: ManagedChannel, definer: AdamDefinitionBlockingStub, searcherBlocking: AdamSearchBlockingStub, searcher: AdamSearchStub) extends AdamParEvalUtils with EvaluationResultLogger {

  //k=10k to get sensible partition information
  val k = 100

  val provenance = "true"

  val numQ = 2

  /**
    * Evaluation Code
    */
  val tupleSizes = Seq(1e5.toInt)
  val dimensions = Seq(10)
  val partitions = Seq(4, 8, 16, 64, 128, 200)
  val indices = Seq(IndexType.sh)
  val partitioners = Seq(RepartitionMessage.Partitioner.SPARK)

  dropAllEntities()

  try
      for (tuples <- tupleSizes) {
        for (dim <- dimensions) {
          System.out.println("New Round! " + tuples + " " + dim)
          val eName = getOrGenEntity(tuples, dim)
            //Index generation
            for (index <- indices) {
              val name = getOrGenIndex(index, eName)
            }
            for(index <- indices){
              val name = getOrGenIndex(index, eName)
              for (part <- partitions) {
                System.out.println("Repartitioning: "+part)
                for (partitioner <- partitioners) {
                  definer.repartitionIndexData(RepartitionMessage(name, numberOfPartitions = part, option = RepartitionMessage.PartitionOptions.REPLACE_EXISTING, partitioner = partitioner))
                  val props = definer.getEntityProperties(EntityNameMessage(eName))
                  //TODO Verify Dimension Count
                  System.out.println(props)
                  val (avgTime, noResults) = timeQuery(name, dim, part)
                  appendToResults(tuples, dim, part, index.name, avgTime, k, noResults, partitioner)
                }
              }
          }
        }
      }
  finally out.close

  /** Checks if an Entity with the given Tuple size and dimensions exists */
  def getOrGenEntity(tuples: Int, dim: Int) : String = {
    var eName: Option[String] = None
    val entitymessage = definer.listEntities(EmptyMessage())

    for (entity <- entitymessage.entities) {
      val c = definer.count(EntityNameMessage(entity))
      if (c.message.toInt == tuples) {
        val props = definer.getEntityProperties(EntityNameMessage(entity))
        //TODO Verify Dimension Count
        System.out.println(props)
        eName = Some(entity)
        System.out.println("Entity found - "+eName.get)
      }
    }

    if(eName.isEmpty){
      System.out.println("Generating new Entity")
      eName = Some(("silvan" + Math.abs(Random.nextInt())).filter(_ != '0'))
      definer.createEntity(CreateEntityMessage(eName.get, Seq(AttributeDefinitionMessage.apply("id", AttributeType.LONG, true, true, true), AttributeDefinitionMessage("feature", AttributeType.FEATURE, false, false, true))))
      val options = Map("fv-dimensions" -> dim, "fv-min" -> 0, "fv-max" -> 1, "fv-sparse" -> false).mapValues(_.toString)
      definer.generateRandomData(GenerateRandomDataMessage(eName.get,tuples, options))
    }

    eName.get
  }

  /** Checks if Index exists and generates it otherwise */
  def getOrGenIndex(index: IndexType, eName: String): String = {
    val indexList = definer.listIndexes(EntityNameMessage(eName))
    var name = ""
    if (!indexList.indexes.exists(el => el.indextype == index)) {
      System.out.println("Index "+index.name+" does not exist, generating... ")
      name = generateIndex(index, eName)
    } else name = indexList.indexes.find(im => im.indextype == index).get.index
    System.out.println("Index name: "+name)

    name
  }

  //TODO Log individual queries in chronos
  def timeQuery(indexName: String, dim: Int, part: Int): (Float, Int) = {
    val queryCount = numQ
    val res = searcherBlocking.doQuery(QueryMessage(nnq = Some(randomQueryMessage(dim, part)), from = Some(FromMessage(FromMessage.Source.Index(indexName)))))

    //Average over Queries
    var resSize = 0
    val start = System.currentTimeMillis()
    var counter = 0
    while (counter < queryCount) {
      val res = searcherBlocking.doQuery(QueryMessage(nnq = Some(randomQueryMessage(dim, part)), from = Some(FromMessage(FromMessage.Source.Index(indexName)))))
      System.out.println(res.responses.head.results.head.data.toString())
      val partInfo = mutable.HashMap[Int, Int]()
      res.responses.map(f => f.results.map(r => {
        val key = r.data.getOrElse("adamproprovenance", DataMessage.defaultInstance).getIntData
        val value = partInfo.getOrElse(key, 0)
        partInfo.put(key, value+1)
      }))
      System.out.println(partInfo.toString())
      resSize += res.responses.head.results.size
      counter += 1
    }
    val stop = System.currentTimeMillis()
    ((stop - start) / queryCount.toFloat, resSize / queryCount)
  }

  /** Generates a random query using Random.nextFloat() */
  def randomQueryMessage(dim: Int, part: Int) = NearestNeighbourQueryMessage("feature", Some(FeatureVectorMessage().withDenseVector(DenseVectorMessage(Seq.fill(dim)(Random.nextFloat())))), None, getDistanceMsg, k, Map[String, String]("locality"-> provenance), indexOnly = true, 1 until part)

  /** Drops all entities */
  def dropAllEntities() = {
    val entityList = definer.listEntities(EmptyMessage())

    for (entity <- entityList.entities) {
      System.out.println("Dropping Entity: "+entity)
      val props = definer.getEntityProperties(EntityNameMessage(entity))
      System.out.println(props)
      val dropEnt = definer.dropEntity(EntityNameMessage(entity))
      if(dropEnt.code.isError){
        System.err.println("Error when dropping Entity "+entity +": "+dropEnt.message)
      }
    }
  }

  /** Generates DistanceMessage with Minkowski-norm 2 */
  def getDistanceMsg: Option[DistanceMessage] = Some(DistanceMessage(DistanceMessage.DistanceType.minkowski, Map[String, String](("norm", "2"))))

  /** generates Index and returns the name*/
  def generateIndex(indexType: IndexType, eName: String): String = {
    val indexMsg = IndexMessage(eName, "feature", indexType, getDistanceMsg, Map[String, String]())
    val indexRes = definer.index(indexMsg)
    indexRes.message
  }



}

object RPCClient {
  def apply(host: String, port: Int): RPCClient = {
    val channel = OkHttpChannelBuilder.forAddress(host, port).usePlaintext(true).asInstanceOf[ManagedChannelBuilder[_]].build()

    new RPCClient(
      channel,
      AdamDefinitionGrpc.blockingStub(channel),
      AdamSearchGrpc.blockingStub(channel),
      AdamSearchGrpc.stub(channel)
    )
  }
}