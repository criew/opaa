# Suchqualität messbar machen: Demo-Korpus und Retrieval-Regression

> **Status: Entwurf — offene Fragen an den Maintainer in [Offene Fragen](#offene-fragen--zukünftige-erweiterungen).**

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
4. **Der Korpus ist eingefroren.** Quelldaten-Snapshot einmalig, Sampling mit fixem Seed,
   SHA-256-Manifest über alle generierten Dateien im Repository. Nie live von einer Fremdquelle
   ziehen.
5. **Demo und Regression laufen entkoppelt.** Die Demo nutzt den bestehenden
   HTTP-Verzeichnis-Konnektor gegen einen statischen Webserver im Compose-Stack. Der
   Regressionstest ist ein JUnit-Integrationstest gegen Testcontainers und braucht die Demo nicht.
6. **Eine Domäne komplett, dann die anderen.** Comichelden wird end-to-end durchgezogen (Generator
   → Golden Dataset → Metriken → CI → Demo). Filme, Reiseziele und Tiere folgen erst danach über
   denselben, dann bewährten Pfad.
7. **Nur CC0 und CC BY.** Der verbreitete Korpus enthält ausschließlich Quellen unter CC0 oder
   CC BY. CC BY-SA wird vermieden, IMDb ist ausgeschlossen.

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
  Query-Filter) sowie #117 (Konnektor-Workspace-Integration). Zudem schlägt Epic #198 vor, das
  Workspace-Modell durch ein Space-Modell zu ersetzen — auf ein Modell im Umbau sollte die Demo
  nicht aufbauen. **Entscheidung: Phase 1 und 2 bauen einen einzelnen, gemeinsamen Index. Die
  Multi-Tenancy-Demonstration wird als eigenes, explizit blockiertes Issue geführt.** Domänen
  werden vorerst über ein Dateinamen-Präfix getrennt (`comic-…`, `movie-…`), was für Auswertung
  und Anzeige ausreicht.
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
| Comichelden | viele numerische und kategoriale Attribute, kaum Prosa — hier bricht reine Vektorsuche | FiveThirtyEight `comic-characters`, CC BY 4.0 (Phase 1) |
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
- **Minderungsmaßnahme zum Comic-Datensatz:** FiveThirtyEight steht unter CC BY 4.0, die Daten
  stammen jedoch ursprünglich aus den Marvel-/DC-Fandom-Wikis, deren Inhalte CC BY-SA sind.
  Übernommen werden deshalb ausschließlich **einzelne Faktenfelder** (Name, Ausrichtung, Geschlecht,
  Augen-/Haarfarbe, Erstauftritt, Anzahl Auftritte, Status). Der Fließtext wird vom Generator aus
  diesen Feldern **selbst formuliert**; kein Wiki-Text wird kopiert. Figurennamen bleiben Marken
  Dritter, die rein beschreibende Nennung in einer nichtkommerziellen Demo ist davon unberührt.
  → Bewertung durch den Maintainer erbeten, siehe Offene Fragen.

### Dokumentenformat

Eine Datei pro Entität, Dateiname als stabiler Slug mit ID:
`comic-marvel-001678_spider-man-peter-parker.md`

```markdown
---
id: comic-marvel-001678
domain: comic-characters
publisher: Marvel
name: Spider-Man (Peter Parker)
alignment: good
sex: male
eye_color: hazel
hair_color: brown
identity: secret
first_appearance: 1962-08
appearances: 4043
status: alive
source: fivethirtyeight/comic-characters
license: CC BY 4.0
---

# Spider-Man (Peter Parker)

Spider-Man (Peter Parker) is a Marvel character with a secret identity. The character is
aligned as good, has hazel eyes and brown hair, first appeared in August 1962 and has been
featured in 4,043 appearances.
```

Wichtig: Tika liefert das Frontmatter als Teil des Fließtexts aus — es wird also mit eingebettet.
Das ist gewollt, weil es Attributbegriffe in den Vektorraum bringt, muss bei der Auswertung aber
bewusst sein.

### Einfrieren des Korpus

Ohne diesen Punkt ist die gesamte Regression wertlos:

1. **Snapshot** der Rohquelle einmalig herunterladen, mit Abrufdatum, Quell-URL und
   SHA-256-Summe der Rohdatei festhalten.
