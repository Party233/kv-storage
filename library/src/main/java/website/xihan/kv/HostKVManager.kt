package website.xihan.kv

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 宿主端KV管理器，通过systemContext访问模块的ContentProvider
 */
object HostKVManager : KoinComponent {

    internal val systemContext: Context by inject()
    internal val cache = ConcurrentHashMap<String, LruCache<String, Any>>()
    internal val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()
    internal val listeners = ConcurrentHashMap<String, MutableList<WeakReference<(String, Any?) -> Unit>>>()
    internal var broadcastReceiver: BroadcastReceiver? = null
    internal var spCache: SharedPreferences? = null
    internal var enableSpCache = false
    internal var moduleAuthority: String = "website.xihan.kv"
    internal var defaultCacheSize = 100
    internal val receiverLock = Any()

    private const val TAG = "HostKVManager"
    private const val EXTRA_KV_ID = "kvId"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_VALUE = "value"
    private const val EXTRA_VALUE_TYPE = "valueType"

    enum class ValueType(val typeName: String) {
        STRING("string"),
        INT("int"),
        LONG("long"),
        BOOLEAN("boolean"),
        FLOAT("float"),
        DOUBLE("double"),
        STRING_SET("stringset"),
        CONTAINS("contains"),
        REMOVE("remove"),
        CLEAR("clear");

        companion object {
            fun from(value: Any?) = when (value) {
                is String -> STRING
                is Int -> INT
                is Long -> LONG
                is Boolean -> BOOLEAN
                is Float -> FLOAT
                is Double -> DOUBLE
                is Set<*> -> if (value.all { it is String }) STRING_SET else STRING
                else -> STRING
            }
        }
    }

    private fun lockOf(kvId: String) = locks.getOrPut(kvId) { ReentrantReadWriteLock() }

    private inline fun <T> writeLocked(kvId: String, block: () -> T): T = lockOf(kvId).write(block)

    private inline fun <T> readLocked(kvId: String, block: () -> T): T = lockOf(kvId).read(block)

    private fun cacheOf(kvId: String) = cache.getOrPut(kvId) { LruCache(defaultCacheSize) }

    private fun spKey(kvId: String, key: String) = "${kvId}_$key"

    internal fun buildGetUri(kvId: String, key: String, type: String, default: String? = null): Uri =
        "content://$moduleAuthority/get/$kvId/$key".toUri().buildUpon()
            .appendQueryParameter("type", type)
            .apply { default?.let { appendQueryParameter("default", it) } }
            .build()

    internal fun buildPutUri(kvId: String, key: String, type: String, value: String): Uri =
        "content://$moduleAuthority/put/$kvId/$key".toUri().buildUpon()
            .appendQueryParameter("type", type)
            .appendQueryParameter("value", value)
            .build()

