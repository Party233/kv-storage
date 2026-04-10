package website.xihan.kv.storage

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import website.xihan.kv.storage.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        updateUI()
        binding.btnRandomData.setOnClickListener {
            Toast.makeText(this, "随机数据按钮被点击", Toast.LENGTH_SHORT).show()
        }
    }

    fun updateUI() = binding.apply {
        textView.text = "字符串: ${getText()}"
        switch1.isChecked = getSwitch()
        textViewInt.text = "整数: ${getInt()}"
        textViewFloat.text = "浮点数: ${getFloat()}"
        textViewDouble.text = "双精度: ${getDouble()}"
        textViewLong.text = "长整数: ${getLong()}"
        textViewStringSet.text = "字符串集合: ${getStringSet()}"
    }

    // 返回 TextView 文本
    fun getText(): String = "Hello World"

    // 返回 Switch 状态
    fun getSwitch(): Boolean = false

    // 返回整数
    fun getInt(): Int = 42

    // 返回浮点数
    fun getFloat(): Float = 3.14f

    // 返回双精度浮点数
    fun getDouble(): Double = 2.71828

    // 返回长整数
    fun getLong(): Long = 123456789L

    // 返回字符串集合
    fun getStringSet(): Set<String> = setOf("item1", "item2", "item3")

}