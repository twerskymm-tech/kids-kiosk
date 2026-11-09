# Builds the Shabbos / Yom Tov backgrounds as SVG (viewBox 360x780, phone portrait).
# Focal art sits in the band y=430..700, between the zmanim box and the call buttons.
import random, os, sys

OUT = sys.argv[1]
W, H = 360, 780

def stars(n, seed, ymax=420, color="#fff"):
    r = random.Random(seed)
    s = []
    for _ in range(n):
        x, y = r.uniform(0, W), r.uniform(0, ymax)
        rad = r.choice([0.6, 0.8, 1.0, 1.3])
        op = r.uniform(0.35, 0.9)
        s.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{rad}" fill="{color}" opacity="{op:.2f}"/>')
    return "".join(s)

def svg(defs, body):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}"><defs>{defs}</defs>{body}</svg>'

def bg(id, top, mid, bot):
    return f'<linearGradient id="{id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{top}"/><stop offset="0.55" stop-color="{mid}"/><stop offset="1" stop-color="{bot}"/></linearGradient>'

GLOW = '<radialGradient id="glow"><stop offset="0" stop-color="#FFE9A8" stop-opacity="0.95"/><stop offset="0.35" stop-color="#FFC75A" stop-opacity="0.45"/><stop offset="1" stop-color="#FFB13B" stop-opacity="0"/></radialGradient>'
FLAME = '<radialGradient id="flame" cx="0.5" cy="0.7" r="0.6"><stop offset="0" stop-color="#FFFBE8"/><stop offset="0.45" stop-color="#FFD25A"/><stop offset="1" stop-color="#FF8A1F"/></radialGradient>'

def candle(x, base, h=70, w=14, color="#FBF6EC"):
    top = base - h
    return (f'<circle cx="{x}" cy="{top-14}" r="46" fill="url(#glow)"/>'
            f'<rect x="{x-w/2}" y="{top}" width="{w}" height="{h}" rx="3" fill="{color}"/>'
            f'<rect x="{x-w/2}" y="{top}" width="{w/3}" height="{h}" rx="2" fill="#ffffff" opacity="0.5"/>'
            f'<line x1="{x}" y1="{top}" x2="{x}" y2="{top-5}" stroke="#3a2a1a" stroke-width="1.4"/>'
            f'<path d="M{x},{top-28} C{x+7},{top-17} {x+6},{top-6} {x},{top-4} C{x-6},{top-6} {x-7},{top-17} {x},{top-28}Z" fill="url(#flame)"/>')

def candlestick(x, base):
    # silver candlestick with candle
    s = (f'<path d="M{x-20},{base} Q{x},{base-10} {x+20},{base} Z" fill="#C9CED8"/>'
         f'<rect x="{x-3.5}" y="{base-48}" width="7" height="44" fill="url(#silver)"/>'
         f'<ellipse cx="{x}" cy="{base-26}" rx="7" ry="4" fill="#DDE2EA"/>'
         f'<path d="M{x-13},{base-48} L{x+13},{base-48} L{x+9},{base-56} L{x-9},{base-56} Z" fill="url(#silver)"/>')
    return s + candle(x, base - 56, h=74)

SILVER = '<linearGradient id="silver" x1="0" x2="1"><stop offset="0" stop-color="#8E96A5"/><stop offset="0.45" stop-color="#F3F5F9"/><stop offset="1" stop-color="#9AA2B0"/></linearGradient>'

def challah(cx, cy, scale=1.0):
    parts = []
    for i, dx in enumerate([-42, -21, 0, 21, 42]):
        ry = [11, 15, 17, 15, 11][i] * scale
        rx = 15 * scale
        parts.append(f'<ellipse cx="{cx+dx*scale}" cy="{cy}" rx="{rx}" ry="{ry}" fill="url(#bread)" stroke="#9A5B1E" stroke-width="0.8"/>')
        parts.append(f'<path d="M{cx+dx*scale-8*scale},{cy-ry*0.4} q{8*scale},{-6*scale} {16*scale},0" stroke="#F6D49A" stroke-width="1.6" fill="none" opacity="0.8"/>')
    for i in range(14):
        sx = cx + random.Random(i).uniform(-50, 50) * scale
        sy = cy + random.Random(i + 99).uniform(-10, 4) * scale
        parts.append(f'<ellipse cx="{sx:.1f}" cy="{sy:.1f}" rx="1.1" ry="0.6" fill="#FFF6DD"/>')
    return "".join(parts)

