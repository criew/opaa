# Fortschrittsberichte

Dieses Verzeichnis führt die datierten Leistungsberichte des Projekts — je Stichtag ein
Unterverzeichnis (`JJJJMMTT/`), das den Bericht **und** seine vollständige Beleg-Grundlage
enthält. Erster Stichtag: [20260831](./20260831/) (Meilenstein 1, siehe
[VISION.md](../VISION.md), Roadmap).

## Aufbau je Stichtag

```
JJJJMMTT/
├── anker.md         Diff-Anker: Commit-Stand von main und Abfragezeitpunkt der Issues/PRs
├── index.md         generierter Index: jedes geschlossene Issue mit seinen gemergten PRs,
│                    dazu gemergte PRs ohne Issue-Verknüpfung
├── bausteine/       je Issue ein Baustein (issue-NNN.md), je unverknüpftem PR einer (pr-NNN.md)
├── gruppierung.md   Zuordnung jedes Bausteins zu genau einem Themenbereich
└── report.md        der Bericht — bis zur Abnahme als ENTWURF markiert
```

## Wie ein Bericht entsteht

1. **Anker ziehen.** Commit-Hash von `main` und Zeitstempel der GitHub-Abfrage in `anker.md`
   festhalten. Beides trennen: Der Hash datiert den Code-Stand, der Zeitstempel die Issue-Menge.
2. **Rohdaten erheben.** Geschlossene Issues und gemergte PRs als JSON dumpen
   (`gh issue list --state closed …`, `gh pr list --state merged …` mit `closingIssuesReferences`),
   Issue↔PR-Zuordnung daraus ableiten; PRs ohne „Closes"-Referenz gesondert führen und ihre im
   Titel/Body erwähnten Issue-Nummern als weiche Referenz erfassen.
3. **Bausteine schreiben.** Pro Issue: was laut Issue gefordert war, was laut PR(s) tatsächlich
   gemergt wurde (Abweichungen ausdrücklich benennen), kurzer Realitätscheck gegen den heutigen
   Code, Themen-Schlagworte. Format siehe unten. Bausteine sind roh und ungeschönt — sie sind
   die Wahrheitsquelle, nicht die Erzählung.
4. **Gruppieren.** Jeden Baustein genau einem Bereich zuordnen: die elf Themenbereiche A–K der
   Vision, ergänzt um T1–T3 (Projektsetup, Agenten-Organisation, Testinfrastruktur), V
   (Produktvision) und P (Projekt als Produkt). Umgruppieren ist erlaubt, solange der Report
   Entwurf ist; die Bausteine bleiben unverändert.
5. **Report schreiben.** Nur aus den gruppierten Bausteinen, mit Issue-/PR-Nummern als Beleg.
   **Regel: Der Report zeigt nur den Endzustand.** Gebautes, das wieder zurückgebaut oder ersetzt
   wurde, ist kein Leistungsposten — es bleibt in den Bausteinen dokumentiert. Lücken werden als
   dokumentierte Entscheidungen mit Issue-Nummern benannt, nicht verschwiegen.

## Fortschreibung zum nächsten Stichtag

Neues Verzeichnis anlegen und **nur das Delta** erheben:

- **Code:** `git log <alter-anker-hash>..main` — jeder Squash-Commit auf `main` ist genau ein PR.
- **Issues:** `gh issue list --state closed --search "closed:><alter-abfragezeitpunkt>"`.
- Für das Delta neue Bausteine schreiben, die bestehenden aus dem Vorgänger-Verzeichnis
  unverändert übernehmen bzw. referenzieren, Gruppierung und Report fortschreiben. Der alte
  Bericht bleibt als historisches Dokument stehen.

## Baustein-Format

```markdown
# Issue #NNN — Titel
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

Für PRs ohne Issue-Verknüpfung analog (`pr-NNN.md`) mit `Bezug: #a, #b | keiner` für weiche
Referenzen.

## Stolperfallen aus der ersten Inventur (20260831)

- GitHub verknüpft PRs gelegentlich falsch (z. B. „Closes #N" als Beispieltext im PR-Body) —
  thematisch unpassende Verknüpfungen als Fehlzuordnung kennzeichnen.
- `stateReason: completed` heißt nicht „geliefert": Issues wurden auch formal geschlossen, weil
  ein Nachfolgemodell sie ersetzte. Deshalb gehört die tatsächliche Lieferung in jeden Baustein.
- Epics haben keinen eigenen PR — die Lieferung steckt in den Sub-Issues.
- Manche PRs schließen ihr Issue nur in der Commit-Message, nicht über die GitHub-Verknüpfung —
  die weichen Referenzen fangen das auf.
