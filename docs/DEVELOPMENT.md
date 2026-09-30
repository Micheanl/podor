# 开发

需要 JDK 25 或更新版本与 Rust，Windows 还需 MSVC C++ 工具链。CI 使用 JDK 27 和 Rust 1.98.1，依赖版本在 `gradle/libs.versions.toml`。

```powershell
./gradlew.bat :desktopApp:run
./scripts/check.ps1
./gradlew.bat :desktopApp:packageMsi
```

MSI 在 `desktopApp/build/release/<版本>/main/msi/`。`:desktopApp:createDistributable` 可生成免安装目录，使用时需保留整个目录。

## 目录

| 位置 | 内容 |
| --- | --- |
| `engine/` | Rust 笔刷，像素，图层，历史和文件格式 |
| `shared/` | 共用界面，按 domain，data，presentation，ui 分层 |
| `desktopApp/` | 桌面入口，本地引擎加载，文件和更新服务 |
| `androidApp/`，`iosApp/` | 移动端入口 |
| `scripts/`，`release/` | 检查，打包和发布配置 |

工具默认值在 `StudioDefaults`，视觉参数在 `StudioTheme`，引擎上限在 `engine/src/model.rs`。

调整侧栏中的画布大小，图像尺寸，色彩，模糊，曲线和渐变映射直接展开编辑，填充设置位于底部；预览确认仍在画布下方的悬浮胶囊。切换调整，工具或开始绘画时取消未确认的调整预览，撤销和重做同样先退出预览再执行历史操作；过期的后台结果不会重新显示。图层蒙版保留方形缩略图，设置按钮打开独立窗口。进入动画面板时初始化时间轴，初始化可撤销；动画帧线图停靠面板底部，标尺支持横向拖动。

图层变换用 Ctrl+T，拖控制点缩放，顶部圆点旋转，方向键微调，Shift 加快移动或将旋转吸附到 15°。Enter 确认，Esc 取消；未确认的变换不会写入工程。预览复用图块，确认后 Rust 在后台重采样，缩放使用 Lanczos3，旋转使用预乘 RGBA 双线性采样；像素模式使用最近邻。只变换当前整层，选区需先取消，画布外内容在确认时裁切。

渐变用 Shift+G，拖动两端调整方向和范围，Shift 吸附到 15°。画布下方的悬浮胶囊切换线性，径向，反转和透明色，点击色块在调色盘中编辑。Enter 确认，Esc 取消，可一步撤销。预览复用图层与选区遮罩，确认后后台按 sRGB 通道插值，预乘 Alpha 合成，只更新改变的图块，遵循选区和透明度锁定。

