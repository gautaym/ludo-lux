"""Contact sheet of rendered frames: python tools/sheet.py <folder> <first> <last> <step> <out.png> [cols] [crop y0,y1 fractions]"""
import sys, glob, os
from PIL import Image, ImageDraw
folder, a, b, step, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
cols = int(sys.argv[6]) if len(sys.argv) > 6 else 4
crop = [float(v) for v in sys.argv[7].split(",")] if len(sys.argv) > 7 else [0.0, 1.0]
ims = []
for i in range(a, b + 1, step):
    p = os.path.join(folder, "f%04d.png" % i)
    if not os.path.exists(p): break
    im = Image.open(p).convert("RGB"); w, h = im.size
    im = im.crop((0, int(h * crop[0]), w, int(h * crop[1])))
    d = ImageDraw.Draw(im); d.text((6, 4), str(i), fill=(255, 255, 0))
    ims.append(im)
w, h = ims[0].size
rows = (len(ims) + cols - 1) // cols
sheet = Image.new("RGB", (w * cols, h * rows))
for k, im in enumerate(ims): sheet.paste(im, ((k % cols) * w, (k // cols) * h))
sheet.save(out); print(out, sheet.size)
