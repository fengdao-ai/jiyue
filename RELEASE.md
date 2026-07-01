# 即阅发布说明

## 当前发布身份

- 应用名称：即阅
- 正式包名：`com.jiyue.reader`
- 当前版本：`2.0.4`
- 当前 `versionCode`：`24`
- 构建方式：Android Studio 生成 Release APK

包名和 Release 签名决定 Android 是否认为后续版本是同一个应用。发给真实用户后，后续版本应继续使用 `com.jiyue.reader` 和同一套签名文件。

## 生成 Release APK

1. 用 Android Studio 打开本项目根目录。
2. 等待 Gradle Sync 完成。
3. 进入 `Build > Generate Signed Bundle / APK...`。
4. 选择 `APK`。
5. 第一次打包时选择 `Create new...` 生成 keystore。
6. 保存 keystore 到项目目录之外，例如个人文档的安全备份目录。
7. 选择 `release` 构建变体并完成打包。
8. 找到生成的 `app-release.apk`，建议重命名为 `即阅-2.0.4-release.apk` 再分发。

不要把 keystore、密码、`keystore.properties` 或 `signing.properties` 放进仓库。`.gitignore` 已经加入常见签名文件规则，但签名文件仍建议保存在项目目录之外。

## 分发前检查

- 安装后桌面名称显示为“即阅”。
- 可从系统文件管理器打开 `.md`、`.markdown`、`.html`、`.htm`、`.txt`、文档 `.zip`。
- 可从聊天或系统分享面板分享 Markdown / HTML 文件到“即阅”。
- 最近文件、收藏、删除本地记录可用。
- HTML 默认启用本地交互，JavaScript、页面内文件选择器和页面生成文件保存可用；需要更保守时可在显示菜单关闭本地交互。
- 文档 ZIP 可解压并打开 `index.html` / HTML / README / Markdown / TXT。
- App 不申请联网权限，WebView 会拦截 `http/https` 网络请求。
- 通过聊天软件或网盘发送 APK 后，可正常下载和安装。

Android 的“未知来源应用安装”提示是系统安全提示，不是应用错误。

## 应用宝上架预备

应用宝上架入口为腾讯应用开放平台：https://app.open.qq.com/

后续正式上架前准备：

- 腾讯应用开放平台开发者账号和实名认证。
- 应用名称、图标、截图、简介、分类、关键词、版本说明。
- Release APK。
- 隐私政策 URL 和权限说明。
- App 备案信息。腾讯应用开放平台提示，2023 年 9 月 1 日之后新增 App 需先履行 App 备案手续再申请上架。
- 如审核要求补充，再准备软著、版权证明或其他资质材料。

为了降低审核复杂度，当前版本应保持本地文件工具定位：无账号、无上传、无联网权限、不接入支付。
