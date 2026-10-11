# 更新日志 / Changelog

本文件记录每个公开发布的版本。版本号唯一来源是 `version.txt`，
`versionCode` 由 `MAJOR*10000 + MINOR*100 + PATCH` 自动推导（1.0.0 → 10000）。

## [未发布 / Unreleased]

**两条主线：①200+ 书源的搜索不再"要好几分钟"；②书源引擎升级为"直接吃阅读 3.0 书源"，不用再逐条适配。**

### 搜索：从"等全部书源跑完"改成"边搜边出、随时可停"

旧实现是固定 4 线程 + 等所有源返回：导入 200+ 书源后，一个搜索要经过 200/4 = 50 轮，
每轮都要等一个死站点的连接超时，于是"搜索一次好几分钟"，而且**最后一个源跑完之前界面上一片空白**。
新实现（`engine/SearchJob.java`）：

- **并发按"等待"而不是"算力"定**：线程数按堆大小取 8/10/14/16（旧版写死 4），
  真正占内存的"解码 + 解析成 DOM"另用一个 4 个名额的信号量卡住——所以同时开着十几个请求也不会 OOM。
- **结果流式回传**：每个书源一返回就通知界面（阅读 3.0 的 `onSuccess` 也是每源一次），
  第一批结果约一个往返就出现，搜索框右侧的按钮变成「停止」，停止后已找到的书全部保留。
- **站点级失败缓存**：同一个站挂一次（DNS 解析不了 / 连接被拒 / TLS 握手失败 / 连接超时），
  该站上其它书源直接跳过并说明原因——200 个源往往只落在几十个站点上，超时次数从"每源一次"降到"每站一次"。
- **按"上次是否成功"排序**（`model/SourceHealth.java`，落在 `files/search-health.json`）：
  上次答得出来的源先搜，所以搜几秒钟点「停止」得到的结果，和等长尾死站全部超时是一样的。
- **整个搜索有时间预算**（45 秒）并有硬上限兜底：到点把没轮到的源报成"超时未查"，而不是无限转圈。
- 请求层同时补上了 **`@put/@get` 变量按线程隔离**（并行搜索时两个源曾互相覆盖变量）。

### 书源引擎：直接吃阅读 3.0 书源

- **旧版扁平格式书源可以直接导入**（`model/LegacySource.java`，对应阅读的 `SourceAnalyzer`）：
  `ruleSearchList → ruleSearch.bookList`、`ruleBookContent → ruleContent.content`、
  `httpUserAgent → header` 等 35 个字段的恒等映射，外加旧方言改写
  （`#`→`##`、`|`→`||`、`&`→`&&`、`searchKey/searchPage`→`{{key}}/{{page}}`、`@Header:`/`|charset=`→请求选项、`@body`→POST）。
  流通中的 4466 条书源里有 **2978 条是这种旧格式**，以前导入后规则全空。
- **URL 末尾 `, {json}` 请求选项**（`net/UrlOptions.java`）：`method`/`body`/`headers`/`charset` 全部生效，
  POST 正文按 `charset` 编码。搜索/详情/目录/正文四处的请求统一走 `Http.fetch()` 这一个入口。
  （此前"搜索只能 GET"直接判死了 bqg70、xswang 这类 POST-only 站点。）
- **URL 里的 `{{...}}` 模板**：`{{key}}`/`{{page}}` 之外，`{{page+1}}`、`{{本站 JS 表达式}}` 也走 JS 求值。
- **JS 宿主环境补齐**（`rule/Js.java`）：`java.*`（base64/md5/digestHex/timeFormat/timeFormatUTC/
  getString/getElement(s)/ajax/post/connect/get/put/encodeURI/log）、`Base64`（android.util.Base64 的等价物，
  API 1 没有它）、`source.*`（getVariable/putVariable/name/url/key）、`book.*`、`Packages.*` / `JavaImporter`。
  脚本在**每次调用独立的子作用域**里执行：`ImporterTopLevel` 顶层自带一个叫 `java` 的 JavaPackage，
  直接往全局写会输给它（实测报错 `Cannot call property getString in object [JavaPackage java]`），
  子作用域按"就近优先"拿到我们的对象，同时 `Packages.java.…` 依然可用。
  另加 `ClassShutter` 屏蔽 `java.io.File`/`Runtime`/`System`/反射等，书源脚本来自网络，不能让它随便读写文件。
- **`@ownText` / `@textNodes`** 取值后缀（此前只有 `@text`/`@html`/任意属性）。
- **构建修复：把 Rhino 的消息目录打进 APK**。改造版 `rhino-api1.jar` 重编译时丢了
  `org/mozilla/javascript/resources/Messages.properties`，于是**任何 JS 报错都变成
  `MissingResourceException: null`**——书源里一行 JS 出错，日志里只有这一句，根本没法查。
  现在 `build-reader.ps1` 第 5/8 步把它作为 class-path 资源塞进 APK。

### 验证

- Android 1.0（API 1）模拟器 `verify-on-api1.ps1`：**34/34 ALL PASS**（导入 → 搜索 → 详情 → 目录 → 阅读页分页全链路）
- Android 1.0 `RuleCheckActivity`：**73/73 PASS**（含旧格式转换、URL 选项、`{{page+1}}`、
  `java.*`/`Packages`/`book`/`source`、`@ownText`；且是"先跑一次搜索再跑自检"，专门覆盖跨线程 JS）
- 常用书源端到端（API 1，14 条 `device-sources.json`）：**17.7 秒**搜完 13 个源，80 条命中 → 合并 5 本
- 搜索耗时对比（API 1，120 源书单，`tools-tmp/measure-search-speed.ps1`）：
  旧引擎 120 秒只走完 **7 个源**且搜索没结束；新引擎 **197 秒走完全部 120 个源**，
  结果边搜边出、随时可停
- 书源兼容性（API 1，`SourceCheckActivity`，一程书源 231 条）：**199 条完全兼容（86.1%）**；
  剩余阻塞里除 1 条 JSONPath 过滤表达式外**全部是"该源需要 WebView"**（API 1 硬限制）；
  同口径逐源对比显示 12 条源因本轮补齐引擎（`java.*`/`Packages`/格式转换）而由不可用转为可用
- 完整实测报告：`G:\legado-api1\简阅搜索与书源引擎实测报告-2026-10-11.md`
- 未做/边界：WebView 类源、`cache.*`/登录态、JSONPath 过滤、XPath 轴与函数（详见报告第五节）

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
