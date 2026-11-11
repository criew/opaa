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
    pdf_deadline_seconds seconds after the script's start by which the PDF must be done, below
                         the script's own time limit
    stamp                optional text for every footer and the document properties, e.g. a draft
                         notice; from the backend, never from the model, and never rendered

The data are inserted as text: Jinja evaluates only the tags of the template, never a value, and
autoescape turns every value into escaped XML text. A template may use only what fields, lists and
conditions need (ALLOWED_NODES, ALLOWED_FILTERS, ALLOWED_TESTS): no call of any function or method,
no assignment, macro, block, filter block or autoescape block, no recursive loop, no
multiplication, division or modulo, no slice and no name or key that starts with an underscore.
Everything else is refused before rendering, so no template can produce text Jinja treats as
markup. The tags run in Jinja's immutable sandbox without globals; a.b and a['b'] read only the
data or the loop variable's attributes, anything else is undefined. A missing value renders empty;
a template that computes with one (round, +) fails and says so.

Prints exactly one JSON line on stdout: {"files": [...], "errors": [...]}, the files as names in
output_dir. Exit code 0 when at least one file was written, 2 when the template could not be
filled, 1 on any other failure.
"""

import json
import os
import re
import unicodedata
import shutil
import signal
import subprocess
import sys
import tempfile
import time

SOFFICE = "/usr/bin/soffice"
MAX_NAME_CHARS = 80
MAX_PROPERTY_CHARS = 255
DEFAULT_PDF_DEADLINE_SECONDS = 30
FORMATS = ("docx", "pdf")

# When the script started; the PDF deadline of the job counts from here.
STARTED = time.monotonic()

# The node types a template may contain: text, output, names, parts of the data, loops,
# conditions, comparisons, adding and subtracting, and concatenation. docxtpl's own tags ({%p %},
# {%tr %}, {%tc %}, {%r %}, vm, colspan, cellbg) become exactly these before Jinja parses the
# template; its hm becomes "<digits> * loop.length", the one multiplication allowed (DOCXTPL_SPAN).
ALLOWED_NODES = (
    "Template", "Output", "TemplateData", "Const", "Name", "Getattr", "Getitem", "For", "If",
    "CondExpr", "Compare", "Operand", "And", "Or", "Not", "Neg", "Pos", "Add", "Sub", "Concat",
    "Filter", "Test", "Tuple", "List",
)

# The attributes of the loop variable a template may read; its methods (cycle, changed) are not.
LOOP_ATTRIBUTES = (
    "index", "index0", "revindex", "revindex0", "first", "last", "length", "depth", "depth0",
    "previtem", "nextitem",
)

# Filters that give text, a number or a list of values and never mark anything as markup.
ALLOWED_FILTERS = (
    "default", "d", "upper", "lower", "title", "capitalize", "trim", "round", "int", "float", "abs",
    "join", "length", "count", "first", "last",
)

ALLOWED_TESTS = (
    "defined", "undefined", "none", "number", "string", "boolean", "true", "false", "odd", "even",
    "divisibleby", "eq", "ne", "lt", "le", "gt", "ge", "in",
)

# Names that only exist inside constructs a template may not use or that reach the template object.
FORBIDDEN_NAMES = ("self", "caller", "varargs", "kwargs")


def check_template(tree, name=None, filename=None):
    """Refuses a parsed template that uses anything beyond ALLOWED_NODES and the allowed names."""
    from jinja2 import nodes
    from jinja2.exceptions import TemplateSyntaxError

    def refuse(node, what):
        raise TemplateSyntaxError(f"{what} is not allowed in a template", node.lineno, name,
                                  filename)

    def plain(value):
        return not isinstance(value, str) or not value.startswith("_")

    def docxtpl_span(node):
        return (isinstance(node, nodes.Mul) and isinstance(node.left, nodes.Const)
                and type(node.left.value) is int and isinstance(node.right, nodes.Getattr)
                and isinstance(node.right.node, nodes.Name) and node.right.node.name == "loop"
                and node.right.attr == "length")

    for node in [tree, *tree.find_all(nodes.Node)]:
        kind = type(node).__name__
        if kind not in ALLOWED_NODES and not docxtpl_span(node):
            refuse(node, kind)
        if isinstance(node, nodes.Name) and (node.name.startswith("_")
                                             or node.name in FORBIDDEN_NAMES):
            refuse(node, f"the name {node.name}")
        if isinstance(node, nodes.Getattr) and node.attr.startswith("_"):
            refuse(node, f"the attribute {node.attr}")
        if isinstance(node, nodes.Getitem) and not (isinstance(node.arg, nodes.Const)
                                                    and plain(node.arg.value)):
            refuse(node, "a key that is not a plain constant")
        if isinstance(node, nodes.For) and node.recursive:
            refuse(node, "a recursive loop")
        if isinstance(node, nodes.Filter) and node.name not in ALLOWED_FILTERS:
            refuse(node, f"the filter {node.name}")
        if isinstance(node, nodes.Test) and node.name not in ALLOWED_TESTS:
            refuse(node, f"the test {node.name}")
        if isinstance(node, (nodes.Filter, nodes.Test)) and (
                node.kwargs or node.dyn_args or node.dyn_kwargs
                or not all(isinstance(arg, nodes.Const) and plain(arg.value)
                           for arg in node.args)):
            refuse(node, f"an argument of {node.name} that is not a plain constant")


def template_environment():
    """Jinja's immutable sandbox with autoescape and without globals; templates are checked."""
    from jinja2 import ChainableUndefined
    from jinja2.runtime import LoopContext
    from jinja2.sandbox import ImmutableSandboxedEnvironment

    class TemplateEnvironment(ImmutableSandboxedEnvironment):

        def _parse(self, source, name, filename):
            tree = super()._parse(source, name, filename)
            check_template(tree, name, filename)
            return tree

        def is_safe_attribute(self, obj, attr, value):
            # Only the loop variable's plain attributes; a string or Markup exposes nothing.
            return isinstance(obj, LoopContext) and attr in LOOP_ATTRIBUTES

        def getattr(self, obj, attribute):
            # a.b of the data is the member b or undefined, never a method of dict or list; of
            # the loop variable only LOOP_ATTRIBUTES; of anything else undefined.
            if isinstance(obj, dict):
                return self.getitem(obj, attribute)
            if isinstance(obj, LoopContext) and attribute in LOOP_ATTRIBUTES:
                return getattr(obj, attribute)
            return self.undefined(obj=obj, name=attribute)

        def getitem(self, obj, argument):
            # a['b'] and a[0] read the data only, never fall back to an attribute.
            if isinstance(obj, dict):
                if isinstance(argument, str) and argument in obj:
                    return obj[argument]
            elif (isinstance(obj, (list, tuple, str)) and type(argument) is int
                  and -len(obj) <= argument < len(obj)):
                return obj[argument]
            return self.undefined(obj=obj, name=argument)

    environment = TemplateEnvironment(autoescape=True, undefined=ChainableUndefined)
    environment.globals.clear()
    environment.filters = {name: environment.filters[name] for name in ALLOWED_FILTERS}
    environment.tests = {name: environment.tests[name] for name in ALLOWED_TESTS}
    return environment


