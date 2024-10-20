
package scalus.utils

import scalus.flat.{*, given}


class HashConsedEncoderState(val encode: EncoderState,
                             val hashConsed: HashConsed.State)  {

    inline def lookupValue(ihc: Int, tag: HashConsed.Tag): Option[HashConsedRef[?]] = {
        hashConsed.lookupValue(ihc, tag)
    }

    inline def setRef[A<:AnyRef](ihc: Int, tag: HashConsed.Tag, a: HashConsedRef[A]): Unit = {
        hashConsed.setRef(ihc, tag, a)
    }

    inline def putForwardRefAcceptor(fw: HashConsed.ForwardRefAcceptor): Unit = {
        hashConsed.putForwardRef(fw)
    }
    
}

object HashConsedEncoderState {
    
    def withSize(size: Int): HashConsedEncoderState = 
        HashConsedEncoderState(EncoderState(size), HashConsed.State.empty)
    
}

class HashConsedDecoderState(val decode: DecoderState,
                             val hashConsed: HashConsed.State,
                             var finCallbacks: List[(HashConsed.State) => Unit] = Nil) {

    def addFinCallback(callback: (HashConsed.State) => Unit): Unit =
        this.finCallbacks = callback :: finCallbacks

    def runFinCallbacks(): Unit =
        finCallbacks.foreach(_(hashConsed))

}




trait HashConsedFlat[A] extends Flat[A] {

    final override def bitSize(a: A): Int =
        bitSizeHC(a, HashConsed.State.empty)

    final override def encode(a: A, encoderState: EncoderState): Unit =
        encodeHC(a, HashConsedEncoderState(encoderState, HashConsed.State.empty))

    def decode(decode: DecoderState): A =
        decodeHC(HashConsedDecoderState(decode, HashConsed.State.empty))

    def bitSizeHC(a: A, encoderState: HashConsed.State): Int

    def encodeHC(a: A, encoderState: HashConsedEncoderState): Unit

    def decodeHC(decoderState: HashConsedDecoderState): A

}


trait HashConsedReprFlat[A<:AnyRef, SA <: HashConsedRef[A]]  {

    type Repr = SA

    def toRepr(a: A): SA

    def bitSizeHC(a: A, hashConsed: HashConsed.State): Int

    def encodeHC(a: A, encode: HashConsedEncoderState): Unit

    def decodeHC(decode: HashConsedDecoderState): SA
    
}

object HashConsedReprFlat {

    case class ListRepl[A <: AnyRef, SA <: HashConsedRef[A]](elems: List[SA]) extends HashConsedRef[List[A]] {

        def isComplete(hashConsed: HashConsed.State): Boolean = elems.forall(_.isComplete(hashConsed))

        def finValue(hashConsed: HashConsed.State, level: Int, parents: HSRIdentityHashMap): List[A] =
            if (parents.get(this) != null)
                throw new Exception("Cycle detected")
            parents.put(this, this)
            val nextLevel = level + 1
            val retval = elems.map(_.finValue(hashConsed,nextLevel,parents))
            parents.remove(this)
            retval

    }


    implicit def listHashConsedRepr[A<:AnyRef,SA<:HashConsedRef[A]](using flatA: HashConsedReprFlat[A, SA]): HashConsedReprFlat[List[A], ListRepl[A, SA]] =
        new HashConsedReprFlat[List[A], ListRepl[A, SA]] {

            def toRepr(a: List[A]): ListRepl[A,SA] = ListRepl[A,SA](a.map(flatA.toRepr))

            def bitSizeHC(a: List[A], hashConsed: HashConsed.State): Int = {
                a.foldLeft(1)((acc, elem) => acc + 1 + flatA.bitSizeHC(elem, hashConsed))
            }

            def encodeHC(a: List[A], encode: HashConsedEncoderState): Unit = {
                a.foreach( elem =>
                    encode.encode.bits(1, 1)
                    flatA.encodeHC(elem, encode)
                )
                encode.encode.bits(1, 0)
            }

            def decodeHC(decode: HashConsedDecoderState): ListRepl[A,SA] = {
                val builder = List.newBuilder[SA]
                while
                    decode.decode.bits8(1) == 1.toByte
                  do
                    val elem = flatA.decodeHC(decode)
                    builder += elem
                ListRepl(builder.result())
            }

        }

    def listRepr[A<:AnyRef,SA<:HashConsedRef[A]](flatRepr: HashConsedReprFlat[A,SA]): HashConsedReprFlat[List[A], ListRepl[A, SA]] =
        listHashConsedRepr[A,SA](using flatRepr)


}


/*
trait HashConsedTagged[A] {

    def tag: HashConsed.Tag

}
*/

object HashConsedFlat {

    given listHashConsedFlat[A](using flatA: HashConsedFlat[A]): HashConsedFlat[List[A]] with

