# Builds the bedtime lock-screen picture as SVG (viewBox 360x780, phone portrait).
# The clock and "Good night" sit at the top (y < 210) and the call buttons at the bottom (y > 690),
# so the cute part lives in between.
import math, random, sys

W, H = 360, 780


def star_shape(cx, cy, r, rot=0):
    pts = []
    for i in range(10):
        rad = r if i % 2 == 0 else r * 0.5
        a = math.radians(rot - 90 + i * 36)
        pts.append(f"{cx + rad * math.cos(a):.1f},{cy + rad * math.sin(a):.1f}")
    return " ".join(pts)


def face_star(cx, cy, r, rot=0, color="#FFE27A"):
    # a chubby star with sleepy eyes and a little smile
    s = f'<circle cx="{cx}" cy="{cy}" r="{r * 1.9}" fill="url(#starglow)"/>'
    s += f'<polygon points="{star_shape(cx, cy, r, rot)}" fill="{color}" stroke="#F5B941" stroke-width="1.2" stroke-linejoin="round"/>'
    e = r * 0.22
    for dx in (-e, e):
        s += f'<path d="M{cx + dx - 2.2:.1f},{cy - 1:.1f} q2.2,2.2 4.4,0" stroke="#5A3A12" stroke-width="1.2" fill="none" stroke-linecap="round"/>'
    s += f'<path d="M{cx - 2:.1f},{cy + 3:.1f} q2,1.8 4,0" stroke="#5A3A12" stroke-width="1.1" fill="none" stroke-linecap="round"/>'
    s += f'<circle cx="{cx - e - 2.5:.1f}" cy="{cy + 2.5:.1f}" r="1.6" fill="#FF9DA8" opacity="0.8"/>'
    s += f'<circle cx="{cx + e + 2.5:.1f}" cy="{cy + 2.5:.1f}" r="1.6" fill="#FF9DA8" opacity="0.8"/>'
    return s


def sparkle(cx, cy, r, op=0.9):
    return (f'<path d="M{cx},{cy - r} Q{cx + r * 0.18},{cy - r * 0.18} {cx + r},{cy} Q{cx + r * 0.18},{cy + r * 0.18} {cx},{cy + r} '
            f'Q{cx - r * 0.18},{cy + r * 0.18} {cx - r},{cy} Q{cx - r * 0.18},{cy - r * 0.18} {cx},{cy - r}Z" fill="#FFF6D6" opacity="{op}"/>')


def cloud(cx, cy, s=1.0, color="#B9B2F0", op=1.0):
    bumps = [(-34, 6, 16), (-16, -6, 22), (8, -12, 26), (32, -2, 20), (48, 8, 14)]
    b = f'<g opacity="{op}">'
    b += f'<rect x="{cx - 46 * s:.1f}" y="{cy:.1f}" width="{100 * s:.1f}" height="{22 * s:.1f}" rx="{11 * s:.1f}" fill="{color}"/>'
    for dx, dy, r in bumps:
        b += f'<circle cx="{cx + dx * s:.1f}" cy="{cy + dy * s + 6 * s:.1f}" r="{r * s:.1f}" fill="{color}"/>'
    # soft highlight on top
    b += f'<circle cx="{cx + 2 * s:.1f}" cy="{cy - 10 * s:.1f}" r="{14 * s:.1f}" fill="#FFFFFF" opacity="0.18"/>'
    return b + '</g>'


