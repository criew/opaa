"""HTML version of the update package (update_package.py), readable offline in any browser.

Every Markdown file of the package gets an HTML file beside it, plus index.html as start page and
style.css. Headings carry ids in GitHub's slug style, so anchors written for GitHub keep working;
relative links to Markdown files lead to their HTML files, links to a folder to its README. Nothing
is loaded from outside the package: no scripts, fonts or styles from the internet, Mermaid diagrams
stay code. Requires markdown-it-py (.github/requirements-ci.txt).
"""

from __future__ import annotations

import html
import posixpath
import re
import urllib.parse
from html.parser import HTMLParser

from markdown_it import MarkdownIt

FRONT_MATTER = re.compile(r"---\n(.*?)\n---\n", re.DOTALL)
SCHEME = re.compile(r"[a-zA-Z][a-zA-Z0-9+.-]*:")
HREF = re.compile(r'(\bhref=")([^"]*)(")')
NOT_IN_SLUG = re.compile(r"[^\w\- ]")

CSS = """\
:root { color-scheme: light dark; --fg: #1f2328; --bg: #ffffff; --muted: #59636e; --line: #d1d9e0;
  --soft: #f6f8fa; --link: #0969da; }
@media (prefers-color-scheme: dark) {
  :root { --fg: #e6edf3; --bg: #0d1117; --muted: #9198a1; --line: #3d444d; --soft: #151b23;
    --link: #4493f8; }
}
body { margin: 0; background: var(--bg); color: var(--fg);
  font: 16px/1.6 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
nav, main { max-width: 60rem; margin: 0 auto; padding: 0 1rem; }
nav { padding-top: .8rem; padding-bottom: .6rem; border-bottom: 1px solid var(--line);
  font-size: .9rem; color: var(--muted); }
main { padding-bottom: 4rem; }
a { color: var(--link); }
h1, h2, h3, h4 { line-height: 1.25; margin-top: 1.8em; }
h1 { border-bottom: 1px solid var(--line); padding-bottom: .3em; }
table { border-collapse: collapse; margin: 1em 0; display: block; overflow-x: auto; }
th, td { border: 1px solid var(--line); padding: .35em .7em; vertical-align: top; text-align: left; }
th { background: var(--soft); }
code { font: .88em ui-monospace, SFMono-Regular, Consolas, monospace; background: var(--soft);
  padding: .1em .3em; border-radius: 4px; }
pre { background: var(--soft); padding: .8em 1em; overflow-x: auto; border-radius: 6px;
  border: 1px solid var(--line); }
pre code { background: none; padding: 0; }
blockquote { margin: 1em 0; padding: .2em 1em; border-left: 4px solid var(--line); color: var(--muted); }
.front-matter { font-size: .82rem; color: var(--muted); background: var(--soft);
  border: 1px solid var(--line); border-radius: 6px; padding: .4em .8em; margin: 1em 0; }
.front-matter dl { display: grid; grid-template-columns: max-content 1fr; gap: .1em 1em; margin: 0; }
.front-matter dt { font-weight: 600; }
.front-matter dd { margin: 0; }
:target { scroll-margin-top: 1rem; background: rgba(255, 200, 0, .18); }
"""

# Entries of the navigation bar: label, target, and the file that must exist for it to show.
NAVIGATION = (
    ("Start", "index.html", "index.html"),
    ("Vorlagen", "vorlagen/README.html", "vorlagen/README.md"),
    ("Handbuch", "handbuch/README.html", "handbuch/README.md"),
    ("Faktenblätter", "index.html#faktenblaetter", "faktenblaetter/faktenblaetter.json"),
    ("Update-Vermerk", "update-vermerk.html", "update-vermerk.md"),
)

PARSER = MarkdownIt("commonmark", {"html": True}).enable(["table", "strikethrough"])


def html_path(path: str) -> str:
    return path[:-3] + ".html"


def slug(text: str) -> str:
    """GitHub's anchor of a heading text: lowercase, letters, digits, '_', '-' and spaces kept, each
    space a '-'."""
    return NOT_IN_SLUG.sub("", text.lower()).replace(" ", "-")


