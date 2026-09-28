# Podor walkthrough

英文操作演示，1920 × 1080，30 fps。从新建画布到画笔、调色、图层、选区、变换、曲线、参考图、导出和作品列表。

```sh
npm ci
npm run dev
npm run render
```

成片输出到 `out/podor-introduction-en.mp4`。运行 `python scripts/captions.py` 可单独导出英文字幕。

`public/clips` 是 Podor 0.2.41 的 Compose 界面操作录制，使用演示工程与隔离文件适配器。鼠标提示、镜头缩放和字幕在 Remotion 中合成，播放时间不代表性能测试结果。

解说使用 Edge TTS 的 `en-US-GuyNeural`，文稿在 `operations.json`。修改文稿后，安装 `edge-tts==7.2.8` 和 `mutagen==1.48.1`，运行 `python scripts/voice.py`。已有语音按文稿哈希复用。

配乐由 `scripts/music.py` 合成，需要 NumPy。字体使用 Cormorant Garamond 和 Inter，授权文件放在 `public/fonts`。图标沿用 Podor 原始品牌资源。
