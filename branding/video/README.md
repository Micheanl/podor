# Podor 0.4.0 产品演示

本片时间线为 1920×1080 像素，30 fps，18,000 帧，准确 600 秒。九个章节分别注册为 Remotion Composition。所有产品镜头使用当前 0.4.0 的浅色英文界面。影片标题和说明只用中文。

音轨只使用用户提供的 Lucky Luke / “Cooler Than Me (Radio Edit)” 背景音乐，没有配音。原曲和中间混音仅保存在已忽略的本地目录。中文字幕以大字号烧录在独立字幕区，保留完整界面与下方操作胶囊。字幕 JSON 遵循 `@remotion/captions` 4.0.529 的 `Caption` 类型；外部中文和英文 SRT 可选，英文不烧录进画面。

代码章节展示仓库中实际 Kotlin 和 Rust 源码摘录，每屏最多六行。浅色编辑器布局用于代码讲解，画面明确标注“真实源码摘录”，不是 IntelliJ IDEA 操作录屏。Compose Multiplatform 是 JetBrains 开发的 Kotlin 声明式 UI 框架，像素与工程历史由本项目 Rust 引擎处理。官方技术标识保持原样，详细来源和许可见 NOTICE。

## 准备和预览

```powershell
npm ci
python scripts/prepare.py
python scripts/captions.py
npm run lint
npm run validate
npm run dev
```

`operations.json` 保存九章说明文字和原时间位置，`src/visual-timing040.json` 保留镜头锚点，`src/timeline040.json` 保存章长与字幕。镜头和源码页随字幕锚点同步，总时长为 600 秒。通用音频导入工具保留在 `scripts`，本版播放器不引用独立语音文件。

录制素材由当前真实 Compose/JNI 工作台生成，放在 `public/v040/capture`，采用 1800×900 和 30 fps。`manifest.json` 必须声明 `appVersion: "0.4.0"`，`theme: "Light"`，`language: "English"`，并包含 `shots`。每项有 `id`，`file`，`metadata`，`width`，`height`，`frames`，`fps` 和 `cursorEmbedded`。同名 JSON 保留每帧鼠标位置与真实操作事件。系统指针不在离屏像素中时，影片依据真实记录的鼠标轨迹叠加原品牌笔形资产，笔尖热点保持与生产代码一致。

缺失当前素材或功能字幕时，验证会失败，不会回退旧录屏或占位画面。`public/v040/coverage.json` 保存功能的源码证据和兼容范围。

## 导出

使用具备完整音频滤镜的 FFmpeg 运行本地混音，歌曲路径由使用者提供：

```powershell
python scripts/mix_audio.py --music "local/Radio Edit.mp3" --output out/v040/podor-040-music.wav --ffmpeg "local/ffmpeg.exe" --ffprobe "local/ffprobe.exe"
```

音乐循环采用四秒交叉淡化，开头三秒渐入，结尾六秒淡出，没有语音触发的音量压低。双遍响度调整目标为 −18 LUFS，PCM true peak 不高于 −1.5 dBTP，为 AAC 编码留出余量。混音保持 48 kHz 立体声和准确 600 秒。Studio 使用同一纯音乐轨的本地 AAC 副本，歌曲文件不提交或单独发布。

```powershell
npm run render
```

输出为 `out/v040/podor-0.4.0-tour-zh.mp4`，以及 `podor-040-zh.srt` 和 `podor-040-en.srt`。最终混音必须先放在已忽略的 `out/v040/podor-040-music.wav`，导出前检查素材，纯音乐哈希和功能覆盖范围。导出后修整 AAC 尾部填充并保留所有视频帧，再使用 ffprobe 读取实际帧数，尺寸，帧率，音轨和 600 秒时长，写入 `out/v040/validation.json`。

## 素材和技术来源

花束图片是生成的厚涂练习参考，画面标识“生成练习参考”。五个绘画阶段使用参考取色后的真实原生笔触，持续标明“加速原生笔触练习”。这不是原速手工录像，也没有将参考图导入后冒充笔刷绘画。

项目及第三方署名见 [NOTICE](NOTICE.md)。框架解释参照 [Kotlin 官方 Compose Multiplatform 文档](https://kotlinlang.org/docs/multiplatform/compose-multiplatform.html)。浅色代码讲解参考 [IntelliJ IDEA 官方主题说明](https://www.jetbrains.com/help/idea/user-interface-themes.html)，不使用 IDE 产品 Logo。
