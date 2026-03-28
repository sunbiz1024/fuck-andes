package fuck.andes

internal object ModuleConfig {
    const val TAG = "FuckAndes"
    const val ENABLE_VERBOSE_LOGS = false
    const val HOT_PATH_LOG_WINDOW_MS = 60_000L

    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    const val CONTEXTUAL_SEARCH_ACTION = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH"
    const val CONTEXTUAL_SEARCH_SERVICE = "contextual_search"
    const val CONTEXTUAL_SEARCH_CLASS =
        "com.android.server.contextualsearch.ContextualSearchManagerService"
    const val TIMINGS_TRACE_AND_SLOG_CLASS = "com.android.server.utils.TimingsTraceAndSlog"
    const val SYSTEM_SERVER_CLASS = "com.android.server.SystemServer"
    const val PHONE_WINDOW_MANAGER_CLASS = "com.android.server.policy.PhoneWindowManager"

    const val CIRCLE_TO_SEARCH_ENTRYPOINT = 2
    const val INTERCEPT_DEDUP_WINDOW_MS = 1_000L

    // ===== OPPO ColorOS / Andes AI =====
    const val OCR_BUSINESS_CLASS =
        "com.oplus.systemui.navigationbar.ocrscreen.OplusOcrScreenBusiness"
    const val OP_LUS_SPEECH_HANDLER_CLASS =
        "com.android.server.policy.PhoneWindowManagerExtImpl\$OplusSpeechHandler"
    const val OP_LUS_ASSIST_MESSAGE_WHAT = 0x3F3

    // ===== Xiaomi HyperOS 3 / 小爱同学 =====
    // PhoneWindowManager 的小米子类，会覆盖 powerLongPress 等方法
    const val MIUI_PHONE_WINDOW_MANAGER_CLASS =
        "com.android.server.policy.MiuiPhoneWindowManager"

    // SystemUI 小爱/AI 识屏触发入口
    // 若 Hook 未命中，请用 jadx 反编译设备上的 SystemUIGoogle.apk，
    // 搜索含 "onLongPress" 且与「小爱」「AI识屏」「圈选」相关的类名，替换此值。
    const val MIUI_AI_SCREEN_CLASS =
        "com.miui.systemui.miai.MiAIScreenSuggestion"

    // SystemServer 层小爱语音 Handler 内部类
    // 若未命中，请反编译 framework.jar，搜索继承 Handler 且处理小爱唤醒消息的内部类。
    const val MIUI_SPEECH_HANDLER_CLASS =
        "com.android.server.policy.MiuiPhoneWindowManager\$XiaoAiSpeechHandler"

    // 触发小爱的 Handler.what 消息码
    // 若拦截无效，请反编译后查找 MSG_LAUNCH_XIAOAI 或类似常量，替换此值。
    const val MIUI_ASSIST_MESSAGE_WHAT = 0x3F3
}