BREAD = '<radialGradient id="bread" cx="0.4" cy="0.35" r="0.8"><stop offset="0" stop-color="#F2B866"/><stop offset="0.6" stop-color="#C97A2C"/><stop offset="1" stop-color="#8E4E17"/></radialGradient>'

def cup(x, base, wine="#7A1230", metal="url(#silver)", s=1.0):
    return (f'<path d="M{x-17*s},{base-62*s} L{x+17*s},{base-62*s} L{x+14*s},{base-38*s} Q{x},{base-28*s} {x-14*s},{base-38*s} Z" fill="{metal}"/>'
            f'<path d="M{x-15*s},{base-58*s} L{x+15*s},{base-58*s} L{x+13*s},{base-41*s} Q{x},{base-33*s} {x-13*s},{base-41*s} Z" fill="{wine}" opacity="0.85"/>'
            f'<rect x="{x-2.5*s}" y="{base-32*s}" width="{5*s}" height="{24*s}" fill="{metal}"/>'
            f'<path d="M{x-14*s},{base} Q{x},{base-14*s} {x+14*s},{base} Z" fill="{metal}"/>')

def table(y, color="#F7F3EA", edge="#E2D9C6"):
    return (f'<path d="M-10,{y} L370,{y} L370,790 L-10,790 Z" fill="{color}"/>'
            f'<path d="M-10,{y} L370,{y} L370,{y+10} L-10,{y+10} Z" fill="{edge}"/>'
            f'<path d="M0,{y+10} Q30,{y+22} 60,{y+10} T120,{y+10} T180,{y+10} T240,{y+10} T300,{y+10} T360,{y+10}" fill="none" stroke="#D8CDB6" stroke-width="2"/>')

def shabbos():
    d = bg("b", "#0E1230", "#2A1F4D", "#4A2A4A") + GLOW + FLAME + SILVER + BREAD
    b = '<rect width="360" height="780" fill="url(#b)"/>' + stars(70, 1)
    b += '<circle cx="290" cy="90" r="26" fill="#FFF3C4" opacity="0.9"/><circle cx="302" cy="82" r="24" fill="#1B1840"/>'
    b += table(640, "#F4EFE4", "#E1D6C1")
    b += candlestick(95, 640) + candlestick(150, 640)
    # challah with embroidered cover partly folded back
    b += challah(252, 614, 1.25)
    b += '<path d="M190,632 Q252,600 314,632 L314,640 L190,640 Z" fill="#2E3B7A" opacity="0.95"/>'
    b += '<path d="M206,634 Q250,606 296,634" stroke="#E7C66B" stroke-width="2" fill="none"/>'
    b += cup(330, 640, s=0.8)
    return svg(d, b)

def rosh_hashana():
    d = bg("b", "#2A0E14", "#5A1A22", "#8A3A1E") + GLOW + '<radialGradient id="apple" cx="0.35" cy="0.3" r="0.8"><stop offset="0" stop-color="#FF6B6B"/><stop offset="0.6" stop-color="#D21F2C"/><stop offset="1" stop-color="#7E0E18"/></radialGradient><linearGradient id="honey" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFD36B"/><stop offset="1" stop-color="#E08A0B"/></linearGradient><linearGradient id="horn" x1="0" x2="1"><stop offset="0" stop-color="#F3E3C3"/><stop offset="0.6" stop-color="#C79A5B"/><stop offset="1" stop-color="#6E4A23"/></linearGradient><radialGradient id="pom" cx="0.4" cy="0.35" r="0.8"><stop offset="0" stop-color="#E04B6A"/><stop offset="1" stop-color="#7A1028"/></radialGradient>'
    b = '<rect width="360" height="780" fill="url(#b)"/>' + stars(40, 2, color="#FFD9A0")
    b += '<circle cx="180" cy="560" r="170" fill="url(#glow)" opacity="0.45"/>'
    b += table(650, "#3B1A12", "#2A120C")
    # shofar across the back
    b += '<path d="M40,560 C90,520 190,520 250,560 C275,577 300,575 320,548 L330,556 C312,592 272,600 240,582 C190,555 100,560 52,588 Z" fill="url(#horn)" stroke="#5A3A1A" stroke-width="1"/>'
    b += '<ellipse cx="325" cy="552" rx="7" ry="10" fill="#3A2410" transform="rotate(35 325 552)"/>'
    # honey jar
    b += '<rect x="168" y="590" width="62" height="60" rx="12" fill="url(#honey)" opacity="0.95"/><rect x="164" y="580" width="70" height="14" rx="5" fill="#B5651D"/><path d="M176,598 q12,10 0,22" stroke="#FFF1C2" stroke-width="3" fill="none" opacity="0.7"/>'
    b += '<line x1="215" y1="575" x2="245" y2="520" stroke="#8B5A2B" stroke-width="5" stroke-linecap="round"/>'
    # apples
    b += '<circle cx="100" cy="618" r="32" fill="url(#apple)"/><path d="M100,588 q2,-10 8,-14" stroke="#5B3A1A" stroke-width="3" fill="none"/><path d="M106,580 q14,-8 22,2 q-12,8 -22,-2Z" fill="#3E9B3E"/>'
    b += '<circle cx="62" cy="636" r="22" fill="url(#apple)"/>'
    # pomegranate
    b += '<circle cx="285" cy="618" r="30" fill="url(#pom)"/><path d="M275,590 l5,-10 l5,8 l5,-8 l5,10 Z" fill="#7A1028"/>'
    for i, (x, y) in enumerate([(276, 612), (288, 622), (296, 608), (282, 630)]):
        b += f'<circle cx="{x}" cy="{y}" r="2.2" fill="#FFB3C1" opacity="0.6"/>'
    return svg(d, b)

