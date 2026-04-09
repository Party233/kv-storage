package website.xihan.kv

import android.content.Context
import android.content.Intent
import android.util.Log
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 同步管理器，通过广播实现跨进程配置变化通知
 */
object KVSyncManager : KoinComponent {

    private const val EXTRA_KV_ID = "kvId"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_VALUE = "value"
    private const val EXTRA_VALUE_TYPE = "valueType"
    private const val TAG = "KVSyncManager"

    private val context: Context by inject()
    private var targetPackages: MutableSet<String> = mutableSetOf()

    fun setTargetPackages(vararg packages: String) {
        targetPackages.clear()
        targetPackages.addAll(packages)
    }

    fun notifyChange(kvId: String, key: String, value: Any? = null, valueType: String = "null") {
        try {
            val intent = Intent(KVContentProvider.broadcastAction).apply {
                putExtra(EXTRA_KV_ID, kvId)
                putExtra(EXTRA_KEY, key)
                putExtra(EXTRA_VALUE, value?.toString())
                putExtra(EXTRA_VALUE_TYPE, valueType)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }

            if (targetPackages.isEmpty()) {
                context.sendBroadcast(intent)
            } else {
                targetPackages.forEach { packageName ->
                    intent.setPackage(packageName)
                    context.sendBroadcast(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send broadcast", e)
        }
    }
}