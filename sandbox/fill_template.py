"""Fills a document template for the agent tool fill_template (ADR-0073).

The backend uploads the template and a job file into the sandbox and runs this fixed script from
the image; the model only supplies the data, never code. Run as

    python3 -I /opt/opaa/fill_template.py /workspace/fill_template.json

Job file (JSON, written by the backend):
    template             path of the DOCX template inside the sandbox
    data                 the values for the placeholders: strings, numbers, booleans, lists, objects
    output_dir           a directory that must not exist yet; the results are written there alone
    output_name          base name of the result files, sanitized again here
    formats              any of "docx", "pdf"
    pdf_timeout_seconds  how long LibreOffice may convert, below the script's own time limit
    stamp                optional text for every footer and the document properties, e.g. a draft
                         notice; from the backend, never from the model, and never rendered

The data are inserted as text: Jinja evaluates only the tags of the template, never a value, and
autoescape turns every value into escaped XML text. A template cannot switch that off: the filters
that mark text as raw markup (safe, urlize, xmlattr) and the global lipsum do not exist here, and
a template with an autoescape block is refused before rendering. The tags run in Jinja's immutable
sandbox, so they cannot reach Python internals. A missing value renders empty; a template that
computes with one (round, +) fails and says so.

Prints exactly one JSON line on stdout: {"files": [...], "errors": [...]}, the files as names in
output_dir. Exit code 0 when at least one file was written, 2 when the template could not be
filled, 1 on any other failure.
"""

import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile

SOFFICE = "/usr/bin/soffice"
MAX_NAME_CHARS = 80
DEFAULT_PDF_TIMEOUT_SECONDS = 30
FORMATS = ("docx", "pdf")

# Filters and globals whose result Jinja treats as markup and would insert unescaped.
RAW_MARKUP_FILTERS = ("safe", "urlize", "xmlattr")
RAW_MARKUP_GLOBALS = ("lipsum",)


def template_environment():
    """Jinja's immutable sandbox with autoescape that no template can switch off."""
    from jinja2 import ChainableUndefined, nodes
    from jinja2.exceptions import TemplateSyntaxError
    from jinja2.sandbox import ImmutableSandboxedEnvironment

    class TemplateEnvironment(ImmutableSandboxedEnvironment):

        def _parse(self, source, name, filename):
            tree = super()._parse(source, name, filename)
            for node in tree.find_all((nodes.EvalContextModifier, nodes.MarkSafe,
                                       nodes.MarkSafeIfAutoescape)):
                raise TemplateSyntaxError(
                    "autoescape blocks are not allowed in a template", node.lineno, name, filename)
            for node in tree.find_all((nodes.Filter, nodes.Test)):
                if node.name in RAW_MARKUP_FILTERS:
                    raise TemplateSyntaxError(
                        f"the filter {node.name} is not allowed in a template", node.lineno, name,
                        filename)
            return tree

    environment = TemplateEnvironment(autoescape=True, undefined=ChainableUndefined)
    for name in RAW_MARKUP_FILTERS:
        environment.filters.pop(name, None)
    for name in RAW_MARKUP_GLOBALS:
        environment.globals.pop(name, None)
    return environment


def safe_name(name):
    """A plain file base name: letters, digits, space, dot, dash and underscore only."""
    cleaned = re.sub(r"[^\w .-]", "_", str(name or ""), flags=re.UNICODE).strip(" .")
    cleaned = cleaned[:MAX_NAME_CHARS].strip(" .")
    return cleaned or "Dokument"


def render(template_path, data, target):
    from docxtpl import DocxTemplate

    template = DocxTemplate(template_path)
    template.render(data, jinja_env=template_environment(), autoescape=True)
    template.save(target)


