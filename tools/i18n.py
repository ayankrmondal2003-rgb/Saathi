"""App text translation helper (see app/src/main/java/in/maitri/app/I18n.kt).

    python tools/i18n.py wrap FILE...     wrap user-visible English literals in tr(...) (templates -> {0} args)
    python tools/i18n.py keys             write app/src/main/assets/i18n/keys.json: every tr("...")/trKey("...") text
    python tools/i18n.py check            list texts missing from each language file

Translations live in app/src/main/assets/i18n/<code>.json as {"English": "translation"}. They are
machine-drafted and must be reviewed by native speakers ("_review" in each file says so).
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/in/maitri/app"
I18N = ROOT / "app/src/main/assets/i18n"
LANGUAGES = ["hi", "bn", "mr", "ta", "te"]

LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
SKIP_LINE = re.compile(
    r'Log\.|@SerializedName|@GET|@POST|@PUT|@DELETE|@PATCH|@Preview|getSharedPreferences|putString|getString\(|'
    r'putBoolean|getBoolean|putExtra\(|Intent\(|Uri\.parse|navigate\(|composable\(|route|testTag|const val|require\(|'
    r'requireNotNull|check\(|error\(|Exception\(|TAG|contentType|Manifest|MediaType|toMediaType|\.format\(|Regex\(|'
    r'SELECT |CREATE |INSERT |startsWith|endsWith|\.contains\(|equals\(|JsonParser|addTag|"sos-|tr\(\s*$')


def visible(text: str) -> bool:
    if len(text) < 2 or not re.search(r"[A-Za-z]{2,}", text) or "%" in text:
        return False
    if text.startswith(("http", "api/", "in.maitri", "android.", "application/", "image/", "audio/", "saathi", "tel:", "mailto:")):
        return False
    if re.fullmatch(r"[A-Z0-9_]+", text):
        return False  # codes such as ACTIVE, SOS_RATE_LIMITED
    if re.fullmatch(r"[a-z0-9_.:/-]+", text):
        return False  # keys, ids, paths
    return " " in text or text[0].isupper() or text.startswith(("≈", "•", "●", "☐", "☑"))


def unescape(kotlin: str) -> str:
    out, i = [], 0
    while i < len(kotlin):
        c = kotlin[i]
        if c == "\\" and i + 1 < len(kotlin):
            n = kotlin[i + 1]
            if n == "u":
                out.append(chr(int(kotlin[i + 2:i + 6], 16))); i += 6; continue
            out.append({"n": "\n", "t": "\t", "\"": "\"", "\\": "\\", "$": "$", "'": "'", "r": "\r"}.get(n, n)); i += 2
            continue
        out.append(c); i += 1
    return "".join(out)


def template(kotlin: str):
    """Splits a Kotlin template literal into ("text {0} …", [arg expressions]); None if it can't."""
    text, args, i = [], [], 0
    while i < len(kotlin):
        c = kotlin[i]
        if c == "\\":
            text.append(kotlin[i:i + 2]); i += 2; continue
        if c == "$" and i + 1 < len(kotlin) and kotlin[i + 1] == "{":
            depth, j = 1, i + 2
            while j < len(kotlin) and depth:
                if kotlin[j] == "{": depth += 1
                elif kotlin[j] == "}": depth -= 1
                j += 1
            expr = kotlin[i + 2:j - 1]
            if '"' in expr:
                return None  # nested literal: leave for a person
            text.append("{%d}" % len(args)); args.append(expr.strip()); i = j; continue
        if c == "$" and i + 1 < len(kotlin) and (kotlin[i + 1].isalpha() or kotlin[i + 1] == "_"):
            m = re.match(r"[A-Za-z_]\w*", kotlin[i + 1:])
            text.append("{%d}" % len(args)); args.append(m.group(0)); i += 1 + len(m.group(0)); continue
        text.append(c); i += 1
    return "".join(text), args


