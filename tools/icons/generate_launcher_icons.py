"""从「应用图标源图」确定性重建 Android 全套启动图标资源。

## 为什么需要本脚本
`app/src/main/res/mipmap-*/` 下的 20 个 PNG 是**二进制产物**，肉眼不可审计。此前它们由外部工具生成、
源图未入库（`a1c1dce` 只提交了产物），换图时无法复现同一套几何。本脚本把「源图 → 各密度 / 各层」
的映射写成可复跑代码：**换图只换源图**，留白与安全区由脚本保证。

## 输入
`tools/icons/source/app_icon_source.png`（默认，`--source` 可覆盖）——一张**方形**图标设计稿：
- 不透明区＝**底板**（圆角方形），底板之外透明；
- 底板上＝**蓝色渐变背景** + 中性色 / 暖色**前景图案**（本稿：白锁 + 黄钥匙）。

以下全部由源图**实测**得出，不写死任何像素坐标：
- 底板矩形与中心；
- 背景渐变：对「背景色」像素做最小二乘线性拟合 `c = a + b·x + c·y` ⇒ 各层背景由该模型**外推**生成，
  换图后渐变方向自动跟随，不会退化成「取中心色涂满」；
- 前景掩码：与背景模型的距离 > 阈值。

## 输出（覆盖写入；`--check` 只校验不写）
| 文件 | 尺寸（mdpi / hdpi / xhdpi / xxhdpi / xxxhdpi） | 几何 |
| --- | --- | --- |
| `mipmap-*/ic_launcher_background.png` | 108 / 162 / 216 / 324 / 432 | 底板映射到**中央 72dp**，外圈由渐变**外推铺满 108dp**，不透明 |
| `mipmap-*/ic_launcher_foreground.png` | 同上 | 图案同几何，四周透明（仅图案处 alpha>0） |
| `mipmap-*/ic_launcher.png` | 48 / 72 / 96 / 144 / 192 | 底板铺满整幅（全出血），不透明 |
| `mipmap-*/ic_launcher_round.png` | 同上 | 与 `ic_launcher.png` **逐像素相同**（沿用既有约定） |

- **前景与背景共用「底板 → 中央 72dp」等比映射**：图案最大外接半径由此落进 72dp 圆内
  （脚本现跑打印实测值，判据 `SAFE_RADIUS_DP`），圆 / 圆角方 / squircle 蒙版下都不裁切；
  图案占可见区约 72%，与设计稿自身构图比例一致。
- **背景层铺满 108dp 整幅**：蒙版之外（外圈 18dp）也要有颜色，否则裁切边缘露白。
- **单色层（Android 13+ 主题图标）复用前景层**：主题图标只取 **alpha 通道**着色，而前景的 alpha
  恰好就是「图案剪影（锁梁 / 锁体 / 钥匙实心、锁孔镂空）」⇒ 不另出资产。故
  `mipmap-anydpi-v26/*.xml` 的 `<monochrome>` 指向 `@mipmap/ic_launcher_foreground`。

## 前景抠图判据（含踩坑）
判据＝**与背景渐变模型的距离** `dist(rgb, model(x, y))`：
- 白锁 / 黄钥匙：`dist` 约 200+ ⇒ alpha=1；
- 锁孔（设计上即「底色透出」）：`dist≈0` ⇒ alpha=0，合成后自然透出底色；
- **投影 / 环境光晕**（锁体右下方的深蓝）也属「背景色」⇒ `dist` 小 ⇒ 判为背景。**这是有意的**：
  若把它留成半透明，主题图标模式下会渲成大块灰雾（单色层只看 alpha，不看颜色）；
- 边缘抗锯齿＝**先阈值二值化、再做 1px 高斯模糊**，不是按 `dist` 线性映射——线性映射会把投影
  带成 0.3~0.5 的半透明，正是上一条要避免的形态。

## 用法
```
python tools/icons/generate_launcher_icons.py            # 重建 20 个 PNG
python tools/icons/generate_launcher_icons.py --check    # 现盘产物与源图不一致 ⇒ 退出码 1（漏检兜底）
```
"""

from __future__ import annotations

import argparse
import pathlib
import sys

import numpy as np
from PIL import Image, ImageFilter

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = pathlib.Path(__file__).resolve().parent / "source" / "app_icon_source.png"
RES_DIR = REPO_ROOT / "app" / "src" / "main" / "res"

ADAPTIVE_CANVAS_DP = 108.0  # 自适应图标画布
SAFE_ZONE_DP = 72.0  # 底板映射到中央这么宽；可见蒙版的最大外接半径即其一半
SAFE_RADIUS_DP = SAFE_ZONE_DP / 2
LEGACY_BASE_DP = 48.0  # 传统位图图标基准（全出血）
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

BLUE_B_MINUS_R = 60  # 背景像素判定：B 显著高于 R
OPAQUE_ALPHA = 200  # 不透明判定：躲开源图圆角抗锯齿带
FOREGROUND_DIST = 60.0  # 前景掩码阈值（实测投影 dist < 55）
EDGE_BLUR_SIGMA = 1.0  # 掩码二值化后的抗锯齿模糊半径（源图 1254px 尺度）


