"""Original, deterministic keyboard taps. No downloaded recording or third-party sound license."""
import math
import random
import struct
import wave
from pathlib import Path

destination = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"
destination.mkdir(parents=True, exist_ok=True)
rate = 44100
for variant in range(1, 4):
    rng = random.Random(9500 + variant)
    frames = []
    previous = 0.0
    for index in range(int(rate * 0.045)):
        t = index / rate
        noise = rng.uniform(-1, 1)
        high = noise - previous * 0.85
        previous = noise
        front = math.exp(-t * 320)
        release = math.exp(-max(t - 0.010, 0) * 380) if t >= 0.010 else 0
        click = high * (front * 0.22 + release * 0.10)
        # 更清脆的主频：提高到 1800-2200Hz，去掉低沉的 190Hz 尾音
        click += math.sin(2 * math.pi * (1800 + variant * 220) * t) * math.exp(-t * 220) * 0.15
        click += math.sin(2 * math.pi * (3600 + variant * 300) * t) * math.exp(-t * 350) * 0.06
        click *= min(t / 0.0005, 1)
        frames.append(struct.pack("<h", int(max(-1, min(1, click)) * 32767)))
    with wave.open(str(destination / f"key_tap_{variant}.wav"), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(rate)
        output.writeframes(b"".join(frames))
