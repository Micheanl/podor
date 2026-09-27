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

稳笔按路径弧长做指数平滑，抬笔补齐到终点，取消笔画则回滚。强度越高，笔迹越平稳，也会更落后于笔尖；不改变压力值，不保留额外采样队列。

当前为 8 位预乘 RGBA，最多 32 层。单边不超过 8192，总像素不超过 16,777,216；文档像素预算 128 MiB，历史额外预算 64 MiB，最多 60 步。这些预算不包含显示、导出和运行时占用。

复制图层共享像素块，修改时才复制。合并可见图层保持当前合成结果，保留隐藏层，并记录一次撤销；超过撤销预算时不执行合并。作品列表只记录手动打开、保存的文件和缩略图，不保存画布草稿。退出或切换作品时处理未保存改动，撤销回保存位置后恢复为已保存状态。

工程保留图层，普通图片不保留图层。PNG、WebP 支持透明，WebP 导出为无损编码。JPEG 叠加白底，可调整质量。导入会处理 EXIF 方向，不保留元数据，不转换 ICC 配置。

[OpenRaster](https://www.openraster.org/baseline/layer-stack-spec.html)（`.ora`）导出保留图层顺序、名称、可见性、不透明度和混合模式，保留原始透明度，不添加白底。各层裁到有数据的块范围，PNG 按行编码，合成预览只缓存一排画布块。暂不支持 ORA 导入。

已用 GIMP 3.2.6 验证图层读取。GIMP 默认合成设置可能改变显示效果；将图层混合空间、合成空间设为 `RGB (from color profile)`，合成模式设为 `Union` 后，八种模式样本与 podor 的通道差值不超过 2/255，设置说明见 [GIMP 手册](https://docs.gimp.org/3.2/en/gimp-layer-new.html)。文件内的合成预览保持 podor 原貌。

混合公式参考 [W3C Compositing and Blending](https://www.w3.org/TR/compositing-1/)，模糊窗口参考 [Fast Almost-Gaussian Filtering](https://www.peterkovesi.com/papers/FastGaussianSmoothing.pdf)。

## 验证

`scripts/check.ps1` 运行 Rust 格式、Clippy、引擎测试和 Kotlin 测试。桌面测试包含 JNI 调用、手动保存、作品列表、更新下载与安装交接、画布裁切、分块接缝和动效收尾。渲染截图在 `desktopApp/build/reports/screenshots/`。

```powershell
cargo bench --bench painting
cargo bench --bench editing
cargo bench --bench blending --bench previews --bench imports
cargo bench --bench exports
cargo bench --bench layers
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

安装前再次校验文件，后台安装助手就绪后应用才退出。助手等待旧进程结束，再运行带 `/norestart REBOOT=ReallySuppress` 的安装程序。MSI 构建最后执行 `scripts/configure-msi.ps1`，检查桌面快捷方式、禁止自动重启，添加完成后的启动选项。用户取消安装时不会重启应用；遇到文件占用仍需手动处理，不自动重启 Windows。

本机通过 `scripts/configure-publishing.ps1` 设置 `GITEE_TOKEN`，GitHub 使用 Git 凭据或 `GITHUB_TOKEN`。凭据只保存在本机。
