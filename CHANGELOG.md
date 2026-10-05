# 更新日志 / Changelog

本文件记录每个公开发布的版本。版本号唯一来源是 `version.txt`，
`versionCode` 由 `MAJOR*10000 + MINOR*100 + PATCH` 自动推导（1.0.0 → 10000）。

## [1.0.1] - 2026-10-05

**能在新安卓上安装了** —— 1.0.0 的 APK 在 Android 14+ 上会被系统直接拒绝安装（`INSTALL_FAILED_DEPRECATED_SDK_VERSION`，因为它的 `targetSdkVersion` 是 1）。

### 修复
- **APK 现在带 `targetSdkVersion=28`**：aapt v0.2 + API-1 android.jar 写不出这个字段（资源表里没有该属性，换新 android.jar 它也读不了），
  所以由 `tools\axml-patch.ps1` 在构建第 6/8 步直接给编译好的二进制 manifest 补一个属性 + resource-map 项，资源表/dex/签名链路不变。
  第 8/8 步用 `aapt dump xmltree` 断言它真的进去了。
- **`usesCleartextTraffic="true"`**：targetSdk ≥ 28 起 Android 9+ 默认禁明文 HTTP，而书源全是 HTTP。同一个补丁一起加。
- **运行时存储权限**：targetSdk ≥ 23 后读 `/sdcard` 需要用户授权，新增 `util/Compat.java` 作唯一入口
  （编译期只有 API-1 符号，故全程反射；API < 23 自动短路），被权限对话框打断的导入会在授权后自动续跑。
- 取值 28 是实测定的：24/26 会被 Android 17 弹"为旧版 Android 构建"系统框，29 起强制分区存储会读不到 `/sdcard`。

### 验证
- Android 1.0（API 1）：`verify-on-api1.ps1` **34/34**
- Android 4.1.2（API 16）：`tools\verify-api16.ps1` **31/31**
- Android 11 真机（vivo V2073A）：全新安装 → 系统权限框"允许" → 书源导入 5 条 → 搜索 21 本/47 来源 → 阅读页正常
- Android 17（API 37，16KB 页）：普通安装成功、无兼容提示框 → 搜索 21 本/47 来源 → 目录 395 章 → 正文分页 → 本地 txt 导入 13 章
- 副作用（已确认无害）：targetSdk ≥ 4 后老设备不再套 legacy 屏幕兼容模式，API 16 由 384x640 拉伸变为 768x1184 原生渲染

## [1.0.0] - 2026-10-05

首个公开版本。

### 新增
- 首次带版本号发布：`versionCode 10000 / versionName 1.0.0`，支持覆盖安装升级
- 帮助页「关于本应用」显示当前版本（从 APK 自身读取，不会与 version.txt 脱节）

### 内容（此前一直存在于源码，此处一并归档）
- 规则引擎：JSONPath / CSS 选择器 / 精简 XPath / 正则替换 / `@js:`（改造版 Rhino），
  统一入口 `Engine.evalRule()` → `Rules.getString()`
- 联网搜索 → 详情 → 目录 → 正文全链路；多书源并行搜索、同名书跨源合并与换源
- 本地导入 txt / epb / html，按字节偏移分章
- 阅读页真实排版分页（离屏 TextView 量真实行首，不做换行预测）
- 底部三 tab（书架 / 书源 / 本地导入），仿开源阅读观感、自绘界面
- 适配 Android 1.0（API Level 1）：纯 Java，无 Kotlin、无 AndroidX、无第三方库
- 三档设备验收脚本（API 1 模拟器 / API 16 模拟器 / Android 5.0.2 真机）
