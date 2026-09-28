# 测试素材

- `progressive.jpg`：Pillow 生成的 24 × 16 纯色渐进式 JPEG，RGB 为 60 / 120 / 180，质量 95。
- `lossy-alpha.webp`：Pillow 生成的 24 × 16 有损 WebP，RGBA 为 50 / 100 / 200 / 128，质量 95。
- `animated.webp`：Pillow 生成的两帧 8 × 8 红 / 蓝色块，无损编码，每帧 100 ms，用于验证静态工作流不会误读动画。
- `project-v1.bin`、`project-v1.png`：由 0.2.12 引擎生成的双图层工程及其导出图，128 × 96，含半透明色块和隐藏的正片叠底图层，用于验证旧工程读取。
- `gimp-layers.ora`、`gimp-interlaced.png`：GIMP 3.2.6 创建的 129 × 131 色块样本，含半透明正片叠底、负偏移、越界及隐藏图层；PNG 使用交错编码，用于检查 ORA 读取与合成结果。
- `gimp-layers.psd`、`gimp-psd-preview.png`：GIMP 3.2.6 创建的三层 PSD 和合成预览，含 Unicode 名称、透明度锁定、正片叠底、负偏移和隐藏图层，用于检查 PSD 导入。

素材均为测试创建，不含用户作品。JPEG / WebP EXIF 八方向和超限尺寸由 Rust 测试临时构造。
