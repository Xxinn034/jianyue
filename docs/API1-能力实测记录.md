# API 1 能力实测记录（书源引擎可行性依据）

本文件记录在**真实 Android 1.0 模拟器**上实测得到的平台能力边界。
所有结论都有设备日志或探针输出支撑，不是推测。

探针工程：`G:\api1-probe2`（源码 `src/io/legado/probe/MainActivity.java`）

---

## 一、实测结论总表

| 能力 | 结论 | 证据 |
|---|---|---|
| 纯 HTTP 请求 | ✅ 可用 | `HTTP = HTTP 200 read 5 lines, 168 chars` |
| URL 编码（GBK） | ✅ 可用 | `enc=%B6%B7%C2%DE%B4%F3%C2%BD dec=斗罗大陆 roundtrip=true` |
| JSON（`org.json`） | ✅ 可用 | `JSON = name=test items=2 first=a n=42` |
| 正则（`java.util.regex`） | ✅ 可用 | `Regex = 2 matches: /book/1=Book One /book/2=Book Two` |
| GZIP | ✅ 可用 | `java.util.zip.GZIPInputStream` 在 android.jar 中 |
| **JS 引擎（Rhino 改造后）** | ✅ **可用** | 见第三节，5 个 JS 用例全部执行成功 |
| **TLS 协议上限** | ⚠️ **只有 SSLv3 + TLSv1** | `TLS.protocols = SSLv3 TLSv1` |
| TLS 证书校验 | ⚠️ 可绕过 | 自定义 `X509TrustManager` + `HostnameVerifier` 后 baidu/qidian 返回 200/202 |

---

## 二、TLS 是硬约束（最重要）

API 1 的 SSL 栈是 2008 年的 OpenSSL 0.9.8h，**最高只支持 TLS 1.0**：

```
TLS.protocols = SSLv3 TLSv1
```

后果——只支持 TLS 1.2+ 的站点在 API 1 上**永远连不上**：

```
https://www.qq.com/                  -> SSL handshake failure
https://raw.githubusercontent.com/   -> error:1407742E:SSL23_GET_SERVER_HELLO:
                                        tlsv1 alert protocol version
```

这不是能靠改代码绕过的问题（除非自带一整套 TLS 实现，工程量与风险都极大）。

### 好消息：中文小说站大量仍是纯 HTTP

宿主机探测结果（`http=` / `https=` 分别对应 HTTP 与 HTTPS 可达性）：

| 站点 | HTTP | HTTPS | 可用性 |
|---|---|---|---|
| www.zongheng.com | 200 | 200 | ✅ 两种都行 |
| www.bookben.com | 200 | 无 | ✅ |
| www.biqu5200.net | 200 | 无 | ✅ |
| www.17k.com | 301 | 200 | ✅ 两种都行 |
| www.xbiquge.bz | 200 | 200 | ✅ 两种都行 |
| www.biquge5200.cc | 200 | 无 | ✅ |
| www.beqege.cc | 403 | — | ⚠️ 403（可能的反爬） |
| www.biquge.com.cn | 403 | 无 | ⚠️ 403 |
| www.69shu.com / www.ddxs.com / www.qu.la / www.bqg228.com | 不通 | 不通 | ❌ |

**结论：书源功能在 API 1 上成立**，但要用 HTTP 或 TLS 1.0 友好的站点，
并且需要处理 403 反爬（User-Agent 等请求头）。

### 网络层必须做的事
1. 默认安装宽松 `TrustManager` + `HostnameVerifier`（2008–2012 年的应用都这么干）；
2. 优先 HTTP，HTTPS 失败可回退；
3. 处理 charset（很多老站是 GBK）、GZIP、重定向；
4. 设置 UA 等请求头规避 403。

---

## 三、Rhino（JS 引擎）改造记录

阅读的书源规则大量使用 `@js:`，所以 JS 引擎价值很高。

### 3.0 结论：改造成功，JS 可用