        def bitSizeHC(a: List[A], hashConsed: HashConsed.State): Int = {
            val size = a.foldLeft(0)((acc, elem) => acc + flatA.bitSizeHC(elem, hashConsed))
            size
        }
        def encodeHC(a: List[A], encode: HashConsedEncoderState): Unit = {
            val nElements = a.size
            summon[Flat[Int]].encode(nElements, encode.encode)
            a.foreach(elem => flatA.encodeHC(elem,encode))
        }
        def decodeHC(decode: HashConsedDecoderState): List[A] = {
            val size = summon[Flat[Int]].decode(decode.decode)
            (0 until size).map(_ => flatA.decodeHC(decode)).toList
        }

}

object PlainIntFlat extends HashConsedFlat[Int] {

    def bitSizeHC(a: Int, hashConsed: HashConsed.State): Int = 4*8

    def encodeHC(a: Int, encoderState: HashConsedEncoderState): Unit =
        encoderState.encode.bits(8, (a >>> 24).toByte)
        encoderState.encode.bits(8, (a >>> 16).toByte)
        encoderState.encode.bits(8, (a >>> 8).toByte)
        encoderState.encode.bits(8, a.toByte)

    def decodeHC(decoderState: HashConsedDecoderState): Int =
        var retval = 0
        retval |= ((decoderState.decode.bits8(8) << 24) & 0xFF000000)
        retval |= ((decoderState.decode.bits8(8) << 16) & 0x00FF0000)
        retval |= ((decoderState.decode.bits8(8) << 8)  & 0x0000FF00)
        retval |= (decoderState.decode.bits8(8) & 0x000000FF)
        retval

}


trait HashConsedMutRefReprFlat[A <: AnyRef]  extends HashConsedReprFlat[A, HashConsedRef[A]] {

    def tag: HashConsed.Tag

    def bitSizeHCNew(a: A, encode: HashConsed.State): Int

    def encodeHCNew(a: A, encode: HashConsedEncoderState): Unit

    def decodeHCNew(decoderState: HashConsedDecoderState): HashConsedRef[A]

    override def bitSizeHC(a: A, encoderState: HashConsed.State): Int = {
        val ihc = a.hashCode
        var retval = PlainIntFlat.bitSize(ihc)
        encoderState.lookup(ihc, tag) match
            case None =>
                encoderState.putForwardRef(HashConsed.ForwardRefAcceptor(ihc, tag, Nil))
                retval += bitSizeHCNew(a, encoderState)
                encoderState.setRef(ihc, tag, HashConsedRef.fromData(a))
            case Some(_) =>
        retval
    }

    override def encodeHC(a: A, encoderState: HashConsedEncoderState): Unit = {
        val ihc = a.hashCode
        PlainIntFlat.encode(ihc, encoderState.encode)
        println(s"Encoding HashConsedMutRefReprFlat: ${a}, hc=${ihc}")
        encoderState.hashConsed.lookup(ihc, tag) match
            case None =>
                println(s"Encoding HashConsedMutRefReprFlat: lookup failed, encoding new ihc=${ihc}, bitpos = ${encoderState.encode.bitPosition()}")
                encoderState.putForwardRefAcceptor(HashConsed.ForwardRefAcceptor(ihc, tag, Nil))
                encodeHCNew(a, encoderState)
                println(s"Encoded HashConsedMutRefReprFlat: encoded new ihc=${ihc}, bitpos = ${encoderState.encode.bitPosition()}")
                encoderState.setRef(ihc, tag, HashConsedRef.fromData(a))
            case Some(ref) =>
                println(s"Encoded HashConsedMutRefReprFlat: lookup succeeded, ihc=${ihc}, ref=${ref}, bitpos = ${encoderState.encode.bitPosition()}")

    }

    override def decodeHC(decoderState: HashConsedDecoderState): HashConsedRef[A] = {
        val ihc = PlainIntFlat.decode(decoderState.decode)
        println(s"Decoding HashConsedMutRefReprFlat: ihc=${ihc}, pos=${decoderState.decode.bitPosition()}")
        decoderState.hashConsed.lookup(ihc, tag) match
            case None =>
                decoderState.hashConsed.putForwardRef(HashConsed.ForwardRefAcceptor(ihc, tag, Nil))
                println(s"Decoded HashConsedMutRefReprFlat: lookup failed, decoding new ihc=${ihc}")
                val sa = decodeHCNew(decoderState)
                if (sa.isForward) then
                    throw new IllegalStateException("decodeHCNew returned a forward reference")
                decoderState.hashConsed.setRef(ihc, tag, sa)
                println(s"Decoded HashConsedMutRefReprFlat: setRef, ihc=${ihc}")
                sa
            case Some(Left(fw)) =>
                HashConsedRef.fromForward[A](decoderState.hashConsed, ihc, tag)
            case Some(Right(sa)) =>
                sa.asInstanceOf[HashConsedRef[A]]
    }

}


