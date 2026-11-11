package app.anbu.car.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore의 AES-256-GCM 키로 암호화해 앱 전용 폴더에 저장한다.
 *
 * 키는 Keystore 밖으로 나오지 않는다. [wipe]는 파일과 함께 키를 지우므로,
 * 파일 조각이 남더라도 다시 읽을 수 없다.
 */
class KeystoreBlobStore(context: Context) : BlobStore {

    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val lock = Any()

    override fun read(name: String): String? = synchronized(lock) {
        val f = fileOf(name)
        if (!f.exists()) return null
        return try {
            val bytes = f.readBytes()
            if (bytes.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // 키가 바뀌었거나 파일이 손상된 경우. 읽을 수 없는 파일은 버린다.
            Log.w(TAG, "vault read failed for $name: ${e.javaClass.simpleName}")
            f.delete()
            null
        }
    }

    override fun write(name: String, value: String): Unit = synchronized(lock) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val tmp = File(dir, "$name.tmp")
        tmp.outputStream().use {
            it.write(cipher.iv)
            it.write(body)
        }
        if (!tmp.renameTo(fileOf(name))) {
            fileOf(name).delete()
            tmp.renameTo(fileOf(name))
        }
    }

    override fun delete(name: String): Unit = synchronized(lock) {
        fileOf(name).delete()
    }

    override fun wipe(): Unit = synchronized(lock) {
        dir.listFiles()?.forEach { it.delete() }
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
    }

    private fun fileOf(name: String) = File(dir, "$name.bin")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val TAG = "AnbuVault"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "anbu.vault.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