class SourceIcon:
    """源图实测几何：底板矩形 / 中心 / 边长、背景渐变模型、前景图案（RGB + alpha）。"""

    def __init__(self, path: pathlib.Path) -> None:
        self.path = path
        arr = np.asarray(Image.open(path).convert("RGBA")).astype(np.float64)
        self.height, self.width = arr.shape[:2]
        rgb, alpha = arr[..., :3], arr[..., 3]
        opaque = alpha > OPAQUE_ALPHA
        background = opaque & (rgb[..., 2] - rgb[..., 0] > BLUE_B_MINUS_R)
        if background.sum() < 0.05 * opaque.sum():
            raise SystemExit("源图蓝色背景占比过低，疑似不是本脚本约定的设计稿")

        x0, y0, x1, y1 = self._bbox(opaque)
        self.plate = (x0, y0, x1, y1)
        self.plate_center = ((x0 + x1) / 2, (y0 + y1) / 2)
        self.plate_side = ((x1 - x0) + (y1 - y0)) / 2
        self.gradient = self._fit_gradient(rgb, background)

        self.rgb = rgb
        dist = np.linalg.norm(rgb - self._model(*self._index_grid()), axis=-1)
        art = opaque & (dist > FOREGROUND_DIST)
        if not art.any():
            raise SystemExit("源图未识别出前景图案（与背景模型的距离全部低于阈值）")
        self.art = self._bbox(art)
        mask = Image.fromarray(((art & opaque) * 255).astype(np.uint8), mode="L")
        blurred = np.asarray(mask.filter(ImageFilter.GaussianBlur(EDGE_BLUR_SIGMA)))
        ax0, ay0, ax1, ay1 = self.art
        self.art_alpha = blurred[ay0:ay1, ax0:ax1].astype(np.float64)

    # ---- 实测几何 -------------------------------------------------------
    @staticmethod
    def _bbox(mask: np.ndarray) -> tuple[int, int, int, int]:
        ys, xs = np.nonzero(mask)
        return int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1

    def _index_grid(self) -> tuple[np.ndarray, np.ndarray]:
        ys, xs = np.mgrid[0 : self.height, 0 : self.width]
        return xs.astype(np.float64), ys.astype(np.float64)

    @staticmethod
    def _fit_gradient(rgb: np.ndarray, background: np.ndarray) -> np.ndarray:
        ys, xs = np.nonzero(background)
        design = np.stack([np.ones_like(xs), xs, ys], axis=1).astype(np.float64)
        coefficients = np.empty((3, 3), dtype=np.float64)
        for channel in range(3):
            coefficients[channel] = np.linalg.lstsq(design, rgb[ys, xs, channel], rcond=None)[0]
        return coefficients

    def _model(self, xs: np.ndarray, ys: np.ndarray) -> np.ndarray:
        stacked = np.stack([np.ones_like(xs), xs, ys], axis=-1)
        return np.einsum("...k,ck->...c", stacked, self.gradient)

    def _source_px_per_dp(self, canvas_dp: float, plate_side_dp: float) -> float:
        """把底板边长映射为 `plate_side_dp` 时的比例（源图像素 / dp）。"""
        return self.plate_side / plate_side_dp

    def art_radius_dp(self, canvas_dp: float, plate_side_dp: float) -> float:
        """图案相对画布中心的最大外接半径（dp）——安全区判据的实测值。"""
        scale = self._source_px_per_dp(canvas_dp, plate_side_dp)
        ys, xs = np.nonzero(self.art_alpha > 0.5)
        xs = xs + self.art[0]
        ys = ys + self.art[1]
        radius = np.hypot(xs - self.plate_center[0], ys - self.plate_center[1]) / scale
        return float(radius.max())

    # ---- 渲染 -----------------------------------------------------------
    def _canvas_dp_axis(self, canvas_dp: float, size_px: int) -> np.ndarray:
        return (np.arange(size_px, dtype=np.float64) + 0.5) * (canvas_dp / size_px)

    def _to_source(self, values: np.ndarray, canvas_dp: float, plate_side_dp: float) -> np.ndarray:
        """画布 dp 坐标 → 源图像素坐标（画布中心 ↔ 底板中心，等比）。"""
        scale = self._source_px_per_dp(canvas_dp, plate_side_dp)
        return np.asarray(self.plate_center)[:, None] + (np.asarray(values) - canvas_dp / 2) * scale

    def opaque_layer(self, canvas_dp: float, size_px: int, plate_side_dp: float) -> Image.Image:
        """底板铺满画布（不透明）：画布内逐像素求值背景渐变模型，底板之外以**边缘色延拓**。

        外圈（自适应图标外 18dp）隐式外推渐变会让左上顶角的 B 通道饱和到 255、并留下一条硬边；
        按底板矩形钳位即可得到平静的边缘色延伸，且对可见的 72dp 区域**逐像素无影响**。
        """
        dp = self._canvas_dp_axis(canvas_dp, size_px)
        source = self._to_source(dp, canvas_dp, plate_side_dp)
        x0, y0, x1, y1 = self.plate
        xs, ys = np.meshgrid(
            np.clip(source[0], x0, x1 - 1), np.clip(source[1], y0, y1 - 1)
        )
        rgb = np.clip(self._model(xs, ys), 0, 255).astype(np.uint8)
        alpha = np.full(rgb.shape[:2] + (1,), 255, dtype=np.uint8)
        return Image.fromarray(np.concatenate([rgb, alpha], axis=-1), mode="RGBA")

    def art_layer(self, canvas_dp: float, size_px: int, plate_side_dp: float) -> Image.Image:
        """前景图案层（其余透明）：源图 RGB + 掩码 alpha，映射到同一画布。"""
        ax0, ay0, ax1, ay1 = self.art
        art = np.concatenate(
            [self.rgb[ay0:ay1, ax0:ax1], self.art_alpha[..., None]], axis=-1
        ).astype(np.uint8)
        source = Image.fromarray(art, mode="RGBA")

        px_per_source = (size_px / canvas_dp) / self._source_px_per_dp(canvas_dp, plate_side_dp)
        target = (
            max(1, int(round((ax1 - ax0) * px_per_source))),
            max(1, int(round((ay1 - ay0) * px_per_source))),
        )
        scale = self._source_px_per_dp(canvas_dp, plate_side_dp)
        origin_px = (
            (ax0 - self.plate_center[0]) / scale + canvas_dp / 2,
            (ay0 - self.plate_center[1]) / scale + canvas_dp / 2,
        )
        origin_px = (origin_px[0] * size_px / canvas_dp, origin_px[1] * size_px / canvas_dp)

        layer = Image.new("RGBA", (size_px, size_px), (0, 0, 0, 0))
        resized = source.resize(target, Image.LANCZOS)
        layer.paste(resized, (int(round(origin_px[0])), int(round(origin_px[1]))))
        return layer


