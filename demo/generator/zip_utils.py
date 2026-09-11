"""Strips non-reproducible per-entry timestamps from OOXML (.docx/.pptx/.xlsx) zip
containers.

python-docx and python-pptx use fixed values for the document's own
`docProps/core.xml` created/modified properties (see their respective
default templates), but the *zip container* itself still stamps every
member's local file header with `datetime.now()` at save time. Two
generator runs a few seconds apart therefore produce byte-different files
even though their actual document content is identical — this rewrites
every entry's `date_time` to a fixed epoch so the container itself becomes
reproducible too.
"""

from __future__ import annotations

import zipfile
from io import BytesIO

# The MS-DOS zip date_time floor (1980-01-01); arbitrary but fixed, and the
# same floor Python's own zipfile module falls back to for out-of-range
# timestamps, so it round-trips cleanly on every platform.
FIXED_DATE_TIME = (1980, 1, 1, 0, 0, 0)


def normalize_zip_timestamps(data: bytes, first_entry: str | None = None) -> bytes:
    """`first_entry` moves that member to the front of the container without touching the others'
    order — openpyxl writes `[Content_Types].xml` last, where a streaming format detection reaches
    it only after every other entry."""
    source = zipfile.ZipFile(BytesIO(data))
    infos = list(source.infolist())
    if first_entry is not None:
        infos.sort(key=lambda info: info.filename != first_entry)
    output = BytesIO()
    with zipfile.ZipFile(output, "w") as target:
        for info in infos:
            new_info = zipfile.ZipInfo(info.filename, date_time=FIXED_DATE_TIME)
            new_info.compress_type = info.compress_type
            new_info.external_attr = info.external_attr
            target.writestr(new_info, source.read(info.filename))
    return output.getvalue()
