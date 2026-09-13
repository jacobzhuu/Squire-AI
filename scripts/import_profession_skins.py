"""Repack the user-approved preview sheets into classic 64x64 Minecraft UVs.

Run from any directory with Python + Pillow. Originals in skin/ are never changed.
The previews are not uniform UV sheets: crop each visible face separately. Hidden
faces reuse the supplied side/back material; no black preview background is used
as an outer layer. Output is intentionally a standard four-pixel-arm skin.
"""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/main/resources/assets/squire/textures/entity/avatar"
# Face rectangles measured in the original 1254x1254 preview images.
SOURCES = {
    "common": dict(
        head=(158,158,314,313), hair=(158,4,313,155), head_side=(315,158,470,313),
        body=(392,314,548,627), back=(648,393,783,627), side=(316,393,389,627),
        arm=(80,393,155,627), arm_back=(236,393,312,627),
        leg=(80,706,156,940), leg_back=(236,706,312,940)),
    "guard": dict(
        head=(184,147,361,291), hair=(186,7,359,144), head_side=(363,148,495,290),
        body=(377,295,567,576), back=(694,295,840,576), side=(570,366,615,575),
        arm=(46,365,102,575), arm_back=(249,365,311,575),
        leg=(106,578,178,749), leg_back=(180,578,245,749)),
    "engineer": dict(
        head=(176,177,372,373), hair=(177,1,370,175), head_side=(374,178,508,373),
        body=(276,393,527,770), back=(748,413,977,762), side=(750,416,796,761),
        arm=(22,394,135,762), arm_back=(565,394,703,762),
        leg=(22,788,136,1184), leg_back=(435,827,529,1184)),
}


def patch(source, rect, size, resample=Image.Resampling.NEAREST):
    p = source.crop(rect).resize(size, resample).convert("RGBA")
    # Base skin faces must be opaque. Replace accidental preview-background
    # samples with the nearest actual material sample from this same face.
    good = [(x, y) for y in range(p.height) for x in range(p.width)
            if p.getpixel((x, y))[3] > 128 and max(p.getpixel((x, y))[:3]) > 20]
    if not good:
        raise ValueError(f"Empty face at {rect}")
    for y in range(p.height):
        for x in range(p.width):
            c = p.getpixel((x, y))
            if c[3] <= 128 or max(c[:3]) <= 20:
                q = min(good, key=lambda q: (q[0]-x)**2 + (q[1]-y)**2)
                c = p.getpixel(q)
            p.putpixel((x, y), (*c[:3], 255))
    return p


def cuboid(skin, uv, dimensions, front, back, right, left, top, bottom):
    u, v = uv
    w, h, d = dimensions
    for face, box in [(top,(u+d,v,w,d)), (bottom,(u+d+w,v,w,d)),
                      (right,(u,v+d,d,h)), (front,(u+d,v+d,w,h)),
                      (left,(u+d+w,v+d,d,h)), (back,(u+2*d+w,v+d,w,h))]:
        x,y,fw,fh = box
        skin.paste(face.resize((fw,fh),Image.Resampling.NEAREST),(x,y))


def convert(name, spec):
    source = Image.open(ROOT / "skin" / f"{name}.png").convert("RGBA")
    if source.size != (1254,1254):
        raise ValueError(f"{name}: source changed; remeasure face crops")
    skin = Image.new("RGBA",(64,64))
    # Average the face cells: uneven source pixels can otherwise omit an eye
    # completely when its pupil falls between nearest-neighbour sample points.
    head = patch(source,spec["head"],(8,8),Image.Resampling.BOX)
    hair = patch(source,spec["hair"],(8,8))
    side = patch(source,spec["head_side"],(8,8))
    cuboid(skin,(0,0),(8,8,8),head,hair,side,side.transpose(Image.Transpose.FLIP_LEFT_RIGHT),hair,head.crop((0,7,8,8)))
    body = patch(source,spec["body"],(8,12))
    back = patch(source,spec["back"],(8,12))
    side = patch(source,spec["side"],(4,12))
    cuboid(skin,(16,16),(8,12,4),body,back,side,side,body.crop((0,0,8,1)),body.crop((0,11,8,12)))
    for kind, positions in [("arm",[(40,16),(32,48)]),("leg",[(0,16),(16,48)])]:
        front = patch(source,spec[kind],(4,12))
        back = patch(source,spec[kind+"_back"],(4,12))
        for i,uv in enumerate(positions):
            f = front if i == 0 else front.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            cuboid(skin,uv,(4,12,4),f,back,back,back,f.crop((0,0,4,1)),f.crop((0,11,4,12)))
    if name == "engineer":
        # The goggles are a separate strip on the preview, above its black hole.
        goggles = patch(source,(800,177,976,254),(8,3))
        skin.paste(goggles,(40,8))
    OUT.mkdir(parents=True,exist_ok=True)
    skin.save(OUT / f"{name}.png")
    return skin


def preview(skins):
    canvas = Image.new("RGB",(720,440),(36,39,46))
    draw = ImageDraw.Draw(canvas)
    for i,(name,skin) in enumerate(skins.items()):
        draw.text((i*240+30,12),name,fill="white")
        for j,back in enumerate([False,True]):
            figure=Image.new("RGBA",(16,32))
            parts = [((24,8,32,16) if back else (8,8,16,16),(4,0)),
                     ((32,20,40,32) if back else (20,20,28,32),(4,8)),
                     ((52,20,56,32) if back else (44,20,48,32),(0,8)),
                     ((44,52,48,64) if back else (36,52,40,64),(12,8)),
                     ((12,20,16,32) if back else (4,20,8,32),(4,20)),
                     ((28,52,32,64) if back else (20,52,24,64),(8,20))]
            for box,pos in parts:figure.alpha_composite(skin.crop(box),pos)
            if not back:figure.alpha_composite(skin.crop((40,8,48,16)),(4,0))
            figure=figure.resize((96,192),Image.Resampling.NEAREST)
            canvas.paste(figure,(i*240+j*112+12,40),figure)
        sheet=skin.resize((192,192),Image.Resampling.NEAREST)
        canvas.paste(sheet,(i*240+24,244),sheet)
    path=ROOT / "build/skin-import-preview.png"
    path.parent.mkdir(exist_ok=True)
    canvas.save(path)


if __name__ == "__main__":
    preview({name:convert(name,spec) for name,spec in SOURCES.items()})