class Slugger:
    """Anchors of one file in github-slugger's way: a repeated slug gets -1, -2, skipping any suffix
    already taken."""

    def __init__(self) -> None:
        self.taken: dict[str, int] = {}

    def anchor(self, text: str) -> str:
        base = result = slug(text)
        while result in self.taken:
            self.taken[base] += 1
            result = f"{base}-{self.taken[base]}"
        self.taken[result] = 0
        return result


def heading_text(inline) -> str:
    """The plain text of a heading, as GitHub slugs it: HTML and link targets left out."""
    return "".join(child.content for child in inline.children or []
                   if child.type in ("text", "code_inline"))


def split_front_matter(text: str) -> tuple[list[tuple[str, str]], str]:
    match = FRONT_MATTER.match(text)
    if match is None:
        return [], text
    pairs = [line.split(":", 1) for line in match.group(1).splitlines() if ":" in line]
    return [(key.strip(), value.strip()) for key, value in pairs], text[match.end():]


def directories(files) -> set[str]:
    found = set()
    for path in files:
        while "/" in path:
            path = path.rsplit("/", 1)[0]
            found.add(path)
    return found


def link_target(href: str, page: str, files, folders: set[str]) -> str:
    """`href` of the Markdown file `page` as it leads in the HTML version: a Markdown file to its HTML
    file, a folder with README.md to that README. Anything else stays as written."""
    if not href or href.startswith(("#", "//")) or SCHEME.match(href):
        return href
    file, hash_sign, anchor = href.partition("#")
    target = posixpath.normpath(posixpath.join(posixpath.dirname(page), urllib.parse.unquote(file)))
    readme = "README.md" if target == "." else f"{target}/README.md"
    if target.endswith(".md") and target in files:
        file = file[:-3] + ".html"
    elif (target == "." or target in folders) and readme in files:
        file = (file if file.endswith("/") else file + "/") + "README.html"
    return file + hash_sign + anchor


def page(path: str, title: str, body: str, files) -> str:
    up = "../" * path.count("/")
    links = [f'<a href="{up}{target}">{html.escape(label)}</a>' for label, target, needs in NAVIGATION
             if needs in files]
    return (
        "<!doctype html>\n"
        '<html lang="de">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        f"<title>{html.escape(title)}</title>\n"
        f'<link rel="stylesheet" href="{up}style.css">\n</head>\n<body>\n'
        f'<nav>{" · ".join(links)} · <span>{html.escape(path)}</span></nav>\n'
        f"<main>\n{body}</main>\n</body>\n</html>\n"
    )


def convert(path: str, text: str, files, folders: set[str]) -> tuple[str, str]:
    """The HTML page of the Markdown file `path` and its title (first level-one heading)."""
    front_matter, body = split_front_matter(text.replace("\r\n", "\n"))
    tokens = PARSER.parse(body)
    slugger = Slugger()
    title = path
    for index, token in enumerate(tokens):
        if token.type == "heading_open":
            text_of = heading_text(tokens[index + 1])
            token.attrSet("id", slugger.anchor(text_of))
            if token.tag == "h1" and title == path:
                title = text_of
        elif token.type == "inline":
            for child in token.children or []:
                if child.type == "link_open":
                    child.attrSet("href", link_target(child.attrGet("href") or "", path, files, folders))
                elif child.type == "html_inline":
                    child.content = HREF.sub(
                        lambda m: m.group(1) + link_target(m.group(2), path, files, folders) + m.group(3),
                        child.content)
    rendered = PARSER.renderer.render(tokens, PARSER.options, {})
    if front_matter:
        rows = "".join(f"<dt>{html.escape(key)}</dt><dd>{html.escape(value)}</dd>"
                       for key, value in front_matter)
        rendered = f'<aside class="front-matter"><dl>{rows}</dl></aside>\n{rendered}'
    return page(html_path(path), title, rendered, files), title


