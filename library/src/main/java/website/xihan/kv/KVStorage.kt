package website.xihan.kv


import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 模块端KV存储实现
 */
object KVStorage : KoinComponent {

    private val context: Context by inject()
    private val prefCache = ConcurrentHashMap<String, SharedPreferences>()
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()
    private const val TAG = "KVStorage"


    /**
     * 获取SharedPreferences实例
     */
    private fun getPrefs(kvId: String): SharedPreferences {
        return prefCache.getOrPut(kvId) {
            val prefName = if (kvId.isEmpty()) "kv_default" else "kv_$kvId"
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        }
    }

    /**
     * 获取读写锁
     */
    private fun getLock(kvId: String): ReentrantReadWriteLock {
        return locks.getOrPut(kvId) { ReentrantReadWriteLock() }
    }

    private inline fun writeValue(
        kvId: String,
        key: String,
        action: String,
        valueType: String,
        notifyValue: Any? = null,
        crossinline block: SharedPreferences.Editor.() -> Unit,
    ): Boolean = getLock(kvId).write {
        try {
            getPrefs(kvId).edit(true, block)
            KVSyncManager.notifyChange(kvId, key, notifyValue, valueType)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to $action: $key", e)
            false
        }
    }

    private inline fun <T> readValue(
        kvId: String,
        key: String,
        default: T,
        action: String,
        crossinline block: SharedPreferences.() -> T,
    ): T = getLock(kvId).read {
        try {
            getPrefs(kvId).block()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to $action: $key", e)
            default
        }
    }

    /**
     * 检查键是否存在
     */
    fun contains(kvId: String, key: String): Boolean {
        return getLock(kvId).read {
            getPrefs(kvId).contains(key)
        }
    }

    /**
     * 删除指定键
     */
    fun remove(kvId: String, key: String): Boolean = writeValue(
        kvId = kvId,
        key = key,
        action = "remove key",
        valueType = "null"
    ) { remove(key) }

    // String操作
    fun putString(kvId: String, key: String, value: String): Boolean = writeValue(
        kvId = kvId,
        key = key,
        action = "put string",
        valueType = "string",
        notifyValue = value,
    ) { putString(key, value) }

    fun getString(kvId: String, key: String, default: String = ""): String = readValue(
        kvId = kvId,
        key = key,
        default = default,
        action = "get string",
    ) { getString(key, default) ?: default }

    // Int操作
    fun putInt(kvId: String, key: String, value: Int): Boolean = writeValue(
        kvId = kvId,
        key = key,
        action = "put int",
        valueType = "int",
        notifyValue = value,
    ) { putInt(key, value) }

    fun getInt(kvId: String, key: String, default: Int = 0): Int = readValue(
        kvId = kvId,
        key = key,
        default = default,
        action = "get int",
    ) { getInt(key, default) }

    // Long操作
    fun putLong(kvId: String, key: String, value: Long): Boolean = writeValue(
        kvId = kvId,
        key = key,
        action = "put long",
        valueType = "long",
        notifyValue = value,
    ) { putLong(key, value) }

    fun getLong(kvId: String, key: String, default: Long = 0L): Long = readValue(
        kvId = kvId,
        key = key,
        default = default,
        action = "get long",
    ) { getLong(key, default) }

    // Boolean操作
    fun putBoolean(kvId: String, key: String, value: Boolean): Boolean = writeValue(
        kvId = kvId,
        key = key,
        action = "put boolean",
        valueType = "boolean",
        notifyValue = value,
    ) { putBoolean(key, value) }

    fun getBoolean(kvId: String, key: String, default: Boolean = false): Boolean = readValue(
        kvId = kvId,
        key = key,
        default = default,
        action = "get boolean",
    ) { getBoolean(key, default) }

    // Float操作
    fun putFloat(kvId: String, key: String, value: Float): Boolean = writeValue(
        kvId = kvId,
        key = key,
        action = "put float",
        valueType = "float",
        notifyValue = value,
    ) { putFloat(key, value) }

    fun getFloat(kvId: String, key: String, default: Float = 0f): Float = readValue(
        kvId = kvId,
        key = key,
        default = default,
        action = "get float",
    ) { getFloat(key, default) }

    // Double操作 (使用Long存储bits)
    fun putDouble(kvId: String, key: String, value: Double): Boolean = writeValue(
        kvId = kvId,
        key = key,
        action = "put double",
        valueType = "double",
        notifyValue = value,
    ) { putLong(key, value.toRawBits()) }

    fun getDouble(kvId: String, key: String, default: Double = 0.0): Double = readValue(
        kvId = kvId,
        key = key,
        default = default,
        action = "get double",
    ) {
        if (!contains(key)) default else Double.fromBits(getLong(key, default.toRawBits()))
    }

    // StringSet操作
    fun putStringSet(kvId: String, key: String, value: Set<String>): Boolean = writeValue(
        kvId = kvId,
        key = key,
        action = "put string set",
        valueType = "stringset",
        notifyValue = JSONArray(value).toString(),
    ) { putStringSet(key, value) }

    fun getStringSet(kvId: String, key: String, default: Set<String>): Set<String> = readValue(
        kvId = kvId,
        key = key,
        default = default,
        action = "get string set",
    ) { getStringSet(key, default)?.toSet() ?: default }

    private fun decodeStoredValue(key: String, value: Any?): Pair<String, Any?>? = when {
        key.endsWith("_bytes") -> {
            val originalKey = key.removeSuffix("_bytes")
            runCatching {
                (value as? String)?.let { encoded ->
                    originalKey to Base64.decode(encoded, Base64.DEFAULT)
                }
            }.onFailure { Log.w(TAG, "Failed to decode bytes for key: $originalKey", it) }.getOrNull()
        }

        key.endsWith("_parcelable") -> key.removeSuffix("_parcelable") to (value as? String)
        else -> key to value
    }


    /**
     * 获取所有KV数据
     */
    fun getAllKV(kvId: String): Map<String, Any?> = readValue(
        kvId = kvId,
        key = "*",
        default = emptyMap(),
        action = "get all KV",
    ) {
        buildMap {
            all.forEach { (key, value) ->
                decodeStoredValue(key, value)?.let { (decodedKey, decodedValue) ->
                    put(decodedKey, decodedValue)
                }
            }
        }
    }

    /**
     * 清除所有数据
     */
    fun clearAll(kvId: String): Boolean = writeValue(
        kvId = kvId,
        key = "__CLEAR_ALL__",
        action = "clear all",
        valueType = "null"
    ) { clear() }
}