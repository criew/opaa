# Suchqualität messbar machen: Demo-Korpus und Retrieval-Regression

> **Status: Abgestimmt für Phase 1 und 2.** Die strukturellen Entscheidungen sind in
> [ADR-0008](../decisions/0008-search-quality-evaluation-harness.md) (Status: Akzeptiert)
> festgehalten. Verbleibende Punkte stehen unter
> [Offene Fragen](#offene-fragen--zukünftige-erweiterungen).

## Motivation

OPAA kann heute nicht beantworten, ob eine Änderung an Chunking, Embedding-Modell, `top-k` oder
Ähnlichkeitsschwelle die Suche besser oder schlechter macht. Jede Pipeline-Änderung ist damit ein
Blindflug: Sie wird nach Gefühl bewertet und Regressionen fallen erst auf, wenn ein Nutzer sich
beschwert. `docs/discussions/discussion-rag-evaluation.md` hat die Theorie dazu vollständig geklärt
(Golden Dataset, Hit Rate/MRR/nDCG, Spring-AI-Evaluatoren, Paired Bootstrap, RAGAS-Sidecar); was
fehlt, ist ein konkreter Korpus und ein Harness, das die dort beschriebenen Phasen 1 und 2 umsetzt.

Gleichzeitig fehlt OPAA etwas zum Vorzeigen. Interessenten sehen heute eine leere Installation. Ein
öffentlich erreichbarer, gefüllter Index mit einem nachvollziehbaren Datenbestand ist das
wirksamste Marketing-Asset, das ein RAG-Projekt haben kann.

Beide Bedürfnisse teilen sich dieselbe teure Vorleistung: **einen lizenzsauberen, eingefrorenen
Testkorpus mit bekannter Ground Truth**. Deshalb werden sie hier gemeinsam spezifiziert.

---

## Überblick

1. **Ein Generator, ein Korpus, zwei Konsumenten.** Ein deterministischer Generator erzeugt aus
   einem eingefrorenen Quelldaten-Snapshot Markdown-Dateien. Derselbe Korpus speist die
   öffentliche Demo *und* den CI-Regressionstest.
2. **Ein Dokument pro Entität.** Nicht die Rohdatei (CSV/JSON) wird indiziert, sondern pro
   Datensatz eine Markdown-Datei mit YAML-Frontmatter plus generiertem Fließtext.
3. **Das Frontmatter ist die Ground Truth.** Aus den strukturierten Feldern werden Golden-Queries
   teilautomatisch abgeleitet, inklusive der Menge der relevanten Dokumente.
4. **Der Korpus ist eingefroren.** Quelldaten-Snapshot einmalig, deterministische Generierung,
   SHA-256-Manifest über alle generierten Dateien im Repository. Nie live von einer Fremdquelle
   ziehen.
5. **Demo und Regression laufen entkoppelt.** Die Demo nutzt den bestehenden
   HTTP-Verzeichnis-Konnektor gegen einen statischen Webserver im Compose-Stack. Der
   Regressionstest ist ein JUnit-Integrationstest gegen Testcontainers und braucht die Demo nicht.
6. **Eine Domäne komplett, dann die anderen.** Comichelden wird end-to-end durchgezogen (Generator
   → Golden Dataset → Metriken → CI → Demo). Filme, Reiseziele und Tiere folgen erst danach über
   denselben, dann bewährten Pfad.
7. **Nur CC0 und CC BY.** Der verbreitete Korpus enthält ausschließlich Quellen unter CC0 oder
   CC BY. CC BY-SA wird vermieden, IMDb ist ausgeschlossen. Für Phase 1 ist die Quelle CC0.

---

## Was der bestehende Code hergibt — und was nicht

Vor der Planung wurde der Ist-Stand geprüft. Drei Annahmen aus dem Auftrag bestätigen sich, drei
nicht.

**Bestätigt:**

- Der HTTP-Verzeichnis-Konnektor existiert und ist einsatzfähig (`io.opaa.indexing`, Crawler +
  Downloader + Job-Verwaltung, ausgelöst über `POST /api/v1/indexing/trigger` mit `url`). Für die
  Demo-Ingestion ist **kein neuer Ingestion-Code** nötig.
- `.md` ist ein unterstütztes Format; `.csv` und `.json` sind es nicht. Die Leitentscheidung
  „ein Dokument pro Entität als Markdown" ist damit nicht nur fachlich richtig, sondern die
  einzige Variante, die ohne Code-Änderung überhaupt funktioniert.
- Die Extraktion läuft über Apache Tika, das Chunking über einen Token-Splitter mit
  `chunkSize=1000` Tokens und `minChunkSizeChars=350`. Ein Entitäts-Dokument von rund 1–2 KB
  ergibt damit **genau einen Chunk**. Das ist der Idealfall für saubere Ground Truth: Ein
  gefundener Chunk entspricht eindeutig einer Entität.

**Nicht bestätigt — hier weicht der Plan von der Annahme ab:**

- **Workspaces sind noch nicht in der Suche verankert.** Workspace-Entitäten, CRUD und
  Mitgliedschaften existieren, aber Dokumente tragen keine Workspace-Zuordnung und der
  Vektor-Chunk trägt nur `document_id`, `chunk_index` und `file_name` als Metadaten. Die Suche
  filtert nicht nach Workspace. Die Idee „vier Domänen = vier Workspaces zeigt Multi-Tenancy" ist
  heute **nicht umsetzbar** und hängt an Issue #115 (`workspace_ids` in Chunk-Metadaten und
  Query-Filter) sowie #117 (Konnektor-Workspace-Integration). **Entscheidung des Maintainers:
  Phase 1 und 2 bauen einen einzelnen, gemeinsamen Index; Domänen werden über ein
  Dateinamen-Präfix getrennt (`comic-…`, `movie-…`), was für Auswertung und Anzeige ausreicht. Die
  Multi-Tenancy-Demonstration wird als eigenes, explizit blockiertes Issue geführt und wartet auf
  #115 und #117 — nicht auf das Space-Modell aus Epic #198.**
