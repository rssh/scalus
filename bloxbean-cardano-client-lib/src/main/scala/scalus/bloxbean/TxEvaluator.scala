package scalus.bloxbean

import com.bloxbean.cardano.client.address.Address
import com.bloxbean.cardano.client.address.AddressType
import com.bloxbean.cardano.client.address.Credential
import com.bloxbean.cardano.client.address.CredentialType
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil
import com.bloxbean.cardano.client.crypto.Blake2bUtil
import com.bloxbean.cardano.client.plutus.spec.*
import com.bloxbean.cardano.client.transaction.spec.*
import com.bloxbean.cardano.client.transaction.spec.cert.*
import com.bloxbean.cardano.client.transaction.spec.governance.ProposalProcedure
import com.bloxbean.cardano.client.transaction.spec.governance.Voter
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType
import com.bloxbean.cardano.client.transaction.util.TransactionUtil
import io.bullet.borer.Cbor
import io.bullet.borer.Decoder
import org.slf4j.LoggerFactory
import scalus.bloxbean.Interop.*
import scalus.builtin.ByteString
import scalus.builtin.Data
import scalus.builtin.JVMPlatformSpecific
import scalus.builtin.given
import scalus.ledger
import scalus.ledger.api
import scalus.ledger.api.PlutusLedgerLanguage
import scalus.ledger.api.PlutusLedgerLanguage.*
import scalus.ledger.api.v1
import scalus.ledger.api.v1.ScriptPurpose
import scalus.ledger.api.v1.StakingCredential
import scalus.ledger.api.v2
import scalus.ledger.api.v2.OutputDatum
import scalus.ledger.api.v3
import scalus.prelude.Maybe
import scalus.uplc.Constant
import scalus.uplc.Program
import scalus.uplc.ProgramFlatCodec
import scalus.uplc.Term
import scalus.uplc.Term.Apply
import scalus.uplc.Term.Const
import scalus.uplc.eval.*
import scalus.utils.Hex
import upickle.default.*

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util
import scala.annotation.unused
import scala.beans.BeanProperty
import scala.collection.immutable
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

case class SlotConfig(zeroTime: Long, zeroSlot: Long, slotLength: Long)

object SlotConfig {
    // taken from https://github.com/spacebudz/lucid/blob/main/src/plutus/time.ts
    val Mainnet: SlotConfig = SlotConfig(
      zeroTime = 1596059091000L,
      zeroSlot = 4492800,
      slotLength = 1000
    ) // Starting at Shelley era
    val Preview: SlotConfig = SlotConfig(
      zeroTime = 1666656000000L,
      zeroSlot = 0,
      slotLength = 1000
    ) // Starting at Shelley era
    val Preprod: SlotConfig = SlotConfig(
      zeroTime = 1654041600000L + 1728000000L,
      zeroSlot = 86400,
      slotLength = 1000
    )
    @deprecated("Use Mainnet", "0.8.0")
    val default: SlotConfig = Mainnet
}

class TxEvaluationException(
    message: String,
    cause: Throwable,
    @BeanProperty val logs: Array[String]
) extends Exception(message, cause)

enum ScriptVersion:
    case Native
    case PlutusV1(flatScript: ByteString)
    case PlutusV2(flatScript: ByteString)
    case PlutusV3(flatScript: ByteString)

/** Evaluate script costs for a transaction using two phase eval.
  * @note
  *   This is experimental API and subject to change
  */
