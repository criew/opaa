"""Variant (c): PaddleOCR PP-StructureV3 (layout, tables, Markdown) on CPU."""

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
        device="cpu",
    )


def convert(pipeline, src):
    pages = []
    for res in pipeline.predict(str(src)):
        md = res.markdown
        pages.append(md["markdown_texts"] if isinstance(md, dict) else str(md))
    return "\n\n".join(pages)


if __name__ == "__main__":
    a, profiles, docs = cli()
    run("paddle-structure", convert, a.corpus, a.out, profiles, docs, setup)
