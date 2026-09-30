<p align="center">
  <img src="branding/cover.png" alt="podor，浅色英文界面与插画宣传图" width="100%" />
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

- **画笔与颜色**，22 种内置笔刷，可调笔尖、颗粒、纸纹、间距、压感和稳笔，也能导入自己的笔刷包。柳叶笔尖随笔画方向旋转，起笔从针尖渐胀、收笔自然收尖；排齿笔一次画出四条平行线；笔触边缘做了抗锯齿处理，线条和圆滑曲线没有锯齿台阶。调色混合在涂抹时带入笔刷颜色，纸纹按画布平铺让笔画之间保持同一张纸。笔刷支持搜索、收藏和来源筛选，自定义笔刷可直接更新、另存副本或删除。画笔和橡皮支持左右、上下及双轴对称，可移动对称轴，一笔一次撤销。涂抹工具沿笔触推开已有颜色，可沿用各类笔尖。线性与径向渐变，可调整两端颜色、反转或淡出到透明色。个人色卡可手动存色，也可从画布提取主要颜色。
- **图层与编辑**，图片可导入为独立图层，选区可复制、剪切，剪贴板图片可粘贴为新图层。图层可缩放、旋转、翻转，确认前可预览，支持一步撤销。拖动缩略图排序，移动、复制和合并图层，锁定透明度与图层内容，8 种混合模式。不透明度与混合模式可在画布上预览、对比和重置，确认后一次撤销。矩形、椭圆、套索与魔棒选区可叠加、相减、相交或反选。魔棒按颜色取样，可选连续区域或整幅图像。色彩、曲线和模糊调整可直接在画布预览、对比原图，确认后再应用。
- **画布与视图**，整幅作品可缩放，支持锁定比例、平滑和像素两种方式。画布可扩边或裁切，九宫格定位并预览边界，支持撤销。视图可缩放、平移、旋转和镜像，Windows 触屏可用双指操作，也可从底部调整角度，或按住 Shift 滚动鼠标滚轮。白色、灰色和透明棋盘格背景可切换，右侧面板可展开、收起，视图变化不改动作品像素。
- **参考图**，导入图片或用 Ctrl+Shift+V 粘贴到画布，拖动摆放、拖角缩放，可镜像、隐藏。参考图不参与导出，切换作品或退出后清除。
- **文件与习惯**，自定义画布，PNG、JPEG、静态 WebP 导入导出，TIFF、BMP 透明图像导出，PSD、ORA 平面图层导入导出，工程保存为 `.pod`，旧 `.podor` 工程仍可打开。中英文界面，快捷键可改。
- **作品首页**，缩略图列表，按名称查找或排序。默认从首页开始，也可设置为空白画布。作品手动保存，默认画笔为黑色。

### 引擎示意

压感控制笔尖，画布分块更新，历史共享未修改的像素。

<p>
  <a href="branding/pressure.png"><img src="branding/pressure.png" alt="Pressure，压力控制笔尖半径" width="32%" /></a>
  <a href="branding/tiles.png"><img src="branding/tiles.png" alt="Tiles，只传输修改过的画布块" width="32%" /></a>
  <a href="branding/history.png"><img src="branding/history.png" alt="History，共享未修改的块" width="32%" /></a>
</p>

### 安装

下载 MSI，按提示安装，桌面会创建快捷方式。设置中可以检查、下载和安装更新，未保存作品会先提醒。安装结束默认打开 podor，不自动重启电脑。安装包同时发布到 Gitee 和 GitHub。

当前提供 Windows x64 安装包。其他平台仍需设备验证。PSD 支持 8 位 RGB 像素图层，颜色按 sRGB 读取；图层组、蒙版、特效和 ICC 色彩管理尚未支持。

Wacom 可在设置中切换 WinTab / Windows Ink，支持压感、笔尾橡皮擦和侧键取色。自动模式优先使用 WinTab，驱动不可用时回退 Windows Ink。各型号仍需实机验证，倾斜与触控环暂未接入。

### 从源码运行

准备 JDK 25、Rust stable 和平台 C++ 工具链，运行：

```powershell
./gradlew.bat :desktopApp:run
```

macOS、Linux 使用 `./gradlew`。构建、测试和发版见[开发说明](docs/DEVELOPMENT.md)，笔刷扩展见[格式说明](docs/PLUGINS.md)。

[MIT License](LICENSE)
