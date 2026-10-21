#!/usr/bin/env python3
"""把逐帧 WebP 动画打包成 APK 里用的 .bin + pet-manifest.json。

用法（在仓库根目录）：

    python tools/pack_pet.py <源目录> [--out android-app/app/src/main/assets/pet]

<源目录> 下每个子目录是一段动画，子目录名就是 clip id，里面按文件名顺序排好
`*.webp` 帧。默认源目录是 dsh-dafeiyu 插件的素材位置：

    %USERPROFILE%\\.dsh\\profiles\\desktop\\node_modules\\dsh-dafeiyu\\assets\\pet

产物：
    <out>/<clip>.bin            该 clip 的全部帧首尾相接
    <out>/pet-manifest.json     每帧字节长度，运行时据此切片
    <out>/LICENSE.txt           素材许可原文（如源目录里有就一起拷过来）

为什么打成 .bin 而不是逐帧文件：3444 个 zip 条目会让安装和资源枚举都变慢，
而且把 AssetManager.open 变成了每帧成本。打成一整段之后，播一帧只是数组切片。

素材来自 PC2005-cloud/dsh-pet（MIT），经 QCYTSN/dsh-dafeiyu（MIT）转码为 WebP 序列。
本脚本只做缩放与重编码，不改变素材的权利归属，不提供担保。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - 环境问题，直接说清楚
    sys.exit("需要 Pillow：python -m pip install Pillow")

SCALE = 0.7
QUALITY = 70
FPS = 24
METHOD = 4  # WebP 编码强度，4 已经在体积和耗时之间平衡

DEFAULT_SOURCE = Path(
    os.environ.get("USERPROFILE", Path.home())
) / ".dsh/profiles/desktop/node_modules/dsh-dafeiyu/assets/pet"


def frame_paths(clip_dir: Path) -> list[Path]:
    """一个 clip 的帧，按文件名排序（素材本身就是零填充的序号）。"""
    return sorted(p for p in clip_dir.iterdir() if p.suffix.lower() == ".webp")


def main() -> int:
    ap = argparse.ArgumentParser(description="打包桌宠动画帧")
    ap.add_argument("source", nargs="?", default=str(DEFAULT_SOURCE), help="源素材目录")
    ap.add_argument(
        "--out",
        default="android-app/app/src/main/assets/pet",
        help="输出目录（默认写进 android-app 的 assets）",
    )
    args = ap.parse_args()

    source = Path(args.source)
    out = Path(args.out)
    if not source.is_dir():
        sys.exit(f"源目录不存在：{source}")

    out.mkdir(parents=True, exist_ok=True)

    # 上一轮的产物先清掉，避免 clip 改名后留下孤儿文件
    for stale in out.glob("*.bin"):
        stale.unlink()

    clips = []
    for clip_dir in sorted(p for p in source.iterdir() if p.is_dir()):
        paths = frame_paths(clip_dir)
        if not paths:
            continue

        sizes: list[int] = []
        blob = bytearray()
        width = height = 0

        for path in paths:
            with Image.open(path) as img:
                rgb = img.convert("RGBA")
                size = (
                    max(1, round(rgb.width * SCALE)),
                    max(1, round(rgb.height * SCALE)),
                )
                resized = rgb.resize(size, Image.LANCZOS)
                width, height = resized.size

                # 编码到内存再截取长度：既拿到字节，又不用写临时文件
                import io

                buffer = io.BytesIO()
                resized.save(buffer, "WEBP", quality=QUALITY, method=METHOD)
                data = buffer.getvalue()

            blob.extend(data)
            sizes.append(len(data))

        (out / f"{clip_dir.name}.bin").write_bytes(bytes(blob))
        clips.append(
            {
                "id": clip_dir.name,
                "frames": len(sizes),
                "width": width,
                "height": height,
                "sizes": sizes,
            }
        )
        mb = len(blob) / 1024 / 1024
        print(f"  {clip_dir.name:18s} {len(sizes):4d} 帧  {mb:5.2f} MB  {width}x{height}")

    manifest = {
        "version": 1,
        "fps": FPS,
        "scale": SCALE,
        "width": clips[0]["width"] if clips else 0,
        "height": clips[0]["height"] if clips else 0,
        "clips": clips,
    }
    (out / "pet-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )

    licence = source / "LICENSE.txt"
    if licence.is_file():
        shutil.copyfile(licence, out / "LICENSE.txt")

    total = sum(f["frames"] for f in clips)
    size = sum(p.stat().st_size for p in out.glob("*.bin")) / 1024 / 1024
    print(f"\n{len(clips)} 个 clip / {total} 帧 / {size:.2f} MB → {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
