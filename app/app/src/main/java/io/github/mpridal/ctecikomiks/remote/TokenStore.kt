package io.github.mpridal.ctecikomiks.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * GitHub token pro odesílání záznamů, šifrovaný klíčem v Android Keystore (SPEC: Soukromí).
 * Token zadává rodič v nastavení; v kódu ani v repu není.
 */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return gen.generateKey()
    }

    fun save(token: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.doFinal(token.trim().toByteArray())
        prefs.edit()
            .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .putString("token", Base64.encodeToString(enc, Base64.NO_WRAP))
            .apply()
    }

    fun load(): String? = runCatching {
        val iv = Base64.decode(prefs.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val enc = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(c.doFinal(enc))
    }.getOrNull()

    fun clear() = prefs.edit().remove("iv").remove("token").apply()

    val hasToken: Boolean get() = prefs.contains("token")

    /** Krátké náhodné id instalace (rozliší zařízení v repu logů, bez osobních údajů). */
    val installId: String
        get() = prefs.getString("installId", null) ?: java.util.UUID.randomUUID().toString().take(8).also {
            prefs.edit().putString("installId", it).apply()
        }

    companion object { private const val ALIAS = "cteci-komiks-log-token" }
}
