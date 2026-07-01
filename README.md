# 即阅 Jiyue

即阅是一个轻量 Android 本地文档预览器，用来快速打开 Markdown、HTML、TXT 和文档 ZIP。

它解决的是手机上的“即开即读”：从聊天软件、文件管理器或系统分享面板收到一个文档时，不需要建知识库、登录账号或配置同步，直接打开阅读。

## Features

- 从系统 `VIEW` / `SEND` Intent 接收 `.md`、`.markdown`、`.html`、`.htm`、`.txt`、`.zip` 文件。
- 手动文件选择入口。
- Markdown / TXT 阅读视图，支持标题目录、表格横向滚动、代码块、链接、图片、任务列表、Frontmatter 跳过、深色模式和缩放。
- HTML 使用本地 WebView 渲染，默认启用本地交互，支持 JavaScript、`<input type="file">` 文件选择和页面生成文件保存。
- WebView 拦截 `http/https` 请求，App 不申请联网权限。
- 文档 ZIP 会解压到 App 私有目录，优先打开 `index.html` / HTML，其次打开 README / Markdown / TXT。
- 最近文件、重复文件去重、收藏、删除本地记录。
- 文件和记录仅保存在本地 App 私有目录，无账号、无上传。

## Not A Browser

即阅不是完整浏览器，也不是 Obsidian 这类知识库工具的替代品。

它更适合：

- 本地 Markdown / TXT 阅读
- 本地 HTML 预览
- 图片压缩器、二维码生成器、文本处理器等离线 HTML 小工具
- 包含 `index.html`、README、Markdown 或 TXT 的文档 ZIP

复杂网页应用、登录流程、联网 API、Service Worker、摄像头/定位/蓝牙等浏览器能力，建议继续用手机浏览器。

## Version

- App name: 即阅
- Package: `com.jiyue.reader`
- Current version: `2.0.4`
- Version code: `24`
- Min SDK: 26
- Target SDK: 35

## Build

Open this project with Android Studio and run the `app` module.

Release APK signing material is intentionally not included. See [RELEASE.md](RELEASE.md) for release packaging notes.

## Privacy

即阅不申请联网权限，不上传文件，不需要账号。See [PRIVACY.md](PRIVACY.md).

## Download

Official signed APKs are published from GitHub Releases.

## Author

- 作者：冯导的AI工具箱
- 网站：https://www.fengdaoai.com
- 反馈与交流：公众号「冯导的AI工具箱」

## License

Apache License 2.0. See [LICENSE](LICENSE).
