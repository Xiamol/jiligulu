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
    for index in range(int(rate * 0.055)):
        t = index / rate
        noise = rng.uniform(-1, 1)
        high = noise - previous * 0.8
        previous = noise
        front = math.exp(-t * 250)
        release = math.exp(-max(t - 0.014, 0) * 280) if t >= 0.014 else 0
        click = high * (front * 0.25 + release * 0.13)
        click += math.sin(2 * math.pi * (1400 + variant * 170) * t) * math.exp(-t * 160) * 0.13
        click += math.sin(2 * math.pi * 190 * t) * math.exp(-t * 100) * 0.09
        click *= min(t / 0.0007, 1)
        frames.append(struct.pack("<h", int(max(-1, min(1, click)) * 32767)))
    with wave.open(str(destination / f"key_tap_{variant}.wav"), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(rate)
        output.writeframes(b"".join(frames))
