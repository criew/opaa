# UI-Lab: Varianten der Quelleinrichtung (#3084)

Prototyp-Branch, wird nicht gemerged.

- `inputs/inventory.md` – Bestandsaufnahme (Ist-Stand, Verstöße, Randfälle je Konnektor)
- `inputs/anti-ai-checklist.md` – verbindliche Prüfliste (44 Punkte) + Guideline-Vorschläge
- `inputs/best-practices.md` – Recherche mit Quellen
- `harness/` – Messskript: `cd ui-lab/harness && npm ci && NODE_PATH=$(npm root -g) node capture.mjs --base http://localhost:<PORT> --out <dir>`
- Mock-Szenarien: `?mockScenario=default|small|scale|empty|failed|slow|partial`
- Ergebnisse je Variante: `ui-lab/results/<X>/` (REPORT.md + verkleinerte Screenshots + metrics.jsonl)