class TxEvaluator(
    val slotConfig: SlotConfig,
    val initialBudget: ExBudget,
    val protocolMajorVersion: Int,
    val costMdls: CostMdls,
    val mode: EvaluatorMode = EvaluatorMode.EVALUATE_AND_COMPUTE_COST,
    val debugDumpFilesForTesting: Boolean = false
) {
    import TxEvaluator.*
    private val log = LoggerFactory.getLogger(getClass.getName)
    private lazy val machineParamsPlutusV1 = translateMachineParamsFromCostMdls(
      costMdls,
      PlutusV1,
      api.ProtocolVersion(protocolMajorVersion, 0)
    )
    private lazy val machineParamsPlutusV2 = translateMachineParamsFromCostMdls(
      costMdls,
      PlutusV2,
      api.ProtocolVersion(protocolMajorVersion, 0)
    )
    private lazy val machineParamsPlutusV3 = translateMachineParamsFromCostMdls(
      costMdls,
      PlutusV3,
      api.ProtocolVersion(protocolMajorVersion, 0)
    )

    /** Phase 2 validation and execution of the transaction
      */
    def evaluateTx(
        transaction: Transaction,
        inputUtxos: Map[TransactionInput, TransactionOutput]
    ): collection.Seq[Redeemer] = {
        val datums = transaction.getWitnessSet.getPlutusDataList.asScala
            .map(data => ByteString.fromArray(data.serializeToBytes()))
            .toSeq
        evaluateTx(transaction, inputUtxos, datums, TransactionUtil.getTxHash(transaction))
    }

    /** Phase 2 validation and execution of the transaction
      *
      * @param transaction
      * @param inputUtxos
      * @param datums
      *   \- Exact CBOR datums from Transaction Witness Set. This is to compute correct datum hash
      * @return
      *   Redeemers
      */
    def evaluateTx(
        transaction: Transaction,
        inputUtxos: Map[TransactionInput, TransactionOutput],
        datums: collection.Seq[ByteString],
        txhash: String
    ): collection.Seq[Redeemer] = {
        assert(
          transaction.getWitnessSet.getPlutusDataList.size == datums.size,
          s"Datum size mismatch, expected: ${transaction.getWitnessSet.getPlutusDataList.size}, actual: ${datums.size}"
        )

        // For debugging, store ins and outs in cbor format
        // to run aiken simulator
        // like this:
        // aiken tx simulate --cbor tx-$txhash.cbor ins.cbor outs.cbor > aiken.log"
        if debugDumpFilesForTesting then
            Files.write(Paths.get(s"tx-$txhash.cbor"), transaction.serialize())
            Files.deleteIfExists(java.nio.file.Paths.get("scalus.log"))
            storeInsOutsInCborFiles(inputUtxos, txhash)

        evalPhaseTwo(transaction, txhash, datums, inputUtxos, runPhaseOne = true)
    }

    private def storeInsOutsInCborFiles(
        utxos: Map[TransactionInput, TransactionOutput],
        txhash: String
    ): Unit = {
        val ins = co.nstant.in.cbor.model.Array()
        val outs = co.nstant.in.cbor.model.Array()

        for (in, out) <- utxos do
            ins.add(in.serialize())
            outs.add(out.serialize())

        Files.write(Path.of(s"ins-$txhash.cbor"), CborSerializationUtil.serialize(ins));
        Files.write(Path.of(s"outs-$txhash.cbor"), CborSerializationUtil.serialize(outs));
    }

    private def findScript(
        tx: Transaction,
        redeemer: Redeemer,
        lookupTable: LookupTable,
        utxos: Map[TransactionInput, TransactionOutput]
    ): (ScriptVersion, Option[Data]) = {
        val index = redeemer.getIndex.intValue
        val inputs = tx.getBody.getInputs
        redeemer.getTag match
            case RedeemerTag.Spend =>
                val ins = inputs.asScala.sorted
                if ins.isDefinedAt(index) then
                    val input = ins(index)
                    val txInInfo = getTxInInfoV2(input, utxos)
                    val script = txInInfo.resolved.address match
                        case v1.Address(v1.Credential.ScriptCredential(hash), _) =>
                            val script = lookupTable.scripts.getOrElse(
                              hash,
                              throw new IllegalStateException(s"Script not found: $hash")
                            )
                            script
                        case _ => throw new IllegalStateException(s"impossible: $input")

                    val datum: Option[Data] = txInInfo.resolved.datum match
                        case OutputDatum.NoOutputDatum => None
                        case OutputDatum.OutputDatumHash(datumHash) =>
                            lookupTable.datums.get(datumHash)
                        case OutputDatum.OutputDatum(datum) => Some(datum)

                    script match
                        case ScriptVersion.PlutusV1(_) | ScriptVersion.PlutusV2(_) =>
                            datum match
                                case None =>
                                    throw new IllegalStateException(
                                      s"Missing required datum for script: $script"
                                    )
                                case Some(_) => ()
                        case _ => ()
                    script -> datum
                else throw new IllegalStateException(s"Input not found: $index in $inputs")
            case RedeemerTag.Mint =>
                val minted =
                    (tx.getBody.getMint ?? util.List.of()).asScala.map(_.getPolicyId).sorted
                if minted.isDefinedAt(index) then
                    val policyId = minted(index)
                    val script = lookupTable.scripts.getOrElse(
                      ByteString.fromHex(policyId),
                      throw new IllegalStateException(s"Script not found: $policyId")
                    )
                    script -> None
                else throw new IllegalStateException(s"Mint not found: $index in $minted")
            case RedeemerTag.Cert =>
                val certs = tx.getBody.getCerts.asScala
                if certs.isDefinedAt(index) then
                    val cert = certs(index)
                    val script = getCertScript(cert)
                    script match
                        case Some(hash) =>
                            val script = lookupTable.scripts.getOrElse(
                              hash,
                              throw new IllegalStateException(s"Script not found: $hash")
                            )
                            script -> None
                        case None =>
                            throw new IllegalStateException(
                              s"Missing required script for cert: $cert"
                            )
                else throw new IllegalStateException(s"Cert not found: $index in $certs")

            case RedeemerTag.Reward =>
                val withdrawals = getWithdrawals(tx.getBody.getWithdrawals).toList
                if withdrawals.isDefinedAt(index) then
                    withdrawals(index) match
                        case (
                              v1.StakingCredential.StakingHash(
                                v1.Credential.ScriptCredential(hash)
                              ),
                              _
                            ) =>
                            val script = lookupTable.scripts.getOrElse(
                              hash,
                              throw new IllegalStateException(s"Script not found: $hash")
                            )
                            script -> None
                        case withdrawal =>
                            throw new IllegalStateException(
                              s"Invalid withdrawal: $withdrawal @ $index in $withdrawals"
                            )
                else throw new IllegalStateException(s"Cert not found: $index in $withdrawals")

            case RedeemerTag.Proposing =>
                val proposals = tx.getBody.getProposalProcedures.asScala
                if proposals.isDefinedAt(index) then
                    val proposal = proposals(index)
                    getProposalScriptHash(proposal) match
                        case Some(hash) =>
                            val script = lookupTable.scripts.getOrElse(
                              hash,
                              throw new IllegalStateException(s"Script not found: $hash")
                            )
                            script -> None
                        case None =>
                            throw new IllegalStateException(
                              s"Missing required script for proposal: $proposal"
                            )
                else throw new IllegalStateException(s"Proposal not found: $index in $proposals")

            case RedeemerTag.Voting =>
                import Interop.given // for Ordering instances
                val voting = tx.getBody.getVotingProcedures.getVoting.asScala.toSeq.sortBy(_._1)
                if voting.isDefinedAt(index) then
                    val (voter, _) = voting(index)
                    voter.getType match
                        case VoterType.CONSTITUTIONAL_COMMITTEE_HOT_SCRIPT_HASH |
                            VoterType.DREP_SCRIPT_HASH =>
                            val script = lookupTable.scripts.getOrElse(
                              ByteString.fromArray(voter.getCredential.getBytes),
                              throw new IllegalStateException(s"Script not found: $voter")
                            )
                            script -> None
                        case _ =>
                            throw new IllegalStateException(s"Invalid voter: $voter")
                else throw new IllegalStateException(s"Voting not found: $index in $voting")
    }

    private def evalRedeemer(
        tx: Transaction,
        txhash: String,
        datums: collection.Seq[(ByteString, Data)],
        utxos: Map[TransactionInput, TransactionOutput],
        redeemer: Redeemer,
        lookupTable: LookupTable
    ): Redeemer = {
        import scalus.bloxbean.Interop.toScalusData
        import scalus.builtin.Data.toData
        import scalus.ledger.api.v1.ToDataInstances.given
        import scalus.ledger.api.v2.ToDataInstances.given
        import scalus.ledger.api.v3.ToDataInstances.given

        val result = findScript(tx, redeemer, lookupTable, utxos) match
            case (ScriptVersion.Native, _) =>
                throw new IllegalStateException("Native script not supported")
            case (ScriptVersion.PlutusV1(script), datum) =>
                val rdmr = toScalusData(redeemer.getData)
                val txInfoV1 =
                    getTxInfoV1(tx, txhash, datums, utxos, slotConfig, protocolMajorVersion)
                val purpose = getScriptPurposeV1(redeemer, tx)
                val scriptContext = v1.ScriptContext(txInfoV1, purpose)
                val ctxData = scriptContext.toData
                if log.isDebugEnabled() then
                    log.debug(s"eval: PlutusV1, ${purpose}")
                    log.debug(s"Datum: ${datum.map(_.toJson)}")
                    log.debug(s"Redeemer: ${rdmr.toJson}")
                    log.debug(s"Script context: ${ctxData.toJson}")
                datum match
                    case Some(datum) =>
                        evalScript(
                          redeemer,
                          machineParamsPlutusV1,
                          script.bytes,
                          datum,
                          rdmr,
                          ctxData
                        )
                    case None =>
                        evalScript(redeemer, machineParamsPlutusV1, script.bytes, rdmr, ctxData)

            case (ScriptVersion.PlutusV2(script), datum) =>
                val rdmr = toScalusData(redeemer.getData)
                val txInfo =
                    getTxInfoV2(tx, txhash, datums, utxos, slotConfig, protocolMajorVersion)
                val purpose = getScriptPurposeV2(redeemer, tx)
                val scriptContext = v2.ScriptContext(txInfo, purpose)
                val ctxData = scriptContext.toData
                if log.isDebugEnabled() then
                    log.debug(s"eval: PlutusV2, ${purpose}")
                    log.debug(s"Datum: ${datum.map(_.toJson)}")
                    log.debug(s"Redeemer: ${rdmr.toJson}")
                    log.debug(s"Script context: ${ctxData.toJson}")
                datum match
                    case Some(datum) =>
                        evalScript(
                          redeemer,
                          machineParamsPlutusV2,
                          script.bytes,
                          datum,
                          rdmr,
                          ctxData
                        )
                    case None =>
                        evalScript(redeemer, machineParamsPlutusV2, script.bytes, rdmr, ctxData)

            case (ScriptVersion.PlutusV3(script), datum) =>
                val rdmr = toScalusData(redeemer.getData)
                val txInfo =
                    getTxInfoV3(tx, txhash, datums, utxos, slotConfig, protocolMajorVersion)
                val scriptInfo = getScriptInfoV3(tx, redeemer, datum)
                val scriptContext = v3.ScriptContext(txInfo, rdmr, scriptInfo)
                val ctxData = scriptContext.toData
                if log.isDebugEnabled() then
                    log.debug(s"eval: PlutusV3, ${scriptInfo}")
                    log.debug(s"Datum: ${datum.map(_.toJson)}")
                    log.debug(s"Redeemer: ${rdmr.toJson}")
                    log.debug(s"Script context: ${ctxData.toJson}")
                evalScript(redeemer, machineParamsPlutusV3, script.bytes, ctxData)

        val cost = result.budget
        log.debug(s"Eval result: $result")
        Redeemer(
          redeemer.getTag,
          redeemer.getIndex,
          redeemer.getData,
          ExUnits(
            BigInteger.valueOf(cost.memory),
            BigInteger.valueOf(cost.cpu)
          )
        )
    }

    private def evalScript(
        redeemer: Redeemer,
        machineParams: MachineParams,
        script: VM.ScriptForEvaluation,
        args: Data*
    ) = {
        val budget = ExBudget.fromCpuAndMemory(
          cpu = redeemer.getExUnits.getSteps.longValue,
          memory = redeemer.getExUnits.getMem.longValue
        )
        val program = ProgramFlatCodec.decodeFlat(script)
        val applied = args.foldLeft(program.term) { (acc, arg) =>
            Apply(acc, Const(scalus.uplc.DefaultUni.asConstant(arg)))
        }
        if debugDumpFilesForTesting then
            val flat =
                ProgramFlatCodec.unsafeEncodeFlat(Program(version = program.version, applied))
            Files.write(
              java.nio.file.Paths.get(s"script-${redeemer.getTag}-${redeemer.getIndex}.flat"),
              flat,
              java.nio.file.StandardOpenOption.CREATE,
              java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            )
        val spender = mode match
            case EvaluatorMode.EVALUATE_AND_COMPUTE_COST => CountingBudgetSpender()
            case EvaluatorMode.VALIDATE => RestrictingBudgetSpenderWithScripDump(budget)

        val logger = Log()
        val cek = new CekMachine(machineParams, spender, logger, JVMPlatformSpecific)
        val r =
            try
                val resultTerm = cek.evaluateTerm(applied)
                CekResult(resultTerm, spender.getSpentBudget, logger.getLogs)
            catch
                case e: MachineError =>
                    throw new TxEvaluationException(e.getMessage, e, logger.getLogs)

        if mode == EvaluatorMode.VALIDATE && r.budget != budget then
            log.warn(s"Budget mismatch: expected $budget, got ${r.budget}")
        r
    }

    private def evalPhaseTwo(
        tx: Transaction,
        txhash: String,
        datums: collection.Seq[ByteString],
        utxos: Map[TransactionInput, TransactionOutput],
        runPhaseOne: Boolean
    ): collection.Seq[Redeemer] = {
        log.debug(
          s"Eval phase two $tx, $utxos, $costMdls, $initialBudget, $slotConfig, $runPhaseOne"
        )
        val redeemers = tx.getWitnessSet.getRedeemers ?? util.List.of()
        // compute Datum hashes from the original datum CBORs
        // because if we re-serialize Data, the hash may be different
        val datumsMapping = datums.map: datum =>
            val datumHash = ByteString.fromArray(Blake2bUtil.blake2bHash256(datum.bytes))
            val data = Cbor.decode(datum.bytes).to[Data].value
            datumHash -> data
        val lookupTable = getScriptAndDatumLookupTable(tx, datumsMapping, utxos)
        log.debug(s"Lookup table: $lookupTable")

        if runPhaseOne then
            // Subset of phase 1 check on redeemers and scripts
            evalPhaseOne(tx, utxos, lookupTable)

        var remainingBudget = initialBudget
        val collectedRedeemers = for redeemer <- redeemers.asScala yield {
            val evaluatedRedeemer = evalRedeemer(
              tx,
              txhash,
              datumsMapping,
              utxos,
              redeemer,
              lookupTable
            )

            if evaluatedRedeemer.getExUnits.getSteps != redeemer.getExUnits.getSteps
                || evaluatedRedeemer.getExUnits.getMem != redeemer.getExUnits.getMem
            then
                log.debug(
                  s"ExUnits: ${redeemer.getExUnits} - Evaluated: ${evaluatedRedeemer.getExUnits}"
                )

            // The subtraction is safe here as ex units counting is done during evaluation.
            // Redeemer would fail already if budget was negative.
            remainingBudget = ExBudget.fromCpuAndMemory(
              remainingBudget.cpu - evaluatedRedeemer.getExUnits.getSteps.longValue,
              remainingBudget.memory - evaluatedRedeemer.getExUnits.getMem.longValue
            )

            evaluatedRedeemer
        }
        collectedRedeemers
    }

    final class RestrictingBudgetSpenderWithScripDump(val maxBudget: ExBudget)
        extends BudgetSpender {
        private var cpuLeft: Long = maxBudget.cpu
        private var memoryLeft: Long = maxBudget.memory

        def spendBudget(cat: ExBudgetCategory, budget: ExBudget, env: CekValEnv): Unit = {
            if debugDumpFilesForTesting then
                cat match
                    case ExBudgetCategory.BuiltinApp(fun) =>
                        Files.write(
                          java.nio.file.Paths.get("scalus.log"),
                          s"fun $$${fun}, cost: ExBudget { mem: ${budget.memory}, cpu: ${budget.cpu} }\n".getBytes,
                          java.nio.file.StandardOpenOption.CREATE,
                          java.nio.file.StandardOpenOption.APPEND
                        )
                    case _ =>
            cpuLeft -= budget.cpu
            memoryLeft -= budget.memory
            if cpuLeft < 0 || memoryLeft < 0 then throw new OutOfExBudgetError(maxBudget, env)
        }

        def getSpentBudget: ExBudget =
            ExBudget.fromCpuAndMemory(maxBudget.cpu - cpuLeft, maxBudget.memory - memoryLeft)

        def reset(): Unit = {
            cpuLeft = maxBudget.cpu
            memoryLeft = maxBudget.memory
        }
    }

}

