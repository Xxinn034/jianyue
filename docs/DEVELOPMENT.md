# 简阅 · 开发细节（原 README，保留原始记录）

> 这是开发期的详细记录，措辞未改。文中 `../xxx.md` 之类的路径对应本仓库的 `docs/`；
> `../bookSources/sources-v4.json` 是私有目录里的实测书源，**未随仓库分发**。

## 原文：阅读 · API 1 版 —— 支持书源的联网阅读器

为 **Android 1.0（API Level 1，2008 年）** 从零实现的联网阅读器。
纯 Java，**无 Kotlin、无 AndroidX、无第三方库**（JS 引擎除外，见下）。

已在**真实 Android 1.0 模拟器**上完成端到端验证。

---

## 一、能做什么

| 功能 | 状态 | 设备实测证据 |
|---|---|---|
| 导入书源（粘贴 JSON / 从文件） | ✅ | `importPath result: added=1 updated=0 failed=0` |
| 书源持久化 | ✅ | `bookSources.json` 落盘 |
| 跨书源搜索 | ✅ | `search done: 1 books` |
| 书籍详情 | ✅ | `engine.info 斗罗大陆V重生唐三 / 唐家三少` |
| 目录（1184 章） | ✅ | `engine.toc -> 1184 chapters` |
| 正文抓取 | ✅ | `engine.content -> 1739 chars` |
| 中文解码（UTF-8 / GB2312） | ✅ | 自写解码器 + 映射表 |
| 阅读进度记忆 | ✅ | `progress.json` 落盘，重进自动恢复 |
| 书架 / 书源管理 / 本地导入 / 帮助 / 关于 / 文件选择 | ✅ | 6 个界面全部 `Displayed`，零崩溃 |
| JavaScript 书源规则（`@js:`） | ✅ | Rhino 改造后可执行 |
| JSONPath / CSS 选择器 / XPath / 正则替换 | ✅ | 规则引擎自检 36 项全绿（`RuleCheckActivity`） |
| **本地导入 txt / epub / html** | ✅ | 「本地导入」页右上角 `+`；实测 txt(utf-8/gbk) 13 章、无标题回退切块、xhtml 4 章、epub 4 章 |
| **本地书按字节偏移分章** | ✅ | `files/local/<id>.dat` + `index.json`，读一章 = seek + 一次读 |
| **同名书跨源合并 + 换源** | ✅ | 一行显示 N 个源，按章序比例迁移进度 |
| **实测可用书源 5 条** | ✅ | `../bookSources/sources-v4.json`，搜索 → 目录 → 正文逐条实测；v3 的 4 条（纵横/阅友/新笔趣阁/黑岩）20/20，新增猫九小说在设备上 705 章 / 首章 2256 字 |

### 界面设计

仿开源阅读的观感，但**刻意不做臃肿的部分**：

- **底部三 tab**：`书架 / 书源 / 本地导入`，每个 tab = 图标 + 文字，当前页的图标与文字换成
  强调色、并在顶部加一条 `2dip` 指示线（开源阅读的底栏就是这个形态）。
  API 1 **没有 Fragment、也没有 ViewPager**，所以三个页面各自是独立 Activity、
  **各自带一条 id 完全相同的底栏**（`Ui.tabs`），看上去才像一条常驻控件。
  切页 = 起目标页 + 结束当前页（书架是任务根，永不结束），所以栈里最多两页、
  返回键从书源/本地导入回书架。
  「我的」那一格**没做**——没有东西可放
- **书架**：白底列表 + 发丝分隔线，**不显示封面**、不做网格、无动画；
  顶部横向搜索框，**右边的提交控件是放大镜图标**（原来是红色「搜索」按钮，
  照开源阅读改成图标；`res/drawable/ic_search.png`，由 `tools/make_search_icon.py`
  以 8 倍超采样生成，点按区域仍是 `36dip` 见方）
- **搜索**：一行一本（同名跨源合并），副标题**把书源放在最前面**——
  `【纵横中文网 · 2 源】唐家三少 | 玄幻奇幻 | 2808349字`。
  原来书源排在副标题末尾，而这一行是 `singleLine + ellipsize=end`，
  于是只要作者/分类够长就被省略号吃掉，等于「看不到这本书来自哪个源」
- **详情页**：书名下方第一行就是 `书源：纵横中文网（共 N 个源，可换源）`，
  右边常驻「换源」按钮（只有一个源时也会显示，点了会说明只找到这一个源）
- **书源**：顶部标题行（书源 / 个数 / 批量）+ 紧随其后一行操作
  （粘贴导入 / 从文件导入 / 格式说明）。这三个按钮**原本在屏幕底部**，
  为了把底部让给 tab 栏而挪到顶部
- **本地导入**：只列 `sourceUrl = local://local` 的书（联网书留在书架页），
  右上角 `+` 选文件；空态写「可以点击加号来添加本地图书」
