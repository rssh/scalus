package scalus.builtin

import org.bitcoins.crypto.ECDigitalSignature
import org.bitcoins.crypto.ECPublicKey
import org.bitcoins.crypto.SchnorrDigitalSignature
import org.bitcoins.crypto.SchnorrPublicKey
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.jcajce.provider.digest.SHA3
import scalus.utils.Utils
import scodec.bits.ByteVector

object JVMPlatformSpecific extends JVMPlatformSpecific
trait JVMPlatformSpecific extends PlatformSpecific {
    override def sha2_256(bs: ByteString): ByteString =
        ByteString.unsafeFromArray(Utils.sha2_256(bs.bytes))

    override def sha3_256(bs: ByteString): ByteString =
        val digestSHA3 = new SHA3.Digest256()
        ByteString.unsafeFromArray(digestSHA3.digest(bs.bytes))

    override def blake2b_224(bs: ByteString): ByteString =
        val digest = new Blake2bDigest(224)
        digest.update(bs.bytes, 0, bs.length)
        val hash = new Array[Byte](digest.getDigestSize)
        digest.doFinal(hash, 0)
        ByteString.unsafeFromArray(hash)

    override def blake2b_256(bs: ByteString): ByteString =
        val digest = new Blake2bDigest(256)
        digest.update(bs.bytes, 0, bs.length)
        val hash = new Array[Byte](digest.getDigestSize)
        digest.doFinal(hash, 0)
        ByteString.unsafeFromArray(hash)

    override def verifySchnorrSecp256k1Signature(
        pk: ByteString,
        msg: ByteString,
        sig: ByteString
    ): Boolean = {
        if pk.length != 32 then
            throw new IllegalArgumentException(s"Invalid public key length ${pk.length}")
        if sig.length != 64 then
            throw new IllegalArgumentException(s"Invalid signature length ${sig.length}")
        val signature = SchnorrDigitalSignature(ByteVector(sig.bytes))
        SchnorrPublicKey(ByteVector(pk.bytes)).verify(ByteVector(msg.bytes), signature)
    }

    override def verifyEd25519Signature(pk: ByteString, msg: ByteString, sig: ByteString): Boolean =
        if pk.length != 32 then
            throw new IllegalArgumentException(s"Invalid public key length ${pk.length}")
        if sig.length != 64 then
            throw new IllegalArgumentException(s"Invalid signature length ${sig.length}")
        val pubKeyParams =
            try new Ed25519PublicKeyParameters(pk.bytes, 0)
            catch
                case e: IllegalArgumentException =>
                    return false
        val verifier = new Ed25519Signer()
        verifier.init(false, pubKeyParams)
        verifier.update(msg.bytes, 0, msg.length)
        verifier.verifySignature(sig.bytes)

    override def verifyEcdsaSecp256k1Signature(
        pk: ByteString,
        msg: ByteString,
        sig: ByteString
    ): Boolean =
        if pk.length != 33 then
            throw new IllegalArgumentException(s"Invalid public key length ${pk.length}")
        if msg.length != 32 then
            throw new IllegalArgumentException(s"Invalid message length ${msg.length}")
        if sig.length != 64 then
            throw new IllegalArgumentException(s"Invalid signature length ${sig.length}")
        val signature = ECDigitalSignature.fromRS(ByteVector(sig.bytes))
        ECPublicKey(ByteVector(pk.bytes)).verify(ByteVector(msg.bytes), signature)
}

given PlatformSpecific = JVMPlatformSpecific