- **Ein „nginx-Container" funktioniert so nicht.** Der Crawler parst
  Apache-`mod_autoindex`-Listings als HTML-**Tabelle**: Er erwartet `<tr>` mit mindestens vier
  `<td>` und ein Icon-`<img>` mit `alt`-Attribut. Das Standard-`autoindex` von nginx erzeugt eine
  `<pre>`-Liste und wird vom Parser vollständig ignoriert. Der Demo-Webserver muss ein
  Apache httpd mit `IndexOptions FancyIndexing HTMLTable` sein (oder ein statisch vorgeneriertes
  Listing in genau diesem Format).
- **Es gibt keinen retrieval-only-Pfad und keine E2E-Suite.** `QueryService` bündelt Suche und
  LLM-Antwort; ein reiner Retrieval-Aufruf ist über die API nicht möglich. Für Retrieval-Metriken
  ist das kein Problem — der Harness spricht den Vektor-Store direkt an und braucht dafür kein
  LLM. Für die E2E-Szenarien fehlt hingegen jedes Grundgerüst (kein Playwright, keine
  Browser-Tests, `backend/src/test/.../integration` enthält nur einen OpenAI-Test). Das
  E2E-Grundgerüst wird deshalb als eigenes Issue geführt.

---

## Der Testkorpus

### Domänen und was sie prüfen sollen

| Domäne | Prüft gezielt | Quelle (Phase) |
|---|---|---|
| Comichelden | viele numerische und kategoriale Attribute, kaum Prosa — hier bricht reine Vektorsuche | HuggingFace `jrtec/Superheroes`, **CC0-1.0** (Phase 1) |
| Filme | lange Fließtexte, Multi-Value-Felder (Genres, Besetzung) | Wikidata (CC0), ersatzweise TMDB mit Attribution (Phase 2) |
| Reiseziele | Geo-Bezug, deutsche Anfrage auf englischem Korpus | TourPedia (CC0), Wikivoyage, OpenStreetMap (Phase 2) |
| Tiere | Taxonomie-Hierarchie, Anknüpfung an `docs/GraphRAG.md` | GBIF (CC0/CC BY), UCI Zoo (Phase 2) |