```
Rhino: Context.enter() ok
Rhino: initStandardObjects -> org.mozilla.javascript.NativeObject
Rhino.js[0] = 1+1                             => 2.0
Rhino.js[1] = 'a' + 'b'                       => ab
Rhino.js[2] = var s=''; for(var i=0;i<5;i++){s+=i;} s  => 01234
Rhino.js[3] = JSON.stringify({a:1,b:[2,3]})  => {"a":1,"b":[2,3]}
Rhino.js[4] = (function(x){return x*3;})(7)  => 21.0
```

产物：`G:\api1-probe2\lib\rhino-api1.jar`（818,570 字节，364 个类）

**关键手段：用 API 1 的 `android.jar` 作为 `-bootclasspath` 重新编译 Rhino 源码。**
这把运行期的 `VerifyError` 提前成了**编译错误**——编译器会逐个指出所有不兼容点，
而不是等到设备上某个代码路径被触发才崩溃。这是整个改造最有效的一招。

运行期还必须设置 `setOptimizationLevel(-1)`（纯解释执行）：
Rhino 在优化级别 ≥0 时会在运行时生成 JVM 字节码，Dalvik 不接受。

### 3.1 版本选择（关键）

| 版本 | Java 字节码 | 签名引用 API 1 缺失类型 | 结论 |
|---|---|---|---|
| 1.7.13 | Java 8 | **8 个类**（含 `org.mozilla.javascript.Context`）引用 `java.lang.invoke.*` | ❌ 入口类被拒 |
| 1.7.7.2 | **Java 6** | 0 | ✅ 采用的基线 |
| 1.7.6 | Java 6 | 0 | ✅ 备选 |

用 `tools/check_jar_api1.py` 做判定。源码取自 Maven Central 的
`rhino-1.7.7.2-sources.jar`（不需要反编译）。

### 3.2 必须解决的四个问题

| # | 问题 | 出现在 | 处理 |
|---|---|---|---|
| 1 | `tools/debugger` 包（AWT/Swing） | d8 脱糖时直接报错 | `tools/slim_rhino.py` 剥离 `org/mozilla/javascript/tools/` |
| 2 | `java.beans.PropertyChangeListener/Event` | `Context`（**方法签名 + 方法体**） | 删除两个 listener 方法，清空 `firePropertyChange*` |
| 3 | `java.text.Normalizer` | `NativeString`（`String.prototype.normalize`） | 改为抛 `typeError`（书源不会用） |
| 4 | `javax.xml.transform.*` + DOM Level 3 | `xmlimpl`（E4X） | **整体排除 `xmlimpl` 包** |

### 3.3 三个值得记住的坑

**坑 1：`java.beans` 只在方法体内出现，签名扫描漏掉**

```
W/dalvikvm: VFY: unable to resolve check-cast 24 (Ljava/beans/PropertyChangeListener;) in Lorg/mozilla/javascript/Context;
W/dalvikvm: Verifier rejected class Lorg/mozilla/javascript/Context;
```

运行时表现为 `ClassNotFoundException: org.mozilla.javascript.Context`，极易误判为"没打包"。
**必须扫描整个常量池**（`tools/find_missing_java_types.py`），只扫签名会漏。

**坑 2：API 1 的 DOM 只是 Level 1**

API 1 **有**完整的 `org.w3c.dom`（Document/Node/Element/NodeList/…）和
`javax.xml.parsers.DocumentBuilderFactory`、`org.xml.sax.*`，但**缺 DOM Level 3**：

- `Node.getUserData()` / `setUserData()`
- `Node.lookupNamespaceURI()`
- `Document.renameNode()`

Rhino 的 E4X 深度依赖这些，所以 E4X **无法完整移植**。E4X 对书源毫无用处
（书源不会写 XML 字面量），因此整体排除是最优解。

**坑 3：编译期提供的类不能进 dex**

