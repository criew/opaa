"""Tests for the prepared demo chats, without a running stack.

What is covered: the committed chat set as data (volume, marks, spread over weeks, every cited
document present in the corpus and readable in the space), the request the seed sends for a chat
(citation markers, source order, rising instants in the past) and the seed step against an
in-memory chat API - a second run imports nothing and only restores missing marks. The import
itself runs against the backend in ChatImportServiceIntegrationTest and in the nightly demo smoke.

Run from the repository root:
    pytest demo/seed
"""

from __future__ import annotations

import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
import requests

sys.path.insert(0, str(Path(__file__).parent))

import chats  # noqa: E402
import profiles  # noqa: E402

DEMO = profiles.DEMO_PROFILE
NOW = datetime(2026, 9, 30, 12, 0, tzinfo=timezone.utc)


@pytest.fixture(scope="module")
def chat_set() -> chats.ChatSet:
    (directory,) = DEMO.chat_sets
    return chats.load_chat_set(directory)


def corpus_files(library: profiles.LibraryDef) -> set[str]:
    """The file names a library's documents come from, as the seed matches them."""
    if library.source_type == "UPLOAD":
        root = library.upload_dir
    elif library.expected_documents_dir is not None:
        root = library.expected_documents_dir
    elif library.source_type == "RSS_FEED":
        root = profiles.DEMO_CORPUS_ROOT / "pressemitteilungen"
    else:
        root = profiles.DEMO_CORPUS_ROOT / library.source_url.rstrip("/").rsplit("/", 1)[-1]
    return {path.name for path in root.rglob("*") if path.is_file()}


def effective_readers(library_name: str) -> set[str]:
    library = next(lib for lib in DEMO.libraries if lib.name == library_name)
    readers = set(library.viewer_keys)
    for group in (*DEMO.groups, *DEMO.provider_groups):
        if library_name in group.library_grants:
            readers.update(group.member_keys)
    return readers


def test_the_demo_space_gets_at_least_seventy_short_chats_and_one_long_one(chat_set) -> None:
    short = [chat for chat in chat_set.chats if 2 <= len(chat.turns) <= 3]
    long = [chat for chat in chat_set.chats if len(chat.turns) >= 30]
    assert len(short) >= 70
    assert long, "ein Chat mit 30 oder mehr Runden"
    assert len(short) + len(long) == len(chat_set.chats)


def test_several_chats_are_pinned_and_several_archived(chat_set) -> None:
    assert sum(chat.pinned for chat in chat_set.chats) >= 2
    assert sum(chat.archived for chat in chat_set.chats) >= 2


