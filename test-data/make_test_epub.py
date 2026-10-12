# -*- coding: utf-8 -*-
"""Сборка маленькой тестовой EPUB-книги из текстов Акутагавы (Aozora Bunko, public domain).

Использование: python make_test_epub.py <вход1.txt> <вход2.txt> <выход.epub>
Входные файлы — cp932 с разметкой Аозоры (《ルビ》, ｜, ［＃注記]).
"""
import re
import sys
import zipfile

DASHES = "-------------------------------------------------------"


def clean_aozora(raw: str):
    lines = raw.split("\n")
    # Название и автор — первые строки файла (до первого разделителя)
    title = ""
    for line in lines:
        line = line.strip("\r　 ")
        if line.startswith(DASHES[:10]):
            break
        if line and not title:
            title = line
    # Текст — после второй линии-разделителя (конец блока пояснений)
    dash_count = 0
    start = 0
    for i, line in enumerate(lines):
        if line.startswith(DASHES[:20]):
            dash_count += 1
            if dash_count == 2:
                start = i + 1
                break
    # Подвал: от «底本» и всё после
    end = len(lines)
    for i in range(start, len(lines)):
        if lines[i].startswith("底本"):
            end = i
            break
    body = []
    for line in lines[start:end]:
        line = line.rstrip("\r")
        # ruby и пометы
        line = re.sub(r"《[^》]*》", "", line)
        line = line.replace("｜", "")
        line = re.sub(r"［＃[^］]*］", "", line)
        # ※-маркер редкого кандзи перед вырезанной пометой
        line = line.replace("※", "")
        # ведущие полноразмерные пробелы
        line = line.lstrip("　 ").strip()
        if line:
            body.append(line)

    blocks = []
    for line in body:
        is_short_heading = len(line) <= 24 and not re.search(r"[。、？！…「」）]$", line) and len(line) > 1
        blocks.append(("h3" if is_short_heading else "p", line))
    return title, blocks


def xhtml_escape(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def chapter_xhtml(title, blocks):
    parts = [f"<h2>{xhtml_escape(title)}</h2>"]
    for kind, text in blocks:
        if kind == "h3":
            parts.append(f"<h3>{xhtml_escape(text)}</h3>")
        else:
            parts.append(f"<p>{xhtml_escape(text)}</p>")
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="ja"><head><title>{xhtml_escape(title)}</title>
<link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
{chr(10).join(parts)}
</body></html>"""


def build_epub(out_path, chapters):
    files = {}
    files["mimetype"] = "application/epub+zip"
    files["META-INF/container.xml"] = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    files["OEBPS/style.css"] = "body{font-family:serif;line-height:1.6}h2{margin:1em 0}p{margin:.4em 0}"

    chapter_names = []
    for i, (title, _) in enumerate(chapters):
        name = f"chapter{i+1}.xhtml"
        chapter_names.append(name)
        files[f"OEBPS/{name}"] = chapter_xhtml(title, chapters[i][1])

    files["OEBPS/nav.xhtml"] = f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Оглавление</title></head><body><nav epub:type="toc"><ol>
{chr(10).join(f'<li><a href="{n}">{xhtml_escape(t)}</a></li>' for n, (t, _) in zip(chapter_names, chapters))}
</ol></nav></body></html>"""

    files["OEBPS/content.opf"] = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bid">urn:uuid:akutagawa-test-book</dc:identifier>
<dc:title>Тестовая книга: Акутагава — Нить паука и В чаще</dc:title>
<dc:creator>芥川龍之介</dc:creator>
<dc:language>ja</dc:language>
</metadata>
<manifest>
<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
<item id="css" href="style.css" media-type="text/css"/>
{chr(10).join(f'<item id="c{i+1}" href="{n}" media-type="application/xhtml+xml"/>' for i, n in enumerate(chapter_names))}
</manifest>
<spine>
{chr(10).join(f'<itemref idref="c{i+1}"/>' for i in range(len(chapter_names)))}
</spine></package>"""

    with zipfile.ZipFile(out_path, "w") as z:
        # mimetype первым, без сжатия
        z.writestoredu = None  # noqa — просто маркер
        entry = zipfile.ZipInfo("mimetype")
        entry.compress_type = zipfile.ZIP_STORED
        z.writestr(entry, files["mimetype"])
        for path, content in files.items():
            if path == "mimetype":
                continue
            z.writestr(path, content)
    print(f"OK: {out_path}, глав: {len(chapters)}, файлов в zip: {len(files)}")


def main():
    src1, src2, out = sys.argv[1], sys.argv[2], sys.argv[3]
    chapters = []
    for src in (src1, src2):
        raw = open(src, "rb").read().decode("cp932")
        title, blocks = clean_aozora(raw)
        print(f"{src}: «{title}», блоков: {len(blocks)}, знаков: {sum(len(b[1]) for b in blocks)}")
        chapters.append((title, blocks))
    build_epub(out, chapters)


if __name__ == "__main__":
    main()
