# Fortschrittsberichte

Dieses Verzeichnis führt die Leistungsberichte des Projekts auf **zwei Ebenen**:

- **Zeitraumsberichte** (`JJJJMMTT/report.md`): je Stichtag ein Unterverzeichnis mit Bericht
  **und** vollständiger Beleg-Grundlage. Ein Zeitraumsbericht enthält ausschließlich das
  **Delta zum vorigen Stichtag** — was in diesem Zeitraum getan wurde („Audit fertig gebaut"),
  nicht den Gesamtzustand. Er ist der belegbare Tätigkeitsnachweis für den Zeitraum.
- **[gesamtstand.md](./gesamtstand.md)**: der **konsolidierte Produktstand**, der bei jedem
  Stichtag aus dem Delta fortgeschrieben wird. Er beschreibt, was heute gebaut ist; jede
  Aussage ist über die Zeitraumsberichte auf Bausteine rückführbar.

Daneben existiert der **[Tagesreport](./tagesreport.md)**: ein ticketbasierter, täglich per CI
generierter Report über abgeschlossene Vorgänge und gemergte Pull Requests (GitHub Pages, als
Atom-Feed abonnierbar). Er liefert den Tagestakt ohne den Beleg-Anspruch der Zeitraumsberichte;
`tagesreport.md` beschreibt, wie er erzeugt wird und wo er erscheint.

Erster Stichtag: [20260831](./20260831/) (Meilenstein 1, siehe [VISION.md](../VISION.md),
Roadmap). Sonderfall: Sein Zeitraum reicht vom Projektstart bis zum Stichtag — Delta und
Gesamtstand sind dort einmalig deckungsgleich.

## Aufbau je Stichtag

```
JJJJMMTT/
├── anker.md         Diff-Anker: Commit-Stand von main und Abfragezeitpunkt der Issues/PRs
├── bausteine.md     alle Bausteine des Zeitraums in einer Datei: je Issue einer,
│                    je unverknüpftem PR einer — mit Anker `#issue-NNN` bzw. `#pr-NNN`
├── gruppierung.md   Zuordnung jedes Bausteins zu genau einem Themenbereich
└── report.md        der Bericht — bis zur Abnahme als ENTWURF markiert
```

**Nur nicht Reproduzierbares wird committet.** Rohdaten-Dumps (Issue-/PR-JSON) und ein
Issue↔PR-Index sind jederzeit per `gh` erneut abzurufen und gehören deshalb nicht ins
Repository, sondern in ein Arbeitsverzeichnis außerhalb. Committet wird, was nur einmal
entsteht: die Anker, die Bausteine (Abweichungsanalyse und Realitätscheck gegen den
Code-Stand des Stichtags), die Gruppierung und der Bericht.

## Wie ein Bericht entsteht

1. **Anker ziehen.** Commit-Hash von `main` und Zeitstempel der GitHub-Abfrage in `anker.md`
   festhalten. Beides trennen: Der Hash datiert den Code-Stand, der Zeitstempel die Issue-Menge.
2. **Rohdaten erheben.** Geschlossene Issues und gemergte PRs als JSON dumpen — außerhalb des
   Repositorys, die Dumps werden nicht committet
   (`gh issue list --state closed …`, `gh pr list --state merged …` mit `closingIssuesReferences`),
   Issue↔PR-Zuordnung daraus ableiten; PRs ohne „Closes"-Referenz gesondert führen und ihre im
   Titel/Body erwähnten Issue-Nummern als weiche Referenz erfassen.
3. **Bausteine schreiben.** Alle Bausteine eines Stichtags stehen in **einer** Datei
   `bausteine.md`, je Baustein ein `## `-Abschnitt mit vorangestelltem Anker
   (`<a id="issue-NNN"></a>` bzw. `<a id="pr-NNN"></a>`). Pro Issue: was laut Issue gefordert
   war, was laut PR(s) tatsächlich gemergt wurde (Abweichungen ausdrücklich benennen), kurzer
   Realitätscheck gegen den heutigen Code, Themen-Schlagworte. Format siehe unten. Bausteine sind roh und ungeschönt — sie sind
   die Wahrheitsquelle, nicht die Erzählung.
4. **Gruppieren.** Jeden Baustein genau einem Bereich zuordnen: die elf Themenbereiche A–K der
   Vision, ergänzt um T1–T3 (Projektsetup, Agenten-Organisation, Testinfrastruktur), V
   (Produktvision) und P (Projekt als Produkt). Umgruppieren ist erlaubt, solange der Report
   Entwurf ist; die Bausteine bleiben unverändert.
5. **Zeitraumsbericht schreiben.** Nur aus den gruppierten Bausteinen des Zeitraums, mit
   Issue-/PR-Nummern als Beleg — als Delta formuliert („wurde gebaut"), nie als Zustand.
   **Regel: Der Report zeigt nur den Endzustand des Zeitraums.** Gebautes, das im selben Zeitraum
   wieder zurückgebaut oder ersetzt wurde, ist kein Leistungsposten — es bleibt in den Bausteinen
   dokumentiert. Lücken werden als dokumentierte Entscheidungen mit Issue-Nummern benannt, nicht
   verschwiegen.

   Jeder Zeitraumsbericht hat einen festen Rahmen:
   - **Oben eine Management Summary:** geschätzter Umsetzungsgrad der aktuellen Produktphase
     (in Prozent, mit einem Satz Herleitung) und eine Liste dessen, was mit dem Produkt nach
     diesem Zeitraum konkret möglich ist.
   - **Unten zwei Schlusskapitel:** „Lücken und bewusste Schnitte" (was fehlt oder anders
     geschnitten wurde als geplant, je mit Issue-Nummer) und „Offen für Phase N — priorisierte
     Restliste" gegen die jeweils aktuelle Phasendefinition der [Vision](../VISION.md), nach
     Gewicht sortiert.
6. **Gesamtstand fortschreiben.** Das Delta in [gesamtstand.md](./gesamtstand.md) einarbeiten:
   Neues ergänzen, im Zeitraum Zurückgebautes dort entfernen, Formulierungen auf „ist gebaut"
   halten. Der Gesamtstand nennt je Aussage den Stichtag, mit dem sie zuletzt bestätigt wurde.

## Fortschreibung zum nächsten Stichtag

Neues Verzeichnis anlegen und **nur das Delta** erheben:

- **Code:** `git log <alter-anker-hash>..main` — jeder Squash-Commit auf `main` ist genau ein PR.
- **Issues:** `gh issue list --state closed --search "closed:><alter-abfragezeitpunkt>"`.
- Für das Delta eine neue `bausteine.md` schreiben, die **nur die Bausteine des Zeitraums**
  enthält; die des Vorgängers bleiben in seinem Verzeichnis und werden nicht kopiert.
  Gruppierung und Report fortschreiben. Der alte Bericht bleibt als historisches Dokument stehen.

## Baustein-Format

```markdown
<a id="issue-NNN"></a>

## Issue #NNN — Titel
- Geschlossen: JJJJ-MM-TT (completed | not planned)
- Labels: …
- PRs: #a (JJJJ-MM-TT), #b (JJJJ-MM-TT) | keine

**Laut Issue:** Was gefordert bzw. beschrieben war (2–4 Sätze).

**Geliefert:** Was laut PR(s) tatsächlich gemergt wurde; Abweichungen vom Issue ausdrücklich
benennen. Bei „not planned": nicht umgesetzt — und warum, sofern erkennbar.

**Verifikation:** Kurzer Realitätscheck gegen den heutigen Code (zentrale Dateien/Klassen noch
vorhanden? Später entfernt oder ersetzt — durch was?).

**Themen:** freie Schlagworte für die Gruppierung
```

Für PRs ohne Issue-Verknüpfung analog (`## PR #NNN — Titel`, Anker `pr-NNN`) mit
`Bezug: #a, #b | keiner` für weiche Referenzen. Die Abschnitte werden durch `---` getrennt.

## Stolperfallen aus der ersten Inventur (20260831)

- GitHub verknüpft PRs gelegentlich falsch (z. B. „Closes #N" als Beispieltext im PR-Body) —
  thematisch unpassende Verknüpfungen als Fehlzuordnung kennzeichnen.
- `stateReason: completed` heißt nicht „geliefert": Issues wurden auch formal geschlossen, weil
  ein Nachfolgemodell sie ersetzte. Deshalb gehört die tatsächliche Lieferung in jeden Baustein.
- Epics haben keinen eigenen PR — die Lieferung steckt in den Sub-Issues.
- Manche PRs schließen ihr Issue nur in der Commit-Message, nicht über die GitHub-Verknüpfung —
  die weichen Referenzen fangen das auf.
