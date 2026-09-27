# 开发

需要 JDK 25、Rust stable，Windows 还需 MSVC C++ 工具链。依赖版本在 `gradle/libs.versions.toml`。

```powershell
./gradlew.bat :desktopApp:run
./scripts/check.ps1
./gradlew.bat :desktopApp:packageMsi
```

MSI 在 `desktopApp/build/release/<版本>/main/msi/`。`:desktopApp:createDistributable` 可生成免安装目录，使用时需保留整个目录。

## 目录

| 位置 | 内容 |
| --- | --- |
| `engine/` | Rust 笔刷、像素、图层、历史和文件格式 |
| `shared/` | 共用界面，按 domain、data、presentation、ui 分层 |
| `desktopApp/` | 桌面入口、本地引擎加载、文件和更新服务 |
| `androidApp/`、`iosApp/` | 移动端入口 |
| `scripts/`、`release/` | 检查、打包和发布配置 |

工具默认值在 `StudioDefaults`，视觉参数在 `StudioTheme`，引擎上限在 `engine/src/model.rs`。

## 引擎

像素按 128 × 128 分块，按需分配。输入批量送入 Rust，界面只接收脏块。笔刷与合成在 CPU 执行，文件读写、压缩和光栅化放在后台线程。

笔触沿弧长插值，压力控制半径，距离场控制笔尖形状，坐标哈希生成颗粒。填充使用四连通扫描线，模糊使用三次可分离盒式滤波。历史通过 `Arc` 共享未修改的块，修改时复制。

当前为 8 位预乘 RGBA，最多 32 层。单边不超过 8192，总像素不超过 16,777,216；文档像素预算 128 MiB，历史额外预算 64 MiB，最多 60 步。这些预算不包含显示、导出和运行时占用。

工程保留图层，普通图片不保留图层。PNG、WebP 支持透明，WebP 导出为无损编码。JPEG 叠加白底，可调整质量。导入会处理 EXIF 方向，不保留元数据，不转换 ICC 配置。

混合公式参考 [W3C Compositing and Blending](https://www.w3.org/TR/compositing-1/)，模糊窗口参考 [Fast Almost-Gaussian Filtering](https://www.peterkovesi.com/papers/FastGaussianSmoothing.pdf)。

## 验证

`scripts/check.ps1` 运行 Rust 格式、Clippy、引擎测试和 Kotlin 测试。桌面测试包含 JNI 调用、文件恢复、更新下载、画布裁切、分块接缝和动效收尾。渲染截图在 `desktopApp/build/reports/screenshots/`。

```powershell
cargo bench --bench painting
cargo bench --bench editing
cargo bench --bench blending --bench previews --bench imports
```

基准测量引擎负载，不包含设备输入、GPU 上传和屏幕延迟。

## 其他平台

Android 需要 SDK 37、NDK、`cargo-ndk`，配置 `ANDROID_HOME` 和 `ANDROID_NDK_HOME` 后运行 `scripts/build-android.ps1`。

iOS 需要 Apple Silicon Mac、Xcode、Rust 和 XcodeGen。运行 `bash scripts/build-ios.sh`，再打开 `iosApp/podor.xcodeproj`。移动端和 macOS、Linux 桌面尚未完成设备验收。

## 发布

修改版本号，新增 `release/notes-<版本>.md`，提交后用 PowerShell 7 运行：

```powershell
./scripts/publish.ps1
```

脚本检查、打包并同步 GitHub 和 Gitee，匿名下载校验安装包与清单后发布稳定版。构建已完成时可用 `-SkipBuild` 重试，已有附件不会被覆盖。

仓库地址在 `release/publishing.json`，应用更新入口在 `release/channel.properties`。应用从 Gitee 发行接口读取清单，下载完成后校验大小和 SHA-256，再保留 MSI 文件。

本机通过 `scripts/configure-publishing.ps1` 设置 `GITEE_TOKEN`，GitHub 使用 Git 凭据或 `GITHUB_TOKEN`。凭据只保存在本机。
