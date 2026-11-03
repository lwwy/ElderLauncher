# 老人桌面 (ElderLauncher)

一款**纯本地**的安卓简易桌面 / 长辈助手应用。灵感来自对一款商业「老人桌面」App 的逆向分析，
但本仓库为**全新原创实现**（Kotlin），不包含任何原应用的代码，也不包含任何联网功能。

## 功能（全部本地，无需网络 / 账号 / 付费）

| 模块 | 说明 |
| --- | --- |
| 简易桌面 | 大字号主屏，可作为默认桌面（HOME） |
| 拨号 | 大按键拨号盘，一键呼叫 |
| 联系人 | 读取本机通讯录，点按即拨 |
| 短信 | 会话列表 + 发送（本地） |
| 相册 | 本机照片九宫格 + 幻灯片播放 |
| 骚扰拦截 | 本地黑名单，来电自动挂断并语音提示 |
| 语音播报 | 设备自带 TTS 引擎：整点报时、来电播报、低电量播报、新短信播报（开关可在设置中关闭） |

## 明确没有的功能（与原商业版的区别）

- ❌ 定位上传 / 家人远程守护
- ❌ 亲情号绑定 / 账号登录 / 微信 OAuth 登录
- ❌ 短信远程指令
- ❌ 支付 / 会员 / 远端 TTS 合成
- ❌ 任何 `INTERNET` 权限与后台上报

## 构建

### 方式一：GitHub Actions（推荐，零本地环境）

1. 把这个仓库推到你的 GitHub（公开或私有均可）。
2. 进入仓库 **Actions → Build APK → Run workflow**。
3. 构建完成后在 **Artifacts** 中下载 `elder-launcher-debug.apk`。
4. 手机开启「未知来源安装」后安装即可。

### 方式二：本地（Android Studio）

- 用 **Android Studio** 打开本目录（它会自动补全 Gradle Wrapper）。
- 执行 `./gradlew assembleDebug`，产物在 `app/build/outputs/apk/debug/`。
- 若已安装 Gradle 8.2，也可直接 `gradle assembleDebug`。

> 注意：仓库内未附带 `gradle-wrapper.jar`（二进制），因此直接 `./gradlew` 会失败，
> 请使用 Android Studio 打开或本地装有 Gradle 8.2 时直接用 `gradle` 命令。

## 权限说明

应用会申请电话、通讯录、短信、存储、通知等权限，均为对应本地功能所必需。
无任何网络权限。所有数据仅在本机处理，不会上传。

## 技术栈

- Kotlin + AndroidX (AppCompat)
- minSdk 24 / targetSdk 34 / compileSdk 34
- AGP 8.2.2 + Gradle 8.2 + Kotlin 1.9.22

## 免责声明

本工程为学习 / 自用目的的开源实现，与任何商业应用无代码关联。
请仅在你**合法拥有或已获授权**的设备上使用，遵守当地法律法规与隐私规范。
