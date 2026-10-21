#!/usr/bin/env python3
"""把 dsh-pet 桌面端插件的 WebM 动画转成 APK 里用的 .bin + pet-manifest.json。

用法（在仓库根目录）：

    python tools/pack_pet.py --webm <webm 目录> --config <config.jsonc> \
        [--out android-app/app/src/main/assets/pet] [--fps 12] [--width 200] [--quality 60]

<webm 目录> 是 dsh-pet 插件的 `assets/webm`，每段动画一个 `<名字>.webm`，
名字与 `config.jsonc` 里 `animations` 各分组的条目一一对应。脚本从 config 里读出
分组、点击回应、工作状态、余额、碎碎念这些角色，写成 manifest 的 `groups` 与
`roles`，这样 Kotlin 端不用再硬编码中文字符串。

产物：
    <out>/<id>.bin            该 clip 的全部帧首尾相接（RGBA WebP）
    <out>/pet-manifest.json   分组、角色、每帧字节长度
    <out>/LICENSE.txt         素材许可

为什么先转 PNG 再转 WebP：源 WebM 的透明通道存在单独的 alpha plane 里，
只有 `libvpx-vp9` 解码器会把它解出来（原生 vp9 解码器给出黑底）。所以交给
ffmpeg 用 `-c:v libvpx-vp9` 解成 RGBA PNG 序列，再由 Pillow 缩放编码成 WebP。

素材来自 PC2005-cloud/dsh-pet（代码 MIT，美术素材禁止商用），本脚本只做缩放
与重编码，不改变权利归属，不提供担保。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - 环境问题，直接说清楚
    sys.exit("需要 Pillow：python -m pip install Pillow")

DEFAULT_WEBM = Path(
    os.environ.get("USERPROFILE", Path.home())
) / ".dsh/profiles/desktop/node_modules/dsh-pet/assets/webm"
DEFAULT_CONFIG = Path(
    os.environ.get("USERPROFILE", Path.home())
) / ".dsh/profiles/desktop/node_modules/dsh-pet/assets/config.jsonc"
DEFAULT_FFMPEG = "ffmpeg"

METHOD = 4  # WebP 编码强度，4 已经在体积和耗时之间平衡

# config.jsonc 里没有 id，只有中文名；这里给每个角色起一个稳定的 ASCII id。
ROLES = {
    "idle": ["待机呼吸休闲"],
    "turn": ["东张西望"],
    "drag": ["被鼠标拖拽悬空反馈"],
    "click": [
        "点击回应-开心跃动",
        "点击回应-害羞惊讶",
        "点击回应-傲娇生气",
        "点击回应-挠痒咯咯笑",
        "点击回应-元气挥手",
    ],
    "walk": ["螃蟹走路"],
    "walkAlt": ["原地左转奔跑", "原地漂浮踏步"],
    "workThinking": ["工作状态-思考冒泡"],
    "workBusy": ["工作状态-忙碌点按"],
    "workOrganize": ["工作状态-清点归档"],
    "workWaiting": ["工作状态-原地踱步张望"],
    "workSuccess": ["工作状态-雀跃庆祝"],
    "workError": ["工作状态-垂头叹气冒汗"],
    "whisper": ["碎碎念-擦桌碎碎念", "碎碎念-发呆碎碎念", "碎碎念-对屏碎碎念"],
    "balanceHigh": ["余额-钱袋满溢", "余额-金袋叮当"],
    "balanceMid": ["余额-钱袋如常", "余额-数金皱眉"],
    "balanceLow": ["余额-袋空如洗", "余额-分文不剩"],
}

# 菜单里的分组顺序：先状态类，再玩耍类，最后是事件类。
GROUP_ORDER = [
    "待机",
    "转向",
    "拖拽",
    "点击回应",
    "移动",
    "小动作",
    "玩耍",
    "吃什么",
    "时节",
    "文字",
    "余额档位",
    "碎碎念",
    "工作状态",
]


def strip_jsonc(text: str) -> str:
    """去掉 // 与 /* */ 注释和尾逗号，字符串里的不动。"""
    out: list[str] = []
    i = 0
    in_str = False
    esc = False
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and nxt == "*":
            i += 2
            while i < n and not (text[i] == "*" and text[i + 1 : i + 2] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return re.sub(r",(\s*[}\]])", r"\1", "".join(out))


def slug(index: int) -> str:
    return f"m{index:03d}"


def ordered_clips(cfg: dict) -> list[tuple[str, str]]:
    """按分组顺序列出 (中文名, 分组名)，去重。"""
    anim = cfg["animations"]
    groups: dict[str, list[str]] = {}

    def add(group: str, names):
        groups.setdefault(group, [])
        for name in names:
            if name not in groups[group]:
                groups[group].append(name)

    add("待机", anim.get("idle", []))
    add("转向", anim.get("turn", []))
    add("拖拽", anim.get("drag", []))
    add("点击回应", anim.get("clicks", []))
    add("移动", [a["name"] for a in anim.get("moves", {}).get("actions", [])])
    for category in anim.get("categories", []):
        add(category["id"], category["actions"])
    events = anim.get("events", {})
    add("余额档位", events.get("balance", []))
    add("碎碎念", events.get("whisper", []))
    add("工作状态", events.get("workStatus", []))

    ordered = []
    for group in GROUP_ORDER:
        for name in groups.get(group, []):
            ordered.append((name, group))
    for group, names in groups.items():
        if group not in GROUP_ORDER:
            for name in names:
                ordered.append((name, group))
    return ordered


def convert(
    webm: Path,
    out_bin: Path,
    ffmpeg: str,
    fps: int,
    width: int,
    quality: int,
    work: Path,
) -> tuple[int, int, int, list[int]]:
    """解一段 webm，缩放后逐帧编码 WebP，写成一个 .bin。"""
    frames_dir = work / webm.stem
    if frames_dir.exists():
        shutil.rmtree(frames_dir)
    frames_dir.mkdir(parents=True)

    # -c:v libvpx-vp9 是唯一会解出 alpha plane 的解码器
    cmd = [
        ffmpeg,
        "-v",
        "error",
        "-c:v",
        "libvpx-vp9",
        "-i",
        str(webm),
        "-vf",
        f"fps={fps},scale={width}:-1:flags=lanczos",
        "-pix_fmt",
        "rgba",
        "-y",
        str(frames_dir / "f%04d.png"),
    ]
    subprocess.run(cmd, check=True)

    frames = sorted(frames_dir.glob("f*.png"))
    if not frames:
        raise RuntimeError(f"没有解出帧：{webm}")

    sizes: list[int] = []
    blob = bytearray()
    height = 0
    for frame in frames:
        with Image.open(frame) as img:
            rgba = img.convert("RGBA")
            height = rgba.height
            buffer = __import__("io").BytesIO()
            rgba.save(buffer, "WEBP", quality=quality, method=METHOD)
            data = buffer.getvalue()
        blob.extend(data)
        sizes.append(len(data))

    out_bin.write_bytes(bytes(blob))
    shutil.rmtree(frames_dir, ignore_errors=True)
    return len(frames), width, height, sizes


def main() -> int:
    ap = argparse.ArgumentParser(description="打包桌宠动画帧")
    ap.add_argument("--webm", default=str(DEFAULT_WEBM), help="webm 素材目录")
    ap.add_argument("--config", default=str(DEFAULT_CONFIG), help="config.jsonc 路径")
    ap.add_argument(
        "--out",
        default="android-app/app/src/main/assets/pet",
        help="输出目录（默认写进 android-app 的 assets）",
    )
    ap.add_argument("--ffmpeg", default=DEFAULT_FFMPEG, help="ffmpeg 可执行文件")
    ap.add_argument("--fps", type=int, default=12, help="输出帧率（源是 24）")
    ap.add_argument("--width", type=int, default=400, help="输出帧宽（px，源是 640）")
    ap.add_argument("--quality", type=int, default=68, help="WebP 质量")
    ap.add_argument("--only", default="", help="只转名字含该串的 clip（调试用）")
    args = ap.parse_args()

    webm_dir = Path(args.webm)
    out = Path(args.out)
    if not webm_dir.is_dir():
        sys.exit(f"webm 目录不存在：{webm_dir}")

    cfg = json.loads(strip_jsonc(Path(args.config).read_text(encoding="utf-8")))
    wanted = ordered_clips(cfg)
    if args.only:
        wanted = [w for w in wanted if args.only in w[0]]

    out.mkdir(parents=True, exist_ok=True)
    # 上一轮的产物先清掉，避免 clip 改名后留下孤儿文件
    for stale in out.glob("*.bin"):
        stale.unlink()

    clips: list[dict] = []
    group_members: dict[str, list[str]] = {}
    by_name: dict[str, str] = {}
    total_blob = 0

    with tempfile.TemporaryDirectory(dir=".") as tmp:
        work = Path(tmp)
        for index, (name, group) in enumerate(wanted, start=1):
            source = webm_dir / f"{name}.webm"
            if not source.is_file():
                print(f"  跳过（缺素材）: {name}")
                continue
            clip_id = slug(index)
            frames, width, height, sizes = convert(
                source, out / f"{clip_id}.bin", args.ffmpeg, args.fps, args.width, args.quality, work
            )
            size = sum(sizes)
            total_blob += size
            clips.append(
                {
                    "id": clip_id,
                    "label": name,
                    "group": group,
                    "frames": frames,
                    "width": width,
                    "height": height,
                    "sizes": sizes,
                }
            )
            by_name[name] = clip_id
            group_members.setdefault(group, []).append(clip_id)
            print(
                f"  {clip_id} {name:22s} {frames:4d} 帧  {size / 1024 / 1024:5.2f} MB  {width}x{height}"
            )

    roles: dict[str, list[str]] = {}
    for role, names in ROLES.items():
        ids = [by_name[n] for n in names if n in by_name]
        if ids:
            roles[role] = ids

    groups = [
        {"id": group, "clips": group_members[group]}
        for group in GROUP_ORDER
        if group in group_members
    ] + [
        {"id": group, "clips": members}
        for group, members in group_members.items()
        if group not in GROUP_ORDER
    ]

    manifest = {
        "version": 2,
        "fps": args.fps,
        "source": "PC2005-cloud/dsh-pet",
        "width": clips[0]["width"] if clips else 0,
        "height": clips[0]["height"] if clips else 0,
        "clips": clips,
        "groups": groups,
        "roles": roles,
    }
    (out / "pet-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
    )

    notice = Path(__file__).resolve().parent.parent / "THIRD_PARTY_NOTICES.md"
    if notice.is_file():
        (out / "LICENSE.txt").write_text(
            "美术素材来自 PC2005-cloud/dsh-pet（代码 MIT，素材禁止商用）。\n"
            "详见仓库根目录 THIRD_PARTY_NOTICES.md。\n",
            encoding="utf-8",
        )

    print(
        f"\n{len(clips)} 个 clip / {sum(c['frames'] for c in clips)} 帧 / "
        f"{total_blob / 1024 / 1024:.2f} MB → {out}"
    )
    if roles:
        print("角色：" + ", ".join(f"{k}={len(v)}" for k, v in roles.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
