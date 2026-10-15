# Android 控制端

这个目录提供一个 Kotlin/Compose Android App，用来把 `honoka-chan` 作为本地动态库加载，并通过 JNI 控制启停。

## 当前功能

- **启动 / 停止本地 `honoka-chan` 服务**：通过 JNI 与 Android 前台服务无缝管理后台常驻运行
- **内置全屏 GM 控制台 (In-App WebView)**：无需外部浏览器，在启动器内直接管理账号、增减爱心金币等级与卡片，原生适配系统返回键
- **【启动游戏】快捷调起**：服务就绪后秒级唤起 SIF 国服客户端（适配 Android 11+ 软件包可见性与多版本包名检测）
- **物理安全数据挂载**：使用非跟随符号链接遍历与断开逻辑，从底层物理杜绝触碰或误删外部存储中 16GB 的 `list_CN_Android` 游戏资产
- **高并发极速卡片同步**：卡片数据全量预载内存哈希映射并采用批量写入，初始化从 30 秒暴降至 0.1 秒内，彻底解决并发死锁与数据库关闭异常
- **即时状态查询与重载**：直接通过 JNI 查询运行状态、健康信息，并在修改配置后即时热重载
- **数据备份与还原**：支持导出 / 导入用户游戏进度数据（`data.db`）
- **后台稳定保活**：前台服务通知 + `PARTIAL_WAKE_LOCK` 防止切后台被系统休眠清理
- **无感运行时解包**：基于资源哈希追踪，首次启动或资源更新时智能解压运行时模板，自动保留用户已有数据与设置
- **免侵入电池优化提示**：未加入电池优化白名单时温和弹窗提醒，不强制跳转设置打扰用户

## 目录约定

- Go 动态库放到：`android/app/src/main/jniLibs/<abi>/libhonokachan.so`
- 运行时资源包放到：`android/app/src/main/assets/honoka_runtime.zip`

目前工程里已经预建了：

- `android/app/src/main/jniLibs/arm64-v8a/`
- `android/app/src/main/jniLibs/armeabi-v7a/`
- `android/app/src/main/jniLibs/x86_64/`
- `android/app/src/main/assets/`

## 1. 开发环境要求

Linux 下建议直接使用 `android/scripts/*.sh` 先准备运行时资源与 JNI 动态库，最终 APK 由 Android Studio 构建。

需要准备：

- `Go`
- `zip`
- Android SDK
- Android NDK
- Android Studio

常用环境变量：

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk
```

默认 `MIN_SDK=26`，和当前 Android App 的 `minSdk` 保持一致。如果你确实需要改动原生层最低版本，可以在执行脚本前自行覆盖：

```bash
export MIN_SDK=26
```

建议先跑环境检查：

```bash
./android/scripts/check_env.sh
```

说明：

- 不需要预先在仓库根目录准备 `config.json`，`prepare_runtime_zip.sh` 会自动生成默认配置并打包进去。

## 2. 一键准备运行时资源

如果环境已经准备好，直接执行：

```bash
./android/scripts/build_all.sh
```

它会依次完成：

1. 打包 `honoka_runtime.zip`
2. 编译 `arm64-v8a` 的 `libhonokachan.so`
3. 编译 `armeabi-v7a` 的 `libhonokachan.so`
4. 编译 `x86_64` 的 `libhonokachan.so`

完成后再用 Android Studio 打开 `android/` 并构建 APK。

## 3. 单步脚本

### 3.1 打包运行时资源

```bash
./android/scripts/prepare_runtime_zip.sh
```

### 3.2 编译 Go 动态库

需要使用 Android NDK 的 clang 作为 `CC`，并启用 `c-shared`。

脚本方式：

```bash
./android/scripts/build_go_android.sh arm64-v8a
./android/scripts/build_go_android.sh armeabi-v7a
./android/scripts/build_go_android.sh x86_64
```

手工方式，`arm64-v8a` 示例：

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang"
CGO_ENABLED=1 GOOS=android GOARCH=arm64 \
  go build -buildmode=c-shared \
  -o android/app/src/main/jniLibs/arm64-v8a/libhonokachan.so \
  ./cmd/honoka-android
```

手工方式，`armeabi-v7a` 示例：

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi26-clang"
CGO_ENABLED=1 GOOS=android GOARCH=arm GOARM=7 \
  go build -buildmode=c-shared \
  -o android/app/src/main/jniLibs/armeabi-v7a/libhonokachan.so \
  ./cmd/honoka-android
```

手工方式，`x86_64` 示例：

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
export CC="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android26-clang"
CGO_ENABLED=1 GOOS=android GOARCH=amd64 \
  go build -buildmode=c-shared \
  -o android/app/src/main/jniLibs/x86_64/libhonokachan.so \
  ./cmd/honoka-android
```

说明：

- 这里直接使用 `./cmd/honoka-android`，不需要额外 JNI 头文件。
- Android App 自带的 `honoka_android.cpp` 会在运行时通过 `dlopen("libhonokachan.so")` 找到导出的 `ServerStart` / `ServerStop` / `ServerStatusJSON` 等符号。
- `go build -buildmode=c-shared` 会顺带生成 `libhonokachan.h`，脚本会自动删除这个头文件，因为当前 Android 工程并不会使用它。

