# 更新日志 / Changelog

本文件记录每个公开发布的版本。版本号唯一来源是 `version.txt`，
`versionCode` 由 `MAJOR*10000 + MINOR*100 + PATCH` 自动推导（1.0.0 → 10000）。

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