def yom_kippur():
    d = bg("b", "#0B1A33", "#1E3A66", "#4F6E9A") + GLOW + FLAME + '<linearGradient id="ray" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.35"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient><linearGradient id="glass" x1="0" x2="1"><stop offset="0" stop-color="#E8F0FF" stop-opacity="0.5"/><stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0.85"/><stop offset="1" stop-color="#C9D7EE" stop-opacity="0.5"/></linearGradient>'
    b = '<rect width="360" height="780" fill="url(#b)"/>' + stars(50, 3)
    for i, a in enumerate(range(-50, 60, 14)):
        b += f'<path d="M180,380 L{180+a*6},{780} L{180+a*6+40},{780} Z" fill="url(#ray)" opacity="0.5"/>'
    # gates of heaven arch
    b += '<path d="M110,700 L110,470 Q180,400 250,470 L250,700" fill="none" stroke="#F2F5FF" stroke-width="6" opacity="0.75"/>'
    b += '<path d="M126,700 L126,478 Q180,422 234,478 L234,700" fill="none" stroke="#F2F5FF" stroke-width="2" opacity="0.5"/>'
    b += '<rect x="-10" y="690" width="380" height="100" fill="#EEF3FB"/>'
    # memorial candle in glass
    for x in (155, 205):
        b += candle(x, 690, h=56, w=24, color="#FFFFFF")
        b += f'<rect x="{x-17}" y="626" width="34" height="64" rx="4" fill="url(#glass)"/>'
    return svg(d, b)

def sukkos():
    d = bg("b", "#0F2A4A", "#1F5070", "#E09A5A") + GLOW + '<linearGradient id="wall" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="#F6E7C8"/><stop offset="1" stop-color="#E2C99A"/></linearGradient><radialGradient id="esrog" cx="0.35" cy="0.35" r="0.8"><stop offset="0" stop-color="#FFF49A"/><stop offset="0.7" stop-color="#E9C91A"/><stop offset="1" stop-color="#A88A0A"/></radialGradient>'
    b = '<rect width="360" height="780" fill="url(#b)"/>' + stars(45, 4, ymax=330)
    b += '<circle cx="80" cy="110" r="22" fill="#FFF6D0" opacity="0.9"/>'
    # sukkah
    b += '<rect x="40" y="470" width="280" height="230" fill="url(#wall)"/>'
    b += '<rect x="70" y="510" width="220" height="150" fill="#3A2A20" opacity="0.85"/>'
    b += '<circle cx="180" cy="585" r="80" fill="url(#glow)" opacity="0.6"/>'
    for x in (40, 316):
        b += f'<rect x="{x}" y="470" width="4" height="230" fill="#8B5A2B"/>'
    r = random.Random(5)
    for i in range(60):
        x = r.uniform(30, 330); y = r.uniform(440, 482)
        b += f'<ellipse cx="{x:.0f}" cy="{y:.0f}" rx="{r.uniform(10,22):.0f}" ry="{r.uniform(4,7):.0f}" fill="{r.choice(["#2E7D32","#388E3C","#43A047","#1B5E20","#66BB6A"])}" transform="rotate({r.uniform(-30,30):.0f} {x:.0f} {y:.0f})"/>'
    # hanging decorations
    for i, (x, c) in enumerate([(100, "#E53935"), (140, "#FDD835"), (180, "#8E24AA"), (220, "#1E88E5"), (260, "#FB8C00")]):
        L = 30 + (i % 2) * 18
        b += f'<line x1="{x}" y1="486" x2="{x}" y2="{486+L}" stroke="#C9A86A" stroke-width="1"/><circle cx="{x}" cy="{490+L}" r="8" fill="{c}"/><circle cx="{x-2}" cy="{487+L}" r="2.5" fill="#fff" opacity="0.6"/>'
    # lulav and esrog in front
    b += '<path d="M118,700 L128,560" stroke="#7CB342" stroke-width="9" stroke-linecap="round"/><path d="M128,560 L133,520" stroke="#9CCC65" stroke-width="5" stroke-linecap="round"/>'
    for dy in range(0, 70, 10):
        b += f'<ellipse cx="{112}" cy="{640+dy-40}" rx="10" ry="4" fill="#2E7D32" transform="rotate(-35 112 {640+dy-40})"/><ellipse cx="{138}" cy="{640+dy-40}" rx="10" ry="4" fill="#388E3C" transform="rotate(35 138 {640+dy-40})"/>'
    b += '<ellipse cx="240" cy="672" rx="30" ry="22" fill="url(#esrog)"/><path d="M268,668 l8,-4" stroke="#6B4E16" stroke-width="3"/>'
    b += '<rect x="-10" y="698" width="380" height="90" fill="#5D4037"/>'
    return svg(d, b)

