package scalus.bloxbean

import co.nstant.in.cbor.model.UnsignedInteger
import com.bloxbean.cardano.client.account.Account
import com.bloxbean.cardano.client.address.AddressProvider
import com.bloxbean.cardano.client.api.UtxoSupplier
import com.bloxbean.cardano.client.api.common.OrderEnum
import com.bloxbean.cardano.client.api.model.Amount
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.api.util.CostModelUtil
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier
import com.bloxbean.cardano.client.backend.api.ScriptService
import com.bloxbean.cardano.client.backend.blockfrost.common.Constants
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService
import com.bloxbean.cardano.client.backend.blockfrost.service.http.ScriptApi
import com.bloxbean.cardano.client.coinselection.impl.DefaultUtxoSelector
import com.bloxbean.cardano.client.common.ADAConversionUtil
import com.bloxbean.cardano.client.common.CardanoConstants
import com.bloxbean.cardano.client.common.model.Networks
import com.bloxbean.cardano.client.function.helper.SignerProviders
import com.bloxbean.cardano.client.plutus.spec.*
import com.bloxbean.cardano.client.plutus.util.PlutusUtil
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder
import com.bloxbean.cardano.client.quicktx.ScriptTx
import com.bloxbean.cardano.client.spec.Script
import com.bloxbean.cardano.client.transaction.spec.*
import com.bloxbean.cardano.client.transaction.util.TransactionUtil
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil
import com.fasterxml.jackson.databind.ObjectMapper
import io.bullet.borer.Cbor
import org.scalatest.funsuite.AnyFunSuite
import scalus.*
import scalus.Compiler.compile
import scalus.builtin.ByteString
import scalus.builtin.ByteString.given
import scalus.builtin.Data
import scalus.examples.MintingPolicyV2
import scalus.examples.PubKeyValidator
import scalus.prelude.AssocMap
import scalus.uplc.*
import scalus.uplc.TermDSL.{*, given}
import scalus.uplc.eval.ExBudget
import scalus.utils.Utils

import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Paths
import java.util
import java.util.Optional
import co.nstant.in.cbor.model as cbor
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

