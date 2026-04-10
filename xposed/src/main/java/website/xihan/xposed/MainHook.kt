package website.xihan.xposed

import android.app.Application
import android.app.Instrumentation
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import website.xihan.kv.HostKVManager
import website.xihan.kv.HostKVHelper
import website.xihan.kv.KVFileReceiver
import kotlin.system.measureTimeMillis

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "website.xihan.kv.storage") return
        XposedHelpers.findAndHookMethod(
            Instrumentation::class.java,
            "callApplicationOnCreate",
            Application::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val application = param.args[0] as? Application ?: return
                    startKoin {
                        androidContext(application)
                        androidLogger()
                    }
                    HostKVManager.init(
                        enableSharedPreferencesCache = true,
                        modulePackageName = BuildConfig.APPLICATION_ID
                    )

                    val kvHelper = HostKVManager.createKVHelper()

                    // Hook 所有基础类型方法
                    hookMethod(kvHelper, lpparam, "getText") { it.getString("textViewText") }
                    hookMethod(kvHelper, lpparam, "getSwitch") { it.getBoolean("swithchEnable") }
                    hookMethod(kvHelper, lpparam, "getInt") { it.getInt("intValue") }
                    hookMethod(kvHelper, lpparam, "getFloat") { it.getFloat("floatValue") }
                    hookMethod(kvHelper, lpparam, "getDouble") { it.getDouble("doubleValue") }
                    hookMethod(kvHelper, lpparam, "getLong") { it.getLong("longValue") }
                    hookMethod(kvHelper, lpparam, "getStringSet") { it.getStringSet("stringSetValue") }

                    // 设置变更监听器
                    setupChangeListeners(kvHelper)

                    // 测试批量获取
                    runCatching {
                        measureTimeMillis {
                            val keys = kvHelper.keys()
                            Log.d(TAG, "keys: $keys")
                        }.let { Log.d(TAG, "keys time: $it") }
                    }

                    // 文件接收
                    setupFileReceiver(application)
                }
            }
        )
    }

    private inline fun <T> hookMethod(
        kvHelper: HostKVHelper,
        lpparam: XC_LoadPackage.LoadPackageParam,
        methodName: String,
        getter: (HostKVHelper) -> T
    ) {
        runCatching {
            val value = getter(kvHelper)
            Log.d(TAG, "$methodName: $value")
            XposedHelpers.findAndHookMethod(
                "website.xihan.kv.storage.MainActivity",
                lpparam.classLoader,
                methodName,
                XC_MethodReplacement.returnConstant(value)
            )
        }.onFailure { Log.e(TAG, "Failed to hook $methodName", it) }
    }

    private fun setupChangeListeners(kvHelper: HostKVHelper) {
        kvHelper.keys()
            .forEach { key ->
                runCatching {
                    kvHelper.addChangeListener(key) { s, any ->
                        Log.d(TAG, "$key changed: $s, $any")
                    }
                }
            }
    }

    private fun setupFileReceiver(application: Application) {
        runCatching {
            val receiveFile = KVFileReceiver(application)
            receiveFile.receiveFile()?.let { file ->
                Log.d(TAG, "File saved to: ${file.absolutePath}")
            }
            receiveFile.observeFile { file ->
                Log.d(TAG, "File updated and saved to: ${file.absolutePath}")
            }
        }.onFailure { Log.e(TAG, "Failed to setup file receiver", it) }
    }

    companion object {
        const val TAG = "KV-XPOSED"
    }
}