2. **Deterministisches Sampling** mit fixem Seed auf die Zielgröße (Vorschlag: 1.000 Figuren aus
   rund 23.000). Sortierung vor dem Sampling stabil festlegen.
3. **Deterministische Generierung** — gleicher Input, gleicher Seed, byte-identischer Output.
4. **`MANIFEST.sha256`** über alle generierten Dateien im Repository. Der Regressionstest prüft
   das Manifest vor dem Lauf und bricht bei Abweichung ab.
5. Jede Korpus-Änderung ist eine bewusste, reviewte Änderung mit neuem Baseline-Lauf.

### Ablage — Optionen

| Option | Für | Gegen |
|---|---|---|
| 1. Generierte Dateien im Hauptrepo unter `eval/corpus/` | Regressionstest läuft ohne Netz; Diffs sind reviewbar; kein Zusatz-Tooling | Repo wächst (bei 1.000 Dateien × ~1,5 KB ≈ 1,5 MB — vertretbar); bei vier Domänen ggf. 10–20 MB |
| 2. Git LFS | Repo-Historie bleibt schlank | Zusätzliches Tooling für alle Beitragenden, LFS-Kontingent, CI-Komplexität |
| 3. GitHub-Release-Artefakt, im Test heruntergeladen | Repo bleibt sauber | CI braucht Netz; Artefakt kann ersetzt werden → Einfrieren nur noch durch Konvention gesichert |

**Empfehlung: Option 1**, solange der Gesamtkorpus unter etwa 25 MB bleibt. Wird diese Grenze bei
der Ausweitung auf vier Domänen überschritten, wird pro Domäne auf Option 3 gewechselt, mit dem
SHA-256-Manifest weiterhin im Repo. Diese Entscheidung gehört in den ADR.

---

## Golden Dataset

Struktur pro Testfall nach `discussion-rag-evaluation.md` §5.3, ergänzt um die Domäne:

