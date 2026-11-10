from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import slowest_test_classes  # noqa: E402


def suite(directory: Path, name: str, seconds: str, tests: int = 1) -> None:
    (directory / f"TEST-{name}.xml").write_text(
        f'<?xml version="1.0"?><testsuite name="{name}" tests="{tests}" time="{seconds}"/>',
        encoding="utf-8")


def test_classes_are_sorted_by_time_and_cut_at_top(tmp_path: Path, capsys) -> None:
    suite(tmp_path, "io.opaa.A", "1.5")
    suite(tmp_path, "io.opaa.B", "90.0", tests=4)
    suite(tmp_path, "io.opaa.C", "30.0")
    (tmp_path / "TEST-broken.xml").write_text("<testsuite", encoding="utf-8")

    assert slowest_test_classes.main([str(tmp_path), "--top", "2"]) == 0
    out = capsys.readouterr().out

    assert "3 Klassen, Summe 2.0 min" in out
    assert out.index("io.opaa.B") < out.index("io.opaa.C")
    assert "| `io.opaa.B` | 90.0 | 4 |" in out
    assert "io.opaa.A" not in out


def test_a_missing_directory_is_a_note_not_a_failure(tmp_path: Path, capsys) -> None:
    assert slowest_test_classes.main([str(tmp_path / "missing")]) == 0
    assert "Keine JUnit-Berichte" in capsys.readouterr().out