    internal inline fun <T> queryValue(uri: Uri, mapper: (String) -> T): T? =
        systemContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) mapper(cursor.getString(0)) else null
        }

    internal fun insertValue(uri: Uri) = systemContext.contentResolver.insert(uri, null)

    internal fun Set<String>.toJson() = JSONArray(this).toString()

    internal fun String.parseJsonToSet(): Set<String> = try {
        JSONArray(this).let { arr -> (0 until arr.length()).map(arr::getString).toSet() }
    } catch (_: Exception) {
        emptySet()
    }

    fun init(
        enableSharedPreferencesCache: Boolean = false,
        modulePackageName: String? = null,
        cacheSize: Int? = null,
        moduleAuthority: String? = null
    ) {
        enableSpCache = enableSharedPreferencesCache
        cacheSize?.let { defaultCacheSize = it }
        moduleAuthority?.let { this.moduleAuthority = it }
            ?: modulePackageName?.let { this.moduleAuthority = "$it.kv" }
        if (enableSpCache) {
            spCache = systemContext.getSharedPreferences("kv_host_cache", Context.MODE_PRIVATE)
        }
        grantUriPermissions()
        initBroadcastReceiver()
    }

    private fun grantUriPermissions() {
        runCatching {
            val uri = "content://$moduleAuthority".toUri()
            systemContext.grantUriPermission(
                systemContext.packageName,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }.onFailure { Log.w(TAG, "Failed to grant URI permissions", it) }
    }

    private fun notifyListener(listener: (String, Any?) -> Unit, key: String, value: Any?) {
        runCatching { listener(key, value) }.onFailure { Log.e(TAG, "Listener error", it) }
    }

    private fun parseBroadcastValue(valueStr: String?, valueType: String): Any? = when (valueType) {
        "null" -> null
        "string" -> valueStr
        "int" -> valueStr?.toIntOrNull()
        "long" -> valueStr?.toLongOrNull()
        "boolean" -> valueStr?.toBooleanStrictOrNull()
        "float" -> valueStr?.toFloatOrNull()
        "double" -> valueStr?.toDoubleOrNull()
        "stringset" -> valueStr?.parseJsonToSet()
        else -> valueStr
    }

    private fun invalidateKeyCache(kvId: String, key: String) {
        writeLocked(kvId) { cacheOf(kvId).remove(key) }
        if (enableSpCache) {
            spCache?.edit(true) { remove(spKey(kvId, key)) }
        }
    }

    private fun notifyKeyListeners(kvId: String, key: String, value: Any?) {
        listeners["${kvId}_$key"]?.removeAll { ref ->
            ref.get()?.let { listener ->
                notifyListener(listener, key, value)
                false
            } ?: true
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun initBroadcastReceiver() {
        synchronized(receiverLock) {
            if (broadcastReceiver != null) return

            val actionKvChanged = "$moduleAuthority.KV_CHANGED"
            broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != actionKvChanged) return

                    val kvId = intent.getStringExtra(EXTRA_KV_ID) ?: return
                    val key = intent.getStringExtra(EXTRA_KEY) ?: return
                    val valueStr = intent.getStringExtra(EXTRA_VALUE)
                    val valueType = intent.getStringExtra(EXTRA_VALUE_TYPE) ?: "null"

                    if (key == "__CLEAR_ALL__") {
                        clearCacheForKvId(kvId)
                        notifyListenersForKvId(kvId)
                        return
                    }

                    val parsedValue = parseBroadcastValue(valueStr, valueType)

                    invalidateKeyCache(kvId, key)
                    notifyKeyListeners(kvId, key, parsedValue)
                }
            }

            runCatching {
                val filter = IntentFilter(actionKvChanged)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    systemContext.registerReceiver(broadcastReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    systemContext.registerReceiver(broadcastReceiver, filter)
                }
            }.onFailure { Log.e(TAG, "Failed to register broadcast receiver", it) }
        }
    }

    internal fun clearCacheForKvId(kvId: String) {
        writeLocked(kvId) { cache[kvId]?.clear() }
        if (enableSpCache) {
            spCache?.all?.keys
                ?.filter { it.startsWith("${kvId}_") }
                ?.takeIf { it.isNotEmpty() }
                ?.let { keys -> spCache?.edit(true) { keys.forEach(::remove) } }
        }
    }

    private fun notifyListenersForKvId(kvId: String) {
        listeners.keys
            .filter { it.startsWith("${kvId}_") }
            .forEach { listenerKey ->
                listeners[listenerKey]?.removeAll { ref ->
                    ref.get()?.let { listener ->
                        notifyListener(listener, "", null)
                        false
                    } ?: true
                }
            }
    }

    fun createKVHelper(
        kvId: String = "SHARED_SETTINGS",
        enableSharedPreferencesCache: Boolean = enableSpCache,
        cacheSize: Int = defaultCacheSize
    ): HostKVHelper {
        cache.getOrPut(kvId) { LruCache(cacheSize) }
        locks.getOrPut(kvId) { ReentrantReadWriteLock() }
        return HostKVHelper(this, kvId, enableSharedPreferencesCache)
    }

    fun release() {
        synchronized(receiverLock) {
            broadcastReceiver?.let { receiver ->
                runCatching { systemContext.unregisterReceiver(receiver) }
                    .onFailure { Log.e(TAG, "Failed to unregister receiver", it) }
                broadcastReceiver = null
            }
        }
        cache.clear()
        locks.clear()
        listeners.clear()
        spCache = null
    }
}