def safe_name(name):
    """A plain file base name: letters, digits, space, dot, dash and underscore only."""
    normalized = unicodedata.normalize("NFC", str(name or ""))
    cleaned = re.sub(r"[^\w .-]", "_", normalized, flags=re.UNICODE).lstrip(" .-").rstrip(" .")
    cleaned = cleaned[:MAX_NAME_CHARS].strip(" .")
    return cleaned or "Dokument"


def render(template_path, data, target):
    from docxtpl import DocxTemplate

    template = DocxTemplate(template_path)
    template.render(data, jinja_env=template_environment(), autoescape=True)
    template.save(target)


def add_stamp(path, stamp):
    """Appends stamp to every footer definition and writes it to the document properties.

    Comments keep it in the DOCX; the subject is what the PDF export carries into its own
    document information, so a PDF shows the stamp in its footer and its properties alike.
    """
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
    # python-docx refuses core properties longer than 255 characters; the footer keeps the full stamp.
    document.core_properties.comments = stamp[:MAX_PROPERTY_CHARS]
    document.core_properties.subject = stamp[:MAX_PROPERTY_CHARS]
    document.save(path)


def descendants(pid):
    """The ids of all processes below pid, read from /proc.

    The script and LibreOffice run as the same user in a sandbox of the run alone, so /proc shows
    every helper the converter started and no process of another run or person.
    """
    children = {}
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            with open(f"/proc/{entry}/stat", encoding="utf-8") as stat:
                parent = int(stat.read().rsplit(")", 1)[1].split()[1])
        except (OSError, ValueError, IndexError):
            continue
        children.setdefault(parent, []).append(int(entry))
    found, pending = [], [pid]
    while pending:
        for child in children.get(pending.pop(), []):
            found.append(child)
            pending.append(child)
    return found


def kill_tree(pid):
    """Kills pid and every process it started, so no converter outlives the script."""
    for target in [pid, *descendants(pid)]:
        try:
            os.kill(target, signal.SIGKILL)
        except OSError:
            pass


def convert_to_pdf(docx_path, work_dir, timeout_seconds):
    """The PDF LibreOffice writes for docx_path, or None when it failed or ran out of time.

    LibreOffice stays in the script's process group, so stopping the script's group stops it too;
    at its own time limit, and on SIGTERM, the script kills it with every helper it started.
    """
    if timeout_seconds <= 0:
        return None
    profile = tempfile.mkdtemp(prefix="opaa-lo-")
    process = None

    def stop(signum, frame):
        if process is not None:
            kill_tree(process.pid)
        raise SystemExit(128 + signum)

    previous = signal.signal(signal.SIGTERM, stop)
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
        )
        try:
            process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired:
            kill_tree(process.pid)
            process.wait()
            return None
    except OSError:
        return None
    finally:
        signal.signal(signal.SIGTERM, previous)
        shutil.rmtree(profile, ignore_errors=True)
    pdf = os.path.splitext(docx_path)[0] + ".pdf"
    return pdf if os.path.isfile(pdf) else None


def pdf_timeout(job):
    """What is left of the job's PDF deadline, counted from the script's start."""
    value = job.get("pdf_deadline_seconds")
    if not isinstance(value, (int, float)) or isinstance(value, bool) or value <= 0:
        value = DEFAULT_PDF_DEADLINE_SECONDS
    return float(value) - (time.monotonic() - STARTED)


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
            try:
                add_stamp(rendered, stamp.strip())
            except ValueError:  # a character XML cannot hold; the file is never left unstamped
                return 2, {
                    "files": [],
                    "errors": ["Der Fassungsstempel ließ sich nicht in die Datei schreiben."],
                }
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