object TxEvaluator {
    type ScriptHash = ByteString
    type Hash = ByteString

    case class LookupTable(
        scripts: collection.Map[ScriptHash, ScriptVersion],
        datums: collection.Map[Hash, Data]
    )

    def getAllResolvedScripts(
        tx: Transaction,
        utxos: Map[TransactionInput, TransactionOutput]
    ): Map[ScriptHash, ScriptVersion] = {
        val scripts =
            def decodeToFlat(script: PlutusScript) =
                // unwrap the outer CBOR encoding
                val decoded = Cbor.decode(Hex.hexToBytes(script.getCborHex)).to[Array[Byte]].value
                // and decode the inner CBOR encoding. Don't ask me why.
                ByteString.fromArray(Cbor.decode(decoded).to[Array[Byte]].value)

            val native = tx.getWitnessSet.getNativeScripts.asScala
                .map: script =>
                    ByteString.fromArray(script.getScriptHash) -> ScriptVersion.Native

            val v1 = tx.getWitnessSet.getPlutusV1Scripts.asScala
                .map: script =>
                    val flatScript = decodeToFlat(script)
                    ByteString.fromArray(script.getScriptHash) -> ScriptVersion.PlutusV1(flatScript)
            val v2 = tx.getWitnessSet.getPlutusV2Scripts.asScala
                .map: script =>
                    val flatScript = decodeToFlat(script)
                    ByteString.fromArray(script.getScriptHash) -> ScriptVersion.PlutusV2(flatScript)
            val v3 = tx.getWitnessSet.getPlutusV3Scripts.asScala
                .map: script =>
                    val flatScript = decodeToFlat(script)
                    ByteString.fromArray(script.getScriptHash) -> ScriptVersion.PlutusV3(flatScript)
            native ++ v1 ++ v2 ++ v3

        val referenceScripts = ArrayBuffer.empty[(ScriptHash, ScriptVersion)]

        for output <- utxos.values do
            if output.getScriptRef != null then
                val scriptInfo = Interop.getScriptInfoFromScriptRef(output.getScriptRef)
                referenceScripts += scriptInfo.hash -> scriptInfo.scriptVersion

        (scripts ++ referenceScripts).toMap
    }

