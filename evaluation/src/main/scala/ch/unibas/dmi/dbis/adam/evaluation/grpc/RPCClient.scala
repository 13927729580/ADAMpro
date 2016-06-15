package ch.unibas.dmi.dbis.adam.evaluation.grpc


import ch.unibas.dmi.dbis.adam.evaluation.AdamParUtils
import ch.unibas.dmi.dbis.adam.http.grpc.AdamDefinitionGrpc.AdamDefinitionBlockingStub
import ch.unibas.dmi.dbis.adam.http.grpc.AdamSearchGrpc.{AdamSearchBlockingStub, AdamSearchStub}
import ch.unibas.dmi.dbis.adam.http.grpc._
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.{ManagedChannel, ManagedChannelBuilder}

import scala.util.Random

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * June 2016
  */
class RPCClient(channel: ManagedChannel, definer: AdamDefinitionBlockingStub, searcherBlocking: AdamSearchBlockingStub, searcher: AdamSearchStub) extends AdamParUtils{

  val eName = "silvan"
  val nTuples = 1e5.toInt
  val nDims = 128
  val noPart = 3
  val k = 100

  val entityList = definer.listEntities(EmptyMessage())
  System.out.println("List of current entities: \n" + entityList.entities.toString()+"\n")

  //dropAllEntities()
  //generateEntity()
  //time("Generating Random Data")(verifyRes(definer.generateRandomData(GenerateRandomDataMessage(eName, nTuples, nDims))))

  val repartitionRes = time("Repartitioning Entity")(definer.repartitionEntityData(RepartitionMessage(eName,noPart,Seq("feature"),RepartitionMessage.PartitionOptions.REPLACE_EXISTING)))

  System.exit(1)

  val featureVector = FeatureVectorMessage().withDenseVector(DenseVectorMessage(Seq.fill(nDims)(Random.nextFloat())))
  var queryMsg = NearestNeighbourQueryMessage("feature",Some(featureVector),None,None,k,Map[String,String](),true,1 until noPart)
  //Specifies Distance-Message
  queryMsg = NearestNeighbourQueryMessage("feature",Some(featureVector),None,getDistanceMsg,k,Map[String,String](),true,1 until noPart)

  //TODO maybe this needs the name of the index
  val fromMsg = FromMessage(FromMessage.Source.Entity(eName))
  val resEntity = time("Performing nnQuery")(searcherBlocking.doQuery(QueryMessage(nnq = Some(queryMsg),from = Some(fromMsg))))

  System.out.println("\n" + resEntity.serializedSize + " Results!\n")

  val resIndex = time("Performing nnQuery")(searcherBlocking.doQuery(QueryMessage(nnq = Some(queryMsg),from = Some(FromMessage(FromMessage.Source.Index("silvan_feature_ecp_0"))))))

  System.out.println("\n" + resIndex.serializedSize + " Results!\n")

  System.out.println(definer.count(EntityNameMessage(eName)).message)

  def generateECPIndex(): Unit = {
    var indexMsg = IndexMessage(eName,"feature",IndexType.ecp,None,Map[String,String]())
    indexMsg = IndexMessage(eName, "feature",IndexType.ecp,getDistanceMsg,Map[String,String]())
    val indexRes = time("Building ecp Index")(definer.index(indexMsg))
    verifyRes(indexRes)
  }

  def generateEntity(): Unit = {
    //Generate Entity
    val entityRes = time("Creating Entity")(definer.createEntity(CreateEntityMessage.apply(eName, Seq(FieldDefinitionMessage.apply("id", FieldDefinitionMessage.FieldType.LONG, true, true, true), FieldDefinitionMessage.apply("feature", FieldDefinitionMessage.FieldType.FEATURE, false, false, true)))))
    verifyRes(entityRes)
  }

  def insertData(): Unit ={
    //TODO Check if we can avoid the heap space problem
  }

  def getDistanceMsg : Option[DistanceMessage] = Some(DistanceMessage(DistanceMessage.DistanceType.minkowski,Map[String,String](("norm","2"))))


  def verifyRes (res: AckMessage) {
    if (!(res.code == AckMessage.Code.OK) ) {
      System.err.println ("Error during entity creation")
      System.err.println (res.message)
      System.exit (1)
    }
  }


  def dropAllEntities() = {
    val entityList = definer.listEntities(EmptyMessage())
    System.out.println("List of current entities: \n" + entityList.entities.toString()+"\n")

    for(entity <- entityList.entities) {
      System.out.println("Dropping " + entity)
      val dropEnt = definer.dropEntity(EntityNameMessage(entity))
      verifyRes(dropEnt)
    }
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