def wrap_line(line: str) -> str:
    if SKIP_LINE.search(line) or line.strip().startswith(("//", "*", "/*", "import", "package", "@")):
        return line
    out, last = [], 0
    for m in LITERAL.finditer(line):
        raw = m.group(1)
        before, after = line[:m.start()], line[m.end():]
        if not visible(unescape(re.sub(r"\$\{[^}]*\}|\$[A-Za-z_]\w*", "x", raw))):
            continue
        if re.search(r"(tr|trKey|stringResource|getString)\(\s*$", before) or re.search(r"(==|!=|\bin\b|\bis\b)\s*$", before):
            continue
        if re.match(r"\s*(->|,\s*\"[^\"]*\"\s*->)", after):
            continue  # when-branch keys
        if re.search(r"\b(case|when)\s*\(\s*$", before):
            continue
        if "$" in raw:
            parts = template(raw)
            if parts is None:
                continue
            text, args = parts
            replacement = 'tr("' + text + '", ' + ", ".join(args) + ")"
        else:
            replacement = 'tr("' + raw + '")'
        out.append(line[last:m.start()]); out.append(replacement); last = m.end()
    out.append(line[last:])
    return "".join(out)


def wrap(paths):
    for p in paths:
        path = Path(p).resolve()
        src = path.read_text(encoding="utf8")
        lines = src.split("\n")
        new = "\n".join(wrap_line(l) for l in lines)
        if new != src:
            if "import `in`.maitri.app.tr" not in new and path.parent != SRC:
                new = re.sub(r"(\npackage [^\n]+\n)", r"\1\nimport `in`.maitri.app.tr\n", new, count=1)
            path.write_text(new, encoding="utf8", newline="")
            print("wrapped", path.relative_to(ROOT), sum(1 for a, b in zip(lines, new.split("\n")) if a != b), "lines")


CALL = re.compile(r'\b(?:tr|trKey)\(\s*"((?:[^"\\]|\\.)*)"')


def keys():
    found = set()
    for path in SRC.rglob("*.kt"):
        for m in CALL.finditer(path.read_text(encoding="utf8")):
            found.add(unescape(m.group(1)))
    return sorted(found)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "check"
    if cmd == "wrap":
        wrap(sys.argv[2:])
    elif cmd == "keys":
        I18N.mkdir(parents=True, exist_ok=True)
        k = keys()
        (I18N / "keys.json").write_text(json.dumps(k, ensure_ascii=False, indent=1), encoding="utf8")
        print(len(k), "texts")
    elif cmd == "merge":
        # tools/i18n/batch*.json: {"English": [hi, bn, mr, ta, te]} -> one table per language.
        merged = {code: {} for code in LANGUAGES}
        bad = []
        for batch in sorted((ROOT / "tools/i18n").glob("batch*.json")):
            for en, values in json.loads(batch.read_text(encoding="utf8")).items():
                wanted = sorted(re.findall(r"\{\d+\}", en))
                for code, text in zip(LANGUAGES, values):
                    if sorted(re.findall(r"\{\d+\}", text)) != wanted:
                        bad.append((code, en))
                    merged[code][en] = text
        if bad:
            for code, en in bad:
                print("placeholder mismatch", code, en[:60])
            sys.exit(1)
        for code, table in merged.items():
            out = {"_review": "Machine-drafted translation; needs review by a native speaker before release."}
            out.update(dict(sorted(table.items())))
            (I18N / f"{code}.json").write_text(json.dumps(out, ensure_ascii=False, indent=1), encoding="utf8")
            print(code, len(table))
    elif cmd == "check":
        k = keys()
        bad = 0
        for code in LANGUAGES:
            f = I18N / f"{code}.json"
            table = json.loads(f.read_text(encoding="utf8")) if f.exists() else {}
            missing = [x for x in k if x not in table]
            bad += len(missing)
            print(code, len(k) - len(missing), "/", len(k))
            for x in missing[:5]:
                print("   missing:", x[:80])
        sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
