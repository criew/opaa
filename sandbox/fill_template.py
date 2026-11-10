"""Fills a document template for the agent tool fill_template (ADR-0073).

The backend uploads the template and a job file into the sandbox and runs this fixed script from
the image; the model only supplies the data, never code. Run as

    python3 -I /opt/opaa/fill_template.py /workspace/fill_template.json

Job file (JSON, written by the backend):
    template     path of the DOCX template inside the sandbox
    data         the values for the placeholders: strings, numbers, booleans, lists and objects
    output_name  base name of the result files, sanitized again here
    formats      any of "docx", "pdf"
    stamp        optional text the backend wants in every footer and in the document properties,
                 e.g. a draft notice; never from the model

The data are inserted as text: Jinja evaluates only the tags of the template, never a value, and
autoescape keeps a value from becoming Word XML. The template's own tags run in Jinja's immutable
sandbox, so a tag cannot reach Python internals either.

Prints exactly one JSON line on stdout: {"files": [...], "errors": [...]}. Exit code 0 when at
least one file was written, 2 when the template could not be filled, 1 on any other failure.
"""

import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

OUTPUT_DIR = "/workspace/out"
MAX_NAME_CHARS = 80
PDF_TIMEOUT_SECONDS = 120
FORMATS = ("docx", "pdf")


def safe_name(name):
    """A plain file base name: letters, digits, space, dot, dash and underscore only."""
    cleaned = re.sub(r"[^\w .-]", "_", str(name or ""), flags=re.UNICODE).strip(" .")
    cleaned = cleaned[:MAX_NAME_CHARS].strip(" .")
    return cleaned or "Dokument"


def free_path(directory, base, extension):
    """A path in directory that no earlier call of this run has taken."""
    candidate = os.path.join(directory, f"{base}.{extension}")
    counter = 2
    while os.path.exists(candidate):
        candidate = os.path.join(directory, f"{base}-{counter}.{extension}")
        counter += 1
    return candidate


def render(template_path, data, target):
    from docxtpl import DocxTemplate
    from jinja2.sandbox import ImmutableSandboxedEnvironment

    template = DocxTemplate(template_path)
    environment = ImmutableSandboxedEnvironment(autoescape=True)
    template.render(data, jinja_env=environment, autoescape=True)
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


def convert_to_pdf(docx_path, work_dir):
    """The PDF LibreOffice writes for docx_path, or None when the conversion failed."""
    profile = tempfile.mkdtemp(prefix="opaa-lo-")
    try:
        subprocess.run(
            [
                "soffice",
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
            timeout=PDF_TIMEOUT_SECONDS,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    finally:
        shutil.rmtree(profile, ignore_errors=True)
    pdf = os.path.splitext(docx_path)[0] + ".pdf"
    return pdf if os.path.isfile(pdf) else None


def fill(job, output_dir=OUTPUT_DIR):
    """Fills the template of job into output_dir; returns (exit code, result)."""
    data = job.get("data")
    if not isinstance(data, dict):
        return 2, {"files": [], "errors": ["Die Daten sind kein Objekt."]}
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
        os.makedirs(output_dir, exist_ok=True)
        if "pdf" in formats:
            pdf = convert_to_pdf(rendered, work_dir)
            if pdf is None:
                errors.append("Die PDF-Fassung ließ sich nicht erzeugen.")
            else:
                target = free_path(output_dir, base, "pdf")
                shutil.move(pdf, target)
                files.append(os.path.basename(target))
        if "docx" in formats or not files:
            target = free_path(output_dir, base, "docx")
            shutil.move(rendered, target)
            files.insert(0, os.path.basename(target))
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
