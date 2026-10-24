from PIL import Image, ImageDraw, ImageFont
import numpy as np
from pathlib import Path
import sys
project = Path(__file__).resolve().parents[1]
workspace = project.parent.parent
if len(sys.argv) < 2:
    raise SystemExit('Usage: python make_logo.py <source-image>')
source = Path(sys.argv[1])
outputs = [
    (workspace / 'outputs' / 'JcrwTranslator-logo.png', 1024),
    (project / 'app/src/main/res/drawable/app_logo.png', 512),
    (project / 'app/src/main/res/drawable-nodpi/app_logo.png', 1200),
]
im = Image.open(source).convert('RGB')
a = np.asarray(im).astype(np.float32)
h, w, _ = a.shape
y, x = np.mgrid[0:h, 0:w]
r, g, b = a[:,:,0], a[:,:,1], a[:,:,2]
# Keep the controller shell white; recolor its bright blue accents and rings pink.
controller = (y >= h*.49) & (x >= w*.12) & (x <= w*.88)
blue_trim = controller & (y > h*.51) & (b > 120) & (b > r*1.25) & (b > g*1.12)
light = (r+g+b)/3.0
a[:,:,0][blue_trim] = np.minimum(255, light[blue_trim]*1.02)
a[:,:,1][blue_trim] = np.minimum(255, light[blue_trim]*.49)
a[:,:,2][blue_trim] = np.minimum(255, light[blue_trim]*.88+8)
im = Image.fromarray(np.uint8(np.clip(a,0,255)))
draw = ImageDraw.Draw(im)
font = ImageFont.truetype(r'C:\Windows\Fonts\seguisb.ttf', int(w*.041))
label='TRANSLATOR'
text_box=draw.textbbox((0,0),label,font=font)
tw=text_box[2]-text_box[0]; th=text_box[3]-text_box[1]
center_x=w/2; center_y=h*.632
draw.rounded_rectangle((center_x-w*.07,h*.585,center_x+w*.07,h*.589),radius=max(1,int(w*.002)),fill=(238,83,174))
draw.text((center_x-tw/2,center_y-th/2),label,font=font,fill=(9,32,76))
for path,size in outputs:
    path.parent.mkdir(parents=True,exist_ok=True)
    im.resize((size,size),Image.Resampling.LANCZOS).save(path,optimize=True)
print(f'{w}x{h}; wrote logo sizes 1024, 512, 1200')