`java.beans` / `Normalizer` / `javax.xml.transform` 这些"仅编译期依赖"，
如果打进 dex 反而会因为自身引用 API 1 没有的东西而在运行期崩溃。
正确做法：放在 javac 的 `-classpath`（android.jar 在 `-bootclasspath` 优先级更高），
**且不加入 d8 的输入**。这些代码路径必须永不执行。

### 3.4 改造清单（`tools/patch_rhino_for_api1.py`）

全部是定向文本变换，**每步都带断言**——源码结构变化会直接报错，
而不是静默产出一个坏 jar：

```
Context.java patched (102872 -> 101284 bytes)
  - removed java.beans imports
  - deleted addPropertyChangeListener
  - deleted removePropertyChangeListener
  - stubbed firePropertyChange
  - stubbed firePropertyChangeImpl
XmlProcessor.java patched (15986 -> 18098 bytes)
  - removed TransformerFactory field
  - removed TransformerFactory.newInstance() from XmlProcessor
NativeString.java patched (37882 -> 37473 bytes)
```

`XmlProcessor` 里的 `toString(Node)` 原本用 XSLT 做序列化，被替换成手写 DOM 序列化
（`javascript.io.serialize()` 需要它，书源会用）。

---

## 四、书源引擎的可行性判断

| 规则能力 | API 1 可行性 |
|---|---|
| JSON 规则（`$.data[*].name`） | ✅ 需自写轻量 JSONPath（`org.json` 可用） |
| CSS 选择器 / HTML（`class.xxx@href`） | ✅ 需自写轻量选择器（无 jsoup） |
| XPath（`//div[@id='x']`） | ✅ 可用 `DocumentBuilderFactory` + `org.w3c.dom`（Level 1 足够） |
| 正则替换（`##regex##replacement`） | ✅ `java.util.regex` |
| **`@js:` 动态规则** | ✅ **Rhino 改造已验证可用** |
| `{{ }}` 变量模板 | ✅ 纯字符串处理 |
| 网络请求 | ✅ HTTP；HTTPS 仅 TLS 1.0 站点 |
| 字符集（GBK 等） | ✅ 已有自写解码器（见 `legado-api1` 的 `TextCodec`） |
| E4X（JS 里的 XML 字面量） | ❌ 需要 DOM Level 3，API 1 只有 Level 1；书源不用 |

**结论：书源引擎所需的全部能力在 API 1 上均可用。**

配套脚本：
- `tools/check_jar_api1.py` — 按方法签名判定（快，但会漏方法体内的引用）
- `tools/find_missing_java_types.py` — 扫整个常量池（慢，但准确；**用这个**）
- `tools/slim_rhino.py` — 剥离 Rhino 的桌面 tools 包
- `tools/patch_rhino_for_api1.py` — 剥离 `java.beans` / `Normalizer` / XSLT
- `tools/ExtractJdkClasses.java` — 用 `jrt:` 文件系统提取真实 JDK 类做仅编译期依赖
- `api1-probe2/build-rhino-api1.ps1` — 用 API 1 bootclasspath 重编译 Rhino

---

## 五、复现方式

```powershell
# 1) 编译并运行探针，取得本文件的所有数据
& 'G:\api1-probe2\build-probe.ps1'
adb -s emulator-5554 install -r G:\api1-probe2\out\probe.apk
adb -s emulator-5554 shell am start -n io.legado.probe/.MainActivity
adb -s emulator-5554 logcat -d -s ProbeApi1:I

# 2) 判定某个 jar 能否在 API 1 上加载
python G:\legado-api1\tools\find_missing_java_types.py <jar> `
  "$env:TEMP\api1-classes.txt" `
  G:\android-legacy\sdk-1.0_r2\android-sdk-windows-1.0_r2\android.jar

# 3) 重新生成可在 API 1 上运行的 Rhino
python G:\legado-api1\tools\patch_rhino_for_api1.py G:\api1-probe2\rhino-src\extract
& 'G:\api1-probe2\build-rhino-api1.ps1'
```

