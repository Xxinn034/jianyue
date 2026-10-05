# tools/sdk — 随仓库分发的构建依赖

这三份二进制是构建必需的，而且**已经无法从官方渠道获得**（Android 1.0 SDK 早已下架、
Rhino 是针对 API 1 改造过的），所以直接放进仓库，让 clone 下来就能构建。

| 文件 | 大小 | 用途 | 来源 | 许可 |
|---|---|---|---|---|
| `aapt.exe` + `mgwz.dll` | 11.3 MB | aapt v0.2：打包资源并生成 R.java。2008 年的二进制，`aapt.exe` 旁边必须有 `mgwz.dll`，否则退出码 0xC0000135 | 归档的 Android 1.0 r2 SDK | Apache-2.0 |
| `android.jar` | 2.8 MB | API 1 的框架桩，作为 `javac -bootclasspath`。**这是把运行时 VerifyError 变成编译错误的关键** | 归档的 Android 1.0 r2 SDK | Apache-2.0 |
| `rhino-api1.jar` | 0.8 MB | 针对 API 1 改造过的 Rhino（去掉 `java.beans`、`Normalizer`、E4X），用于执行 `@js:` 书源规则 | [Rhino](https://github.com/mozilla/rhino)，改造脚本见 <https://github.com/> 上游与本项目说明 | MPL-2.0 |

> Rhino 以 **MPL-2.0** 分发：你可以把它与本项目（GPL-3.0）一起使用和分发，
> 但 Rhino 自身的修改部分仍需以 MPL-2.0 提供。见 `THIRD-PARTY.md`。

`build-reader.ps1` 的查找顺序是：`tools/sdk/` → 环境变量（`ANDROID_SDK_ROOT`、
`JAVA_HOME_8_X64` 等）→ 开发机上的绝对路径。所以本地和 CI 跑的是同一个脚本。
