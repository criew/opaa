"""Building blocks for the MIME mails (.eml) of the demo corpus, which are assembled line by line.
Both helpers are pure functions of their input, so a mail's bytes depend only on its content."""

from __future__ import annotations

import base64
from email.header import Header


def header_value(value: str) -> str:
    """The value as is when ASCII, otherwise as an RFC 2047 encoded word."""
    if value.isascii():
        return value
    return Header(value, "utf-8").encode()


def base64_lines(data: bytes) -> str:
    """Base64 in lines of 76 characters, as MIME requires for a body part."""
    encoded = base64.b64encode(data).decode("ascii")
    return "\n".join(encoded[i : i + 76] for i in range(0, len(encoded), 76))
