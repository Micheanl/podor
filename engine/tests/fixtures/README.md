# 测试素材

- `progressive.jpg`：Pillow 生成的 24 × 16 纯色渐进式 JPEG，RGB 为 60 / 120 / 180，质量 95。
- `lossy-alpha.webp`：Pillow 生成的 24 × 16 有损 WebP，RGBA 为 50 / 100 / 200 / 128，质量 95。
- `animated.webp`：Pillow 生成的两帧 8 × 8 红 / 蓝色块，无损编码，每帧 100 ms，用于验证静态工作流不会误读动画。

素材均为测试创建，不含用户作品。JPEG / WebP EXIF 八方向和超限尺寸由 Rust 测试临时构造。
