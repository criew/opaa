# ADR-0031: Gesprächsgedächtnis — wörtliches Fenster plus sichtbare Gesprächsnotiz

## Status

Akzeptiert · zuletzt geändert 2026-10-14

## Kontext

Das Gesprächsgedächtnis eines Chats war ein festes Fenster von 20 Nachrichten, das
Teilfragen-Zerlegung und Antwort unverändert und gemeinsam bekamen, samt der Zitiermarken früherer
Antworten. Drei Fehlerbilder (#1446): **Themen-Bleed** (die Zerlegung sah zehn Runden, ihr
Sicherheitsgurt ankerte gegen den Verlauf, nach einem Themenwechsel legitimierte das alte Thema
weiterhin Teilfragen), **Zitatwiederholung** (Marken für Dokumente, die in der aktuellen Runde nicht
im Kontext sind), **harte Vergessensgrenze** (eine Rahmenangabe aus Runde 1 ist in Runde 12 weg).
Der Folgefragen-Pfad war ungemessen. Bindend aus [ADR-0014](0014-produktausrichtung-oeffentliche-verwaltung.md)
und [security-and-compliance.md](../features/security-and-compliance.md#mitbestimmungsfähigkeit):
kein personenbezogener Auswertungspfad, und was eine Antwort beeinflusst, muss für die Person
nachvollziehbar sein. Spezifikation: [conversation-memory.md](../features/conversation-memory.md).

## Entscheidung

### 1. Gesprächsfenster — kurz und wörtlich, unterschiedlich dosiert

Die Zerlegung sieht nur ein Suchfenster von zwei Runden (`opaa.query.search-window-turns`), die
Antwort das ganze Fenster (`opaa.query.conversation-window-messages`, 20). Der Sicherheitsgurt der
Zerlegung ankert gegen genau den Kontext, der der Zerlegung gegeben wurde — Frage, Suchfenster,
gerenderte Notizpunkte — als Invariante, nicht als Aufzählung; der Rückfall stellt die letzte statt
der ersten Nutzerfrage voran. Zitiermarken werden an beiden Eingängen ins Gedächtnis entfernt (nach
der Antwort und beim Nachladen), nie im persistierten Text.

### 2. Gesprächsnotiz — klein, persistent, sichtbar, löschbar, pro Chat

Nach jeder Antwort verdichtet das systemweit aktive Chat-Modell nebenläufig die Nutzernachricht der
Runde zu null bis zwei Notizpunkten (`ChatNoteExtraction.MAX_POINTS_PER_TURN`, ≤ 200 Zeichen),
gedeckelt auf `opaa.chat.note.max-items` je Chat (Vorgabe 10, zulässig 1–50; ältester fällt). Eine
fehlgeschlagene Verdichtung wird nicht nachgeholt. Jeder Punkt trägt eine Art: `RAHMEN` (Rolle,
Zuständigkeit, Ort, Zeitraum, Fassung, Organisation, Festlegung) erreicht Zerlegung und Antwort,
`ANTWORTFORM` nur die Antwort; eine Zeile mit unbekanntem Artpräfix wird `ANTWORTFORM`. Die Notiz
enthält keine Antwortinhalte, keine Themen, keine Bewertungen; sie ist im Chat sichtbar, punktweise
löschbar, wird mit dem Chat gelöscht (und, sobald gebaut, mit dem Chat exportiert und mit dem Konto
gelöscht), nicht protokolliert, nicht aggregiert. In den Ankerraum des
Sicherheitsgurts gehen die Notiz**punkte** ein, nicht die Überschrift ihres Blocks
(`ConversationNoteBlock` reicht Modelltext und Ankertext aus einem Rendervorgang getrennt weiter,
der Ankerraum ist eine geprüfte Teilmenge des Modelltexts): Der Gurt vergleicht Teilwörter ab vier
Zeichen, und „Person" oder „Beleg" aus der Überschrift ankerten jede Teilfrage.

### 3. Sichtbarkeit ab drei abgeschlossenen Runden

Die Schaltfläche erscheint, sobald die Notiz einen Punkt hat **und** drei Runden abgeschlossen
sind; die Verdichtung läuft ab Runde 1. Ein Notizpunkt wirkt damit bis zu zwei Runden, bevor er
sichtbar ist — eine bewusste Abweichung von der Transparenzlinie, vertretbar, weil der Inhalt
ausschließlich aus Nachrichten der Person selbst stammt, die Notiz ab dem Erscheinen vollständig und
rückwirkend einsehbar ist und nichts über den Chat hinaus fortbesteht. Fester Wert, kein Parameter.

### 4. Keine Themenwechsel-Erkennung

Das kurze Suchfenster macht den Wechsel billig; das alte Thema ist nach zwei Runden von selbst aus
der Suche verschwunden.

### 5. Messung vor Änderung

Der Messpfad kennt Mehrrunden-Fälle (Klassen `anaphora_resolution`, `topic_switch`,
`constraint_carryover`, seit #1684 `answer_continuation`) mit eigener Baseline; er läuft nächtlich
in einem eigenen CI-Job (ADR-0012, Entscheidung 49) und misst ausschließlich zerlegend.
Nachmessung (#1490) und Ablation (#1587): Das Suchfenster hebt `anaphora_resolution` und
`topic_switch` (Kriterium ist der Anteil gelöster Fälle, nicht die nDCG-Kennzahl); die Notiz
erreicht unter dem gepinnten Eval-Modell ihren Zweck nicht, mit einem produktionsüblichen
Chat-Modell trägt sie die Rahmenangabe in die Zielrunden. Beides ist ein **Modellbefund, kein
Defekt** (#1586). Der nächtliche Mehrrunden-Lauf misst damit eine Konfiguration, die niemand
betreibt — als Regressionswächter richtig, als Aussage über die Produktqualität unbrauchbar; die
Messwerte und der Befundpfad gegen ein Produktionsmodell (#1674) stehen in
`eval/corpus/verwaltung/MAINTENANCE.md`.

## Konsequenzen

- **Einfacher:** Themenwechsel brauchen keine Erkennung; Rahmenangaben gelten über die
  Fensterbreite hinaus, sichtbar und löschbar; Zitatwiederholung entfällt strukturell (Test auf den
  markenfreien Prompt); die Fensterbreite ist benchmarkbar. Wird das Teilen von Chats gebaut, wird
  die Notiz mitgeteilt, und nur der Autor ändert sie.
- **Schwieriger:** Ein zusätzlicher Chat-Modell-Aufruf je Runde, nebenläufig. In den zwei Runden
  nach einem Themenwechsel kann eine Rückfrage falsch aufgelöst werden; in den zwei Runden vor der
  Anzeige wirkt die Notiz unsichtbar. Die Art eines Punkts vergibt das Modell und ist fehlbar; dagegen
  gibt es keine maschinelle Sicherung, nur Prompt-Regel und Löschbarkeit. Eine fehlgeschlagene
  Verdichtung kostet die Angaben dieser einen Runde; Zähler und Logzeile bleiben.
- **Restrisiko:** Weniger Ankertoken machen den Gurt der Sache nach strenger, aber nicht monoton —
  unterhalb von zwei Ankertoken bricht die Prüfung ab (`countUnrelated`); mit einem Notizpunkt
  praktisch nicht erreichbar. Wiederaufnahme der Trennung von Modell- und Ankertext, wenn ein
  künftiger Kontextbaustein keine eigene Rahmung hat.

## Verworfene Alternativen

- Größeres Gesprächsfenster — verstärkt Bleed und Zitatwiederholung, verschiebt das Vergessen nur.
- Rolling Summary (unsichtbar, vom System geschrieben) — beeinflusst Antworten mit Text, den niemand sieht; Dienstvereinbarungsthema.
- Themenwechsel-Erkennung mit Vorschlag „Neuen Chat beginnen?" — Fehlalarme bei Rückfragen mit neuem Vokabular; Kriterium sind gelöste `topic_switch`-Fälle, in denen das Ziel auf Rang 1 bleibt.
- Chatübergreifendes Nutzergedächtnis, Vektorindex über Gesprächsinhalte — Produkthaltung: personenbezogene Auswertung bzw. Speicher von Verhalten.
- Notiz aus Antworten verdichten — unbelegte Aussagen im Systemprompt.
- Notiz ungefiltert in den Zerlegungs-Prompt — Darstellungswünsche sind für die Suche Rauschen.
- Zerlegung ohne Notiz — der Kernfall `constraint_carryover` bliebe ungelöst; bleibt trotz der Messung unter dem Eval-Modell verworfen.
- Verdichtung erst beim Fensterüberlauf oder erst ab Runde 3 — Lücke zwischen Suchfenster und Fensterende bzw. Verlust der häufigsten Angaben.
- Anzeige ab dem ersten Punkt — Oberflächenereignis in kurzen Chats; Wiederaufnahme, wenn die Notiz je etwas enthält, das nicht von der Person stammt.
- Punkte bearbeiten, manuell anlegen, „alle entfernen" — der Chat bietet den Weg selbst; Wiederaufnahme, wenn Personen nachweislich Angaben wiederholen, weil die Verdichtung sie verfehlt.
- Ein-/Ausschalter je Installation oder Chat — kein Adressat; Wiederaufnahme, wenn eine konkrete Dienstvereinbarungsklausel ihn verlangt.
- Fallklasse `long_horizon` — misst nichts, was `constraint_carryover` nicht misst; Wiederaufnahme, wenn ein Generationsharness existiert.
- Verdichtungs-Parser und Prompt gegen das Vorlagen-Ausfüllen des 1,5B-Modells härten (#1586) — behöbe ein Problem, das in keiner Installation auftritt.

## Offen

—

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-09-12 | #1490 | Nachmessung: Fenster trägt, Notiz unter dem Eval-Modell nicht | 5 |
| 2026-09-12 | #1553 | Mehrrunden-Messung nächtlich als Regressionsschutz (ADR-0012, Entscheidung 49) | 5 |
| 2026-09-16 | #1586, #1587, #1674 | Ablation und Befund gegen ein Produktionsmodell: Modellbefund, kein Defekt | 5 |
| 2026-10-14 | #2700 | Neufassung auf den gültigen Endstand | 5 |
