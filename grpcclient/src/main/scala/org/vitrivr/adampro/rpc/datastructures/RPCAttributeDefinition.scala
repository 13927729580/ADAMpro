package org.vitrivr.adampro.rpc.datastructures

/**
  * ADAMpro
  *
  * Ivan Giangreco
  * July 2016
  */
case class RPCAttributeDefinition(name: String, datatype: String, storagehandlername : Option[String] = None, params : Map[String, String] = Map())

