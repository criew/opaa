"""Variant (c): PaddleOCR PP-StructureV3 (layout, tables, Markdown) on CPU; OCR_DEVICE=gpu:0 for a GPU build."""

import html
import os
import re
import tempfile
from pathlib import Path

from runlib import cli, run


def setup():
    from paddleocr import PPStructureV3

    return PPStructureV3(
        lang="de",
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_formula_recognition=False,
        use_chart_recognition=False,
        use_seal_recognition=False,
        device=os.environ.get("OCR_DEVICE", "cpu"),
        # paddlepaddle 3.3.1: the oneDNN executor rejects a PIR attribute of the layout model
        enable_mkldnn=os.environ.get("OCR_MKLDNN", "0") == "1",
    )


def html_tables_to_markdown(text):
    """PP-StructureV3 emits tables as HTML; rewrite each as Markdown pipe rows like Docling's output."""

    def table(m):
        rows = []
        for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", m.group(0), flags=re.S):
            cells = re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, flags=re.S)
            rows.append("| " + " | ".join(html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in cells) + " |")
        if rows:
            rows.insert(1, "|" + "---|" * (rows[0].count(" | ") + 1))
        return "\n" + "\n".join(rows) + "\n"

    text = re.sub(r"<table.*?</table>", table, text, flags=re.S)
    return re.sub(r"</?(?:html|body|div)[^>]*>", "", text)


def convert(pipeline, src):
    pages = []
    for res in pipeline.predict(str(src)):
        md = res.markdown
        pages.append(md["markdown_texts"] if isinstance(md, dict) else str(md))
    return html_tables_to_markdown("\n\n".join(pages))


if __name__ == "__main__":
    a, profiles, docs = cli()
    run("paddle-structure", convert, a.corpus, a.out, profiles, docs, setup)