def add_stamp(path, stamp):
    """Appends stamp to every footer definition and writes it to the document properties."""
    import docx

    document = docx.Document(path)
    even_pages = document.settings.odd_and_even_pages_header_footer
    for index, section in enumerate(document.sections):
        footers = [section.footer]
        if section.different_first_page_header_footer:
            footers.append(section.first_page_footer)
        if even_pages:
            footers.append(section.even_page_footer)
        for footer in footers:
            # A linked footer writes into the previous section's definition; once is enough.
            if index == 0 or not footer.is_linked_to_previous:
                footer.add_paragraph(stamp)
    document.core_properties.comments = stamp
    document.save(path)


def convert_to_pdf(docx_path, work_dir, timeout_seconds):
    """The PDF LibreOffice writes for docx_path, or None when it failed or ran out of time."""
    profile = tempfile.mkdtemp(prefix="opaa-lo-")
    try:
        process = subprocess.Popen(
            [
                SOFFICE,
                f"-env:UserInstallation=file://{profile}",
                "--headless",
                "--norestore",
                "--nolockcheck",
                "--convert-to",
                "pdf",
                "--outdir",
                work_dir,
                docx_path,
            ],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        try:
            process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            # soffice starts helper processes; the whole session goes.
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            return None
    except OSError:
        return None
    finally:
        shutil.rmtree(profile, ignore_errors=True)
    pdf = os.path.splitext(docx_path)[0] + ".pdf"
    return pdf if os.path.isfile(pdf) else None


def pdf_timeout(job):
    value = job.get("pdf_timeout_seconds")
    if isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0:
        return float(value)
    return DEFAULT_PDF_TIMEOUT_SECONDS


def fill(job):
    """Fills the template of job into its output directory; returns (exit code, result)."""
    data = job.get("data")
    if not isinstance(data, dict):
        return 2, {"files": [], "errors": ["Die Daten sind kein Objekt."]}
    output_dir = job.get("output_dir")
    if not isinstance(output_dir, str) or not os.path.isabs(output_dir):
        return 1, {"files": [], "errors": ["Kein Ausgabeverzeichnis."]}
    formats = [f for f in job.get("formats") or FORMATS if f in FORMATS] or list(FORMATS)
    base = safe_name(job.get("output_name"))
    stamp = job.get("stamp")
    files, errors = [], []
    work_dir = tempfile.mkdtemp(prefix="opaa-fill-")
    try:
        rendered = os.path.join(work_dir, base + ".docx")
        try:
            render(job["template"], data, rendered)
        except Exception as error:  # every template error is reported, none is raised
            message = str(error).strip().splitlines()
            reason = message[0] if message else ""
            return 2, {
                "files": [],
                "errors": [f"Die Vorlage ließ sich nicht befüllen: {type(error).__name__} {reason}"],
            }
        if isinstance(stamp, str) and stamp.strip():
            add_stamp(rendered, stamp.strip())
        pdf = None
        if "pdf" in formats:
            pdf = convert_to_pdf(rendered, work_dir, pdf_timeout(job))
            if pdf is None:
                errors.append("Die PDF-Fassung ließ sich nicht erzeugen.")
        # A directory of its own that did not exist before: nothing in it predates this call.
        os.makedirs(os.path.dirname(output_dir), exist_ok=True)
        os.mkdir(output_dir, 0o700)
        if "docx" in formats or pdf is None:
            shutil.move(rendered, os.path.join(output_dir, base + ".docx"))
            files.append(base + ".docx")
        if pdf is not None:
            shutil.move(pdf, os.path.join(output_dir, base + ".pdf"))
            files.append(base + ".pdf")
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)
    return 0, {"files": files, "errors": errors}


def main(argv):
    if len(argv) != 2:
        print(json.dumps({"files": [], "errors": ["Aufruf ohne Auftragsdatei."]}))
        return 1
    try:
        with open(argv[1], encoding="utf-8") as handle:
            job = json.load(handle)
        code, result = fill(job)
    except Exception as error:  # the backend reads the result line, never a traceback
        code, result = 1, {"files": [], "errors": [f"Interner Fehler: {type(error).__name__}"]}
    print(json.dumps(result, ensure_ascii=False))
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
