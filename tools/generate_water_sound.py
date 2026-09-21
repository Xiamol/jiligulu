"""喝水「咕噜咕噜」音效：从 CC0 公共领域素材剪接而成。

素材来源（均为 CC0 / 公共领域，可自由商用与再分发，无需署名）：
  https://bigsoundbank.com/sound-0150-water-bubbles.html  (Water bubbles, 吸管吹杯里的水泡) ★当前使用
  https://bigsoundbank.com/sound-3427-blop-d-eau-1.html   (Water Blop #1, 单次水下咕咚)
  https://bigsoundbank.com/sound-0183-water-bubble-2.html  (Water Bubble #2, 浴缸里的水泡)
作者 Joseph SARDIN (LaSonotheque) 以 CC0 授权发布。

脚本只做剪接/淡入淡出/归一化，不引入任何需要署名的素材。
原始 mp3 放在 tools/sounds_raw/，本脚本输出 44.1kHz 单声道 16bit WAV 到 res/raw。
"""
import os
import sys
import wave
from pathlib import Path

import numpy as np
import soundfile as sf

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / "tools/sounds_raw"
DEST = ROOT / "app/src/main/res/raw"

BLOP = "bsb3427_blop1.mp3"
BUBBLE_A = "bsb0150_bubbles_straw.mp3"
BUBBLE_B = "bsb0183_bubble2.mp3"

# 当前选用 BUBBLE_A（coder 2026-09-22 选定方案 A）
OUT_RATE = 44100


def load(name: str) -> tuple[np.ndarray, int]:
    path = RAW / name
    if not path.exists():
        sys.exit(f"缺少素材 {path}，请先下载 CC0 音效到 tools/sounds_raw/")
    data, rate = sf.read(str(path))
    if data.ndim > 1:
        data = data[:, 0]
    return data.astype(np.float64), rate


def resample(x: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    if src_rate == dst_rate:
        return x
    n = int(round(len(x) * dst_rate / src_rate))
    return np.interp(np.linspace(0, 1, n), np.linspace(0, 1, len(x)), x)


def fade(x: np.ndarray, rate: int, ms: float = 8.0) -> np.ndarray:
    n = min(int(rate * ms / 1000), len(x) // 4)
    if n < 1:
        return x
    out = x.copy()
    out[:n] *= np.linspace(0.0, 1.0, n)
    out[-n:] *= np.linspace(1.0, 0.0, n)
    return out


def normalize(x: np.ndarray, peak: float = 0.88) -> np.ndarray:
    top = float(np.max(np.abs(x)))
    return x * (peak / top) if top > 0 else x


def lowpass(x: np.ndarray, rate: int, cutoff: float) -> np.ndarray:
    """一阶 IIR 低通，用来把气泡素材的「嘶嘶」高频压下去，更像喉咙里的咕噜。"""
    alpha = 1.0 - np.exp(-2.0 * np.pi * cutoff / rate)
    out = np.empty_like(x)
    acc = 0.0
    for i, sample in enumerate(x):
        acc += alpha * (sample - acc)
        out[i] = acc
    return out


def grab(x: np.ndarray, rate: int, start_s: float, dur_s: float) -> np.ndarray:
    start = int(start_s * rate)
    return x[start:start + int(dur_s * rate)]


def main() -> None:
    DEST.mkdir(parents=True, exist_ok=True)
    bubble, bubble_rate = load(BUBBLE_A)

    # 方案 A：0150 吸管吹杯里水泡的连续气泡段。
    # coder 选定的就是这个音色，所以只做低通 + 提响度，不再叠合成层。
    seg = resample(grab(bubble, bubble_rate, 2.05, 0.78), bubble_rate, OUT_RATE)
    # 低通到 3kHz：保留气泡的「咕噜」质感，压掉吸管的高频嘶声。
    track = lowpass(seg, OUT_RATE, 3000.0)
    # 两端淡入淡出，避免播放时爆音。
    track = fade(normalize(track, peak=0.99), OUT_RATE, 8.0)

    target = DEST / "water_glug.wav"
    with wave.open(str(target), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(OUT_RATE)
        out.writeframes((track * 32767).astype("<i2").tobytes())
    print(f"wrote {target} ({os.path.getsize(target)} bytes, {len(track) / OUT_RATE:.2f}s)")


if __name__ == "__main__":
    main()
