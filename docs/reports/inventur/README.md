# Leistungsinventur (Meilenstein 1)

Bestandsaufnahme aller abgeschlossenen Issues und gemergten PRs als Grundlage für den
Meilenstein-1-Report (siehe [VISION.md](../../VISION.md), Roadmap Punkt 4, und Issue #744).

## Aufbau

- **[anker.md](./anker.md)** — der Diff-Anker: Commit-Stand und Abfragezeitpunkt dieser Inventur.
  Spätere Fortschreibungen erheben nur das Delta ab diesem Anker.
- **[index.md](./index.md)** — automatisch erzeugter Index: jedes geschlossene Issue mit seinen
  gemergten PRs, dazu die PRs ohne Issue-Verknüpfung.
- **[bausteine/](./bausteine/)** — je Issue ein Baustein (`issue-NNN.md`), je unverknüpftem PR
  ein Baustein (`pr-NNN.md`): was laut Issue gefordert war, was laut PR tatsächlich geliefert
  wurde, kurzer Realitätscheck gegen den Code.

## Baustein-Format

```markdown
# Issue #NNN — Titel
- Geschlossen: JJJJ-MM-TT (completed | not planned)
- Labels: …
- PRs: #a (JJJJ-MM-TT), #b (JJJJ-MM-TT) | keine

**Laut Issue:** Was gefordert bzw. beschrieben war (2–4 Sätze).

**Geliefert:** Was laut PR(s) tatsächlich gemergt wurde; Abweichungen vom Issue ausdrücklich benennen.
Bei „not planned": nicht umgesetzt — und warum, sofern aus Kommentaren erkennbar.

**Verifikation:** Kurzer Realitätscheck gegen den heutigen Code (zentrale Dateien/Klassen vorhanden?
Später wieder entfernt oder ersetzt? Dann: durch was).

**Themen:** freie Schlagworte für die spätere Gruppierung (z. B. auth, deployment, retrieval, projektsetup)
```

Die Bausteine sind bewusst roh und ungeschönt — die Erzählung entsteht erst im Report,
die Bausteine bleiben die belegte Wahrheitsquelle.
