"""Create the original, seamless mono Vorbis assets. Requires numpy and soundfile."""
from pathlib import Path

import numpy as np
import soundfile as sf


RATE = 22050
ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/naval_engineering/sounds"


def write(name, samples):
    sf.write(ROOT / f"{name}.ogg", samples, RATE, format="OGG", subtype="VORBIS")


def diesel():
    seconds = 2
    t = np.arange(RATE * seconds) / RATE
    beat = 24 * 2 * np.pi * t
    firing = sum(np.sin(beat * n + n * .7) / n ** .77 for n in range(1, 12))
    head = np.sin(2 * np.pi * 72 * t) * (.84 + .16 * np.cos(2 * np.pi * 6 * t))
    return .24 * np.tanh(firing * .62) + .04 * head


def sea():
    seconds = 8
    n = seconds * RATE
    rng = np.random.default_rng(16290)
    frequency = np.fft.rfftfreq(n, 1 / RATE)
    spectrum = np.fft.rfft(rng.normal(size=n))
    shape = frequency / (frequency + 40) / (1 + (frequency / 340) ** 1.52)
    hiss = np.fft.irfft(spectrum * shape, n=n)
    t = np.arange(n) / RATE
    swell = .43 + .25 * np.sin(2 * np.pi * t * 3 / seconds) + .15 * np.cos(2 * np.pi * t * 5 / seconds)
    return np.tanh(hiss * (1.4 + 3.2 * swell)) * .50


if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    write("engine_loop", diesel())
    write("ocean_loop", sea())

