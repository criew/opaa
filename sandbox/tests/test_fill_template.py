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
        templates = [
            field.replace("FILTER", "|safe"),
            "{% filter safe %}{{ x }}{% endfilter %}",
            "{% autoescape false %}{{ x }}{% endautoescape %}",
            "{{ [x]|map('safe')|join }}",
            "{{ x|urlize }}",
            "{{ {'a': x}|xmlattr }}",
            "{{ lipsum(1, true) }}",
        ]
        for template in templates:
            with self.subTest(template=template):
                shutil.rmtree(self.out, ignore_errors=True)
                make_template(self.template, [template])

                code, result = fill_template.fill(self.job({"x": "<w:br/>"}))

                if code == 0:
                    self.assertNotIn("<w:fldSimple", document_xml(self.result_docx()))
                    self.assertNotIn("<w:br/>", document_xml(self.result_docx()))
                else:
                    self.assertEqual(2, code, result)
                    self.assertEqual([], result["files"])
        make_template(self.template, [field.replace("FILTER", "")])
        shutil.rmtree(self.out, ignore_errors=True)
        code, result = fill_template.fill(self.job({}))
        self.assertEqual(0, code, result)
        self.assertNotIn("<w:fldSimple", document_xml(self.result_docx()))

    def test_a_tag_of_the_template_cannot_reach_python_internals(self):
        make_template(self.template, ["{{ ''.__class__.__mro__[1].__subclasses__() }}"])

        code, result = fill_template.fill(self.job({}))

        self.assertEqual(2, code)
        self.assertEqual([], result["files"])
        self.assertIn("SecurityError", result["errors"][0])

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
            self.job({"betreff": "Test"}, formats=["docx", "pdf"], pdf_timeout_seconds=60))

        self.assertEqual(0, code, result)
        self.assertEqual(["Bericht.docx", "Bericht.pdf"], result["files"])
        self.assertEqual([], result["errors"])
        with open(os.path.join(self.out, "Bericht.pdf"), "rb") as pdf:
            self.assertEqual(b"%PDF-", pdf.read(5))

    def test_a_conversion_beyond_its_time_keeps_the_docx(self):
        make_template(self.template, ["{{ x }}"])
        hanging = os.path.join(self.dir, "soffice")
        with open(hanging, "w", encoding="utf-8") as script:
            script.write("#!/bin/sh\nsleep 30\n")
        os.chmod(hanging, 0o755)
        original = fill_template.SOFFICE
        fill_template.SOFFICE = hanging
        try:
            started = time.monotonic()
            code, result = fill_template.fill(
                self.job({"x": "1"}, formats=["pdf"], pdf_timeout_seconds=1))
        finally:
            fill_template.SOFFICE = original

        self.assertLess(time.monotonic() - started, 10)
        self.assertEqual(0, code, result)
        self.assertEqual(["Bericht.docx"], result["files"])
        self.assertIn("PDF", result["errors"][0])


if __name__ == "__main__":
    unittest.main()
