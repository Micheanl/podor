# 笔刷插件

在设置中打开拼图图标，导入笔刷包，或导出自己的笔刷。

笔刷包使用 JSON，只保存笔刷参数，不运行外部代码。

可直接导入 [Atelier 示例包](../examples/brushes/atelier.podor-brushes.json)，包含 Dry ink，Ribbon，Dust。扩展包支持启用，停用，删除；同 ID 再次导入会替换该包。

```json
{
  "id": "artist.ribbon",
  "name": "Ribbon brushes",
  "version": 1,
  "brushes": [
    {
      "id": "ribbon",
      "label": "Ribbon",
      "size": 46,
      "opacity": 0.6,
      "hardness": 0.9,
      "tip": "Flat",
      "aspect": 0.18,
      "angle": -40,
      "grain": 0,
      "spacing": 0.06
    }
  ]
}
```

| 字段 | 范围 |
| --- | --- |
| `id` | 1–64 个字母，数字，点，下划线或连字符；包内笔刷 ID 唯一 |
| `name` / `label` | 非空，最多 60 字符 |
| `size` | 1–256 px |
| `opacity` / `hardness` / `grain` | 0–1 |
| `tip` | `Round`，`Flat`，`Leaf`（叶形笔尖，启用 `followDirection` 后随运笔方向旋转并表现起笔与收尖）或 `Comb`（排齿，一次画出 4 条平行线） |
| `aspect` | 0.1–1 |
| `angle` | -180–180 度 |
| `followDirection` | 跟随笔画方向旋转，`angle` 为相对角度，省略时关闭 |
| `spacing` | 0.02–1，笔尖尺寸的比例 |
| `stabilization` | 0–1，稳笔强度，省略或为 0 时关闭 |
| `pressureCurve` | -1–1，负值需更重的笔压，正值轻压就有更明显的响应，默认 0 为线性 |
| `sizePressure` | 0–1，粗细随笔压变化的强度，默认 1；0 为固定粗细 |
| `opacityPressure` | 0–1，透明度随笔压变化的强度，默认 0；1 为完全跟随笔压 |
| `paper` | 0–1，纸张纹理强度，纹理按画布坐标平铺，笔画之间保持同一张纸，省略时关闭 |
| `mix` | 0–1，调色混合强度，选中即切换为涂抹工具并带着笔刷颜色一起涂抹，省略时关闭 |
| `texture` | `Smooth`，`Graphite`，`Charcoal`，`Bristle`，`DryBristle`，`Pigment`，`Canvas` 或 `Wash`，默认 `Smooth` |
| `raster` | `Antialiased`，`Pixel` 或 `PixelPerfect`，默认 `Antialiased` |

工具栏中的“柳叶笔”是闭合轮廓填色工具，不由 `tip` 字段创建。内置“柳条笔”使用 `Leaf` 与 `followDirection = true`。

压感参数在“编辑笔刷 → 压感”中调整。自定义笔刷可直接保存，或点复制图标另存副本；内置与扩展笔刷只能另存，不改动来源。旧笔刷包沿用线性粗细压感，透明度不随笔压变化。

单个包最多 64 支笔刷，256 KiB，最多安装 16 个包，另可保存 64 支本地自定义笔刷。格式或参数不合法时，导入不会覆盖已有设置。

笔刷库支持名称搜索和星标收藏，可筛选全部，收藏，自定义或扩展。搜索同时识别原名称与当前界面语言；停用插件会保留收藏，删除笔刷或替换笔刷包时清理失效星标。

桌面笔刷设置保存在 `~/.podor/preferences.json`。笔触由形状，柔边，材质，颗粒和纸纹组成，不包含颜料扩散模拟；`mix` 沿涂抹管线把笔刷颜色掺入拖拽方向。
