package vn.tlu.sosmesh.coremap.alert

import android.content.Context
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet

object AlertSignatureVerifier {
    private const val KEY_ALGORITHM = "EC"
    private const val CURVE_NAME = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val PREFS_TRUST_STORE = "sos_mesh_official_trust_store"
    private const val KEY_TRUSTED_PUBS = "trusted_official_public_keys"

    /**
     * Default embedded X.509 SubjectPublicKeyInfo (ECDSA P-256) trust list.
     * Only public keys are stored here; private keys live exclusively in Android Keystore
     * on authorized commander devices.
     */
    val DEFAULT_TRUSTED_PUBLIC_KEYS_B64: Set<String> = setOf(
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERY/oHjJ4Qla3KgFh1Jw9dhdLX3cqz9shtI8X3jQ4syIykFyDnGOw6qC1dfF7Pv4cRvn8FdfcPdyubNokrJr1Sw=="
    )

    private val trustedPublicKeysB64 = CopyOnWriteArraySet<String>(DEFAULT_TRUSTED_PUBLIC_KEYS_B64)

    fun getTrustedPublicKeys(): Set<String> = LinkedHashSet(trustedPublicKeysB64)

    fun loadPersistedTrustStore(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_TRUST_STORE, Context.MODE_PRIVATE)
        val saved = prefs.getStringSet(KEY_TRUSTED_PUBS, emptySet()).orEmpty()
        trustedPublicKeysB64.addAll(DEFAULT_TRUSTED_PUBLIC_KEYS_B64)
        trustedPublicKeysB64.addAll(saved)
    }

    fun registerTrustedOfficialPublicKey(context: Context, publicKeyB64: String): Boolean {
        val clean = publicKeyB64.trim()
        val valid = runCatching { decodePublicKey(clean) }.isSuccess
        if (!valid) return false
        trustedPublicKeysB64.add(clean)
        val prefs = context.applicationContext.getSharedPreferences(PREFS_TRUST_STORE, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_TRUSTED_PUBS, LinkedHashSet(trustedPublicKeysB64)).apply()
        return true
    }

    fun addTrustedPublicKeyForTest(publicKeyB64: String) {
        trustedPublicKeysB64.add(publicKeyB64.trim())
    }

    fun resetTrustStoreToDefaults() {
        trustedPublicKeysB64.clear()
        trustedPublicKeysB64.addAll(DEFAULT_TRUSTED_PUBLIC_KEYS_B64)
    }

    fun canonicalPayload(
        id: String,
        senderId: String,
        urgencyLevel: String,
        conditionSummary: String,
        content: String,
        createdAt: Long
    ): ByteArray {
        val text = "$id|$senderId|$urgencyLevel|$conditionSummary|$content|$createdAt"
        return text.toByteArray(Charsets.UTF_8)
    }

    fun canonicalPayload(packet: MeshMessageEntity): ByteArray = canonicalPayload(
        id = packet.id,
        senderId = packet.senderId,
        urgencyLevel = packet.urgencyLevel,
        conditionSummary = packet.conditionSummary,
        content = packet.content,
        createdAt = packet.createdAt
    )

    fun signAlert(packet: MeshMessageEntity, signerKey: PrivateKey): String {
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(signerKey)
        signer.update(canonicalPayload(packet))
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    fun signSignedAlertPacket(packet: MeshMessageEntity, signerKey: PrivateKey): MeshMessageEntity {
        val sig = signAlert(packet, signerKey)
        return packet.copy(signature = sig)
    }

    fun verifyAlert(
        packet: MeshMessageEntity,
        trustedKeysB64: Set<String> = getTrustedPublicKeys()
    ): Boolean {
        val signatureB64 = packet.signature?.trim()
        if (signatureB64.isNullOrEmpty() || trustedKeysB64.isEmpty()) return false
        val sigBytes = runCatching { Base64.getDecoder().decode(signatureB64) }.getOrNull() ?: return false
        val payloadBytes = canonicalPayload(packet)
        return trustedKeysB64.any { pubB64 ->
            runCatching {
                val publicKey = decodePublicKey(pubB64)
                val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
                verifier.initVerify(publicKey)
                verifier.update(payloadBytes)
                verifier.verify(sigBytes)
            }.getOrDefault(false)
        }
    }

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM)
        kpg.initialize(ECGenParameterSpec(CURVE_NAME))
        return kpg.generateKeyPair()
    }

    private fun decodePublicKey(publicKeyB64: String): PublicKey {
        val bytes = Base64.getDecoder().decode(publicKeyB64)
        val kf = KeyFactory.getInstance(KEY_ALGORITHM)
        return kf.generatePublic(X509EncodedKeySpec(bytes))
    }
}