    private def getScriptAndDatumLookupTable(
        tx: Transaction,
        datums: collection.Seq[(ByteString, Data)],
        utxos: Map[TransactionInput, TransactionOutput]
    ): LookupTable = {
        val allScripts = getAllResolvedScripts(tx, utxos)
        LookupTable(allScripts, datums.toMap)
    }

    private def evalPhaseOne(
        tx: Transaction,
        utxos: Map[TransactionInput, TransactionOutput],
        lookupTable: LookupTable
    ): Unit = {
        val scripts = scriptsNeeded(tx, utxos)
        validateMissingScripts(scripts, lookupTable.scripts)
        verifyExactSetOfRedeemers(tx, scripts, lookupTable.scripts)
    }

    type AlonzoScriptsNeeded = immutable.Seq[ScriptHash]

    def scriptsNeeded(
        tx: Transaction,
        utxos: Map[TransactionInput, TransactionOutput]
    ): AlonzoScriptsNeeded = {
        val needed = ArrayBuffer.empty[ScriptHash]
        val txb = tx.getBody

        for input <- txb.getInputs.asScala do
            val output = utxos.getOrElse(input, throw new IllegalStateException("Input not found"))
            val address = Address(output.getAddress)

            // if we spend a script output, we need the script
            if address.getPaymentCredential.get.getType == CredentialType.Script then
                needed += ByteString.fromArray(address.getPaymentCredentialHash.orElseThrow())

        for withdrawal <- txb.getWithdrawals.asScala do
            val address = Address(withdrawal.getRewardAddress)
            if address.getAddressType == AddressType.Reward then
                getCredential(address.getDelegationCredential.get) match
                    case api.v1.Credential.ScriptCredential(hash) =>
                        needed += hash
                    case _ =>

        for cert <- txb.getCerts.asScala do
            val maybeHash = getCertScript(cert)
            maybeHash.foreach: hash =>
                needed += hash

        for mint <- txb.getMint.asScala do
            val policyId = ByteString.fromHex(mint.getPolicyId)
            needed += policyId

        for
            vp <- Option(txb.getVotingProcedures)
            voter <- vp.getVoting.keySet.asScala
        do
            val v = Interop.getVoterV3(voter)
            v match
                case v3.Voter.CommitteeVoter(v1.Credential.ScriptCredential(hash)) =>
                    needed += hash
                case v3.Voter.DRepVoter(v1.Credential.ScriptCredential(hash)) =>
                    needed += hash
                case _ => // no script needed
                    ()

        for
            proposals <- Option(txb.getProposalProcedures)
            propose <- proposals.asScala
        do
            getProposalScriptHash(propose).foreach: hash =>
                needed += hash
        needed.toSeq
    }

