# 笔刷插件

在设置中打开拼图图标，导入笔刷包，或导出自己的笔刷。

笔刷包使用 JSON，只保存笔刷参数，不运行外部代码。

可直接导入 [Atelier 示例包](../examples/brushes/atelier.podor-brushes.json)，包含 Dry ink、Ribbon、Dust。扩展包支持启用、停用、删除；同 ID 再次导入会替换该包。

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
| `id` | 1–64 个字母、数字、点、下划线或连字符；包内笔刷 ID 唯一 |
| `name` / `label` | 非空，最多 60 字符 |
| `size` | 1–256 px |
| `opacity` / `hardness` / `grain` | 0–1 |
| `tip` | `Round` 或 `Flat` |
| `aspect` | 0.1–1 |
| `angle` | -180–180 度 |
| `spacing` | 0.02–1，笔尖尺寸的比例 |
| `stabilization` | 0–1，稳笔强度，省略或为 0 时关闭 |

单个包最多 64 支笔刷、256 KiB，最多安装 16 个包，另可保存 64 支本地自定义笔刷。格式或参数不合法时，导入不会覆盖已有设置。

桌面笔刷设置保存在 `~/.podor/preferences.json`。笔触由形状、柔边和颗粒组成，不包含颜料扩散模拟。
