package fuck.andes

import android.content.Context
import android.content.Intent
import android.os.Message
import android.os.SystemClock
import android.provider.Settings
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal object PowerHooks {

    @Volatile
    private var lastInterceptUptime = 0L

    @Volatile
    private var cachedLaunchAssistMethod: Method? = null

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        hookPowerLongPress(module, logger, classLoader)
        hookOplusSpeechHandler(module, logger, classLoader)
    }

    private fun hookPowerLongPress(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val pwmClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.PHONE_WINDOW_MANAGER_CLASS)
        val powerLongPressMethod = pwmClass?.let {
            HookSupport.findMethod(it, "powerLongPress", Long::class.javaPrimitiveType!!)
        }
        val resolvedBehaviorMethod = pwmClass?.let {
            HookSupport.findMethod(it, "getResolvedLongPressOnPowerBehavior")
        }
        val launchAssistMethod = pwmClass?.let { resolveLaunchAssistMethod(it) }

        if (powerLongPressMethod == null || resolvedBehaviorMethod == null || launchAssistMethod == null) {
            logger.warn("PhoneWindowManager 关键方法缺失，跳过电源键主 Hook")
            return
        }

        HookSupport.hookMethod(module, logger, powerLongPressMethod, "PhoneWindowManager.powerLongPress(long)") { chain ->
            val behavior = module.getInvoker(resolvedBehaviorMethod).invoke(chain.getThisObject()) as Int
            if (behavior !in setOf(4, 5)) {
                return@hookMethod chain.proceed()
            }

            val eventTime = chain.getArg(0) as Long
            if (tryLaunchGoogleAssist(module, logger, chain.getThisObject(), launchAssistMethod, eventTime, "powerLongPress")) {
                null
            } else {
                chain.proceed()
            }
        }

        val powerRuleClass = HookSupport.findClassOrNull(
            classLoader,
            "${ModuleConfig.PHONE_WINDOW_MANAGER_CLASS}\$PowerKeyRule"
        )
        val onLongPressMethod = powerRuleClass?.let {
            HookSupport.findMethod(it, "onLongPress", Long::class.javaPrimitiveType!!)
        }
        if (onLongPressMethod != null) {
            HookSupport.deoptimize(module, logger, onLongPressMethod, "PhoneWindowManager\$PowerKeyRule.onLongPress(long)")
        }
    }

    private fun hookOplusSpeechHandler(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val handlerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OP_LUS_SPEECH_HANDLER_CLASS)
        val handleMessageMethod = handlerClass?.let {
            HookSupport.findMethod(it, "handleMessage", Message::class.java)
        }
        if (handleMessageMethod == null) {
            logger.warn("未找到 OplusSpeechHandler.handleMessage(Message)")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            handleMessageMethod,
            "PhoneWindowManagerExtImpl\$OplusSpeechHandler.handleMessage"
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != ModuleConfig.OP_LUS_ASSIST_MESSAGE_WHAT) {
                return@hookMethod chain.proceed()
            }

            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            if (pwm == null) {
                logger.warnThrottled(
                    "oplus_speech_missing_pwm",
                    "OplusSpeechHandler 未能解析 PhoneWindowManager，回退原逻辑"
                )
                return@hookMethod chain.proceed()
            }

            val launchAssistMethod = resolveLaunchAssistMethod(pwm)
            if (launchAssistMethod == null) {
                logger.warnThrottled(
                    "oplus_speech_missing_launch_assist",
                    "OplusSpeechHandler 缺少 launchAssistAction，回退原逻辑"
                )
                return@hookMethod chain.proceed()
            }

            if (tryLaunchGoogleAssist(
                    module,
                    logger,
                    pwm,
                    launchAssistMethod,
                    SystemClock.uptimeMillis(),
                    "OplusSpeechHandler"
                )
            ) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun tryLaunchGoogleAssist(
        module: XposedModule,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        launchAssistMethod: Method,
        eventTime: Long,
        source: String
    ): Boolean {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_missing_context", "$source 缺少 mContext，回退原逻辑")
            return false
        }

        if (!HookSupport.isPackageInstalled(context, ModuleConfig.GOOGLE_PACKAGE)) {
            logger.warnThrottled("${source}_google_missing", "$source: Google App 未安装，回退原逻辑")
            return false
        }

        val voiceCommandIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            setPackage(ModuleConfig.GOOGLE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (HookSupport.resolvesActivity(context, voiceCommandIntent)) {
            val started = runCatching {
                context.startActivity(voiceCommandIntent)
                lastInterceptUptime = SystemClock.uptimeMillis()
                logger.debug("$source: 已通过 VOICE_COMMAND 启动 Google")
                true
            }.getOrElse { throwable ->
                logger.warnThrottled(
                    "${source}_voice_command_failed",
                    "$source: VOICE_COMMAND 启动失败，改走 Assist 链: ${throwable.message}"
                )
                false
            }
            if (started) {
                return true
            }
        } else {
            logger.debug("$source: Google 未暴露 VOICE_COMMAND，改走 Assist 链")
        }

        val assistantPackage = HookSupport.extractPackageName(
            Settings.Secure.getString(context.contentResolver, "assistant")
        )
        val voiceInteractionPackage = HookSupport.extractPackageName(
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
        )
        if (assistantPackage != ModuleConfig.GOOGLE_PACKAGE &&
            voiceInteractionPackage != ModuleConfig.GOOGLE_PACKAGE
        ) {
            logger.warnThrottled("${source}_default_assistant", "$source: 当前默认助手不是 Google，回退原逻辑")
            return false
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug("$source: 命中去重窗口，直接吞掉重复触发")
            return true
        }

        return runCatching {
            module.getInvoker(launchAssistMethod).invoke(
                phoneWindowManager,
                null,
                -2,
                eventTime,
                ModuleConfig.POWER_ASSIST_INVOCATION_TYPE,
                ModuleConfig.POWER_ASSIST_LAUNCH_MODE
            )
            lastInterceptUptime = now
            logger.debug("$source: 已转发到系统 Assist 链")
            true
        }.getOrElse { throwable ->
            logger.error("$source: 调用 launchAssistAction 失败，回退原逻辑", throwable)
            false
        }
    }

    private fun resolvePhoneWindowManager(handlerInstance: Any): Any? {
        val owner = HookSupport.getFieldValue(handlerInstance, "this$0") ?: return null
        HookSupport.findField(owner.javaClass, "mPhoneWindowManager")?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }

        var current: Class<*>? = owner.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == ModuleConfig.PHONE_WINDOW_MANAGER_CLASS) {
                    field.isAccessible = true
                    return runCatching { field.get(owner) }.getOrNull()
                }
            }
            current = current.superclass
        }
        return null
    }

    private fun resolveLaunchAssistMethod(phoneWindowManager: Any): Method? {
        cachedLaunchAssistMethod?.let { cached ->
            if (cached.declaringClass.isAssignableFrom(phoneWindowManager.javaClass)) {
                return cached
            }
        }
        return resolveLaunchAssistMethod(phoneWindowManager.javaClass)
    }

    private fun resolveLaunchAssistMethod(clazz: Class<*>): Method? =
        HookSupport.findMethod(
            clazz,
            "launchAssistAction",
            String::class.java,
            Int::class.javaPrimitiveType!!,
            Long::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        )?.also { cachedLaunchAssistMethod = it }
}