class TxEvaluatorSpec extends AnyFunSuite:
    val senderMnemonic: String =
        "drive useless envelope shine range ability time copper alarm museum near flee wrist live type device meadow allow churn purity wisdom praise drop code";
    val sender1 = new Account(Networks.testnet(), senderMnemonic)
    val sender1Addr: String = sender1.baseAddress()

    test("TxEvaluator ") {
        import scala.jdk.CollectionConverters.*
        val costMdls = CostMdls()
        costMdls.add(CostModelUtil.PlutusV1CostModel)
        costMdls.add(CostModelUtil.PlutusV2CostModel)
        val evaluator = TxEvaluator(
          SlotConfig.default,
          initialBudget = ExBudget.fromCpuAndMemory(10_000000000L, 10_000000L),
          protocolMajorVersion = 8,
          costMdls = costMdls
        )
        val pubKeyValidator =
            compile(PubKeyValidator.validatorV2(hex"deadbeef")).toPlutusProgram((1, 0, 0))
        val s: PlutusV2Script =
            PlutusV2Script
                .builder()
                .cborHex(pubKeyValidator.doubleCborHex)
                .build()
                .asInstanceOf[PlutusV2Script]
        val scripts: util.List[PlutusScript] = util.List.of(s)
        val pubKeyScriptAddress = AddressProvider.getEntAddress(s, Networks.testnet())
        println(
          s"Pubkey script address: ${pubKeyScriptAddress.getAddress}, type hash: ${pubKeyScriptAddress.getPaymentCredentialHash
                  .map(ByteString.fromArray)}"
        )

        val input = TransactionInput.builder().transactionId("deadbeef").index(0).build()
        val inputs = util.List.of(input)

        val utxo = Map(
          input -> TransactionOutput
              .builder()
              .value(Value.builder().coin(BigInteger.valueOf(20)).build())
              .address(pubKeyScriptAddress.getAddress)
              .datumHash(Utils.hexToBytes(PlutusData.unit().getDatumHash))
              .build()
        )
        val redeemer = Redeemer
            .builder()
            .tag(RedeemerTag.Spend)
            .data(PlutusData.unit())
            .index(BigInteger.ZERO)
            .exUnits(
              ExUnits
                  .builder()
                  .mem(BigInteger.valueOf(0L))
                  .steps(BigInteger.valueOf(0L))
                  .build()
            )
            .build()
        val tx = Transaction
            .builder()
            .body(
              TransactionBody
                  .builder()
                  .fee(ADAConversionUtil.adaToLovelace(0.2))
                  .ttl(1000)
                  .inputs(inputs)
                  .requiredSigners(util.List.of(hex"deadbeef".bytes))
                  .build()
            )
            .witnessSet(
              TransactionWitnessSet
                  .builder()
                  .plutusV2Scripts(util.List.of(s))
                  .redeemers(util.List.of(redeemer))
                  .plutusDataList(util.List.of(PlutusData.unit()))
                  .build()
            )
            .build()
        val redeemers = evaluator.evaluateTx(tx, utxo)
        println(redeemers)
    }

    test("asdf") {
        val scriptcbor =
            "59014f59014c01000032323232323232322223232325333009300e30070021323233533300b3370e9000180480109118011bae30100031225001232533300d3300e22533301300114a02a66601e66ebcc04800400c5288980118070009bac3010300c300c300c300c300c300c300c007149858dd48008b18060009baa300c300b3754601860166ea80184ccccc0288894ccc04000440084c8c94ccc038cd4ccc038c04cc030008488c008dd718098018912800919b8f0014891ce1317b152faac13426e6a83e06ff88a4d62cce3c1634ab0a5ec133090014a0266008444a00226600a446004602600a601a00626600a008601a006601e0026ea8c03cc038dd5180798071baa300f300b300e3754601e00244a0026eb0c03000c92616300a001375400660106ea8c024c020dd5000aab9d5744ae688c8c0088cc0080080048c0088cc00800800555cf2ba15573e6e1d200201"
        val scriptFlat = Cbor
            .decode(Cbor.decode(Utils.hexToBytes(scriptcbor)).to[Array[Byte]].value)
            .to[Array[Byte]]
            .value
        val program = ProgramFlatCodec.decodeFlat(scriptFlat)
        val namedTerm = DeBruijn.fromDeBruijnTerm(program.term)
        println(namedTerm.prettyXTerm.render(120))
    }

    ignore("Blockfrost testnet evaluate tx with minting policy v2") {
        val apiKey = System.getenv("BLOCKFROST_API_KEY")
        val backendService = new BFBackendService(Constants.BLOCKFROST_MAINNET_URL, apiKey)
        val utxoSupplier = new DefaultUtxoSupplier(backendService.getUtxoService)
        val protocolParamsSupplier =
            new DefaultProtocolParamsSupplier(backendService.getEpochService)
        val evaluator =
            ScalusTransactionEvaluator(utxoSupplier, protocolParamsSupplier, scriptSupplier = null)
        val utxoSelector = new DefaultUtxoSelector(utxoSupplier)
        val utxoOptional = utxoSelector.findFirst(
          sender1Addr,
          utxo =>
              utxo.getAmount
                  .stream()
                  .anyMatch(a =>
                      CardanoConstants.LOVELACE.equals(a.getUnit) && a.getQuantity
                          .compareTo(ADAConversionUtil.adaToLovelace(2)) >= 0
                  )
        ); // Find an utxo with at least 2 ADA

        val utxo = utxoOptional.orElseThrow()
        val txId = ByteString.fromHex(utxo.getTxHash)
        val idx = BigInt(utxo.getOutputIndex)
        val validator =
            MintingPolicyV2.compiledMintingPolicyScriptV2.toUplc(generateErrorTraces = true)
        val evaledTokens =
            val tokensSIR = compile(AssocMap.singleton(hex"484f534b59", BigInt("1000000000000000")))
            tokensSIR.toUplc()

        val appliedValidator =
            validator $ txId $ idx $ evaledTokens
        val program = Program((1, 0, 0), appliedValidator)
        val script = PlutusV2Script.builder().cborHex(program.doubleCborHex).build()
        val scriptTx = new ScriptTx()
            .collectFrom(utxo)
            .mintAsset(
              script,
              new Asset("SCALUS", BigInteger.valueOf(1)),
              PlutusData.unit(),
              sender1Addr
            )
        val result = new QuickTxBuilder(backendService)
            .compose(scriptTx)
            .feePayer(sender1Addr)
            .withSigner(SignerProviders.signerFrom(sender1))
            .withTxEvaluator(evaluator)
            .withTxInspector(transaction => {
                System.out.println(transaction)
            })
            .completeAndWait()
    }