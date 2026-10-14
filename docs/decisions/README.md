# Architecture Decision Records

Ein ADR hält den **heute gültigen Endstand** einer Architekturentscheidung fest, nicht ihre
Geschichte. Die Historie liefert `git log`; wer wissen will, was vorher galt, liest den Diff.
Vorlage: [TEMPLATE.md](TEMPLATE.md) — die Abschnittsfolge ist verbindlich, der Stil wird in der CI
durch `.github/scripts/check_adr_style.sh` geprüft.

## Fortschreiben statt anhängen

- Eine Änderung an einer Entscheidung **ändert den Kerntext** der betroffenen Entscheidung. Es gibt
  keine Nachträge, keine „Überholt“-Kästen und keinen stehen bleibenden alten Wortlaut.
- Eine geänderte Entscheidung trägt genau einen Halbsatz „seit #NNNN“, ohne Beschreibung des
  früheren Zustands. Die Tabelle **Änderungsverlauf** bekommt eine Zeile: Datum, Issue/PR, eine
  Zeile Inhalt, betroffene Entscheidungsnummern.
- Bringt eine Fortschreibung **eigene Entscheidungen** mit oder würde sie **mehr als etwa 40 Zeilen**
  umfassen, wird sie ein neuer ADR; der bestehende verweist in der Statuszeile („ergänzt durch
  ADR-XXXX“) und in der betroffenen Entscheidung darauf.
- Der **Kontext** beschreibt das Problem zum Zeitpunkt der Entscheidung und wird nicht
  fortgeschrieben. **Offen** enthält nur, was heute offen ist; Erledigtes wird gelöscht.

## Entscheidungsnummern sind stabil

Code, Handbuch und Spezifikationen verweisen auf „ADR-NNNN, Entscheidung M“. Eine Nummer wird
deshalb nie neu vergeben und nie verschoben. Eine entfallene Entscheidung behält ihre Nummer und
besteht aus einer Zeile: „Entfallen mit #NNNN, siehe ADR-XXXX Entscheidung M.“ Vor dem Umbau
prüfen, worauf verwiesen wird:

```bash
grep -rn "ADR-00NN" backend/src docs frontend/src deploy opaa-api \
  --include=*.java --include=*.md --include=*.yaml --include=*.ts --include=*.tsx --include=*.kts
```

## Was nicht in einen ADR gehört

- Messzahlen, Lauf-IDs, Baselines und Benchmark-Ergebnisse — sie leben in `eval/` und in den
  Issues, die sie erhoben haben.
- Paketreihenfolgen, Schichtungen und Modulregeln — dafür `backend/AGENTS.md` und die
  Modul-`AGENTS.md` verlinken.
- Listen „Belegt in …Test“ — ein Test, der eine Entscheidung absichert, nennt sie im Kommentar
  selbst (`// regression guard for ADR-NNNN, Entscheidung M`).
- Zuschnitte von Sub-Issues, Phasenpläne und Umsetzungsreihenfolgen — sie gehören ins Epic.
- Stakeholder-Prosa und Entstehungsgeschichte. Zusagen an Stakeholder stehen als Tabelle
  „Zusage | Stand | geändert mit“, verworfene Alternativen als je eine Zeile.

## Status

- **Vorgeschlagen** gilt nur, bis der PR gemergt ist, der den ADR einführt. Mit dem Merge wird der
  Status „Akzeptiert · zuletzt geändert JJJJ-MM-TT“; das Datum ist das der letzten inhaltlichen
  Änderung und wird mit jeder Fortschreibung nachgezogen. Der Maintainer entscheidet über den
  Merge und damit über die Annahme; eine spätere Umsetzung ändert den Status nicht mehr.
- Sind **alle Entscheidungen** eines ADR abgelöst, wird er ein Stub von höchstens 20 Zeilen mit
  dem Status „Ersetzt durch ADR-XXXX · zuletzt geändert JJJJ-MM-TT“. Die Datei bleibt, damit
  Verweise weiter auflösen.
- Die Statuszeile ist die erste Zeile unter `## Status`, beginnt mit „Akzeptiert“, „Vorgeschlagen“
  oder „Ersetzt durch“ und enthält „zuletzt geändert JJJJ-MM-TT“; weitere Angaben folgen mit „·“
  (etwa „ergänzt durch ADR-XXXX“, „ersetzt Entscheidung N von ADR-YYYY“).

## Form

Deutsch, knapp, im Zweifel kürzer. Eine Entscheidung: Endstand in 1–10 Zeilen, Begründung in 1–5
Sätzen. Codeverweise müssen existieren — jede Entscheidung vor dem Commit gegen den Code prüfen.
Dateiname `NNNN-<thema>.md`, Thema in Kleinbuchstaben mit Bindestrichen.
