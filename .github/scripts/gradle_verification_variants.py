#!/usr/bin/env python3
"""Adds the second genuine variant of artifacts that the Gradle Plugin Portal serves itself.

The Plugin Portal redirects most requests to Maven Central, but serves plugin markers, plugin jars
and at times POM and module files from its own storage; such a copy may differ from Central's. A
build that resolves the plugin classpath from the Portal (CI, Docker) and one that resolves it
through a Central mirror (a corporate proxy, a dev environment) then see different bytes for the
same coordinates. For every artifact the Portal serves from its own storage, this script fetches
both origins' copies and records each checksum missing from the metadata as <also-trust>. Only
the two origins are contacted, never a mirror; existing values are never changed or removed.

It cannot add files a generation never resolved: gradlePluginPortal() reads POMs only, so the
metadata must be generated through that repository (docs/renovate.md, Gradle-Prüfsummen).

Usage: gradle_verification_variants.py [path/to/verification-metadata.xml]
"""

import hashlib
import http.client
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PORTAL = "https://plugins.gradle.org/m2/"
CENTRAL = "https://repo.maven.apache.org/maven2/"
CENTRAL_HOSTS = ("repo.maven.apache.org", "repo1.maven.org")
USER_AGENT = "opaa-gradle-verification-variants"

COMPONENT = re.compile(
    r'<component group="([^"]+)" name="([^"]+)" version="([^"]+)">(.*?)</component>', re.S
)
ARTIFACT = re.compile(
    r'(?P<indent>[ ]*)<artifact name="(?P<file>[^"]+)">\s*'
    r'<sha256 value="(?P<sha>[0-9a-f]{64})" origin="(?P<origin>[^"]*)"'
    r'(?:/>|>(?P<body>(?:\s*<also-trust value="[0-9a-f]{64}"/>)*\s*)</sha256>)\s*</artifact>',
    re.S,
)
ALSO_TRUST = re.compile(r'<also-trust value="([0-9a-f]{64})"/>')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


NO_REDIRECT = urllib.request.build_opener(NoRedirect)
FOLLOW = urllib.request.build_opener()


def fetch(opener, url):
    """Returns (status, body or Location); retries 429 and 5xx with backoff, sequentially."""
    delay = 1.0
    for _ in range(30):
        request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with opener.open(request, timeout=120) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            if error.code in (301, 302, 303, 307, 308):
                return error.code, error.headers.get("Location", "").encode()
            if error.code in (404, 410):
                return error.code, b""
            if error.code != 429 and error.code < 500:
                raise
        except (urllib.error.URLError, http.client.HTTPException, TimeoutError):
            pass
        time.sleep(delay)
        delay = min(delay * 1.5, 10)
    raise RuntimeError(f"giving up on {url} after repeated 429/5xx/network errors")


def portal_copy(url):
    """The Portal's own copy of a file, or None when it has none or redirects to Central."""
    status, body = fetch(NO_REDIRECT, url)
    if status == 200:
        return body
    if status in (301, 302, 303, 307, 308):
        location = urllib.parse.urljoin(url, body.decode())
        if urllib.parse.urlsplit(location).hostname not in CENTRAL_HOSTS:
            status, body = fetch(FOLLOW, location)
            return body if status == 200 else None
    return None


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "backend/gradle/verification-metadata.xml"
    with open(path, encoding="utf-8") as handle:
        text = handle.read()

    portal_served = 0
    added = []
    unmatched = []

    def rewrite_component(component):
        group, name, version, body = component.groups()
        base = f"{group.replace('.', '/')}/{name}/{version}/"
        coordinates = f"{group}:{name}:{version}"
        matched = {m["file"] for m in ARTIFACT.finditer(body)}
        unmatched.extend(
            f"{coordinates} {file}"
            for file in re.findall(r'<artifact name="([^"]+)">', body)
            if file not in matched
        )

        def rewrite_artifact(artifact):
            nonlocal portal_served
            known = {artifact["sha"], *ALSO_TRUST.findall(artifact["body"] or "")}
            portal_bytes = portal_copy(PORTAL + base + artifact["file"])
            if portal_bytes is None:
                return artifact.group(0)
            portal_served += 1
            variants = [hashlib.sha256(portal_bytes).hexdigest()]
            status, central_bytes = fetch(FOLLOW, CENTRAL + base + artifact["file"])
            if status == 200:
                variants.append(hashlib.sha256(central_bytes).hexdigest())
            missing = [v for v in dict.fromkeys(variants) if v not in known]
            state = "new variant" if missing else "known"
            print(f"Portal serves {coordinates} {artifact['file']}: {state}", flush=True)
            if not missing:
                return artifact.group(0)
            added.extend(f"{coordinates} {artifact['file']} {v}" for v in missing)
            ind = artifact["indent"]
            values = sorted(known - {artifact["sha"]}) + missing
            trusts = [f'{ind}      <also-trust value="{v}"/>' for v in values]
            return (
                f'{ind}<artifact name="{artifact["file"]}">\n'
                f'{ind}   <sha256 value="{artifact["sha"]}" origin="{artifact["origin"]}">\n'
                + "\n".join(trusts)
                + f"\n{ind}   </sha256>\n{ind}</artifact>"
            )

        return component.group(0).replace(body, ARTIFACT.sub(rewrite_artifact, body))

    text = COMPONENT.sub(rewrite_component, text)
    components = len(re.findall(r"<component[ >]", text))
    if components != len(COMPONENT.findall(text)):
        unmatched.append(f"{components - len(COMPONENT.findall(text))} component(s)")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)

    print(f"Plugin Portal serves {portal_served} artifact files itself.")
    print(f"Added {len(added)} also-trust checksum(s) for differing Portal/Central variants:")
    for line in added:
        print(f"  {line}")
    for line in unmatched:
        print(f"::warning::Not in the layout Gradle writes (sha256, also-trust), skipped: {line}")


if __name__ == "__main__":
    main()
