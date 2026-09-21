"""Glug-glug drinking sound. Original, deterministic synthesis."""
import math
import random
import struct
import wave
from pathlib import Path

destination = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"
destination.mkdir(parents=True, exist_ok=True)
rate = 44100
rng = random.Random(7100)

# 三段「咕噜」，每段约 0.28s，间隔 0.06s
total = int(rate * 1.1)
frames = [0.0] * total

def glug(start_s: float, pitch: float, volume: float):
    start = int(start_s * rate)
    length = int(0.22 * rate)
    for i in range(length):
        t = i / rate
        idx = start + i
        if idx >= total:
            break
        # 气泡感：快速衰减的正弦 + 一点噪声
        env = math.sin(math.pi * min(t / 0.22, 1.0)) ** 1.5
        body = math.sin(2 * math.pi * pitch * t) * env
        body += math.sin(2 * math.pi * pitch * 2.7 * t) * env * 0.3
        body += rng.uniform(-0.08, 0.08) * env * 0.4
        frames[idx] += body * volume

glug(0.05, 320, 0.5)
glug(0.38, 300, 0.45)
glug(0.71, 340, 0.4)

with wave.open(str(destination / "water_glug.wav"), "wb") as output:
    output.setnchannels(1)
    output.setsampwidth(2)
    output.setframerate(rate)
    output.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, f)) * 32767)) for f in frames))
