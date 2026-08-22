# Issue #274 — fix(eval): Nachziehbedarf aus dem Review des Golden Datasets
- Geschlossen: 2026-08-02 (completed)
- Labels: bug, size:M, evaluation
- PRs: #277 (2026-08-02)

**Laut Issue:** Review-Nachzieharbeit zum Golden Dataset (#226/PR #273). Zwingend: Case-insensitive Vergleich in `_matches_description`/`generate_multi_attribute_filter` (behebt sieben zu Unrecht ausgeschlossene Treffer bei `comic-desc-005`), Sentinel `"∞"` global aus allen Bereichsfragen ausschließen, `CURATED_CASE_IDS` von rein positionell auf fingerprint-basiert umstellen. Zusätzlich: Entitäts-Konzentration bei Einzeldokument-Fällen streuen, Crosslingual-Sampling ausgewogener machen, `discarded.json` entweder mit echten Gründen füllen oder streichen, eine fachlich schiefe Frage (`comic-attr-128`) neutral formulieren, vier deutsche Resistenz-Queries korrigieren, vier ADR-0008→0011-Verweise nachziehen. Blockiert #227/#228.

**Geliefert:** PR #277 behebt alle drei zwingenden Punkte (case-insensitive Vergleich, `CURATED_CASES` als `(natural_key, query)`-Tupel statt Positionsliste) sowie sämtliche „Bitte zusätzlich“-Punkte. Eine fachliche Präzisierung gegenüber dem Issue: die Sentinel-Regel wurde nicht global, sondern **feldbezogen** umgesetzt (Ausschluss nur bei Fragen zu `overall_score` selbst, nicht bei Fragen zu anderen Attributen) — laut PR-Body eine vom Product Manager nachträglich korrigierte Fassung der ursprünglichen Issue-Formulierung. `discarded.json` wurde gestrichen statt mit Gründen gefüllt. Der Fingerabdruck in PR #277 deckte zu diesem Zeitpunkt aber noch nicht die Trefferliste (`expected_documents`) ab — das wurde erst in einer zweiten Review-Runde als Folgeissue #282 nachgezogen (siehe dort), weil der entsprechende Fix nach dem Merge von #277 noch nicht gepusht war.

**Verifikation:** `eval/generator/generate_golden_dataset.py` enthält heute `is_rated`/`has_numeric_overall` als getrennte Prädikate und `_ci_eq`/`casefold`-Vergleiche — Fortsetzung dieser Arbeit über #282. `eval/golden/comic-characters.discarded.json` existiert nicht mehr im Verzeichnis (nur `README.md`, `city-landmarks.json`, `comic-characters.candidates.json`, `comic-characters.json`) — Entfernung bestätigt.

**Themen:** evaluation, retrieval, doku
