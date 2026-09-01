"""Generates the minimal, valid ODT/ODS/ODP fixtures under
backend/src/test/resources/test-documents/ (#1057).

An ODF file is a ZIP container whose first entry must be an uncompressed "mimetype" file - that is
what Tika's OpenDocument detector keys on, so a hand-built file needs the same structure a real
LibreOffice export has, not just a correct file extension. Run once via:

    ODF_OUT_DIR=<target dir> python make_odf.py

then copy the six generated files into backend/src/test/resources/test-documents/, overwriting the
existing ones. Not wired into the build - these fixtures are committed, not regenerated per run.
"""

import zipfile
import os

OUT_DIR = os.environ["ODF_OUT_DIR"]

MANIFEST_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
 <manifest:file-entry manifest:full-path="/" manifest:version="1.2" manifest:media-type="{mime}"/>
 <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
</manifest:manifest>
"""

ODT_CONTENT = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:body>
  <office:text>
   <text:p>OPAA Testdokument im ODF-Textformat.</text:p>
  </office:text>
 </office:body>
</office:document-content>
"""

ODT_CONTENT_EMPTY = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:body>
  <office:text/>
 </office:body>
</office:document-content>
"""

ODS_CONTENT = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:body>
  <office:spreadsheet>
   <table:table table:name="Sheet1">
    <table:table-row>
     <table:table-cell office:value-type="string"><text:p>OPAA Testtabelle</text:p></table:table-cell>
    </table:table-row>
   </table:table>
  </office:spreadsheet>
 </office:body>
</office:document-content>
"""

ODS_CONTENT_EMPTY = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:body>
  <office:spreadsheet/>
 </office:body>
</office:document-content>
"""

ODP_CONTENT = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:automatic-styles/>
 <office:body>
  <office:presentation>
   <draw:page draw:name="Folie1">
    <draw:frame draw:name="Titel">
     <draw:text-box>
      <text:p>OPAA Testpraesentation</text:p>
     </draw:text-box>
    </draw:frame>
   </draw:page>
  </office:presentation>
 </office:body>
</office:document-content>
"""

ODP_CONTENT_EMPTY = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
 xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" office:version="1.2">
 <office:automatic-styles/>
 <office:body>
  <office:presentation/>
 </office:body>
</office:document-content>
"""


def write_odf(path, mime, content_xml):
    manifest = MANIFEST_TEMPLATE.format(mime=mime)
    with zipfile.ZipFile(path, "w") as z:
        # mimetype must be first, stored (not deflated), per ODF spec
        z.writestr(zipfile.ZipInfo("mimetype"), mime, compress_type=zipfile.ZIP_STORED)
        z.writestr("META-INF/manifest.xml", manifest)
        z.writestr("content.xml", content_xml)


os.makedirs(OUT_DIR, exist_ok=True)

write_odf(
    os.path.join(OUT_DIR, "test-document.odt"),
    "application/vnd.oasis.opendocument.text",
    ODT_CONTENT,
)
write_odf(
    os.path.join(OUT_DIR, "empty-document.odt"),
    "application/vnd.oasis.opendocument.text",
    ODT_CONTENT_EMPTY,
)
write_odf(
    os.path.join(OUT_DIR, "test-document.ods"),
    "application/vnd.oasis.opendocument.spreadsheet",
    ODS_CONTENT,
)
write_odf(
    os.path.join(OUT_DIR, "empty-document.ods"),
    "application/vnd.oasis.opendocument.spreadsheet",
    ODS_CONTENT_EMPTY,
)
write_odf(
    os.path.join(OUT_DIR, "test-document.odp"),
    "application/vnd.oasis.opendocument.presentation",
    ODP_CONTENT,
)
write_odf(
    os.path.join(OUT_DIR, "empty-document.odp"),
    "application/vnd.oasis.opendocument.presentation",
    ODP_CONTENT_EMPTY,
)

print("done")
