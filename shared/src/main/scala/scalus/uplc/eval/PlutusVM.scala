package scalus.uplc.eval

import scalus.builtin.PlatformSpecific
import scalus.ledger.api.BuiltinSemanticsVariant
import scalus.ledger.api.PlutusLedgerLanguage
import scalus.uplc.BuiltinsMeaning
import scalus.uplc.Constant
import scalus.uplc.DeBruijn
import scalus.uplc.DeBruijnedProgram
import scalus.uplc.Term

/** Plutus VM facade.
  *
  *   - Term is a representation of a UPLC term.
  *   - Term can be named, debruijned, or both.
  *   - Term can be evaluated by [[CekMachine]]
  *   - Program is a versioned UPLC term. 1.0.0 for Plutus V1 and V2, 1.1.0 for Plutus V3.
  *   - Plutus Script is a UPLC Program that should be evaluated according to the Plutus
  *     specification. This includes CIP-117.
  *
  * @param language
  *   The Plutus version
  * @param machineParams
  *   The machine parameters
  * @param semanticVariant
  *   The builtin semantics variant
  * @param platformSpecific
  *   The platform specific implementation of certain functions used by VM builtins
  */
class PlutusVM(
    val language: PlutusLedgerLanguage,
    val machineParams: MachineParams,
    val semanticVariant: BuiltinSemanticsVariant,
    platformSpecific: PlatformSpecific
) {
    private lazy val builtins =
        new BuiltinsMeaning(machineParams.builtinCostModel, platformSpecific, semanticVariant)

    /** Evaluates a Plutus script according to the Plutus specification.
      *
      * This includes CIP-117.
      *
      * @param program
      *   The Plutus script
      * @param budgetSpender
      *   The budget spender
      * @param logger
      *   The logger
      * @return
      *   The result of the evaluation. Must be `Unit` for Plutus V3.
      * @throws RuntimeException
      *   if the result is not valid
      */
    def evaluateScript(
        program: DeBruijnedProgram,
        budgetSpender: BudgetSpender,
        logger: Logger
    ): Term = {
        val result = evaluateDeBruijnedTerm(program.term, budgetSpender, logger)
        if isResultValid(result) then result
        else throw new InvalidReturnValue(result)
    }

    /** Evaluates a Plutus script according to the Plutus specification.
      *
      * This includes CIP-117.
      *
      * @param program
      *   The Plutus script
      * @return
      *   The result of the evaluation
      */
    def evaluateScriptDebug(program: DeBruijnedProgram): Result = {
        val spenderLogger = TallyingBudgetSpenderLogger(CountingBudgetSpender())
        try
            val result = evaluateScript(program, spenderLogger, spenderLogger)
            Result.Success(
              result,
              spenderLogger.getSpentBudget,
              spenderLogger.costs.toMap,
              spenderLogger.getLogsWithBudget
            )
        catch
            case e: Exception =>
                Result.Failure(
                  e,
                  spenderLogger.getSpentBudget,
                  spenderLogger.costs.toMap,
                  spenderLogger.getLogsWithBudget
                )
    }

    /** Evaluates a debruijned term using the CEK machine. This method does not follow CIP-117.
      *
      * @param debruijnedTerm
      *   The debruijned term
      * @param budgetSpender
      *   The budget spender
      * @param logger
      *   The logger
      * @return
      *   The evaluated term
      * @throws RuntimeException
      */
    def evaluateDeBruijnedTerm(
        debruijnedTerm: Term,
        budgetSpender: BudgetSpender = NoBudgetSpender,
        logger: Logger = NoLogger
    ): Term = {
        val cek = new CekMachine(machineParams, budgetSpender, logger, builtins)
        DeBruijn.fromDeBruijnTerm(cek.evaluateTerm(debruijnedTerm))
    }

    /** Plutus V3 requires the result to be `Unit` to be considered valid.
      * @param res
      *   The result term
      * @return
      *   `true` if the result is valid, `false` otherwise
      *
      * @see
      *   [CIP-117](https://cips.cardano.org/cip/CIP-0117/)
      */
    private def isResultValid(res: Term): Boolean = (language, res) match
        case (PlutusLedgerLanguage.PlutusV1 | PlutusLedgerLanguage.PlutusV2, _) => true
        case (PlutusLedgerLanguage.PlutusV3, Term.Const(Constant.Unit))         => true
        case _                                                                  => false
}

object PlutusVM {
    def makePlutusV1VM(
        params: MachineParams = MachineParams.defaultPlutusV1PostConwayParams
    )(using platformSpecific: PlatformSpecific): PlutusVM = new PlutusVM(
      PlutusLedgerLanguage.PlutusV2,
      params,
      params.semanticVariant,
      platformSpecific
    )

    def makePlutusV2VM(
        params: MachineParams = MachineParams.defaultPlutusV2PostConwayParams
    )(using platformSpecific: PlatformSpecific): PlutusVM =
        new PlutusVM(
          PlutusLedgerLanguage.PlutusV2,
          params,
          params.semanticVariant,
          platformSpecific
        )

    def makePlutusV3VM(
        params: MachineParams = MachineParams.defaultPlutusV3Params
    )(using platformSpecific: PlatformSpecific): PlutusVM =
        new PlutusVM(
          PlutusLedgerLanguage.PlutusV3,
          params,
          params.semanticVariant,
          platformSpecific
        )
}