def simchas_torah():
    d = bg("b", "#1A0F3D", "#3B1E7A", "#7A3FA8") + GLOW + '<linearGradient id="wood" x1="0" x2="1"><stop offset="0" stop-color="#5A3418"/><stop offset="0.5" stop-color="#A0652E"/><stop offset="1" stop-color="#5A3418"/></linearGradient><linearGradient id="parch" x1="0" x2="1"><stop offset="0" stop-color="#E9D9B5"/><stop offset="0.5" stop-color="#FFF6E0"/><stop offset="1" stop-color="#E9D9B5"/></linearGradient><linearGradient id="gold" x1="0" x2="1"><stop offset="0" stop-color="#B8860B"/><stop offset="0.5" stop-color="#FFE38A"/><stop offset="1" stop-color="#B8860B"/></linearGradient>'
    b = '<rect width="360" height="780" fill="url(#b)"/>' + stars(60, 6)
    r = random.Random(7)
    for i in range(70):
        x = r.uniform(0, 360); y = r.uniform(380, 720)
        b += f'<rect x="{x:.0f}" y="{y:.0f}" width="5" height="9" rx="1" fill="{r.choice(["#FFD54F","#4FC3F7","#F06292","#81C784","#FFFFFF","#BA68C8"])}" transform="rotate({r.uniform(0,90):.0f} {x:.0f} {y:.0f})" opacity="0.85"/>'
    b += '<circle cx="180" cy="580" r="150" fill="url(#glow)" opacity="0.45"/>'
    # open Torah scroll
    b += '<rect x="122" y="500" width="116" height="150" fill="url(#parch)"/>'
    for i in range(9):
        b += f'<line x1="134" y1="{518+i*14}" x2="{226 - (i%3)*8}" y2="{518+i*14}" stroke="#3A2A1A" stroke-width="2" opacity="0.55" stroke-dasharray="6 3 9 2 5 3"/>'
    for x in (108, 252):
        b += f'<rect x="{x-14}" y="488" width="28" height="174" rx="13" fill="url(#parch)" stroke="#CDB98F"/>'
        b += f'<rect x="{x-3}" y="452" width="6" height="250" rx="3" fill="url(#wood)"/>'
        b += f'<ellipse cx="{x}" cy="488" rx="18" ry="5" fill="url(#wood)"/><ellipse cx="{x}" cy="662" rx="18" ry="5" fill="url(#wood)"/>'
        b += f'<path d="M{x-10},452 L{x-12},432 L{x-5},442 L{x},428 L{x+5},442 L{x+12},432 L{x+10},452 Z" fill="url(#gold)"/>'
    # flags
    for x, rot in ((52, -12), (308, 12)):
        b += (f'<g transform="rotate({rot} {x} 700)"><line x1="{x}" y1="700" x2="{x}" y2="540" stroke="#D7CCC8" stroke-width="4"/>'
              f'<rect x="{x}" y="545" width="54" height="40" rx="3" fill="#FFFFFF"/><rect x="{x}" y="545" width="54" height="8" fill="#7E57C2"/><rect x="{x}" y="577" width="54" height="8" fill="#7E57C2"/>'
              f'<path d="M{x+15},572 L{x+13},560 L{x+20},566 L{x+27},556 L{x+34},566 L{x+41},560 L{x+39},572 Z" fill="#E0A800"/></g>')
    return svg(d, b)

