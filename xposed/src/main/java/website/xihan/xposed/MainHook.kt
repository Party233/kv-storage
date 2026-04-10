package website.xihan.xposed

import android.app.Application
import android.app.Instrumentation
import android.util.Log
import android.widget.Button
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import website.xihan.kv.HostKVHelper
import website.xihan.kv.HostKVManager
import website.xihan.kv.KVFileReceiver
import kotlin.random.Random
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
                    hookMethod(kvHelper, lpparam, "getSwitch") { it.getBoolean("switchEnable") }
                    hookMethod(kvHelper, lpparam, "getInt") { it.getInt("intValue") }
                    hookMethod(kvHelper, lpparam, "getFloat") { it.getFloat("floatValue") }
                    hookMethod(kvHelper, lpparam, "getDouble") { it.getDouble("doubleValue") }
                    hookMethod(kvHelper, lpparam, "getLong") { it.getLong("longValue") }
                    hookMethod(kvHelper, lpparam, "getStringSet") { it.getStringSet("stringSetValue") }

                    // Hook 随机数据按钮点击
                    hookRandomButton(kvHelper, lpparam)

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

    private fun hookRandomButton(kvHelper: HostKVHelper, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "website.xihan.kv.storage.MainActivity",
                lpparam.classLoader,
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as android.app.Activity
                        // 使用 findViewById 获取按钮
                        val btnId = activity.resources.getIdentifier(
                            "btnRandomData",
                            "id",
                            activity.packageName
                        )
                        if (btnId == 0) {
                            Log.e(TAG, "btnRandomData id not found")
                            return
                        }
                        val btnRandomData = activity.findViewById<Button>(btnId)
                        if (btnRandomData == null) {
                            Log.e(TAG, "btnRandomData view not found")
                            return
                        }

                        btnRandomData.setOnClickListener {
                            // 生成并写入随机数据
                            writeRandomData(kvHelper)
                            // 刷新UI
                            XposedHelpers.callMethod(activity, "updateUI")
                            Log.d(TAG, "Random data written and UI updated")
                        }
                        Log.d(TAG, "Random button hooked successfully")
                    }
                }
            )
        }.onFailure { Log.e(TAG, "Failed to hook random button", it) }
    }

    private fun writeRandomData(kvHelper: HostKVHelper) {
        kvHelper.putString("textViewText", generateRandomString())
        kvHelper.putBoolean("switchEnable", Random.nextBoolean())
        kvHelper.putInt("intValue", Random.nextInt(0, 1000))
        kvHelper.putFloat("floatValue", Random.nextFloat() * 1000)
        kvHelper.putDouble("doubleValue", Random.nextDouble() * 10000)
        kvHelper.putLong("longValue", Random.nextLong(0, 1_000_000_000L))
        kvHelper.putStringSet("stringSetValue", generateRandomStringSet())
    }

    private inline fun hookMethod(
        kvHelper: HostKVHelper,
        lpparam: XC_LoadPackage.LoadPackageParam,
        methodName: String,
        crossinline getter: (HostKVHelper) -> Any?
    ) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                "website.xihan.kv.storage.MainActivity",
                lpparam.classLoader,
                methodName,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? = getter(kvHelper)
                }
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

        private fun generateRandomString(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val length = Random.nextInt(5, 15)
            return (1..length).map { chars.random() }.joinToString("")
        }

        private fun generateRandomStringSet(): Set<String> {
            val count = Random.nextInt(1, 5)
            return (1..count).map { "item${Random.nextInt(1, 100)}" }.toSet()
        }
    }
}