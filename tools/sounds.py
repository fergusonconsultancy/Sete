"""Synthesises Sete's four sounds from scratch. Run: python3 tools/sounds.py
Every sound is water or touch: nothing is sampled, so nothing is borrowed."""
import numpy as np, wave, os
SR = 44100
rng = np.random.default_rng(7)
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

def save(name, x, peak=0.5):
    x = x - np.mean(x)
    fade = int(SR * 0.004)
    x[:fade] *= np.linspace(0, 1, fade); x[-fade:] *= np.linspace(1, 0, fade)
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())

def t(d): return np.arange(int(SR * d)) / SR

def bubble(f0, d=0.09, rise=1.9, decay=38.0):
    """A bubble's ring: its pitch rises as it shrinks, and it dies away fast."""
    tt = t(d)
    f = f0 * (1 + rise * tt / d * 0.6)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * np.exp(-decay * tt) * (1 - np.exp(-tt * 900))

def lowpass(x, a):
    y = np.zeros_like(x); acc = 0.0
    for i, v in enumerate(x): acc += a * (v - acc); y[i] = acc
    return y

# 1. Plip: one drop landing in water
plip = np.zeros(int(SR * 0.22))
b = bubble(800, d=0.13, rise=2.0, decay=32)
plip[:len(b)] += b
splash = lowpass(rng.standard_normal(int(SR * 0.02)), 0.35) * np.exp(-t(0.02) * 220) * 0.25
plip[:len(splash)] += splash
save("plip", plip, 0.55)

# 2. Gurgle: water poured into a pot. The pitch climbs as the pot fills, the way a filling glass does.
d = 0.85
g = np.zeros(int(SR * d))
bed = lowpass(rng.standard_normal(len(g)), 0.08)
env = np.minimum(1, t(d) / 0.08) * np.exp(-np.maximum(0, t(d) - 0.55) * 9)
g += bed * env * 0.2
for i in range(11):
    start = 0.03 + i * 0.062 + rng.uniform(-0.012, 0.012)
    f0 = 330 + i * 46 + rng.uniform(-40, 40)
    bb = bubble(f0, d=0.08, rise=1.5, decay=46) * rng.uniform(0.45, 0.9)
    s = int(start * SR); g[s:s + len(bb)] += bb[: len(g) - s]
save("gurgle", g, 0.42)

# 3. Tap: a finger pressed into damp soil. Low, soft, short.
tap = np.sin(2 * np.pi * np.cumsum(170 * np.exp(-t(0.16) * 9)) / SR) * np.exp(-t(0.16) * 30)
tap += lowpass(rng.standard_normal(len(tap)), 0.12) * np.exp(-t(0.16) * 90) * 0.5
save("tap", tap, 0.5)

# 4. Chime: a round finished. Two glass notes a sixth apart, struck lightly.
def bell(f, d=1.3, decay=3.2):
    tt = t(d)
    partials = [(1, 1.0), (2.76, 0.32), (5.4, 0.12), (8.9, 0.05)]
    x = sum(a * np.sin(2 * np.pi * f * p * tt) * np.exp(-decay * p ** 0.6 * tt) for p, a in partials)
    return x * (1 - np.exp(-tt * 1400))
ch = np.zeros(int(SR * 1.5))
n1 = bell(1046.5); ch[:len(n1)] += n1
n2 = bell(1568.0) * 0.85; s = int(SR * 0.14); ch[s:s + len(n2)] += n2[: len(ch) - s]
save("chime", ch, 0.4)
print("ok", sorted(os.listdir(OUT)))
