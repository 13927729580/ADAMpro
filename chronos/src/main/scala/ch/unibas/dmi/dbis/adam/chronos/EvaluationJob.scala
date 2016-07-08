package ch.unibas.dmi.dbis.adam.chronos

import ch.unibas.cs.dbis.chronos.agent.ChronosJob
import scala.xml.{Node, XML}


/**
  * ADAMpro
  *
  * Ivan Giangreco
  * July 2016
  */
class EvaluationJob(job : ChronosJob) extends ChronosJob(job) {
  private val xml = XML.loadString(job.cdl)
  private val adampro = (xml \ "evaluation" \ "adampro").head
  private val data = (xml \ "evaluation" \ "data").head
  private val query = (xml \ "evaluation" \ "query").head
  private val execution = (xml \ "evaluation" \ "execution").head
  private val access = (xml \ "evaluation" \ "access").head
  private val measurement = (xml \ "evaluation" \ "measurement").head

  //adampro
  val adam_master : String = getAttribute(adampro, "adam_master")

  //data parameters
  val data_tuples : Int = getAttribute(data, "tuples").toInt
  val data_vector_dimensions : Int = getAttribute(data, "vector_dimensions").toInt
  val data_vector_sparsity : Float = getAttribute(data, "vector_sparsity").toFloat
  val data_vector_min : Float = getAttribute(data, "vector_min").toFloat
  val data_vector_max : Float = getAttribute(data, "vector_max").toFloat
  val data_vector_sparse : Boolean = getAttribute(data, "vector_population").equals("sparse")
  val data_vector_pk : String = getAttribute(data, "vector_pk")

  val data_metadata_long : Int = getAttribute(data, "metadata_long").toInt
  val data_metadata_int : Int = getAttribute(data, "metadata_int").toInt
  val data_metadata_float : Int = getAttribute(data, "metadata_float").toInt
  val data_metadata_double : Int = getAttribute(data, "metadata_double").toInt
  val data_metadata_string : Int = getAttribute(data, "metadata_string").toInt
  val data_metadata_text : Int = getAttribute(data, "metadata_text").toInt
  val data_metadata_boolean : Int = getAttribute(data, "metadata_boolean").toInt

  //query parameters
  val query_k : Seq[Int] = getAttribute(query, "k").split(",").map(_.toInt)
  val query_dense_n : Int = getAttribute(query, "dense_n").toInt
  val query_sparse_n : Int = getAttribute(query, "sparse_n").toInt
  val query_distance : String = getAttribute(query, "distance")
  val query_denseweighted : Boolean = getAttribute(query, "denseweighted").toBoolean
  val query_sparseweighted : Boolean = getAttribute(query, "sparseweighted").toBoolean

  //execution paths
  val execution_name : String = getAttribute(execution, "name")
  val execution_withsequential : Boolean = getAttribute(execution, "withsequential").toBoolean
  val execution_hint : String = getAttribute(execution, "hint")

  //data access parameters
  val access_entity_partitions : Seq[Int] = getAttribute(access, "entity_partitions").split(",").map(_.toInt)
  val access_entity_partitioner : String = getAttribute(access, "entity_partitioner")
  val access_index_partitions : Seq[Int]  = getAttribute(access, "index_partitions").split(",").map(_.toInt)
  val access_index_partitioner : String = getAttribute(access, "index_partitioner")

  //measurement parameters
  val measurement_firstrun : Boolean = getAttribute(measurement, "firstrun").toBoolean
  val measurement_cache : Boolean = getAttribute(measurement, "cache").toBoolean


  /**
    * 
    * @param node
    * @param key
    * @param errorIfEmpty
    * @return
    */
  private def getAttribute(node : Node, key : String, errorIfEmpty : Boolean = true) : String = {
    val attributeNode = node.attribute(key)

    if(errorIfEmpty && attributeNode.isEmpty){
      throw new Exception("attribute " + key + " is missing")
    } else if(attributeNode.isEmpty) {
      ""
    } else {
      attributeNode.get.text
    }
  }
}