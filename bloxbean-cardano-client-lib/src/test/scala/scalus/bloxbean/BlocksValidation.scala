package scalus.bloxbean

import co.nstant.in.cbor.CborException
import co.nstant.in.cbor.model as cbor
import co.nstant.in.cbor.model.SimpleValue
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier
import com.bloxbean.cardano.client.backend.blockfrost.common.Constants
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService
import com.bloxbean.cardano.client.transaction.spec.*
import com.bloxbean.cardano.client.transaction.util.TransactionUtil
import com.bloxbean.cardano.yaci.core.model.serializers.util.TransactionBodyExtractor
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil
import com.bloxbean.cardano.yaci.core.model.serializers.util.WitnessUtil.getArrayBytes
import com.bloxbean.cardano.yaci.core.util.CborSerializationUtil
import scalus.*
import scalus.bloxbean.Interop.??
import scalus.builtin.ByteString
import scalus.utils.Utils

import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util
import java.util.stream.Collectors
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Setup BLOCKFROST_API_KEY environment variable before running this test
  */
object BlocksValidation:

    lazy val apiKey = System.getenv("BLOCKFROST_API_KEY") ?? sys.error(
      "BLOCKFROST_API_KEY is not set, please set it before running the test"
    )

    private def validateBlocksOfEpoch(epoch: Int): Unit = {
        import com.bloxbean.cardano.yaci.core.config.YaciConfig
        YaciConfig.INSTANCE.setReturnBlockCbor(true) // needed to get the block cbor
        YaciConfig.INSTANCE.setReturnTxBodyCbor(true) // needed to get the tx body cbor

        val cwd = Paths.get("bloxbean-cardano-client-lib")
        val backendService = new BFBackendService(Constants.BLOCKFROST_MAINNET_URL, apiKey)
        val utxoSupplier = CachedUtxoSupplier(
          cwd.resolve("utxos"),
          DefaultUtxoSupplier(backendService.getUtxoService)
        )
        // memory and file cached script supplier using the script service
        val scriptSupplier = InMemoryCachedScriptSupplier(
          FileScriptSupplier(
            cwd.resolve("scripts"),
            ScriptServiceSupplier(backendService.getScriptService)
          )
        )
        val protocolParams = backendService.getEpochService.getProtocolParameters(epoch).getValue
        val evaluator = ScalusTransactionEvaluator(
          protocolParams,
          utxoSupplier,
          scriptSupplier,
          EvaluatorMode.VALIDATE
        )

        var totalTx = 0
        val errors = mutable.ArrayBuffer[(String, Int, String)]()
        val v1Scripts = mutable.HashSet.empty[String]
        var v1ScriptsExecuted = 0
        val v2Scripts = mutable.HashSet.empty[String]
        var v2ScriptsExecuted = 0

        for blockNum <- 10294189 to 10315649 do
            val txs = readTransactionsFromBlockCbor(cwd.resolve(s"blocks/block-$blockNum.cbor"))
            val withScriptTxs = txsToEvaluate(txs)
            println(s"Block $blockNum, num txs to validate: ${withScriptTxs.size}")

            for (tx, datums) <- withScriptTxs do {
                val txhash = TransactionUtil.getTxHash(tx)
//                println(s"Validating tx $txhash")
                //                println(tx)

                for scripts <- Option(tx.getWitnessSet.getPlutusV1Scripts) do
                    v1Scripts ++= scripts.asScala.map(s => Utils.bytesToHex(s.getScriptHash))
                    v1ScriptsExecuted += 1

                for scripts <- Option(tx.getWitnessSet.getPlutusV2Scripts) do
                    v2Scripts ++= scripts.asScala.map(s => Utils.bytesToHex(s.getScriptHash))
                    v2ScriptsExecuted += 1

                if tx.isValid then
//                println(s"datum hashes ${datumHashes.asScala.map(_.toHex)}")
                    val result = evaluator.evaluateTx(tx, util.Set.of(), datums)
                    totalTx += 1
                    if !result.isSuccessful then
                        errors += ((result.getResponse, blockNum, txhash))
                        println(
                          s"${Console.RED}AAAA!!!! $txhash ${result.getResponse}${Console.RESET}"
                        )
                else
                    println(s"${Console.RED}AAAAA invalid!!! $txhash ${Console.RESET}")
                    errors += (("Invalid tx", blockNum, txhash))
            }

//                println(tx.getWitnessSet.getRedeemers)
//                println("----------------------------------------------------")
            println(s"=======================================")
        println(s"""Total txs: $totalTx,
               |errors: $errors,
               |v1: $v1ScriptsExecuted of ${v1Scripts.size},
               |v2: $v2ScriptsExecuted of ${v2Scripts.size}""".stripMargin)

    }

    private def txsToEvaluate(txs: collection.Seq[(Transaction, util.List[ByteString])]) = {
        txs.filter { case (tx, _) =>
            tx.getWitnessSet.getPlutusV1Scripts != null || tx.getWitnessSet.getPlutusV2Scripts != null
        }
    }

    def readTransactionsFromBlockCbor(
        path: Path
    ): collection.Seq[(Transaction, util.List[ByteString])] = {
        val blockBytes = Utils.hexToBytes(new String(Files.readAllBytes(path)))
        readTransactionsFromBlockCbor(blockBytes)
    }

    def readTransactionsFromBlockCbor(
        blockCbor: Array[Byte]
    ): collection.Seq[(Transaction, util.List[ByteString])] = {
        val array = CborSerializationUtil.deserializeOne(blockCbor).asInstanceOf[cbor.Array]
        val blockArray = array.getDataItems.get(1).asInstanceOf[cbor.Array]
        val witnessesListArr = blockArray.getDataItems.get(2).asInstanceOf[cbor.Array]
        val invalidTxs = blockArray.getDataItems
            .get(4)
            .asInstanceOf[cbor.Array]
            .getDataItems
            .stream()
            .map(_.asInstanceOf[cbor.Number].getValue.intValue())
            .collect(Collectors.toSet())
        val txBodyTuples = TransactionBodyExtractor.getTxBodiesFromBlock(blockCbor)
        val witnesses = WitnessUtil.getWitnessRawData(blockCbor)
        val txBodyDatumsCbor = witnesses.asScala.zipWithIndex
            .map { case (witness, idx) =>
                try
                    val fields = WitnessUtil.getWitnessFields(witness)
                    val datums = fields.get(BigInteger.valueOf(4L))
                    if datums != null then
                        getArrayBytes(datums)
                            .stream()
                            .map(ByteString.fromArray)
                            .collect(Collectors.toList())
                    else util.Collections.emptyList()
                catch
                    case e: CborException =>
                        e.printStackTrace();
                        util.Collections.emptyList();
            }
        for ((tuple, datumsCbor), idx) <- txBodyTuples.asScala.zip(txBodyDatumsCbor).zipWithIndex
        yield
            val txbody = TransactionBody.deserialize(tuple._1.asInstanceOf[cbor.Map])
            val witnessSetDataItem = witnessesListArr.getDataItems.get(idx).asInstanceOf[cbor.Map]
            val witnessSet = TransactionWitnessSet.deserialize(witnessSetDataItem)
            val isValid = !invalidTxs.contains(idx)
            val txbytes =
                val array = new cbor.Array()
                array.add(tuple._1)
                array.add(witnessSetDataItem)
                array.add(if isValid then SimpleValue.TRUE else SimpleValue.FALSE)
                array.add(SimpleValue.NULL)
                CborSerializationUtil.serialize(array)
//            Files.write(Paths.get(s"tx-${TransactionUtil.getTxHash(txbytes)}.cbor"), txbytes)
//            println(Utils.bytesToHex(txbytes))
            /*val witnessMap = witnessesListArr.getDataItems.get(idx).asInstanceOf[cbor.Map]
            val plutusDataArray = witnessMap.get(new UnsignedInteger(4))
            if plutusDataArray != null then
                val plutusData = plutusDataArray.asInstanceOf[cbor.Array]
//                    println(s"size ${plutusData.getDataItems.size()}")
                plutusData.getDataItems.asScala.foreach { item =>
                    // get cbor hex
//                        println(s"${Utils.bytesToHex(CborSerializationUtil.serialize(item))}")
                }*/

            val transaction =
                Transaction.builder().body(txbody).witnessSet(witnessSet).isValid(isValid).build()
//            println(
//              s"txhash ${TransactionUtil.getTxHash(transaction)} ${TransactionUtil.getTxHash(txbytes)}"
//            )
//            println(s"tx ${Utils.bytesToHex(transaction.serialize())}")
            (transaction, datumsCbor)
    }

    def main(args: Array[String]): Unit = {
        validateBlocksOfEpoch(484)
    }
