package `in`.maitri.app.network

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import `in`.maitri.app.location.LocationUpdate
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Private database. Only routing keys and timestamps are indexed. GPS payloads are encrypted. */
// Closeable is declared here because SQLiteOpenHelper only became AutoCloseable in later Android versions:
// without it, `use { }` throws ClassCastException on Android 8-9 (found on the API 26 emulator).
internal class IncidentFixQueue(context: Context, databaseName: String = "incident_fix_queue.db") :
    SQLiteOpenHelper(context, databaseName, null, 1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE fixes (uid TEXT NOT NULL, incident TEXT NOT NULL, stamp INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(uid, incident, stamp))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported queue migration")

    private val key: SecretKey by lazy {
        synchronized(IncidentFixQueue::class.java) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.getKey(ALIAS, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
    }

    private fun binding(uid: String, incident: String, stamp: Long) = "$uid\u0000$incident\u0000$stamp".toByteArray()

    fun prune(now: Long = System.currentTimeMillis()) {
        writableDatabase.delete("fixes", "stamp < ? OR stamp > ?", arrayOf((now - TTL).toString(), (now + 5000).toString()))
    }

    fun hasPending(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM fixes LIMIT 1", null).use { it.moveToFirst() }

    fun offer(uid: String, incident: String, fix: LocationUpdate, now: Long = System.currentTimeMillis()) {
        if (!fix.valid() || fix.timestampMillis !in (now - TTL)..(now + 5000) || fix.accuracyMeters > `in`.maitri.app.location.TrackingConfig.MAX_SOS_ACCURACY_METRES) return
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(binding(uid, incident, fix.timestampMillis))
        val plain = ByteBuffer.allocate(21).putDouble(fix.latitude).putDouble(fix.longitude)
            .putFloat(fix.accuracyMeters).put(if (fix.isMock) 1 else 0).array()
        val row = ContentValues().apply {
            put("uid", uid); put("incident", incident); put("stamp", fix.timestampMillis)
            put("payload", cipher.iv + cipher.doFinal(plain))
        }
        writableDatabase.apply {
            beginTransaction()
            try {
                prune(now)
                insertWithOnConflict("fixes", null, row, SQLiteDatabase.CONFLICT_IGNORE)
                execSQL("DELETE FROM fixes WHERE rowid IN (SELECT rowid FROM fixes ORDER BY stamp DESC LIMIT -1 OFFSET 48)")
                setTransactionSuccessful()
            } finally { endTransaction() }
        }
    }

    fun pending(uid: String, incident: String, now: Long = System.currentTimeMillis()): List<LocationUpdate> {
        prune(now)
        return readableDatabase.rawQuery("SELECT stamp,payload FROM fixes WHERE uid=? AND incident=? ORDER BY stamp",
            arrayOf(uid, incident)).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    val stamp = rows.getLong(0)
                    val blob = rows.getBlob(1)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
                    cipher.updateAAD(binding(uid, incident, stamp))
                    val data = ByteBuffer.wrap(cipher.doFinal(blob, 12, blob.size - 12))
                    add(LocationUpdate(data.double, data.double, data.float, stamp, null, data.get().toInt() != 0))
                }
            }
        }
    }

    fun acknowledge(uid: String, incident: String, stamp: Long) {
        writableDatabase.delete("fixes", "uid=? AND incident=? AND stamp=?", arrayOf(uid, incident, stamp.toString()))
    }

    fun clear(uid: String, incident: String) {
        writableDatabase.delete("fixes", "uid=? AND incident=?", arrayOf(uid, incident))
    }

    companion object {
        private const val ALIAS = "maitri-queued-fixes-v1"
        private const val TTL = 120000L
    }
}
