package scalus.builtins
import scalus.uplc.Data
import scalus.utils.Utils

import scala.collection.immutable

object Builtins:

  def mkConstr(ctor: BigInt, args: List[Data]): Data = Data.Constr(ctor.toLong, args.toList)
  def mkList(values: List[Data]): Data = Data.List(values.toList)
  def mkMap(values: List[Pair[Data, Data]]): Data = Data.Map(values.toList.map(p => (p.fst, p.snd)))
  def mkI(value: BigInt): Data = Data.I(value)
  def mkB(value: ByteString): Data = Data.B(value)
  def unsafeDataAsConstr(d: Data): Pair[BigInt, List[Data]] = d match
    case Data.Constr(constr, args) => Pair(constr: BigInt, List(args: _*))
    case _                         => throw new Exception(s"not a constructor but $d")
  def unsafeDataAsList(d: Data): List[Data] = d match
    case Data.List(values) => List(values: _*)
    case _                 => throw new Exception(s"not a list but $d")
  def unsafeDataAsMap(d: Data): List[Pair[Data, Data]] = d match
    case Data.Map(values) => List(values.map(Pair.apply): _*)
    case _                => throw new Exception(s"not a list but $d")

  def unsafeDataAsI(d: Data): BigInt = d match
    case Data.I(value) => value
    case _             => throw new Exception(s"not an integer but $d")

  def unsafeDataAsB(d: Data): ByteString = d match
    case Data.B(value) => value
    case _             => throw new Exception(s"not a bytestring but $d")

  def sha2_256(bs: ByteString): ByteString =
    // calculate the hash
    val hash = Utils.sha2_256(bs.bytes)
    ByteString.fromArray(hash)

  def trace[A](s: String)(a: A): A =
    // calculate the hash
    println(s)
    a

  def equalsByteString(a: ByteString, b: ByteString): Boolean = a == b
  def indexByteString(bs: ByteString, i: BigInt): BigInt =
    if i < 0 || i >= bs.bytes.length then
      throw new Exception(s"index $i out of bounds for bytestring of length ${bs.bytes.length}")
    else BigInt(bs.bytes(i.toInt) & 0xff)

  def consByteString(char: BigInt, byteString: ByteString): ByteString =
    ByteString.fromArray(char.toByte +: byteString.bytes)

  def lengthOfByteString(bs: ByteString): BigInt = bs.bytes.length

  def decodeUtf8(bs: ByteString): String =
    new String(bs.bytes, "UTF-8")

  def appendString(s1: String, s2: String): String = s1 + s2
  def equalsString(s1: String, s2: String): Boolean = s1 == s2

  def equalsInteger(i1: BigInt, i2: BigInt): Boolean = i1 == i2
  def lessThanInteger(i1: BigInt, i2: BigInt): Boolean = i1 < i2

  def equalsData(d1: Data, d2: Data): Boolean = d1 == d2
