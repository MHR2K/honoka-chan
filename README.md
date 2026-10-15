# honoka-chan

LoveLive! 学园偶像祭（SIF1）自用离线服务端 & Android 单机启动器。

本项目旨在为《Love Live! School Idol Festival》提供轻量、稳定且功能完善的离线单机运行环境。支持 PC（Windows/Linux/macOS）以及 **Android 手机原生一体化独立运行**（无需电脑、免 Termux、免 Root）。

> 🤖 **AI 协作开发**：本分支的 Android 独立一体化单机启动器、内置全屏 GM 控制台、交叉编译工程化以及底层并发/数据库性能重构，由开发者与 AI（Google DeepMind Antigravity）深度结对编程（Pair Programming）协助完成。

---

## 🌟 核心特性

- **完整 SIF1 离线游戏支持**：覆盖卡片、剧情、演唱会、活动、抽卡等核心流程。
- **Android 原生单机启动器**：
  - **内嵌 Go 服务端核心**：通过 JNI 直接加载编译的 `libhonokachan.so`，前台服务常驻，免去搭建 Linux/Termux 等繁琐环境。
  - **内置全屏 GM 控制台 (In-App WebView)**：无需跳出应用或依赖外部浏览器，启动器内直接修改爱心、金币、等级与全图鉴卡片。
  - **一键启动游戏**：服务就绪后秒级唤起 SIF 客户端（已适配 Android 11+ 软件包可见性机制）。
  - **零拷贝数据挂载 & 物理安全防护**：通过软链接无缝挂载外部 16GB `list_CN_Android` 资源包，彻底杜绝误删外部存储文件。
  - **极速初始化**：卡片数据全量预载 Hash 优化，数据库初始化由 30 秒暴降至 0.1 秒内，杜绝并发闪退。
  - **存档管理**：支持一键导出与导入 `data.db` 用户游戏进度。
- **全日替曲目支持**：可自由开关全部日替曲目轮换。

---

## 📱 Android 单机版快速上手

1. **准备文件**：
   - 启动器安装包：`LoveLive_Offline_Launcher.apk`
   - 游戏客户端：`LoveLive_9.7.1_patched.apk`（已修改 API 地址指向本地 `127.0.0.1:8080`）
   - 离线全量资源包：`list_CN_Android`（解压至手机存储，如 `/sdcard/Download/data`）
2. **安装与授权**：
   - 在手机上同时安装启动器与游戏客户端。
   - 打开启动器，授予「全部文件访问权限」与通知权限。
3. **选择数据目录并启动**：
   - 点击「选择数据目录」，选中 `list_CN_Android` 所在文件夹。
   - 点击「启动服务」，等待服务状态显示为 `RUNNING`。
4. **畅玩与管理**：
   - 点击「打开 GM 控制台」进入后台修改账号数值与卡片。
   - 点击「启动游戏」直接进入 SIF1 畅享打歌！

详细编译与架构说明请参见 [Android 端详细文档](./android/README.md)。

---

## 💻 桌面端 / 服务器部署

请查阅 [docs](./docs/README.md) 目录下的系列指南：
1. [文件准备](./docs/01-prerequisites.md)
2. [客户端修改](./docs/02-client-modification.md)
3. [服务端部署](./docs/03-server-deployment.md)
4. [运行测试](./docs/04-testing.md)

---

## 📺 视频教程

- [合集·SIF、AS 私服（本地服）搭建教程 (Bilibili)](https://space.bilibili.com/671443/lists/2148053)

---

## 💖 特别感谢

- [虹原翼](https://github.com/NijiharaTsubasa) @ [LLSIF 查卡器](https://card.ganbaru.by/) 的技术支持
- tungnotpunk @ LL Hax 在 iOS 客户端修改方面的支持
- [NPPS4](https://github.com/DarkEnergyProcessor/NPPS4) 项目在歌曲、随机打歌等逻辑实现上的参考
- [alay](https://github.com/Salaron/alay) 项目在接口行为和功能补全上的参考
- 其他可能一时没想起来的大佬们和项目
