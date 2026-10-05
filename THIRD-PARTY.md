# 第三方组件 / Third-party notices

简阅自身的代码以 **GPL-3.0** 发布（见 `LICENSE`）。下面是随仓库分发或参考到的第三方内容。

## Rhino（`tools/sdk/rhino-api1.jar`）— MPL-2.0

Mozilla Rhino，为适配 Android 1.0 做过改造：去掉 `java.beans`、`Normalizer`、E4X。
以 **Mozilla Public License 2.0** 分发，许可证全文见 <https://www.mozilla.org/MPL/2.0/>。

MPL-2.0 是文件级 copyleft：本项目可以整体以 GPL-3.0 发布，但 Rhino 自身（含本仓库所做的
修改）仍应按 MPL-2.0 提供。源码见 <https://github.com/mozilla/rhino>。

## Android SDK 组件（`tools/sdk/aapt.exe`、`mgwz.dll`、`android.jar`）— Apache-2.0

来自已归档的 **Android 1.0 r2 SDK**（`android-sdk-windows-1.0_r2`）。这些工具在官方渠道
已经无法获取，为了让 clone 下来即可构建，直接随仓库分发。若你所在地区或场景不允许再分发
SDK 组件，可以用自己手上的老 SDK 副本替换它们（文件名保持不变即可）。

## 书源规则语义参考自 gedoor/legado

简阅的规则引擎（JSONPath / CSS / XPath / `@js:` 的写法与取值语义）以
[gedoor/legado](https://github.com/gedoor/legado)（GPL-3.0）的行为为基准，
目的是让现有书源能直接使用。**没有复制其源代码**：简阅是纯 Java 从零实现，
上游是 Kotlin + AndroidX，两者没有文件级的继承关系，本项目也不是它的分支。

## 书源数据

仓库**不包含**任何书源 JSON，也不提供、不托管任何书籍内容。书源由使用者自行提供，
格式见 `docs/书源JSON格式说明.md`。
