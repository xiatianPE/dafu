# 达芙 WebView ↔ Android 原生接口

统一 JS 对象名：`Native`。网页通过 `window.Native` 调用，所有参数使用基础类型，复杂结果以 JSON 字符串返回。

## 运行状态
- `Native.getNativeStatus()` → JSON：权限、OCR、悬浮窗、自动出价、游戏状态。
- `Native.isAccessibilityEnabled()` / `Native.isAccessibilityConnected()`
- `Native.isScreenCaptureRunning()` / `Native.isOverlayRunning()`

## 屏幕识别
- `Native.startScreenCapture()`：请求系统屏幕共享授权并启动 OCR。
- `Native.stopScreenCapture()`
- `Native.getScreenRecognition()` → 当前识别 JSON。
- `Native.readScreen()` → 无障碍节点 JSON。
- `Native.clickScreenItem(index)` → 点击最近一次节点快照中的节点。
- `Native.openAccessibilitySettings()`

## 自动出价
- `Native.requestAutoBid(price)`：请求试运行，填价并校验，不提交。
- `Native.requestAutoBidSubmit(price)`：明确请求实际提交；仍要求无障碍与 OCR 已连接。
- `Native.cancelAutoBid()`
- `Native.getAutoBidStatus()` → JSON。
- `price` 是游戏内部整数价格单位，不是页面展示的“万”。

## 游戏状态
- `Native.setGameMaxRounds(rounds)`：0 表示自动判断，1–100 表示总回合数。
- `Native.resetGameTracker()`
- `Native.getGameState()` → JSON。

## 悬浮窗与日志
- `Native.setOverlayEnabled(true|false)` → `started`、`stopped`、`permission_required` 或错误文本。
- `Native.getNativeLogs(limit)` → JSON 字符串数组，limit 自动限制在 1–80。

## 估价数据同步
- `Native.publishAuctionData(json)`：网页向原生端发布 schemaVersion=1 的估价数据。
- `Native.getAuctionData()`：读取最近一次发布的数据。
- 兼容现有 `Native.get/set/remove` 存储接口。

## 原生事件
网页监听：
```js
window.addEventListener("dafu-native", (event) => {
  const { name, data } = event.detail;
  // 例如 name === "ocr.updated" 或 "autobid.status"
});
```
原生端使用 `Bridge.emit(name, json)` 推送事件。事件名只允许字母、数字、点、下划线和连字符。

## 安全约定
- WebView 页面仅加载应用内 assets origin。
- 默认自动出价入口为试运行；实际提交必须调用独立的 `requestAutoBidSubmit`。
- 调用出价前必须检查无障碍服务与屏幕捕获状态。
- 所有 JSON 输入均有长度与 schemaVersion 检查。
