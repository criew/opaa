"""HTTP client of the OCR smoke test (.github/scripts/ocr_smoke_test.sh). Runs inside the opaa-ocr
container, which has no network but its loopback, with the image's own Python and the standard
library only.

  status <path> [key]            prints the HTTP status of GET <path>
  convert <pdf> <key> [preset]   posts <pdf> to /v1/convert/source with the options OPAA sends and
                                 prints the HTTP status, then the response body, on stdout

The key goes into the header X-Api-Key; "-" sends no header.
"""

import base64
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:5001"


def request(path: str, key: str, body: bytes | None = None) -> tuple[int, bytes]:
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if key != "-":
        headers["X-Api-Key"] = key
    req = urllib.request.Request(BASE + path, data=body, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=600) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def convert_body(pdf: Path, preset: str) -> bytes:
    # The procedure OPAA sends with every call: forced full-page OCR with RapidOCR in German, table
    # structure on, the DoclingDocument as JSON in the response body.
    options = {
        "from_formats": ["pdf"],
        "to_formats": ["json"],
        "do_ocr": True,
        "force_ocr": True,
        "ocr_preset": preset,
        "ocr_lang": ["de"],
        "do_table_structure": True,
        "table_mode": "accurate",
        "image_export_mode": "placeholder",
    }
    source = {
        "kind": "file",
        "filename": pdf.name,
        "base64_string": base64.b64encode(pdf.read_bytes()).decode("ascii"),
    }
    return json.dumps({"options": options, "sources": [source], "target": {"kind": "inbody"}}).encode()


def main(argv: list[str]) -> int:
    if len(argv) >= 2 and argv[0] == "status":
        status, _ = request(argv[1], argv[2] if len(argv) > 2 else "-")
        print(status)
        return 0
    if len(argv) >= 3 and argv[0] == "convert":
        preset = argv[3] if len(argv) > 3 else "rapidocr"
        status, body = request("/v1/convert/source", argv[2], convert_body(Path(argv[1]), preset))
        print(status)
        sys.stdout.write(body.decode("utf-8", errors="replace"))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
