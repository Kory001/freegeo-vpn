package org.freegeo.vpn.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecurePrefs(context: Context) {

    private val plain: SharedPreferences =
        context.getSharedPreferences("freegeo_secure", Context.MODE_PRIVATE)

    var registryUrl: String
        get() = decrypt(plain.getString(KEY_REGISTRY_URL, null)) ?: ""
        set(value) = plain.edit().putString(KEY_REGISTRY_URL, encrypt(value)).apply()

    var selectedNodeId: String?
        get() = decrypt(plain.getString(KEY_SELECTED_NODE, null))
        set(value) = plain.edit()
            .putString(KEY_SELECTED_NODE, value?.let(::encrypt))
            .apply()

    fun favorites(): Set<String> =
        decrypt(plain.getString(KEY_FAVORITES, null))
            ?.split(",")?.filter { it.isNotBlank() }?.toSet()
            ?: emptySet()

    fun setFavorites(ids: Set<String>) = plain.edit()
        .putString(KEY_FAVORITES, encrypt(ids.joinToString(",")))
        .apply()

    fun toggleFavorite(id: String): Set<String> {
        val current = favorites()
        val next = if (id in current) current - id else current + id
        setFavorites(next)
        return next
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(iv + data, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String? {
        stored ?: return null
        return runCatching {
            val all = android.util.Base64.decode(stored, android.util.Base64.NO_WRAP)
            val iv = all.copyOfRange(0, 12)
            val data = all.copyOfRange(12, all.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        }.getOrNull()
    }

    var warpAccountJson: String?
        get() = decrypt(plain.getString(KEY_WARP_ACCOUNT, null))
        set(value) = plain.edit()
            .putString(KEY_WARP_ACCOUNT, value?.let(::encrypt))
            .apply()

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "freegeo_master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_REGISTRY_URL = "registry_url"
        private const val KEY_SELECTED_NODE = "selected_node"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_WARP_ACCOUNT = "warp_account"
    }
}
