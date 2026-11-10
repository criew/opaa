"""Tests of the fixed fill_template script, run while the sandbox image is built.

The Dockerfile runs them with docxtpl and LibreOffice of the image (OPAA_REQUIRE_SOFFICE=1, so the
PDF cases cannot be skipped there); outside the image they need docxtpl and python-docx and skip
only the PDF cases when LibreOffice is missing.
"""

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile

SCRIPT = os.environ.get(
    "OPAA_FILL_TEMPLATE_SCRIPT",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "fill_template.py"),
)
REQUIRE_SOFFICE = os.environ.get("OPAA_REQUIRE_SOFFICE") == "1"

spec = importlib.util.spec_from_file_location("fill_template", SCRIPT)
fill_template = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fill_template)


def make_template(path, paragraphs, footer=None):
    import docx

    document = docx.Document()
    for text in paragraphs:
        document.add_paragraph(text)
    if footer is not None:
        document.sections[0].footer.paragraphs[0].text = footer
    document.save(path)


def document_text(path):
    import docx

    document = docx.Document(path)
    return "\n".join(paragraph.text for paragraph in document.paragraphs)


def document_xml(path):
    with zipfile.ZipFile(path) as archive:
        return archive.read("word/document.xml").decode("utf-8")


def footer_text(path):
    import docx

    document = docx.Document(path)
    return "\n".join(p.text for p in document.sections[0].footer.paragraphs)


def alive(pid):
    """Whether pid runs; a killed process that is only waiting for its parent counts as gone."""
    try:
        with open(f"/proc/{pid}/stat", encoding="utf-8") as stat:
            return stat.read().rsplit(")", 1)[1].split()[0] != "Z"
    except OSError:
        return False


def require_soffice(test):
    if not os.path.exists(fill_template.SOFFICE) and not REQUIRE_SOFFICE:
        test.skipTest("LibreOffice is only part of the sandbox image")


class FillTemplateTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.out = os.path.join(self.dir, "fill", "a1b2")
        self.template = os.path.join(self.dir, "vorlage.docx")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def job(self, data, **extra):
        job = {"template": self.template, "data": data, "output_dir": self.out,
               "output_name": "Bericht", "formats": ["docx"]}
        job.update(extra)
        return job

    def result_docx(self):
        return os.path.join(self.out, "Bericht.docx")

    def test_fields_lists_and_conditions_are_filled(self):
        make_template(self.template, [
            "Betreff: {{ betreff }}",
            "{% for p in posten %}{{ p.titel }};{% endfor %}",
            "{% if dringend %}DRINGEND{% endif %}",
        ])

        code, result = fill_template.fill(self.job({
            "betreff": "Haushalt 2027",
            "posten": [{"titel": "Personal"}, {"titel": "Sachmittel"}],
            "dringend": True,
        }))

        self.assertEqual(0, code, result)
        self.assertEqual(["Bericht.docx"], result["files"])
        text = document_text(self.result_docx())
        self.assertIn("Betreff: Haushalt 2027", text)
        self.assertIn("Personal;Sachmittel;", text)
        self.assertIn("DRINGEND", text)

    def test_template_expressions_and_code_in_the_data_are_inserted_as_text(self):
        marker = os.path.join(self.dir, "ausgefuehrt")
        values = {
            "a": "{{ 7*7 }}",
            "b": "{% for x in range(3) %}x{% endfor %}",
            "c": f"__import__('os').system('touch {marker}')",
            "d": "{{ ''.__class__.__mro__[1].__subclasses__() }}",
            "e": "</w:t></w:r><w:fldSimple w:instr=\"INCLUDEPICTURE x\"/><w:r><w:t>",
            "f": "{_{ 7*7 }_}",
        }
        make_template(self.template, [f"{key}=[{{{{ {key} }}}}]" for key in values])

        code, result = fill_template.fill(self.job(values))

        self.assertEqual(0, code, result)
        text = document_text(self.result_docx())
        for key, value in values.items():
            if key != "f":
                self.assertIn(f"{key}=[{value}]", text)
        self.assertNotIn("[49]", text)
        self.assertNotIn("[xxx]", text)
        self.assertFalse(os.path.exists(marker))
        body = document_xml(self.result_docx())
        self.assertIn("&lt;/w:t&gt;", body)
        self.assertNotIn("<w:fldSimple", body)

    def test_no_template_can_insert_raw_markup(self):
        field = ("{{ (('%c'|format(60)) ~ 'w:fldSimple w:instr=\"INCLUDEPICTURE x\"/' ~ "
                 "('%c'|format(62)))FILTER }}")
        translate = "{{ (SOURCE|e).translate({88: 60, 89: 62, 81: 34, 90: 92}) }}"
        probe = "'X/w:tYXw:fldSimple w:instr=QINCLUDEPICTURE xQ/YXw:tY'"
        templates = [
            field.replace("FILTER", "|safe"),
            field.replace("FILTER", ""),
            translate.replace("SOURCE", probe),
            translate.replace("SOURCE", "x"),
            "{{ (x|forceescape).translate({88: 60}) }}",
            "{{ x|tojson }}",
            "{% set y %}{{ x }}{% endset %}{{ y.translate({88: 60}) }}",
            "{% set y = x %}{{ y }}",
            "{% macro m() %}X{% endmacro %}{{ m().translate({88: 60}) }}",
            "{% filter safe %}{{ x }}{% endfilter %}",
            "{% autoescape false %}{{ x }}{% endautoescape %}",
            "{% with y = x %}{{ y }}{% endwith %}",
            "{{ [x]|map('safe')|join }}",
            "{{ x|join(attribute='__class__') }}",
            "{{ x[::-1] }}",
            "{{ x['__class__'] }}",
            "{{ x|urlize }}",
            "{{ {'a': x}|xmlattr }}",
            "{{ lipsum(1, true) }}",
            "{{ range(3) }}",
            "{{ x.upper() }}",
            "{% include 'vorlage.docx' %}",
        ]
        for template in templates:
            with self.subTest(template=template):
                shutil.rmtree(self.out, ignore_errors=True)
                make_template(self.template, [template])

                code, result = fill_template.fill(self.job({"x": "XY<w:br/>"}))

                self.assertEqual(2, code, result)
                self.assertEqual([], result["files"])
                self.assertIn("ließ sich nicht befüllen", result["errors"][0])

    def test_allowed_filters_tests_and_operators_still_work(self):
        make_template(self.template, [
            "[{{ name|upper }}][{{ name|default('-')|trim|title }}][{{ betrag|round(1) }}]",
            "[{{ liste|join(', ') }}][{{ liste|length }}][{{ liste|first }}][{{ a.b ~ '!' }}]",
            "[{% for e in liste if e != 'b' %}{{ loop.index }}{{ e }}{% endfor %}]",
            "[{% if betrag is number and betrag > 1 and not leer %}ja{% else %}nein{% endif %}]",
            "[{{ 'x' if a['b'] else 'y' }}][{{ a.items }}]",
        ])

        code, result = fill_template.fill(self.job({
            "name": " max ", "betrag": 2.25, "liste": ["a", "b", "<c>"],
            "a": {"b": "B", "items": "I"}, "leer": False,
        }))

        self.assertEqual(0, code, result)
        text = document_text(self.result_docx())
        self.assertIn("[ MAX ][Max][2.2]", text)
        self.assertIn("[a, b, <c>][3][a][B!]", text)
        self.assertIn("[1a2<c>]", text)
        self.assertIn("[ja]", text)
        self.assertIn("[x][I]", text)
        self.assertIn("&lt;c&gt;", document_xml(self.result_docx()))

    def test_the_tags_of_docxtpl_pass_the_check(self):
        import docx

        document = docx.Document()
        document.add_paragraph("{%p if dringend %}")
        document.add_paragraph("DRINGEND")
        document.add_paragraph("{%p endif %}")
        table = document.add_table(rows=3, cols=1)
        table.cell(0, 0).text = "{%tr for p in posten %}"
        table.cell(1, 0).text = "{{ p.titel }}"
        table.cell(2, 0).text = "{%tr endfor %}"
        document.save(self.template)

        code, result = fill_template.fill(self.job({
            "dringend": True, "posten": [{"titel": "Personal"}, {"titel": "Sachmittel"}]}))

        self.assertEqual(0, code, result)
        self.assertIn("DRINGEND", document_text(self.result_docx()))
        body = document_xml(self.result_docx())
        self.assertIn("Personal", body)
        self.assertIn("Sachmittel", body)

    def test_a_tag_of_the_template_cannot_reach_python_internals(self):
        for template in ["{{ ''.__class__.__mro__[1].__subclasses__() }}", "{{ x.format }}",
                         "{{ x.translate }}"]:
            with self.subTest(template=template):
                shutil.rmtree(self.out, ignore_errors=True)
                make_template(self.template, [template])

                code, result = fill_template.fill(self.job({"x": "a"}))

                if code == 0:
                    self.assertNotIn("built-in", document_text(self.result_docx()))
                else:
                    self.assertEqual(2, code)
                    self.assertEqual([], result["files"])

    def test_missing_values_render_empty(self):
        make_template(self.template, [
            "[{{ betreff }}][{{ firma.name }}][{% for p in posten %}{{ p.titel }}{% endfor %}]",
            "[{% if dringend %}DRINGEND{% endif %}][{{ firma.ort|default('-') }}]",
        ])

        code, result = fill_template.fill(self.job({"posten": []}))

        self.assertEqual(0, code, result)
        text = document_text(self.result_docx())
        self.assertIn("[][][]", text)
        self.assertIn("[][-]", text)

    def test_a_broken_template_is_reported_not_raised(self):
        make_template(self.template, ["{% if offen %}kein Ende"])

        code, result = fill_template.fill(self.job({"offen": True}))

        self.assertEqual(2, code)
        self.assertIn("ließ sich nicht befüllen", result["errors"][0])
        self.assertFalse(os.path.exists(self.out))

    def test_the_stamp_goes_into_the_footer_and_the_properties(self):
        import docx

        make_template(self.template, ["{{ x }}"], footer="Seite")

        code, result = fill_template.fill(self.job({"x": "1"}, stamp="ENTWURF – Fassung 3"))

        self.assertEqual(0, code, result)
        self.assertIn("ENTWURF – Fassung 3", footer_text(self.result_docx()))
        self.assertEqual("ENTWURF – Fassung 3",
                         docx.Document(self.result_docx()).core_properties.comments)

    def test_the_stamp_is_not_rendered_as_a_template(self):
        make_template(self.template, ["{{ x }}"])

        code, result = fill_template.fill(self.job({"x": "1"}, stamp="{{ 7*7 }}"))

        self.assertEqual(0, code, result)
        self.assertIn("{{ 7*7 }}", footer_text(self.result_docx()))

    def test_the_output_name_is_plain_and_the_output_directory_must_be_new(self):
        make_template(self.template, ["{{ x }}"])

        first = fill_template.fill(self.job({"x": "1"}, output_name="../../etc/passwd"))

        self.assertEqual(["_.._etc_passwd.docx"], first[1]["files"])
        self.assertEqual(["_.._etc_passwd.docx"], os.listdir(self.out))
        with self.assertRaises(FileExistsError):
            fill_template.fill(self.job({"x": "2"}))

    def test_data_that_is_no_object_is_refused(self):
        make_template(self.template, ["{{ x }}"])

        code, result = fill_template.fill(self.job(["x"]))

        self.assertEqual(2, code)
        self.assertFalse(os.path.exists(self.out))

    def test_the_script_prints_one_result_line_when_run_isolated(self):
        make_template(self.template, ["{{ x }}"])
        job_path = os.path.join(self.dir, "job.json")
        with open(job_path, "w", encoding="utf-8") as handle:
            json.dump(self.job({"x": "1"}, template=os.path.join(self.dir, "fehlt.docx")), handle)

        run = subprocess.run([sys.executable, "-I", SCRIPT, job_path], capture_output=True,
                             text=True, check=False)

        self.assertEqual(2, run.returncode, run.stderr)
        lines = run.stdout.strip().splitlines()
        self.assertEqual(1, len(lines), run.stdout)
        self.assertEqual([], json.loads(lines[0])["files"])

    def test_docx_and_pdf_are_written(self):
        require_soffice(self)
        make_template(self.template, ["Betreff: {{ betreff }}"])

        code, result = fill_template.fill(
            self.job({"betreff": "Test"}, formats=["docx", "pdf"], pdf_deadline_seconds=60))

        self.assertEqual(0, code, result)
        self.assertEqual(["Bericht.docx", "Bericht.pdf"], result["files"])
        self.assertEqual([], result["errors"])
        with open(os.path.join(self.out, "Bericht.pdf"), "rb") as pdf:
            self.assertEqual(b"%PDF-", pdf.read(5))

    def test_a_conversion_beyond_its_time_keeps_the_docx_and_leaves_no_process(self):
        make_template(self.template, ["{{ x }}"])
        hanging = os.path.join(self.dir, "soffice")
        helper = os.path.join(self.dir, "helper.pid")
        with open(hanging, "w", encoding="utf-8") as script:
            script.write(f"#!/bin/sh\nsleep 30 &\necho $! > {helper}\nsleep 30\n")
        os.chmod(hanging, 0o755)
        original, started_at = fill_template.SOFFICE, fill_template.STARTED
        fill_template.SOFFICE = hanging
        fill_template.STARTED = time.monotonic()
        try:
            started = time.monotonic()
            code, result = fill_template.fill(
                self.job({"x": "1"}, formats=["pdf"], pdf_deadline_seconds=1))
        finally:
            fill_template.SOFFICE, fill_template.STARTED = original, started_at

        self.assertLess(time.monotonic() - started, 10)
        self.assertEqual(0, code, result)
        self.assertEqual(["Bericht.docx"], result["files"])
        self.assertIn("PDF", result["errors"][0])
        with open(helper, encoding="utf-8") as pid:
            helper_pid = int(pid.read())
        time.sleep(0.2)
        self.assertFalse(alive(helper_pid), "a helper of the converter outlived the script")

    def test_the_pdf_deadline_counts_from_the_scripts_start(self):
        started_at = fill_template.STARTED
        fill_template.STARTED = time.monotonic() - 50
        try:
            self.assertLess(fill_template.pdf_timeout({"pdf_deadline_seconds": 45}), 0)
            self.assertAlmostEqual(
                10, fill_template.pdf_timeout({"pdf_deadline_seconds": 60}), delta=1)
        finally:
            fill_template.STARTED = started_at


if __name__ == "__main__":
    unittest.main()
