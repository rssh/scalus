package scalus.uplc

import org.scalatest.funsuite.AnyFunSuite
import scalus.*
import scalus.uplc.DefaultUni.asConstant
import scalus.uplc.Term.*
import scalus.uplc.eval.VM

class CekSpec extends AnyFunSuite:
    test("Scalus") {
        val h = Const(asConstant("Hello"))
        val id = LamAbs("x", Var(NamedDeBruijn("x")))
        val app = Apply(id, h)
        assert(VM.evaluateTerm(app) == h)
    }

    def eval(code: String): Term = {
        UplcParser()
            .parseProgram(code)
            .map(p => VM.evaluateProgram(p))
            .getOrElse(sys.error("Parse error"))
    }

    test("EqualsInteger") {
        def check(code: String, result: Boolean) =
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
            assertThrows[Exception] { eval(code) }
        }

        {
            val code = "(program 1.0.0 [[(builtin equalsInteger) (con integer 1)] (con bool True)])"
            assertThrows[Exception](eval(code))
        }

    }