def index(heading: str, notice: str | None, titles: dict[str, str], files, names) -> str:
    """The start page: every file of the package `files` by group, a Markdown file as its HTML page
    with its title; `names` holds every path of the finished package."""

    def link(path: str, label: str | None = None) -> str:
        target = html_path(path) if path.endswith(".md") else path
        return f'<a href="{target}">{html.escape(label or titles.get(path, path))}</a>'

    def items(paths) -> str:
        return "".join(f"<li>{link(p)}</li>" for p in paths)

    def under(prefix: str) -> list[str]:
        return sorted(p for p in files if p.startswith(prefix))

    groups = [
        ("unterlagen", "Unterlagen für Behörden",
         [p for p in ("LIESMICH.md", "vorlagen/README.md", "update-vermerk.md") if p in files]),
        ("handbuch", "Handbuch", [p for p in ("handbuch/README.md", "handbuch/glossar.md") if p in files]),
        ("faktenblaetter", "Faktenblätter", under("faktenblaetter/")),
        ("aenderungen", "Änderungen und Betrieb", under("delta/") + under("betrieb/")),
        ("nachweise", "Nachweise", under("lizenzen/") + under("sbom/")
         + [p for p in ("SHA256SUMS",) if p in files]),
    ]
    body = [f"<h1>{html.escape(heading)}</h1>"]
    if notice:
        body.append(f"<blockquote><p>{html.escape(notice)}</p></blockquote>")
    body.append("<p>Jede Seite liegt daneben auch als Markdown (<code>.md</code>) zum Weiterbearbeiten"
                " und Vergleichen.</p>")
    for anchor, title, paths in groups:
        if paths:
            body.append(f'<h2 id="{anchor}">{html.escape(title)}</h2>\n<ul>{items(paths)}</ul>')
    return page("index.html", heading, "\n".join(body) + "\n", names)


def render(files: dict[str, bytes], heading: str, notice: str | None = None) -> dict[str, bytes]:
    """The HTML files for the package `files` (path to content): one page beside each Markdown file,
    index.html with `heading` and `notice`, and style.css."""
    names = set(files) | {html_path(p) for p in files if p.endswith(".md")} | {"index.html", "style.css"}
    folders = directories(files)
    site: dict[str, bytes] = {}
    titles: dict[str, str] = {}
    for path in sorted(p for p in files if p.endswith(".md")):
        text, titles[path] = convert(path, files[path].decode("utf-8"), names, folders)
        site[html_path(path)] = text.encode("utf-8")
    if "LIESMICH.md" in titles:
        titles["LIESMICH.md"] = "LIESMICH: Inhalt und Prüfung des Pakets"
    site["index.html"] = index(heading, notice, titles, set(files), names).encode("utf-8")
    site["style.css"] = CSS.encode("utf-8")
    return site


class _References(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.ids: set[str] = set()
        self.links: list[str] = []

    def handle_starttag(self, tag, attrs) -> None:
        values = dict(attrs)
        for name in ("id", "name") if tag == "a" else ("id",):
            if values.get(name):
                self.ids.add(values[name])
        for name in ("href", "src"):
            if values.get(name) is not None:
                self.links.append(values[name])


def broken_links(site: dict[str, bytes]) -> list[str]:
    """Every relative link of the HTML pages in `site` (path to content, the whole package) whose
    file is missing or whose anchor names no id of its target page, as '<page>: <link>'."""
    pages: dict[str, _References] = {}
    for path in sorted(p for p in site if p.endswith(".html")):
        parser = _References()
        parser.feed(site[path].decode("utf-8"))
        pages[path] = parser
    broken = []
    for path, parser in pages.items():
        for link in parser.links:
            if link.startswith("//") or SCHEME.match(link):
                continue
            file, _, anchor = link.partition("#")
            target = posixpath.normpath(posixpath.join(posixpath.dirname(path), urllib.parse.unquote(file))) \
                if file else path
            anchor = urllib.parse.unquote(anchor)
            if target not in site:
                broken.append(f"{path}: {link}")
            elif anchor and (target not in pages or anchor not in pages[target].ids):
                broken.append(f"{path}: {link}")
    return broken
