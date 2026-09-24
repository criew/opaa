#!/usr/bin/env python3
"""Creates one access key whose rights are exactly a policy document, over the object store's
MinIO-compatible admin API (PUT /rustfs/admin/v3/...). Idempotent: the store overwrites an existing
policy and user with the same names, so a repeated run of the seed step is a no-op.

Usage: scoped-key.py <endpoint> <policy-name> <policy-file> <access-key> <secret-key>
Root credentials come from AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY; only the standard library is
used, so the step needs no package installation at start-up.
"""

import datetime
import hashlib
import hmac
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

REGION = "us-east-1"
SERVICE = "s3"


def _sign(key: bytes, message: str) -> bytes:
    return hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()


def _signed_request(endpoint: str, path: str, query: dict, body: bytes) -> urllib.request.Request:
    access_key = os.environ["AWS_ACCESS_KEY_ID"]
    secret_key = os.environ["AWS_SECRET_ACCESS_KEY"]
    parsed = urllib.parse.urlparse(endpoint)
    host = parsed.netloc
    now = datetime.datetime.now(datetime.timezone.utc)
    stamp = now.strftime("%Y%m%dT%H%M%SZ")
    day = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(body).hexdigest()
    canonical_query = "&".join(
        f"{urllib.parse.quote(k, safe='')}={urllib.parse.quote(v, safe='')}"
        for k, v in sorted(query.items())
    )
    canonical_headers = f"host:{host}\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{stamp}\n"
    signed_headers = "host;x-amz-content-sha256;x-amz-date"
    canonical_request = "\n".join(
        ["PUT", path, canonical_query, canonical_headers, signed_headers, payload_hash]
    )
    scope = f"{day}/{REGION}/{SERVICE}/aws4_request"
    to_sign = "\n".join(
        [
            "AWS4-HMAC-SHA256",
            stamp,
            scope,
            hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
        ]
    )
    signing_key = _sign(
        _sign(_sign(_sign(f"AWS4{secret_key}".encode("utf-8"), day), REGION), SERVICE),
        "aws4_request",
    )
    signature = hmac.new(signing_key, to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    url = f"{endpoint}{path}"
    if canonical_query:
        url += f"?{canonical_query}"
    return urllib.request.Request(
        url,
        data=body,
        method="PUT",
        headers={
            "Host": host,
            "x-amz-date": stamp,
            "x-amz-content-sha256": payload_hash,
            "Content-Type": "application/json",
            "Authorization": (
                f"AWS4-HMAC-SHA256 Credential={access_key}/{scope}, "
                f"SignedHeaders={signed_headers}, Signature={signature}"
            ),
        },
    )


def call(endpoint: str, path: str, query: dict, body: bytes = b"") -> None:
    request = _signed_request(endpoint, path, query, body)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            response.read()
    except urllib.error.HTTPError as error:
        sys.exit(f"{path} failed ({error.code}): {error.read().decode('utf-8', 'replace')}")


def main() -> None:
    endpoint, policy_name, policy_file, access_key, secret_key = sys.argv[1:6]
    with open(policy_file, "rb") as handle:
        policy = json.dumps(json.load(handle)).encode("utf-8")
    base = "/rustfs/admin/v3"
    call(endpoint, f"{base}/add-canned-policy", {"name": policy_name}, policy)
    call(
        endpoint,
        f"{base}/add-user",
        {"accessKey": access_key},
        json.dumps({"secretKey": secret_key, "status": "enabled"}).encode("utf-8"),
    )
    call(
        endpoint,
        f"{base}/set-user-or-group-policy",
        {"policyName": policy_name, "userOrGroup": access_key, "isGroup": "false"},
    )
    print(f"access key {access_key} scoped by policy {policy_name}")


if __name__ == "__main__":
    main()