/**
 * 宿主端KV工具类
 */
class HostKVHelper internal constructor(
    private val manager: HostKVManager,
    private val kvId: String,
    private val useSpCache: Boolean
) {
    private val cache get() = manager.cache.getOrPut(kvId) { LruCache(manager.defaultCacheSize) }
    private val spCache get() = manager.spCache
    private val lock get() = manager.locks.getOrPut(kvId) { ReentrantReadWriteLock() }

    operator fun get(key: String): String? = getString(key)
    operator fun set(key: String, value: String) = putString(key, value)

    operator fun plusAssign(pair: Pair<String, Any>) = when (val value = pair.second) {
        is String -> putString(pair.first, value)
        is Int -> putInt(pair.first, value)
        is Long -> putLong(pair.first, value)
        is Boolean -> putBoolean(pair.first, value)
        is Float -> putFloat(pair.first, value)
        is Double -> putDouble(pair.first, value)
        is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(pair.first, value as Set<String>)
        else -> Unit
    }

    private inline fun <T> writeLocked(block: () -> T): T = lock.write(block)
    private inline fun <T> readLocked(block: () -> T): T = lock.read(block)

    private fun spKey(key: String) = "${kvId}_$key"

    private fun updateSpCache(key: String, value: Any) {
        if (!useSpCache) return
        spCache?.edit(true) {
            val k = spKey(key)
            when (value) {
                is String -> putString(k, value)
                is Int -> putInt(k, value)
                is Long -> putLong(k, value)
                is Boolean -> putBoolean(k, value)
                is Float -> putFloat(k, value)
                is Double -> putLong(k, value.toRawBits())
                is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, value as Set<String>)
            }
        }
    }

    private fun updateCache(key: String, value: Any) {
        cache.put(key, value)
        updateSpCache(key, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String): T? = cache.get(key) as? T

    private fun <T> spCached(key: String, default: T, getter: SharedPreferences.(String, T) -> T?): T? {
        if (!useSpCache) return null
        val k = spKey(key)
        return if (spCache?.contains(k) == true) spCache?.getter(k, default) ?: default else null
    }

    private fun cachedDouble(key: String, default: Double): Double? {
        val k = spKey(key)
        return if (useSpCache && spCache?.contains(k) == true) {
            Double.fromBits(spCache?.getLong(k, default.toRawBits()) ?: default.toRawBits())
        } else {
            null
        }
    }

    private fun buildGetUri(key: String, type: String, default: String? = null) =
        manager.buildGetUri(kvId, key, type, default)

    private fun buildPutUri(key: String, type: String, value: String) =
        manager.buildPutUri(kvId, key, type, value)

    private inline fun <T> query(uri: Uri, mapper: (String) -> T): T? = manager.queryValue(uri, mapper)

    private fun insert(uri: Uri) = manager.insertValue(uri)

    private fun <T : Any> getValue(
        key: String,
        default: T,
        type: String,
        queryDefault: String,
        spGetter: (SharedPreferences.(String, T) -> T?)? = null,
        queryMapper: (String) -> T
    ): T = readLocked {
        cached<T>(key)
            ?: spGetter?.let { getter -> spCached(key, default, getter) }
            ?: query(buildGetUri(key, type, queryDefault), queryMapper)?.also { updateCache(key, it) }
            ?: default
    }

    private fun <T : Any> putValue(
        key: String,
        value: T,
        type: String,
        encodedValue: String,
    ) = writeLocked {
        insert(buildPutUri(key, type, encodedValue))
        updateCache(key, value)
    }

    fun putString(key: String, value: String) = putValue(key, value, "string", value)

    fun getString(key: String, default: String = ""): String =
        getValue(key, default, "string", default, spGetter = { k, d -> getString(k, d) }) { it }

    fun putInt(key: String, value: Int) = putValue(key, value, "int", value.toString())

    fun getInt(key: String, default: Int = 0): Int =
        getValue(key, default, "int", default.toString(), spGetter = { k, d -> getInt(k, d) }) { it.toInt() }

    fun putLong(key: String, value: Long) = putValue(key, value, "long", value.toString())

    fun getLong(key: String, default: Long = 0L): Long =
        getValue(key, default, "long", default.toString(), spGetter = { k, d -> getLong(k, d) }) { it.toLong() }

    fun putBoolean(key: String, value: Boolean) = putValue(key, value, "boolean", value.toString())

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        getValue(key, default, "boolean", default.toString(), spGetter = { k, d -> getBoolean(k, d) }) { it.toBoolean() }

    fun putFloat(key: String, value: Float) = putValue(key, value, "float", value.toString())

    fun getFloat(key: String, default: Float = 0f): Float =
        getValue(key, default, "float", default.toString(), spGetter = { k, d -> getFloat(k, d) }) { it.toFloat() }

    fun putDouble(key: String, value: Double) = putValue(key, value, "double", value.toRawBits().toString())

    fun getDouble(key: String, default: Double = 0.0): Double = readLocked {
        cached<Double>(key)
            ?: cachedDouble(key, default)
            ?: query(buildGetUri(key, "double", default.toRawBits().toString())) { Double.fromBits(it.toLong()) }
                ?.also { updateCache(key, it) }
            ?: default
    }

    fun putStringSet(key: String, value: Set<String>) = putValue(key, value, "stringset", with(manager) { value.toJson() })

    @Suppress("UNCHECKED_CAST")
    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> = readLocked {
        cached<Set<String>>(key)
            ?: spCached(key, default) { k, _ -> getStringSet(k, emptySet())?.toSet() }
            ?: query(buildGetUri(key, "stringset", with(manager) { default.toJson() })) { with(manager) { it.parseJsonToSet() } }
                ?.also { updateCache(key, it) }
            ?: default
    }

    private fun removeSpCache(key: String) {
        if (!useSpCache) return
        spCache?.edit(true) { remove(spKey(key)) }
    }

    private fun listenerKey(key: String) = "${kvId}_$key"

    private fun containsValue(key: String): Boolean = query(buildGetUri(key, "contains", "")) { it == "true" } ?: false

    private fun clearProtocolValue(key: String, type: String): Boolean = writeLocked {
        runCatching {
            insert(buildPutUri(key, type, ""))
            cache.remove(key)
            removeSpCache(key)
            true
        }.getOrElse { Log.e(TAG, "Failed to $type: $key", it); false }
    }

    private fun insertProtocolValue(key: String, type: String): Boolean = writeLocked {
        runCatching {
            insert(buildPutUri(key, type, ""))
            cache.clear()
            true
        }.getOrElse { Log.e(TAG, "Failed to $type", it); false }
    }

    fun contains(key: String): Boolean = readLocked { containsValue(key) }

    fun remove(key: String): Boolean = clearProtocolValue(key, "remove")

    fun clearAll(): Boolean = insertProtocolValue("__CLEAR_ALL__", "clear")

    fun addChangeListener(key: String, listener: (String, Any?) -> Unit) {
        manager.listeners.getOrPut(listenerKey(key)) { mutableListOf() }.add(WeakReference(listener))
    }

    fun removeChangeListener(key: String, listener: (String, Any?) -> Unit) {
        manager.listeners[listenerKey(key)]?.removeAll { it.get() == listener || it.get() == null }
    }

    fun clearCache() = manager.clearCacheForKvId(kvId)

    fun configureCacheSize(maxSize: Int) = writeLocked {
        manager.cache[kvId] = LruCache(maxSize)
    }

    fun keys(): Set<String> = cache.keys()

    companion object {
        private const val TAG = "HostKVHelper"
    }
}
