package scalus

import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.builtins.ByteString.given
import scalus.builtins.{Builtins, ByteString}
import scalus.ledger.api.v1.*
import scalus.ledger.api.v1.Instances.given
import scalus.sir.Recursivity.*
import scalus.sir.SIR.*
import scalus.sir.{Binding, Recursivity, SIR, SimpleSirToUplcLowering}
import scalus.uplc.*
import scalus.uplc.DefaultFun.*
import scalus.Compiler.{compile, fieldAsData}
import scalus.uplc.TermDSL.{lam, λ}
import scalus.utils.Utils

import scala.collection.immutable
import scalus.uplc.Data.FromData
import scalus.Predef.Maybe

class CompileFromDataToSIRSpec extends AnyFunSuite with ScalaCheckPropertyChecks:
  val deadbeef = Constant.ByteString(hex"deadbeef")

  inline def compilesTo(expected: SIR)(inline e: Any) = assert(compile(e) == expected)

  def testFromData[A: Data.ToData](
      compiled: SIR,
      arg: A,
      expectedSize: Int,
      expectedResult: Term
  ) = {
    // println(compiled.pretty.render(80))
    val term = new SimpleSirToUplcLowering().lower(compiled)
    // println(term.pretty.render(80))
    val flatBytes = ProgramFlatCodec.encodeFlat(Program(version = (1, 0, 0), term = term))
    // println(flatBytes.length)
    import TermDSL.*
    import scalus.uplc.Data.*
    val result = Cek.evalUPLC(term $ Term.Const(Constant.Data(arg.toData)))
    // println(result)
    assert(flatBytes.length == expectedSize)
    assert(result == expectedResult)
  }

  test("compile FromData[Boolean]") {
    val compiled = compile { (d: Data) =>
      summon[Data.FromData[Boolean]](d)
    }
    testFromData(compiled, true, 44, Term.Const(Constant.Bool(true)))
  }

  test("compile FromData[(A, B)]") {
    val compiled = compile {
      (d: Data) =>
        val pair = summon[Data.FromData[(Boolean, Boolean)]](d)
        pair match
          case (a, b) => b
    }
    testFromData(compiled, (true, false), 126, Term.Const(Constant.Bool(false)))
  }

  test("compile FromData[PubKeyHash]") {
    val compiled = compile { (d: Data) =>
      summon[Data.FromData[PubKeyHash]](d).hash
    }
    testFromData(compiled, TxId(hex"deadbeef"), 27, Term.Const(Constant.ByteString(hex"deadbeef")))
  }

  test("compile FromData[List[A]]") {
    import scalus.Predef.List.{Nil, Cons}
    val compiled = compile { (v: Data) =>
      val txids = summon[Data.FromData[scalus.Predef.List[TxId]]](v)
      txids match
        case Nil              => BigInt(0)
        case Cons(head, tail) => BigInt(1)

    }
    testFromData(
      compiled,
      immutable.List(TxId(hex"deadbeef")),
      116,
      Term.Const(Constant.Integer(1))
    )
  }

  test("compile FromData[Value]") {
    import scalus.Predef.List.{Nil, Cons}
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[Value]](v)
      value match
        case Nil => BigInt(0)
        case Cons(head, tail) =>
          head match
            case (cs, vals) =>
              vals match
                case Nil => BigInt(1)
                case Cons(tn, vl) =>
                  tn match
                    case (tn, vl) => vl

    }
    testFromData(compiled, Value.lovelace(42), 184, Term.Const(Constant.Integer(42)))
  }

  test("compile FromData[TxOutRef]") {
    import scalus.Predef.List.{Nil, Cons}
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[TxOutRef]](v)
      value match
        case TxOutRef(id, idx) => idx
    }
    testFromData(compiled, TxOutRef(TxId(hex"12"), 2), 66, Term.Const(Constant.Integer(2)))
  }

  test("compile FromData[Credential]") {
    import Credential.*
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[Credential]](v)
      value match
        case PubKeyCredential(pubKeyHash) => pubKeyHash.hash
        case ScriptCredential(hash)       => hash
    }
    testFromData(
      compiled,
      Credential.ScriptCredential(hex"12"),
      97,
      Term.Const(Constant.ByteString(hex"12"))
    )
  }

  test("compile FromData[StakingCredential]") {
    import StakingCredential.*
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[StakingCredential]](v)
      value match
        case StakingHash(cred)   => BigInt(1)
        case StakingPtr(a, b, c) => c
    }
    testFromData(compiled, StakingPtr(1, 2, 3), 202, Term.Const(Constant.Integer(3)))
  }

  test("compile FromData[DCert]") {
    import scalus.Predef.List.{Nil, Cons}
    import DCert.*
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[DCert]](v)
      value match
        case DelegRegKey(cred)              => BigInt(1)
        case DelegDeRegKey(cred)            => BigInt(2)
        case DelegDelegate(cred, delegatee) => BigInt(3)
        case PoolRegister(poolId, vrf)      => BigInt(4)
        case PoolRetire(poolId, epoch)      => BigInt(5)
        case Genesis                        => BigInt(6)
        case Mir                            => BigInt(7)

    }
    testFromData(compiled, DCert.Genesis, 491, Term.Const(Constant.Integer(6)))
  }

  test("compile FromData[Extended]") {
    import Extended.*
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[Extended[BigInt]]](v)
      value match
        case NegInf    => BigInt(1)
        case Finite(a) => a
        case PosInf    => BigInt(2)

    }
    testFromData(compiled, Finite(123), 124, Term.Const(Constant.Integer(123)))
  }

  test("compile FromData[ScriptPurpose]") {
    import ScriptPurpose.*
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[ScriptPurpose]](v)
      value match
        case Minting(curSymbol)     => BigInt(1)
        case Spending(txOutRef)     => BigInt(2)
        case Rewarding(stakingCred) => BigInt(3)
        case Certifying(cert)       => BigInt(4)

    }
    testFromData(compiled, Minting(hex"12"), 634, Term.Const(Constant.Integer(1)))
  }

  test("compile FromData[Address]") {
    import scalus.Predef.Maybe.Nothing
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[Address]](v)
      value match
        case Address(cred, stak) => BigInt(1)

    }
    testFromData(
      compiled,
      Address(Credential.PubKeyCredential(PubKeyHash(hex"12")), Nothing),
      303,
      Term.Const(Constant.Integer(1))
    )
  }

  test("compile FromData[TxOut]") {
    import scalus.Predef.Maybe.{Nothing, Just}
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[TxOut]](v)
      value match
        case TxOut(addr, value, datumHash) =>
          datumHash match
            case Nothing     => BigInt(1)
            case Just(value) => BigInt(2)
    }
    testFromData(
      compiled,
      TxOut(
        Address(Credential.PubKeyCredential(PubKeyHash(hex"12")), Nothing),
        Value.lovelace(42),
        Just(hex"beef")
      ),
      485,
      Term.Const(Constant.Integer(2))
    )
  }

  test("compile FromData[TxInInfo]") {
    import scalus.Predef.Maybe.{Nothing, Just}
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[TxInInfo]](v)
      value match
        case TxInInfo(ref, out) => ref.txOutRefIdx
    }
    testFromData(
      compiled,
      TxInInfo(
        TxOutRef(TxId(hex"12"), 12),
        TxOut(
          Address(Credential.PubKeyCredential(PubKeyHash(hex"12")), Nothing),
          Value.lovelace(42),
          Just(hex"beef")
        )
      ),
      558,
      Term.Const(Constant.Integer(12))
    )
  }

  test("compile FromData[UpperBound[A]]") {
    import scalus.Predef.Maybe.{Nothing, Just}
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[UpperBound[BigInt]]](v)
      value match
        case UpperBound(upper, clos) => clos
    }
    testFromData(
      compiled,
      UpperBound[BigInt](Extended.PosInf, false),
      193,
      Term.Const(Constant.Bool(false))
    )
  }

  test("compile FromData[LowerBound[A]]") {
    import scalus.Predef.Maybe.{Nothing, Just}
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[LowerBound[BigInt]]](v)
      value match
        case LowerBound(upper, clos) => clos
    }
    testFromData(
      compiled,
      LowerBound[BigInt](Extended.PosInf, false),
      193,
      Term.Const(Constant.Bool(false))
    )
  }

  test("compile FromData[POSIXTimeRange]") {
    import scalus.Predef.Maybe.{Nothing, Just}
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[POSIXTimeRange]](v)
      value match
        case Interval(lower, upper) => lower.closure
    }
    testFromData(
      compiled,
      Interval.always[BigInt],
      275,
      Term.Const(Constant.Bool(true))
    )
  }

  test("compile FromData[TxInfo]") {
    import scalus.Predef.Maybe.{Nothing, Just}
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[TxInfo]](v)
      value.txInfoId.hash
    }
    testFromData(
      compiled,
      TxInfo(
        txInfoInputs = scalus.Predef.List.Nil,
        txInfoOutputs = scalus.Predef.List.Nil,
        txInfoFee = Value.zero,
        txInfoMint = Value.zero,
        txInfoDCert = scalus.Predef.List.Nil,
        txInfoWdrl = scalus.Predef.List.Nil,
        txInfoValidRange = Interval.always,
        txInfoSignatories = scalus.Predef.List.Nil,
        txInfoData = scalus.Predef.List.Nil,
        txInfoId = TxId(ByteString.fromHex("bb"))
      ),
      1299,
      Term.Const(Constant.ByteString(ByteString.fromHex("bb")))
    )
  }

  test("compile FromData[ScriptContext]") {
    import scalus.Predef.Maybe.{Nothing, Just}
    val compiled = compile { (v: Data) =>
      val value = summon[Data.FromData[ScriptContext]](v)
      value.scriptContextTxInfo.txInfoId.hash
    }
    testFromData(
      compiled,
      ScriptContext(
        TxInfo(
          txInfoInputs = scalus.Predef.List.Nil,
          txInfoOutputs = scalus.Predef.List.Nil,
          txInfoFee = Value.zero,
          txInfoMint = Value.zero,
          txInfoDCert = scalus.Predef.List.Nil,
          txInfoWdrl = scalus.Predef.List.Nil,
          txInfoValidRange = Interval.always,
          txInfoSignatories = scalus.Predef.List.Nil,
          txInfoData = scalus.Predef.List.Nil,
          txInfoId = TxId(ByteString.fromHex("bb"))
        ),
        ScriptPurpose.Spending(TxOutRef(TxId(hex"12"), 12))
      ),
      1446,
      Term.Const(Constant.ByteString(ByteString.fromHex("bb")))
    )
  }