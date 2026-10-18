"""从 design/ 里的原图生成 app 用的默认开屏图和图标（需要 Pillow：pip install pillow）。

    python design/make_assets.py

输出：
  app/src/main/res/drawable-nodpi/splash_art.webp    开屏图：裁掉上下空白，四边渐变融进开屏底色
  app/src/main/res/drawable-nodpi/ic_launcher_art.png 图标：只取门，放在 108dp 画布的安全区里，
                                                     桌面会自己套圆角方块或圆形
  app/src/main/res/drawable-xxxhdpi/ic_notification.png 通知栏小图标：门的白色剪影（安卓只认单色），96 像素=24dp
  docs/icon.png                                       README 顶上的图标：上面那个图标套圆角方块
换图的话替换 design/ 里的两张原图；构图不一样时要改下面的裁剪坐标。
"""
import os
import warnings
from PIL import Image, ImageChops, ImageDraw, ImageFilter

warnings.filterwarnings('ignore', category=DeprecationWarning)  # Pillow 12 提示 putdata 改名，不影响结果

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DESIGN = os.path.join(ROOT, 'design')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')

SPLASH_BG = (1, 16, 37)   # 开屏底色，和 values/colors 里的 splash_bg 一致
ICON_BG = (2, 18, 33)     # 图标底色，和 icon_bg 一致


def feather(img, bg, side, top_bottom):
    """把图片四边按比例渐变融进纯色底，贴在同色底上看不出接缝。"""
    w, h = img.size

    def ramp(n, fade):
        out = []
        for i in range(n):
            d = min(i, n - 1 - i)
            out.append(255 if fade == 0 or d >= fade else int(255 * (d / fade) ** 1.5))
        return out

    across = Image.new('L', (w, 1))
    across.putdata(ramp(w, int(w * side)))
    down = Image.new('L', (1, h))
    down.putdata(ramp(h, int(h * top_bottom)))
    # 两个方向各自渐变，角上取更透明的那个
    mask = ImageChops.darker(across.resize((w, h), Image.NEAREST), down.resize((w, h), Image.NEAREST))
    out = Image.new('RGB', (w, h), bg)
    out.paste(img, (0, 0), mask)
    return out


def splash():
    src = Image.open(os.path.join(DESIGN, 'splash-default-source.jpg')).convert('RGB')
    w, h = src.size
    # 图案在原图纵向 10.1%–86.3%，上下各留一点再裁
    art = feather(src.crop((0, int(h * 0.075), w, int(h * 0.895))), SPLASH_BG, 0.06, 0.08)
    out = os.path.join(RES, 'drawable-nodpi', 'splash_art.webp')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    art.save(out, 'WEBP', quality=92, method=6)
    return out, art.size


def icon(size=768):
    src = Image.open(os.path.join(DESIGN, 'icon-default-source.webp')).convert('RGB')
    # 门在原图里约 x 315–960、y 145–1105；取门周围一圈（不碰原图自带的粉框），左右渐变融进底色
    door = feather(src.crop((200, 110, 1075, 1140)), ICON_BG, 0.13, 0.035)
    # 自适应图标画布 108dp，门的外接框对角线收进中间 66dp 的安全圆，圆形桌面也不会切到门
    canvas = 1080
    scale = 0.569
    layer = Image.new('RGB', (canvas, canvas), ICON_BG)
    dw, dh = int(door.size[0] * scale), int(door.size[1] * scale)
    layer.paste(door.resize((dw, dh), Image.LANCZOS), ((canvas - dw) // 2, (canvas - dh) // 2))
    out = os.path.join(RES, 'drawable-nodpi', 'ic_launcher_art.png')
    layer.resize((size, size), Image.LANCZOS).save(out, optimize=True)
    return out, (size, size)


def notification_icon(size=96):
    """通知栏小图标：安卓只认单色剪影（按透明度画、系统自己上色），所以取门的轮廓做成白色剪影。"""
    src = Image.open(os.path.join(DESIGN, 'icon-default-source.webp')).convert('RGB')
    door = src.crop((300, 130, 975, 1120))               # 门本身，不带周围的底色
    # 门板比底色亮：按亮度取出门的形状；门板是半透明叠着的，中间有暗斑，先「闭合」（膨胀再收缩）
    # 把门板里的小洞补上（中间那道门洞够宽，补不上），再磨掉零星噪点，边缘留一点抗锯齿
    # 先缩到三分之一再处理（图标最后才 80 像素高，够用，也快得多）
    door = door.resize((door.size[0] // 3, door.size[1] // 3), Image.BOX)
    shape = door.convert('L').point(lambda v: 255 if v > 34 else 0)
    shape = shape.filter(ImageFilter.MaxFilter(17)).filter(ImageFilter.MinFilter(17))
    shape = shape.filter(ImageFilter.MedianFilter(3)).filter(ImageFilter.GaussianBlur(1))
    shape = shape.point(lambda v: 255 if v > 128 else 0)
    shape = shape.crop(shape.getbbox())
    # 24dp 的图标四周留 2dp：门的高度占 20/24
    target = size * 20 // 24
    w, h = shape.size
    scale = target / max(w, h)
    shape = shape.resize((max(1, int(w * scale)), max(1, int(h * scale))), Image.LANCZOS)
    alpha = Image.new('L', (size, size), 0)
    alpha.paste(shape, ((size - shape.size[0]) // 2, (size - shape.size[1]) // 2))
    out_img = Image.new('RGBA', (size, size), (255, 255, 255, 0))
    out_img.putalpha(alpha)
    out = os.path.join(RES, 'drawable-xxxhdpi', 'ic_notification.png')  # 96 像素 = 4 倍密度下的 24dp
    os.makedirs(os.path.dirname(out), exist_ok=True)
    out_img.save(out, optimize=True)
    return out, (size, size)


def readme_icon(size=192):
    """README 顶上的图标：取自适应图标中间桌面上露出来的 72dp，套圆角方块（桌面上看起来大致就这样）。"""
    art = Image.open(os.path.join(RES, 'drawable-nodpi', 'ic_launcher_art.png')).convert('RGB')
    w = art.size[0]
    inner = w * 72 // 108
    off = (w - inner) // 2
    face = art.crop((off, off, off + inner, off + inner)).resize((size, size), Image.LANCZOS)
    big = size * 4  # 圆角先画 4 倍大再缩小，边缘才平滑
    mask = Image.new('L', (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius=int(big * 0.23), fill=255)
    out_img = face.convert('RGBA')
    out_img.putalpha(mask.resize((size, size), Image.LANCZOS))
    out = os.path.join(ROOT, 'docs', 'icon.png')
    out_img.save(out, optimize=True)
    return out, (size, size)


if __name__ == '__main__':
    # readme_icon 读 icon() 刚生成的图，要排在它后面
    for path, dims in (splash(), icon(), notification_icon(), readme_icon()):
        print(f'{os.path.relpath(path, ROOT)}  {dims[0]}x{dims[1]}  {os.path.getsize(path) // 1024} KB')
