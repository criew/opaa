"""Tests of the fixed fill_template script, run while the sandbox image is built.

The Dockerfile runs them with docxtpl and LibreOffice of the image (OPAA_REQUIRE_SOFFICE=1, so the
PDF case cannot be skipped there); outside the image they need docxtpl and python-docx and skip
only the PDF case when soffice is missing.
"""

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
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


def footer_text(path):
    import docx

    document = docx.Document(path)
    return "\n".join(p.text for p in document.sections[0].footer.paragraphs)


class FillTemplateTest(unittest.TestCase):

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.out = os.path.join(self.dir, "out")
        self.template = os.path.join(self.dir, "vorlage.docx")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def job(self, data, **extra):
        job = {"template": self.template, "data": data, "output_name": "Bericht",
               "formats": ["docx"]}
        job.update(extra)
        return job

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
        }), self.out)

        self.assertEqual(0, code, result)
        self.assertEqual(["Bericht.docx"], result["files"])
        text = document_text(os.path.join(self.out, "Bericht.docx"))
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
            "e": "</w:t></w:r><w:r><w:t>eingeschleust & <b>",
            "f": "{_{ 7*7 }_}",
        }
        make_template(self.template, [f"{key}=[{{{{ {key} }}}}]" for key in values])

        code, result = fill_template.fill(self.job(values), self.out)

        self.assertEqual(0, code, result)
        path = os.path.join(self.out, "Bericht.docx")
        text = document_text(path)
        for key, value in values.items():
            if key != "f":
                self.assertIn(f"{key}=[{value}]", text)
        self.assertNotIn("[49]", text)
        self.assertNotIn("[xxx]", text)
        self.assertFalse(os.path.exists(marker))
        with zipfile.ZipFile(path) as archive:
            body = archive.read("word/document.xml").decode("utf-8")
        self.assertIn("&lt;/w:t&gt;", body)

    def test_a_tag_of_the_template_cannot_reach_python_internals(self):
        make_template(self.template, ["{{ ''.__class__.__mro__[1].__subclasses__() }}"])

        code, result = fill_template.fill(self.job({}), self.out)

        self.assertEqual(2, code)
        self.assertEqual([], result["files"])
        self.assertIn("SecurityError", result["errors"][0])

    def test_a_broken_template_is_reported_not_raised(self):
        make_template(self.template, ["{% if offen %}kein Ende"])

        code, result = fill_template.fill(self.job({"offen": True}), self.out)

        self.assertEqual(2, code)
        self.assertIn("ließ sich nicht befüllen", result["errors"][0])

    def test_the_stamp_goes_into_the_footer_and_the_properties(self):
        import docx

        make_template(self.template, ["{{ x }}"], footer="Seite")

        code, result = fill_template.fill(
            self.job({"x": "1"}, stamp="ENTWURF – Fassung 3"), self.out)

        self.assertEqual(0, code, result)
        path = os.path.join(self.out, "Bericht.docx")
        self.assertIn("ENTWURF – Fassung 3", footer_text(path))
        self.assertEqual("ENTWURF – Fassung 3", docx.Document(path).core_properties.comments)

    def test_the_stamp_is_not_rendered_as_a_template(self):
        make_template(self.template, ["{{ x }}"])

        code, result = fill_template.fill(self.job({"x": "1"}, stamp="{{ 7*7 }}"), self.out)

        self.assertEqual(0, code, result)
        self.assertIn("{{ 7*7 }}", footer_text(os.path.join(self.out, "Bericht.docx")))

    def test_the_output_name_stays_in_the_output_directory_and_never_overwrites(self):
        make_template(self.template, ["{{ x }}"])

        first = fill_template.fill(self.job({"x": "1"}, output_name="../../etc/passwd"), self.out)
        second = fill_template.fill(self.job({"x": "2"}, output_name="../../etc/passwd"), self.out)

        self.assertEqual(["_.._etc_passwd.docx"], first[1]["files"])
        self.assertEqual(["_.._etc_passwd-2.docx"], second[1]["files"])
        self.assertEqual(sorted(os.listdir(self.out)), ["_.._etc_passwd-2.docx",
                                                        "_.._etc_passwd.docx"])

    def test_data_that_is_no_object_is_refused(self):
        make_template(self.template, ["{{ x }}"])

        code, result = fill_template.fill(self.job(["x"]), self.out)

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
        if shutil.which("soffice") is None and not REQUIRE_SOFFICE:
            self.skipTest("LibreOffice is only part of the sandbox image")
        make_template(self.template, ["Betreff: {{ betreff }}"])

        code, result = fill_template.fill(
            self.job({"betreff": "Test"}, formats=["docx", "pdf"]), self.out)

        self.assertEqual(0, code, result)
        self.assertEqual(["Bericht.docx", "Bericht.pdf"], result["files"])
        self.assertEqual([], result["errors"])
        with open(os.path.join(self.out, "Bericht.pdf"), "rb") as pdf:
            self.assertEqual(b"%PDF-", pdf.read(5))


if __name__ == "__main__":
    unittest.main()