    private def getProposalScriptHash(propose: ProposalProcedure): Option[ScriptHash] = {
        val procedure = Interop.getProposalProcedureV3(propose)
        procedure.governanceAction match
            case v3.GovernanceAction.ParameterChange(_, _, constitutionScript) =>
                constitutionScript.toOption
            case v3.GovernanceAction.TreasuryWithdrawals(_, constitutionScript) =>
                constitutionScript.toOption
            case _ => None
    }

    private def getCertScript(cert: Certificate): Option[ScriptHash] = {
        cert match
            case c: UnregCert               => credScriptHash(c.getStakeCredential)
            case c: StakeDelegation         => credScriptHash(c.getStakeCredential)
            case c: StakeDeregistration     => credScriptHash(c.getStakeCredential)
            case c: StakeRegDelegCert       => credScriptHash(c.getStakeCredential)
            case c: StakeVoteDelegCert      => credScriptHash(c.getStakeCredential)
            case c: StakeVoteRegDelegCert   => credScriptHash(c.getStakeCredential)
            case c: VoteDelegCert           => credScriptHash(c.getStakeCredential)
            case c: VoteRegDelegCert        => credScriptHash(c.getStakeCredential)
            case c: AuthCommitteeHotCert    => credScriptHash(c.getCommitteeColdCredential)
            case c: ResignCommitteeColdCert => credScriptHash(c.getCommitteeColdCredential)
            case c: RegDRepCert             => credScriptHash(c.getDrepCredential)
            case c: UnregDRepCert           => credScriptHash(c.getDrepCredential)
            case c: UpdateDRepCert          => credScriptHash(c.getDrepCredential)
            case _                          => None
    }