## 4. 打包运行时资源

`honoka-chan` 在 Android 上仍然按仓库根目录结构读取文件，所以 zip 包内必须直接包含这些顶层条目：

- `assets/`
- `static/`
- `config.json`

最小建议内容：

- `assets/main.db`
- `assets/serverdata/`
- `assets/certs/`
- `static/templates/`
- `static/css/`
- `static/js/`
- `static/images/`
- `static/font/`
- `config.json`

如果你希望把已有用户数据也一起带进去，可以额外放入：

- `assets/data.db`

`prepare_runtime_zip.sh` 会自动生成一份默认配置，内容等同于服务端 `config.DefaultConfigs()`：

- `app_name`: `honoka-chan`
- `settings.listen_port`: `8080`
- `settings.cdn_server`: `http://127.0.0.1:8080/static`
- `settings.reload_token`: `""`
- `settings.unlock_all_special_rotation`: `false`

手工打包命令：

```bash
zip -r android/app/src/main/assets/honoka_runtime.zip assets static config.json
```

注意：

- 压缩包根目录下必须直接看到 `assets`、`static`、`config.json`，不要多包一层目录。
- App 会记录 `honoka_runtime.zip` 的内容哈希；如果资源包发生变化，下次启动服务或刷新挂载时会自动重新解压。
- 自动重新解压时会保留运行时目录里现有的 `config.json`，避免覆盖用户已经修改过的服务设置。
- 自动重新解压时也会保留运行时目录里现有的 `assets/data.db` 及其 `-wal/-shm`，避免覆盖用户数据。

## 5. 构建 APK

当前不再提供命令行脚本构建 APK，直接使用 Android Studio 打开 `android/` 构建即可。

如果你主要用 Android Studio 模拟器测试，建议保留 `x86_64` ABI；很多模拟器镜像不会加载仅包含 ARM so 的 APK。

## 6. 数据目录

App 会把你选择的目录挂到运行时：

- `/static/Android`

实现方式不是 Linux bind mount，而是：

- 在应用私有运行时目录中创建 `static/Android -> 你的目录` 的符号链接

默认数据目录：

- `/sdcard/Download/data`

如果 Android 版本较高，点击“选择目录”时会先跳转系统设置申请“所有文件访问权限”，授权后自动继续打开目录选择器。

## 7. 备份导入导出

- 备份导出和导入的对象都是运行时目录里的 `assets/data.db`
- 执行前需要先停止服务，避免数据库仍在使用中
- 导出前 App 会先执行一次 `wal_checkpoint(TRUNCATE)`，这样单独导出的 `data.db` 就是完整的
- 导入备份时会提示“将覆盖当前用户数据，是否继续”

## 8. 运行流程

1. 准备好 `libhonokachan.so`
2. 准备好 `honoka_runtime.zip`
3. 用 Android Studio 构建并安装 APK
4. 打开 App
5. 首次进入时按系统提示授予通知权限
6. 根据需要处理“所有文件访问权限”与数据目录选择
7. 如有需要，打开“解锁全部日替”开关
8. 点击“启动服务”
9. 用“刷新状态”确认当前进程健康状态正常

说明：

- 如果系统尚未将本应用加入电池优化白名单，App 只会弹出说明提示，不会自动跳转设置页。
- 即使没有加入白名单，服务仍然可以启动。
- 在部分国产系统上，如果退到后台后服务仍会断开，除了忽略电池优化、自启动和后台运行，还可能需要在最近任务中手动锁定本应用。

## 9. 其他说明

- 这个 Android 子目录默认只提交源码与脚本；`gradlew`、`gradle/`、构建产物、`jniLibs/`、`assets/` 等本地生成内容当前都被 `android/.gitignore` 排除了。
- 如果你希望把 Gradle wrapper 一起纳入仓库，可以自行调整 `android/.gitignore`。
- 前台服务会持有 `PARTIAL_WAKE_LOCK`，停止服务时会释放。
- App 内部的状态、健康检查、配置重载都直接通过 JNI 调用 Go 导出符号。
- `/system/health` 和 `/system/reload` 仍然保留，方便外部调试、脚本探活和浏览器访问。
- Android Studio 升级到 AGP `8.11.2` 后，如果项目放在 Samba 映射盘上，可能会出现 `Cannot create directory .../merged.dir/values` 之类的 AAPT 目录创建失败。这个更像文件系统兼容问题，建议改回本地磁盘构建。

## 10. Reload 与资源更新说明

- App 内部修改服务设置时会直接在进程内执行 reload
- `/system/reload` HTTP 接口只用于外部调用，仍然保留 token 校验
- 无论通过 JNI 还是 HTTP，reload 都只会重新加载运行时目录中的 `config.json`
- 它不会重新解压 `honoka_runtime.zip`
- 如果你更新了数据库、证书、serverdata、模板等资源，需要重新生成 `honoka_runtime.zip` 并重新安装或覆盖资源包