def main() -> int:
    parser = argparse.ArgumentParser(description="从源图重建 Android 启动图标资源")
    parser.add_argument("--source", type=pathlib.Path, default=DEFAULT_SOURCE)
    parser.add_argument("--check", action="store_true", help="只校验现盘产物与源图是否一致")
    args = parser.parse_args()

    if not args.source.exists():
        print(f"源图缺失：{args.source}", file=sys.stderr)
        return 2

    icon = SourceIcon(args.source)
    print(f"源图 {args.source.name} {icon.width}x{icon.height}")
    print(f"  底板 bbox {icon.plate} 中心 {icon.plate_center} 边长 {icon.plate_side:.1f}")
    print(f"  图案 bbox {icon.art}")

    radius = icon.art_radius_dp(ADAPTIVE_CANVAS_DP, SAFE_ZONE_DP)
    verdict = "PASS" if radius <= SAFE_RADIUS_DP else "FAIL"
    print(f"  图案最大外接半径 {radius:.2f}dp / 上限 {SAFE_RADIUS_DP:.0f}dp ⇒ {verdict}")

    drift: list[pathlib.Path] = []
    written = 0
    for name, factor in DENSITIES.items():
        directory = RES_DIR / f"mipmap-{name}"
        adaptive_px = int(round(ADAPTIVE_CANVAS_DP * factor))
        legacy_px = int(round(LEGACY_BASE_DP * factor))

        background = icon.opaque_layer(ADAPTIVE_CANVAS_DP, adaptive_px, SAFE_ZONE_DP)
        foreground = icon.art_layer(ADAPTIVE_CANVAS_DP, adaptive_px, SAFE_ZONE_DP)
        legacy = icon.opaque_layer(LEGACY_BASE_DP, legacy_px, LEGACY_BASE_DP)
        legacy.alpha_composite(icon.art_layer(LEGACY_BASE_DP, legacy_px, LEGACY_BASE_DP))

        targets = {
            "ic_launcher_background.png": background,
            "ic_launcher_foreground.png": foreground,
            "ic_launcher.png": legacy,
            "ic_launcher_round.png": legacy,
        }
        for filename, image in targets.items():
            path = directory / filename
            if args.check:
                if not path.exists() or not np.array_equal(
                    np.asarray(Image.open(path).convert("RGBA")), np.asarray(image)
                ):
                    drift.append(path)
                continue
            image.save(path, format="PNG", optimize=True)
            written += 1
        print(f"  {directory.name}: {adaptive_px}px 自适应层 / {legacy_px}px 位图层")

    if args.check:
        if drift:
            print(f"漂移：{len(drift)} 个产物与源图不一致（重跑本脚本即可）")
            for path in drift:
                print(f"  {path.relative_to(REPO_ROOT)}")
            return 1
        print("一致：现盘产物可由源图逐像素重建")
        return 0

    print(f"已写入 {written} 个 PNG")
    return 0 if radius <= SAFE_RADIUS_DP else 1


if __name__ == "__main__":
    sys.exit(main())
