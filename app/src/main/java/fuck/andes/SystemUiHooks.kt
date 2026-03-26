package fuck.andes

import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal object SystemUiHooks {
    @Volatile
    private var getServiceMethod: Method? = null

    @Volatile
    private var asInterfaceMethod: Method? = null

    @Volatile
    private var startContextualSearchMethod: Method? = null

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        val businessClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OCR_BUSINESS_CLASS)
        val onLongPressedMethod = businessClass?.let {
            HookSupport.findMethod(it, "onLongPressed")
        }
        if (onLongPressedMethod == null) {
            logger.warn("未找到 OplusOcrScreenBusiness.onLongPressed()")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            onLongPressedMethod,
            "OplusOcrScreenBusiness.onLongPressed"
        ) { chain ->
            val context = resolveContext(chain.getThisObject())
            if (context == null) {
                logger.warnThrottled("systemui_context", "SystemUI 无法取得 Context，回退原 OCR 逻辑")
                return@hookMethod chain.proceed()
            }

            if (!canTriggerCircleToSearch(context, logger)) {
                return@hookMethod chain.proceed()
            }

            if (triggerCircleToSearch(logger)) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun canTriggerCircleToSearch(context: Context, logger: ModuleLogger): Boolean {
        if (!HookSupport.isPackageInstalled(context, ModuleConfig.GOOGLE_PACKAGE)) {
            logger.warnThrottled("cts_google_missing", "Circle to Search: Google App 未安装，回退原 OCR 逻辑")
            return false
        }

        val intent = Intent(ModuleConfig.CONTEXTUAL_SEARCH_ACTION).setPackage(ModuleConfig.GOOGLE_PACKAGE)
        if (!HookSupport.resolvesActivity(context, intent)) {
            logger.warnThrottled(
                "cts_entry_missing",
                "Circle to Search: Google App 未暴露 Contextual Search 入口，回退原 OCR 逻辑"
            )
            return false
        }

        val binder = getContextualSearchBinder() ?: run {
            logger.warnThrottled(
                "cts_service_missing",
                "Circle to Search: contextual_search service 不可用，回退原 OCR 逻辑"
            )
            return false
        }

        return binder.isBinderAlive
    }

    private fun triggerCircleToSearch(logger: ModuleLogger): Boolean {
        val binder = getContextualSearchBinder() ?: return false
        return runCatching {
            // 直接调用系统 binder，避免再走 OEM OCR/识屏分发链。
            val asInterface = resolveAsInterfaceMethod() ?: return@runCatching false
            val startContextualSearch = resolveStartContextualSearchMethod() ?: return@runCatching false
            val service = asInterface.invoke(null, binder) ?: return@runCatching false
            startContextualSearch.invoke(service, ModuleConfig.CIRCLE_TO_SEARCH_ENTRYPOINT)
            logger.debug("SystemUI: 已触发 Circle to Search")
            true
        }.getOrElse { throwable ->
            logger.error("SystemUI: 触发 Circle to Search 失败，回退原 OCR 逻辑", throwable)
            false
        }
    }

    private fun getContextualSearchBinder(): IBinder? =
        runCatching {
            resolveGetServiceMethod()?.invoke(null, ModuleConfig.CONTEXTUAL_SEARCH_SERVICE) as? IBinder
        }.getOrNull()

    private fun resolveContext(target: Any): Context? =
        HookSupport.invokeNoArgs(target, "getContext") as? Context
            ?: HookSupport.getFieldValue(target, "context") as? Context
            ?: HookSupport.getFieldValue(target, "mContext") as? Context
            ?: HookSupport.getFieldValue(target, "mOcrContext") as? Context

    private fun resolveGetServiceMethod(): Method? {
        getServiceMethod?.let { return it }
        return runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { getServiceMethod = it }
    }

    private fun resolveAsInterfaceMethod(): Method? {
        asInterfaceMethod?.let { return it }
        return runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { asInterfaceMethod = it }
    }

    private fun resolveStartContextualSearchMethod(): Method? {
        startContextualSearchMethod?.let { return it }
        return runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager")
                .getDeclaredMethod("startContextualSearch", Int::class.javaPrimitiveType!!)
                .apply { isAccessible = true }
        }.getOrNull()?.also { startContextualSearchMethod = it }
    }
}
