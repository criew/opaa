"""Prepared chat transcripts of the demo profile.

A chat set is one directory under chats/: its set.json names the space, the owner (the account
the chats are written for) and the corpus documents the answers cite; every other *.json file holds
chats of that set, split by topic only to keep each file readable. The seed writes them through
the backend's seed-only import (POST /v1/spaces/{id}/chat-imports, off unless
OPAA_DEMO_CHAT_IMPORT_ENABLED=true) - deterministic, no model call.

Answers cite with [[ref]] or [[ref#chunk]]: ref is a key of the set's "documents", resolved at
seed time to the document id of the running instance and rendered as the backend's own citation
marker. Instants are relative to the seed run: a chat lies "daysAgo" days back at "time"
(Europe/Berlin), its turns follow each other a few minutes apart. The chat's title is its identity:
a chat whose title the owner already has in the space is left as it is, so a second run writes
nothing new; only the pinned/archived marks the file asks for are set again if they went missing.
"""

from __future__ import annotations

import json
import re
from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from urllib.parse import unquote, urlparse

from api_client import ApiError

CITATION_PLACEHOLDER = re.compile(r"\[\[([a-z0-9-]+)(?:#(\d+))?\]\]")
ARCHIVED_PAGE_SIZE = 100


@dataclass(frozen=True)
class DocumentRef:
    library: str  # LibraryDef.name
    file: str  # the corpus file name - matched against the document's name or source address


@dataclass(frozen=True)
class TurnDef:
    question: str
    answer: str
    # Documents the answer drew on without citing them; they follow the cited ones as uncited sources.
    also: tuple[str, ...] = ()


@dataclass(frozen=True)
class ChatDef:
    title: str
    days_ago: int
    time: str  # HH:MM, Europe/Berlin
    turns: tuple[TurnDef, ...]
    pinned: bool = False
    archived: bool = False


@dataclass(frozen=True)
class ChatSet:
    space: str  # SpaceDef.name
    owner_key: str  # UserDef.key
    documents: dict[str, DocumentRef]
    chats: tuple[ChatDef, ...]


def cited_refs(answer: str) -> list[str]:
    """The refs an answer cites, in order of first appearance."""
    refs: list[str] = []
    for match in CITATION_PLACEHOLDER.finditer(answer):
        if match.group(1) not in refs:
            refs.append(match.group(1))
    return refs


SET_FILE = "set.json"


def load_chat_set(directory: Path) -> ChatSet:
    """Reads and checks one chat set; every inconsistency stops the seed before the first write."""
    raw = json.loads((directory / SET_FILE).read_text(encoding="utf-8"))
    documents = {
        key: DocumentRef(library=value["library"], file=value["file"])
        for key, value in raw["documents"].items()
    }
    entries = [
        entry
        for path in sorted(directory.glob("*.json"))
        if path.name != SET_FILE
        for entry in json.loads(path.read_text(encoding="utf-8"))["chats"]
    ]
    chats = []
    for entry in entries:
        turns = tuple(
            TurnDef(question=t["q"], answer=t["a"], also=tuple(t.get("also", ())))
            for t in entry["turns"]
        )
        chats.append(
            ChatDef(
                title=entry["title"],
                days_ago=int(entry["daysAgo"]),
                time=entry["time"],
                turns=turns,
                pinned=bool(entry.get("pinned", False)),
                archived=bool(entry.get("archived", False)),
            )
        )
    chat_set = ChatSet(
        space=raw["space"], owner_key=raw["owner"], documents=documents, chats=tuple(chats)
    )
    problems = validate(chat_set)
    if problems:
        raise SystemExit(f"Chat-Satz {directory.name} ist nicht stimmig: " + "; ".join(problems))
    return chat_set


def validate(chat_set: ChatSet) -> list[str]:
    problems: list[str] = []
    titles = [chat.title for chat in chat_set.chats]
    duplicates = sorted({title for title in titles if titles.count(title) > 1})
    if duplicates:
        problems.append(f"doppelte Titel {duplicates}")
    for chat in chat_set.chats:
        if not chat.turns:
            problems.append(f"'{chat.title}' hat keine Runde")
        if chat.pinned and chat.archived:
            problems.append(f"'{chat.title}' ist zugleich angeheftet und archiviert")
        if chat.days_ago < 1:
            problems.append(f"'{chat.title}' liegt nicht in der Vergangenheit (daysAgo < 1)")
        if not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", chat.time):
            problems.append(f"'{chat.title}' hat keine Uhrzeit HH:MM")
        for turn in chat.turns:
            for ref in [*cited_refs(turn.answer), *turn.also]:
                if ref not in chat_set.documents:
                    problems.append(f"'{chat.title}' nennt unbekanntes Dokument '{ref}'")
    return problems


def berlin_offset(instant: datetime) -> timedelta:
    """UTC offset of Europe/Berlin at instant: summer time from the last Sunday of March to the
    last Sunday of October, each at 01:00 UTC - without a time zone database on the seed host."""

    def last_sunday(year: int, month: int) -> datetime:
        day = date(year, month + 1, 1) - timedelta(days=1)
        day -= timedelta(days=(day.weekday() + 1) % 7)
        return datetime.combine(day, time(1, 0), tzinfo=timezone.utc)

    year = instant.year
    summer = last_sunday(year, 3) <= instant < last_sunday(year, 10)
    return timedelta(hours=2 if summer else 1)


