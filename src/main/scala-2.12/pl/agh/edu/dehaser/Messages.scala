package pl.agh.edu.dehaser

import scala.collection.immutable.NumericRange


sealed trait Messages

case class DehashIt(hash: String, algo: String) extends Messages

case class Check(range: NumericRange[BigInt], hash: String, algo: String)

// TODO: send original hash and algo or not?
case class FoundIt(crackedPass: String)

case class RangeChecked(range: NumericRange[BigInt])
