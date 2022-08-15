package scalus.uplc

import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalus.uplc.DefaultUni.{Bool, ByteString, asConstant}
import scalus.uplc.Meaning.EqualsInteger
import scalus.uplc.Term.*

import java.io.ByteArrayInputStream
import scala.io.Source.fromFile

class CekJVMSpec extends AnyFunSuite with ScalaCheckPropertyChecks with ArbitraryInstances:
  def runUPLC(code: String) = {
    import scala.sys.process.*
    val cmd = "/Users/nau/projects/scalus/uplc evaluate"
    val out = cmd.#<(new ByteArrayInputStream(code.getBytes("UTF-8"))).!!
    println(out)
  }

  def evalUPLC(code: String): Term = {
    import scala.sys.process.*
    val cmd = "/Users/nau/projects/scalus/uplc evaluate"
    val out = cmd.#<(new ByteArrayInputStream(code.getBytes("UTF-8"))).!!
    println(out)
    UplcParser.term
      .parse(out)
      .map(_._2)
      .getOrElse(
        throw new Exception(
          s"Could not parse: $out"
        )
      )
  }

  def run(code: String) = {
    val parser = UplcParser
    for
      program <- parser.parseProgram(code)
      evaled = Cek.evalUPLC(program.term)
    do println(evaled.pretty.render(80))
  }

  def eval(code: String): Term = {
    val parser = UplcParser
    parser.parseProgram(code).map(t => Cek.evalUPLC(t.term)).getOrElse(sys.error("Parse error"))
  }

  test("EqualsInteger") {
    def check(code: String, result: Boolean) =
      assert(evalUPLC(code) == Const(asConstant(result)))
      assert(eval(code) == Const(asConstant(result)))

    check("(program 1.0.0 [[(builtin equalsInteger) (con integer 0)] (con integer 0)])", true)
    check("(program 1.0.0 [[(builtin equalsInteger) (con integer 1)] (con integer 1)])", true)
    check(
      "(program 1.0.0 [[(builtin equalsInteger) (con integer -1234567890)] (con integer -1234567890)])",
      true
    )
    check("(program 1.0.0 [[(builtin equalsInteger) (con integer 1)] (con integer 2)])", false)
    {
      val code = "(program 1.0.0 [[(builtin equalsInteger) (con bool True)] (con integer 2)])"
      assertThrows[Exception] { evalUPLC(code) }
      assertThrows[Exception] { eval(code) }
    }

    {
      val code = "(program 1.0.0 [[(builtin equalsInteger) (con integer 1)] (con bool True)])"
      assertThrows[Exception](evalUPLC(code))
      assertThrows[Exception](eval(code))
    }

    forAll { (a: BigInt, b: BigInt) =>
      val arg1 = Const(asConstant(a))
      val arg2 = Const(asConstant(b))
      Cek.evalUPLC(Apply(Apply(Builtin(DefaultFun.EqualsInteger), arg1), arg1)) match
        case Const(Constant(Bool, true)) => assert(true)
        case r                           => fail(s"Expected true but got ${r.pretty.render(80)}")

      Cek.evalUPLC(Apply(Apply(Builtin(DefaultFun.EqualsInteger), arg1), arg2)) match
        case Const(Constant(Bool, r)) => assert(r == (a == b))
        case r                        => fail(s"Expected true but got ${r.pretty.render(80)}")
    }
  }

  test("conformance") {
    def check(name: String) =
      val path =
        s"/Users/nau/projects/iohk/plutus/plutus-conformance/test-cases/uplc/evaluation"
      val code = fromFile(s"$path/$name.uplc").mkString
      val expected = fromFile(s"$path/$name.uplc.expected").mkString
      println(eval(code).pretty.render(80))
      assert(eval(code) == eval(expected))

    check("builtin/addInteger/addInteger")
    check("builtin/addInteger-uncurried/addInteger-uncurried")
    check("builtin/equalsInteger/equalsInteger")
    check("builtin/ifThenElse/ifThenElse")

    // Examples
    check("example/factorial/factorial")
    check("example/fibonacci/fibonacci")
  }
