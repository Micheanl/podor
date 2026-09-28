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

画布旋转和镜像由 Compose 变换图块，`Viewport` 统一正反坐标换算，旋转后的可见范围由视口四角反算，保留图块裁剪。Windows 已检查 Direct3D 下 4096 × 4096 画布连续切换视图时复用原像素帧。

Windows 指针消息在接收线程读取，再通过 AWT 交给 Compose 命中检测。笔和触摸共用输入队列，排队上限 64 批，每批最多 256 点，每轮最多派发 8 批；溢出或系统取消会撤回当前笔画。未添加轮询定时器，光栅化继续在后台执行。

触屏单指遵循“手指绘画”开关，双指平移、缩放和旋转只改视图。第二根手指落下时撤回未完成笔画，手势结束前剩余手指不会继续画。相邻手势帧合并为最新位置，保留手指增减的边界，最多跟踪 32 个触点。笔进入感应范围后取消触摸并抑制新触摸。处理依据为 [Windows 指针消息](https://learn.microsoft.com/en-us/windows/win32/inputmsg/wm-pointerdown)，压感、手势和按钮命中通过系统注入测试，实体触屏与数位板仍需验收。

作品画面和光标、选区分别录制到显示层，移动光标不重新录制作品图块，视口外的图块不提交绘制。选中颜色在绘制阶段读取动画值，启动流光和溶解结束后卸载。界面由 Compose / Skia 合成，Rust 像素算法仍在 CPU 后台执行。

## 引擎

像素按 128 × 128 分块，按需分配。输入批量送入 Rust，界面只接收脏块。笔刷与合成在 CPU 执行，文件读写、压缩和光栅化放在后台线程。同一笔内缓存当前层下方的合成图块，最多 4 MiB，结束或取消笔画后释放；当前层及上方图层仍按原顺序合成。

笔触沿弧长插值，压力控制半径，距离场控制笔尖形状，坐标哈希生成颗粒。填充使用四连通扫描线，模糊使用三次可分离盒式滤波。历史通过 `Arc` 共享未修改的块，修改时复制。

按 M 使用选区，底部切换矩形、椭圆和套索。Shift 约束正方形或圆形，Esc 放弃当前圈选，Ctrl+D 取消选区。套索松手闭合，路径最多 4096 点，长路径逐步降采样。后台按扫描线生成 8 位覆盖率，纵向八次采样、横向计算像素覆盖面积，自交轮廓使用奇偶规则；遮罩最多 16 MiB，画笔直接读取缓存，填充不会跨过选区外区域，滤镜仅混合选中部分。选区不写入工程，也不计入作品的撤销历史。

稳笔按路径弧长做指数平滑，抬笔补齐到终点，取消笔画则回滚。强度越高，笔迹越平稳，也会更落后于笔尖；不改变压力值，不保留额外采样队列。

当前为 8 位预乘 RGBA，最多 32 层。单边不超过 8192，总像素不超过 16,777,216；文档像素预算 128 MiB，历史额外预算 64 MiB，最多 60 步。这些预算不包含显示、导出和运行时占用。

复制图层共享像素块，修改时才复制。合并可见图层保持当前合成结果，保留隐藏层，并记录一次撤销；超过撤销预算时不执行合并。作品列表只记录手动打开、保存的文件和缩略图，不保存画布草稿。退出或切换作品时处理未保存改动，撤销回保存位置后恢复为已保存状态。

Windows 图片剪贴板提供 PNG 和系统图片格式，保留透明像素。Ctrl+C 复制当前层原始像素，Ctrl+Shift+C 复制可见层合成，按选区覆盖率裁出透明边缘；无选区时复制整个画布范围。Ctrl+X 先写剪贴板再剪切，Ctrl+V 在当前层上方粘贴新层，两者各记录一次撤销。同一应用内、同尺寸画布保留复制位置，其他图片原尺寸居中，画布外裁切。PNG 按行编码，合成最多缓存一排图块，处理放在后台。文字输入框优先使用文字剪贴板。

拖动图层缩略图调整层序，靠近列表上下边缘会自动滚动，拖出列表取消。拖动时复用原画布和缩略图，松手后才提交一次排序，Rust 共享像素块并标记跨越图层的脏块。文档发生修改或加入第二个触点时取消拖动，排序可一步撤销。

“调整”面板中的画布大小可扩边或裁切，九宫格决定原画面位置，不缩放笔迹。新区域透明，所有图层一起处理，保留图层属性；超出新边界的像素会移除，可一次撤销。预览复用合成缩略图，只动画显示边界。确认后在后台按行搬移像素，完整且对齐的图块直接共享，超出文档或撤销预算时不执行。尺寸改变时清理显示缓存并适合窗口，避免裁切后遗留图块。

按 V 移动当前图层，Esc 取消。预览按图层复用图像，拖动时不向引擎传输像素；松手后在后台按行复制，完整图块对齐时共享原像素，记录一次撤销。仅支持整层整数像素移动，需先取消选区、解除图层和透明度锁定。超出画布的部分会裁切，可撤销恢复。4096 × 4096 双图层预览已在 Direct3D 下检查，尚未测量整机帧率。

工程保留图层，普通图片不保留图层。PNG、WebP 支持透明，WebP 导出为无损编码。JPEG 叠加白底，可调整质量。导入会处理 EXIF 方向，不保留元数据，不转换 ICC 配置。

PSD 目前只导出 8 位 RGB 图层，保留层序、Unicode 名称、可见性、不透明度和八种混合模式，写入锁定标记与合成预览。按 [Adobe PSD 规范](https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/) 逐行 PackBits 编码，合成只缓存一排像素图块和压缩后的通道。GIMP 3.2.6 已检查十个样本；八种模式的预览逐像素一致，重算图层的通道误差不超过 2/255。GIMP 不恢复完整图层锁定；Photoshop 本机兼容性尚未验证。

图层面板可将 PNG、JPEG、静态 WebP 插入当前层上方。图片居中，小图保持原尺寸，大图等比缩小到画布内；选中后可直接移动，原作品路径不变，仍需手动保存。缩小采用预乘 RGBA 的面积加权采样，按行读写稀疏图块，缩放只使用行像素缓冲。无需缩放且偏移对齐图块时直接使用解码后的图块。导入记录一次撤销，超出文档或撤销预算则保留原作品。

锁定透明度保留原像素的 Alpha，画笔、填充和滤镜只改颜色，橡皮和清空需先解除锁定。图层锁定阻止内容编辑、删除和合并，仍可更名、调整显示属性和层序。锁定状态写入工程格式 v2，旧 v1 工程可读取，新保存的文件需要 podor 0.2.13 或更新版本。

[OpenRaster](https://www.openraster.org/baseline/layer-stack-spec.html)（`.ora`）导入导出保留平面图层的顺序、名称、可见性、不透明度和当前支持的八种混合模式。导出保留原始透明度，不添加白底；各层裁到有数据的块范围，PNG 按行编码，合成预览只缓存一排画布块。

导入在后台解码，按图层偏移写入画布内的稀疏图块，画布外像素会裁切。普通 PNG 按行读取，交错 PNG 使用受尺寸限制的缓冲区，16 位通道转换为 8 位；图层组和其他混合模式会明确报错。文件目录、XML 和像素分别限制大小，解析失败保留当前作品。手动保存默认另存为 `.podor`，不覆盖来源 ORA。测试包含 GIMP 导出的多图层、交错 PNG、越界偏移和损坏文件。

已用 GIMP 3.2.6 验证图层读取。GIMP 默认合成设置可能改变显示效果；将图层混合空间、合成空间设为 `RGB (from color profile)`，合成模式设为 `Union` 后，八种模式样本与 podor 的通道差值不超过 2/255，设置说明见 [GIMP 手册](https://docs.gimp.org/3.2/en/gimp-layer-new.html)。文件内的合成预览保持 podor 原貌。

混合公式参考 [W3C Compositing and Blending](https://www.w3.org/TR/compositing-1/)，模糊窗口参考 [Fast Almost-Gaussian Filtering](https://www.peterkovesi.com/papers/FastGaussianSmoothing.pdf)。

## 验证

`scripts/check.ps1` 运行 Rust 格式、Clippy、引擎测试和 Kotlin 测试。桌面测试包含 JNI 调用、手动保存、作品列表、更新下载与安装交接、画布裁切、分块接缝和动效收尾。渲染截图在 `desktopApp/build/reports/screenshots/`。

设置 `PODOR_CLIPBOARD_SYSTEM_TEST=1` 可运行 Windows 原生剪贴板互通测试，子进程在独立 window station 中验证 PNG 双向读写，不访问用户剪贴板。`PODOR_GPU_TEST=1` 包含 4096 × 4096 画布复制、剪切、粘贴时的主线程响应检查，结果在 `desktopApp/build/reports/clipboard-performance.txt`。

```powershell
cargo bench --bench painting
cargo bench --bench editing
cargo bench --bench blending --bench previews --bench imports
cargo bench --bench exports
cargo bench --bench layers
cargo bench --bench ora_import
cargo bench --bench canvas_size
cargo bench --bench canvas_frames
cargo bench --bench image_size
```

基准测量引擎负载，不包含设备输入、GPU 上传和屏幕延迟。

Windows 可设置 `PODOR_GPU_TEST=1` 后运行 `scripts/check.ps1`，额外检查原生窗口的硬件渲染通道，结果写入 `desktopApp/build/reports/gpu-renderer.txt`。离屏动画耗时包含图像读回，不能作为整机帧率承诺。

`painting-performance.txt` 记录完整桌面界面中八图层连续绘画的输入批次到像素帧耗时、强制渲染调用耗时和主线程排队时间，超时采样附带线程栈。测试窗口位于屏幕外，结果不代表实际显示帧率或实体笔延迟。

`image-resize-performance.txt` 记录大图重采样、画布刷新和主线程排队时间。平滑缩放使用预乘颜色的 Lanczos3 滤波，按行复用最多 4 MiB 缓存；像素模式使用最近邻。两种方式都保留图层，并在内存上限内支持一步撤销。

交互式 Windows 会话可另设 `PODOR_INK_SYSTEM_TEST=1`，测试通过系统指针注入检查压感、笔尾擦除和重复鼠标事件。注入前逐点确认命中测试窗口，结束后关闭窗口。实体数位板、触控笔仍需设备验证；双指手势目前只有 Compose 输入模拟测试。

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

最新发行若是预发布或缺少更新附件，应用按降序查询最近的稳定发行，最多检查 5 页，每页 20 条。清单限 64 KiB，发行列表每页限 512 KiB，网络失败仍显示错误。发版失败时撤下本次新建且未完成的 Gitee 发行记录，避免遮住稳定更新入口。

安装前再次校验文件，后台安装助手就绪后应用才退出。助手等待旧进程结束，再运行带 `/norestart REBOOT=ReallySuppress` 的安装程序。MSI 构建最后执行 `scripts/configure-msi.ps1`，检查桌面快捷方式、禁止自动重启，添加完成后的启动选项。用户取消安装时不会重启应用；遇到文件占用仍需手动处理，不自动重启 Windows。

本机通过 `scripts/configure-publishing.ps1` 设置 `GITEE_TOKEN`，GitHub 使用 Git 凭据或 `GITHUB_TOKEN`。凭据只保存在本机。
