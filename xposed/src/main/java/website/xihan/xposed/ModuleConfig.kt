package website.xihan.xposed

import website.xihan.kv.IKVOwner
import website.xihan.kv.KVOwner

object ModuleConfig : IKVOwner by KVOwner("SHARED_SETTINGS") {
    // 默认值常量
    const val intDefault = 42
    const val floatDefault = 3.14f
    const val doubleDefault = 2.71828
    const val longDefault = 123456789L
    val boolDefault = false
    val stringDefault = ""
    val stringSetDefault = setOf("item1", "item2", "item3")

    var switchEnable by kvBool(boolDefault)
    var textViewText by kvString(stringDefault)
    var intValue by kvInt(intDefault)
    var floatValue by kvFloat(floatDefault)
    var doubleValue by kvDouble(doubleDefault)
    var longValue by kvLong(longDefault)
    var stringSetValue by kvStringSet(stringSetDefault)
}