- **配色**：白底 + 克制的暖红强调色（`#BF3B2E`）+ 灰阶次级文字
- **排版**：`16sp` 主标题 / `12sp` 次级信息 / 充足行距
- **阅读页**：暖纸底色（`#F6F1E7`）、`8dip` 行距、**三等分点击区**
  （左 1/3 上一页、右 1/3 下一页、**中间 1/3 短按切上下状态栏、长按出菜单**），
  没有滑动、没有一排小按钮
- **分页（2026-10-04 重做）**：分页**不再用 Paint 预测换行，而是先把整章交给一个
  离屏 TextView 排一遍、按它真实的行首切页**。原因见「三个坑」第 1 条：
  TextView 是**按词换行**并带中文标点规则的，用 `Paint.breakText` 预测出来的行
  和实际渲染的行对不上，多出来的行会被底部 padding 裁成半个字。
  另外每页切完还会**单独排一遍验证**（页面是子串，子串的换行未必和整章一致），
  多一行就退一行；每页开头的空白会被跳过，免得页面顶上空一行
- **阅读页左右边距**：正文框不是左右对称的 18dip。中文每行只能在整字处断行，
  所以每行右边天生会多出**平均半个字**的空白；原来左右都是 18dip，
  这半个字就全落在右边，看起来「右边比左边宽一列」（实测 18sp 时：左边留白 19px、
  右边 28~38px）。现在按当前字号把正文框右移 `字宽/4`、右边距同量减少
  （25sp 实测 padL=24/padR=12，左边留白 25px、右边 15~33px、均值约 25px），
  总宽度不变，所以每行字数与分页都不变
- API 1 没有 Material 组件，所以观感是靠**颜色、间距、字号层级**做出来的，不是靠控件

---

## 二、平台能力实测（决定了架构）

全部数据来自真实 API 1 设备，详细记录见 `../API1-能力实测记录.md`。

| 能力 | 结论 |
|---|---|
| HTTP / GZIP / `org.json` / `java.util.regex` / DOM | ✅ 可用 |
| **TLS 协议上限** | ⚠️ **只有 SSLv3 + TLSv1** |
| TLS 证书校验 | ⚠️ 可绕过（自定义 TrustManager + HostnameVerifier） |
| JS 引擎 | ⚠️ 需用**专为本项目改造过的 Rhino** |

### 两个必须知道的限制

**1. 只支持 TLS 1.0 的 HTTPS。**
只支持 TLS 1.2+ 的站点在 API 1 上**物理上无法访问**。例如：

| 站点 | 结果 |
|---|---|
| `www.qidian.com`（起点） | ❌ 仅 TLS 1.2+，HTTP 也 302 跳 HTTPS → **无法支持** |
| `www.zongheng.com`（纵横） | ✅ HTTP 可达（实测 200） |

网络层会在 HTTPS 失败时**自动回退到 HTTP**。

**2. 商业站的 VIP 章节读不到全本。**
纵横正文页明确写着"本章节为VIP章节，需要订阅才可以继续阅读"。
这是正版站的必然结果，与阅读器无关。

---

## 三、代码结构

```
src/com/jianyue/reader/
  net/
    Http.java             HTTP 客户端：HTTP 优先 + TLS 回退 + GBK 解码 + GZIP
    NetKit.java           TLS 配置（宽松 TrustManager）与协议探测
  rule/
    JsonPath.java         轻量 JSONPath（$.a.b / [*] / [0] / ..）
    Html.java             宽容 HTML 解析器 + CSS 选择器引擎
    XPathLite.java        XPath 子集（//div[@id='x'] 等）
    Rules.java            规则引擎：四种语法 + ##正则## + 组合符 + <js>（无下标时合并所有匹配，同阅读）
    Js.java               Rhino 桥接（解释模式，变量注入）
  model/
    BookSource.java       书源模型与 JSON 解析（兼容阅读格式）
    Books.java            书籍 / 章节 / 搜索结果
    SourceStore.java      书源持久化与导入
    SourceSwitcher.java   同名书跨源合并 + 换源（按章序比例迁移进度）
    ProgressStore.java    阅读进度 + 书架
    LocalStore.java       本地导入的书（files/local + 章节字节偏移索引）
  engine/
    Engine.java           业务流：搜索 → 详情 → 目录 → 正文
  util/
    TextCodec.java        手写 UTF-8 / GB2312 / UTF-16 解码器
    GbTableData.java      【生成】GB2312→Unicode 映射表
    FileUtils.java        文件工具
    TextSplitter.java     TXT/HTML 分章（标题行优先，回退按块切）
    EpubExtractor.java    EPUB：ZIP + OPF spine + 宽容 HTML，不需要 XML 库
  ui/
    MainActivity.java         书架（tab 1）
    SourcesActivity.java      书源管理（tab 2：批量编辑 + 导入）
    LocalActivity.java        本地导入（tab 3：只列本地书，右上角 + 选文件）
    Ui.java                   窗口设置 + 底部三 tab 栏（画状态、接点击、打印底栏坐标）
    LocalImport.java          导入本地书的唯一入口（+ 按钮与宿主入口共用同一段代码）
    FilePickerActivity.java   文件选择（书源 .json / 本地书 txt+epub+html）
    SearchActivity.java       搜索（同名合并成组）
    BookDetailActivity.java   详情 + 目录 + 换源
    ReaderActivity.java       阅读（分页、长按菜单、本地书、换源）
    HelpActivity.java         书源格式说明
    RuleCheckActivity.java    规则自检 36 项
    SourceCheckActivity.java  书源语法体检
    SourceProbeActivity.java  站点可达性探测
    Paginator.java            同章分页
res/
  values/colors.xml       配色
  values/styles.xml       文字层级与列表行样式
  layout/                 各屏布局
```

