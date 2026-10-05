# 书源 JSON 格式说明（面向本项目的 API 1 阅读器）

本文档说明本项目支持的**书源 JSON 格式**，并对每个字段标注**在 Android 1.0 上的可用性**。
所有"可用/不可用"结论都来自真实 API 1 设备实测（见 `API1-能力实测记录.md`）。

配套实测书源：`bookSources/zongheng.json`（纵横中文网）

---

## 一、先看两个硬约束（决定了书源怎么写）

### 1. HTTPS 只能用 TLS 1.0 的站点

Android 1.0 的 SSL 栈上限是 **TLS 1.0**。只支持 TLS 1.2+ 的站点**永远连不上**：

| 站点 | 主机 | API 1 可用性 |
|---|---|---|
| 纵横中文网 | `search.zongheng.com` / `book.zongheng.com` / `read.zongheng.com` / `www.zongheng.com` | ✅ **HTTP 可达**（实测 200） |
| **起点中文网** | `www.qidian.com` | ❌ **不可用**（仅 TLS 1.2+，HTTP 是 302 跳 HTTPS） |

**结论：起点在 API 1 上无法支持**，这不是书源能解决的问题。
本项目的网络层会在 HTTPS 失败时回退到 HTTP。

### 2. VIP 章节拿不到

商业站的正版小说，收费章节需要登录订阅。以纵横为例，正文页里明确写着：

```html
<div class="content">…免费试读…</div>
<div class="reader-end">抱歉哦，本章节为VIP章节，需要订阅才可以继续阅读哦～</div>
```

所以纵横书源**只能读免费章节和 VIP 章节的试读部分**。
要读全本，需要目标是**免费站**（盗版聚合站），或接受这个限制。

---

## 二、书源 JSON 结构

```jsonc
{
  "bookSourceName": "纵横中文网",          // 书源名称（显示用）
  "bookSourceUrl": "http://www.zongheng.com", // 站点主域，用于相对 URL 拼接
  "bookSourceGroup": "正版",               // 分组（可选）
  "enabled": true,                        // 是否启用

  // ---- 搜索 ----
  "searchUrl": "http://search.zongheng.com/search/book?keyword={{key}}&pageNo={{page}}&pageNum=20",
  "ruleSearch": {
    "bookList": "$.data.datas.list",       // 结果列表的路径
    "name": "name",                        // 书名
    "author": "authorName",                // 作者
    "bookUrl": "/detail/{$.bookId}",       // 详情页 URL
    "intro": "description",                // 简介
    "wordCount": "totalWord"               // 字数（可选）
  },

  // ---- 详情页 ----
  "ruleBookInfo": {
    "name": "class.book-info--title@text",
    "author": "class.book-info--author@text",
    "intro": "class.book-info--intro@text",
    "wordCount": "class.book-info--nums@text##.*?(\\d+\\.?\\d*)万字数.*##$1万字"
  },

  // ---- 目录 ----
  "ruleToc": {
    // 目录页地址。可省略——省略时用详情页地址作为目录页。
    // 也支持模板 {$.bookId}；注意若目录在别的子域，必须写【绝对地址】，
    // 否则相对路径会按详情页的域名拼接而失效。
    "tocUrl": "http://book.zongheng.com/showchapter/{$.bookId}.html",
    "chapterList": "class.chapter-list@tag.a",
    "chapterName": "text",
    "chapterUrl": "href",
    "isVip": "class.vip@text"
  },

  // ---- 正文 ----
  "ruleContent": {
    "content": "id.Jcontent@html##<p>|</p>",
    "nextContentUrl": ""                   // 翻页规则，纵横不需要
  }
}
```

---

## 三、字段与语法参考

### 3.1 `{{ }}` 变量

| 变量 | 含义 |
|---|---|
| `{{key}}` | 搜索关键词（自动 URL 编码） |
| `{{page}}` | 页码（从 1 开始） |

### 3.2 规则语法

规则分三种风格，本项目都支持：