def test_the_chats_spread_over_several_weeks(chat_set) -> None:
    days = [chat.days_ago for chat in chat_set.chats]
    assert max(days) - min(days) >= 28
    assert len({day // 7 for day in days}) >= 6, "mehrere Wochen mit Chats"


def test_the_space_belongs_to_the_owner_of_the_chats(chat_set) -> None:
    space = next(s for s in DEMO.spaces if s.name == chat_set.space)
    assert space.owner_key == chat_set.owner_key


def test_every_cited_document_lies_in_the_corpus_of_its_library(chat_set) -> None:
    libraries = {lib.name: lib for lib in DEMO.libraries}
    missing = [
        f"{key}: {ref.library}/{ref.file}"
        for key, ref in chat_set.documents.items()
        if ref.file not in corpus_files(libraries[ref.library])
    ]
    assert not missing


def test_every_cited_library_is_associated_with_the_space_and_readable_by_the_owner(
    chat_set,
) -> None:
    space = next(s for s in DEMO.spaces if s.name == chat_set.space)
    for ref in chat_set.documents.values():
        assert ref.library in space.library_names, ref.library
        assert chat_set.owner_key in effective_readers(ref.library), ref.library


def test_every_declared_document_is_cited_somewhere(chat_set) -> None:
    used = {
        ref
        for chat in chat_set.chats
        for turn in chat.turns
        for ref in (*chats.cited_refs(turn.answer), *turn.also)
    }
    assert set(chat_set.documents) == used


def test_no_answer_contains_a_placeholder_the_renderer_would_miss(chat_set) -> None:
    for chat in chat_set.chats:
        for turn in chat.turns:
            leftover = re.sub(chats.CITATION_PLACEHOLDER, "", turn.answer)
            assert "[[" not in leftover and "]]" not in leftover, chat.title


def test_every_turn_of_every_chat_rises_and_lies_before_the_seed_run(chat_set) -> None:
    for chat in chat_set.chats:
        instants = chats.turn_instants(chat, NOW)
        flat = [instant for turn in instants for instant in turn]
        assert flat == sorted(flat), chat.title
        assert flat[-1] < NOW, chat.title


def test_a_chat_starts_at_its_berlin_wall_clock_time() -> None:
    summer = chats.ChatDef(
        title="Sommer", days_ago=2, time="09:15", turns=(chats.TurnDef("F", "A"),)
    )
    winter_now = datetime(2026, 1, 20, 12, 0, tzinfo=timezone.utc)
    assert chats.turn_instants(summer, NOW)[0][0] == datetime(
        2026, 9, 28, 7, 15, tzinfo=timezone.utc
    )
    assert chats.turn_instants(summer, winter_now)[0][0] == datetime(
        2026, 1, 18, 8, 15, tzinfo=timezone.utc
    )


def test_the_berlin_offset_switches_on_the_last_sundays_of_march_and_october() -> None:
    assert chats.berlin_offset(datetime(2026, 3, 29, 0, 59, tzinfo=timezone.utc)) == timedelta(
        hours=1
    )
    assert chats.berlin_offset(datetime(2026, 3, 29, 1, 0, tzinfo=timezone.utc)) == timedelta(
        hours=2
    )
    assert chats.berlin_offset(datetime(2026, 10, 25, 1, 0, tzinfo=timezone.utc)) == timedelta(
        hours=1
    )


RESOLVED = {
    "vgs": {"id": "11111111-1111-1111-1111-111111111111", "fileName": "01_vgs.pdf"},
    "pa": {"id": "22222222-2222-2222-2222-222222222222", "fileName": "001_pa.md"},
    "extra": {"id": "33333333-3333-3333-3333-333333333333", "fileName": "extra.md"},
}


def test_the_request_renders_citation_markers_and_lists_cited_before_uncited_sources() -> None:
    chat = chats.ChatDef(
        title="Gebühren",
        days_ago=3,
        time="10:00",
        turns=(
            chats.TurnDef(
                "Was kostet es?",
                "Laut Satzung 42,60 Euro[[vgs]], siehe auch[[pa#2]] und nochmals[[vgs]].",
                also=("extra", "vgs"),
            ),
        ),
    )

    request = chats.import_request(chat, RESOLVED, NOW)

    turn = request["turns"][0]
    assert request["title"] == "Gebühren"
    assert turn["answer"] == (
        "Laut Satzung 42,60 Euro【source: 11111111-1111-1111-1111-111111111111#0 | 01_vgs.pdf】, "
        "siehe auch【source: 22222222-2222-2222-2222-222222222222#2 | 001_pa.md】 und nochmals"
        "【source: 11111111-1111-1111-1111-111111111111#0 | 01_vgs.pdf】."
    )
    assert turn["sources"] == [
        {"documentId": RESOLVED["vgs"]["id"], "cited": True},
        {"documentId": RESOLVED["pa"]["id"], "cited": True},
        {"documentId": RESOLVED["extra"]["id"], "cited": False},
    ]
    assert turn["askedAt"] < turn["answeredAt"]
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", turn["askedAt"])


def test_an_inconsistent_chat_set_is_refused_before_anything_is_written(tmp_path: Path) -> None:
    (tmp_path / "set.json").write_text(
        '{"space": "S", "owner": "o", "documents": {}}', encoding="utf-8"
    )
    (tmp_path / "a.json").write_text(
        '{"chats": [{"title": "X", "daysAgo": 0, "time": "25:00", "pinned": true,'
        ' "archived": true, "turns": [{"q": "F", "a": "A[[fehlt]]"}]},'
        ' {"title": "X", "daysAgo": 2, "time": "09:00", "turns": [{"q": "F", "a": "A"}]}]}',
        encoding="utf-8",
    )

    with pytest.raises(SystemExit) as refusal:
        chats.load_chat_set(tmp_path)

    message = str(refusal.value)
    for part in ("doppelte Titel", "angeheftet und archiviert", "daysAgo", "HH:MM", "'fehlt'"):
        assert part in message


def test_a_press_release_resolves_by_its_source_address() -> None:
    chat_set = chats.ChatSet(
        space="S",
        owner_key="o",
        documents={
            "pm": chats.DocumentRef("Presse", "stadtfest.html"),
            "doc": chats.DocumentRef("Presse", "liste.csv"),
        },
        chats=(),
    )
    listing = [
        {
            "id": "a",
            "fileName": "Bürgerbüro am 19. Juni geschlossen",
            "sourceUrl": "http://presse.stadt-rheinfurt.example/stadtfest.html",
        },
        {"id": "b", "fileName": "liste.csv", "sourceUrl": None},
    ]

    resolved = chats.resolve_documents(chat_set, {"Presse": "lib"}, lambda _: listing)

    assert resolved == {
        "pm": {"id": "a", "fileName": "Bürgerbüro am 19. Juni geschlossen"},
        "doc": {"id": "b", "fileName": "liste.csv"},
    }


@pytest.mark.parametrize("listing", [[], [{"id": "a", "fileName": "x.md"}] * 2])
def test_a_document_that_is_missing_or_ambiguous_stops_the_seed(listing: list[dict]) -> None:
    chat_set = chats.ChatSet(
        space="S", owner_key="o", documents={"x": chats.DocumentRef("L", "x.md")}, chats=()
    )
    with pytest.raises(SystemExit, match="statt genau eines"):
        chats.resolve_documents(chat_set, {"L": "lib"}, lambda _: listing)


class FakeChatApi:
    """In-memory stand-in for the owner's chat endpoints the seed calls; it pages the archive in
    pages of two to exercise the seed's paging."""

    PAGE = 2

    def __init__(self, import_status: int = 201) -> None:
        self.chats: dict[str, dict] = {}
        self.calls: list[tuple[str, str]] = []
        self.import_status = import_status

    def _summary(self, chat: dict) -> dict:
        return {k: chat[k] for k in ("id", "title", "pinnedAt", "archivedAt")}

    def get_ok(self, path: str, params: dict | None = None, **kwargs):
        self.calls.append(("GET", path))
        if path == "/v1/spaces/space-1/chats":
            return [self._summary(c) for c in self.chats.values() if not c["archivedAt"]]
        if path == "/v1/spaces/space-1/chats/archived":
            archived = [self._summary(c) for c in self.chats.values() if c["archivedAt"]]
            page, size = params["page"], min(params["size"], self.PAGE)
            return {
                "items": archived[page * size : (page + 1) * size],
                "page": page,
                "size": size,
                "totalElements": len(archived),
            }
        raise AssertionError(f"unerwarteter GET {path}")

    def post(self, path: str, json=None, **kwargs) -> requests.Response:
        self.calls.append(("POST", path))
        assert path == "/v1/spaces/space-1/chat-imports"
        built = requests.Response()
        built.status_code = self.import_status
        built.request = requests.Request("POST", f"http://localhost{path}").prepare()
        if self.import_status == 201:
            chat_id = f"chat-{len(self.chats) + 1}"
            self.chats[chat_id] = {
                "id": chat_id,
                "title": json["title"],
                "pinnedAt": None,
                "archivedAt": None,
                "request": json,
            }
            built._content = __import__("json").dumps(self._summary(self.chats[chat_id])).encode()
        else:
            built._content = b'{"error": "Not Found"}'
        return built

    def put_ok(self, path: str, **kwargs):
        self.calls.append(("PUT", path))
        chat_id, mark = re.fullmatch(r"/v1/chats/([^/]+)/(pin|archive)", path).groups()
        chat = self.chats[chat_id]
        if mark == "pin":
            chat["pinnedAt"], chat["archivedAt"] = "2026-09-30T12:00:00Z", None
        else:
            chat["pinnedAt"], chat["archivedAt"] = None, "2026-09-30T12:00:00Z"
        return self._summary(chat)


def small_set() -> chats.ChatSet:
    def chat(title: str, **marks) -> chats.ChatDef:
        return chats.ChatDef(
            title=title,
            days_ago=3,
            time="09:00",
            turns=(chats.TurnDef("F?", "A[[vgs]]"), chats.TurnDef("G?", "B")),
            **marks,
        )

    return chats.ChatSet(
        space="S",
        owner_key="o",
        documents={"vgs": chats.DocumentRef("L", "01_vgs.pdf")},
        chats=(
            chat("Eins", pinned=True),
            chat("Zwei", archived=True),
            chat("Drei", archived=True),
            chat("Vier", archived=True),
            chat("Fünf"),
        ),
    )


def test_seeding_imports_every_chat_once_with_its_marks() -> None:
    api = FakeChatApi()

    imported, present = chats.seed_chat_set(api, "space-1", small_set(), RESOLVED, NOW)

    assert (imported, present) == (5, 0)
    by_title = {c["title"]: c for c in api.chats.values()}
    assert by_title["Eins"]["pinnedAt"]
    assert all(by_title[t]["archivedAt"] for t in ("Zwei", "Drei", "Vier"))
    assert not by_title["Fünf"]["pinnedAt"] and not by_title["Fünf"]["archivedAt"]


def test_a_second_run_imports_nothing_and_changes_no_mark() -> None:
    api = FakeChatApi()
    chats.seed_chat_set(api, "space-1", small_set(), RESOLVED, NOW)
    api.calls.clear()

    imported, present = chats.seed_chat_set(api, "space-1", small_set(), RESOLVED, NOW)

    assert (imported, present) == (0, 5)
    assert [call for call in api.calls if call[0] != "GET"] == []
    assert len(api.chats) == 5


def test_a_repeat_run_restores_a_missing_mark_but_leaves_others_alone() -> None:
    api = FakeChatApi()
    chats.seed_chat_set(api, "space-1", small_set(), RESOLVED, NOW)
    by_title = {c["title"]: c for c in api.chats.values()}
    by_title["Eins"]["pinnedAt"] = None
    by_title["Fünf"]["pinnedAt"] = "2026-09-30T13:00:00Z"
    api.calls.clear()

    chats.seed_chat_set(api, "space-1", small_set(), RESOLVED, NOW)

    assert [call for call in api.calls if call[0] == "PUT"] == [
        ("PUT", f"/v1/chats/{by_title['Eins']['id']}/pin")
    ]
    assert by_title["Fünf"]["pinnedAt"] == "2026-09-30T13:00:00Z"


def test_a_backend_without_the_import_switch_is_named_as_the_cause() -> None:
    api = FakeChatApi(import_status=404)

    with pytest.raises(SystemExit, match="OPAA_DEMO_CHAT_IMPORT_ENABLED=true"):
        chats.seed_chat_set(api, "space-1", small_set(), RESOLVED, NOW)


def test_the_e2e_profile_seeds_no_chats() -> None:
    assert profiles.E2E_PROFILE.chat_sets == ()