**回归自检**：
- 规则引擎：`adb shell am start -n com.jianyue.reader/.ui.RuleCheckActivity` → 日志 `rulecheck: SUMMARY pass=36 fail=0`
- 底部 tab 栏 / 三页切换：`tools\verify-tabs.ps1`（33 项）
- 本地导入：`tools\verify-local-import.ps1`（23 项）
- 书源端到端：`tools\verify-sources-v3.ps1`（20 项）

---

## 四、构建

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& 'G:\jianyue-src\build-reader.ps1'
```

产物：`G:\jianyue-src\out\reader.apk`（约 542 KB）

依赖（均已就绪）：

| 组件 | 路径 |
|---|---|
| API 1 平台 | `G:\android-legacy\sdk-1.0_r2\...\android.jar` |
| 原版 aapt v0.2 + mgwz.dll | `G:\legado-api1\tools\` |
| **改造过的 Rhino** | `G:\api1-probe2\lib\rhino-api1.jar` |
| d8 / zipalign / apksigner | `build-tools\37.0.0` |

### 为什么 Rhino 要自己编译

原版 Rhino 在 API 1 上会 **VerifyError / ClassNotFoundException**：
`org.mozilla.javascript.Context` 的方法签名引用了 `java.lang.invoke.*`。

`rhino-api1.jar` 是**用 API 1 的 `android.jar` 作为 bootclasspath 重新编译** 1.7.7.2 源码得到的，
剥离了 `java.beans`、`java.text.Normalizer` 与 E4X XML 包。
重编译把运行期的 `VerifyError` 提前成了编译错误——这是整个项目最有效的一招。

生成方式见 `../API1-能力实测记录.md` 第三节。

---

## 五、部署与验证

### 一键验收（推荐）

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
& 'G:\jianyue-src\verify-on-api1.ps1'
```

结果（真实 API 1 设备）：

```
passed: 34
failed: 0
RESULT: ALL PASS
```

覆盖：设备确认、安装、**6 个界面（书架 / 书源 / 本地导入 / 搜索 / 帮助 / 选文件）逐一启动无崩溃**、
书源导入与持久化、搜索命中站点、详情、目录 1184 章、正文 2465 字、章节索引正确、
进度落盘、引擎自检（Rhino/JSONPath/CSS/XPath/正则）。

### 手工部署

```powershell
adb install -r G:\jianyue-src\out\reader.apk
adb push G:\legado-api1\bookSources\zongheng.json /sdcard/
# 正常使用：书架 →「书源」→「从文件导入」选 /sdcard/zongheng.json
#           书架 →「搜索」→ 输入书名
```

### 关于测试入口

API 1 的限制让自动化验证很棘手，界面里因此有几个**只在宿主驱动时才触发**的入口：

| 入口 | 用途 |
|---|---|
| `-e importPath <file>` → SourcesActivity | 直接导入书源文件 |
| `-e importBook <file>` → MainActivity / LocalActivity | 直接导入本地书（txt/epub/html） |
| `-e keyword <kw>` → SearchActivity | 直接发起搜索 |
| `-e sourceUrl/-e bookUrl/-e chapterIndex <n>` → ReaderActivity | 直接打开指定章节（本地书：`sourceUrl=local://local`） |
| `-e gesture center\|menu` → ReaderActivity | 驱动中间三分之一的短按 / 长按 |
| `--ei textSize <0-6>` → ReaderActivity | **按字号表切字号**（15/16/17/18/20/22/25sp），走菜单里「增大/减小字号」的同一条路径：API 1 不能注入点击，这是验证「字号变大后分页是否仍然放得下」的入口 |
| `--ei tabTap <0\|1\|2>` → 三个 tab 页 | **点一下底栏某个 tab**（走的是手指点同一个监听器） |
| `-e layoutDump 1` → MainActivity / SourcesActivity / LocalActivity | 打印控件真实坐标尺寸（含底栏三个 tab 的坐标与选中态） |
| 广播 `com.jianyue.reader.TEST_EXIT` | 让应用退出，以便下次启动重跑 `onCreate` |

