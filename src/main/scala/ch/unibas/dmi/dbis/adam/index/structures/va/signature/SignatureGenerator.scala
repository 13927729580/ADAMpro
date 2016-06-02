package ch.unibas.dmi.dbis.adam.index.structures.va.signature

import ch.unibas.dmi.dbis.adam.datatypes.bitString.BitString

/**
 * adamtwo
 *
 * Ivan Giangreco
 * August 2015
 */
@SerialVersionUID(100L)
private[va] trait SignatureGenerator extends Serializable {

  /**
    *
    * @param cells cell ids to translate to signature
    * @return
    */
  def toSignature(cells: Seq[Int]): BitString[_]

  /**
    *
    * @param signature signature to translate to cell ids
    * @return
    */
  def toCells(signature: BitString[_]): Seq[Int]
}
