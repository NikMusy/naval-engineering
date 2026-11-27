"""Generate original 16 px block textures and the mod icon. Requires Pillow."""
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/naval_engineering"
PALETTE = {
    "plating": ((49, 66, 76), "#667f8f", "#92a9ae"),
    "helm": ((40, 54, 57), "#6e9593", "#d7ae6e"),
    "diesel": ((42, 54, 52), "#558a73", "#a6ccb4"),
    "propeller": ((48, 51, 59), "#726452", "#d5b779"),
    "tank": ((54, 55, 50), "#93814d", "#d4b059"),
    "pump": ((34, 54, 68), "#416f8a", "#72c8cb"),
    "navy_panel": ((36, 53, 66), "#405868", "#8a989c"),
    "ivory_panel": ((194, 202, 198), "#d7ddda", "#889a9e"),
    "red_primer": ((112, 47, 48), "#975755", "#b18877"),
    "berth": ((58, 76, 83), "#879b9b", "#d0d0bd"),
    "locker": ((69, 89, 88), "#879f97", "#c3ab79"),
}


def tile(name, top):
    base, frame, accent = PALETTE[name]
    rng = Random(f"{name}:{top}")
    image = Image.new("RGB", (16, 16))
    for y in range(16):
        for x in range(16):
            d = rng.choice((-5, -3, -1, 0, 1, 4, 5))
            image.putpixel((x, y), tuple(c + d for c in base))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 15, 15), outline="#1a2931")
    draw.line((1, 1, 14, 1), fill=frame)
    draw.line((1, 2, 1, 14), fill=frame)
    draw.line((2, 14, 14, 14), fill="#26333a")
    for x in (2, 13):
        for y in (2, 13):
            draw.point((x, y), fill=accent)
            draw.point((x, y + 1), fill="#1b282e")

    if name in {"navy_panel", "ivory_panel", "red_primer"}:
        seam = tuple(max(0, c - 17) for c in base)
        draw.line((0, 0, 15, 0, 15, 15), fill=seam)
        draw.line((0, 15, 0, 1), fill=seam)
        draw.line((2, 1, 14, 1), fill=frame)
        draw.line((1, 2, 1, 14), fill=frame)
        draw.line((2, 14, 14, 14), fill=seam)
        for x in (2, 13):
            for y in (2, 13):
                draw.point((x, y + 1), fill=seam)

    if name == "plating":
        seam = 10 if top else 11
        draw.line((2, seam, 13, seam), fill="#2a3a42")
        draw.line((2, seam + 1, 13, seam + 1), fill=frame)
        draw.line((3, 4, 11, 4), fill="#415867")
    elif name == "helm":
        if top:
            draw.ellipse((3, 3, 12, 12), outline=accent)
            for end in [(2, 7), (13, 7), (7, 2), (7, 13)]:
                draw.line((7, 7, *end), fill=accent)
            draw.rectangle((6, 6, 8, 8), fill="#667f89")
        else:
            draw.rectangle((3, 3, 12, 9), fill="#16272b", outline=frame)
            draw.line((5, 6, 10, 6), fill="#75d1be")
            draw.point((6, 8), fill=accent)
            draw.point((9, 8), fill=accent)
    elif name == "diesel":
        draw.rectangle((3, 4, 12, 11), fill="#283b38", outline=frame)
        for x in range(4, 13, 2):
            draw.line((x, 4, x, 10), fill=accent if top else frame)
        draw.line((3, 12, 12, 12), fill="#253637")
    elif name == "propeller":
        draw.ellipse((3, 3, 12, 12), fill="#333038", outline=frame)
        for end in [(4, 6), (9, 4), (11, 9), (6, 11)]:
            draw.line((7, 7, *end), fill=accent, width=2)
        draw.rectangle((7, 7, 8, 8), fill=frame)
    elif name == "tank":
        draw.rounded_rectangle((3, 4, 12, 11), radius=2, fill=frame, outline=accent)
        if top:
            draw.rectangle((6, 6, 9, 9), fill="#494039", outline="#f3cc73")
        else:
            draw.line((4, 8, 11, 8), fill="#514736")
            draw.line((8, 5, 8, 10), fill="#e8bc61")
    elif name == "pump":
        draw.ellipse((3, 3, 12, 12), fill="#1f303e", outline=frame)
        draw.line((3, 9, 7, 9, 7, 6, 12, 6), fill=accent, width=2)
        draw.point((10, 9), fill=accent)
    elif name == "berth":
        if top:
            draw.rectangle((2, 2, 13, 13), fill="#47666f", outline="#758e95")
            draw.rounded_rectangle((3, 3, 12, 6), radius=1, fill="#ded8be")
            draw.line((3, 10, 12, 10), fill="#87ada9")
        else:
            draw.rectangle((2, 9, 13, 12), fill="#243844", outline=frame)
            draw.line((3, 10, 12, 10), fill=accent)
    elif name == "locker":
        draw.rectangle((3, 3, 12, 13), outline=frame)
        for y in range(4, 8):
            draw.line((5, y, 10, y), fill="#384c50" if y % 2 == 0 else frame)
        draw.line((10, 9, 10, 11), fill=accent)
    return image


def main():
    dst = ROOT / "textures/block"
    dst.mkdir(parents=True, exist_ok=True)
    for name in PALETTE:
        tile(name, False).save(dst / f"{name}.png")
        tile(name, True).save(dst / f"{name}_top.png")
    icon = Image.new("RGB", (32, 32), "#132731")
    draw = ImageDraw.Draw(icon)
    draw.rectangle((1, 1, 30, 30), outline="#496975")
    draw.polygon([(4, 17), (28, 17), (23, 23), (9, 23)], fill="#c2dabf")
    draw.rectangle((13, 12, 22, 16), fill="#c2dabf")
    draw.rectangle((16, 8, 18, 12), fill="#b4d6b9")
    draw.line([(4, 26), (10, 25), (16, 27), (22, 25), (28, 26)], fill="#6abbbe", width=2)
    icon.resize((128, 128), Image.Resampling.NEAREST).save(ROOT / "icon.png")


if __name__ == "__main__":
    main()
