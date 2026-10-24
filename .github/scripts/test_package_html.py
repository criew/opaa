"""Self-check for package_html.py, the HTML version of the update package.

Only a release builds the package, so a link that leads nowhere in the HTML version or an anchor
that differs from GitHub's would surface at a release at the earliest.
"""

import importlib.util
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("package_html", HERE / "package_html.py")
package_html = importlib.util.module_from_spec(spec)
sys.modules["package_html"] = package_html
spec.loader.exec_module(package_html)


def site(files: dict[str, str], notice: str | None = None,
         diagrams: dict[str, str] | None = None) -> dict[str, str]:
    raw = {path: text.encode("utf-8") for path, text in files.items()}
    return {path: content.decode("utf-8")
            for path, content in package_html.render(raw, "Update-Paket OPAA 1.2.3", notice,
                                                     diagrams=diagrams).items()}


def test_every_markdown_file_gets_a_page_beside_it_and_the_package_a_start_page() -> None:
    pages = site({"LIESMICH.md": "# Liesmich\n", "vorlagen/README.md": "# Vorlagen\n",
                  "faktenblaetter/fristen.md": "# Faktenblatt: Fristen\n", "SHA256SUMS": "x\n"})

    assert sorted(pages) == ["LIESMICH.html", "faktenblaetter/fristen.html", "index.html", "style.css",
                             "vorlagen/README.html"]
    start = pages["index.html"]
    assert '<a href="faktenblaetter/fristen.html">Faktenblatt: Fristen</a>' in start
    assert '<a href="vorlagen/README.html">Vorlagen</a>' in start
    assert '<a href="SHA256SUMS">SHA256SUMS</a>' in start
    assert 'id="faktenblaetter"' in start


def test_the_start_page_lists_every_file_of_the_package_once() -> None:
    start = site({"LIESMICH.md": "# Update-Paket\n", "faktenblaetter/fristen.md": "# Fristen\n",
                  "faktenblaetter/faktenblaetter.json": "{}\n", "delta/manifest-delta.md": "# Delta\n",
                  "sbom/opaa-backend-1.2.3.cdx.json": "{}\n"})["index.html"]
    main = start.split("<main>", 1)[1]

    targets = re.findall(r'href="([^"]+)"', main)
    assert sorted(targets) == ["LIESMICH.html", "delta/manifest-delta.html", "faktenblaetter/faktenblaetter.json",
                               "faktenblaetter/fristen.html", "sbom/opaa-backend-1.2.3.cdx.json"]


def test_headings_carry_the_anchors_github_gives_them() -> None:
    text = ("# Löschkonzept\n\n## Wo die Faktenblätter liegen\n\n## Provenance und Software-Stückliste\n\n"
            "## 3. Die Konnektoren im Überblick\n\n## `opaa.audit` und *mehr*: [Link](x.md)\n\n"
            "## Fristen\n\n## Fristen\n\n## Fristen-1\n\n## Fristen\n")

    page = site({"a.md": text, "x.md": "# x\n"})["a.html"]

    for anchor in ("löschkonzept", "wo-die-faktenblätter-liegen", "provenance-und-software-stückliste",
                   "3-die-konnektoren-im-überblick", "opaaaudit-und-mehr-link", "fristen", "fristen-1",
                   "fristen-1-1", "fristen-2"):
        assert f'id="{anchor}"' in page, anchor


def test_explicit_anchors_and_links_to_them_keep_working() -> None:
    pages = site({
        "faktenblaetter/fristen.md": '# Fristen\n\n| <a id="audit-retention"></a>`audit` |\n',
        "vorlagen/04-datenschutz/loeschkonzept.md":
            "# Löschkonzept\n\n[Frist](../../faktenblaetter/fristen.md#audit-retention),"
            " [Handbuch](../../handbuch/betrieb/nachvollziehbarkeit.md#wo-die-faktenblätter-liegen),"
            " [Ordner](./), [Handbuch-Ordner](../../handbuch/), [hier](#löschkonzept),"
            " [DSGVO](https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu)\n",
        "vorlagen/04-datenschutz/README.md": "# Datenschutz\n",
        "handbuch/README.md": "# Handbuch\n",
        "handbuch/betrieb/nachvollziehbarkeit.md": "# Nachvollziehbarkeit\n\n## Wo die Faktenblätter liegen\n",
    })

    page = pages["vorlagen/04-datenschutz/loeschkonzept.html"]
    assert 'href="../../faktenblaetter/fristen.html#audit-retention"' in page
    assert 'href="../../handbuch/betrieb/nachvollziehbarkeit.html#wo-die-faktenbl' in page
    assert 'href="./README.html"' in page
    assert 'href="../../handbuch/README.html"' in page
    assert 'href="https://eur-lex.europa.eu/eli/reg/2016/679/oj/deu"' in page
    assert '<a id="audit-retention"></a>' in pages["faktenblaetter/fristen.html"]
    raw = {path: text.encode("utf-8") for path, text in pages.items()}
    assert package_html.broken_links(raw) == []


