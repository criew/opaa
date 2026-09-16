# Handprobe: hält ein produktionsübliches Chat-Modell das Verdichtungsformat ein?

**Datum:** 2026-09-16 · **Issue:** #1586 · **Anlass:** Ablation #1587 (PR #1670)

## Frage

Die Ablation hat gemessen, dass die Gesprächsnotiz der Suche schadet — die Notizpunkte der
Zielrunden waren Rollenwörter („Arzt", „Prüfer", „Person") oder abgeschnittene Abwürfe der
Artenaufzählung aus dem Prompt. Gemessen wurde mit dem gepinnten Eval-Modell
`qwen2.5:1.5b-instruct`. #1586 nennt als dritten Ansatz: „Prüfen, ob ein größeres Chat-Modell das
Format einhält — dann ist es eine Eigenschaft des gepinnten Eval-Modells und die Produktionsaussage
eine andere."

## Anordnung

- **Prompt:** `ChatNoteExtraction.PROMPT_TEMPLATE`, unverändert, mit den Produktionswerten
  (`MAX_TEXT_LENGTH` 200, `MAX_POINTS_PER_TURN` 2, Sentinel `KEINE`).
- **Modell:** `claude-haiku-4-5` über die Messages-API — das Chat-Modell, das die
  Demo-Installation betreibt. Ohne `temperature` (das Anthropic-SDK 1.x führt den Parameter nicht
  mehr), `max_tokens` 256, ein Aufruf je Nachricht, kein Verlauf.
- **Eingabe:** die neun Runde-1-Nachrichten der `constraint_carryover`-Fälle aus
  `eval/golden/verwaltung-conversations.json` — dieselben Texte wie im Messlauf.
- **Auswertung:** wortgetreue Portierung von `ChatNoteExtraction.parse` (Aufzählungszeichen
  entfernen, Sentinel, Artpräfix vor dem ersten Doppelpunkt, unbekanntes Präfix → `ANTWORTFORM`,
  Kürzung auf 200 Zeichen, höchstens zwei Punkte).
- **Kriterium:** Trägt einer der `RAHMEN`-Punkte die Jahreszahl aus Runde 1?

Neun Aufrufe, keine Wiederholung, kein Median — eine Handstichprobe, kein Messlauf.

## Ergebnis

**9 von 9 `RAHMEN`-Punkte tragen die Rahmenangabe.** Kein Vorlagen-Abwurf, kein Rollenwort als
ganzer Punkt, korrektes Artpräfix in jeder Zeile, dritte Person, jede Zeile unter 200 Zeichen, ein
bis zwei Zeilen je Nachricht.

Zum Vergleich die Punkte, die derselbe Prompt im Messlauf mit `qwen2.5:1.5b-instruct` an den
Zielrunden hinterlassen hat: „Arzt", „2023", „Prüfer", ein abgeschnittener Abwurf, „Berater",
„Prüfer", „Mitarbeiter", ein zweiter Abwurf, „Person".

## Was daraus folgt

- **Beobachtung 1 aus #1586 ist eine Eigenschaft des 1,5B-Modells, nicht des Prompts.** Ein
  robusterer Parser oder ein umgebauter Prompt behebt ein Problem, das ein produktionsübliches
  Modell nicht hat.
- **Der gemessene Schaden der Notiz ist keine Aussage über den Produktionsbetrieb.** Er entsteht an
  Punkten, die so nur ein sehr kleines Modell erzeugt.
- **Ungeklärt bleibt die andere Hälfte:** ob die Zerlegung einen *guten* `RAHMEN`-Punkt aufnimmt.
  Auch sie lief im Messlauf auf dem 1,5B-Modell; in Produktion läuft sie auf demselben Chat-Modell
  wie die Verdichtung.
- **Grundsätzlich:** Der Mehrrunden-Pfad misst Bausteine, die selbst aus Modellausgaben bestehen,
  mit einem Modell, das niemand betreibt. Für Retrieval-Parameter ist das Chat-Modell als Festpunkt
  richtig (ADR-0012: Determinismus, Kosten, Testcontainer ohne Schlüssel); für die Gesprächsnotiz
  trägt die Stellvertretung nicht.

## Rohantworten

```text
=== verw-conv-cc-001  Rahmenjahr 2023  -> TRAEGT
  Rohantwort:
    | RAHMEN: Die Person bearbeitet einen Altfall aus dem Jahr 2023 und arbeitet durchgehend mit der Fassung 2023.
  geparst: [('RAHMEN', 'Die Person bearbeitet einen Altfall aus dem Jahr 2023 und arbeitet durchgehend mit der Fassung 2023.')]

=== verw-conv-cc-002  Rahmenjahr 2023  -> TRAEGT
  Rohantwort:
    | RAHMEN: Der Vorgang stammt aus dem Jahr 2023 und die Person benötigt durchgehend die Fassung 2023.
  geparst: [('RAHMEN', 'Der Vorgang stammt aus dem Jahr 2023 und die Person benötigt durchgehend die Fassung 2023.')]

=== verw-conv-cc-003  Rahmenjahr 2023  -> TRAEGT
  Rohantwort:
    | RAHMEN: Die Person prüft einen Vorgang, der 2023 abgeschlossen wurde, und nimmt den Stand von 2023 als maßgeblich.
    | RAHMEN: Die Person ist zuständig für oder befasst sich mit dem Sozialamt und Dienstanweisungen zur Bearbeitung von Amtshandlungsanträgen.
  geparst: [('RAHMEN', 'Die Person prüft einen Vorgang, der 2023 abgeschlossen wurde, und nimmt den Stand von 2023 als maßgeblich.'), ('RAHMEN', 'Die Person ist zuständig für oder befasst sich mit dem Sozialamt und Dienstanweisungen zur Bearbeitung von Amtshandlungsanträgen.')]

=== verw-conv-cc-004  Rahmenjahr 2023  -> TRAEGT
  Rohantwort:
    | RAHMEN: Die Person bezieht sich auf den Rechtsstand 2023 als maßgeblich für ihren Vorgang.
    | RAHMEN: Die Person fragt zu Dienstanweisungen des Ordnungsamts.
  geparst: [('RAHMEN', 'Die Person bezieht sich auf den Rechtsstand 2023 als maßgeblich für ihren Vorgang.'), ('RAHMEN', 'Die Person fragt zu Dienstanweisungen des Ordnungsamts.')]

=== verw-conv-cc-005  Rahmenjahr 2024  -> TRAEGT
  Rohantwort:
    | RAHMEN: Die Person berät zu einem laufenden Fall nach der Fassung 2024 der Gewerbeanmeldegebührensatzung.
  geparst: [('RAHMEN', 'Die Person berät zu einem laufenden Fall nach der Fassung 2024 der Gewerbeanmeldegebührensatzung.')]

=== verw-conv-cc-006  Rahmenjahr 2023  -> TRAEGT
  Rohantwort:
    | RAHMEN: Die Person prüft einen Gebührenbescheid von 2023 und bezieht sich auf die Fassung 2023.
    | RAHMEN: Die Person interessiert sich für die Geltungsdauer der Personalausweisgebührensatzung in der Fassung 2023.
  geparst: [('RAHMEN', 'Die Person prüft einen Gebührenbescheid von 2023 und bezieht sich auf die Fassung 2023.'), ('RAHMEN', 'Die Person interessiert sich für die Geltungsdauer der Personalausweisgebührensatzung in der Fassung 2023.')]

=== verw-conv-cc-007  Rahmenjahr 2023  -> TRAEGT
  Rohantwort:
    | RAHMEN: Die Person bearbeitet einen Beitragsbescheid für das Jahr 2023 in der Fassung 2023.
  geparst: [('RAHMEN', 'Die Person bearbeitet einen Beitragsbescheid für das Jahr 2023 in der Fassung 2023.')]

=== verw-conv-cc-008  Rahmenjahr 2024  -> TRAEGT
  Rohantwort:
    | RAHMEN: Die Person arbeitet mit der Fassung 2024.
    | RAHMEN: Die Person ist dem Bauamt zugeordnet und befasst sich mit Amtshandlungsanträgen.
  geparst: [('RAHMEN', 'Die Person arbeitet mit der Fassung 2024.'), ('RAHMEN', 'Die Person ist dem Bauamt zugeordnet und befasst sich mit Amtshandlungsanträgen.')]

=== verw-conv-cc-009  Rahmenjahr 2024  -> TRAEGT
  Rohantwort:
    | RAHMEN: Die Person bezieht sich auf die Fassung 2024 der Sozialgebührenbefreiungssatzung als für ihren Fall gültig.
  geparst: [('RAHMEN', 'Die Person bezieht sich auf die Fassung 2024 der Sozialgebührenbefreiungssatzung als für ihren Fall gültig.')]

Ergebnis: 9 von 9 RAHMEN-Punkten tragen das Rahmenjahr.
```

## Skript

```python
"""Handprobe zu #1586: Hält ein grösseres Chat-Modell das Verdichtungsformat ein?

Fährt den Produktions-Prompt von ChatNoteExtraction gegen claude-haiku-4-5 (das Chat-Modell
der Demo-Installation) und wertet die Antwort mit einer wortgetreuen Portierung von
ChatNoteExtraction.parse aus. Eingabe sind die neun Runde-1-Nachrichten der
constraint_carryover-Faelle - dieselben Texte, aus denen qwen2.5:1.5b-instruct im
Messlauf "RAHMEN: Arzt" gemacht hat.
"""

import json
import re
import subprocess
import sys

import anthropic

MODEL = "claude-haiku-4-5"
MAX_POINTS_PER_TURN = 2
MAX_TEXT_LENGTH = 200
NOTHING_SENTINEL = "KEINE"
KINDS = ("RAHMEN", "ANTWORTFORM")

PROMPT_TEMPLATE = (
    "Aus der folgenden Nachricht einer Person sollst du dauerhafte Angaben \u00fcber diese "
    "Person festhalten, die auch f\u00fcr sp\u00e4tere Fragen im selben Gespr\u00e4ch gelten.\n"
    "\n"
    "Festhalten: Rolle, Zust\u00e4ndigkeit, Ort, Zeitraum, Fassung, Organisation, Festlegungen "
    "im Gespr\u00e4ch sowie W\u00fcnsche zur Darstellung der Antwort.\n"
    "\n"
    "Nicht festhalten: das Thema der Frage, Inhalte m\u00f6glicher Antworten, Bewertungen oder "
    "Vermutungen \u00fcber die Person, sowie die Frage selbst.\n"
    "\n"
    "Format: je Zeile genau eine Angabe, als ein kurzer Satz in der dritten Person, auf "
    "Deutsch, h\u00f6chstens %d Zeichen. Stelle jeder Zeile die Art voran, gefolgt von einem "
    "Doppelpunkt: RAHMEN f\u00fcr Rolle, Zust\u00e4ndigkeit, Ort, Zeitraum, Fassung, Organisation "
    "und Festlegungen, ANTWORTFORM f\u00fcr W\u00fcnsche zur Darstellung. H\u00f6chstens %d Zeilen. "
    "Enth\u00e4lt die Nachricht keine solche Angabe, antworte ausschlie\u00dflich mit %s.\n"
    "\n"
    "Nachricht: %s\n"
)

LEADING_BULLET_OR_NUMBER = re.compile(r"^[-*\u2022]\s+|^\d+[.)]\s+")


def normalize_token(text):
    return re.sub(r"[^\w]", "", text.lower(), flags=re.UNICODE)


def parse(raw_text):
    """Wortgetreue Portierung von ChatNoteExtraction.parse."""
    if raw_text is None or not raw_text.strip():
        return []
    candidates = []
    for raw_line in raw_text.strip().splitlines():
        line = LEADING_BULLET_OR_NUMBER.sub("", raw_line.strip(), count=1).strip()
        if not line:
            continue
        if normalize_token(line) == NOTHING_SENTINEL.lower():
            return []
        colon = line.find(":")
        kind, text = "ANTWORTFORM", line
        if colon >= 0:
            prefix = normalize_token(line[:colon])
            for candidate_kind in KINDS:
                if prefix == candidate_kind.lower():
                    kind, text = candidate_kind, line[colon + 1 :].strip()
                    break
        if not text:
            continue
        if len(text) > MAX_TEXT_LENGTH:
            text = text[: MAX_TEXT_LENGTH - 1].rstrip() + "\u2026"
        candidates.append((kind, text))
        if len(candidates) == MAX_POINTS_PER_TURN:
            break
    return candidates


def cases():
    raw = subprocess.run(
        ["git", "-C", "C:/source/opaa", "show",
         "origin/main:eval/golden/verwaltung-conversations.json"],
        capture_output=True, check=True,
    ).stdout.decode("utf-8")
    for case in json.loads(raw):
        if case["category"] != "constraint_carryover":
            continue
        message = case["turns"][0]["query"]
        year = re.findall(r"\b(20\d\d)\b", message)[-1]
        yield case["id"], year, message


def main():
    key = open(sys.argv[1], encoding="utf-8").read().strip()
    client = anthropic.Anthropic(api_key=key)

    carried, total = 0, 0
    for case_id, year, message in cases():
        total += 1
        response = client.messages.create(
            model=MODEL,
            max_tokens=256,
            messages=[{
                "role": "user",
                "content": PROMPT_TEMPLATE
                % (MAX_TEXT_LENGTH, MAX_POINTS_PER_TURN, NOTHING_SENTINEL, message),
            }],
        )
        answer = "".join(b.text for b in response.content if b.type == "text")
        points = parse(answer)
        rahmen = [t for k, t in points if k == "RAHMEN"]
        hit = any(year in t for t in rahmen)
        carried += hit

        print(f"=== {case_id}  Rahmenjahr {year}  -> {'TRAEGT' if hit else 'verloren'}")
        print("  Rohantwort:")
        for line in answer.strip().splitlines():
            print("    |", line)
        print("  geparst:", points or "(keine)")
        print()

    print(f"Ergebnis: {carried} von {total} RAHMEN-Punkten tragen das Rahmenjahr.")


if __name__ == "__main__":
    main()
```

Der Schlüssel wird als Datei übergeben (`python handprobe.py pfad/zum/key`) und ist nicht Teil des Repositories.
