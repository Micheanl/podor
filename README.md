<p align="center">
  <img src="branding/cover.png" alt="podor" width="100%" />
</p>

<p align="center">
  <a href="https://gitee.com/Micheanl/podor/releases/latest"><b>下载 Windows 版</b></a>
  &nbsp; · &nbsp;
  <a href="https://github.com/Micheanl/podor/releases/latest">GitHub 下载</a>
  &nbsp; · &nbsp;
  <a href="https://gitee.com/Micheanl/podor/issues">反馈问题</a>
</p>

podor 是一款绘画与图像编辑软件，用 Rust 处理笔触和像素，用 Kotlin Compose 构建界面。目前主要开发 Windows 版。

### 画布上的事

- **画笔与颜色**，11 种内置笔刷，支持压感和可调稳笔，可调整笔尖、颗粒和间距，也可以导入自己的笔刷包。
- **图层与编辑**，8 种混合模式，矩形选区、填充、色彩调整和模糊，支持撤销与重做。
- **文件与习惯**，自定义画布，PNG、JPEG、静态 WebP 导入导出，工程保存为 `.podor`。中英文界面，快捷键可改。

### 引擎示意

压感控制笔尖，画布分块更新，历史共享未修改的像素。

<p>
  <a href="branding/pressure.png"><img src="branding/pressure.png" alt="Pressure，压力控制笔尖半径" width="32%" /></a>
  <a href="branding/tiles.png"><img src="branding/tiles.png" alt="Tiles，只传输修改过的画布块" width="32%" /></a>
  <a href="branding/history.png"><img src="branding/history.png" alt="History，共享未修改的块" width="32%" /></a>
</p>

### 安装

下载 MSI，按提示安装。更新前先保存作品并退出应用，设置中可以检查、下载新版本。安装包同时发布到 Gitee 和 GitHub。

当前提供 Windows x64 安装包。其他平台仍需设备验证。PSD、蒙版和 ICC 色彩管理尚未支持。

### 从源码运行

准备 JDK 25、Rust stable 和平台 C++ 工具链，运行：

```powershell
./gradlew.bat :desktopApp:run
```

macOS、Linux 使用 `./gradlew`。构建、测试和发版见[开发说明](docs/DEVELOPMENT.md)，笔刷扩展见[格式说明](docs/PLUGINS.md)。

[MIT License](LICENSE)