    private def credScriptHash(cred: Credential): Option[ScriptHash] = {
        cred.getType match
            case CredentialType.Key    => None
            case CredentialType.Script => Some(ByteString.fromArray(cred.getBytes))
    }

    private def credScriptHash(cred: StakeCredential): Option[ScriptHash] = {
        cred.getType match
            case StakeCredType.ADDR_KEYHASH => None
            case StakeCredType.SCRIPTHASH   => Some(ByteString.fromArray(cred.getHash))
    }

    private def validateMissingScripts(
        scripts: AlonzoScriptsNeeded,
        txScripts: collection.Map[ScriptHash, ScriptVersion]
    ): Unit = {
        val received = txScripts.keySet
        val needed = scripts.toSet
        val missing = needed.diff(received)
        if missing.nonEmpty then throw new IllegalStateException(s"Missing scripts: $missing")
    }

    private def verifyExactSetOfRedeemers(
        @unused tx: Transaction,
        @unused scripts: AlonzoScriptsNeeded,
        @unused txScripts: collection.Map[ScriptHash, ScriptVersion]
    ): Unit = {
        // FIXME: implement
    }

    /// builds a redeemer pointer (tag, index) from a script purpose by setting the tag
    /// according to the type of the script purpose, and the index according to the
    /// placement of script purpose inside its container.
    private def buildRedeemerPtr(
        tx: Transaction,
        purpose: v1.ScriptPurpose
    ): Option[Redeemer] = {
        ??? // FIXME: implement
    }
}
