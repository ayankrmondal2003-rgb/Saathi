"""Builds app/src/main/assets/volunteer-declaration.pdf, the template behind "Download template" on screen 04.

A plain one-page A4 PDF written directly (standard Helvetica, no dependencies), in the app's colours.
Re-run after changing the wording:  python tools/make_declaration_pdf.py
"""
from pathlib import Path

OUT = Path(__file__).resolve().parents[1] / "app/src/main/assets/volunteer-declaration.pdf"
ACCENT = "0.722 0.125 0.333"   # #B82055
INK = "0.251 0.188 0.227"      # #40303A
MUTED = "0.490 0.404 0.443"    # #7D6771

DECLARATIONS = [
    "I am 18 years of age or older.",
    "The information and documents I give Maitri are true and belong to me.",
    "I will only respond to a request when I can do so safely, and I will call 112 when someone is in danger.",
    "I understand that Maitri volunteers are not police, medical or emergency services.",
    "I will use a person's location and details only to help them during that request, and never share or keep them.",
    "I will treat everyone I help with respect, whatever their gender, religion, caste, age or background.",
    "I will not ask for or accept money or favours for helping.",
    "I understand Maitri may suspend or remove me as a volunteer if I break this declaration, and that I can "
    "withdraw at any time from the app.",
]


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def wrap(text: str, width: int = 88):
    words, line, lines = text.split(), "", []
    for w in words:
        if len(line) + len(w) + 1 > width:
            lines.append(line)
            line = w
        else:
            line = (line + " " + w).strip()
    return lines + [line] if line else lines


def build() -> bytes:
    ops = []
    y = 780

    def text(s, size=11, font="F1", colour=INK, x=56):
        nonlocal y
        ops.append(f"BT {colour} rg /{font} {size} Tf {x} {y} Td ({esc(s)}) Tj ET")

    text("MAITRI", 10, "F2", ACCENT); y -= 26
    text("Volunteer declaration", 22, "F2"); y -= 22
    text("Fill this in, sign it, and upload a photo or scan of it in the Maitri app (Responder > volunteer).", 10, colour=MUTED)
    y -= 34
    for label in ("Full name", "Phone number", "City or area"):
        text(label, 10, "F2", MUTED); y -= 22
        ops.append(f"{MUTED} RG 0.6 w 56 {y} m 539 {y} l S"); y -= 22
    y -= 6
    text("I declare that:", 12, "F2"); y -= 22
    for i, item in enumerate(DECLARATIONS, 1):
        for j, line in enumerate(wrap(item)):
            text((f"{i}. " if j == 0 else "    ") + line, 10.5); y -= 15
        y -= 4
    y -= 20
    for label in ("Signature", "Date"):
        text(label, 10, "F2", MUTED); y -= 26
        ops.append(f"{MUTED} RG 0.6 w 56 {y} m 300 {y} l S"); y -= 24
    y = 50
    text("Maitri keeps this document privately and deletes it when you delete your account.", 8.5, colour=MUTED)

    content = "\n".join(ops).encode("latin-1")
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R "
        b"/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> >>",
        b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>",
        b"<< /Title (Maitri volunteer declaration) /Producer (tools/make_declaration_pdf.py) >>",
    ]
    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = []
    for n, body in enumerate(objects, 1):
        offsets.append(len(out))
        out += f"{n} 0 obj\n".encode() + body + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode()
    out += b"".join(f"{o:010d} 00000 n \n".encode() for o in offsets)
    out += f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R /Info 7 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


if __name__ == "__main__":
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(build())
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")