Comichelden zuerst, weil die Domäne den härtesten Fall abbildet: Attribut-Fragen („welche
Marvel-Figuren sind böse und haben rote Haare?") sind exakt das, woran reines Vektor-Retrieval
scheitert. Wenn der Harness diesen Fall messbar macht, ist er auch für die einfacheren Domänen
tragfähig.

### Lizenz-Rahmen (hart)

- Nur **CC0** oder **CC BY** kommen in den verbreiteten Korpus.
- CC BY erfordert einen sichtbaren Attributionshinweis in der Demo und eine `ATTRIBUTION.md` neben
  dem Korpus.
- **CC BY-SA wird vermieden** — Copyleft würde auf den Korpus abfärben.
- **IMDb-Datensätze sind ausgeschlossen** (non-commercial, keine Weiterverbreitung erlaubt).

**Entscheidung des Maintainers zur Phase-1-Quelle:** Es wird `jrtec/Superheroes` (CC0-1.0)
verwendet, nicht der FiveThirtyEight-Datensatz. Damit entfällt die Attributionspflicht und die
Frage einer möglichen CC-BY-SA-Ansteckung über die Fandom-Ursprungsdaten ist gegenstandslos. Ein
Quellenhinweis wird trotzdem geführt — nicht weil die Lizenz ihn verlangt, sondern weil
Nachvollziehbarkeit zum Charakter des Korpus gehört.

Unabhängig von der Lizenz gilt weiterhin: **Der Generator übernimmt nur strukturierte Faktenfelder
und formuliert den Fließtext selbst.** Die Felder `history_text` und `powers_text` des Datensatzes
werden **nicht** übernommen. Zwei Gründe, beide unabhängig voneinander ausreichend:

1. `history_text` reicht bis 130.000 Zeichen. Ein solcher Datensatz ergäbe dutzende Chunks statt
   einem — die eindeutige Zuordnung „ein Chunk = eine Entität" und damit die saubere Ground Truth
   wären dahin.
2. Der Prosatext ist fremder Ursprung; die Faktenfelder sind es nicht. Ihn wegzulassen hält die
   Provenienz sauber, ohne dass eine Bewertung nötig wird.

Der Verzicht auf die Prosa schwächt die Domäne nicht, sondern schärft sie: Comichelden sollen
gerade den Fall „viele Attribute, kaum Fließtext" abbilden.

### Dokumentenformat

Eine Datei pro Entität, Dateiname als stabiler Slug mit ID:
`comic-0142_spider-man.md`

```markdown
---
id: comic-0142
domain: comic-characters
name: Spider-Man
real_name: Peter Parker
creator: Marvel Comics
alignment: good
gender: Male
type_race: Human
place_of_birth: New York City
first_appearance: Amazing Fantasy #15
occupation: Photographer
teams: Avengers, Fantastic Four
eye_color: Hazel
hair_color: Brown
height_cm: 178
weight_kg: 76
intelligence_score: 90
strength_score: 55
speed_score: 60
durability_score: 75
combat_score: 85
overall_score: 74
superpowers: agility, wall-crawling, super strength, reflexes, stamina
source: huggingface/jrtec/Superheroes
license: CC0-1.0
---

# Spider-Man

Spider-Man, real name Peter Parker, is a good-aligned male Human character created by
Marvel Comics, born in New York City and first appearing in Amazing Fantasy #15. He works
as a photographer and is affiliated with the Avengers and the Fantastic Four. He has hazel
eyes and brown hair, stands 178 cm tall and weighs 76 kg. His notable powers include
agility, wall-crawling, super strength, reflexes and stamina. Rated across attributes, he
scores 90 for intelligence, 55 for strength, 60 for speed, 75 for durability and 85 for
combat, giving an overall score of 74.
```

Diese Quelle ist für die Domäne deutlich ergiebiger als der zuvor erwogene Datensatz: Neben den
kategorialen Feldern liefert sie sechs numerische Bewertungen und rund 60 boolesche
Fähigkeits-Merkmale (`has_flight`, `has_telepathy`, …). Genau daraus entstehen die
Filter- und Bereichsfragen, an denen reine Vektorsuche scheitert.

Zwei Punkte, die bei der Umsetzung bewusst sein müssen:

- Tika liefert das Frontmatter als Teil des Fließtexts aus — es wird also mit eingebettet. Das ist
  gewollt, weil es Attributbegriffe in den Vektorraum bringt.
- Die booleschen Merkmale gehören ins Frontmatter (als Liste gesetzter Fähigkeiten, nicht als 60
  einzelne `false`-Zeilen) und in den Fließtext nur, soweit sie gesetzt sind. Andernfalls wird das
  Dokument von Rauschen dominiert und alle Entitäten ähneln sich im Vektorraum.

### Einfrieren des Korpus

Ohne diesen Punkt ist die gesamte Regression wertlos:

1. **Snapshot** der Rohquelle einmalig herunterladen, mit Abrufdatum, Quell-URL und
   SHA-256-Summe der Rohdatei festhalten.
2. **Kein Sampling in Phase 1.** Der Datensatz umfasst rund 1.450 Entitäten (Splits `train` und
   `test` zusammengenommen) und wird vollständig verwendet. Das ist gegenüber dem ursprünglich
   geplanten Ziehen von 1.000 aus 23.000 die bessere Variante: Es entfällt ein Freiheitsgrad
   (Sampling-Seed), der Korpus ist ohne Zusatzannahme reproduzierbar, und die Größenordnung liegt
   weiterhin im Rahmen. Sortierung nach `id` vor der Generierung stabil festlegen.
3. **Deterministische Generierung** — gleicher Input, byte-identischer Output.
4. **`MANIFEST.sha256`** über alle generierten Dateien im Repository. Der Regressionstest prüft
   das Manifest vor dem Lauf und bricht bei Abweichung ab.
5. Jede Korpus-Änderung ist eine bewusste, reviewte Änderung mit neuem Baseline-Lauf.

### Ablage — Optionen

| Option | Für | Gegen |
|---|---|---|
| 1. Generierte Dateien im Hauptrepo unter `eval/corpus/` | Regressionstest läuft ohne Netz; Diffs sind reviewbar; kein Zusatz-Tooling | Repo wächst (bei ~1.450 Dateien × ~1,5 KB ≈ 2 MB — vertretbar); bei vier Domänen ggf. 10–20 MB |
| 2. Git LFS | Repo-Historie bleibt schlank | Zusätzliches Tooling für alle Beitragenden, LFS-Kontingent, CI-Komplexität |
| 3. GitHub-Release-Artefakt, im Test heruntergeladen | Repo bleibt sauber | CI braucht Netz; Artefakt kann ersetzt werden → Einfrieren nur noch durch Konvention gesichert |

**Entschieden: Option 1** (ADR-0008), solange der Gesamtkorpus unter etwa 25 MB bleibt. Wird diese
Grenze bei der Ausweitung auf vier Domänen überschritten, wird pro Domäne auf Option 3 gewechselt,
mit dem SHA-256-Manifest weiterhin im Repository. Die Neubewertung ist als Prüfpunkt in der
Ausweitung verankert.

---

## Golden Dataset

Struktur pro Testfall nach `discussion-rag-evaluation.md` §5.3, ergänzt um die Domäne:

```json
{
  "id": "comic-q-042",
  "domain": "comic-characters",
  "query": "Welche Augenfarbe hat Spider-Man?",
  "expected_documents": ["comic-0142_spider-man.md"],
  "category": "attribute_lookup",
  "difficulty": "easy",
  "language": "de",
  "type": "factual"
}
```

### Ableitung aus dem Frontmatter

Der Generator erzeugt Kandidaten aus Vorlagen; die Ground Truth ergibt sich dabei rechnerisch aus
den strukturierten Feldern und nicht aus einer LLM-Vermutung.

| Kategorie | Vorlage | Ground Truth | Was gemessen wird |
|---|---|---|---|
| `attribute_lookup` | „Welche Augenfarbe hat {name}?" | genau die eine Datei | Hit Rate, MRR |
| `entity_description` | Paraphrase des generierten Fließtexts | genau die eine Datei | MRR, nDCG |
| `multi_attribute_filter` | „Welche {alignment} Figuren von {creator} können {superpower}?" | alle passenden Dateien | Recall@k — der Fall, an dem Vektorsuche bricht |
| `numeric_range` | „Welche Figuren haben einen Intelligenzwert über {n}?" | alle passenden Dateien | Recall@k |
| `crosslingual` | deutsche Frage auf englischem Korpus | wie oben | Robustheit des Embeddings |

Filter-Fragen werden nur aufgenommen, wenn die Treffermenge klein genug ist (Vorschlag: 2–15
Dokumente) — sonst ist die Metrik nicht aussagekräftig. Die rund 60 booleschen
Fähigkeits-Merkmale des Datensatzes lassen sich dafür kombinieren, bis die Treffermenge in diesem
Fenster liegt.

### Kuratierung

Vollautomatisch generierte Fälle sind ein „Silver Dataset". Vor der Aufnahme in die Baseline wird
eine Stichprobe manuell geprüft: keine mehrdeutigen Fragen, keine Fragen mit leerer oder
allumfassender Treffermenge, keine Duplikate. Zielgröße Phase 1: **100 kuratierte Fälle**,
verteilt über alle fünf Kategorien und über DE und EN.

Die Zielgröße von 100 Fällen bleibt beim Wechsel auf den kleineren Korpus (~1.450 statt der
zuvor geplanten 1.000 gesampelten Entitäten) unverändert gültig — sie liegt am unteren Rand der
in `discussion-rag-evaluation.md` §5.5 empfohlenen 50–100 und ist von der Korpusgröße ohnehin
weitgehend unabhängig: Was zählt, ist die Zahl der Anfragen, nicht die der Dokumente. Der
Attributreichtum der Quelle (sechs numerische Werte, ~60 boolesche Merkmale) macht 100 diverse,
nicht-triviale Fälle sogar leichter erreichbar als beim ursprünglich vorgesehenen Datensatz.

---

## Retrieval-Harness und Regression in CI

### Aufbau

Der Harness ist ein Spring-Boot-Integrationstest, kein Aufruf gegen ein laufendes System:

```
JUnit-Test
  ├─ Testcontainers: pgvector/pgvector:pg18
  ├─ Testcontainers: Ollama mit nomic-embed-text
  ├─ Korpus aus eval/corpus/ indizieren (Manifest vorher prüfen)
  ├─ pro Golden-Query: VectorStore.similaritySearch(topK=10)
  ├─ Treffer über Chunk-Metadatum file_name auf Entitäten abbilden
  └─ Hit Rate@5, MRR, nDCG@10, Recall@10 berechnen → Report + Baseline-Vergleich
```

Kein LLM ist beteiligt. Retrieval-Metriken sind reine Ranking-Metriken; die Generationsmetriken
(`RelevancyEvaluator`, `FactCheckingEvaluator`) folgen in einer späteren Phase.

### Einbettungsmodell in CI — Optionen

| Option | Für | Gegen |
|---|---|---|
| 1. Ollama-Container mit `nomic-embed-text` | kostenlos, kein Secret, entspricht der Standardkonfiguration von OPAA, über die Modellversion reproduzierbar | Modell-Pull (~275 MB) und Einbettung von ~1.450 Dokumenten kosten Laufzeit |
| 2. OpenAI `text-embedding-3-small` | schnell, folgt dem bestehenden `backend-integration`-Job | kostet Geld, braucht ein Secret (in Forks nicht verfügbar), Anbieter kann das Modell still ändern → Baseline driftet ohne Code-Änderung |
| 3. Vorberechnete Embeddings im Repo | schnell und deterministisch | misst nur noch den Ranking-Code, nicht die Pipeline — nutzlos für Modellvergleiche |

**Empfehlung: Option 1 als Standard**, Option 2 als optionaler zusätzlicher Lauf für Vergleiche.

### Baseline und Fehlerkriterium

Baseline-Werte liegen als JSON im Repo. Der Job schlägt fehl, wenn eine der Primärmetriken
entweder eine harte Untergrenze unterschreitet **oder** um mehr als eine Toleranz unter die
Baseline fällt (Vorschlag: 0,03 absolut). Verbesserungen schlagen nie fehl; sie erzeugen einen
Hinweis, dass die Baseline aktualisiert werden sollte. Baseline-Aktualisierungen sind bewusste,
reviewte Commits.

Für einen belastbaren *Vergleich* zweier Konfigurationen (nicht für die Regression) gilt weiterhin
das gepaarte Verfahren aus `discussion-rag-evaluation.md` §7. Für die reine
Regressionsüberwachung genügt der Schwellenvergleich.

### Auslösung

Nicht bei jedem Pull Request. Vorschlag: nächtlich auf `main`, zusätzlich manuell auslösbar und
per Label `evaluation` an einem PR. Der Report wird als CI-Artefakt abgelegt.

### Bekanntes Risiko: HNSW ist approximativ

Der pgvector-Index ist fest auf `hnsw` gesetzt (siehe auch Issue #77 zum hartkodierten Indextyp).
Approximative Nachbarsuche kann bei identischen Eingaben leicht abweichende Ranglisten liefern und
so Metriken um Bruchteile schwanken lassen. Für den Regressionsjob wird der Korpus deshalb klein
gehalten und die Toleranz entsprechend gewählt. Falls sich Flattern zeigt, ist der saubere Weg,
den Indextyp für die Evaluierung konfigurierbar zu machen und exakte Suche zu verwenden.

---

## Öffentliche Demo

### Ablauf

```
eval/corpus/comic-characters/*.md
        │  (Bind-Mount)
        ▼
 Apache httpd Container (IndexOptions FancyIndexing HTMLTable)
        │  http://corpus/comic-characters/
        ▼
 POST /api/v1/indexing/trigger  { "url": "http://corpus/comic-characters/" }
        │
        ▼
 bestehender UrlIndexingExecutor → Tika → Chunking → pgvector
        ▼
 Suche über die bestehende Weboberfläche
```

Der Korpus-Container läuft im Compose-Stack unter einem eigenen Profil, damit eine normale
Entwicklungsumgebung ihn nicht startet.

### Die öffentliche Instanz

**Sie existiert bereits: `opaa.ewerlin.com`.** Es ist also kein Hosting aufzubauen, sondern der
Korpus auf eine laufende Instanz auszurollen. Das verkleinert Phase 2 erheblich.

Zwei Folgerungen:

- **Kein kommerzielles Modell.** Der Stack nutzt `OPAA_AI_CHAT_PROVIDER` mit Vorgabe `ollama` und
  `phi3:mini`; die bestehende Konfiguration der Instanz gilt unverändert. Damit ist das
  Kostenrisiko einer öffentlich erreichbaren Suche vom Tisch, und die Demo zeigt OPAA in genau der
  selbstgehosteten Betriebsart, für die es gebaut ist.
- **Die Instanz ist nirgends dokumentiert.** Weder `docs/deployment.md` noch `docker-compose.yml`
  erwähnen sie. Das ist eine eigenständige Lücke und wird als eigenes Dokumentations-Issue geführt;
  ohne diese Beschreibung kann ein Entwickler den Rollout gar nicht durchführen.

Anforderungen an den Betrieb der Instanz:

- **Anonymer Lesezugriff** auf die Suche, keine Schreib- oder Admin-Endpunkte von außen. Der
  Indizierungs-Endpunkt ist bereits auf `SYSTEM_ADMIN` beschränkt und darf so bleiben.
- **Rate Limiting** ist vorhanden (`opaa.rate-limit`) und muss für die Demo scharf gestellt sein.
  Auch ohne Token-Kosten bleibt es nötig: Ein selbstgehostetes Modell ist die knappere Ressource,
  nicht die billigere.
- **Quellenhinweis** sichtbar in der Oberfläche. CC0 verlangt ihn nicht; er wird trotzdem geführt,
  weil ein Besucher wissen soll, woher die Daten stammen.
- **Hinweis auf den Demo-Charakter**: synthetisch formulierte Texte, keine Faktenautorität.

---

## Phasen

| Phase | Inhalt | Ergebnis |
|---|---|---|
| 1 | Comichelden: Generator, Golden Dataset, Metrik-Harness, CI-Job | Suchqualität ist eine Zahl, Regressionen fallen auf |
| 2 | Demo-Ingestion im Compose-Stack, Rollout auf die bestehende Instanz, E2E-Szenarien | OPAA ist vorführbar |
| 3 | Ausweitung auf Filme, Reiseziele, Tiere | breitere Abdeckung, Mehrsprachigkeit, Taxonomie |
| 4 | Generationsmetriken (Spring-AI-Evaluatoren), später RAGAS-Sidecar | Antwortqualität statt nur Trefferqualität |

Phase 1 und 2 sind der Gegenstand der jetzt erstellten Issues.

---

## Integrationspunkte

- `docs/discussions/discussion-rag-evaluation.md` — Metriken, Frameworks, statistische Verfahren.
  Diese Spezifikation wiederholt sie nicht, sondern setzt Phase 1 und 2 daraus um.
- `docs/features/data-indexing-rag.md` — die Pipeline, die hier gemessen wird.
- `docs/features/deployment-infrastructure.md` — der Compose-Stack, den die Demo erweitert.
- `docs/GraphRAG.md` — die Tier-Domäne mit ihrer Taxonomie ist der natürliche Testfall, falls
  GraphRAG evaluiert wird.
- Issues #115 und #117 — Voraussetzung für die Multi-Tenancy-Demonstration.
- `docs/deployment.md` — beschreibt die bestehende öffentliche Instanz `opaa.ewerlin.com` noch
  nicht; das ist Voraussetzung für den Korpus-Rollout.
- `docs/AGENT-ORGANIZATION.md` — der QA Engineer ist Eigentümer der RAG-Evaluierung im laufenden
  Betrieb; dieses Feature liefert ihm das Werkzeug.

---

## Festlegungen

Vom Maintainer entschieden:

| # | Festlegung |
|---|---|
| 1 | **Phase-1-Quelle:** `jrtec/Superheroes` (CC0-1.0). Der FiveThirtyEight-Datensatz entfällt. |
| 2 | **Multi-Tenancy:** ein gemeinsamer Index, Domänentrennung über Dateinamen-Präfix. Die Workspace-Variante wartet auf #115/#117, nicht auf Epic #198. |
| 3 | **Demo-Hosting:** die bestehende Instanz `opaa.ewerlin.com`, mit ihrer bestehenden Ollama-Konfiguration (`phi3:mini`). Kein kommerzielles Modell. |
| 4 | **Korpus-Ablage:** Hauptrepository mit SHA-256-Manifest; Neubewertung bei der Ausweitung auf vier Domänen. |
| 5 | **ADR-0008** ist akzeptiert. |

Vom Product Manager gesetzt, mangels Rückfrage, weiterhin widerrufbar:

1. Der Regressionstest läuft nächtlich auf `main` und per Label, nicht bei jedem Pull Request.
2. Zielgröße Phase 1: **alle ~1.450 Entitäten des Datensatzes ohne Sampling**, 100 Golden-Queries.
   *Korrigiert:* ursprünglich waren 1.000 aus rund 23.000 gesampelte Entitäten vorgesehen. Der
   neue Datensatz ist kleiner, aber vollständig verwendbar — Sampling entfällt, und damit ein
   Freiheitsgrad, der die Reproduzierbarkeit unnötig belastet hätte. Die Zahl der Golden-Queries
   bleibt bei 100; sie hängt am Fragenbedarf, nicht an der Korpusgröße.
3. Der Korpus-Generator wird als kleines Python-Werkzeug unter `eval/` umgesetzt, nicht in Java.
   Begründung: Sein Ergebnis wird committet, er läuft nie in CI und nie zur Laufzeit; Datenaufbereitung
   aus CSV ist in Python erheblich billiger als im Spring-Boot-Build.
4. Der Korpus wird in englischer Sprache generiert; Mehrsprachigkeit wird über deutsche Anfragen
   auf englischem Korpus getestet, nicht über einen übersetzten Korpus.
5. Die Freitextfelder `history_text` und `powers_text` des Datensatzes werden nicht übernommen.

---

## Offene Fragen / Zukünftige Erweiterungen

Die Fragen zu Lizenz, Demo-Hosting, Korpus-Ablage und Multi-Tenancy sind entschieden und stehen
oben unter [Festlegungen](#festlegungen). Offen bleibt:

1. **Antwortqualität mit `phi3:mini`:** Das Modell ist klein. Für Retrieval-Metriken spielt es
   keine Rolle (der Harness nutzt kein LLM), für den Eindruck der öffentlichen Demo schon. Falls
   die Antworten auf dem Korpus zu schwach ausfallen, ist ein größeres lokales Modell auf der
   Instanz die naheliegende Reaktion — zu bewerten, sobald der Korpus dort liegt.
2. **Ablage bei vier Domänen:** Prüfpunkt in der Ausweitung — bleibt das Repository auch bei
   10–20 MB die richtige Ablage?
3. **Zurückgestellt:** Generationsmetriken (`RelevancyEvaluator`, `FactCheckingEvaluator`),
   RAGAS-Sidecar, Paired-Bootstrap-Vergleichsläufe, ein eigener deutscher Benchmark. Alles bereits
   in `discussion-rag-evaluation.md` beschrieben; kommt in Phase 4.
4. **Zurückgestellt:** Ein Nutzer-Feedback-Kanal in der Demo („war das hilfreich?") wäre eine
   billige Quelle für echte Anfragen und damit für ein besseres Golden Dataset. Eigenes Feature.

---

## Erfolgs-Metriken

- Eine Pipeline-Änderung, die das Retrieval verschlechtert, wird von CI erkannt, ohne dass ein
  Mensch danach sucht.
- Die Frage „ist Konfiguration A besser als B?" wird mit einer Zahl und einem Verfahren
  beantwortet, nicht mit einer Meinung.
- Ein Interessent kann OPAA ohne Installation ausprobieren und bekommt auf eine Anfrage an den
  Demo-Korpus eine belegte Antwort mit Quellenangabe.
