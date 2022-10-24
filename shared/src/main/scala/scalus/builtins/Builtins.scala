package scalus.builtins
import scalus.uplc.Data
import scalus.utils.Utils

import scala.collection.immutable

class ByteString private (val bytes: Array[Byte]) {
  override def toString: String = "\"" + toHex + "\""

  override def hashCode(): Int = java.util.Arrays.hashCode(bytes)

  override def equals(obj: Any): Boolean = obj match {
    case that: ByteString => java.util.Arrays.equals(this.bytes, that.bytes)
    case _                => false
  }

  lazy val toHex: String = Utils.bytesToHex(bytes)

}

object ByteString {
  val empty = new ByteString(Array.empty)
  def apply(bytes: Array[Byte]): ByteString = new ByteString(bytes.toArray)

  def apply(bytes: Byte*): ByteString = new ByteString(bytes.toArray)

  def unsafeFromArray(bytes: Array[Byte]): ByteString = new ByteString(bytes)
  def fromHex(bytes: String): ByteString = new ByteString(Utils.hexToBytes(bytes))

  implicit class StringInterpolators(val sc: StringContext) extends AnyVal:

    def hex(args: Any*): ByteString =
      val hexString = sc.s(args: _*).replace(" ", "")
      fromHex(hexString)
}

case class Pair[A, B](fst: A, snd: B):
  override def toString = "(" + fst + ", " + snd + ")"

enum List[+A]:
  case Nil extends List[Nothing]

  case Cons(h: A, tl: List[A]) extends List[A]

  def isEmpty: Boolean = this match
    case Nil => true
    case _   => false

  def head: A = this match
    case Cons(h, _) => h
    case _          => throw new NoSuchElementException("head of empty list")

  def tail: List[A] = this match
    case Cons(_, t) => t
    case _          => throw new NoSuchElementException("tail of empty list")

  def ::[B >: A](x: B): List[B] = Cons(x, this)

  def toList: immutable.List[A] = this match
    case Nil        => immutable.Nil
    case Cons(h, t) => h :: t.toList

object List:
  def empty[A]: List[A] = Nil
  def apply[A](xs: A*): List[A] = xs.foldRight(empty[A])(_ :: _)

object Builtins:

  def mkConstr(ctor: BigInt, args: List[Data]): Data = Data.Constr(ctor.toLong, args.toList)
  def mkList(values: List[Data]): Data = Data.List(values.toList)
  def mkMap(values: List[Pair[Data, Data]]): Data = Data.Map(values.toList.map(p => (p.fst, p.snd)))
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
