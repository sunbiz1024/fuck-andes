package fuck.andes

import android.os.Build
import java.lang.reflect.Field

internal object GoogleAppHooks {

    fun install(logger: ModuleLogger) {
        // 只在 Google 进程内伪装机型，尽量缩小对系统其余进程的影响面。
        setBuildField(logger, Build::class.java, "MANUFACTURER", "samsung")
        setBuildField(logger, Build::class.java, "BRAND", "samsung")
        setBuildField(logger, Build::class.java, "MODEL", "SM-S928B")
        setBuildField(logger, Build::class.java, "PRODUCT", "e3s")
        setBuildField(logger, Build::class.java, "DEVICE", "e3s")
        logger.debug("GSA: 已尝试伪装设备为 Samsung S24 Ultra")
    }

    private fun setBuildField(
        logger: ModuleLogger,
        clazz: Class<*>,
        fieldName: String,
        value: String
    ) {
        val field = runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        }.getOrElse { throwable ->
            logger.error("GSA: 找不到 Build.$fieldName", throwable)
            return
        }

        runCatching {
            field.set(null, value)
        }.recoverCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
                isAccessible = true
            }.get(null)
            val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                .invoke(theUnsafe, field)
            val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                .invoke(theUnsafe, field) as Long
            unsafeClass.getDeclaredMethod(
                "putObjectVolatile",
                Any::class.java,
                Long::class.javaPrimitiveType!!,
                Any::class.java
            ).invoke(theUnsafe, base, offset, value)
        }.onFailure { throwable ->
            logger.error("GSA: 修改 Build.$fieldName 失败", throwable)
        }
    }
}