def test_the_front_matter_becomes_a_small_box_not_text() -> None:
    page = site({"a.md": "---\nstand: 2026-10-23\ngilt-fuer: main\n---\n\n# Titel\n"})["a.html"]

    assert '<aside class="front-matter"><dl><dt>stand</dt><dd>2026-10-23</dd>' in page
    assert "<hr" not in page
    assert "<title>Titel</title>" in page


def test_nothing_is_loaded_from_outside_the_package() -> None:
    source = "flowchart LR\n  A --> B\n"
    svg = '<svg id="d"><defs><marker id="d_end"/></defs><path marker-end="url(#d_end)"/></svg>'
    pages = site({"a.md": f"# Titel\n\n```mermaid\n{source}```\n\n[Extern](https://example.org)\n"},
                 diagrams={source: svg})

    for path, text in pages.items():
        assert "<script" not in text, path
        assert not re.search(r'<link[^>]+href="(https?:)?//', text), path
        assert "@import" not in text and not re.search(r"url\((?!#)", text), path


def test_a_rendered_mermaid_diagram_shows_as_svg_with_its_source_beside_it() -> None:
    source = "flowchart LR\n  A --> B\n"
    page = site({"a.md": f"# Titel\n\n```mermaid\n{source}```\n"},
                diagrams={source: '<svg id="d"></svg>'})["a.html"]

    text = package_html.diagram_id(source) + "-source-text"
    assert f'<figure class="diagram"><svg aria-describedby="{text}" id="d"></svg></figure>' in page
    assert ('<details class="diagram-source"><summary>Diagramm als Text</summary>'
            f'<pre id="{text}"><code class="language-mermaid">flowchart LR\n  A --&gt; B\n</code></pre>'
            '</details>') in page


def test_a_mermaid_diagram_without_rendering_stays_code() -> None:
    page = site({"a.md": "# Titel\n\n```mermaid\nflowchart LR\n  A --> B\n```\n"})["a.html"]

    assert '<pre><code class="language-mermaid">flowchart LR\n  A --&gt; B\n</code></pre>' in page
    assert "<figure" not in page


def test_mermaid_sources_lists_each_diagram_of_the_markdown_files_once() -> None:
    files = {"a.md": b"---\nstand: x\n---\n# A\n\n```mermaid\nflowchart LR\n  A --> B\n```\n",
             "b.md": b"# B\n\n```mermaid\nflowchart LR\n  A --> B\n```\n\n```text\nnein\n```\n",
             "c.txt": b"```mermaid\nflowchart LR\n  X --> Y\n```\n"}

    assert package_html.mermaid_sources(files) == ["flowchart LR\n  A --> B\n"]


def test_the_svg_id_of_a_diagram_depends_on_its_source_only() -> None:
    first = package_html.diagram_id("flowchart LR\n  A --> B\n")

    assert first == package_html.diagram_id("flowchart LR\n  A --> B\n")
    assert first != package_html.diagram_id("flowchart LR\n  A --> C\n")
    assert re.fullmatch(r"diagram-[0-9a-f]{12}", first)


def test_every_page_links_back_to_the_start_page_from_its_depth() -> None:
    pages = site({"vorlagen/04-datenschutz/loeschkonzept.md": "# Löschkonzept\n",
                  "vorlagen/README.md": "# Vorlagen\n"})

    page = pages["vorlagen/04-datenschutz/loeschkonzept.html"]
    assert '<a href="../../index.html">Start</a>' in page
    assert '<a href="../../vorlagen/README.html">Vorlagen</a>' in page
    assert 'href="../../style.css"' in page
    assert "Handbuch" not in page


def test_the_start_page_shows_the_notice_of_a_trial() -> None:
    start = site({"LIESMICH.md": "# x\n"}, notice="Probelauf: kein veröffentlichtes Release")["index.html"]

    assert "Probelauf: kein veröffentlichtes Release" in start


def test_a_missing_file_or_anchor_is_reported() -> None:
    pages = site({"a.md": "# Titel\n\n[weg](b.md), [Anker](c.md#gibt-es-nicht), [gut](c.md#c),"
                          " [hier](#gibt-es-auch-nicht), [Ordner](d/)\n",
                  "c.md": "# C\n"})
    raw = {path: text.encode("utf-8") for path, text in pages.items()}

    assert package_html.broken_links(raw) == [
        "a.html: b.md", "a.html: c.html#gibt-es-nicht", "a.html: #gibt-es-auch-nicht", "a.html: d/"]


def test_repeated_ids_names_each_id_that_occurs_twice_on_a_page() -> None:
    pages = {"a.html": b'<h1 id="x"></h1><svg id="d1"><path id="e"/></svg><svg id="d2"><path id="e"/></svg>',
             "b.html": b'<h1 id="e"></h1><a name="e"></a>', "c.md": b'id="x" id="x"'}

    assert package_html.repeated_ids(pages) == ["a.html: e"]
