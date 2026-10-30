"""Fails a pull request whose dependency changes add a runtime dependency with a critical advisory.

Reads the dependency-graph compare API between the PR's base and head commit. That API is only
eventually consistent: a query it once answered with "no snapshots" for the head keeps that answer
for a while, and advisories show up on a change minutes late and not in every response. So every
request for the head snapshots uses a per_page value not used before, and once they are there the
gate reads several samples a minute apart and fails on a finding seen in any of them. The
action's own retry-on-snapshot-warnings repeats one query and never sees late snapshots. Without head
snapshots it warns and checks the graph GitHub knows on its own (fail-open).

Environment: GH_TOKEN, REPO, BASE_SHA, HEAD_SHA; optional GITHUB_API_URL.
"""

import base64
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

NO_HEAD_SNAPSHOT = "No snapshots were found for the head SHA"
WARNINGS_HEADER = "x-github-dependency-graph-snapshot-warnings"
WAIT_SECONDS = 1500
SAMPLES = 6
SAMPLE_INTERVAL = 60
POLL_INTERVAL = 30
RETRIES = 4


class Api:
    """GET requests with retries on 5xx and rate limits (429, or 403 with an exhausted or
    retry-after rate limit); any other non-2xx status raises."""

    def __init__(self, base_url, token, sleep=time.sleep, opener=urllib.request.urlopen):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.sleep = sleep
        self.opener = opener

    def get(self, url):
        if not url.startswith("http"):
            url = f"{self.base_url}/{url}"
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self.token}",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        for attempt in range(RETRIES):
            try:
                with self.opener(request, timeout=60) as response:
                    headers = {k.lower(): v for k, v in response.headers.items()}
                    return headers, json.loads(response.read().decode("utf-8"))
            except urllib.error.HTTPError as error:
                if not retryable(error) or attempt == RETRIES - 1:
                    raise
            except urllib.error.URLError:
                if attempt == RETRIES - 1:
                    raise
            self.sleep(10 * (attempt + 1))
        raise AssertionError("unreachable")


def retryable(error):
    if error.code == 429 or error.code >= 500:
        return True
    headers = error.headers or {}
    return error.code == 403 and (
        headers.get("x-ratelimit-remaining") == "0" or headers.get("retry-after") is not None
    )


def next_link(headers):
    for part in headers.get("link", "").split(","):
        match = re.match(r'\s*<([^>]+)>;\s*rel="next"', part)
        if match:
            return match.group(1)
    return None


def head_warning(headers):
    raw = headers.get(WARNINGS_HEADER, "")
    if not raw:
        return ""
    try:
        return base64.b64decode(raw).decode("utf-8")
    except ValueError:
        return raw


def all_pages(api, headers, changes):
    """Follows the Link header; per_page limits manifests per page, not changes."""
    url = next_link(headers)
    while url:
        headers, page = api.get(url)
        changes.extend(page)
        url = next_link(headers)
    return changes


def critical_runtime_findings(changes):
    findings = set()
    for change in changes:
        if change.get("change_type") != "added" or change.get("scope") != "runtime":
            continue
        for vulnerability in change.get("vulnerabilities") or []:
            if vulnerability.get("severity") == "critical":
                findings.add(
                    f"{change.get('manifest')} » {change.get('name')}@{change.get('version')}: "
                    f"{vulnerability.get('advisory_ghsa_id')} {vulnerability.get('advisory_summary')}"
                )
    return sorted(findings)


def escape_annotation(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def run(api, base, head, sleep=time.sleep, clock=time.monotonic, out=print):
    compare = f"repos/{os.environ['REPO']}/dependency-graph/compare/{base}...{head}"
    per_page = iter(range(100, 0, -1))
    deadline = clock() + WAIT_SECONDS
    while True:
        headers, changes = api.get(f"{compare}?per_page={next(per_page)}")
        found = NO_HEAD_SNAPSHOT not in head_warning(headers)
        if found:
            samples = [all_pages(api, headers, changes)]
            break
        if clock() >= deadline:
            out(
                f"::warning::Keine Snapshots für {head} nach {WAIT_SECONDS // 60} Minuten, das Gate "
                "prüft nur den Graphen, den GitHub selbst kennt."
            )
            samples = [all_pages(api, headers, changes)]
            break
        sleep(POLL_INTERVAL)
    if found:
        while len(samples) < SAMPLES:
            sleep(SAMPLE_INTERVAL)
            headers, changes = api.get(f"{compare}?per_page={next(per_page)}")
            samples.append(all_pages(api, headers, changes))
    findings = critical_runtime_findings(change for sample in samples for change in sample)
    for finding in findings:
        out(f"::error::{escape_annotation(finding)}")
    if not findings:
        out(f"Keine neue critical-Schwachstelle im Scope runtime ({len(samples)} Stichproben).")
    return 1 if findings else 0


def main():
    api = Api(os.environ.get("GITHUB_API_URL", "https://api.github.com"), os.environ["GH_TOKEN"])
    return run(api, os.environ["BASE_SHA"], os.environ["HEAD_SHA"])


if __name__ == "__main__":
    sys.exit(main())
