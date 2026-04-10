package website.xihan.kv

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import org.json.JSONArray


/**
 * 模块独立的ContentProvider，在模块Activity中注册
 */
class KVContentProvider : ContentProvider() {

    companion object {
        private const val CODE_GET = 1
        private const val CODE_PUT = 2
        private const val TAG = "KVContentProvider"

        private var _authority: String? = null

        val authority: String
            get() = _authority ?: "website.xihan.kv"

        val broadcastAction: String
            get() = "$authority.KV_CHANGED"

        fun setAuthority(authority: String) {
            _authority = authority
        }
    }

    private lateinit var uriMatcher: UriMatcher

    override fun onCreate(): Boolean {
        if (_authority == null) {
            context?.packageName?.let { _authority = "$it.kv" }
        }
        uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(authority, "get/*/*", CODE_GET)
            addURI(authority, "put/*/*", CODE_PUT)
        }
        return true
    }

    private fun Uri.requireKvPath(): Pair<String, String>? {
        val pathSegments = pathSegments
        if (pathSegments.size < 3) {
            Log.e(TAG, "Invalid URI: $this")
            return null
        }
        return pathSegments[1] to pathSegments[2]
    }

    private fun String?.parseStringSet(): Set<String> {
        if (this == null) return emptySet()
        return try {
            JSONArray(this).let { arr ->
                (0 until arr.length()).map(arr::getString).toSet()
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun String?.orEmptyString() = this ?: ""

    private fun String?.orInt(default: Int = 0) = this?.toIntOrNull() ?: default

    private fun String?.orLong(default: Long = 0L) = this?.toLongOrNull() ?: default

    private fun String?.orFloat(default: Float = 0f) = this?.toFloatOrNull() ?: default

    private fun String?.orBoolean(default: Boolean = false) = this?.toBooleanStrictOrNull() ?: default

    private fun queryValue(kvId: String, key: String, type: String, default: String?): String = when (type) {
        "string" -> KVStorage.getString(kvId, key, default.orEmptyString())
        "int" -> KVStorage.getInt(kvId, key, default.orInt()).toString()
        "long" -> KVStorage.getLong(kvId, key, default.orLong()).toString()
        "boolean" -> KVStorage.getBoolean(kvId, key, default.orBoolean()).toString()
        "float" -> KVStorage.getFloat(kvId, key, default.orFloat()).toString()
        "double" -> {
            val defaultBits = default.orLong()
            KVStorage.getDouble(kvId, key, Double.fromBits(defaultBits)).toRawBits().toString()
        }

        "stringset" -> JSONArray(KVStorage.getStringSet(kvId, key, default.parseStringSet())).toString()
        "contains" -> KVStorage.contains(kvId, key).toString()
        else -> {
            Log.w(TAG, "Unknown type: $type")
            ""
        }
    }

    private fun insertValue(kvId: String, key: String, type: String, value: String?) {
        when (type) {
            "string" -> value?.let { KVStorage.putString(kvId, key, it) }
            "int" -> value?.toIntOrNull()?.let { KVStorage.putInt(kvId, key, it) }
            "long" -> value?.toLongOrNull()?.let { KVStorage.putLong(kvId, key, it) }
            "boolean" -> value?.toBooleanStrictOrNull()?.let { KVStorage.putBoolean(kvId, key, it) }
            "float" -> value?.toFloatOrNull()?.let { KVStorage.putFloat(kvId, key, it) }
            "double" -> value?.toLongOrNull()?.let { KVStorage.putDouble(kvId, key, Double.fromBits(it)) }
            "stringset" -> value?.let { KVStorage.putStringSet(kvId, key, it.parseStringSet()) }
            "remove" -> KVStorage.remove(kvId, key)
            "clear" -> KVStorage.clearAll(kvId)
            else -> Log.w(TAG, "Unknown type: $type")
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return try {
            when (uriMatcher.match(uri)) {
                CODE_GET -> {
                    val (kvId, key) = uri.requireKvPath() ?: return null
                    val type = uri.getQueryParameter("type") ?: "string"
                    val default = uri.getQueryParameter("default")

                    MatrixCursor(arrayOf("value")).apply {
                        addRow(arrayOf(queryValue(kvId, key, type, default)))
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Query failed", e)
            null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return try {
            when (uriMatcher.match(uri)) {
                CODE_PUT -> {
                    val (kvId, key) = uri.requireKvPath() ?: return uri
                    val type = uri.getQueryParameter("type") ?: "string"
                    val value = uri.getQueryParameter("value")

                    insertValue(kvId, key, type, value)
                    uri
                }
                else -> uri
            }
        } catch (e: Exception) {
            Log.e(TAG, "Insert failed", e)
            uri
        }
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null
}