| 风格 | 写法 | 例子 | API 1 可用 |
|---|---|---|---|
| **JSONPath** | 以 `$.` 或 `$[` 开头 | `$.data.datas.list` | ✅ `org.json` |
| **CSS 选择器** | `class.xxx` / `id.xxx` / `tag.xxx` | `class.book-info--title@text` | ✅ 自写解析器 |
| **XPath** | 以 `//` 开头 | `//div[@id='Jcontent']` | ✅ DOM Level 1 |
| **正则** | `##正则##替换` 追加在规则后 | `##\\s+##` | ✅ `java.util.regex` |
| **JS** | 以 `@js:` 开头 | `@js:result.replace(...)` | ✅ Rhino（已改造） |

### 3.3 取值后缀

CSS 选择器后面用 `@` 指定取什么：

| 后缀 | 含义 |
|---|---|
| `@text` | 取文本内容 |
| `@html` | 取内部 HTML |
| `@href` / `@src` | 取属性 |
| `@textNodes` | 取所有文本节点 |

不写后缀时默认取文本。

### 3.4 JSONPath 风格

```jsonc
"bookList": "$.data.datas.list",   // 列表
"name":      "name",               // 取当前项的字段（不用写 $.）
"author":    "authorName"
```

列表内取值直接写字段名即可。

### 3.5 `bookUrl` 里的模板

`bookUrl` 可以用 `{$.字段}` 引用当前项的 JSON 字段：

```jsonc
"bookUrl": "/detail/{$.bookId}"
```

拼接时以 `bookSourceUrl` 为主域。

### 3.6 `##正则##替换`

- `##正则` — 只删匹配内容
- `##正则##替换` — 替换，`$1` 引用分组
- 想删 HTML 标签：`##<[^>]+>##`

例：搜索接口返回的书名带高亮标签 `<font color="RED">斗罗大陆</font>V重生唐三`，
用 `##<[^>]+>##` 就能清掉。

---

## 四、在 API 1 上不可用的书源能力（如实列出）

| 能力 | 状态 | 原因 |
|---|---|---|
| 目标站点仅 TLS 1.2+ | ❌ | API 1 的 SSL 栈上限 TLS 1.0 |
| E4X（JS 里写 XML 字面量） | ❌ | API 1 的 DOM 只有 Level 1，缺 DOM Level 3 |
| `String.prototype.normalize` | ❌ | `java.text.Normalizer` 是 API 9+ |
| VIP 章节正文 | ❌ | 需要登录订阅，与阅读器无关 |
| 需要 WebView 渲染的页面 | ⚠️ | API 1 有 WebView，但很难取到渲染后的 DOM |

其余常用能力（JSON、CSS、XPath、正则、JS、GBK 解码、GZIP、Cookie、重定向）**均可用**。

---

## 五、写一个新书源的步骤

1. **确认站点在 API 1 上可达**：先试 `http://`，能用 HTTP 就别用 HTTPS
   （用 `api1-probe2` 探针或在设备上直接请求）
2. **找搜索接口**：优先找 **JSON API**（很多现代站点是前后端分离的，
   HTML 只是空壳）。在浏览器开发者工具里看 XHR 请求
3. **找详情/目录/正文 URL 规律**：注意 HTTP 是否被 302 跳到 HTTPS
4. **抄选择器**：把页面保存下来，找书名/作者/章节列表/正文的容器
5. **写 JSON**，先在设备上跑通搜索，再验证目录和正文

> 提醒：书源是**易碎**的。站点改版会导致选择器失效，这是所有阅读类应用的共同问题，
> 不是本项目特有的。所以 JSON 里尽量用**语义化 class 名**而不是位置选择器。

---

## 六、排查方法

在设备上打开应用后：

```powershell
adb -s emulator-5554 logcat -d -s JianYue:I
```

规则引擎会打印每一步的请求 URL、响应状态、命中数量、取值结果，
哪个环节为空一眼就能看出来。
