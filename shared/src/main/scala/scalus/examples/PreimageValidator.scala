package scalus.examples

import scalus.*
import scalus.Compiler.compile
import scalus.Compiler.fieldAsData
import scalus.builtin.Builtins.*
import scalus.builtin.ByteString
import scalus.builtin.FromDataInstances.given
import scalus.builtin.Data
import scalus.builtin.Data.fromData
import scalus.builtin.given
import scalus.ledger.api.v2.*
import scalus.ledger.api.v2.FromDataInstances.given
import scalus.prelude.List
import scalus.sir.SIR
import scalus.uplc.*

@Compile
object PreimageValidator {
    def preimageValidator(datum: Data, redeemer: Data, ctxData: Data): Unit = {
        // deserialize from Data
        val (hash, pkh) = fromData[(ByteString, ByteString)](datum)
        val preimage = fromData[ByteString](redeemer)
        val ctx = fromData[ScriptContext](ctxData)
        // get the transaction signatories
        val signatories = ctx.txInfo.signatories
        // check that the transaction is signed by the public key hash
        List.findOrFail(signatories) { sig => sig.hash == pkh }
        // check that the preimage hashes to the hash
        if sha2_256(preimage) == hash then ()
        else throw new RuntimeException("Wrong preimage")
        // throwing an exception compiles to UPLC error
    }
}

@Compile
object OptimizedPreimageValidator {

    /** Validates that the preimage is correct for the given hash and public key hash. The public
      * key hash must be a signatory of the transaction.
      */
    def preimageValidator(datum: Data, redeemer: Data, ctxData: Data): Unit = {
        // datum is a pair of 2 bytestrings: sha2_256(preimage) and public key hash
        val pair = unConstrData(datum).snd
        // get the hash
        inline def hash = unBData(pair.head)
        // get the public key hash
        val pkh = pair.tail.head
        // get the preimage
        inline def preimage = unBData(redeemer)
        def checkSignatories(sigs: builtin.List[Data]): Unit =
            if trace("sig.head")(sigs.head) == pkh then trace("signed")(())
            else checkSignatories(sigs.tail)
        // get the signatories of the transaction
        inline def sigs = unListData(fieldAsData[ScriptContext](_.txInfo.signatories)(ctxData))
        checkSignatories(sigs)
        sha2_256(preimage) == hash || (throw new RuntimeException("Wrong"))
    }
}

object OptimizedPreimage {
    val compiledOptimizedPreimageValidator: SIR = compile(
      OptimizedPreimageValidator.preimageValidator
    )
    val validator: Term = compiledOptimizedPreimageValidator.toUplc(generateErrorTraces = true)
    val programV1: Program = Program((1, 0, 0), validator)
    // val cbor = Cbor.encode(flatEncoded).toByteArray
    // val cborHex = Utils.bytesToHex(Cbor.encode(flatEncoded).toByteArray)
    val doubleCborHex: String = Program((1, 0, 0), validator).doubleCborHex
}
