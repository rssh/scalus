import org.typelevel.paiges.Doc
import scalus.sir.PrettyPrinter
import scalus.sir.PrettyPrinter.Style
import scalus.sir.SIR
import scalus.sir.SimpleSirToUplcLowering
import scalus.uplc.Constant
import scalus.uplc.DefaultUni
import scalus.uplc.Program
import scalus.uplc.Term
import scalus.ledger.api.PlutusLedgerLanguage
import scalus.utils.Utils
package object scalus {

    /** Pipe operator */
    extension [A](inline a: A) inline infix def |>[B](inline f: A => B): B = f(a)

    extension (sir: SIR)
        def pretty: Doc = PrettyPrinter.pretty(sir, Style.Normal)
        def prettyXTerm: Doc = PrettyPrinter.pretty(sir, Style.XTerm)
        def doubleCborHex(version: (Int, Int, Int), generateErrorTraces: Boolean = false): String =
            val term = sir.toUplc(generateErrorTraces)
            Program(version, term).doubleCborHex

        def toUplc(generateErrorTraces: Boolean = false): Term =
            SimpleSirToUplcLowering(sir, generateErrorTraces).lower()

        def toPlutusProgram(
            version: (Int, Int, Int),
            generateErrorTraces: Boolean = false
        ): Program =
            val term = sir.toUplc(generateErrorTraces)
            Program(version, term)

    extension (p: Program)
        def writePlutusFile(path: String, plutusVersion: PlutusLedgerLanguage): Unit =
            Utils.writePlutusFile(path, p, plutusVersion)

    extension (du: DefaultUni) def pretty: Doc = PrettyPrinter.pretty(du)
    extension (c: Constant) def pretty: Doc = PrettyPrinter.pretty(c)

    extension (self: Term)
        def pretty: Doc = PrettyPrinter.pretty(self, Style.Normal)
        def prettyXTerm: Doc = PrettyPrinter.pretty(self, Style.XTerm)

    extension (self: uplc.Program)
        def pretty: Doc = PrettyPrinter.pretty(self, Style.Normal)
        def prettyXTerm: Doc = PrettyPrinter.pretty(self, Style.XTerm)
}