它们调用的是**与界面完全相同的代码路径**，所以日志能真实反映功能行为。正常使用用不到。

**两个实测才知道的坑**：

1. 用 `am start` 启动**任务根 Activity**（MainActivity）必须带 `-f 0x04000000`
   （CLEAR_TOP）。否则系统只是把已有任务调到前面并**重放根 Activity 原来的 intent**，
   新的 extras 被丢掉——表现是"又导入了上一本书"。
2. int extra 要用 `--ei chapterIndex 0`；`-e` 传的是字符串，`getIntExtra` 会静默拿到 0。

**为什么需要 `TEST_EXIT`**：API 1 没有 `am force-stop`，
且它的 shell 里**连 `kill`/`pidof`/`grep`/`awk` 都没有**，
所以无法从宿主停止一个正在运行的应用。而重新启动一个已在栈顶的 Activity
不会重跑 `onCreate`，导致自检这类逻辑无法二次触发。

### 截图：能截，但走的不是 `screencap`

API 1 **没有 `screencap`**（截图命令 API 4 才引入），这个 2008 年的模拟器控制台也**没有
`screenrecord`**。但**内核 framebuffer 是可读的**：`/dev/graphics/fb0`（root shell 可读），
而且 `-no-window` 下模拟器照样往里渲染。

实测几何（这决定了怎么读）：`bits_per_pixel=16`（RGB565 小端）、`virtual_size=320,960`、
而 WindowManager 是 **480x320 横屏**——也就是说**帧缓冲里存的是转置图**（320x480），
而且是**双页缓冲**。所以：按 320x480 读、选对页、再转 90°。

```powershell
Set-ExecutionPolicy -Scope Process Bypass -Force
& 'G:\jianyue-src\tools\grab-screen.ps1' -Out G:\jianyue-src\out\shot.png
```

- `tools/fb_to_png.py` 负责 RGB565 → PNG，`--size 480x320 --rotate ccw` 是必须的
- **当前显示的是哪一页**由 `/sys/class/graphics/fb0/pan`（这里是 0 或 480）决定。
  不读它就会截到**上一帧**——实测截「本地导入」页拿回来的却是「书源」页
- 一次 dump 约 25 秒（远古 adbd 传 600KB），所以它适合人工看效果，不适合塞进自动化循环

自动化验收仍然靠 `layoutDump` 打坐标 + `dumpsys` 看任务栈；截图用来给人看观感。

---

## 六、写书源

格式说明：应用内「书源」→「格式说明」，或 `../书源JSON格式说明.md`。
实测可用的书源：`../bookSources/sources-v3.json`（纵横 / 阅友 / 新笔趣阁 / 黑岩）。

四条实测经验：

1. **优先找 JSON 接口**。很多站点前后端分离，HTML 只是空壳。
   纵横的搜索就是纯 JSON：`search.zongheng.com/search/book?keyword=...`
2. **`tocUrl` 若目录在别的子域，必须写绝对地址**。
   相对路径会按详情页域名拼接，结果 404 或 302。
3. **书名常带高亮标签**，用 `##<[^>]+>##` 清洗。
4. **正文规则不要写下标**（`class.con@html`，而不是 `class.con.0@html`）。
   阅读的语义是"把所有匹配用换行连起来"；一站把一章拆成多个容器时，
   只取第一个会得到**残缺但有内容的正文**，看起来像成功，最难查。
   本工程 `Rules.viaCss()` 已对齐这个语义。

写脚本时的提醒：**PowerShell 5.1 会把无 BOM 的 UTF-8 当 GBK 读**，
脚本里写中文会导致解析失败。本项目的验收脚本因此**全 ASCII**，
需要中文的地方用 `[char]0x6597` 这类字符码拼。

---

## 七、未实现（有意为之）

| 项 | 说明 |
|---|---|
| 封面显示 | 你明确说不需要 |
| 网格书架 / 动画 | 你明确说不要太臃肿 |
| 听书 / 音频 | 你明确说不需要 |
| E4X（JS 里的 XML 字面量） | API 1 的 DOM 只有 Level 1，缺 DOM Level 3；书源不用 |
| GBK 扩展区 | 只内置 GB2312 主体；扩展区显示为 U+FFFD 而非编造乱码 |
| EPUB 的 CSS / 图片 / 字体 | 只按 spine 顺序取正文，不做排版还原 |
| 章节缓存 / 导出书源 | 可以后续加 |

（本地导入与 epub 解析原列在此，2026-10-04 已实现：见 `LocalStore` / `EpubExtractor`。）
