package fuck.andes

import android.content.Context
import android.content.Intent
import android.os.Message
import android.os.SystemClock
import io.github.libxposed.api.XposedModule

internal object PowerHooks {

    @Volatile
    private var lastInterceptUptime = 0L

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        hookPowerLongPress(module, logger, classLoader)
        hookMiuiPowerLongPress(module, logger, classLoader)
        hookMiuiAssistLaunch(module, logger, classLoader)
        hookOplusSpeechHandler(module, logger, classLoader)
        hookMiuiSpeechHandler(module, logger, classLoader)
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

        if (powerLongPressMethod == null || resolvedBehaviorMethod == null) {
            logger.warn("PhoneWindowManager 关键方法缺失，跳过电源键 Hook")
            return
        }

        HookSupport.hookMethod(module, logger, powerLongPressMethod, "PhoneWindowManager.powerLongPress(long)") { chain ->
            val behavior = module.getInvoker(resolvedBehaviorMethod).invoke(chain.getThisObject()) as Int
            // 始终记录行为码，便于诊断
            logger.info("powerLongPress: behavior=$behavior")
            // 4=GO_TO_VOICE_ASSIST 5=ASSISTANT；HyperOS 可能用更高自定义值，宽松拦截
            if (behavior !in 4..9) {
                return@hookMethod chain.proceed()
            }

            if (tryLaunchGoogleAssist(logger, chain.getThisObject(), "powerLongPress")) {
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

            if (tryLaunchGoogleAssist(
                    logger,
                    pwm,
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
        logger: ModuleLogger,
        phoneWindowManager: Any,
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

        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug("$source: 命中去重窗口，直接吞掉重复触发")
            return true
        }

        val voiceCommandIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
            setPackage(ModuleConfig.GOOGLE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (HookSupport.resolvesActivity(context, voiceCommandIntent)) {
            val started = runCatching {
                context.startActivity(voiceCommandIntent)
                lastInterceptUptime = now
                logger.debug("$source: 已通过 VOICE_COMMAND 启动 Google")
                true
            }.getOrElse { throwable ->
                logger.warnThrottled(
                    "${source}_voice_command_failed",
                    "$source: VOICE_COMMAND 启动失败，回退原逻辑: ${throwable.message}"
                )
                false
            }
            if (started) {
                return true
            }
        } else {
            logger.warnThrottled("${source}_voice_command_missing", "$source: Google 未暴露 VOICE_COMMAND，回退原逻辑")
        }
        return false
    }

    private fun hookMiuiPowerLongPress(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val miuiPwmClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.MIUI_PHONE_WINDOW_MANAGER_CLASS)
        if (miuiPwmClass == null) {
            logger.warn("Xiaomi: 未找到 MiuiPhoneWindowManager，跳过 Xiaomi 电源键 Hook")
            return
        }
        // 仅当 Miui 子类自身覆盖了 powerLongPress 时才补 Hook，避免与 AOSP Hook 重复
        val powerLongPressMethod = runCatching {
            miuiPwmClass.getDeclaredMethod("powerLongPress", Long::class.javaPrimitiveType!!)
                .apply { isAccessible = true }
        }.getOrNull()
        if (powerLongPressMethod == null) {
            logger.debug("Xiaomi: MiuiPhoneWindowManager 未覆盖 powerLongPress，由 AOSP Hook 覆盖")
            return
        }
        val resolvedBehaviorMethod = HookSupport.findMethod(miuiPwmClass, "getResolvedLongPressOnPowerBehavior")
        if (resolvedBehaviorMethod == null) {
            logger.warn("Xiaomi: MiuiPhoneWindowManager 未找到 getResolvedLongPressOnPowerBehavior")
            return
        }

        HookSupport.hookMethod(module, logger, powerLongPressMethod, "MiuiPhoneWindowManager.powerLongPress(long)") { chain ->
            val behavior = module.getInvoker(resolvedBehaviorMethod).invoke(chain.getThisObject()) as Int
            logger.info("MiuiPWM.powerLongPress: behavior=$behavior")
            if (behavior !in 4..9) {
                return@hookMethod chain.proceed()
            }
            if (tryLaunchGoogleAssist(logger, chain.getThisObject(), "miuiPowerLongPress")) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookMiuiSpeechHandler(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val handlerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.MIUI_SPEECH_HANDLER_CLASS)
        val handleMessageMethod = handlerClass?.let {
            HookSupport.findMethod(it, "handleMessage", Message::class.java)
        }
        if (handleMessageMethod == null) {
            logger.warn("Xiaomi: 未找到 ${ModuleConfig.MIUI_SPEECH_HANDLER_CLASS}.handleMessage(Message)")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            handleMessageMethod,
            "${ModuleConfig.MIUI_SPEECH_HANDLER_CLASS}.handleMessage"
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != ModuleConfig.MIUI_ASSIST_MESSAGE_WHAT) {
                return@hookMethod chain.proceed()
            }
            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            if (pwm == null) {
                logger.warnThrottled(
                    "miui_speech_missing_pwm",
                    "Xiaomi SpeechHandler 未能解析 PhoneWindowManager，回退原逻辑"
                )
                return@hookMethod chain.proceed()
            }
            if (tryLaunchGoogleAssist(logger, pwm, "MiuiSpeechHandler")) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * HyperOS 3 可能不经过 powerLongPress 的 behavior 分支，而是直接在
     * MiuiPhoneWindowManager 内调用 launchAssist / triggerVoiceAssist 等方法。
     * 这里枚举常见名称，命中即拦截。
     */
    private fun hookMiuiAssistLaunch(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val miuiPwmClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.MIUI_PHONE_WINDOW_MANAGER_CLASS)
            ?: return

        val candidateNames = listOf(
            "launchAssistant", "launchAssist", "launchVoiceAssistant",
            "triggerVoiceAssist", "startAssistAction", "launchXiaoAi",
            "startXiaoAi", "triggerAssist"
        )

        var found = false
        for (name in candidateNames) {
            // 无参版本
            val m = runCatching {
                miuiPwmClass.getDeclaredMethod(name).apply { isAccessible = true }
            }.getOrNull() ?: continue

            HookSupport.hookMethod(module, logger, m, "MiuiPhoneWindowManager.$name()") { chain ->
                logger.info("MiuiPWM.$name() 被调用，尝试拦截为 Google")
                if (tryLaunchGoogleAssist(logger, chain.getThisObject(), "miuiAssist.$name")) {
                    null
                } else {
                    chain.proceed()
                }
            }
            found = true
        }

        if (!found) {
            logger.warn("Xiaomi: MiuiPhoneWindowManager 中未找到任何已知助手触发方法")
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
}