调整面板可预览明暗，对比度，饱和度和高斯模糊，交互参考 [GIMP 色彩调整](https://docs.gimp.org/3.0/en/gimp-tool-brightness-contrast.html)。悬浮胶囊提供原图对比，重置，取消和确认，Enter 应用，Esc 取消；预览时可平移，缩放画布。Rust 从原图共享未修改的图块，后台计算选区内效果并合成变化区域，不改动作品或历史。快速拖动合并为最新参数，持续拖动时仍显示已完成的预览；取消后返回的结果会丢弃。确认前核对图层与版本，应用记一次撤销，结果与预览一致。`adjustment-performance.txt` 记录大画布，混合图层下的更新和主线程响应，不代表实际显示帧率。

曲线提供 RGB 总曲线及独立通道，直方图，拖点和 0–255 数值输入，交互参考 [GIMP 曲线](https://docs.gimp.org/3.0/en/gimp-tool-curves.html)。每通道最多 16 个点，使用 [PCHIP 分段三次插值](https://docs.scipy.org/doc/scipy/reference/generated/scipy.interpolate.PchipInterpolator.html)生成查找表，保留控制点，不越过相邻极值。先应用总曲线，再应用通道曲线；保留 Alpha，按选区覆盖率混合。直方图在进入调整时后台计算，按透明度和选区覆盖率加权，拖点时复用。

画布旋转和镜像由 Compose 变换图块，`Viewport` 统一正反坐标换算，旋转后的可见范围由视口四角反算，保留图块裁剪。Windows 已检查 Direct3D 下 4096 × 4096 画布连续切换视图时复用原像素帧。

Windows 指针消息在接收线程读取，再通过 AWT 交给 Compose 命中检测。笔和触摸共用输入队列，排队上限 64 批，每批最多 256 点，每轮最多派发 8 批；溢出或系统取消会撤回当前笔画。未添加轮询定时器，光栅化继续在后台执行。

触屏单指遵循“手指绘画”开关，双指平移，缩放和旋转只改视图。第二根手指落下时撤回未完成笔画，手势结束前剩余手指不会继续画。相邻手势帧合并为最新位置，保留手指增减的边界，最多跟踪 32 个触点。笔进入感应范围后取消触摸并抑制新触摸。处理依据为 [Windows 指针消息](https://learn.microsoft.com/en-us/windows/win32/inputmsg/wm-pointerdown)，压感，手势和按钮命中通过系统注入测试，实体触屏与数位板仍需验收。

作品画面和光标，选区分别录制到显示层，移动光标不重新录制作品图块，视口外的图块不提交绘制。选中颜色在绘制阶段读取动画值。界面由 Compose / Skia 合成，Rust 像素算法仍在 CPU 后台执行。

启动只等待后台读取偏好和初始化引擎，随后按启动页偏好直接显示首页或画布。首帧使用已保存的主题；没有启动遮罩，动画或等待定时器。页面切换和工具交互动效仍遵循减少动态效果设置。

## 引擎

内置库包含 24 个笔刷预设，包括像素铅笔与像素完美笔刷。笔刷参数和 JSON 扩展格式见[笔刷插件](PLUGINS.md)。工具栏中的柳叶笔是闭合轮廓填色工具，支持填色与擦除；笔刷库中的柳条笔则使用随笔画方向旋转的叶形笔尖。两者共用当前颜色，但通过不同的引擎操作编辑像素。

色卡提取按图块合成可见图层，忽略透明像素，用 5 位 RGB 直方图与按透明度加权的中位切分选出最多 12 色，保存到最多 256 色的本机色卡中。复用图层混合算法，直方图约 1 MiB，随后只处理非空色桶，不复制整幅图像；提取由后台任务执行，不改动作品。交互参考 [Procreate 色卡](https://help.procreate.com/procreate/handbook/colors/colors-palettes)。桌面响应测试结果在 `palette-performance.txt`，不代表实体设备帧率。

笔刷库的搜索和收藏参考 [Krita 笔刷管理](https://docs.krita.org/en/reference_manual/resource_management/paintoppresets.html)。按名称，来源和收藏筛选，结果记忆化，网格只绘制可见卡片，搜索栏收放结束后停止动画。收藏使用稳定笔刷 ID，插件停用不删除星标，删除和替换资源时清理失效引用。自定义笔刷原位保存保留 ID，参数与收藏在后台写入本机设置，不触发工程保存。`brush-library-performance.txt` 记录 1104 支笔刷下的筛选，滚动，强制绘制与常规渲染时的主线程响应，不代表实际显示帧率。

像素按 128 × 128 分块，按需分配。输入批量送入 Rust，界面只接收脏块。笔刷与合成在 CPU 执行，文件读写，压缩和光栅化放在后台线程。同一笔内缓存当前层下方的合成图块，最多 4 MiB，结束或取消笔画后释放；当前层及上方图层仍按原顺序合成。

笔触沿弧长插值，压力控制半径，距离场控制笔尖形状，坐标哈希生成颗粒。填充使用四连通扫描线，模糊使用三次可分离盒式滤波。历史通过 `Arc` 共享未修改的块，修改时复制。

按 S 涂抹当前图层，强度独立于画笔不透明度，支持现有笔尖，颗粒，压感和稳笔。局部双缓冲随笔尖移动，按双线性采样搬运预乘 RGBA，只从画布与选区内取色；缓存上限 2 MiB。透明度锁定时只混色，取消笔画丢弃缓存并恢复原像素，完成一笔记一次撤销。算法与图块更新在后台执行，未提供合并图层取色。

按 M 使用选区，画布下方的悬浮工具条切换矩形，椭圆和套索；快捷操作保留在胶囊工具条，侧栏仅显示工具的详细参数，避免重复入口。Shift 约束正方形或圆形，Esc 放弃当前圈选，Ctrl+D 取消选区。套索松手闭合，路径最多 4096 点，长路径逐步降采样。后台按扫描线生成 8 位覆盖率，纵向八次采样，横向计算像素覆盖面积，自交轮廓使用奇偶规则；遮罩最多 16 MiB，画笔直接读取缓存，填充不会跨过选区外区域，滤镜仅混合选中部分。选区不写入工程，也不计入作品的撤销历史。

工具选项中的重叠方框菜单切换新建，添加，相减和相交，交互参考 [GIMP 选区模式](https://docs.gimp.org/3.0/en/gimp-using-selections-add.html)。覆盖率分别取最大值，饱和相减和最小值，Ctrl+Shift+I 反选。空选区保留为零覆盖，画笔，填充和滤镜不会误作用到整幅画布。组合轮廓按选区编号缓存；边线过密或过长时显示选中区域的淡色遮罩，使用 512 像素块的单通道 Alpha 纹理，保留原始覆盖率。轮廓和纹理在后台生成，悬停不重新计算，旋转和镜像复用缓存。`selection-performance.txt` 记录 4096 × 4096 复杂选区的计算，强制渲染与主线程排队时间。

按 W 使用魔棒，点击松手后按容差选择相近颜色，可在工具选项中设置连续区域，全图和可见图层取样，交互参考 [GIMP 魔棒](https://docs.gimp.org/3.0/en/gimp-tool-fuzzy-select.html)。在去预乘的 RGBA 通道上比较最大差值，透明像素统一为零；当前层直接读取像素块，可见层按原混合顺序合成，不加入显示白底。后台先生成单通道匹配表，再按四连通扫描线扩展，复用已有选区轮廓与组合操作。匹配表最多 16 MiB，扫描队列最多 4 MiB，超限保留原选区；选择操作不改变作品和撤销历史。`color-selection-performance.txt` 记录 4096 × 4096 五图层取样与主线程响应，不代表实际显示帧率。

稳笔按路径弧长做指数平滑，抬笔补齐到终点，取消笔画则回滚。强度越高，笔迹越平稳，也会更落后于笔尖；不改变压力值，不保留额外采样队列。

像素层使用 8 位预乘 RGBA 或索引色。像素与矢量层合计最多 32 个，包含图层组与调整层的节点总数最多 64 个，组嵌套最多 16 层。单边不超过 8192，总像素不超过 16,777,216；文档像素预算 128 MiB，历史额外预算 64 MiB，最多 60 步。这些预算不包含显示，导出和运行时占用。

图层组提供隔离与穿透合成；穿透组不支持自身不透明度，蒙版，剪贴或非普通混合模式。每层最多 16 个独立蒙版，可启停，链接，反相，复制与排序，编辑目标可切回像素层。明暗色彩，曲线与渐变映射可创建调整层，高斯模糊仍通过图像调整应用。

复制图层共享像素块，修改时才复制。合并可见图层保持当前合成结果，保留隐藏层，并记录一次撤销；超过撤销预算时不执行合并。作品列表只记录手动打开，保存的文件和缩略图，不保存画布草稿。退出或切换作品时处理未保存改动，撤销回保存位置后恢复为已保存状态。

Windows 图片剪贴板提供 PNG 和系统图片格式，保留透明像素。Ctrl+C 复制当前层原始像素，Ctrl+Shift+C 复制可见层合成，按选区覆盖率裁出透明边缘；无选区时复制整个画布范围。Ctrl+X 先写剪贴板再剪切，Ctrl+V 在当前层上方粘贴新层，两者各记录一次撤销。同一应用内，同尺寸画布保留复制位置，其他图片原尺寸居中，画布外裁切。PNG 按行编码，合成最多缓存一排图块，处理放在后台。文字输入框优先使用文字剪贴板。

参考图直接摆放在画布中，使用作品坐标，随画布缩放，旋转和镜像；最多保留 8 张，每张预览长边不超过 2048 像素。参考图不写入作品或导出，切换作品和退出时清除。文件读取和 Rust 解码，预乘 Alpha 重采样在后台执行，专用接口不持有画布引擎锁；缩放，平移和镜像复用同一张纹理。

拖动图层缩略图调整层序，靠近列表上下边缘会自动滚动，拖出列表取消。拖动时复用原画布和缩略图，松手后才提交一次排序，Rust 共享像素块并标记跨越图层的脏块。文档发生修改或加入第二个触点时取消拖动，排序可一步撤销。

“调整”面板中的画布大小可扩边或裁切，九宫格决定原画面位置，不缩放笔迹。新区域透明，所有图层一起处理，保留图层属性；超出新边界的像素会移除，可一次撤销。预览复用合成缩略图，只动画显示边界。确认后在后台按行搬移像素，完整且对齐的图块直接共享，超出文档或撤销预算时不执行。尺寸改变时清理显示缓存并适合窗口，避免裁切后遗留图块。

按 V 移动当前图层，Esc 取消。预览按图层复用图像，拖动时不向引擎传输像素；松手后在后台按行复制，完整图块对齐时共享原像素，记录一次撤销。仅支持整层整数像素移动，需先取消选区，解除图层和透明度锁定。超出画布的部分会裁切，可撤销恢复。4096 × 4096 双图层预览已在 Direct3D 下检查，尚未测量整机帧率。

工程保留图层，普通图片不保留图层。PNG，WebP 支持透明，WebP 导出为无损编码。JPEG 叠加白底，可调整质量。导入会处理 EXIF 方向，不保留元数据，不转换 ICC 配置。

画笔面板的镜像图标打开对称设置，提供左右，上下和双轴模式，交互参考 [Procreate 对称辅助线](https://help.procreate.com/procreate/handbook/guides/guides-symmetry)。轴线按半像素对齐，随画布旋转和镜像，参考线与镜像光标不写入像素。设置只用于当前作品，切换作品恢复关闭；目前适用于画笔，橡皮，涂抹不参与。Rust 在稳笔和间距采样后反射笔尖角度与颗粒坐标，同一落笔内的重叠覆盖取最大值，避免轴线处加深。最多处理四个笔尖，重复图块只访问一次，选区，锁定和内存预算照常生效，整笔共享一次撤销。`PODOR_GPU_TEST=1` 包含双轴绘画与普通绘画的响应对比，报告在 `desktopApp/build/reports/symmetry-performance.txt`。

PSD 支持 8 位 RGB 像素层与嵌套组导入导出，保留层序，Unicode 名称，可见性，不透明度，锁定标记，剪贴属性，单个普通像素蒙版和八种混合模式。按 [Adobe PSD 规范](https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/) 读取 Raw，PackBits，ZIP 和 ZIP 预测压缩；分层文件按行解码写入稀疏图块，限制累计解码量与实际像素内存。混合颜色带，矢量蒙版，蒙版密度与羽化，文字，智能对象和其他未实现属性会明确报错。导出可编辑调整层，矢量层或多个独立蒙版时，需要选择烘焙副本或改存 `.pod`。画布外像素会裁切，颜色按 sRGB 读取，手动保存另存为 `.pod`，不覆盖来源 PSD。无图层的 RGB 合成图也可打开，额外 Alpha 通道不会误当透明度。

PSD 导出逐行 PackBits 编码，合成只缓存一排像素图块和压缩后的通道。GIMP 3.2.6 已检查十个导出样本；八种模式的预览逐像素一致，重算图层的通道误差不超过 2/255。反向导入 GIMP 的三层样本，合成通道差值不超过 1/255。GIMP 不恢复完整图层锁定；Photoshop 本机兼容性尚未验证。

图层不透明度与混合模式的画布预览参考 [Procreate 混合模式](https://help.procreate.com/procreate/handbook/5.1/layers/layers-blend)。复用后台调整队列，快速输入只保留最新参数；预览共享原像素，仅重新合成当前层覆盖的图块。重置恢复进入时的属性，取消不改作品，确认一次撤销。空图层也可保存属性，内容锁定不限制显示属性；选区不影响整层混合。`adjustment-performance.txt` 包含六图层下的连续调参和主线程响应，预览耗时与实际显示帧率不同。

图层面板可将 PNG，JPEG，静态 WebP 插入当前层上方。图片居中，小图保持原尺寸，大图等比缩小到画布内；选中后可直接移动，原作品路径不变，仍需手动保存。缩小采用预乘 RGBA 的面积加权采样，按行读写稀疏图块，缩放只使用行像素缓冲。无需缩放且偏移对齐图块时直接使用解码后的图块。导入记录一次撤销，超出文档或撤销预算则保留原作品。

锁定透明度保留原像素的 Alpha，画笔，填充和滤镜只改颜色，橡皮和清空需先解除锁定。图层锁定阻止内容编辑，删除和合并，仍可更名，调整显示属性和层序。工程使用 `.pod` 后缀和 v12 存储格式，保留图层结构，蒙版，索引色，矢量对象，绘画助手和动画数据。旧版格式读取分支仍保留，当前文件应由同版本或更新版本打开。

[OpenRaster](https://www.openraster.org/baseline/layer-stack-spec.html)（`.ora`）导入导出保留像素层与嵌套组的顺序，名称，可见性，不透明度和当前支持的八种混合模式。导出保留原始透明度，不添加白底；各层裁到有数据的块范围，PNG 按行编码，合成预览只缓存一排画布块。

导入在后台解码，按图层偏移写入画布内的稀疏图块，画布外像素会裁切。普通 PNG 按行读取，交错 PNG 使用受尺寸限制的缓冲区，16 位通道转换为 8 位；不支持的混合模式，独立蒙版，剪贴属性与调整扩展会明确报错；可编辑调整层与矢量层导出需要选择烘焙副本。文件目录，XML 和像素分别限制大小，解析失败保留当前作品。手动保存默认另存为 `.pod`，不覆盖来源 ORA。测试包含 GIMP 导出的多图层，交错 PNG，越界偏移和损坏文件。

已用 GIMP 3.2.6 验证图层读取。GIMP 默认合成设置可能改变显示效果；将图层混合空间，合成空间设为 `RGB (from color profile)`，合成模式设为 `Union` 后，八种模式样本与 podor 的通道差值不超过 2/255，设置说明见 [GIMP 手册](https://docs.gimp.org/3.2/en/gimp-layer-new.html)。文件内的合成预览保持 podor 原貌。

混合公式参考 [W3C Compositing and Blending](https://www.w3.org/TR/compositing-1/)，模糊窗口参考 [Fast Almost-Gaussian Filtering](https://www.peterkovesi.com/papers/FastGaussianSmoothing.pdf)。

基础矢量工具提供矩形，椭圆，直线与路径，节点编辑先预览再确认。矢量图层仅用于 RGBA 工程，SVG 导出覆盖当前实现的子集。索引色工程最多 256 色，支持调色板编辑，像素绘画与索引 PNG 导出；柳叶笔等依赖 RGBA 的操作仍有限制，不能将索引模式视为全部功能的等价替代。

逐帧动画最多 256 帧，8192 个单元与 64 个标签。帧时长范围为 1–60000 ms，播放支持正向，反向与两种往返方向。图层轨道显示关键帧和共享单元，可解除共享后独立编辑。GIF 导出需要选择色数，透明度与时长策略，精确时长须为 10 ms 的倍数；PNG 图集 ZIP 保留帧图片和布局信息。输出受像素访问量，图集尺寸与内存预算限制。

Aseprite 工程交换保留当前实现支持的像素层，图层组，帧与单元等内容，导出前通过能力接口列出需要烘焙或阻止导出的属性。该接口明确声明 `fullFormatSupport = false`，不代表完整 Aseprite 格式兼容。`.pod` 仍是保留本项目全部结构的工程格式。

## 验证

`scripts/check.ps1` 运行 Rust 格式，Clippy，引擎测试和 Kotlin 测试。桌面测试包含 JNI 调用，手动保存，作品列表，更新下载与安装交接，画布裁切，分块接缝和动效收尾。渲染截图在 `desktopApp/build/reports/screenshots/`。

设置 `PODOR_CLIPBOARD_SYSTEM_TEST=1` 可运行 Windows 原生剪贴板互通测试，子进程在独立 window station 中验证 PNG 双向读写，不访问用户剪贴板。`PODOR_GPU_TEST=1` 包含 4096 × 4096 画布复制，剪切，粘贴时的主线程响应检查，结果在 `desktopApp/build/reports/clipboard-performance.txt`。

```powershell
cargo bench --bench painting
cargo bench --bench editing
cargo bench --bench blending --bench previews --bench imports
cargo bench --bench exports
cargo bench --bench layers
cargo bench --bench ora_import
cargo bench --bench psd_import
cargo bench --bench canvas_size
cargo bench --bench canvas_frames
cargo bench --bench image_size
```

基准测量引擎负载，不包含设备输入，GPU 上传和屏幕延迟。

Windows 可设置 `PODOR_GPU_TEST=1` 后运行 `scripts/check.ps1`，额外检查原生窗口及绘画响应。离屏渲染耗时包含图像读回，不能作为整机帧率承诺。

`painting-performance.txt` 记录完整桌面界面中八图层连续绘画的输入批次到像素帧耗时，强制渲染调用耗时和主线程排队时间，超时采样附带线程栈。测试窗口位于屏幕外，结果不代表实际显示帧率或实体笔延迟。

`image-resize-performance.txt` 记录大图重采样，画布刷新和主线程排队时间。平滑缩放使用预乘颜色的 Lanczos3 滤波，按行复用最多 4 MiB 缓存；像素模式使用最近邻。两种方式都保留图层，并在内存上限内支持一步撤销。

交互式 Windows 会话可另设 `PODOR_INK_SYSTEM_TEST=1`，测试通过系统指针注入检查压感，笔尾擦除和重复鼠标事件。注入前逐点确认命中测试窗口，结束后关闭窗口。实体数位板，触控笔仍需设备验证；双指手势目前只有 Compose 输入模拟测试。

## GitHub 多平台 CI

`.github/workflows/check.yml` 在每次 push，pull request 和手动 `workflow_dispatch` 时运行。工作流使用 Gradle Wrapper，Temurin 27 和 Rust 1.98.1，分别在对应系统编译原生引擎与应用；不调用发布脚本，不创建 Release，也不部署登录服务。

| 任务 | 构建与检查 | Actions Artifact |
| --- | --- | --- |
| Windows x64 | Rust 格式，Clippy，测试，`shared:jvmTest`，`desktopApp:test`，`desktopApp:packageMsi` | `podor-windows-x64`，包含 MSI |
| macOS ARM64 | 同上，桌面打包任务为 `desktopApp:packageDmg` | `podor-macos-arm64`，包含 DMG |
| Linux x64 | 同上，在 Xvfb 中运行 JVM 测试和 `desktopApp:packageDeb` | `podor-linux-x64`，包含 DEB |
| Android | `scripts/build-android.sh` 编译 arm64-v8a，x86_64 原生库并运行 `androidApp:assembleDebug` | `podor-android-debug`，包含双 ABI Debug APK |
| iOS | `scripts/build-ios.sh` 编译设备，模拟器原生库和 Kotlin 静态框架，再执行 Xcode 模拟器 build 与设备 archive | `podor-ios-unsigned`，包含下述三个 ZIP |

三个桌面任务另外上传 `tests-windows-x64`，`tests-macos-arm64`，`tests-linux-x64`，保留 Gradle 测试报告和 JUnit XML；测试失败时也尝试上传已有报告。桌面 JNI 库随资源打包，测试和应用 launcher 均启用 `--enable-native-access=ALL-UNNAMED`。CI 不开启 Windows 系统剪贴板，原生指针注入和 GPU 性能测试所需的可选环境变量。

Android 任务安装 `platforms;android-37.0`，Build Tools 37.0.0，NDK 30.0.16248370 和 cargo-ndk 4.1.2。原生库使用版本目录中的 minSdk 构建。APK 使用 Debug 签名，只验证编译与打包；没有运行设备或模拟器测试，也不产出商店发布包。

iOS 使用 Apple Silicon 的 `xcode-27` runner，并通过 `DEVELOPER_DIR` 选定 Xcode 27 正式版。`podor-ios-simulator.zip` 包含 ARM64 模拟器应用；`podor-ios-device-unsigned.zip` 包含未签名的 `podor.xcarchive`；`podor-ios-frameworks.zip` 包含设备和模拟器的 `PodorShared` 静态框架，对应 Rust 静态库与 Compose 资源。集成静态框架时需链接对应 SDK 的 Rust 库，并复制资源到应用包。设备归档尚需开发者团队，证书及 provisioning profile 才能安装或发布，不能视为已完成真机验证。macOS 包也未进行签名或 notarization。

构建结果以具体 Actions run 的成功状态和产物为准。离屏 Scene 测试不等于实体触控笔，系统集成或所有平台设备验收；最新工具链组合仍需分别通过这些 CI 任务。

## 其他平台

Android 需要 SDK 37，NDK，`cargo-ndk`，配置 `ANDROID_HOME` 和 `ANDROID_NDK_HOME` 后运行 `scripts/build-android.ps1`。

iOS 需要 Apple Silicon Mac，Xcode，Rust 和 XcodeGen。运行 `bash scripts/build-ios.sh`，再打开 `iosApp/podor.xcodeproj`。移动端和 macOS，Linux 桌面尚未完成设备验收。

## 发布

修改版本号，新增 `release/notes-<版本>.md`，提交后用 PowerShell 7 运行：

```powershell
./scripts/publish.ps1
```

脚本检查，打包并同步 GitHub 和 Gitee，匿名下载校验安装包与清单后发布稳定版。构建已完成时可用 `-SkipBuild` 重试，已有附件不会被覆盖。额外平台产物与视频可通过 `-AdditionalAssets` 传入，附件名称必须唯一。

仓库地址在 `release/publishing.json`，应用更新入口在 `release/channel.properties`。`latest.json` 与 `SHA256SUMS.txt` 在安装包目录中生成，仅作为 Release 附件上传；发布脚本不将更新清单回写源码，不创建额外提交。应用从 Gitee 发行接口读取清单，下载完成后校验大小和 SHA-256，再保留 MSI 文件。

最新发行若是预发布或缺少更新附件，应用按降序查询最近的稳定发行，最多检查 5 页，每页 20 条。清单限 64 KiB，发行列表每页限 512 KiB，网络失败仍显示错误。发版失败时撤下本次新建且未完成的 Gitee 发行记录，避免遮住稳定更新入口。

安装前再次校验文件，后台安装助手就绪后应用才退出。助手等待旧进程结束，再运行带 `/norestart REBOOT=ReallySuppress` 的安装程序。MSI 构建最后执行 `scripts/configure-msi.ps1`，检查桌面快捷方式，禁止自动重启，添加完成后的启动选项。用户取消安装时不会重启应用；遇到文件占用仍需手动处理，不自动重启 Windows。

本机通过 `scripts/configure-publishing.ps1` 设置 `GITEE_TOKEN`，GitHub 使用 Git 凭据或 `GITHUB_TOKEN`。凭据只保存在本机。
