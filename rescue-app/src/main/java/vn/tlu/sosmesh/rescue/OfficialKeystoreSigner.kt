package vn.tlu.sosmesh.rescue

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import vn.tlu.sosmesh.coremap.alert.AlertSignatureVerifier
import vn.tlu.sosmesh.coremap.db.MeshMessageEntity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Base64

object OfficialKeystoreSigner {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEYSTORE_ALIAS = "vn.tlu.sosmesh.official.ecdsa.p256"

    @Synchronized
    fun ensureOfficialKeyPairAndTrust(context: Context): String {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(KEYSTORE_ALIAS)) {
            val kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        val cert = ks.getCertificate(KEYSTORE_ALIAS)
        val pubB64 = Base64.getEncoder().encodeToString(cert.publicKey.encoded)
        AlertSignatureVerifier.registerTrustedOfficialPublicKey(context, pubB64)
        return pubB64
    }

    @Synchronized
    fun signOfficialAlert(context: Context, unsignedAlert: MeshMessageEntity): MeshMessageEntity {
        ensureOfficialKeyPairAndTrust(context)
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(KEYSTORE_ALIAS, null) as KeyStore.PrivateKeyEntry
        return AlertSignatureVerifier.signSignedAlertPacket(unsignedAlert, entry.privateKey)
    }
}