def turn_instants(chat: ChatDef, now: datetime) -> list[tuple[datetime, datetime]]:
    """(asked, answered) per turn: the first question at daysAgo/time, every further one two to six
    minutes after the previous answer, each answer 12 to 55 seconds after its question."""
    local_day = (now + berlin_offset(now)).date() - timedelta(days=chat.days_ago)
    hour, minute = (int(part) for part in chat.time.split(":"))
    local_start = datetime.combine(local_day, time(hour, minute), tzinfo=timezone.utc)
    asked = local_start - berlin_offset(local_start)
    instants = []
    for index, turn in enumerate(chat.turns):
        if index > 0:
            asked = instants[-1][1] + timedelta(minutes=2 + (index * 3) % 5)
        answered = asked + timedelta(seconds=min(55, 12 + len(turn.answer) // 40))
        instants.append((asked, answered))
    return instants


def render_answer(answer: str, resolved: dict[str, dict]) -> str:
    """Replaces every [[ref#chunk]] by the backend's citation marker for the resolved document."""

    def marker(match: re.Match[str]) -> str:
        document = resolved[match.group(1)]
        return f"【source: {document['id']}#{match.group(2) or 0} | {document['fileName']}】"

    return CITATION_PLACEHOLDER.sub(marker, answer)


def import_request(chat: ChatDef, resolved: dict[str, dict], now: datetime) -> dict:
    """The ChatImportRequest of chat-import.yaml for one chat."""
    turns = []
    for turn, (asked, answered) in zip(chat.turns, turn_instants(chat, now)):
        cited = cited_refs(turn.answer)
        sources = [{"documentId": resolved[ref]["id"], "cited": True} for ref in cited]
        sources += [
            {"documentId": resolved[ref]["id"], "cited": False}
            for ref in turn.also
            if ref not in cited
        ]
        turns.append(
            {
                "question": turn.question,
                "answer": render_answer(turn.answer, resolved),
                "askedAt": _iso(asked),
                "answeredAt": _iso(answered),
                "sources": sources,
            }
        )
    return {"title": chat.title, "turns": turns}


def _iso(instant: datetime) -> str:
    return instant.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _matches(document: dict, file_name: str) -> bool:
    """A corpus file is the document of that name - or, where the connector names it otherwise (an
    RSS entry by its headline), the document whose source address ends in that file."""
    if document.get("fileName") == file_name:
        return True
    source_url = document.get("sourceUrl")
    return bool(source_url) and unquote(urlparse(source_url).path).rsplit("/", 1)[-1] == file_name


def resolve_documents(
    chat_set: ChatSet,
    library_ids: dict[str, str],
    list_documents: Callable[[str], list[dict]],
) -> dict[str, dict]:
    """Every ref of the chat set as {"id", "fileName"} of the running instance. A ref no document
    or several documents answer stops the seed - a changed corpus must not yield wrong sources."""
    resolved: dict[str, dict] = {}
    by_library: dict[str, list[dict]] = {}
    for key, ref in chat_set.documents.items():
        if ref.library not in library_ids:
            raise SystemExit(f"Chat-Beleg '{key}' nennt eine unbekannte Bibliothek '{ref.library}'.")
        if ref.library not in by_library:
            by_library[ref.library] = list_documents(library_ids[ref.library])
        candidates = [d for d in by_library[ref.library] if _matches(d, ref.file)]
        if len(candidates) != 1:
            raise SystemExit(
                f"Chat-Beleg '{key}': In '{ref.library}' passen {len(candidates)} Dokumente zu "
                f"'{ref.file}' statt genau eines - ist die Bibliothek vollständig indiziert?"
            )
        resolved[key] = {"id": candidates[0]["id"], "fileName": candidates[0]["fileName"]}
    return resolved


def existing_chats(owner_client, space_id: str) -> dict[str, dict]:
    """The owner's chats of the space by title, active and archived."""
    chats = {c["title"]: c for c in owner_client.get_ok(f"/v1/spaces/{space_id}/chats")}
    page = 0
    while True:
        result = owner_client.get_ok(
            f"/v1/spaces/{space_id}/chats/archived",
            params={"page": page, "size": ARCHIVED_PAGE_SIZE},
        )
        for chat in result["items"]:
            chats.setdefault(chat["title"], chat)
        if (page + 1) * result["size"] >= result["totalElements"] or not result["items"]:
            return chats
        page += 1


def import_chat(owner_client, space_id: str, request: dict) -> dict:
    response = owner_client.post(f"/v1/spaces/{space_id}/chat-imports", json=request)
    if response.status_code == 404:
        raise SystemExit(
            "Chat-Import abgelehnt (HTTP 404). Meist ist der Import im Backend abgeschaltet: "
            "OPAA_DEMO_CHAT_IMPORT_ENABLED=true in der Umgebung des Backends setzen, Backend neu "
            "starten und den Seed erneut laufen lassen (siehe demo/README.md). Antwort: "
            f"{response.text[:300]}"
        )
    if response.status_code != 201:
        raise ApiError(response)
    return response.json()


def apply_marks(owner_client, chat: ChatDef, summary: dict) -> None:
    """Sets the marks the file asks for; a mark the file does not ask for is left alone."""
    if chat.pinned and not summary.get("pinnedAt"):
        owner_client.put_ok(f"/v1/chats/{summary['id']}/pin")
    if chat.archived and not summary.get("archivedAt"):
        owner_client.put_ok(f"/v1/chats/{summary['id']}/archive")


def seed_chat_set(
    owner_client,
    space_id: str,
    chat_set: ChatSet,
    resolved: dict[str, dict],
    now: datetime,
) -> tuple[int, int]:
    """Imports every chat the owner does not have yet. Returns (imported, already present)."""
    present = existing_chats(owner_client, space_id)
    imported = 0
    for chat in chat_set.chats:
        summary = present.get(chat.title)
        if summary is None:
            summary = import_chat(owner_client, space_id, import_request(chat, resolved, now))
            imported += 1
        apply_marks(owner_client, chat, summary)
    return imported, len(chat_set.chats) - imported
