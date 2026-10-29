# 达芙估价（安卓壳）

WebView 加载 `dafu.html` + 悬浮窗。悬浮窗里就是同一个估价页，输入由你手动完成；
**没有**任何自动读取游戏画面、自动填价或自动点击的功能。

## 构建（Android Studio）
1. Android Studio Koala (2024.1) 或更新，JDK 17。
2. File → Open，选本文件夹，等 Gradle 同步完成（需联网下载依赖）。
3. Build → Build Bundle(s) / APK(s) → Build APK(s)，生成的 debug APK 在 `app/build/outputs/apk/debug/`。
4. 命令行也可以：先 `gradle wrapper --gradle-version 8.7` 生成 wrapper，再 `./gradlew assembleDebug`。

## 使用
1. 打开应用，点右上「开启悬浮窗」，按提示授予「显示在其他应用上层」，回来再点一次。
2. 屏幕上出现金色圆球「估」：点一下展开面板，按住标题栏拖动，「—」或返回键最小化，「✕」关闭。
3. 通知栏有常驻通知（前台服务，防止被系统杀掉），也可以从那里关闭。

## 数据互通
- `assets/native-shim.js` 会在页面脚本之前注入，把 `dafu-` 开头的 localStorage 读写同步到原生 `Store`（SharedPreferences）。
  所以 **`dafu.html` 不用改**，以后直接替换 `app/src/main/assets/dafu.html` 即可。
- 主界面和悬浮窗两个 WebView 共用同一份数据：一边改过后，另一边在回到前台/重新展开时自动刷新。
- 原生代码读写：`Store.get("dafu-auction-v3")`（估价状态 JSON）。
- 原生 → 网页：`WebSetup.emit(webView, "名字", """{"a":1}""")`，网页里定义 `window.onNative = (name, data) => {...}` 接收。
- 直接用浏览器打开 `dafu.html` 时没有 `window.Native`，自动退回普通 localStorage，照常可用。

## 文件
- `MainActivity.kt` 主界面（顶部原生条 + 全屏 WebView）
- `OverlayService.kt` 悬浮窗前台服务
- `WebSetup.kt` / `Bridge.kt` / `Store.kt` WebView 配置、JS 桥、数据存储

## 已知限制
- 这份代码是在没有 Android SDK 的环境里写的，没有编译和真机测试过，第一次构建如有报错把日志发给我。
- 面板宽度按屏幕算：竖屏约 94%、横屏约一半（最大 480dp），页面会走窄屏布局（底栏抽屉）。
- 部分游戏/系统会限制悬浮窗显示，或在后台杀应用，需要自己在系统设置里给本应用开自启/后台运行。

## GitHub 自动构建
推送到 main/master 会自动构建 debug APK，在仓库 Actions 页面对应的运行记录底部 Artifacts 里下载 `dafu-debug-apk`。
打 tag（如 `git tag v1.0 && git push origin v1.0`）会同时发布到 Releases。
注意：每次构建用的 debug 签名可能不同，覆盖安装提示「签名不一致」时，先卸载旧版再装。