def pesach():
    d = bg("b", "#0C1E2E", "#163A4F", "#2F5E63") + GLOW + SILVER + '<linearGradient id="matzah" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#F6E3B8"/><stop offset="1" stop-color="#D7B271"/></linearGradient>'
    b = '<rect width="360" height="780" fill="url(#b)"/>' + stars(70, 8)
    b += '<circle cx="275" cy="105" r="30" fill="#FFF8DD" opacity="0.95"/>'
    b += table(640, "#F5F1E8", "#DCD2BE")
    b += '<circle cx="180" cy="575" r="160" fill="url(#glow)" opacity="0.35"/>'
    # matzah stack (left)
    for i in range(3):
        y = 610 - i * 9
        b += f'<path d="M30,{y} l90,-6 l6,28 l-92,6 Z" fill="url(#matzah)" stroke="#B88A45" stroke-width="0.8"/>'
    r = random.Random(9)
    for i in range(40):
        x = r.uniform(38, 118); y = r.uniform(594, 618)
        b += f'<circle cx="{x:.0f}" cy="{y:.0f}" r="0.9" fill="#9A6A2A"/>'
    # seder plate
    b += '<ellipse cx="200" cy="622" rx="62" ry="22" fill="url(#silver)"/><ellipse cx="200" cy="618" rx="52" ry="17" fill="#EEF2F7"/>'
    items = [(172, 612, "#7CB342"), (200, 606, "#F5F5F5"), (228, 612, "#A1887F"), (178, 626, "#8D6E63"), (222, 626, "#C62828"), (200, 630, "#33691E")]
    for x, y, c in items:
        b += f'<ellipse cx="{x}" cy="{y}" rx="9" ry="4.5" fill="{c}"/>'
    # cup of Eliyahu
    b += cup(305, 640, s=1.15)
    return svg(d, b)

def shavuos():
    d = bg("b", "#0E2A22", "#1F5A45", "#79A86A") + GLOW + '<linearGradient id="stone" x1="0" x2="1"><stop offset="0" stop-color="#C9CED6"/><stop offset="0.5" stop-color="#F2F4F7"/><stop offset="1" stop-color="#AEB5C0"/></linearGradient>'
    b = '<rect width="360" height="780" fill="url(#b)"/>' + stars(50, 10, ymax=300)
    b += '<path d="M-10,690 L120,500 L170,540 L230,450 L370,690 Z" fill="#2E5E3E" opacity="0.9"/><path d="M230,450 L200,500 L250,520 Z" fill="#E8F5E9" opacity="0.5"/>'
    b += '<circle cx="230" cy="440" r="120" fill="url(#glow)" opacity="0.5"/>'
    # luchos
    for x in (130, 190):
        b += f'<path d="M{x},640 L{x},540 A20,20 0 0,1 {x+40},540 L{x+40},640 Z" fill="url(#stone)" stroke="#8C95A3" stroke-width="1.5"/>'
        for i in range(5):
            b += f'<line x1="{x+8}" y1="{552+i*16}" x2="{x+32}" y2="{552+i*16}" stroke="#4A5568" stroke-width="2.2" opacity="0.6"/>'
    # flowers
    r = random.Random(11)
    b += '<rect x="-10" y="660" width="380" height="130" fill="#2F6B3A"/>'
    for i in range(26):
        x = r.uniform(10, 350); y = r.uniform(650, 720); c = r.choice(["#F48FB1", "#FFF176", "#FFFFFF", "#CE93D8", "#FF8A65", "#90CAF9"])
        b += f'<line x1="{x:.0f}" y1="{y:.0f}" x2="{x:.0f}" y2="{y+30:.0f}" stroke="#3E8E41" stroke-width="2"/>'
        for k in range(5):
            import math
            a = k * 72 + r.uniform(0, 30)
            px = x + 6 * math.cos(math.radians(a)); py = y + 6 * math.sin(math.radians(a))
            b += f'<circle cx="{px:.1f}" cy="{py:.1f}" r="4.2" fill="{c}"/>'
        b += f'<circle cx="{x:.0f}" cy="{y:.0f}" r="3" fill="#FFB300"/>'
    return svg(d, b)

scenes = dict(shabbos=shabbos, rosh_hashana=rosh_hashana, yom_kippur=yom_kippur, sukkos=sukkos,
              simchas_torah=simchas_torah, pesach=pesach, shavuos=shavuos)
os.makedirs(OUT, exist_ok=True)
for k, f in scenes.items():
    open(os.path.join(OUT, f"{k}.svg"), "w").write(f())
print("ok", len(scenes))
