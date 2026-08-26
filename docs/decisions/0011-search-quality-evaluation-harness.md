# ADR-0011: Aufbau der Suchqualitäts-Evaluierung und Ablage des Testkorpus

## Status

Akzeptiert — ergänzt um den [Nachtrag vom 2026-08-02](#nachtrag-korrigierte-tatsachenlage-zur-demo-instanz)
(Tatsachenkorrektur; die Entscheidung selbst bleibt unverändert).

## Kontext

OPAA kann heute nicht beziffern, ob eine Änderung an Chunking, Embedding-Modell, `top-k` oder
Ähnlichkeitsschwelle das Retrieval verbessert oder verschlechtert. `docs/discussions/discussion-rag-evaluation.md`
hat geklärt **was** gemessen werden soll (Hit Rate, MRR, nDCG, Golden Dataset, gepaarte Vergleiche);
offen ist **wie** das dauerhaft im Projekt verankert wird. Die Feature-Spezifikation
`docs/features/search-quality-evaluation.md` beschreibt das Vorhaben fachlich.

Drei Punkte darin sind strukturell und legen das Projekt langfristig fest:

1. **Wo liegt der Testkorpus?** Ein Regressionstest ist nur aussagekräftig, wenn der Korpus
   eingefroren ist. Repository, Git LFS und Release-Artefakt haben unterschiedliche Folgen für
   Reproduzierbarkeit, CI und Beitragende.
2. **Woraus besteht der Evaluierungs-Harness?** Spring AI bringt Evaluatoren mit, die
   umfassenderen Metriken existieren nur in Python. Ein zweiter Toolchain im Projekt ist eine
   dauerhafte Wartungslast.
3. **Welches Einbettungsmodell läuft in CI?** Davon hängen Kosten, Laufzeit und — entscheidend —
   ob die Baseline über die Zeit stabil bleibt.

## Entscheidung

**1. Der generierte Korpus liegt als reguläre Dateien im Hauptrepository** unter `eval/corpus/<domäne>/`,
zusammen mit einer `MANIFEST.sha256` über alle generierten Dateien. Kein Git LFS. Die Rohquelle
wird als Snapshot mit Quell-URL, Abrufdatum und SHA-256 dokumentiert, aber nicht committet. Wächst
der Gesamtkorpus über rund 25 MB, wird pro Domäne auf ein GitHub-Release-Artefakt gewechselt,
wobei das Manifest im Repository verbleibt. Diese Grenze wird bei der Ausweitung auf vier Domänen
erneut bewertet.

**2. Der Korpus-Generator ist ein eigenständiges Python-Werkzeug** unter `eval/generator/`, kein
Bestandteil des Gradle-Builds. Er ist deterministisch (stabile Sortierung, fixer Seed wo Zufall
im Spiel ist, byte-identischer Output) und wird nur bei bewussten Korpus-Änderungen ausgeführt —
nie in CI und nie zur Laufzeit.

**2a. Nur CC0- oder CC-BY-Quellen** kommen in den verbreiteten Korpus; CC BY-SA und
non-commercial-Lizenzen sind ausgeschlossen. Übernommen werden ausschließlich strukturierte
Faktenfelder; der Fließtext der Entitäts-Dokumente wird vom Generator selbst formuliert. Das hält
die Provenienz sauber **und** die Dokumentgröße so klein, dass eine Entität genau einem Chunk
entspricht — die Voraussetzung für eindeutige Ground Truth.

**3. Der Retrieval-Harness ist Java-nativ**: ein JUnit-Integrationstest im Backend, der Testcontainers
(pgvector) verwendet, den Korpus über die produktive Indexierungs-Pipeline einliest und
`VectorStore.similaritySearch` direkt abfragt. Kein LLM ist beteiligt. Ein Python-Sidecar (RAGAS)
wird **nicht** eingeführt, solange nur Retrieval-Metriken gemessen werden; er bleibt eine Option für
Generationsmetriken in einer späteren Phase.

**4. Das Einbettungsmodell in CI ist Ollama mit `nomic-embed-text`**, festgenagelt auf eine
Modellversion, als Testcontainer. OpenAI-Embeddings sind ein optionaler zusätzlicher Lauf für
Vergleichszwecke, nie die Grundlage der Baseline.

**5. Der Regressionsjob läuft nicht bei jedem Pull Request**, sondern nächtlich auf `main`, manuell
auslösbar und per Label an einem PR. Er schlägt fehl, wenn eine Primärmetrik eine harte Untergrenze
unterschreitet oder um mehr als eine definierte Toleranz unter die committete Baseline fällt.

**6. Das Golden Dataset liegt als versioniertes JSON im Repository** und wird wie Code behandelt:
Änderungen sind reviewpflichtige Commits mit begründeter Baseline-Aktualisierung.

## Konsequenzen

**Einfacher:**

- Der Regressionstest läuft ohne Netzzugriff auf Fremdquellen und ohne Secrets — auch in Forks.
- Korpus-Änderungen sind im Diff sichtbar und reviewbar; unbeabsichtigtes Wandern des Korpus fällt
  über das Manifest sofort auf.
- Der Harness braucht kein laufendes System, keine Demo-Instanz und kein LLM. Er ist damit
  unabhängig von der Demo-Bereitstellung entwickelbar.
- Die Demo-Ingestion kommt ohne neuen Ingestion-Code aus: derselbe Korpus wird über den
  bestehenden HTTP-Verzeichnis-Konnektor gelesen.

**Schwieriger:**

- Das Repository wächst um den Korpus. Bei vier Domänen ist die Obergrenze erreichbar und der
  Wechsel auf Release-Artefakte wird dann nötig.
- Das Projekt bekommt eine zweite Sprache im Werkzeugkasten (Python für den Generator). Sie ist
  bewusst außerhalb von Build und CI gehalten, bleibt aber Wartungsfläche.
- Ein Ollama-Container in CI kostet Laufzeit (Modell-Pull plus Einbettung des Korpus). Deshalb kein
  Lauf bei jedem Pull Request — Regressionen fallen dadurch mit bis zu einem Tag Verzögerung auf.
- Der pgvector-Index ist fest auf HNSW gesetzt (siehe Issue #77). Approximative Suche kann Metriken
  leicht schwanken lassen; die Toleranz muss das abfedern, oder der Indextyp wird für die
  Evaluierung konfigurierbar gemacht.
- Die Baseline ist an Modellversion **und** Korpus gebunden. Jede Änderung an einem der beiden
  erfordert einen bewussten neuen Baseline-Lauf.

---

## Nachtrag: korrigierte Tatsachenlage zur Demo-Instanz

> **Nachtrag vom 2026-08-02.** Dieser ADR ist bereits akzeptiert und wird deshalb nicht still
> umgeschrieben. Der Abschnitt hält eine Tatsachenkorrektur fest, die nach der Annahme bekannt
> wurde, und bewertet, was sie für die getroffene Entscheidung bedeutet.

### Was falsch war

Die Feature-Spezifikation `docs/features/search-quality-evaluation.md`, Epic #224 und Issue #230
behaupteten, die öffentliche Instanz `opaa.ewerlin.com` laufe mit ihrer „bestehenden
Ollama-Konfiguration (`phi3:mini`)", weshalb „kein Kostenrisiko" bestehe und „kein kommerzielles
Modell" eingeführt werde.

Der Maintainer hat auf Nachfrage klargestellt, dass die Instanz nicht mit dem Ollama-Anwendungsdefault
läuft. Die Fehlannahme entstand aus einer Verwechslung zweier Ebenen — der *Anwendungs-Default* in
`backend/src/main/resources/application.yml` ist `${OPAA_AI_CHAT_PROVIDER:ollama}`, die
*empfohlene Compose-Belegung* in `.env.example` dagegen `OPAA_AI_CHAT_PROVIDER=openai`. Die zweite
Ebene beschreibt den tatsächlichen Betrieb, die erste nur das Verhalten ohne jede Konfiguration.
Ein Folge-Issue klärt diese Zweideutigkeit auch in `docs/handbuch/deployment.md`.

> **Zweite Berichtigung (2026-08-02).** Die daraus abgeleitete Aussage „Die Instanz nutzt
> OpenAI" war ihrerseits ungenau und ist zurückgenommen. Die Betriebsdokumentation des Maintainers
> belegt: **Das Chat-Modell ist `claude-haiku-4-5` von Anthropic**, angebunden über Anthropics
> OpenAI-kompatible Schicht — `OPAA_AI_CHAT_PROVIDER=openai` bezeichnet dort das Protokoll, nicht
> den Anbieter. **Eingebettet wird mit `nomic-embed-text` lokal über Ollama** (768 Dimensionen),
> weil Anthropic keine Embeddings-API anbietet. Diese Aufteilung ist dauerhaft. Der Abschnitt
> „Was sich dennoch ändert" weiter unten ist wegen dieser Korrektur neu gefasst.

Zweite Korrektur derselben Runde, ohne Bezug zu diesem ADR, aber der Vollständigkeit halber: Die
Instanz erhält **keinen anonymen Lesezugriff**; sie bleibt account-gebunden hinter Keycloak. Für
diese Entscheidung wird auf ausdrücklichen Wunsch des Maintainers **kein eigener ADR** angelegt;
sie steht in der Feature-Spezifikation.

### Trägt die Entscheidung noch?

**Ja — vollständig, und ohne Abstriche an einer einzigen der sechs Festlegungen.** Die falsche
Prämisse lag nicht in diesem ADR, sondern in der Demo-Beschreibung der Spezifikation. Im Einzelnen:

- Keine der Entscheidungen 1 bis 6 nimmt auf den Chat-Anbieter der Demo-Instanz Bezug. Der ADR
  hält im Gegenteil ausdrücklich fest, dass der Harness „kein laufendes System, keine Demo-Instanz
  und kein LLM" braucht. Die Demo und der Regressionstest teilen den Korpus, sonst nichts.
- Entscheidung 4 (Ollama als Einbettungsmodell in CI) stützt sich zwar unter anderem auf „kostenlos
  und ohne Secret", trägt aber auch ohne dieses Argument: Ausschlaggebend ist die
  **Baseline-Stabilität**. Ein Anbieter kann ein gehostetes Modell still ändern, womit die Baseline
  ohne Code-Änderung driftet; ein festgenageltes lokales Modell kann das nicht. Dieses Argument ist
  von Kosten unabhängig. Hinzu kommt, dass ein Secret in Forks ohnehin nicht verfügbar ist — auch
  das ist keine Kostenfrage.

Es besteht also **kein Anlass, ADR-0011 neu zu bewerten oder zu ersetzen.**

### Was sich dennoch ändert

> **Neu gefasst am 2026-08-02.** Die ursprüngliche Fassung dieses Abschnitts stützte sich
> auf die inzwischen widerlegte Annahme, die Instanz bette mit `text-embedding-3-small` über OpenAI
> ein. Der daraus gezogene Schluss war falsch und ist **vollständig zurückgenommen**; die
> zurückgenommene Aussage steht unten im Wortlaut, damit niemand sie aus älteren Notizen erneut
> aufgreift.

Zwei Punkte, von denen der erste die Aussagekraft des Harness **stärkt** statt sie zu begrenzen:

- **Der CI-Harness misst dieselbe Einbettung wie die Demo-Instanz.** Beide verwenden
  `nomic-embed-text` über Ollama mit 768 Dimensionen. Ein grüner Regressionslauf sagt damit sehr
  wohl etwas über die Retrieval-Qualität, die ein Besucher auf `opaa.ewerlin.com` erlebt: Der
  gemessene Teil der Pipeline — Chunking, Einbettung, Vektorsuche, Ranking — ist in CI und auf der
  Instanz identisch konfiguriert. Nicht gemessen wird weiterhin die **Generierung**, weil kein LLM
  am Harness beteiligt ist und das Chat-Modell der Instanz (`claude-haiku-4-5` von Anthropic) in CI
  nicht vorkommt. Die Grenze verläuft also zwischen Retrieval und Generierung, nicht zwischen zwei
  Einbettungskonfigurationen.
- Für den Betrieb der Demo — nicht für diesen ADR — bleibt es dabei, dass Kosten aktiv zu begrenzen
  sind: Ausgabenlimit beim Chat-Anbieter und Rate Limiting pro Konto. Neu ist die Einsicht, dass
  **die Indizierung selbst kostenlos ist**, weil lokal eingebettet wird; das Kostenrisiko liegt
  vollständig auf der Anfrageseite. Verankert ist das in der Feature-Spezifikation und in den
  Abnahmekriterien von #230.

**Zurückgenommene Aussage (galt vom 2026-08-02 bis zur Berichtigung desselben Tages):**

> „Der CI-Harness misst eine andere Konfiguration als die Demo-Instanz fährt. Die Baseline entsteht
> mit `nomic-embed-text` über Ollama, die Instanz bettet laut `.env.example` mit
> `text-embedding-3-small` über OpenAI ein. Ein grüner Regressionslauf sagt damit nichts über die
> Trefferqualität, die ein Besucher auf `opaa.ewerlin.com` erlebt." — **Falsch.** Die Angabe stützte
> sich auf `.env.example` statt auf die tatsächliche Belegung der Instanz. Ebenfalls hinfällig ist
> die daran gehängte Empfehlung, den optionalen OpenAI-Vergleichslauf „mindestens einmal gegen die
> Konfiguration der Demo-Instanz" laufen zu lassen: Es gibt keinen Abstand zwischen zwei
> Einbettungsanbietern zu beziffern, weil beide Seiten dasselbe Modell verwenden. Der optionale
> Vergleichslauf aus Entscheidung 4 behält seinen ursprünglichen Zweck — Modellvergleich —, aber
> nicht mehr diese Begründung.
