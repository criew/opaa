"""Writes deterministic OpenDocument packages (.odt/.ods/.odp) from plain XML strings.

No writer library is used here on purpose: odfpy stamps every package with its own wall-clock
creation date, which would break the byte identity the rest of this generator guarantees, and the
three ODF pipelines of the backend read `content.xml`/`meta.xml`/`styles.xml` through a plain SAX
parser, so a hand-built package exercises exactly the same path a LibreOffice export does. Same
approach as backend/src/test/resources/test-documents/generate-odf-fixtures.py, extended by
`meta.xml` and `styles.xml`.

Contract of the container: the `mimetype` entry is first and stored uncompressed - that is what the
format detection keys on - and every entry carries the fixed timestamp of zip_utils, so two runs
produce identical bytes.
"""

from __future__ import annotations

import zipfile
from io import BytesIO

from zip_utils import FIXED_DATE_TIME

ODT_MIME = "application/vnd.oasis.opendocument.text"
ODS_MIME = "application/vnd.oasis.opendocument.spreadsheet"
ODP_MIME = "application/vnd.oasis.opendocument.presentation"

_MANIFEST = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
 <manifest:file-entry manifest:full-path="/" manifest:version="1.2" manifest:media-type="{mime}"/>
{entries}
</manifest:manifest>
"""

_META = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
 xmlns:dc="http://purl.org/dc/elements/1.1/"
 xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" office:version="1.2">
 <office:meta>
  <meta:generator>OPAA Demo-Korpus-Generator</meta:generator>
  <dc:title>{title}</dc:title>
  <dc:creator>{creator}</dc:creator>
  <meta:creation-date>{created}</meta:creation-date>
  <dc:date>{modified}</dc:date>
 </office:meta>
</office:document-meta>
"""


def meta_xml(title: str, creator: str, created: str, modified: str) -> str:
    """`meta.xml` with the three fields the ODF pipelines read (dc:title, meta:creation-date,
    dc:date). Dates are ISO-8601 local date-times; only the day reaches a document row."""
    return _META.format(title=title, creator=creator, created=created, modified=modified)


def package(mime: str, content_xml: str, meta: str, styles_xml: str | None = None) -> bytes:
    entry_names = ["content.xml", "meta.xml"] + (["styles.xml"] if styles_xml else [])
    manifest = _MANIFEST.format(
        mime=mime,
        entries="\n".join(
            f' <manifest:file-entry manifest:full-path="{name}" manifest:media-type="text/xml"/>'
            for name in entry_names
        ),
    )
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        # First and stored, per the ODF specification - a deflated or later entry is not detected
        # as OpenDocument and would be rejected before any pipeline sees it.
        archive.writestr(
            zipfile.ZipInfo("mimetype", date_time=FIXED_DATE_TIME),
            mime,
            compress_type=zipfile.ZIP_STORED,
        )
        _add(archive, "META-INF/manifest.xml", manifest)
        _add(archive, "content.xml", content_xml)
        _add(archive, "meta.xml", meta)
        if styles_xml:
            _add(archive, "styles.xml", styles_xml)
    return buffer.getvalue()


def _add(archive: zipfile.ZipFile, name: str, text: str) -> None:
    archive.writestr(
        zipfile.ZipInfo(name, date_time=FIXED_DATE_TIME), text, compress_type=zipfile.ZIP_DEFLATED
    )
