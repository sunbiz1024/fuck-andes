# FuckAndes

干掉 ColorOS 小布助手，电源键长按唤起 Google Gemini，手势条长按识屏换成一圈即搜。

基于 libxposed API 101 的极简 Xposed 模块，仅针对以下设备适配验证：

- 设备：RMX5200（真我GT8 Pro）
- 系统：realme UI/ColorOS 16.0.5.701
- Android 16 / API 36

## 原理

模块在三个进程中分别 Hook：

**system_server**

- 拦截 PhoneWindowManager 的电源键长按和 OPPO 小布语音助手分发链，替换为启动 Google Gemini
- 强制启用 ContextualSearchManagerService 系统服务，将包名指向 Google App，并放行 SystemUI 的调用权限

**SystemUI**

- 拦截底部手势条长按触发的 OPPO OCR 识屏，改为通过 binder 直接调用 contextual_search 服务触发一圈即搜

**Google App**

- 在 Google 进程内伪装设备为gpt Samsung S24 Ultra，使其启用一圈即搜能力

## 项目结构

核心代码均在 `app/src/main/java/fuck/andes/` 下：

```
ModuleMain.kt              模块入口，按进程分发 Hook
PowerHooks.kt              电源键长按 + 小布语音助手拦截
ContextualSearchHooks.kt   补启动 contextual_search 服务并接管包名/权限
SystemUiHooks.kt           手势条长按识屏拦截，转发一圈即搜
GoogleAppHooks.kt          Google 进程内设备伪装
SystemServerHooks.kt       system_server Hook 总入口
HookSupport.kt             反射与 Hook 工具方法
ModuleConfig.kt            常量集中管理
ModuleLogger.kt            日志封装
```