def build():
    r = random.Random(7)
    d = (
        '<linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">'
        '<stop offset="0" stop-color="#0B1038"/><stop offset="0.45" stop-color="#1E1F63"/>'
        '<stop offset="0.75" stop-color="#3B2F86"/><stop offset="1" stop-color="#5A3E9E"/></linearGradient>'
        '<radialGradient id="moonglow"><stop offset="0" stop-color="#FFF3B8" stop-opacity="0.55"/>'
        '<stop offset="0.45" stop-color="#FFE38A" stop-opacity="0.18"/><stop offset="1" stop-color="#FFE38A" stop-opacity="0"/></radialGradient>'
        '<radialGradient id="starglow"><stop offset="0" stop-color="#FFE9A0" stop-opacity="0.45"/><stop offset="1" stop-color="#FFE9A0" stop-opacity="0"/></radialGradient>'
        '<radialGradient id="moon" cx="0.35" cy="0.35" r="0.8"><stop offset="0" stop-color="#FFF6C9"/>'
        '<stop offset="0.7" stop-color="#FFE07A"/><stop offset="1" stop-color="#F7C548"/></radialGradient>'
        '<radialGradient id="window" cx="0.5" cy="0.5" r="0.7"><stop offset="0" stop-color="#FFF4C2"/><stop offset="1" stop-color="#FFB547"/></radialGradient>'
        '<radialGradient id="winglow"><stop offset="0" stop-color="#FFD27A" stop-opacity="0.6"/><stop offset="1" stop-color="#FFD27A" stop-opacity="0"/></radialGradient>'
        '<linearGradient id="hill1" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4C3F9A"/><stop offset="1" stop-color="#2B2366"/></linearGradient>'
        '<linearGradient id="hill2" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3A2F82"/><stop offset="1" stop-color="#1D1850"/></linearGradient>'
        '<linearGradient id="hill3" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2A2268"/><stop offset="1" stop-color="#120F38"/></linearGradient>'
        '<clipPath id="crescent"><rect x="0" y="0" width="360" height="780"/></clipPath>'
        '<mask id="cutmoon"><rect x="0" y="0" width="360" height="780" fill="#fff"/><circle cx="292" cy="296" r="58" fill="#000"/></mask>'
    )
    b = f'<rect width="{W}" height="{H}" fill="url(#sky)"/>'

    # (tiny stars, sparkles, fireflies and shooting stars are drawn live by the app: NightSky.kt)

    # big sleepy crescent moon with a nightcap
    mx, my, mr = 250, 330, 74
    b += f'<circle cx="{mx - 26}" cy="{my + 4}" r="{mr * 1.8}" fill="url(#moonglow)"/>'
    b += f'<circle cx="{mx}" cy="{my}" r="{mr}" fill="url(#moon)" mask="url(#cutmoon)"/>'
    # craters
    for cx, cy, cr in [(212, 300, 6), (205, 352, 4.5), (232, 390, 5)]:
        b += f'<circle cx="{cx}" cy="{cy}" r="{cr}" fill="#F2C24E" opacity="0.55"/>'
    # face on the crescent (looking left)
    b += '<path d="M200,326 q8,7 16,0" stroke="#6B4512" stroke-width="2.6" fill="none" stroke-linecap="round"/>'
    b += '<path d="M197,322 l-3,-3 M203,320 l-1,-4 M209,320 l1,-4" stroke="#6B4512" stroke-width="1.4" stroke-linecap="round"/>'
    b += '<circle cx="207" cy="342" r="6" fill="#FF9AAE" opacity="0.65"/>'
    b += '<path d="M188,352 q6,6 13,2" stroke="#6B4512" stroke-width="2.2" fill="none" stroke-linecap="round"/>'
    # nightcap sitting on the moon's top tip, flopping over to the right with a pom-pom
    b += '<g transform="translate(-18,8) rotate(-14 240 260)">'
    b += ('<path d="M214,276 C220,240 246,214 276,214 C300,214 314,230 318,252 C310,240 298,234 286,236 C276,240 268,250 264,262 Z" fill="#7FB6FF"/>'
          '<path d="M236,236 C244,226 256,220 268,219 L270,230 C258,231 248,237 242,245 Z" fill="#FFFFFF" opacity="0.7"/>'
          '<path d="M288,220 C298,224 306,232 310,242 L302,240 C298,234 292,230 284,230 Z" fill="#FFFFFF" opacity="0.7"/>'
          '<path d="M208,282 C224,262 250,250 272,254 L270,266 C250,262 228,272 216,290 Z" fill="#FFFFFF"/>'
          '<circle cx="320" cy="258" r="10" fill="#FFFFFF"/><circle cx="317" cy="255" r="4" fill="#E8EEFF"/>')
    b += '</g>'

    # "z z Z" drifting up from the moon
    b += '<g font-family="Arial Rounded MT Bold, Arial, sans-serif" font-weight="700" fill="#E9E4FF">'
    b += '<text x="160" y="296" font-size="13" opacity="0.7">z</text><text x="146" y="276" font-size="17" opacity="0.8">z</text><text x="126" y="252" font-size="23" opacity="0.9">Z</text></g>'

    # little smiling stars
    b += face_star(70, 330, 15, rot=-12)
    b += face_star(122, 412, 11, rot=10, color="#FFD1E8")
    b += face_star(46, 470, 9, rot=5, color="#C9F2FF")

    # clouds
    b += cloud(96, 520, 1.0, "#9F95E6", 0.95)
    b += cloud(300, 498, 0.75, "#8D83D9", 0.85)
    b += cloud(200, 236, 0.55, "#6E66C2", 0.6)

    # hills, back to front
    b += '<path d="M0,600 C60,560 130,566 190,590 C250,612 300,580 360,566 L360,780 L0,780 Z" fill="url(#hill1)"/>'
    b += '<path d="M0,640 C70,610 140,620 200,640 C260,660 320,640 360,628 L360,780 L0,780 Z" fill="url(#hill2)"/>'

    # cozy house on the hill with a warm window
    hx, hy = 248, 600
    b += f'<circle cx="{hx + 20}" cy="{hy + 20}" r="46" fill="url(#winglow)"/>'
    b += f'<rect x="{hx}" y="{hy}" width="44" height="36" rx="3" fill="#3E2F7C"/>'
    b += f'<path d="M{hx - 6},{hy + 2} L{hx + 22},{hy - 22} L{hx + 50},{hy + 2} Z" fill="#E86A8E"/>'
    b += f'<rect x="{hx + 32}" y="{hy - 22}" width="7" height="14" fill="#5B4AA0"/>'
    b += f'<rect x="{hx + 7}" y="{hy + 9}" width="13" height="12" rx="2" fill="url(#window)"/>'
    b += f'<line x1="{hx + 13.5}" y1="{hy + 9}" x2="{hx + 13.5}" y2="{hy + 21}" stroke="#3E2F7C" stroke-width="1.4"/>'
    b += f'<rect x="{hx + 26}" y="{hy + 16}" width="11" height="20" rx="2" fill="#2A2060"/>'
    # tiny chimney puff
    b += f'<circle cx="{hx + 36}" cy="{hy - 30}" r="4" fill="#C8C2F2" opacity="0.5"/><circle cx="{hx + 42}" cy="{hy - 38}" r="5" fill="#C8C2F2" opacity="0.35"/>'

    # sleeping bunny curled up on the left hill
    bx, by = 82, 616
    b += f'<ellipse cx="{bx}" cy="{by + 14}" rx="34" ry="5" fill="#120F38" opacity="0.35"/>'
    b += f'<ellipse cx="{bx}" cy="{by}" rx="30" ry="18" fill="#F4F0FF"/>'          # body
    b += f'<circle cx="{bx + 26}" cy="{by + 2}" r="7" fill="#FFFFFF"/>'            # tail
    b += f'<ellipse cx="{bx - 24}" cy="{by - 4}" rx="15" ry="13" fill="#F4F0FF"/>'  # head
    b += f'<ellipse cx="{bx - 18}" cy="{by - 20}" rx="5" ry="15" fill="#F4F0FF" transform="rotate(40 {bx - 18} {by - 20})"/>'
    b += f'<ellipse cx="{bx - 18}" cy="{by - 20}" rx="2.4" ry="10" fill="#FFC2D4" transform="rotate(40 {bx - 18} {by - 20})"/>'
    b += f'<ellipse cx="{bx - 8}" cy="{by - 14}" rx="5" ry="14" fill="#EAE4FA" transform="rotate(62 {bx - 8} {by - 14})"/>'
    b += f'<path d="M{bx - 32},{by - 5} q3,2.5 6,0" stroke="#6B5A9A" stroke-width="1.4" fill="none" stroke-linecap="round"/>'
    b += f'<circle cx="{bx - 36}" cy="{by + 1}" r="1.8" fill="#FF9AB5"/>'
    b += f'<circle cx="{bx - 26}" cy="{by + 1}" r="3" fill="#FFB8CB" opacity="0.6"/>'
    b += '<g font-family="Arial, sans-serif" font-weight="700" fill="#E9E4FF">'
    b += f'<text x="{bx - 46}" y="{by - 26}" font-size="9" opacity="0.7">z</text><text x="{bx - 56}" y="{by - 38}" font-size="12" opacity="0.85">z</text></g>'

    # front hill (behind the call buttons) and fireflies
    b += '<path d="M0,676 C80,656 160,664 230,680 C290,694 330,684 360,676 L360,780 L0,780 Z" fill="url(#hill3)"/>'

    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}"><defs>{d}</defs>{b}</svg>'


if __name__ == "__main__":
    open(sys.argv[1], "w").write(build())
    print("ok")