`$env:TEMP\api1-classes.txt` 是 API 1 平台类的清单，由
`jar tf <api1 android.jar>` 生成。如果不存在，先执行：

```powershell
& "$env:USERPROFILE\.jdks\jbr-17.0.14\bin\jar.exe" tf `
  G:\android-legacy\sdk-1.0_r2\android-sdk-windows-1.0_r2\android.jar `
  > "$env:TEMP\api1-classes.txt"
```

---

## 六、方法论：判断任意库的最低可用 API

本节做法可原样复用于"某个库最低能支持到哪个 API"：

1. 拿到该库源码，**用目标 API 的 `android.jar` 作为 bootclasspath 编译**；
2. 编译报错处即不兼容点，且**必然是运行期会崩的点**；
3. 缺失类型若只服务可选功能 → 剥离；若在核心路径 → 该库不可用。

实测表明：**不少"看起来需要较新 API"的库，剥离几个可选功能后就能降到 API 1。**
Rhino 就是最好的例子——我最初判断它"不可能"，改造后 5 个 JS 用例全部通过。

---

## 七、书源引擎实现中实测抓到的 bug（值得归档）

引擎在真实 API 1 设备上跑通纵横书源的过程中，抓到 6 个"看起来在工作"的 bug。
它们比 API 1 的平台限制更容易骗人，因为**代码不报错、只是结果为空或不对**。

| # | Bug | 症状 | 根因 |
|---|---|---|---|
| 1 | `JsonPath.eval` 未展开数组 | `ClassCastException: JSONArray` | 路径结尾落在数组上时返回了整个 `JSONArray`，而书源期望的是元素列表 |
| 2 | `##正则##` 分隔符索引错位 | `name##<[^>]+>##` 清不掉标签，输出 `<[^>]+>斗罗大陆<[^>]+>` | 用 `indexOf(sep)` 从 0 开始找，把**开头的** `##` 当成了分隔点，必须从索引 1 开始 |
| 3 | 规则风格被 ctx 污染 | 章节数恒为 0 | `if (ctx == CTX_JSON \|\| r.startsWith("$"))` 让 `class.chapter-list@tag.a` 在 ctx=JSON 时被送进 JSONPath。**规则风格必须只由语法决定**，ctx 只能决定"裸名字"的含义 |
| 4 | `viaCss` 漏了 `id.` 转换 | 正文恒为 0 字 | 只处理了 `class.` 和 `tag.`，`id.Jcontent` 被当成标签名 `id` |
| 5 | `tocUrl` 用相对路径拼错域名 | 目录 HTTP -1 / 0 章 | 目录在 `book.zongheng.com`、详情在 `www.zongheng.com`，相对路径按详情页域名拼接 |
| 6 | **`Connection: close` 请求头** | 正文 HTTP -1，换 HTTP 也不行；**但探针同样的 URL 却成功** | 旧版 HTTP 栈加上该头后对被重定向过的子域请求失败。去掉即恢复正常 |

### 两条经验

**经验 1：`ok()` 不能只看 `code > 0`。**
`HttpURLConnection.getResponseCode()` 在连接失败时返回 **-1**，而错误流可能给出空 body。
如果 `ok()` 写成 `code > 0 && body != null`，失败会被当成"成功但内容为空"，
于是**静默产出空章节**。必须写成 `code > 0 && code < 400`。

**经验 2：不要给老 HTTP 栈加"现代化"的请求头。**
`Connection: close` 和 `Accept-Encoding: gzip` 这类头在 API 1 上会造成难以定位的失败：
报错信息只有 `HTTP -1`，而同样的 URL 在探针里是 200。先怀疑请求头，再怀疑网络。

> 这两个 bug 的排查花了很久，因为**症状都是"空结果"而不是异常**。
> 结论：在 API 1 上做抓取，必须把每一步的 HTTP 状态和字节数打出来，
> 否则"没有内容"和"规则写错"从日志上完全分不出来。

