package website.xihan.xposed

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import website.xihan.kv.KVStorage
import website.xihan.kv.KVSyncManager
import website.xihan.kv.getAllKV
import website.xihan.xposed.databinding.ActivityMainBinding
import kotlin.random.Random


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                website.xihan.kv.KVFileTransfer.saveFileUri("website.xihan.kv.storage", it)
                binding.tvFileUri.text = it.toString()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets()
        setupDataBinding()
        setupActionButtons()
    }

    private fun setupWindowInsets() {
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                maxOf(systemBars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun setupDataBinding() = binding.apply {
        // 字符串
        editText.setText(ModuleConfig.textViewText)
        editText.doAfterTextChanged { ModuleConfig.textViewText = it?.toString() ?: "" }

        // 布尔值
        switch1.isChecked = ModuleConfig.swithchEnable
        switch1.setOnCheckedChangeListener { _, isChecked -> ModuleConfig.swithchEnable = isChecked }

        // 整数
        editTextInt.setText(ModuleConfig.intValue.toString())
        editTextInt.doAfterTextChanged { text ->
            text?.toString()?.toIntOrNull()?.let { ModuleConfig.intValue = it }
        }

        // 浮点数
        editTextFloat.setText(ModuleConfig.floatValue.toString())
        editTextFloat.doAfterTextChanged { text ->
            text?.toString()?.toFloatOrNull()?.let { ModuleConfig.floatValue = it }
        }

        // 双精度
        editTextDouble.setText(ModuleConfig.doubleValue.toString())
        editTextDouble.doAfterTextChanged { text ->
            text?.toString()?.toDoubleOrNull()?.let { ModuleConfig.doubleValue = it }
        }

        // 长整数
        editTextLong.setText(ModuleConfig.longValue.toString())
        editTextLong.doAfterTextChanged { text ->
            text?.toString()?.toLongOrNull()?.let { ModuleConfig.longValue = it }
        }

        // 字符串集合
        editTextStringSet.setText(ModuleConfig.stringSetValue.joinToString(","))
        editTextStringSet.doAfterTextChanged { text ->
            ModuleConfig.stringSetValue = parseStringSet(text?.toString())
        }

        // 文件 URI
        val savedUri = KVStorage.getString("SHARED_SETTINGS", "fileUri")
        tvFileUri.text = savedUri.ifEmpty { "未选择文件" }

        btnSelectFile.setOnClickListener { filePickerLauncher.launch(arrayOf("*/*")) }
    }

    private fun setupActionButtons() = binding.apply {
        btnRemove.setOnClickListener { handleRemoveKey() }
        btnClearAll.setOnClickListener { handleClearAll() }
        btnClearCache.setOnClickListener { handleClearCache() }
        btnRandomData.setOnClickListener { handleRandomData() }
        btnShowKeys.setOnClickListener { updateKeysDisplay() }
        btnContains.setOnClickListener { handleContainsKey() }
    }

    private fun handleRemoveKey() {
        val key = binding.editTextRemoveKey.text.toString().trim()
        if (key.isEmpty()) {
            showToast("请输入要删除的键名")
            return
        }
        val result = ModuleConfig.removeKV(key)
        showToast("删除结果: $result")
    }

    private fun handleClearAll() {
        ModuleConfig.clearAllKV()
        showToast("已清除所有数据")
        resetToDefaults()
        updateKeysDisplay()
    }

    private fun handleClearCache() {
        // 通知宿主端清除缓存
        KVSyncManager.notifyChange(ModuleConfig.kvId, "__CLEAR_ALL__", null, "clear")
        showToast("已通知宿主清除缓存")
    }

    private fun handleRandomData() {
        val randomString = generateRandomString()
        val randomInt = Random.nextInt(0, 1000)
        val randomFloat = Random.nextFloat() * 1000
        val randomDouble = Random.nextDouble() * 10000
        val randomLong = Random.nextLong(0, 1_000_000_000L)
        val randomBool = Random.nextBoolean()
        val randomStringSet = generateRandomStringSet()

        // 更新配置
        ModuleConfig.textViewText = randomString
        ModuleConfig.intValue = randomInt
        ModuleConfig.floatValue = randomFloat
        ModuleConfig.doubleValue = randomDouble
        ModuleConfig.longValue = randomLong
        ModuleConfig.swithchEnable = randomBool
        ModuleConfig.stringSetValue = randomStringSet

        // 更新 UI
        binding.apply {
            editText.setText(randomString)
            editTextInt.setText(randomInt.toString())
            editTextFloat.setText(randomFloat.toString())
            editTextDouble.setText(randomDouble.toString())
            editTextLong.setText(randomLong.toString())
            switch1.isChecked = randomBool
            editTextStringSet.setText(randomStringSet.joinToString(","))
        }

        showToast("已生成随机数据")
    }

    private fun generateRandomString(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val length = Random.nextInt(5, 15)
        return (1..length).map { chars.random() }.joinToString("")
    }

    private fun generateRandomStringSet(): Set<String> {
        val count = Random.nextInt(1, 5)
        return (1..count).map { "item${Random.nextInt(1, 100)}" }.toSet()
    }

    private fun handleContainsKey() {
        val key = binding.editTextContainsKey.text.toString().trim()
        if (key.isEmpty()) {
            showToast("请输入要检查的键名")
            return
        }
        val exists = ModuleConfig.containsKV(key)
        binding.tvContainsResult.text = "检查结果: $key 存在=$exists"
    }

    private fun resetToDefaults() = binding.apply {
        editText.setText("")
        editTextInt.setText(ModuleConfig.intDefault.toString())
        editTextFloat.setText(ModuleConfig.floatDefault.toString())
        editTextDouble.setText(ModuleConfig.doubleDefault.toString())
        editTextLong.setText(ModuleConfig.longDefault.toString())
        editTextStringSet.setText(ModuleConfig.stringSetDefault.joinToString(","))
        switch1.isChecked = ModuleConfig.boolDefault
    }

    private fun updateKeysDisplay() {
        val keys = ModuleConfig.getAllKV()
        binding.tvKeysResult.text = "键列表: ${keys.keys.joinToString(", ")}"
    }

    private fun parseStringSet(text: String?): Set<String> =
        text?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

}