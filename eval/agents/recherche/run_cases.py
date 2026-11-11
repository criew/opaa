#!/usr/bin/env python3
"""Runs the research cases against a running installation and writes what was observed.

Each case is two runs of the delivered agent "Recherche": the question ends with the plan, the
confirmation (or the plan change of the case) starts the run that works it off. Nothing is
judged here; the report lists wall-clock time, step counts, citations and whether the expected
documents were cited, for the person who reads the numbers.
"""
import argparse
import json
import sys
import time
import urllib.request
from pathlib import Path

CONFIRMATION = "Plan bestätigt."


def call(base, headers, method, path, body=None, timeout=600):
    request = urllib.request.Request(
        base + path,
        method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", **headers},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read() or "null")


def ask(base, headers, chat_id, agent_id, question):
    started = time.monotonic()
    answer = call(
        base,
        headers,
        "POST",
        "/api/v1/query",
        {"question": question, "chatId": chat_id, "agentId": agent_id},
    )
    return answer, round(time.monotonic() - started, 1)


def summary(answer, seconds):
    run = answer.get("agentRun") or {}
    steps = run.get("steps", [])
    tools = {}
    for step in steps:
        tools[step["tool"]] = tools.get(step["tool"], 0) + 1
    return {
        "seconds": seconds,
        "endState": run.get("endState"),
        "stepCount": len(steps),
        "toolCalls": tools,
        "limitSteps": sum(1 for step in steps if step["status"] == "LIMIT"),
        "retrieved": sorted({source.get("fileName") for source in answer.get("sources", [])}),
        # "cited" is set by the server only for markers whose document id matches a collected source.
        "cited": sorted({s.get("fileName") for s in answer.get("sources", []) if s.get("cited")}),
        "invalidCitations": sum(1 for s in answer.get("sources", []) if s.get("citationValid") is False),
        "answer": answer["answer"],
    }


def main():
    here = Path(__file__).parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="e.g. http://localhost:8080")
    parser.add_argument("--space-id", required=True, help="space with the corpus library assigned")
    parser.add_argument("--agent-id", help="id of the agent; default: the one named Recherche")
    parser.add_argument("--token", help="Bearer token of the asking account")
    parser.add_argument("--dev-user", help="dev auth only: X-OPAA-Dev-User of the asking account")
    parser.add_argument("--cases", default=str(here / "cases.json"))
    parser.add_argument("--out", default=str(here / "run-report.json"))
    args = parser.parse_args()

    headers = {}
    if args.token:
        headers["Authorization"] = f"Bearer {args.token}"
    if args.dev_user:
        headers["X-OPAA-Dev-User"] = args.dev_user
    base = args.base_url.rstrip("/")

    agent_id = args.agent_id
    if not agent_id:
        named = [a for a in call(base, headers, "GET", "/api/v1/agents") if a["name"] == "Recherche"]
        if not named:
            sys.exit("No agent named Recherche is readable; switch agents on or pass --agent-id.")
        agent_id = named[0]["id"]

    report = []
    for case in json.loads(Path(args.cases).read_text(encoding="utf-8"))["cases"]:
        chat = call(base, headers, "POST", f"/api/v1/spaces/{args.space_id}/chats", {})
        plan, plan_seconds = ask(base, headers, chat["id"], agent_id, case["question"])
        worked, work_seconds = ask(
            base, headers, chat["id"], agent_id, case.get("plan_change") or CONFIRMATION
        )
        result = summary(worked, work_seconds)
        cited = set(result["cited"])
        forbidden = set(case.get("forbidden_documents", []))
        result.update(
            {
                "id": case["id"],
                "planRun": summary(plan, plan_seconds),
                "missingExpected": sorted(set(case["expected_documents"]) - cited),
                "citedForbidden": sorted(forbidden & cited),
                "retrievedForbidden": sorted(forbidden & set(result["retrieved"])),
            }
        )
        report.append(result)
        print(
            f'{case["id"]}: {result["endState"]} in {work_seconds}s, '
            f'{result["stepCount"]} steps, missing {len(result["missingExpected"])}'
        )
    Path(args.out).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("report:", args.out)


if __name__ == "__main__":
    main()
