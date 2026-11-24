package `in`.maitri.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Screen 14 data that never leaves the phone.
 *
 * Medical notes are AES-256-GCM encrypted with a non-exportable Android Keystore key, bound to the
 * account uid as associated data, and kept in no-backup storage so they are not copied to cloud backups.
 * The profile photo is a square, downscaled copy without EXIF; it is also uploaded to private storage.
 */
internal class ProfileLocalStore(context: Context) {
    private val root = File(context.applicationContext.noBackupFilesDir, "profile")

    private fun fileFor(uid: String, kind: String): File {
        require(uid.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$digest.$kind")
    }

    fun readMedicalNotes(uid: String): String {
        val file = fileFor(uid, "notes")
        if (!file.exists()) return ""
        val blob = file.readBytes()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        cipher.updateAAD(uid.toByteArray())
        return String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES))
    }

    /** Blank notes delete the stored file rather than keeping an encrypted empty value. */
    fun writeMedicalNotes(uid: String, notes: String) {
        require(notes.length <= MEDICAL_NOTES_LIMIT)
        val file = fileFor(uid, "notes")
        if (notes.isBlank()) { file.delete(); return }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(uid.toByteArray())
        val blob = cipher.iv + cipher.doFinal(notes.trim().toByteArray())
        root.mkdirs()
        val temp = File(root, file.name + ".tmp")
        temp.writeBytes(blob)
        check(temp.renameTo(file) || (file.delete() && temp.renameTo(file)))
    }

    fun photo(uid: String): File? = fileFor(uid, "jpg").takeIf { it.exists() }

    /** Decodes, downsizes to at most 512 px and re-encodes, discarding EXIF data such as location. */
    fun savePhoto(context: Context, uid: String, source: Uri): File {
        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, source)) { decoder, info, _ ->
                val size = info.size
                val scale = minOf(1f, 512f / maxOf(size.width, size.height))
                decoder.setTargetSize((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(context.contentResolver, source).let {
                val scale = minOf(1f, 512f / maxOf(it.width, it.height))
                Bitmap.createScaledBitmap(it, (it.width * scale).toInt().coerceAtLeast(1), (it.height * scale).toInt().coerceAtLeast(1), true)
            }
        }
        // Square centre crop, as the profile circle shows it.
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        root.mkdirs()
        val file = fileFor(uid, "jpg")
        file.outputStream().use { check(square.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        return file
    }

    /** Stores a photo downloaded from the account (another phone set it). */
    fun savePhotoBytes(uid: String, bytes: ByteArray): File {
        root.mkdirs()
        return fileFor(uid, "jpg").also { it.writeBytes(bytes) }
    }

    private fun key(): SecretKey = synchronized(ProfileLocalStore::class.java) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    companion object {
        const val MEDICAL_NOTES_LIMIT = 1000
        private const val ALIAS = "maitri_medical_notes_v1"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}

/** Shared client-side rules; the server enforces the same limits for the fields it stores. */
internal object ProfileRules {
    private val phonePattern = Regex("""^\+?[0-9][0-9 ()-]{5,30}$""")

    fun nameValid(name: String) = name.isNotBlank() && name.trim().length <= 256

    fun phoneValid(phone: String): Boolean {
        val value = phone.trim()
        if (value.isEmpty()) return true
        val digits = value.count { it.isDigit() }
        return value.length <= 32 && phonePattern.matches(value) && digits in 7..15
    }

    fun cityValid(city: String) = city.trim().length <= 128 && city.none { it.code < 32 }

    fun notesValid(notes: String) = notes.length <= ProfileLocalStore.MEDICAL_NOTES_LIMIT
}
