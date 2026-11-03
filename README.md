# 老人桌面 (ElderLauncher)

一款**纯本地**的安卓简易桌面 / 长辈助手应用
但本仓库为**全新原创实现**（Kotlin），不包含任何联网功能。

## 功能（全部本地，无需网络)

| 模块 | 说明 |
| --- | --- |
| 简易桌面 | 大字号主屏，可作为默认桌面（HOME） |
| 常用联系人 | 首页 2×2 卡片：空卡点击从通讯录选人，已添加卡点击直接拨号，长按可移除 |
| 拨号 | 大按键拨号盘，一键呼叫 |
| 联系人 | 读取本机通讯录，点按即拨 |
| 短信 | 会话列表 + 发送（本地） |
| 来电大卡片 | 全屏来电界面，仅「接听 / 挂断」两个大按钮，参考老人桌面 UI |
| 语音播报 | 设备自带 TTS 引擎：来电播报、低电量播报（开关可在设置中关闭） |

## 设计风格

- 采用 **Material Design 3（Material You）** 深色主题，蓝色主色调。
- 卡片统一使用 `MaterialCardView`，按钮统一使用 `MaterialButton`，开关使用 `SwitchMaterial`。
- 启动图标为 Material 风格自适应图标（蓝色圆角底色 + 白色电话图形），并附带各密度 legacy 图标。


### 方式一：GitHub Actions（推荐，零本地环境）

1. 把这个仓库fork你的 GitHub（公开或私有均可）。
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

应用会申请电话、通讯录、短信等权限，均为对应本地功能所必需（拨号/来电、通讯录、短信读写）。
无任何网络权限。所有数据仅在本机处理，不会上传。

## 技术栈

- Kotlin + AndroidX + Material Design 3 (`com.google.android.material:material:1.12.0`)
- minSdk 24 / targetSdk 34 / compileSdk 34
- AGP 8.2.2 + Gradle 8.2 + Kotlin 1.9.22

## 免责声明

本工程为学习 / 自用目的的开源实现，与任何商业应用无代码关联。
请仅在你**合法拥有或已获授权**的设备上使用，遵守当地法律法规与隐私规范。