```json
{
  "id": "comic-q-042",
  "domain": "comic-characters",
  "query": "Welche Augenfarbe hat Spider-Man?",
  "expected_documents": ["comic-marvel-001678_spider-man-peter-parker.md"],
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
| `multi_attribute_filter` | „Welche {publisher}-Figuren sind {alignment} und haben {hair_color} Haare?" | alle passenden Dateien | Recall@k — der Fall, an dem Vektorsuche bricht |
| `numeric_range` | „Welche Figuren hatten mehr als {n} Auftritte?" | alle passenden Dateien | Recall@k |
| `crosslingual` | deutsche Frage auf englischem Korpus | wie oben | Robustheit des Embeddings |

Filter-Fragen werden nur aufgenommen, wenn die Treffermenge klein genug ist (Vorschlag: 2–15
Dokumente) — sonst ist die Metrik nicht aussagekräftig.

### Kuratierung

Vollautomatisch generierte Fälle sind ein „Silver Dataset". Vor der Aufnahme in die Baseline wird
eine Stichprobe manuell geprüft: keine mehrdeutigen Fragen, keine Fragen mit leerer oder
allumfassender Treffermenge, keine Duplikate. Zielgröße Phase 1: **100 kuratierte Fälle**,
verteilt über alle fünf Kategorien und über DE und EN.

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
| 1. Ollama-Container mit `nomic-embed-text` | kostenlos, kein Secret, entspricht der Standardkonfiguration von OPAA, über die Modellversion reproduzierbar | Modell-Pull (~275 MB) und Einbettung von 1.000 Dokumenten kosten Laufzeit |
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

### Betriebliche Anforderungen an eine öffentlich erreichbare Instanz

- **Anonymer Lesezugriff** auf die Suche, keine Schreib- oder Admin-Endpunkte von außen. Der
  Indizierungs-Endpunkt ist bereits auf `SYSTEM_ADMIN` beschränkt und darf so bleiben.
- **Rate Limiting** ist vorhanden (`opaa.rate-limit`) und muss für die Demo scharf gestellt sein —
  jede Anfrage kostet LLM-Tokens.
- **Attributionshinweis** sichtbar in der Oberfläche (CC BY erfordert das).
- **Hinweis auf den Demo-Charakter**: synthetisch formulierte Texte, keine Faktenautorität.
- Hosting, Domain und Kostenrahmen entscheidet der Maintainer.

---

## Phasen

| Phase | Inhalt | Ergebnis |
|---|---|---|
| 1 | Comichelden: Generator, Golden Dataset, Metrik-Harness, CI-Job | Suchqualität ist eine Zahl, Regressionen fallen auf |
| 2 | Demo-Ingestion im Compose-Stack, öffentliche Instanz, E2E-Szenarien | OPAA ist vorführbar |
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
- Issues #115 und #117 sowie Epic #198 — Voraussetzung für die Multi-Tenancy-Demonstration.
- `docs/AGENT-ORGANIZATION.md` — der QA Engineer ist Eigentümer der RAG-Evaluierung im laufenden
  Betrieb; dieses Feature liefert ihm das Werkzeug.

---

## Getroffene Annahmen

Diese Annahmen wurden mangels Rückfragemöglichkeit getroffen und sind widerrufbar:

1. Der Regressionstest läuft nächtlich auf `main` und per Label, nicht bei jedem Pull Request.
2. Zielgröße Phase 1: 1.000 Entitäten, 100 Golden-Queries.
3. Der Korpus-Generator wird als kleines Python-Werkzeug unter `eval/` umgesetzt, nicht in Java.
   Begründung: Sein Ergebnis wird committet, er läuft nie in CI und nie zur Laufzeit; Datenaufbereitung
   aus CSV ist in Python erheblich billiger als im Spring-Boot-Build.
4. Der generierte Korpus wird im Hauptrepository abgelegt (Option 1 oben).
5. Die Demo verwendet einen einzelnen gemeinsamen Index ohne Workspace-Trennung.
6. Der Korpus wird in englischer Sprache generiert; Mehrsprachigkeit wird über deutsche Anfragen
   auf englischem Korpus getestet, nicht über einen übersetzten Korpus.

---

## Offene Fragen / Zukünftige Erweiterungen

1. **Lizenzbewertung Comic-Korpus:** Genügt die Beschränkung auf Faktenfelder plus selbst
   formulierten Fließtext, um die CC-BY-SA-Herkunft der Fandom-Ursprungsdaten auszuschließen? Falls
   der Maintainer das Risiko nicht tragen will, ist die Alternativquelle
   `jrtec/Superheroes` (CC0) — dann fällt die Attributionspflicht ganz weg.
2. **Hosting der Demo:** Wo läuft sie, mit welchem LLM, mit welchem Kostenrahmen? Ein
   öffentliches Suchfeld gegen ein kommerzielles Modell ist ein offenes Kostenrisiko; eine
   selbstgehostete Ollama-Instanz ist langsamer, passt aber zur Positionierung von OPAA.
3. **Ablage bei vier Domänen:** Bleibt Option 1 (Repo) auch bei 10–20 MB akzeptabel?
4. **Multi-Tenancy in der Demo:** Soll auf #115/#117 gewartet oder auf das Space-Modell aus #198
   gesetzt werden? Solange das offen ist, bleibt die Demo einindexig.
5. **Zurückgestellt:** Generationsmetriken (`RelevancyEvaluator`, `FactCheckingEvaluator`),
   RAGAS-Sidecar, Paired-Bootstrap-Vergleichsläufe, ein eigener deutscher Benchmark. Alles bereits
   in `discussion-rag-evaluation.md` beschrieben; kommt in Phase 4.
6. **Zurückgestellt:** Ein Nutzer-Feedback-Kanal in der Demo („war das hilfreich?") wäre eine
   billige Quelle für echte Anfragen und damit für ein besseres Golden Dataset. Eigenes Feature.

---

## Erfolgs-Metriken

- Eine Pipeline-Änderung, die das Retrieval verschlechtert, wird von CI erkannt, ohne dass ein
  Mensch danach sucht.
- Die Frage „ist Konfiguration A besser als B?" wird mit einer Zahl und einem Verfahren
  beantwortet, nicht mit einer Meinung.
- Ein Interessent kann OPAA ohne Installation ausprobieren und bekommt auf eine Anfrage an den
  Demo-Korpus eine belegte Antwort mit Quellenangabe.
