# Deployment

## Deployment aus vorgebauten Images (GHCR)

Für Zielsysteme, auf denen nicht aus dem Quellcode gebaut werden soll, veröffentlicht CI bei jedem Push auf `main` fertige Container-Images:

| Image | Tags |
|-------|------|
| `ghcr.io/criew/opaa-backend` | `main`, `sha-<commit>` |
| `ghcr.io/criew/opaa-frontend` | `main`, `sha-<commit>` |

Auf dem Zielsystem wird kein Repository-Checkout benötigt — es genügt eine `docker-compose.yml`, die `image:` statt `build:` verwendet:

```yaml
services:
  backend:
    image: ghcr.io/criew/opaa-backend:main
  frontend:
    image: ghcr.io/criew/opaa-frontend:main
```

Aktualisieren auf den neuesten `main`-Stand:

```bash
docker compose pull && docker compose up -d
```

`main` folgt dem jeweils letzten Stand; für reproduzierbare Deployments stattdessen einen `sha-<commit>`-Tag pinnen.

Jedes Image trägt eine SBOM- und eine Provenance-Attestierung, die beim Bauen erzeugt werden; die
SBOM deckt auch die Betriebssystempakete des Basis-Images ab. Abruf:

```bash
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json .SBOM }}'
docker buildx imagetools inspect ghcr.io/criew/opaa-backend:main --format '{{ json .Provenance }}'
```

(für das Frontend entsprechend mit `opaa-frontend`). Zusätzlich legt die CI bei jedem Push auf
`main` je eine CycloneDX-SBOM der ausgelieferten Laufzeit-Abhängigkeiten von Backend und Frontend
als Workflow-Artefakte `sbom-backend` und `sbom-frontend` ab (90 Tage Aufbewahrung, ohne Test- und
Build-Werkzeuge).

## Aktualisierung auf einen neuen `main`-Stand

Die CI baut bei jedem Push auf `main` neue `ghcr.io/criew/opaa-backend`- und `ghcr.io/criew/opaa-frontend`-Images und veröffentlicht sie mit den Tags `main` und `sha-<commit>` in der GHCR-Registry (siehe [Deployment aus vorgebauten Images](#deployment-aus-vorgebauten-images-ghcr) oben).

Ein Deployment-Skript, das genau das tut — die aktuellen Images ziehen und den Stack auf den neuen
Stand bringen —, ist eine naheliegende Automatisierung für eine erreichbare Instanz; die öffentliche
Demo-Instanz des Projekts läuft so, mit einem täglichen Cron-Aufruf und rotiertem Protokoll.
Ein solcher Wrapper sollte einen Schalter, der zusätzlich die Volumes verwirft, klar von der
regulären Aktualisierung trennen — damit ist die Datenbank und mit ihr der gesamte Index weg, das ist
also kein Aktualisierungs-, sondern ein Neuaufsetzschritt; danach ist zwingend eine vollständige
Neuindizierung nötig.

Ohne ein solches Skript entspricht der Ablauf diesen Schritten, ausgeführt im Verzeichnis mit der `docker-compose.yml`:

```bash
docker compose pull          # neue Images aus GHCR holen
docker compose up -d         # Container mit den neuen Images neu erstellen
docker image prune -f        # optional: verdrängte Images aufräumen
```

`docker compose up -d` ersetzt nur die Container, deren Image sich geändert hat; die übrigen laufen weiter. Ein `docker compose restart` genügt **nicht** — es verwendet den vorhandenen Container samt altem Image erneut.

Zustand danach prüfen:

```bash
docker compose ps
docker compose logs -f backend
```

> **Service- statt Containernamen verwenden.** Alle Befehle oben sprechen den **Servicenamen** aus der Compose-Datei an (`backend`, `frontend`, `postgres`), nicht einen Containernamen. Das ist der robustere Weg: Wie die Container tatsächlich heißen, hängt davon ab, ob die jeweilige Compose-Datei `container_name` setzt und wie das Compose-Projekt heißt — beides unterscheidet sich zwischen der Testinstanz, lokalen Entwicklungsstacks und der E2E-Suite. `docker compose logs backend` funktioniert in allen dreien, `docker logs <name>` nur in einem.

#### Was ein Update mit dem Index macht

Kurz: Ein Update über `docker compose pull` + `docker compose up -d` **gefährdet den Index nicht** und macht **keine Neuindizierung erforderlich**. Im Einzelnen:

- **Der Korpus** liegt in einem Bind-Mount auf dem Host und ist von Container-Neustarts unberührt.
- **Der Index** liegt in PostgreSQL, dessen Daten im benannten Volume `opaa-postgres-data` liegen. Das Volume überlebt das Neuerstellen der Container; nur `docker compose down -v` löscht es.
- **Liquibase** wendet beim Backend-Start ausschließlich noch nicht angewendete Changesets vorwärts an. Mit einer Ausnahme (siehe unten) löscht keines der Changesets Dokument- oder Vektordaten — die `dropTable`-Anweisungen in `db/changelog/changes/` stehen, mit Ausnahme einer entfallenen Hilfstabelle des Volltextpfads, ausnahmslos in `rollback`-Blöcken und laufen im Normalbetrieb nie.
- **Die Vektortabelle** wird nicht von Liquibase, sondern von Spring AI selbst angelegt (`spring.ai.vectorstore.pgvector.initialize-schema: true`). Sie wird nur erzeugt, wenn sie fehlt, und bei einem Update nicht verändert.

> **Der Vektorspeicher ist nicht wählbar.** OPAA speichert Vektoren in PostgreSQL mit pgvector; das ist der einzige unterstützte Vektorspeicher. Der Zugriff läuft zwar über eine portable Schnittstelle von Spring AI, ein Wechsel wird aber nicht unterstützt, nicht geprüft und nicht dokumentiert. Metadatenbestand und Vektorindex liegen damit in derselben Datenbank: ein Sicherungslauf, eine Wiederherstellung, ein Nachweispfad. Bekannte Grenze: pgvector stößt bei sehr großen Beständen im Bereich von Millionen Vektoren an Grenzen.

> **Ausnahme: die Migration vom 19.08.2026, die die frühere System-Bibliothek entfernt** (seit der Zusammenfassung der Changesets zur Baseline nicht mehr als eigene Datei sichtbar). Diese Migration löscht bewusst Daten — die früher automatisch angelegte, nur für System-Admins lesbare System-Bibliothek samt ihrer Dokumente, Vektorspeicher-Chunks, Indizierungsaufträge und Grants. Es ist die einzige datenvernichtende Migration; ihr Rollback ist bewusst ein No-op (die entfernten Zeilen ließen sich nicht von danach regulär geschriebenen unterscheiden). **Vor dem Update auf einen Stand mit dieser Migration einen Datenbank-Dump ziehen**, wer den Inhalt der System-Bibliothek noch braucht. Dateien, die ein Dokument der System-Bibliothek einst unter `opaa.upload.storage-path` abgelegt hatte, räumt die Migration nicht mit auf — nur die Datenbankzeilen verschwinden, verwaiste Dateien bleiben auf der Platte liegen und müssen bei Bedarf von Hand entfernt werden.

Eine Neuindizierung wird erst durch Änderungen nötig, die nichts mit dem Image-Update zu tun haben:

| Auslöser | Folge |
|---|---|
| Embedding-Modell oder -Anbieter gewechselt | Bestehende Vektoren stammen aus einem anderen Modell und sind nicht mehr vergleichbar — vollständige Neuindizierung nötig. **`OPAA_PGVECTOR_DIMENSIONS` muss mitgezogen werden**, sonst passt die Vektorbreite nicht zum neuen Modell |
| `OPAA_PGVECTOR_DIMENSIONS` geändert | Passt nicht mehr zur bestehenden Vektortabelle — die Datenbank muss zurückgesetzt und der Korpus neu indiziert werden. Enthält `vector_store` bereits Vektoren mit abweichender Dimension, bricht der Start selbst mit einer klaren Fehlermeldung ab (`io.opaa.config.PgVectorDimensionsGuard`), statt erst beim ersten Indizierungslauf kryptisch zu scheitern |
| `docker compose down -v` bzw. das Deployment-Skript mit zurücksetzendem Schalter ausgeführt | Datenbank inklusive Index ist weg — vollständige Neuindizierung nötig |
| PostgreSQL-Hauptversion gewechselt (Image-Tag von `pg18` auf eine höhere Version) | Das Datenverzeichnis im Volume ist nicht aufwärtskompatibel; ein solcher Wechsel ist ein eigener Migrationsvorgang, kein `docker compose pull` |
| Update von einer Version **vom 22.08.2026 bis zum 23.08.2026** (jeder Stand, der Vektoren über die OpenAI-kompatible Schicht indiziert hat, bevor die Metadaten-Kontamination der Einbettung am 23.08.2026 behoben wurde) | In dieser Zeitspanne indizierte Vektoren tragen fünf Zeilen Metadaten-Rauschen (`document_id`/`chunk_index`/`file_name`/`library_id`/`organization_id`) vor dem eigentlichen Text mit eingebettet (Kosinus-Ähnlichkeit zum sauberen Vektor: rund 0.42) — **betroffene Bibliotheken müssen neu indiziert werden**, sonst liegen kontaminierte und saubere Vektoren nebeneinander im selben Suchraum. Siehe die eigene Anleitung dazu direkt unten |
| Update auf einen Stand **ab dem 27.08.2026** (Contextual Chunking: ein aus dem Dateinamen abgeleiteter Titel wird jedem Chunk eines mehrchunkigen Dokuments als Kontext-Präfix vorangestellt, nur für die Einbettung) | Zuvor eingebettete Vektoren tragen kein Präfix und ranken inkonsistent gegen neu eingebettete Vektoren mit Präfix im selben Suchraum — **jede Bibliothek muss vollständig neu indiziert werden**. Siehe die eigene Anleitung dazu direkt unten |
| Update auf einen Stand **ab dem 01.09.2026** (Ingestion-Pipelines je Dokumenttyp: eigene Zuschnitte für `.pdf`/`.docx`/`.pptx`/`.xlsx`/`.csv`/`.ods`/`.html`/`.eml`/`.msg` statt des einheitlichen Tika-Wegs, ab dem 02.09.2026 auch `.odt`/`.odp`) | Bestand, der vor der jeweiligen Pipeline-Registrierung indiziert wurde, liegt mit dem alten, generischen Zuschnitt im selben Suchraum neben neu erzeugten Chunks der spezialisierten Pipeline — **je Pipeline gezielt nachziehen**, kein Bibliotheks-Reset nötig. Siehe die eigene Anleitung dazu direkt unten |

Wer ein Compose-Profil mit fest gekoppeltem Embedding-Modell und `OPAA_PGVECTOR_DIMENSIONS` betreibt
(so läuft die öffentliche Demo-Instanz mit `nomic-embed-text` und `768`), muss bei einem Wechsel des
Embedding-Modells beide Werte gemeinsam ändern und die Datenbank zurücksetzen. Ein Wechsel des
**Chat**-Modells berührt den Index dagegen nicht, sofern Chat und Einbettung getrennte Anbieter sind.

> **Falle bei einer Neuindizierung:** OPAA überspringt Dateien, deren SHA-256-Prüfsumme unverändert ist **und** deren Datensatz in der Tabelle `documents` den Status `INDEXED` trägt. Wird die Vektortabelle geleert, ohne auch `documents` zu bereinigen, meldet ein neuer Lauf lauter übersprungene Dateien und der Index bleibt leer. Beide Tabellen liegen in derselben Datenbank — wer den Index verwirft, muss `documents` mitverwerfen.

> **Neuindizierung nach der Behebung der Metadaten-Kontamination (23.08.2026):** Der Fix wirkt nur vorwärts — er ändert, was ab dem Update neu eingebettet wird, nicht die bereits gespeicherten Vektoren. Zwischen der Umstellung auf die OpenAI-kompatible Einbettung (22.08.2026) und diesem Fix indizierte Chunks bleiben kontaminiert, bis sie explizit neu indiziert werden, und liegen bis dahin unbemerkt neben sauberen Vektoren im selben Suchraum — eine gemischte Suchqualität, kein Fehlschlag, der auffällt. Betroffen ist jede Bibliothek, die in diesem Zeitfenster (mindestens einmal) indiziert wurde, unabhängig vom Quellentyp. Eine reine `docker compose pull`/`up -d`-Aktualisierung erkennt das nicht automatisch — wer aus dieser Zeitspanne aktualisiert, muss selbst neu indizieren. Zwei bekannte Fallen dabei, zusätzlich zur SHA-256-/`INDEXED`-Falle oben:
>
> - **`FILESYSTEM`/`HTTP_DIRECTORY`:** Die SHA-256-Falle oben gilt unverändert — unveränderte Dateien mit Status `INDEXED` werden übersprungen, auch wenn ihr gespeicherter Vektor kontaminiert ist. Ohne einen Rücksetzschritt merkt ein neuer Lauf gar nichts an.
> - **`RSS_FEED`:** Zusätzlich zur SHA-256-Falle greift hier der `rss_feed_state`-ETag/`Last-Modified` (siehe [RSS-Feed](konnektor-rss-feed.md), Abschnitt „Änderungserkennung"): Meldet der Feed „unverändert" (HTTP 304), endet der Lauf nach der ersten Anfrage, ohne dass auch nur ein Eintrag erneut betrachtet wird — unabhängig vom Zustand der bereits gespeicherten Vektoren.
>
> Für einen echten Neuaufbau einer betroffenen Bibliothek müssen **`documents`, die zugehörigen `vector_store`-Zeilen und (nur bei `RSS_FEED`) `rss_feed_state`** gemeinsam zurückgesetzt werden, gezielt für die betroffene `library_id` — nicht die ganze Datenbank:
>
> ```sql
> DELETE FROM vector_store WHERE metadata->>'library_id' = '<library-id>';
> DELETE FROM documents WHERE library_id = '<library-id>';
> DELETE FROM rss_feed_state WHERE library_id = '<library-id>';  -- nur bei RSS_FEED-Bibliotheken
> ```
>
> Anschließend die Bibliothek regulär neu indizieren (`POST /api/v1/libraries/{libraryId}/indexing` bzw. der entsprechende Button in der Oberfläche). Es gibt keinen dedizierten „Nur-neu-einbetten"-Schalter, der die drei Tabellen automatisch zurücksetzt — dieser manuelle Weg ist der einzige. Betroffen ist jede Bibliothek, die in diesem Zeitfenster mindestens einmal indiziert wurde.

> **Neuindizierung nach Einführung des Contextual Chunking (27.08.2026):** Anders als beim Fix oben
> ist das kein Fehler, sondern
> eine Erweiterung dessen, was in die Einbettung eingeht — ein aus dem Dateinamen abgeleiteter,
> bereinigter Titel als Kontext-Präfix, aber nur für Chunks eines Dokuments, das beim Chunking in 2
> oder mehr Chunks zerfiel (`FileProcessingService#storeChunks`; ein einchunkiges Dokument bleibt
> bit-identisch zum vorherigen Stand). Der gespeicherte Chunk-Text (`content` in `vector_store`) und
> damit jedes Zitat bleiben in beiden Fällen unverändert, nur der Vektor selbst ändert sich, und auch
> nur für mehrchunkige Dokumente. **Das ist keine reine Verbesserung, sondern verschiebt auch das
> relative Ranking innerhalb einer Bibliothek:** Ein einchunkiges Dokument bekommt selbst nie ein
> Kontext-Präfix und kann deshalb nach dem Reindex relativ schlechter ranken als ein thematisch
> verwandtes, jetzt präfixiertes mehrchunkiges Dokument in derselben Bibliothek. Vor und nach diesem
> Stand eingebettete Chunks eines mehrchunkigen Dokuments liegen deshalb im
> selben pgvector-Suchraum nebeneinander, ranken aber inkonsistent gegeneinander (ein Alt-Chunk
> konkurriert ohne das Präfix-Signal gegen Neu-Chunks, die es haben) — ein **vollständiger Reindex
> jeder Bibliothek** ist nach diesem Update erforderlich, unabhängig vom Quellentyp und unabhängig
> davon, ob eine Datei sich inhaltlich geändert hat (die Split-Entscheidung selbst lässt sich vorab
> nicht ohne einen Parse-/Chunking-Lauf feststellen). Dieselbe SHA-256-/`INDEXED`-Falle wie oben
> gilt: Ein unveränderter Datensatz wird ohne einen Rücksetzschritt übersprungen, obwohl sein Vektor
> (bei einem mehrchunkigen Dokument) kein Präfix trägt.
>
> ```sql
> DELETE FROM vector_store WHERE metadata->>'library_id' = '<library-id>';
> DELETE FROM documents WHERE library_id = '<library-id>';
> DELETE FROM rss_feed_state WHERE library_id = '<library-id>';  -- nur bei RSS_FEED-Bibliotheken
> ```
>
> Anschließend die Bibliothek regulär neu indizieren. Für eine Demo-/Testinstanz mit überschaubarem
> Korpus ist ein vollständiger Re-Seed (Datenbank-Volume verwerfen, Korpus neu einspielen) statt
> eines gezielten Rücksetzens pro Bibliothek ebenfalls zulässig und oft einfacher.

> **Neuindizierung nach Einführung der Ingestion-Pipelines je Dokumenttyp (01./02.09.2026).** Vor
> diesem Update lief jedes
> zugelassene Format über denselben Weg (Tika-Extraktion, Token-Chunking). Seitdem übernehmen eigene
> Pipelines den Zuschnitt für `.pdf` (`pdf`), `.docx` (`docx`), `.pptx` (`pptx`),
> `.xlsx`/`.csv`/`.ods` (`tabular`), `.html` (`html`), `.eml`/`.msg` (`email`) sowie `.odt` (`odt`)
> und `.odp` (`odp`) — jede mit ihrer eigenen `pipeline_id`/`pipeline_version` am Chunk (siehe
> [Indexierung](indexierung.md), Abschnitt zu Pipeline-Versionen und Nachzug).
> `.md`, `.txt`, `.doc` sowie jedes Format ohne eigene Pipeline laufen unverändert über
> `TikaFallbackPipeline` weiter — für sie ändert sich am bestehenden Bestand nichts. Für `.odt`/`.odp`
> ändert sich der Bestand dagegen genauso wie für die anderen sechs Formate: Wer diese Anleitung
> abarbeitet, muss `odt`/`odp` als eigene Aufrufe mitnehmen (siehe unten), sonst bleiben bestehende
> ODT-/ODP-Dokumente dauerhaft auf dem Fallback-Zuschnitt.
>
> Anders als bei den beiden Fällen oben ist kein bibliotheksweites Rücksetzen von `documents`/`vector_store`
> nötig: Die selektive Neuindizierung wählt gezielt über die Chunk-Metadaten aus, welcher Bestand vom
> alten, generischen Zuschnitt betroffen ist. **Der eigentliche Migrationsschritt ist ein neunter,
> vorangestellter Aufruf mit `pipelineId: "tika-fallback"`**, wiederholt bis `done: true` in der
> Antwort steht:
>
> ```bash
> curl -X POST http://localhost:8081/api/v1/admin/indexing/pipeline-reindex \
>   -H "Content-Type: application/json" \
>   -H "Authorization: Bearer <token>" \
>   -d '{"pipelineId": "tika-fallback", "belowVersion": 1, "batchSize": 10}'
> ```
>
> Dieser Aufruf wählt jeden Chunk ohne `pipeline_id`/`pipeline_version` aus (Altbestand vor
> Einführung der Pipeline-Metadaten, siehe unten) **und** jeden Chunk, den `tika-fallback` selbst in
> Version 0 erzeugt hat — beides
> wird beim Neuerzeugen über die Registry an die heute zuständige Pipeline geroutet, nicht mehr an
> `TikaFallbackPipeline` zurück. Erst danach folgen, ebenfalls je wiederholt bis `done: true`, die
> acht formatbezogenen Aufrufe (`pdf`, `docx`, `pptx`, `tabular`, `html`, `email`, `odt`, `odp`) — sie
> decken nur noch den Zwischenstand ab, also Chunks, die bereits mit
> `pipeline_id`/`pipeline_version` geschrieben wurden, aber noch auf einer älteren Version ihrer
> heutigen Pipeline liegen:
>
> ```bash
> curl -X POST http://localhost:8081/api/v1/admin/indexing/pipeline-reindex \
>   -H "Content-Type: application/json" \
>   -H "Authorization: Bearer <token>" \
>   -d '{"pipelineId": "pdf", "belowVersion": 1, "batchSize": 10}'
> ```
>
> Fortschritt und Füllstand je Bibliothek (Chunks insgesamt / auf aktueller Version / darunter) sind
> jederzeit über `GET /api/v1/admin/indexing/pipeline-versions` abfragbar (`SYSTEM_ADMIN`, auf die
> eigene Organisation begrenzt). Beide Endpunkte sind authentifiziert; ein Aufruf ohne gültiges Token
> scheitert mit 401, genauso wie der Indizierungs-Endpunkt oben.
>
> **Altbestand vor Einführung der Pipeline-Metadaten (01.09.2026)** trägt weder `pipeline_id`
> noch `pipeline_version` und zählt dafür als `tika-fallback` Version 0 — er ist **ausschließlich**
> über den `tika-fallback`-Aufruf oben erfasst, nicht über die acht formatbezogenen Aufrufe: Die
> Auswahlabfrage der selektiven Neuindizierung matcht einen Chunk ohne `pipeline_id` nur gegen
> `pipelineId: "tika-fallback"` (`COALESCE` auf den Legacy-Wert), niemals gegen eine der acht
> übrigen Kennungen. Wer den `tika-fallback`-Aufruf auslässt, bekommt bei den acht formatbezogenen
> Aufrufen sofort `done: true` und hat den gesamten älteren Bestand — bei jeder heutigen
> Installation der Normalfall — nicht angefasst.

## Sicherheitshinweis: `POST /api/v1/libraries/{libraryId}/indexing` ist von außen erreichbar

Bei jeder über einen Reverse-Proxy erreichbaren Installation ist dieser Endpunkt aus dem Internet
erreichbar. Dass alle Container-Ports nur auf `127.0.0.1` binden (siehe „Härtung für erreichbare
Deployments" unten), ändert daran nichts — der nach außen gerichtete Proxy reicht die API-Pfade
durch, und der Indizierungspfad ist davon nicht ausgenommen. Die Bindung auf `127.0.0.1` verhindert
lediglich, dass jemand die Container unter Umgehung des Proxys direkt anspricht.

Er ist durch Keycloak authentifiziert und verlangt mindestens die Rolle `EDITOR` auf der Zielbibliothek (`DocumentIndexingService#requireEditableLibrary`); eine zusätzliche `SYSTEM_ADMIN`-Schranke gibt es bewusst nicht — ein Anstoß-Knopf, den nur die Systemverwaltung drücken darf, wäre für jeden anderen Bibliothekseigentümer tot. Zusätzlich greift das Rate Limiting mit einem eigenen, engen Kontingent für diesen Pfad (`OPAA_RATE_LIMIT_INDEXING_*`, standardmäßig eine Anfrage pro IP und Minute).

Wer eine Bibliothek vom Typ `HTTP_DIRECTORY` oder `RSS_FEED` anlegt, bestimmt bei der Anlage deren `sourceUrl` — eine beliebige Adresse, die der Server bei jedem Lauf abruft und crawlt. Das schließt Ziele ein, die nur aus dem Netz des VPS erreichbar sind und nicht aus dem Internet. Fachlich ist das serverseitige Anfragefälschung (Server-Side Request Forgery, SSRF) — hier allerdings als **Eigenschaft der Funktion**, nicht als Fehler: Der Endpunkt existiert genau dafür, entfernte Dokumentenquellen zu erschließen, und er verlangt Anmeldung plus mindestens Bearbeitungsrecht auf der Bibliothek.

**Ist-Zustand der Einschränkungen** (geprüft an `KnowledgeLibraryService`, `UrlIndexingExecutor`, `RssFeedIndexingExecutor`, `AutoindexCrawlerService` und `TargetAddressValidator`):

- Das Schema wird von OPAA selbst geprüft (nicht nur als Nebenwirkung des `java.net.http.HttpClient`, der ohnehin nur `http`/`https` akzeptiert): Ein anderes Schema wird mit einer deutschen Meldung abgelehnt, bevor überhaupt eine Verbindung versucht wird.
- Eine **Blockliste privater und lokaler Adressbereiche ist aktiv** (`TargetAddressValidator`, standardmäßig eingeschaltet): Loopback (`127.0.0.0/8`, `::1`), Link-Local einschließlich der Cloud-Metadaten-Adresse `169.254.169.254` (`169.254.0.0/16`, `fe80::/10`), die privaten IPv4-Bereiche (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), Carrier-Grade NAT (`100.64.0.0/10`), reserviert einschließlich Broadcast (`240.0.0.0/4`, `255.255.255.255`), IETF Protocol Assignments (`192.0.0.0/24`), Benchmarking (`198.18.0.0/15`), IPv6 Unique-Local (`fc00::/7`), NAT64 (`64:ff9b::/96`) sowie IPv4-in-IPv6-eingebettete Adressen — sowohl das aktuelle `::ffff:a.b.c.d` (IPv4-mapped) als auch das veraltete `::a.b.c.d` (IPv4-compatible) — werden abgelehnt, geprüft anhand des eingebetteten IPv4-Werts. Geprüft wird durchgehend die **aufgelöste** IP-Adresse, nicht der Hostname. Die Prüfung greift vor dem ersten Abruf **und** nach jeder Weiterleitung, damit ein Redirect eine erlaubte Startadresse nicht auf eine gesperrte Adresse umlenken kann. Abschaltbar (`OPAA_INDEXING_TARGET_VALIDATION_ENABLED=false`) oder um konkrete Hostnamen erweiterbar (`OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST`) für Betriebe mit legitimen internen Quellen — siehe die Tabelle unten.
- **DNS-Rebinding bleibt eine grundsätzliche, dokumentierte Grenze:** Die Auflösung, die geprüft wird, und die Auflösung, mit der `HttpClient` tatsächlich verbindet, sind zwei getrennte Vorgänge — ein Resolver, der zwischen beiden eine andere Antwort gibt, wird von dieser Prüfung nicht erkannt. Der JDK-`HttpClient` bietet keinen unterstützten Weg, eine Verbindung auf eine bereits geprüfte Adresse festzulegen. Das ist Härtung gegen naheliegende Fehlgriffe und Missbrauch, keine vollständige Garantie.
- Weiterleitungen werden gefolgt, aber nicht über `HttpClient.Redirect.NORMAL`: Der `HttpClient` steht auf `Redirect.NEVER`, jede Weiterleitung wird manuell verfolgt und dabei gegen den ursprünglichen Origin geprüft (Schema, Host, normalisierter Port) — eine Weiterleitung auf einen fremden Host wird abgelehnt, statt ihr zu folgen, ebenso ein Downgrade von `https` auf `http`. Ein Upgrade von `http` auf `https` auf demselben Host (Standard-Ports oder identischer expliziter Port) gilt dagegen als dieselbe Origin und wird gefolgt, Zugangsdaten eingeschlossen.
- Mit `sourceInsecureSsl: true` lässt sich die Zertifikatsprüfung abschalten. Bei `HTTP_DIRECTORY` gilt das für den gesamten Crawl (Verzeichnislisting und jede darüber gefundene Datei). Bei `RSS_FEED` dagegen nur für den Origin der konfigurierten `sourceUrl` selbst: Eine Detailseite oder Anlage, auf die der Feed-Inhalt verweist und die auf einem fremden Origin liegt, wird weiterhin normal geprüft, unabhängig von `sourceInsecureSsl` — der Feed-Betreiber kontrolliert diesen Inhalt, nicht der Bibliothekseigentümer.
- **Ein konfigurierter `sourceProxy` bestimmt, wohin die TCP-Verbindung tatsächlich geht, und wird deshalb ebenfalls geprüft** — beim Verbindungstest (`POST /api/v1/libraries/source-test`) direkt gegen die vom Aufrufer im Request gesetzte Proxy-Adresse, bei einem echten Indizierungslauf über den bereits geprüften `sourceUrl`/Redirect-Pfad hinaus nicht zusätzlich: Ein Lauf liest `sourceProxy` ausschließlich aus der bereits gespeicherten, durch mindestens `MANAGER` autorisierten Bibliothekskonfiguration (kein per-Request-Feld) — wer diesen Wert setzen darf, darf `sourceUrl` ohnehin frei wählen.
- Fehlermeldungen des Crawls landen im Jobstatus und sind über `GET /api/v1/libraries/{libraryId}/indexing/status` lesbar; sie unterscheiden erreichbare von nicht erreichbaren Zielen, eine abgelehnte Weiterleitung (Ziel als `Schema://Host` ohne Pfad/Query, nie die vollständige, potenziell sensible Ziel-URL) und ein per Zielprüfung gesperrtes Ziel.

**Risikoeinordnung.** Die Anlage jedes Quellentyps steht bewusst und dauerhaft jedem Berechtigten offen (nicht nur `SYSTEM_ADMIN`) — kein Rollenkonstrukt tritt an die Stelle dieser Öffnung. Für `FILESYSTEM` ist die daraus entstehende Angriffsfläche über die Pfad-Allowlist (`opaa.indexing.filesystem.allowlist`) geschlossen: Sie sichert, welche Serverpfade überhaupt konfigurierbar sind, unabhängig davon, wer die Bibliothek anlegt. Für `HTTP_DIRECTORY`/`RSS_FEED` schließt die Zielprüfung (`opaa.indexing.target-validation`) die analoge Lücke: Sie sichert, welche Adressbereiche überhaupt erreichbar sind, ebenfalls unabhängig davon, wer die Bibliothek anlegt.

**Konsequenz für den Betrieb:** Für `FILESYSTEM` schützt die Allowlist, für `HTTP_DIRECTORY`/`RSS_FEED`/`CONFLUENCE`/`S3` die Zielprüfung — beide unabhängig davon, wem das Anlage-Recht zusteht. Ein Betrieb mit legitimen internen Dokumentenquellen (z. B. einem Intranet-Portal) trägt deren Hostnamen gezielt in `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` ein, statt die Prüfung insgesamt abzuschalten.

Weist ein `sourcePath` außerhalb der Allowlist zurück, nennt die 400-Antwort bewusst keine Serverpfade — das würde die konfigurierten Basisverzeichnisse gegenüber einem nicht berechtigten Aufrufer preisgeben. Damit der abgewiesene Aufrufer trotzdem handlungsfähig bleibt, verweist die Meldung stattdessen auf die Systemverwaltung als Anlaufstelle für die freigegebenen Basisverzeichnisse.

## Härtung für erreichbare Deployments

Der mitgelieferte Compose-Stack (`docker-compose.yml`, `keycloak/realm-export.json`) ist ausdrücklich für
die **lokale Entwicklung** gebaut, wie der Warnhinweis am Kopf von `docker-compose.yml` sagt. Seine
Vorgabewerte sind dafür richtig gewählt — bequem, ohne Ersteinrichtung nutzbar, mit einem funktionierenden
OIDC-Realm ab dem ersten Start. Für eine **öffentlich oder auch nur aus einem größeren Netz erreichbare
Instanz** ist jeder dieser Vorgabewerte ein Problem, und der Warnhinweis allein sagt nicht, was konkret zu
tun ist. Dieser Abschnitt schließt genau diese Lücke — die Doku, nicht der Betrieb. Die öffentliche
Demo-Instanz des Projekts ist ein so gehärtetes Beispiel: alle Container-Ports auf `127.0.0.1`, ein
TLS-terminierender nginx auf dem Host davor, Keycloak unter dem Unterpfad `/idp`, der Entwicklungsnutzer
`testuser` deaktiviert, das Passwort des Administrationskontos nach jedem Seed-Lauf rotiert und der
Client `opaa-seed` nur für die Dauer eines Seed-Laufs aktiviert.

Die folgende Liste geht die sechs tatsächlich im Repository liegenden Vorgabewerte durch. Wo eine
Gegenmaßnahme **zwingend** ist, steht das dabei; alles andere ist eine Empfehlung, deren Unterlassung
begründet werden sollte, kein hartes Muss.

| # | Fundstelle | Vorgabewert | Risiko bei erreichbarem Betrieb | Gegenmaßnahme |
|---|---|---|---|---|
| 1 | `keycloak/realm-export.json` | Realm-Benutzer `testuser`/`testpass` sowie die fünf Demo-Nutzer (`demo-admin`, `maria.weber`, `selin.kaya`, `thomas.klein`, `andrea.vogt`) mit dem gemeinsamen Passwort `RheinfurtDemo!2026`, alle `"temporary": false` | Bekannte, öffentlich im Repository stehende Zugangsdaten zu echten Konten — jeder, der das Repository kennt, kennt diese Logins | **Zwingend.** Diese Benutzer aus dem Realm-Export entfernen oder durch eigene, beim Import per Skript gesetzte Zufallspasswörter ersetzen, bevor der Realm importiert wird. Wer den mitgelieferten Realm unverändert importiert, importiert diese Konten mit. Für einen Rollout der Demo-Instanz selbst gilt grundsätzlich dieselbe Pflicht, mit einer bewussten Ausnahme: Die vier Fachkonten behalten dort das dokumentierte Demo-Passwort als akzeptiertes Restrisiko (sie sind `USER`-Konten ohne Adminrechte, begrenzt durch Rate Limiting und ein Ausgabenlimit beim Chat-Anbieter); `testuser` ist deaktiviert, `demo-admin` trägt ein rotiertes Passwort |
| 2 | `keycloak/realm-export.json:6` | `"sslRequired": "none"` | Keycloak nimmt Anmeldungen und Admin-Zugriffe auch unverschlüsselt an — in Kombination mit einer erreichbaren Instanz eine offene Tür für das Mitlesen von Zugangsdaten | **Zwingend**, sobald die Instanz nicht ausschließlich über eine als vertrauenswürdig geltende Loopback-/interne Verbindung erreicht wird: auf `external` (TLS für externe Zugriffe verpflichtend, intern optional) oder `all` (TLS immer verpflichtend) setzen. TLS selbst terminiert dabei **nicht** Keycloak, sondern der vorgelagerte Reverse-Proxy — siehe [„Netzwerkzugang"](#netzwerkzugang) unten (nginx terminiert TLS, Keycloak selbst bleibt intern auf HTTP). **Zur selben Maßnahme gehört zwingend `KC_PROXY_HEADERS=xforwarded`** (Keycloak-Umgebungsvariable, analog zum in [„Netzwerkzugang"](#netzwerkzugang) beschriebenen `server.forward-headers-strategy` des Backends) — ohne sie hält Keycloak eine über den Proxy per TLS ankommende Anfrage für unverschlüsselt und antwortet mit „HTTPS required", was einen Betreiber typischerweise dazu verleitet, `sslRequired` fälschlich wieder auf `none` zurückzustellen, statt die eigentliche Ursache zu beheben. Bei Einhängung unter einem Unterpfad (z. B. `/idp`) zusätzlich `KC_HTTP_RELATIVE_PATH` setzen. Wie beim Backend muss der vorgelagerte Proxy `X-Forwarded-Proto` dabei **autoritativ** setzen und den Wert nicht vom Client durchlassen |
| 3 | `docker-compose.yml:64-65` | Keycloak-Bootstrap-Admin `KC_BOOTSTRAP_ADMIN_USERNAME`/`_PASSWORD` fest auf `admin`/`admin` | Voller administrativer Zugriff auf den Identitätsanbieter mit einem der bekanntesten Vorgabepasswörter überhaupt | **Zwingend.** Über eigene Umgebungsvariablen setzen und **nach der Ersteinrichtung ersetzen** — der Bootstrap-Admin legt beim allerersten Start einen administrativen Benutzer an; sein Passwort danach unverändert zu lassen, macht die einmalige Bootstrap-Vereinfachung zu einer dauerhaften Schwachstelle. **Nicht** über `.env.docker`: Der `keycloak`-Service hat kein `env_file` und liest `${...}`-Platzhalter in `docker-compose.yml` stattdessen aus der Shell-Umgebung bzw. einer `.env`-Datei (siehe die Erklärung unter [„Konfiguration"](#konfiguration) unten) — die beiden Werte gehören also in `.env` oder werden per `docker compose --env-file <datei>` übergeben, mit derselben Vorsicht wie dort beschrieben, damit sie keine lokalen Entwicklungseinstellungen überschreiben |
| 4 | `docker-compose.yml:62` | `command: start-dev --import-realm` — kein persistentes Datenverzeichnis, nur der Realm-Export ist als Datei gemountet | Keycloaks eingebaute Entwicklungsdatenbank ist an den Container gebunden und geht bei jedem Neuerstellen verloren — ein rotiertes Bootstrap-Passwort (Punkt 3) und ein bereinigter Realm (Punkt 1) verschwinden damit beim nächsten `docker compose up -d`/Image-Update und `admin`/`admin` sowie `testuser` sind wieder da, ohne dass das auffällt | **Zwingend**, sonst wirken die Gegenmaßnahmen zu Punkt 1 und 3 nicht dauerhaft, sondern nur bis zum nächsten Container-Neustart. Für erreichbaren Betrieb `start` statt `start-dev` verwenden (das erzwingt ohnehin die übrigen Härtungspunkte dieser Zeile — Keycloak startet mit `start` ohne konfiguriertes TLS/Proxy-Setup gar nicht erst) und eine externe, persistente Datenbank anbinden (`KC_DB`, `KC_DB_URL` u. a.) statt der eingebauten Entwicklungsdatenbank. `--import-realm` importiert einen Realm dabei ohnehin nur, wenn er noch nicht existiert — nach der ersten, persistenten Einrichtung wirken spätere Realm-Änderungen über die Admin-Konsole oder einen erneuten, gezielten Import |
| 5 | `docker-compose.yml:19-20`, `40`, `56`, `68` | Nur `postgres` bindet auf `127.0.0.1`; `backend` (8081), `frontend` (3000) und `keycloak` (8180) veröffentlichen ihre Ports ohne Adressangabe und binden damit auf allen Schnittstellen | `postgres` auf `127.0.0.1` gebunden schützt vor Zugriff aus dem Netz, aber nicht vor jedem anderen Prozess und Nutzerkonto auf demselben Host. Die übrigen drei Ports sind dagegen aus dem Netz erreichbar, sobald keine Firewall davorsteht — bei `keycloak` ist das der konkrete Ausnutzungsweg zu Punkt 3: Die Admin-Konsole wäre netzweit ansprechbar, unabhängig davon, ob ein vorgelagerter Reverse-Proxy nur bestimmte Pfade durchreicht | Hinter einem Reverse-Proxy alle vier Ports auf `127.0.0.1:` binden, so wie es `postgres` bereits vormacht — für `keycloak` **zwingend** (sonst bleibt die Admin-Konsole trotz Proxy direkt aus dem Netz erreichbar), für `backend`/`frontend` **empfohlen** (der Reverse-Proxy ist dann der einzige Weg zu beiden). Für `postgres` **empfohlen**, die `ports:`-Zuordnung für den erreichbaren Betrieb ganz zu entfernen statt sie nur auf Loopback zu binden — Backend und `postgres` erreichen sich ohnehin über das interne Compose-Netz (Servicename `postgres`), ein Host-Port wird dafür nicht gebraucht. Für lokale Entwicklung (Anschluss mit einem Datenbank-Client vom Host aus, direkter Aufruf der Admin-Konsole) bleiben die bisherigen Bindungen dagegen sinnvoll — deshalb sind sie dort nicht als Fehler markiert |
| 6 | `keycloak/realm-export.json` (Client `opaa-seed`) | Öffentlicher Client mit `directAccessGrantsEnabled: true` (Resource-Owner-Password-Grant) und ohne Client-Secret, ausschließlich für das Seed-Skript der Demo (`demo/seed/seed.py`) gedacht, das sich damit als Demo-Nutzer anmeldet und Bibliotheken, Rechte und Chats über die reguläre API anlegt | Erlaubt einen passwortbasierten Tokenweg **ohne Secret** gegen jedes Realm-Konto — auf einer erreichbaren Instanz ein zusätzlicher, von der eigentlichen Anmeldung (`opaa-frontend`, `directAccessGrantsEnabled: false`, Authorization-Code + PKCE) unabhängiger Angriffsweg, unabhängig davon, wessen Passwort betroffen ist | **Zwingend.** Client `opaa-seed` aus dem Realm-Export entfernen oder auf `enabled: false` setzen, bevor der Realm auf einer erreichbaren Instanz importiert wird. Wer die Demo dort dennoch erneut seeden will, aktiviert den Client nur für die Dauer des Laufs wieder (per `kcadm` oder Admin-Konsole) oder legt die Rechte direkt über die Keycloak-Admin-Konsole/API an, statt den Client dauerhaft scharf zu lassen |

**Realm-Lebensdauern auf einer bereits laufenden Instanz:** `keycloak/realm-export.json`
setzt `accessTokenLifespan`, `ssoSessionIdleTimeout` und `ssoSessionMaxLifespan` explizit. Wie
bei Punkt 4 oben importiert `--import-realm` einen Realm dabei nur, wenn er noch nicht existiert
— auf einer Instanz mit bereits importiertem Realm wirkt eine spätere Änderung dieser Werte im
Repository also **nicht von selbst**. Ein erneuter, vollständiger Import
würde außerdem die dokumentierte Härtung der Konten aus Punkt 1 zurückdrehen. Die Lebensdauern
müssen stattdessen gezielt über `kcadm` (oder die Admin-Konsole) nachgezogen werden:

```bash
kcadm.sh config credentials --server http://localhost:8180 --realm master --user admin
kcadm.sh update realms/opaa -s accessTokenLifespan=900 -s ssoSessionIdleTimeout=3600 -s ssoSessionMaxLifespan=36000
```

**Zugangsdaten, die zusätzlich zu ersetzen sind (empfohlen, unabhängig von den sechs Fundstellen oben):**

- `OPAA_DB_USERNAME`/`OPAA_DB_PASSWORD` — die Vorgabewerte `opaa`/`opaa` sind für die Entwicklung gewählt,
  nicht für den Betrieb. Eine Änderung nach der Ersteinrichtung verlangt `docker compose down -v` (siehe
  [„Datenbank"](#datenbank) unten), weil PostgreSQL den initialen Benutzer nur beim ersten Start anlegt —
  das Zugangsdatenpaar also **vor** dem ersten Start setzen, nicht danach ändern wollen.
- `OPAA_CREDENTIALS_ENCRYPTION_KEY` — sobald eine Bibliothek vom Typ `HTTP_DIRECTORY`/`RSS_FEED`
  Zugangsdaten zu einer externen Quelle speichert (siehe [„Zugangsdaten-Verschlüsselung"](#zugangsdaten-verschlüsselung)
  unten), muss ein eigener Schlüssel gesetzt sein — ohne ihn schlägt nur dieser eine Schreibvorgang fehl,
  der Start selbst nicht. Der im `dev`-Profil hinterlegte Schlüssel ist ausdrücklich nicht
  produktionstauglich und darf nicht übernommen werden.
- `OPAA_SETTINGS_ENCRYPTION_KEY` — sobald ein Chat-Modell mit Zugangsschlüssel angelegt oder
  geändert wird (siehe [„Verschlüsselung der Zugangsschlüssel verwalteter Chat-Modelle"](#verschlüsselung-der-zugangsschlüssel-verwalteter-chat-modelle)
  unten), muss ein eigener Schlüssel gesetzt sein — ohne ihn schlägt nur dieser eine Vorgang fehl,
  der Start selbst nicht. Der im `dev`-Profil hinterlegte Schlüssel ist ausdrücklich nicht
  produktionstauglich und darf nicht übernommen werden.

**Was es nicht gibt und deshalb hier auch nicht zu ersetzen ist:** Ein
anwendungsseitiges JWT-Secret existiert nicht — OPAA verifiziert Tokens ausschließlich gegen die
Signaturschlüssel des konfigurierten
OIDC-Anbieters (`OPAA_OIDC_JWK_SET_URI`), es gibt kein eigenes Signier-Geheimnis, das rotiert werden
müsste. Diese Verantwortung liegt beim Identitätsanbieter (dessen Realm-Schlüssel) statt bei OPAA. Ebenso
gibt es keinen Mock-Auth-Modus: Der einzige ungeprüfte Modus ist das Spring-Profil `dev`
— **es gehört nie auf eine erreichbare Instanz**, siehe die Warnung unter
[„Entwicklungsmodus (dev)"](#entwicklungsmodus-dev) unten. Nur `SPRING_PROFILES_ACTIVE=...,oidc` ist für
den erreichbaren Betrieb zulässig.

**Woran erkennbar ist, was zwingend und was nur empfohlen ist:** Die Tabelle und die Liste oben markieren
jeden Punkt einzeln als „Zwingend" oder „Empfohlen". Als Faustregel: Ein bekanntes, öffentlich im
Repository stehendes Zugangsdatenpaar, ein Klartext-Transportweg zu Anmeldedaten oder ein netzweit
erreichbarer administrativer Zugang ist immer zwingend zu schließen; eine Einschränkung, die nur die
Angriffsfläche auf einem sonst schon vertrauenswürdigen Host verkleinert (etwa Teile von Punkt 5), ist
eine Empfehlung.

**Es gibt keine separate `docker-compose.prod.yml`.** Mehrere der Fundstellen verlangen eine
Änderung an `keycloak/realm-export.json` (kein Compose-Override kann Inhalte einer importierten
JSON-Datei patchen, ohne selbst wieder ein Geheimnis oder einen Generierungsschritt einzuführen), einen
echten Bootstrap-Vorgang (Passwort **nach** der Ersteinrichtung ändern) bzw. eine externe, persistente
Datenbank für Keycloak (eine Betriebsentscheidung, keine Compose-Konstante). Eine zweite Compose-Datei
würde nur die Port-Bindungen automatisieren und für den Rest auf genau diese Anleitung verweisen.

## Vor der Inbetriebnahme: mandantenfähiger Betrieb

Eine Installation wird mit **genau einer** Organisation ausgeliefert; es gibt keinen Verwaltungspfad, der eine zweite anlegt, und alle Identitätsanbieter provisionieren Konten in dieselbe Organisation. Solange das so bleibt, ist die Mandantengrenze unkritisch — es gibt nichts, was sie überschreiten könnte.

Die Grenze wird dennoch in drei Schichten gehalten, damit eine spätere zweite Organisation keine nachträgliche Umstellung jeder Rechteabfrage verlangt: Die **Anwendung** löst fremde Kennungen nur innerhalb der Organisation des Aufrufenden auf (ein Objekt einer anderen Organisation ist „nicht gefunden", nicht „nicht erlaubt"); die **Datenbank** führt die Organisation in jedem Verweis auf ein organisationsgebundenes Objekt mit, sodass eine Zeile, die zwei Organisationen mischt, nicht anlegbar ist; ein **struktureller Prüflauf** in der Testsuite liest das tatsächliche Schema aus und schlägt an, sobald eine neue Tabelle diesen Aufbau verletzt. Außerhalb dieses Prüflaufs liegen die Historientabellen der Rechtevergabe (absichtlich ohne Fremdschlüssel, damit das Löschen einer Bibliothek ihre Historie nicht mitreißt) und die Vektortabelle, die die Organisation nur als JSON-Metadatum trägt.

## Schnellstart

```bash
# 1. Umgebung konfigurieren
cp .env.docker.example .env.docker
```

`.env.docker` bearbeiten. Voreingestellt sind lokal betriebene Modelle über die openai-kompatible
Schicht (der einzige Anbindungsweg) — dafür ist keine weitere Angabe in `.env.docker`
selbst nötig. Zwei sich gegenseitig ausschließende Wege stehen für den lokal betriebenen
Ollama-Server zur Wahl, den `OPAA_OPENAI_BASE_URL` standardmäßig voraussetzt (siehe
[„LLM-Anbieter"](#llm-anbieter) und [„Lokal betriebenes Ollama im
Compose-Stack"](#lokal-betriebenes-ollama-im-compose-stack) unten für Details zu beiden):

**Weg A — Ollama als Teil dieses Stacks (Compose-Profil `ollama`).** Keine weitere Konfiguration
nötig — `OPAA_OPENAI_BASE_URL` zeigt bereits auf `http://ollama:11434/v1`. Empfohlen für den
allerersten Start: zunächst nur den Init-Schritt abwarten (er lädt mehrere GiB Modelldaten herunter,
siehe „Ressourcen- und Downloadhinweis" unten), bevor der restliche Stack hochfährt — ein Dokument,
das währenddessen bereits hochgeladen wird, würde sonst mit Status `FAILED` enden, weil das
Chat-/Embedding-Modell noch fehlt:

```bash
docker compose --profile ollama up ollama-pull   # wartet, bis beide Modelle vorliegen
docker compose --profile ollama up --build       # danach den vollständigen Stack starten
```

Wer dieses Warten nicht braucht (z. B. weil ohnehin erst später indiziert wird), startet stattdessen
direkt mit `docker compose --profile ollama up --build` — der Stack läuft dann bereits, während
`ollama-pull` im Hintergrund noch lädt.

**Weg B — externer Ollama-Server** (auf dem Host oder in einem anderen Netz), ohne das
Compose-Profil. Läuft Ollama auf dem Host, in `.env.docker` setzen:

```env
OPAA_OPENAI_BASE_URL=http://host.docker.internal:11434/v1
```

und danach starten:

```bash
docker compose up --build
```

Ohne einen erreichbaren externen Server unter der konfigurierten Adresse startet der Stack zwar,
aber Indizierung und Fragen schlagen fehl.

```bash
# 3. Anwendung öffnen
# Frontend: http://localhost:3000
# Backend-API: http://localhost:8081/api
```

## Services

| Service     | Host-Port | Container-Port | Beschreibung                                          |
|-------------|-----------|-----------------|--------------------------------------------------------|
| frontend    | 3000      | 80              | React-App über Nginx bereitgestellt                    |
| backend     | 8081      | 8080            | Spring Boot API                                         |
| postgres    | 5432      | 5432            | PostgreSQL 18 mit pgvector                              |
| keycloak    | 8180      | 8180            | Keycloak (nur `oidc`-/`demo`-Profil)                    |
| ollama      | —         | 11434           | Lokal betriebener Ollama-Server (nur `ollama`-Profil; kein Host-Port, siehe unten) |
| ollama-pull | —         | —               | Einmaliger Init-Schritt, zieht `nomic-embed-text`/`phi3:mini` (nur `ollama`-Profil) |

## Konfiguration

Alle Konfigurationen erfolgen über Umgebungsvariablen in `.env.docker`. Docker Compose lädt diese Datei über die `env_file`-Direktive. Alle verfügbaren Optionen mit Beschreibungen finden Sie in `.env.docker.example`.

> **Zwei getrennte Vorlagen, zwei Zwecke.** `.env.example` ist auf `./gradlew bootRun` auf dem Host zugeschnitten (Datenbank auf `localhost`, CORS auf den Vite-Dev-Server `:5173`, kein `SPRING_PROFILES_ACTIVE`, weil `bootRun` es üblicherweise direkt in der Shell setzt). `.env.docker.example` ist die Vorlage für den Compose-Stack: `SPRING_PROFILES_ACTIVE=docker,dev` gesetzt, `OPAA_DB_URL` auskommentiert (der `docker`-Profil-Default liefert bereits den richtigen Hostnamen `postgres`), `OPAA_CORS_ALLOWED_ORIGINS` auf den Frontend-Host-Port `:3000` statt `:5173` und die leer gesetzten `OPAA_OPENAI_*`-Zeilen auskommentiert (ein leer gesetzter Wert überschreibt sonst den Anwendungs-Default, siehe Hinweis in der Variablen-Tabelle unten). Wer beide Kopien unverändert nebeneinander nutzt — `.env` für `bootRun`, `.env.docker` für Compose — bekommt in beiden Fällen einen startfähigen Stand, ohne die jeweils andere Vorlage von Hand nachzubessern.

> **Wichtig:** Docker Compose lädt automatisch eine `.env`-Datei (falls vorhanden) für die Variablen-Interpolation in `docker-compose.yml` selbst. Um Konflikte mit lokalen Entwicklungseinstellungen zu vermeiden, verwenden Docker-Compose-Services `.env.docker` als ihre `env_file`. Wenn Sie eine `.env`-Datei für die lokale Entwicklung haben, stellen Sie sicher, dass die Docker-relevanten Variablen (Ports, DB-Anmeldeinformationen) nicht kollidieren.

### Erforderliche Variablen

Im Standardfall — lokal betriebene Modelle über die openai-kompatible Schicht für Chat und
Einbettung (der einzige Anbindungsweg) — ist **keine** Modellvariable erforderlich. Der
Stack startet ohne zusätzliche Angabe; die Basis-Adresse zeigt bereits auf einen lokal betriebenen
Ollama-Server (`http://localhost:11434/v1` bzw., im Profil `docker`, `http://ollama:11434/v1`).

Wer stattdessen einen anderen Anbieter für Chat oder Einbettung verwenden will, muss die
**Zieladresse überschreiben**:

```env
OPAA_OPENAI_BASE_URL=https://modellserver.example.internal/v1
OPAA_OPENAI_API_KEY=sk-your-key-here
```

Die Adresse hat eine Voreinstellung — sie zeigt aber auf einen lokalen
Endpunkt, nicht nach außen; eine Installation, die im Haus bleiben soll, wird dadurch nicht
stillschweigend nach außen gerichtet. Ein **explizit leer gesetzter** Wert (etwa
`OPAA_OPENAI_BASE_URL=` in einer `.env`-Datei) überschreibt diese Voreinstellung dagegen mit einer
leeren Zeichenkette und lässt das Backend mit einer Meldung abbrechen, die die betroffene Variable
benennt (`io.opaa.config.OpenAiBaseUrlGuard`).

> **Update-Hinweis für Bestandsinstallationen (Modellverwaltung, seit 22.08.2026).** Das Backend
> übernimmt beim ersten Start nach dem Update die obige Konfiguration einmalig als
> verwaltetes Chat-Modell (siehe [„LLM-Anbieter"](#llm-anbieter) unten).
> Das erfordert **keine** neue Variable für den Normalfall — auch nicht für `docker,oidc` oder eine
> reine Ollama-Installation ohne jeden Zugangsschlüssel. Nur wer bereits mit gesetztem
> `OPAA_OPENAI_API_KEY` betrieben wird, sollte vor dem Update `OPAA_SETTINGS_ENCRYPTION_KEY` setzen
> (siehe [„Verschlüsselung der Zugangsschlüssel verwalteter Chat-Modelle"](#verschlüsselung-der-zugangsschlüssel-verwalteter-chat-modelle))
> — ohne ihn scheitert nur diese einmalige Übernahme, der Start selbst nicht.

### Migrationen aus älteren Ständen

**Ein Ereigniseingang für alle Push-Wege (seit 07.09.2026).** Der Confluence-Webhook und der
S3-Ereignisweg teilen sich Sammelzeit, Stapelgrenze und Wartezyklen; die konnektoreigenen Variablen
sind entfallen. Eine noch gesetzte alte Variable wird von Spring stillschweigend ignoriert — dann
gilt der Standard des gemeinsamen Schlüssels. Wer für beide Konnektoren unterschiedliche Werte
gesetzt hatte, wählt einen: Die Stapelgrenze gilt jetzt für Seitenkennungen und Objektschlüssel
gleichermaßen, die Wartezyklen für beide Läufe.

| Entfallene Variable | Ersatz |
|---|---|
| `OPAA_INDEXING_CONFLUENCE_WEBHOOK_DEBOUNCE`, `OPAA_INDEXING_S3_EVENTS_DEBOUNCE` | `OPAA_INDEXING_EVENTS_DEBOUNCE` (Standard unverändert `5s`) |
| `OPAA_INDEXING_CONFLUENCE_WEBHOOK_MAX_PENDING_PAGES` (bisher `200`), `OPAA_INDEXING_S3_EVENTS_MAX_PENDING_KEYS` (bisher `500`) | `OPAA_INDEXING_EVENTS_MAX_PENDING_KEYS` (Standard `500`) |
| `OPAA_INDEXING_CONFLUENCE_WEBHOOK_MAX_DEFERRALS` (bisher `120`), `OPAA_INDEXING_S3_EVENTS_MAX_DEFERRALS` (bisher `12`) | `OPAA_INDEXING_EVENTS_MAX_DEFERRALS` (Standard `120`) |

**Ein `User-Agent` für alle Netzkonnektoren (seit 05.09.2026).** Die konnektoreigenen Variablen sind
entfallen; ihr Wert wird auf den gemeinsamen Schlüssel übertragen. Eine noch gesetzte alte Variable
wird von Spring stillschweigend ignoriert — dann gilt wieder der Standard `OPAA-Indexer/1.0`.

| Entfallene Variable | Ersatz |
|---|---|
| `OPAA_INDEXING_RSS_USER_AGENT` | `OPAA_INDEXING_HTTP_USER_AGENT` |
| `OPAA_INDEXING_CONFLUENCE_USER_AGENT` | `OPAA_INDEXING_HTTP_USER_AGENT` |

**Der native Ollama-Anbindungsweg ist seit dem 22.08.2026 entfallen.** Bis dahin
gab es zwei Anbindungswege — einen nativen für Ollama (`OPAA_AI_CHAT_PROVIDER`/
`OPAA_AI_EMBEDDING_PROVIDER=ollama`, mit eigenen `OPAA_OLLAMA_*`-Variablen) und den
openai-kompatiblen für alles andere. Seitdem gibt es nur noch den zweiten:

| Entfallene Variable | Ersatz |
|---|---|
| `OPAA_AI_CHAT_PROVIDER` / `OPAA_AI_EMBEDDING_PROVIDER` | keiner nötig — der Anbindungsweg ist jetzt fest `openai` |
| `OPAA_OLLAMA_BASE_URL` | `OPAA_OPENAI_BASE_URL` (bzw. `OPAA_OPENAI_CHAT_BASE_URL`/`OPAA_OPENAI_EMBEDDING_BASE_URL` je Funktion) — **mit `/v1`-Suffix**, den die Ollama-Variable nicht brauchte |
| `OPAA_OLLAMA_CHAT_MODEL` | `OPAA_OPENAI_CHAT_MODEL` |
| `OPAA_OLLAMA_EMBEDDING_MODEL` | `OPAA_OPENAI_EMBEDDING_MODEL` |

Wer eine dieser Variablen gesetzt hatte, überträgt den Wert (mit angehängtem `/v1` bei der
Basis-Adresse) vor dem Update auf den Ersatz — der neue `OPAA_OPENAI_BASE_URL`-Default zeigt zwar
ebenfalls auf einen lokal betriebenen Ollama-Server, aber nicht notwendigerweise auf denselben (z. B.
wenn Ollama auf dem Host statt als Compose-Service `ollama` läuft, siehe [„Lokal betriebenes Ollama im
Compose-Stack"](#lokal-betriebenes-ollama-im-compose-stack)). War `OPAA_OPENAI_BASE_URL`
zusätzlich bereits auf einen anderen Anbieter als Ollama gesetzt (z. B. einen Cloud-Endpunkt für den
Chat), muss für die Einbettung ausdrücklich `OPAA_OPENAI_EMBEDDING_BASE_URL` gesetzt werden — sonst
erbt die Einbettung nach dem Update dieselbe Adresse wie der Chat, und indizierter Dokumentinhalt
ginge an einen dafür nie vorgesehenen Anbieter außerhalb des Hauses. Übrig gelassene, jetzt unbekannte
Variablen werden von Spring stillschweigend ignoriert; das Backend protokolliert beim Start
zusätzlich eine `WARN`-Zeile, falls eine der entfallenen Variablen noch in der Umgebung liegt. Für die
**Chat**-Migration gibt es eine Erleichterung: `io.opaa.llm.LlmModelSeeder` liest beim ersten Start
nach dem Update ohnehin noch `OPAA_OLLAMA_BASE_URL`/`OPAA_OLLAMA_CHAT_MODEL`, falls
`OPAA_AI_CHAT_PROVIDER=ollama` zusammen mit einer der beiden noch gesetzt ist — ein Update in einem
Schritt übernimmt die bisherige Chat-Konfiguration also auch ohne vorherige Anpassung korrekt. Für die
**Einbettung** gibt es diese Erleichterung nicht (Einbettungsmodelle sind, anders als das Chat-Modell,
keine verwalteten Objekte) — hier müssen die Werte vor dem Update in `.env`/`.env.docker` übertragen
werden, sonst schlägt die Einbettung ab dem ersten Start nach dem Update fehl.

### Docker-spezifische Variablen

Diese Variablen sind wichtig, wenn mit Docker Compose ausgeführt wird, und sollten in `.env.docker` gesetzt werden:

| Variable | Erforderlicher Wert | Warum |
|----------|---------------------|-------|
| `SPRING_PROFILES_ACTIVE` | `docker,oidc` (Betrieb) oder `docker,dev` (Entwicklung) | Aktiviert Docker-spezifische Konfiguration (DB-URL, openai-kompatible Basis-Adresse) und den Auth-Modus; ohne `oidc` oder `dev` startet das Backend nicht |
| `OPAA_SERVER_ADDRESS` | `0.0.0.0` | Backend muss an alle Schnittstellen binden, um von anderen Containern erreichbar zu sein |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | Muss mit dem Host-Port des Frontends übereinstimmen |
| `OPAA_DB_USERNAME` | `opaa` | Muss zwischen Backend- und postgres-Services übereinstimmen |
| `OPAA_DB_PASSWORD` | `opaa` | Muss zwischen Backend- und postgres-Services übereinstimmen |

### Minimale `.env.docker`

```env
SPRING_PROFILES_ACTIVE=docker,dev
OPAA_SERVER_ADDRESS=0.0.0.0
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:3000
OPAA_DB_USERNAME=opaa
OPAA_DB_PASSWORD=opaa
OPAA_PGVECTOR_DIMENSIONS=768
```

Chat und Einbettung laufen damit über die openai-kompatible Schicht gegen einen lokal betriebenen
Ollama-Server (`OPAA_OPENAI_BASE_URL` ist im Profil `docker` auf `http://ollama:11434/v1`
vorbelegt). `OPAA_PGVECTOR_DIMENSIONS=768` gehört zwingend dazu (siehe die Zeile zu dieser Variable
in [„Alle Umgebungsvariablen"](#alle-umgebungsvariablen) unten) - ohne sie bricht die erste
Einbettung mit einem Dimensionsfehler ab, weil der Embedding-Default `nomic-embed-text` mit 768
Dimensionen einbettet, nicht mit dem Anwendungs-Default 1536. Für einen anderen Anbieter kommen
`OPAA_OPENAI_BASE_URL` und `OPAA_OPENAI_API_KEY` hinzu (überschreiben die Voreinstellung) - dann
richtet sich `OPAA_PGVECTOR_DIMENSIONS` nach dessen Embedding-Modell statt nach `nomic-embed-text`.

### Alle Umgebungsvariablen

**„Standard" ist zweideutig — deshalb zwei Spalten.** Der **Anwendungs-Default** ist der Wert, den
`backend/src/main/resources/application.yml` (bzw. das Docker-Compose-Setup selbst) annimmt, wenn die
Variable **nirgends gesetzt** ist — etwa bei `./gradlew bootRun` ohne `.env`. Die **Compose-Belegung**
ist der Wert, den `.env.docker.example` tatsächlich vorgibt und den ein Nutzer erlebt, der es unverändert nach
`.env.docker` kopiert. Beide Angaben können auseinanderlaufen, ohne dass eine davon falsch ist — sie
beschreiben zwei verschiedene Ebenen. Eine leere Compose-Belegung („nicht gesetzt") bedeutet, dass
`.env.docker.example` die Variable auskommentiert lässt oder gar nicht enthält; dann gilt beim Kopieren nach
`.env.docker` der Anwendungs-Default.

**Vorrang der Konfigurationsquellen — zwei getrennte Mechanismen:**

1. **Werte, die im Backend-Container ankommen und von Spring gelesen werden** (also praktisch jede
   `opaa.*`/`spring.*`-Eigenschaft): Hier hat eine Umgebungsvariable, die explizit im
   `environment:`-Abschnitt einer `docker-compose.yml` gesetzt ist, Vorrang vor demselben Namen in der
   über `env_file:` eingebundenen `.env.docker`, und diese wiederum vor dem in `application.yml`
   hinterlegten Anwendungs-Default (`${VARIABLE:default}`). Bei `./gradlew bootRun` ohne Docker Compose
   tritt an die Stelle von `environment:`/`env_file` schlicht die Prozessumgebung der Shell, in der
   `bootRun` läuft. Eine Host-Shell-Variable erreicht den Backend-Container in Docker Compose **nicht**
   automatisch — nur wenn sie entweder in `environment:` referenziert wird (z. B. `OPAA_UPLOAD_STORAGE_PATH`
   in `docker-compose.yml`) oder über `env_file: .env.docker` geladen wird, landet sie im Container.
2. **Variablen, die `${...}` direkt in `docker-compose.yml` interpoliert** — Bind-Mounts, Host-Ports und
   die `env_file`-Auswahl selbst (`OPAA_ENV_FILE`, siehe unten). Docker Compose löst diese Platzhalter
   ausschließlich aus der **Prozessumgebung** und einer von Compose selbst automatisch geladenen
   `.env`-Datei im Projektwurzelverzeichnis auf — **niemals** aus der über `env_file:` eingebundenen
   `.env.docker` (`e2e/scripts/run-e2e.mjs` nutzt genau das, um Ports und die `env_file`-Auswahl der
   E2E-Suite per Prozessumgebung zu setzen, ohne die Datei eines Entwicklers anzufassen). Ein Wert, den
   `.env.docker.example` für eine solche Variable vorschlägt, bleibt deshalb wirkungslos, solange er nur in
   `.env.docker` steht — er muss als Shell-Variable exportiert oder in eine echte `.env`-Datei
   geschrieben werden. In der Tabelle unten mit „wirkt nur aus Prozessumgebung/`.env`, **nicht** aus
   `.env.docker`" gekennzeichnet.

Ist eine Variable nirgends gesetzt, gilt in beiden Fällen der jeweilige Default.

**`OPAA_OPENAI_*` gilt uneingeschränkt** — es gibt keine Anbieter-Variable, die ihre
Wirkung an- oder abschaltet: Chat und Einbettung laufen immer über diese eine, openai-kompatible
Konfiguration. (Ältere Stände schalteten mit `OPAA_AI_CHAT_PROVIDER`/
`OPAA_AI_EMBEDDING_PROVIDER` zwischen dieser Konfiguration und eigenen `OPAA_OLLAMA_*`-Variablen um
— siehe [„Migrationen aus älteren Ständen"](#migrationen-aus-älteren-ständen) oben für die
Migration.)

Manche Variablen dieser Tabelle sind kein Spring-Property, sondern werden ausschließlich von Docker
Compose selbst ausgewertet (Bind-Mounts, Host-Ports, die `env_file`-Auswahl) oder vom
`envsubst`-Template des Frontend-nginx — für sie gibt es keinen Anwendungs-Default im eigentlichen
Sinn; das ist jeweils vermerkt.

| Variable | Anwendungs-Default (`application.yml`) | Compose-Belegung (`.env.docker.example`) | Beschreibung |
|----------|------------------------------------------|-------------------------------------|-------------|
| **Allgemein** | | | |
| `OPAA_SERVER_ADDRESS` | `localhost` | `0.0.0.0` | Bind-Adresse (`0.0.0.0` für Netzwerkzugang). Docker Compose überschreibt den Anwendungs-Default bewusst — siehe Hinweis unter [Netzwerkzugang](#netzwerkzugang) |
| `OPAA_HTTP_FORCE_HTTP1` | `false` | `false` | HTTP/1.1 für vLLM-Kompatibilität erzwingen |
| `OPAA_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | `http://localhost:3000` | Erlaubte CORS-Origins (kommagetrennt). Der Anwendungs-Default passt nur außerhalb von Docker Compose (lokaler Vite-Dev-Server auf `:5173`) — die Compose-Belegung trägt deshalb bewusst den Frontend-Host-Port, standardmäßig `http://localhost:3000` (siehe [„Docker-spezifische Variablen"](#docker-spezifische-variablen) oben und [„POST-Anfragen geben 403 Forbidden zurück"](#post-anfragen-geben-403-forbidden-zurück) unten) — sonst schlägt jede POST-Anfrage aus dem Compose-Frontend am CORS-Preflight fehl |
| `OPAA_INDEXING_DOCUMENT_PATH_HOST` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `./documents`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — `.env.docker.example` lässt die Variable deshalb bewusst auskommentiert; ohne Shell-Export gilt der Compose-Default `./documents` | Host-Pfad für Dokumente (in Container gemountet) |
| `OPAA_UPLOAD_STORAGE_PATH_HOST` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `./uploads`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `./uploads` | Host-Pfad für hochgeladene Dokumente (in Container gemountet) |
| `OPAA_UPLOAD_STORAGE_PATH` | `./uploads` | — (`docker-compose.yml` setzt sie im Backend-Container fest auf `/app/uploads`, nicht über `.env.docker` änderbar) | Container-interner Speicherpfad für hochgeladene Dokumente (`opaa.upload.storage-path`) — bei Docker Compose nicht mit dem Bind-Mount `OPAA_UPLOAD_STORAGE_PATH_HOST` zu verwechseln |
| `OPAA_UPLOAD_MAX_FILE_SIZE` | `52428800` (50 MiB, Byte) | nicht gesetzt (Anwendungs-Default gilt) | Maximale Dateigröße beim Dokument-Upload (`spring.servlet.multipart.max-file-size`/`max-request-size` und `opaa.upload.max-file-size` in `application.yml`, dieselbe Variable für beide). **Bei Docker Compose zusätzlich zu beachten:** Der nginx-Reverse-Proxy im Frontend-Container (`frontend/nginx.conf`) setzt `client_max_body_size` unabhängig davon fest auf `52m` — etwas oberhalb dieses Limits, weil nginx die gesamte Multipart-Anfrage misst (inklusive Framing-Overhead), das Backend dagegen nur die Dateigröße. Diese Datei wird beim Image-Build fest eingebacken (kein `envsubst`), wird also **nicht** automatisch aus `OPAA_UPLOAD_MAX_FILE_SIZE` übernommen. Wer `OPAA_UPLOAD_MAX_FILE_SIZE` erhöht, muss `client_max_body_size` in `frontend/nginx.conf` entsprechend mit anheben, sonst weist nginx größere Uploads bereits mit einer eigenen HTML-413-Seite ab, bevor die Backend-Prüfung überhaupt greift. Ein weiterer Reverse-Proxy vor dem Frontend-Container braucht denselben Wert zusätzlich (nginx-Default dort: 1 MB) |
| `OPAA_DOCUMENTS_REMOTE_CONTENT_MAX_BYTES` | `20971520` (20 MiB, Byte) | nicht gesetzt (Anwendungs-Default gilt) | Bytegrenze für `GET /api/v1/documents/{documentId}/content`, wenn es ein HTTP_DIRECTORY/RSS_FEED-Original von dessen Quelle durchstreamt — bewusst ein eigener, kleinerer Deckel statt `OPAA_UPLOAD_MAX_FILE_SIZE`: dieser Pfad ist ein synchroner, für jeden VIEWER erreichbarer Klickpfad, kein Hintergrund-Indizierungslauf |
| `OPAA_DOCUMENTS_REMOTE_CONTENT_TIMEOUT_SECONDS` | `20` | nicht gesetzt (Anwendungs-Default gilt) | Timeout je Anfrage (inklusive Redirect-Hops) für denselben Proxy-Abruf — deutlich kürzer als die 120 s, die ein Hintergrund-Indizierungslauf verwendet |
| `OPAA_DOCUMENTS_ATTACHMENT_EXTRACTION_MAX_CONCURRENT` | `4` | nicht gesetzt (Anwendungs-Default gilt) | Wie viele Anhangs-Nachextraktionen gleichzeitig **laufen** dürfen. Ein Anhangsdokument hat kein gespeichertes Original; es wird beim Öffnen aus dem Original seines Elterndokuments nachextrahiert — jeder Vorgang parst das Elterndokument und, bei Konnektor-Beständen, lädt es herunter. Abrufe desselben Elterndokuments laufen zusätzlich immer nacheinander. Wird der Deckel überschritten, antwortet der Abruf mit 429 und einer deutschen Meldung, statt unbegrenzt zu warten. Der Wert begrenzt die laufenden Extraktionen, **nicht** die Zahl gleichzeitig offener Antworten und damit nicht die Lebensdauer der geschriebenen temporären Dateien (die endet erst beim Schließen des Antwortstroms) — diese Obergrenze setzt das Rate-Limit dieses Endpunkts. Gültig 1–64; ein Wert außerhalb dieses Bereichs bricht den Start ab, statt still korrigiert zu werden |
| `OPAA_DOCUMENTS_ATTACHMENT_EXTRACTION_ACQUIRE_TIMEOUT` | `10s` (ISO-8601-Dauer oder Spring-Kurzform) | nicht gesetzt (Anwendungs-Default gilt) | Wie lange ein Abruf auf **jede** der beiden Schranken (Reihenfolge je Elterndokument, globaler Deckel) wartet, bevor er mit 429 abgewiesen wird — im ungünstigsten Fall also das Doppelte dieses Werts. Lang genug, dass eine übliche Klickfolge sich einreiht, kurz genug, dass kein Anfrage-Thread dauerhaft hinter einer langsamen Extraktion hängt. Muss positiv sein |
| **Datenbank** | | | |
| `OPAA_DB_URL` | `jdbc:postgresql://localhost:5432/opaa?prepareThreshold=0`; im Spring-Profil `docker` (ohne gesetzte Variable) stattdessen `jdbc:postgresql://postgres:5432/opaa?prepareThreshold=0` | nicht gesetzt (auskommentiert) — der `docker`-Profil-Default mit dem Hostnamen `postgres` gilt | JDBC-Verbindungs-URL. Der `bootRun`-Beispielwert (`localhost`) passt nur außerhalb von Docker Compose, deshalb lässt `.env.docker.example` die Variable bewusst auskommentiert — ein gesetzter Wert würde den `docker`-Profil-Default mit dem korrekten Hostnamen `postgres` überschreiben |
| `OPAA_DB_USERNAME` | `opaa` | `opaa` | PostgreSQL-Benutzername |
| `OPAA_DB_PASSWORD` | `opaa` | `opaa` | PostgreSQL-Passwort |
| `OPAA_DB_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `5432`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `5432` | Host-Port, auf den `docker-compose.yml` den PostgreSQL-Container bindet (nur `127.0.0.1`) |
| **LLM / Embedding** (ein einziger, openai-kompatibler Anbindungsweg — siehe [„LLM-Anbieter"](#llm-anbieter) unten) | | | |
| `OPAA_OPENAI_API_KEY` | `sk-placeholder` (Platzhalter, kein gültiger Schlüssel — greift nur, falls kein spezifischerer Schlüssel gesetzt ist; ein lokal betriebener Ollama-Server braucht keinen echten) | nicht gesetzt (auskommentiert) — der Anwendungs-Default (Platzhalter) gilt | Zugangsschlüssel der openai-kompatiblen Schnittstelle |
| `OPAA_OPENAI_BASE_URL` | `http://localhost:11434/v1`; im Spring-Profil `docker` stattdessen `http://ollama:11434/v1` | nicht gesetzt (auskommentiert) — der `docker`-Profil-Default gilt | Basis-Adresse der openai-kompatiblen Schnittstelle, gemeinsam für Chat und Einbettung. Zeigt ohne weitere Angabe auf einen lokal betriebenen Ollama-Server; ein **explizit leer gesetzter** Wert überschreibt diesen Default mit einer leeren Zeichenkette und lässt den Start abbrechen (siehe [„Erforderliche Variablen"](#erforderliche-variablen) oben). **Keine Anmeldedaten in der Adresse**: Eine Adresse der Form `https://benutzer:geheim@host/v1` lässt den Start ebenfalls abbrechen (`io.opaa.config.OpenAiBaseUrlGuard`) — eine Basis-Adresse landet in Log-Ausgaben und Statusanzeigen und gilt nirgends als geheim; der Zugangsschlüssel gehört in `OPAA_OPENAI_API_KEY` |
| `OPAA_OPENAI_CHAT_API_KEY` | — (kein eigener Default; fällt auf `OPAA_OPENAI_API_KEY` zurück, verschachtelt: `${OPAA_OPENAI_CHAT_API_KEY:${OPAA_OPENAI_API_KEY:sk-placeholder}}`) | nicht gesetzt (auskommentiert) — der Fallback auf `OPAA_OPENAI_API_KEY` gilt | **Nur noch Seed-Quelle für den ersten Start (siehe oben):** liefert den Zugangsschlüssel des anfänglichen `llm_models`-Eintrags; danach maßgeblich ist die Administrationsoberfläche |
| `OPAA_OPENAI_CHAT_BASE_URL` | — (kein eigener Default; fällt auf `OPAA_OPENAI_BASE_URL` zurück, verschachtelt: `${OPAA_OPENAI_CHAT_BASE_URL:${OPAA_OPENAI_BASE_URL:http://localhost:11434/v1}}`, im Profil `docker` mit `http://ollama:11434/v1` als innerstem Default) | nicht gesetzt (auskommentiert) | **Nur noch Seed-Quelle für den ersten Start (siehe oben):** liefert die Basis-Adresse des anfänglichen `llm_models`-Eintrags; danach maßgeblich ist die Administrationsoberfläche (siehe [„LLM-Anbieter"](#llm-anbieter) unten). Enthält der Wert Anmeldedaten (`https://benutzer:geheim@host/v1`), bricht der Start ab — wie bei `OPAA_OPENAI_BASE_URL`; Zugangsschlüssel gehören in `OPAA_OPENAI_CHAT_API_KEY` |
| `OPAA_OPENAI_CHAT_MODEL` | `phi3:mini` | `phi3:mini` | **Nur noch Seed-Quelle für den ersten Start (siehe oben):** liefert die Modell-Kennung des anfänglichen `llm_models`-Eintrags; danach maßgeblich ist die Administrationsoberfläche |
| `OPAA_OPENAI_CHAT_TEMPERATURE` | `0.7` | `0.7` | **Nur noch Seed-Quelle für den ersten Start (siehe oben)** |
| `OPAA_OPENAI_CHAT_MAX_TOKENS` | `2000` | `2000` | **Nur noch Seed-Quelle für den ersten Start (siehe oben)** |
| `OPAA_OPENAI_EMBEDDING_API_KEY` | — (kein eigener Default; fällt auf `OPAA_OPENAI_API_KEY` zurück, verschachtelt: `${OPAA_OPENAI_EMBEDDING_API_KEY:${OPAA_OPENAI_API_KEY:sk-placeholder}}`) | nicht gesetzt (auskommentiert) — der Fallback auf `OPAA_OPENAI_API_KEY` gilt | Eigener Zugangsschlüssel nur für den Embedding-Aufruf |
| `OPAA_OPENAI_EMBEDDING_BASE_URL` | — (kein eigener Default; fällt auf `OPAA_OPENAI_BASE_URL` zurück, verschachtelt: `${OPAA_OPENAI_EMBEDDING_BASE_URL:${OPAA_OPENAI_BASE_URL:http://localhost:11434/v1}}`, im Profil `docker` mit `http://ollama:11434/v1` als innerstem Default) | nicht gesetzt (auskommentiert) | Eigene Zieladresse nur für den Embedding-Aufruf, überschreibt `OPAA_OPENAI_BASE_URL` für diese eine Funktion (siehe [„LLM-Anbieter"](#llm-anbieter) unten). Der Hostname `ollama` im Default löst auf, sobald der Compose-Stack mit dem Profil `ollama` gestartet wird (`docker compose --profile ollama up`, siehe [„Lokal betriebenes Ollama im Compose-Stack"](#lokal-betriebenes-ollama-im-compose-stack) unten) — ohne dieses Profil und ohne anderweitig erreichbaren Ollama-Server schlagen Indizierung und Abfragen fehl (der Start selbst bricht nicht ab). Läuft Ollama stattdessen auf dem Host, `http://host.docker.internal:11434/v1` setzen (`extra_hosts` im `backend`-Service ist dafür bereits konfiguriert). Enthält der Wert Anmeldedaten (`https://benutzer:geheim@host/v1`), bricht der Start ab — die Adresse erscheint sonst im Meldungstext einer fehlgeschlagenen Einbettung samt Stacktrace im Log; Zugangsschlüssel gehören in `OPAA_OPENAI_EMBEDDING_API_KEY` |
| `OPAA_OPENAI_EMBEDDING_MODEL` | `nomic-embed-text` | `nomic-embed-text` | Embedding-Modellname |
| **Abfrage (RAG-Retrieval)** | | | |
| `OPAA_QUERY_TOP_K` | `8` | `8` | Endgültige Anzahl der für die Antwort ausgewählten Chunks, aus `OPAA_QUERY_FETCH_K` Kandidaten (1–100) |
| `OPAA_QUERY_FETCH_K` | `25` | `25` | Anzahl der Kandidaten, die die Vektorsuche selbst abruft, bevor MMR daraus `OPAA_QUERY_TOP_K` auswählt (1–200, muss ≥ `OPAA_QUERY_TOP_K` sein). Fehlt der Wert, während `OPAA_QUERY_TOP_K` bereits über 25 konfiguriert ist, normalisiert sich der Default auf `max(25, OPAA_QUERY_TOP_K)` statt auf ein starres `25` — sonst würde ein solches Bestandssystem allein durch diese Anhebung nicht mehr starten |
| `OPAA_QUERY_MMR_LAMBDA` | `1.0` | `1.0` | Abwägung zwischen Relevanz und Vielfalt bei der MMR-Auswahl aus `OPAA_QUERY_FETCH_K` Kandidaten (0,0–1,0) — `1,0` schaltet die Vielfaltsauswahl vollständig ab (reine Top-K-Relevanz) und ist bewusst der Default: Gegen 20 Mehrthemen-Golden-Fälle gemessen (beide erwarteten Dokumente unter den zurückgegebenen Chunks vertreten) erreichte `0,7` mit echten Chunk-Embeddings 19/20 Fälle, reines `topK=8` (dieser Default) 20/20 — MMR ist damit implementiert und per niedrigerem Wert aktivierbar, aber bewusst kein Default (siehe `QueryProperties#mmrLambda`) |
| `OPAA_QUERY_SIMILARITY_THRESHOLD` | `0.3` | `0.3` | Minimale Kosinus-Ähnlichkeit für Chunk-Aufnahme (0,0–1,0) |
| `OPAA_QUERY_PERMISSION_HISTORY_SAMPLE_RATE` | `1.0` | `1.0` | Anteil der Abfragen, für die der Abgleich der Live-Rechte gegen die historisierte Rechteformel tatsächlich läuft, als Wahrscheinlichkeit zwischen `0,0` und `1,0`. Standardmäßig läuft der Abgleich bei jeder Abfrage; drei Zusatz-Queries über wachsende Tabellen je Anfrage sind der Grund, den Wert für eine Betriebsumgebung mit hohem Abfrageaufkommen abzusenken |
| `OPAA_QUERY_DECOMPOSITION_ENABLED` | `true` | `true` | Zerlegt eine Frage vor dem Retrieval per LLM-Aufruf in bis zu `OPAA_QUERY_MAX_SUB_QUERIES` eigenständige Suchanfragen, je mit eigenem berechtigungs- und schwellenwertgeprüften `similaritySearch`-Aufruf, rangbasiert (Reciprocal Rank Fusion) zusammengeführt. Ein LLM-Fehlschlag oder eine unparsebare Antwort fällt auf die Ein-Suche-Logik zurück, nie auf einen Fehler; Modellwahl folgt dem systemweiten aktiven Chat-Modell (kein zusätzlicher API-Anbindungsweg) |
| `OPAA_QUERY_MAX_SUB_QUERIES` | `3` | `3` | Obergrenze der Teilfragen aus der Zerlegung — darüber hinaus kappt die Zerlegung, ohne die Zahl der `similaritySearch`-Aufrufe (und damit die Retrieval-Latenz) unbegrenzt wachsen zu lassen |
| `OPAA_QUERY_MAX_CHUNKS_PER_DOCUMENT` | `2` | `2` | Nach der Fusions-/MMR-Auswahl bevorzugt bis zu diese viele Chunks je bereits ausgewähltem Dokument aus der ohnehin berechtigungs- und schwellenwertgefilterten Kandidatenmenge — zweistufige Verdrängung: zuerst der schwächste Chunk eines Dokuments, das schon mit mindestens zwei Chunks vertreten ist; existiert keine solche Quelle, der auswahlrang-letzte Chunk der Gesamtauswahl, sofern das zu vervollständigende Dokument mit seinem besten Chunk strikt besser rankt als dieser (die Dokumentvielfalt darf dabei sinken) — auf `max(1, OPAA_QUERY_TOP_K / 4)` solcher Verdrängungen je Abfrage gedeckelt (bei Default `OPAA_QUERY_TOP_K=8` also 2), damit eine einzelne Abfrage nicht mehrere Themen zugunsten eines einzigen verdrängt. `1` schaltet die Dokument-Vervollständigung vollständig ab |
| `OPAA_QUERY_FULL_TEXT_SEARCH_ENABLED` | `true` | `true` | Ob der lexikalische Suchpfad seine Volltextabfrage ausführt und seine Trefferliste in die Ergebnis-Fusion einbringt (siehe [„Volltextsuche (lexikalischer Suchpfad)"](#volltextsuche-lexikalischer-suchpfad)). `false` spart die Abfrage und lässt die Suche rein vektoriell laufen — der Pfad erscheint dann weiterhin im Erklärprotokoll der Suche und weist sich dort als abgeschaltet aus. Der Wert wirkt unmittelbar auf die Antwort; auf der Verwaltungs-Evaldomäne kostet `false` gemessen 15 Prozentpunkte Hit Rate@5 (0,935 → 0,783) |
| `OPAA_RERANK_ENABLED` | `false` | `false` | **Der Schalter der Rerank-Modellrolle** — bewusst getrennt von den drei Endpunktangaben darunter: „Reranking aus" soll eine Aussage sein und nicht das ununterscheidbare Ergebnis einer vergessenen Konfigurationszeile. Steht er auf `true`, ohne dass `OPAA_RERANK_BASE_URL` und `OPAA_RERANK_MODEL` gesetzt sind, oder antwortet der Endpunkt nicht, meldet die Anwendung das beim Start als Fehler im Log und führt den Zustand danach fortlaufend abfragbar weiter; die Suche läuft in diesem Fall ohne Reranking weiter. Voreingestellt aus: Die Rolle ist gebaut, nicht aktiviert (siehe [„Reranking einschalten"](#reranking-einschalten)) |
| `OPAA_RERANK_BASE_URL` | — (leer) | nicht gesetzt | Basis-Adresse des Rerank-Endpunkts, ohne den Pfad `/rerank` (Beispiel: `http://reranker:80`). Erwartet wird ein Dienst, der `POST {Basis-Adresse}/rerank` beantwortet — vLLM, Text Embeddings Inference, Infinity, Jina, Cohere und Voyage tun das. Der Dienst muss mindestens `OPAA_QUERY_RERANK_CANDIDATE_COUNT` Dokumente je Anfrage annehmen (bei Text Embeddings Inference: `--max-client-batch-size`). **Keine Anmeldedaten in der Adresse**: Eine Adresse der Form `https://benutzer:geheim@host` wird abgelehnt und lässt die Rolle als unbelegt gelten (Startmeldung im Log, Zustand „unbelegt" auf der Seite „Suche & Indexierung"), weil die Basis-Adresse in Log und Statusanzeige erscheint; der Zugangsschlüssel gehört in `OPAA_RERANK_API_KEY` |
| `OPAA_RERANK_MODEL` | — (leer) | nicht gesetzt | Modell-Kennung, die mit jeder Rerank-Anfrage mitgeschickt wird (Beispiel: `BAAI/bge-reranker-v2-m3`). Zusammen mit der Basis-Adresse *belegt* sie die Rolle; fehlt eine der beiden, gilt die Rolle als unbelegt |
| `OPAA_RERANK_API_KEY` | — (leer) | nicht gesetzt | Optionaler Zugangsschlüssel. Er erscheint ausschließlich im `Authorization`-Header der Rerank-Anfrage — nie in einem Log, nie in einer Zustandsantwort, auch nicht gekürzt |
| `OPAA_RERANK_TIMEOUT` | `240s` | `240s` | Zeitbudget einer einzelnen Rerank-Anfrage — die Gesamtfrist von Verbindungsaufbau über Antwort-Header bis zum vollständig gelesenen Antwortkörper; ein Endpunkt, der nach den Headern beim Senden des Bodys stockt, kostet nicht mehr als dieses Budget. Läuft die Frist ab, behält die Suche die fusionierte Reihenfolge — ein langsamer Endpunkt kostet die Sortierung, nie die Antwort. Der Startwert trägt den CPU-Fall: gemessen rund drei Minuten je Frage für das Kandidatenfenster 50 auf einer 20-Kern-CPU mit `BAAI/bge-reranker-v2-m3`. Ein knapperer Wert meldet normale CPU-Latenz als `UNREACHABLE` statt als Langsamkeit — die Zustandsseite unterscheidet die beiden im `detail`-Text, aber nur, wenn der Endpunkt überhaupt die Chance bekommt, innerhalb des Budgets zu antworten. Eine Installation mit einem schnelleren (typischerweise GPU-gestützten) Endpunkt darf den Wert senken |
| `OPAA_QUERY_RERANK_CANDIDATE_COUNT` | `50` | `50` | Wie viele fusionierte Kandidaten die Rerank-Stufe bewertet, und zugleich das Budget, das MMR-Auswahl und Fusion für sie behalten, solange Reranking läuft (0–200; der Wert ist gegen die Verwaltungs-Evaldomäne gemessen, auch mit aktivem Ausfallwächter). `0` schaltet die Stufe über ihren eigenen Parameter ab, unabhängig von `OPAA_RERANK_ENABLED`. Der Wert 50 entspricht dem, was zwei Suchpfade bei `OPAA_QUERY_FETCH_K=25` je Teilfrage überhaupt liefern können — das Fenster deckt damit einen vollständigen Lauf des Auslieferungsstands mit einer Teilfrage ab. **Das Fenster vergrößert die Reichweite der Suche nicht:** Was keine Suchstufe zurückgibt, kann keine Fusion und kein Reranking heben. Die Reichweite ist `OPAA_QUERY_FETCH_K` je Liste mal der Zahl der Listen (Teilfragen mal aktive Suchpfade); diesen Wert anzuheben, ohne `OPAA_QUERY_FETCH_K` mit anzuheben, bringt deshalb nichts (siehe [„Reranking einschalten“](#reranking-einschalten)). Der Wert gehört zu den benchmark-gehärteten internen Defaults und nicht in eine Administrationsoberfläche |
| `OPAA_QUERY_METADATA_FILTER_DOCUMENT_TYPE_THRESHOLD` | `0.90` | `0.90` | Eintrittsbedingung des Kernfeld-Filters: der Füllstand (Anteil der indizierten Dokumente mit Wert oder „kein Wert ermittelbar" im Suchbereich der fragenden Person), ab dem die Filter-Oberfläche die Dokumentart anbietet (siehe [Metadaten](metadaten.md)). Vor der ersten Messung festgelegt; ein Feld darunter wird nicht angeboten, mit sichtbarer Begründung. Für Tests und bewusste Experimente überschreibbar, keine Verwaltungseinstellung |
| `OPAA_QUERY_METADATA_FILTER_DOCUMENT_DATE_THRESHOLD` | `0.75` | `0.75` | Dieselbe Eintrittsbedingung für Datum/Stand — niedriger, weil ein fehlendes Datum wegen der Leerwert-Regel nur Schärfe kostet, nie ein Dokument |
| `OPAA_QUERY_METADATA_FILTER_OPTIONS_CACHE_TTL` | `5m` | `5m` | Wie lange die Filteroptionen einer Person (Füllstand, vorkommende Werte) zwischengespeichert bleiben, bevor sie über den aktuellen Bestand neu gezählt werden. Begrenzt nur die Veraltung des Bestands: Eine Rechteänderung der Person verwirft ihren Eintrag sofort |
| **Indizierung** | | | |
| `OPAA_INDEXING_CHUNK_SIZE` | `1000` | `1000` | Ziel-Tokens pro Chunk (1–10.000) |
| `OPAA_INDEXING_CHUNK_OVERLAP` | `100` | nicht gesetzt (Anwendungs-Default gilt) | Anzahl der Tokens, die jeder Chunk vom Ende seines Vorgängers wiederholt, damit eine Aussage an einer Chunk-Grenze in mindestens einem Chunk vollständig erhalten bleibt. Muss kleiner als `OPAA_INDEXING_CHUNK_SIZE` sein; `0` deaktiviert die Überlappung, ein negativer Wert wird auf `0` normalisiert |
| `OPAA_INDEXING_BATCH_SIZE` | `50` | `50` | Chunks pro Embedding-API-Aufruf (1–1.000) |
| `OPAA_INDEXING_EMBEDDING_CONCURRENCY` | `3` | `3` | Maximale Anzahl gleichzeitiger Embedding-Aufrufe, mit denen ein einzelnes Dokument mit mehreren Chunks aufgeteilt wird, begrenzt über einen gemeinsamen, prozessweiten Pool (1–32). `1` entspricht exakt sequenziellem Verhalten (kein Aufteilen). Ein Dokument, dessen Chunks nicht aufgeteilt werden (z. B. nur ein Chunk), läuft weiterhin direkt auf dem aufrufenden Indizierungs-/Upload-Thread — dieser Pool ist daher keine Obergrenze für die Gesamtzahl gleichzeitiger Embedding-Aufrufe im Prozess, sondern nur für die Aufteilung eines einzelnen, mehr-chunkigen Dokuments (siehe Javadoc von `IndexingConfiguration#embeddingTaskExecutor`). Konservativer Default für CPU-gebundenes, lokal betriebenes Ollama (im Benchmark kaum Durchsatzgewinn über Concurrency 1 hinaus — die Embedding-Berechnung serialisiert dort intern). Bei einem netzwerk-/latenzgebundenen API- oder GPU-Backend, das gleichzeitige Anfragen tatsächlich parallel bedient, sind deutlich höhere Werte sinnvoll (8–16 im selben Benchmark) — keine automatische Erkennung, das Betriebsteam kennt sein Backend |
| `OPAA_INDEXING_THREAD_POOL_CORE_SIZE` | `2` | `2` | Kern-Threads für asynchrone Indizierung |
| `OPAA_INDEXING_THREAD_POOL_MAX_SIZE` | `4` | `4` | Maximale Threads für asynchrone Indizierung |
| `OPAA_INDEXING_THREAD_POOL_QUEUE_CAPACITY` | `20` | `20` | Task-Queue-Kapazität für asynchrone Indizierung |
| `OPAA_INDEXING_HTTP_USER_AGENT` | `OPAA-Indexer/1.0` | `OPAA-Indexer/1.0` | `User-Agent` jeder Anfrage an eine Quelle, die OPAA nicht selbst betreibt — RSS-Feed, Detailseiten und Anlagen, Webverzeichnis, Confluence, Verbindungstest (kein Browser-Faking). Löst `OPAA_INDEXING_RSS_USER_AGENT` und `OPAA_INDEXING_CONFLUENCE_USER_AGENT` ab (siehe [Migrationen](#migrationen-aus-älteren-ständen)) |
| `OPAA_INDEXING_HTTP_MAX_RATE_LIMIT_RETRIES` | `6` | `6` | Wie viele aufeinanderfolgende `429`-Antworten eine Anfrage des RSS-Feed- und des Webverzeichnis-Konnektors abwartet (jeweils nach `Retry-After`, sonst 5 Sekunden), bevor sie als gescheitert gilt — ein Lauf verlangsamt sich, statt Einträge zurückzustellen. `0` fällt auf den Standard 6 zurück; die Wartezeit lässt sich also nicht abschalten, nur über `OPAA_INDEXING_HTTP_MAX_RETRY_AFTER` verkürzen. Confluence hat dafür seinen eigenen Wert (`OPAA_INDEXING_CONFLUENCE_MAX_RATE_LIMIT_RETRIES`) |
| `OPAA_INDEXING_HTTP_MAX_RETRY_AFTER` | `2m` | `2m` | Obergrenze für eine einzelne aus `Retry-After` übernommene Wartezeit beim RSS-Feed- und Webverzeichnis-Konnektor; Confluence: `OPAA_INDEXING_CONFLUENCE_MAX_RETRY_AFTER` |
| `OPAA_INDEXING_HTTP_REQUEST_BUDGET_PER_RUN` | `0` | `0` | Anfragebudget je Lauf des RSS-Feed- und des Webverzeichnis-Konnektors: Anfragen einschließlich Wiederholungen nach `429`, bevor der Lauf **geordnet als „unvollständig, wird fortgesetzt"** endet — kein Fehler; der nächste Lauf sieht den Feed bzw. das Verzeichnis erneut. `0` (Standard) schaltet das Budget ab. Confluence (`OPAA_INDEXING_CONFLUENCE_REQUEST_BUDGET_PER_RUN`) und S3 (`OPAA_INDEXING_S3_REQUEST_BUDGET_PER_RUN`) behalten ihre eigenen Budgets |
| `OPAA_INDEXING_HTTP_MAX_RATE_LIMIT_WAIT_PER_RUN` | `15m` | `15m` | Deckel der Summe aller `429`-Wartezeiten eines Laufs beim RSS-Feed- und Webverzeichnis-Konnektor; eine Wartezeit, die den Deckel überschreiten würde, wird nicht mehr abgewartet, der Lauf endet **geordnet als „unvollständig, wird fortgesetzt"**. `0` fällt auf den Standard zurück. Confluence und S3 sind nicht betroffen |
| `OPAA_INDEXING_ATTACHMENTS_MAX_PER_PARENT` | `10` | `10` | Anhänge je Elternteil für einen künftigen Konnektor ohne eigene Grenze — derzeit ohne Wirkung: RSS (`OPAA_INDEXING_RSS_MAX_ATTACHMENTS_PER_ENTRY`) und Mail (`OPAA_INDEXING_MAIL_MAX_ATTACHMENTS_PER_MESSAGE`) bringen eigene Werte mit, Confluence übergibt je Aufruf genau einen Anhang |
| `OPAA_INDEXING_ATTACHMENTS_MAX_SIZE_BYTES` | `20971520` (20 MiB) | `20971520` | Obergrenze eines vom Anhangsweg selbst heruntergeladenen Anhangs für einen künftigen Konnektor ohne eigene Grenze — derzeit ohne Wirkung: RSS lädt mit `OPAA_INDEXING_RSS_MAX_ATTACHMENT_SIZE_BYTES`, Confluence mit `OPAA_INDEXING_CONFLUENCE_MAX_ATTACHMENT_SIZE_BYTES` |
| `OPAA_INDEXING_RSS_MAX_ENTRIES` | `200` | `200` | Max. Anzahl verarbeiteter RSS-Feed-Einträge je Lauf |
| `OPAA_INDEXING_RSS_MAX_FEED_SIZE_BYTES` | `10485760` | `10485760` | Max. Größe des abgerufenen RSS-Feeds in Byte |
| `OPAA_INDEXING_RSS_MAX_PAGE_SIZE_BYTES` | `5242880` | `5242880` | Max. Größe einer abgerufenen Detailseite in Byte |
| `OPAA_INDEXING_RSS_REQUEST_DELAY_MS` | `1000` | `1000` | Mindestabstand zwischen zwei Detailseiten-Abrufen in ms |
| `OPAA_INDEXING_RSS_MAIN_CONTENT_SELECTOR` | `main, article, [role=main]` | `main, article, [role=main]` | CSS-Selektor (Jsoup-Syntax) für den Hauptinhalt einer Detailseite, Fallback `<body>` |
| `OPAA_INDEXING_RSS_ATTACHMENT_PROFILE` | `GENERIC` | `GENERIC` | Anlagenprofil für RSS-Detailseiten: `GENERIC` oder `GSB` (Government Site Builder) — gilt für jeden RSS-Lauf dieser Installation, nicht je Lauf wählbar |
| `OPAA_INDEXING_RSS_MAX_ATTACHMENTS_PER_ENTRY` | `10` | `10` | Max. Anzahl heruntergeladener Anlagen je RSS-Eintrag |
| `OPAA_INDEXING_RSS_MAX_ATTACHMENT_SIZE_BYTES` | `20971520` | `20971520` | Max. Größe einer einzelnen RSS-Anlage in Byte |
| `OPAA_INDEXING_CRAWL_MAX_DEPTH` | `10` | `10` | Maximale Rekursionstiefe eines `HTTP_DIRECTORY`-Crawls — die Wurzel liegt auf Tiefe 0, ein Crawl besucht also die Tiefen 0 bis einschließlich `max-depth`. Bricht einen Zyklus, der nie dieselbe URL zweimal erzeugt (z. B. eine Symlink-Schleife mit wachsendem Pfad) |
| `OPAA_INDEXING_CRAWL_MAX_ENTRIES` | `5000` | `5000` | Maximale Anzahl gesammelter Dateien **und** besuchter Verzeichnisse je `HTTP_DIRECTORY`-Crawl, bevor abgeschnitten wird (geloggt, kein Fehler) — begrenzt auch einen reinen Verzeichnis-Symlink-Zyklus, den die Tiefenbegrenzung allein nur mit bis zu `Verzweigungsfaktor^max-depth` Anfragen stoppen würde |
| `OPAA_INDEXING_CRAWL_MAX_FILE_SIZE_BYTES` | `104857600` (100 MiB) | `104857600` | Bytegrenze für einen einzelnen Eintrag eines `HTTP_DIRECTORY`-Laufs. Wird beim Übertragen erzwungen, nicht danach: Ein größerer Eintrag wird abgebrochen, bevor die überschüssigen Bytes auf der temporären Partition landen, als Ablehnung im Laufprotokoll vermerkt und als übersprungen gezählt — der Lauf läuft weiter. Gilt auch für den Nachlade-Weg unaufgelöster OLE2-Container. Bewusst unterhalb des `markLimit` von Tikas POIFS-Erkennung (128 MiB), jenseits dessen ein solcher Eintrag ohnehin abgewiesen würde |
| `OPAA_INDEXING_CONFLUENCE_PAGE_SIZE` | `100` | `100` | Seitengröße (`limit`) jeder Auflistung der Confluence-Zugriffsschicht (siehe [Konnektor Confluence](konnektor-confluence.md)) — Spaces, Seiten, Anhänge, Änderungssuche; Cloud kappt bei 250, Data Center bei 200. Die Adapter folgen der tatsächlich gelieferten Seitengröße |
| `OPAA_INDEXING_CONFLUENCE_REQUEST_TIMEOUT` | `30s` | `30s` | Zeitlimit je JSON-Anfrage an die Confluence-API (Anhangs-Downloads laufen über das feste 120-Sekunden-Limit des gemeinsamen `BoundedDownloader`) |
| `OPAA_INDEXING_CONFLUENCE_DETECTION_TIMEOUT` | `10s` | `10s` | Zeitlimit je Sonde der zugangsdatenfreien Editionserkennung — sie läuft auch bei der Anlage einer Confluence-Bibliothek und ist deshalb bewusst kürzer als das allgemeine Anfrage-Limit |
| `OPAA_INDEXING_CONFLUENCE_MAX_RATE_LIMIT_RETRIES` | `6` | `6` | Wie viele aufeinanderfolgende `429`-Antworten eine Anfrage abwartet (jeweils nach `Retry-After`, sonst 5 Sekunden), bevor sie als ratenbegrenzt scheitert — ein Lauf verlangsamt sich, statt abzubrechen. Andere Fehlerstatus (etwa `503` im Wartungsfenster) werden nicht wiederholt, sondern als Fehler gemeldet |
| `OPAA_INDEXING_CONFLUENCE_MAX_RETRY_AFTER` | `2m` | `2m` | Obergrenze für eine einzelne aus `Retry-After` übernommene Wartezeit |
| `OPAA_INDEXING_CONFLUENCE_MAX_RESPONSE_BYTES` | `10485760` (10 MiB) | `10485760` | Obergrenze für eine einzelne JSON-Antwort der Confluence-API |
| `OPAA_INDEXING_CONFLUENCE_MAX_ATTACHMENT_SIZE_BYTES` | `20971520` (20 MiB) | `20971520` | Obergrenze für einen einzelnen Anhangs-Download aus Confluence |
| `OPAA_INDEXING_CONFLUENCE_MAX_LISTING_PAGES` | `500` | `500` | Wie viele Seiten eine einzelne Auflistung (Spaces, Seiten eines Space, Anhänge, Änderungssuche) höchstens durchblättert, bevor sie als unbegrenzt abgebrochen wird — ein sichtbarer Fehler, kein stilles Abschneiden, damit ein unvollständiger Vollabgleich nie als vollständig gilt |
| `OPAA_INDEXING_CONFLUENCE_FULL_SYNC_INTERVAL` | `7d` | `7d` | Abstand, in dem eine Confluence-Bibliothek statt eines inkrementellen Abgleichs wieder einen **Vollabgleich** läuft: nur der Vollabgleich vollzieht Löschungen, die Confluence nie meldet — verlängerbar, nicht abschaltbar. Instanzweite Vorgabe; eine Bibliothek kann im Zeitplan-Dialog einen eigenen Rhythmus (1 bis 365 Tage) setzen. Ist der letzte Vollabgleich älter oder gab es noch keinen abgeschlossenen (auch nach jeder Änderung der Space-Auswahl), wählt der nächste geplante oder manuelle Lauf ohne ausdrückliche Betriebsart den Vollabgleich |
| `OPAA_INDEXING_CONFLUENCE_INCREMENTAL_OVERLAP` | `10m` | `10m` | Überlappung nach hinten, mit der der inkrementelle Abgleich vom Anker des letzten Laufs sucht. Das Fenster geht als `lastmodified >= now("-Nm")` an die Instanz, die es in ihrer eigenen Uhr und Zeitzone auswertet; die Überlappung fängt den verbleibenden Uhrenversatz zwischen OPAA und Instanz, die Minutengenauigkeit von CQL und Änderungen während des vorigen Laufs ab. Eine erneut gefundene, unveränderte Seite kostet nur einen Auflistungseintrag — die Version kommt mit der Suche, der Body wird nicht geholt. `0` fällt auf den Standard zurück |
| `OPAA_INDEXING_CONFLUENCE_REQUEST_BUDGET_PER_RUN` | `50000` | `50000` | Anfragebudget je Lauf: Wie viele Aufrufe ein Lauf an die Instanz richten darf (Wiederholungen nach `429` und Anhangs-Downloads eingerechnet; Verbindungstest und Editionserkennung sind nicht betroffen), bevor er **geordnet als „unvollständig, wird fortgesetzt“** endet — kein Fehler; der nächste Lauf setzt fort (Vollabgleich bei den offenen Spaces, wobei bereits gespeicherte Seiten keinen Aufruf kosten; inkrementeller Abgleich mit demselben Fenster). Gilt für beide Editionen: Cloud rechnet mit einem Punktebudget und drosselt mit `429`, Data Center hat keine eingebaute Grenze und würde sonst schlicht dauerbeschäftigt. Gemessen gegen ein echtes Data Center: eine Seite kostet zwei Aufrufe (Body, Anhangsliste) plus einen je Anhang plus einen Auflistungsaufruf je 100 Seiten — 50 000 Aufrufe decken rund 20 000 neue oder geänderte Seiten je Lauf. Ein Lauf, der trotz erschöpftem Budget nichts aufnehmen konnte, meldet das als Fehler; dann Budget anheben oder Auswahl aufteilen. `0` schaltet das Budget ab |
| `OPAA_INDEXING_S3_LIST_PAGE_SIZE` | `1000` | `1000` | `MaxKeys` je `ListObjectsV2`-Aufruf der S3-Zugriffsschicht (siehe [Konnektor S3](konnektor-s3.md)) — höchstens 1000, S3 kappt dort; die Auflistung folgt dem Fortsetzungstoken seitenweise |
| `OPAA_INDEXING_S3_MAX_OBJECT_SIZE_BYTES` | `52428800` (50 MiB) | `52428800` | Obergrenze für ein einzelnes Objekt: Vorfilter auf die gelistete Größe und Byte-Grenze beim Kopieren des Streams — ein größeres Objekt wird abgebrochen, ohne dass die Teildatei übernommen wird |
| `OPAA_INDEXING_S3_REQUEST_TIMEOUT` | `30s` | `30s` | Zeitlimit je Aufruf; Verbindungs-, Lese- und Versuchs-Timeout werden alle aus diesem einen Wert abgeleitet |
| `OPAA_INDEXING_S3_MAX_RETRIES` | `5` | `5` | Wie oft ein Aufruf nach `503 SlowDown`/`429` oder einem vorübergehenden Transportfehler mit exponentiellem Backoff wiederholt wird, bevor er als ratenbegrenzt scheitert — gezählt je Lauf, nie stillschweigend. `0` schaltet Wiederholungen ab |
| `OPAA_INDEXING_S3_RETRY_BACKOFF` | `500ms` | `500ms` | Basis des exponentiellen Backoffs zwischen Wiederholungen (Obergrenze 20 Sekunden) |
| `OPAA_INDEXING_S3_REQUEST_BUDGET_PER_RUN` | `20000` | `20000` | Anfragebudget je Lauf: Aufrufe einschließlich Wiederholungen und `HeadObject` für endungslose Schlüssel, bevor der Lauf **geordnet als „unvollständig, wird fortgesetzt“** endet; Verbindungstest und Bucket-Auflistung sind nicht betroffen. Rechnung: ein Aufruf je 1000 gelistete Objekte plus einer je geändertem Objekt — 20 000 decken eine Million unveränderter Objekte mit Endung und rund 18 000 Downloads. `0` schaltet das Budget ab. Der Vorgabewert ist ein Ausgangswert aus dem Entwurf, nicht gegen einen realen Objektspeicher kalibriert |
| `OPAA_INDEXING_S3_MAX_OBJECTS_PER_RUN` | `1000000` | `1000000` | Gelistete Objekte je Lauf über alle Geltungsbereiche, bevor der Lauf **sichtbar als Fehler** endet („Geltungsbereiche enger fassen“) — eine Notbremse für Arbeitsspeicher und Laufzeit (jeder gelistete Pfad wird bis zur Bereinigung gehalten), keine Regelgrenze; nicht zu verwechseln mit dem Anfragebudget, das den Lauf geordnet als unvollständig beendet |
| `OPAA_INDEXING_S3_DOWNLOAD_CONCURRENCY` | `2` | `2` | Gleichzeitige Objekt-Downloads je Lauf, während die Auflistung weiterläuft — eine Obergrenze für die Last auf dem Objektspeicher, kein Durchsatzziel; `1` lädt seriell. Die Dokumentstrecke selbst verarbeitet die geladenen Objekte weiterhin eines nach dem anderen in Auflistungsreihenfolge; nur ein ohne Download übersprungenes Objekt steht im Protokoll, sobald es angetroffen wird |
| `OPAA_INDEXING_S3_TEMP_DIRECTORY` | Temp-Verzeichnis der JVM (`java.io.tmpdir`) | nicht gesetzt | Verzeichnis, in das ein Objekt vor der Übergabe an die Dokumentstrecke geladen wird (Datei wird nach der Verarbeitung gelöscht). Kein Eintrag in `application.yml`; die Variable bindet über Spring Boots Relaxed Binding an `opaa.indexing.s3.temp-directory` |
| `OPAA_INDEXING_EVENTS_DEBOUNCE` | `5s` | `5s` | Sammelzeit des gemeinsamen Ereigniseingangs aller Push-Wege ([Confluence-Webhook](konnektor-confluence.md), [S3-Ereignisse](konnektor-s3.md)): Benachrichtigungen zu einer Bibliothek werden so lange gesammelt und dann in **einem** gezielten Lauf geprüft — fünf Speichervorgänge in einer Minute oder ein Skript, das fünfzig Objekte hochlädt, kosten einen Lauf. Löst `OPAA_INDEXING_CONFLUENCE_WEBHOOK_DEBOUNCE` und `OPAA_INDEXING_S3_EVENTS_DEBOUNCE` ab (siehe [Migrationen](#migrationen-aus-älteren-ständen)) |
| `OPAA_INDEXING_EVENTS_MAX_PENDING_KEYS` | `500` | `500` | Höchstzahl verschiedener gemeldeter Schlüssel (Seitenkennungen, Objektschlüssel) je Stapel; darüber läuft statt gezielter Einzelprüfungen der gewöhnliche Lauf des Konnektors — bei Confluence in der Betriebsart, die der Zustand der Bibliothek vorgibt, bei S3 der Vollabgleich (ein Massenimport listet billiger, als er einzeln prüft). Löst `OPAA_INDEXING_CONFLUENCE_WEBHOOK_MAX_PENDING_PAGES` und `OPAA_INDEXING_S3_EVENTS_MAX_PENDING_KEYS` ab |
| `OPAA_INDEXING_EVENTS_MAX_DEFERRALS` | `120` | `120` | Wie oft ein wartender Stapel um eine weitere Sammelzeit verschoben wird, weil für die Bibliothek gerade ein Lauf läuft, bevor er verworfen wird — der nächste Lauf deckt dieselben Schlüssel ab, ein Verwerfen kostet Aktualität, nie Richtigkeit (Standard: zehn Minuten). Löst `OPAA_INDEXING_CONFLUENCE_WEBHOOK_MAX_DEFERRALS` und `OPAA_INDEXING_S3_EVENTS_MAX_DEFERRALS` ab |
| `OPAA_INDEXING_FILESYSTEM_ALLOWLIST` | — (leer; Profil `dev`: `/data,/tmp`) | nicht gesetzt (auskommentiert; Beispielwert `/srv/opaa/documents`) | Absolute Basisverzeichnisse, unter denen der `sourcePath` einer FILESYSTEM-Bibliothek liegen muss (kommagetrennt, siehe [Konnektor Dateisystem](konnektor-filesystem.md)). Eine leere Allowlist deaktiviert den Quellentyp FILESYSTEM vollständig — sie ist die eigentliche Sicherung, nicht die Anlage-Berechtigung. Wird bei Anlage, Änderung **und** jedem Lauf geprüft, da die Allowlist nachträglich verengt werden kann. URL-basierte Quellentypen (HTTP_DIRECTORY, RSS_FEED) sind hiervon nicht erfasst — dafür siehe `OPAA_INDEXING_TARGET_VALIDATION_*` unten. **Betriebsbedingung Symlinks:** Symlinks auf Dateien innerhalb eines freigegebenen Verzeichnisses werden mitindiziert (`Files::isRegularFile` folgt Links) — freigegebene Verzeichnisse dürfen deshalb nicht durch Endnutzer beschreibbar sein. |
| `OPAA_INDEXING_TARGET_VALIDATION_ENABLED` | `true` | `true` | Ob `HTTP_DIRECTORY`/`RSS_FEED`/`CONFLUENCE`-Abrufe (Indizierungsläufe **und** der Verbindungstest) ein Ziel ablehnen, dessen aufgelöste Adresse Loopback, Link-Local, privat oder anderweitig nicht routbar ist (SSRF-Härtung). Vor dem ersten Abruf **und** nach jeder Weiterleitung geprüft. Standardmäßig aktiv — ein Betrieb mit legitimer interner Dokumentenquelle schaltet bewusst ab, kein stillschweigender Permissiv-Modus. |
| `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` | — (leer) | nicht gesetzt (auskommentiert; Beispielwert `intranet.example.org`) | Hostnamen (kommagetrennt, exakter Vergleich ohne Groß-/Kleinschreibung), die von der Zielprüfung oben ausgenommen sind, auch während sie aktiv ist — erlaubt konkrete interne Quellen zu benennen, ohne die Prüfung für jedes andere Ziel abzuschalten. Für einen `S3`-Objektspeicher im Haus (MinIO, Ceph) gehört der Endpoint-Host hinein, unter Virtual-Host-Adressierung zusätzlich jeder Bucket-Host `<bucket>.<endpoint-host>` ([Konnektor S3](konnektor-s3.md), Abschnitt 4.1); der Demo-Stack nennt so seinen `minio`-Service. Ein selbst betriebenes Confluence Data Center steht typischerweise in einem privaten Adressbereich und braucht diesen Eintrag; die Fehlermeldung der Confluence-Zugriffsschicht nennt ihn ([Konnektor Confluence](konnektor-confluence.md)). |
| `OPAA_INDEXING_STALE_JOB_TIMEOUT` | `4h` (als `${OPAA_INDEXING_STALE_JOB_TIMEOUT:4h}`-Platzhalter in `application.yml`, deckungsgleich mit dem Java-Default `PT4H` in `IndexingProperties`) | nicht gesetzt (Anwendungs-Default gilt) | Wie lange ein Lauf `RUNNING` bleiben darf, ohne dass sein Fortschritts-Heartbeat sich bewegt, bevor er als verwaist gilt und automatisch auf `FAILED` gesetzt wird — schützt vor Läufen, die durch eine verworfene `@Async`-Aufgabe oder einen abgestürzten Prozess dauerhaft `RUNNING` bleiben und damit ihre Bibliothek auf Dauer sperren würden (jeder weitere Anstoß derselben Bibliothek antwortet 409, solange die Zeile `RUNNING` ist). Ein tatsächlich aktiver Lauf eines großen Bestands bleibt unangetastet, solange er weiter Fortschritt meldet, auch über diese Zeitspanne hinaus. Wird beim Anwendungsstart (alle `RUNNING`-Zeilen gelten dann als verwaist) und danach periodisch geprüft. **Setzt genau eine Backend-Instanz voraus:** Startup-Recovery und periodischer Sweep kennen nur die `indexing_jobs`-Zeilen der eigenen Datenbank, nicht welcher Prozess sie tatsächlich noch bearbeitet — bei einem Rolling-Deployment oder einer zweiten Replik würde eine Instanz die noch laufenden Jobs der anderen als verwaist erkennen und abbrechen. |
| **Dokument-Upload** | | | |
| `OPAA_UPLOAD_THREAD_POOL_CORE_SIZE` | `2` | `2` | Kern-Threads für die asynchrone Verarbeitung hochgeladener Dokumente — eigener Pool, unabhängig von `OPAA_INDEXING_THREAD_POOL_*` |
| `OPAA_UPLOAD_THREAD_POOL_MAX_SIZE` | `4` | `4` | Maximale Threads für die asynchrone Verarbeitung hochgeladener Dokumente |
| `OPAA_UPLOAD_THREAD_POOL_QUEUE_CAPACITY` | `20` | `20` | Task-Queue-Kapazität für den Upload-Pool — bei voller Queue wird der Upload sofort mit Status `FAILED` beantwortet, statt die Aufgabe still zu verwerfen |
| `OPAA_UPLOAD_PENDING_RECOVERY_THRESHOLD_MINUTES` | `30` | `30` | Minuten, nach denen ein noch `PENDING` hängender Upload beim nächsten Anwendungsstart als durch einen Neustart abgebrochen auf `FAILED` gesetzt wird |
| **Bibliothek** | | | |
| `OPAA_LIBRARY_QUOTA_BYTES` | `10737418240` (10 GiB, Byte) | `10737418240` | Speicherkontingent je Wissensbibliothek — Summe der `file_size`-Spalte aller Dokumente einer Bibliothek, durchgesetzt am Upload-Endpunkt (413) **und** an allen vier Konnektorpfaden (FILESYSTEM/HTTP_DIRECTORY/RSS_FEED/CONFLUENCE, dort als übersprungenes Dokument mit `REJECTED`-Ereignis im Laufprotokoll). Zählt den *Bibliotheksinhalt* (die Größe der Quelldateien), nicht den von OPAA tatsächlich belegten Plattenplatz — bei HTTP_DIRECTORY/RSS_FEED liegen die Dateien nur temporär auf der Platte, OPAA behält dauerhaft nur die Chunks im Vektorspeicher; ein Betreiber sieht deshalb ggf. „10 GiB belegt", obwohl der eigene Plattenverbrauch deutlich kleiner ist. **`0` oder ein negativer Wert deaktiviert das Kontingent vollständig** (kein Rückfall auf den Default) — wichtig für Bestandsinstallationen mit Bibliotheken über 10 GiB: das Kontingent wirkt rückwirkend auf bereits gewachsene Bibliotheken, ein Update auf diese Version würde dort sonst jeden weiteren Upload und jedes weitere Konnektordokument ablehnen, bis die Bibliothek unter das Kontingent geschrumpft ist. |
| **pgvector** | | | |
| `OPAA_PGVECTOR_DIMENSIONS` | `1536` | `768` | Vektor-Dimensionen (muss mit Embedding-Modell übereinstimmen). **Abweichend vom Anwendungs-Default gesetzt:** Dieser Compose-Stack setzt `OPAA_OPENAI_EMBEDDING_MODEL` standardmäßig auf `nomic-embed-text` (768 Dimensionen) — beim Anwendungs-Default `1536` bricht die erste Einbettung mit einem Dimensionsfehler ab. Wer das Embedding-Modell wechselt, muss diesen Wert mitziehen |
| `OPAA_PGVECTOR_DISTANCE_TYPE` | `cosine_distance` | `cosine_distance` | Distanzfunktion für Ähnlichkeitssuche |
| **Rate Limiting** | | | |
| `OPAA_RATE_LIMIT_ENABLED` | `true` | `true` | Rate Limiting aktivieren/deaktivieren |
| `OPAA_RATE_LIMIT_QUERY_MAX_REQUESTS` | `10` | `10` | Max. Abfrageanfragen pro IP pro Fenster |
| `OPAA_RATE_LIMIT_QUERY_WINDOW_SECONDS` | `60` | `60` | Abfrage-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_QUERY_GLOBAL_MAX_REQUESTS` | `100` | `100` | Max. Abfrageanfragen über alle IPs pro Fenster |
| `OPAA_RATE_LIMIT_INDEXING_MAX_REQUESTS` | `1` | `1` | Max. Indizierungsanfragen pro IP pro Fenster |
| `OPAA_RATE_LIMIT_INDEXING_WINDOW_SECONDS` | `60` | `60` | Indizierungs-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_INDEXING_GLOBAL_MAX_REQUESTS` | `5` | `5` | Max. Indizierungsanfragen über alle IPs pro Fenster |
| `OPAA_RATE_LIMIT_SOURCE_TEST_MAX_REQUESTS` | `10` | nicht gesetzt (Anwendungs-Default gilt) | Max. Verbindungstests (`POST /api/v1/libraries/source-test`) pro IP pro Fenster |
| `OPAA_RATE_LIMIT_SOURCE_TEST_WINDOW_SECONDS` | `60` | nicht gesetzt (Anwendungs-Default gilt) | Verbindungstest-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_SOURCE_TEST_GLOBAL_MAX_REQUESTS` | `30` | nicht gesetzt (Anwendungs-Default gilt) | Max. Verbindungstests über alle IPs pro Fenster |
| `OPAA_RATE_LIMIT_DOCUMENT_CONTENT_MAX_REQUESTS` | `20` | nicht gesetzt (Anwendungs-Default gilt) | Max. Aufrufe von `GET /api/v1/documents/{documentId}/content` pro IP pro Fenster — der synchrone Proxy-Abruf für HTTP_DIRECTORY/RSS_FEED-Originale hat dieselbe outbound-Verbindungs-Charakteristik wie der Verbindungstest oben, ist aber routinemäßig jedem VIEWER erreichbar |
| `OPAA_RATE_LIMIT_DOCUMENT_CONTENT_WINDOW_SECONDS` | `60` | nicht gesetzt (Anwendungs-Default gilt) | Content-Endpunkt-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_DOCUMENT_CONTENT_GLOBAL_MAX_REQUESTS` | `100` | nicht gesetzt (Anwendungs-Default gilt) | Max. Aufrufe von `GET /api/v1/documents/{documentId}/content` über alle IPs pro Fenster |
| `OPAA_RATE_LIMIT_WEBHOOK_MAX_REQUESTS` | `120` | nicht gesetzt (Anwendungs-Default gilt) | Max. Aufrufe von `POST /api/v1/libraries/{libraryId}/confluence-webhook` **und** `…/s3-events` pro IP **und Bibliothek** pro Fenster — beide Eingänge sind ohne Sitzung erreichbar, das Limit begrenzt die Signaturprüfungen, die ein Unbekannter auslösen kann; der Eingang sammelt ohnehin, ein Überschreiten kostet Aktualität, keine Korrektheit |
| `OPAA_RATE_LIMIT_WEBHOOK_WINDOW_SECONDS` | `60` | nicht gesetzt (Anwendungs-Default gilt) | Webhook-Rate-Limit-Fenster in Sekunden |
| `OPAA_RATE_LIMIT_WEBHOOK_GLOBAL_MAX_REQUESTS` | `600` | nicht gesetzt (Anwendungs-Default gilt) | Max. Webhook-Aufrufe über alle IPs pro Fenster |
| **Verzeichnis-Synchronisation (Gruppen)** | | | |
| `OPAA_DIRECTORY_SYNC_CHANGE_THRESHOLD_FRACTION` | `0.3` | nicht gesetzt (Anwendungs-Default gilt) | Plausibilitätsschwelle: Würde ein Synchronisationslauf mehr als diesen Anteil der bestehenden Gruppenmitgliedschaften entfernen, wird er verworfen und gemeldet statt angewendet — Schutz vor einer fehlkonfigurierten Verzeichnisquelle, die scheinbar fast alle Mitgliedschaften löscht. Gemessen ausschließlich an Entfernungen, nicht an Hinzufügungen. Muss echt größer als `0` und höchstens `1` sein — ein ungültiger Wert lässt den Start fehlschlagen, statt sich stillschweigend zu lockern |
| **Authentifizierung** | | | |
| `SPRING_PROFILES_ACTIVE` | ohne Angabe ist das Spring-Profil `local` aktiv (`spring.profiles.default: local` in `application.yml`) — das enthält aber weder `oidc` noch `dev`, sodass `io.opaa.auth.AuthProfileGuard` den Start trotzdem mit einer Fehlermeldung abbricht | `docker,dev` | Muss `oidc` (Betrieb) oder `dev` (Entwicklung/Tests) enthalten; ohne eines der beiden startet das Backend nicht — das gilt für den Auth-Modus, nicht für das Spring-Profil an sich, das auch ohne Angabe einen Wert (`local`) hat. Für Betrieb mit dem gebündelten Keycloak stattdessen `docker,oidc` setzen (siehe [„OIDC (Keycloak)"](#oidc-keycloak) unten) |
| `OPAA_INITIAL_ADMIN_EMAIL` | `admin@opaa.local` | `admin@opaa.local` | E-Mail für den automatisch erstellten initialen Admin-Benutzer; greift nur beim ersten Anmelden dieser Adresse und nur über den Standardanbieter (im `dev`-Modus: den Dev-Issuer), siehe [„Anbieterverwaltung"](#anbieterverwaltung) unten |
| **Entwicklungs-Auth (`dev`)** | | | |
| `OPAA_AUTH_DEV_ISSUER` | `opaa-dev` | `opaa-dev` | Issuer-Claim der synthetischen Tokens |
| `OPAA_AUTH_DEV_DEFAULT_USER` | `dev-admin` | `dev-admin` | Nutzer, als der ohne `X-OPAA-Dev-User`-Header authentifiziert wird — `.env.docker.example` setzt diesen Block, weil `docker,dev` der Compose-Standardfall ist, und erklärt dort auch die vorkonfigurierten Nutzer |
| **Zugangsdaten-Verschlüsselung** | | | |
| `OPAA_CREDENTIALS_ENCRYPTION_KEY` | — (leer) außerhalb des Profils `dev`; im Profil `dev` fest hinterlegter, **ausdrücklich nicht produktionstauglicher** Schlüssel (siehe [„Zugangsdaten-Verschlüsselung"](#zugangsdaten-verschlüsselung) unten) | nicht gesetzt (auskommentiert — bewusst, siehe Kommentar in `.env.docker.example`) | Base64-kodierter AES-256-Schlüssel (32 rohe Byte) zur Verschlüsselung von `knowledge_libraries.source_credentials` ruhend in der Datenbank. **Ohne Voreinstellung außerhalb des Profils `dev`; erforderlich, sobald eine Bibliothek mit Zugangsdaten gespeichert wird** |
| `OPAA_SETTINGS_ENCRYPTION_KEY` | — (leer) außerhalb des Profils `dev`; im Profil `dev` fest hinterlegter, **ausdrücklich nicht produktionstauglicher** Schlüssel (siehe [„Verschlüsselung der Zugangsschlüssel verwalteter Chat-Modelle"](#verschlüsselung-der-zugangsschlüssel-verwalteter-chat-modelle) unten) | nicht gesetzt (auskommentiert — bewusst, siehe Kommentar in `.env.docker.example`) | Base64-kodierter AES-256-Schlüssel (32 rohe Byte) zur Verschlüsselung von `llm_models.api_key_ciphertext` ruhend in der Datenbank. **Ohne Voreinstellung außerhalb des Profils `dev`; erforderlich, sobald ein Chat-Modell mit Zugangsschlüssel gespeichert wird — der Start selbst bricht ohne ihn nicht ab** |
| **OIDC** | | | |
| `OPAA_OIDC_JWK_SET_URI` | — (leer) | `http://keycloak:8180/realms/opaa/protocol/openid-connect/certs` | **Bootstrap-Wert** (siehe [„Bestandsübernahme"](#bestandsübernahme-aus-opaa_oidc_) unten): Backend-seitige JWK-Set-Adresse des ersten Anbieters, den OPAA beim ersten Start im `oidc`-Modus einmalig als Standardanbieter „Verzeichnisdienst" in die Anbieterverwaltung übernimmt. Der Backend-Container muss den Docker-internen Hostnamen `keycloak` verwenden, siehe [„OIDC (Keycloak)"](#oidc-keycloak) unten. Bleibt danach als Notanker gesetzt (`OPAA_OIDC_BOOTSTRAP=force`) und ist immer als Adresse erlaubt |
| `OPAA_OIDC_ISSUER_URI` | — (leer) | `http://localhost:8180/realms/opaa` | Bootstrap-Wert: Issuer des ersten Anbieters — bleibt `localhost`, weil der Browser diese URL verwendet. Ohne diesen Wert legt der erste Start keinen Anbieter an und protokolliert einen Fehler; keine Anmeldung möglich |
| `OPAA_OIDC_AUTHORITY` | — (leer) | `http://localhost:8180/realms/opaa` | Ohne Wirkung: Die Anmeldeseite liest die Anbieter aus der Datenbank (`GET /api/v1/auth/config`), die Authority ist die Issuer-URI der Anbieterzeile. Beim Bootstrap wird eine Abweichung von `OPAA_OIDC_ISSUER_URI` nur protokolliert; die Variable kann entfallen |
| `OPAA_OIDC_CLIENT_ID` | — (leer) | `opaa-frontend` | Bootstrap-Wert: Client-ID des ersten Anbieters (Public Client mit PKCE) |
| `OPAA_OIDC_BOOTSTRAP` | — (leer) | nicht gesetzt | `force` stellt den Anbieter aus den `OPAA_OIDC_*`-Werten einmalig wieder her (aktiviert, Standard, Werte überschrieben) — der Weg zurück aus einer vertippten Issuer-URI des einzigen Anbieters. Danach wieder entfernen |
| `OPAA_OIDC_TARGET_VALIDATION_ENABLED` | `true` | `true` | Adressprüfung (SSRF) für Issuer- und JWK-Set-Adressen, die eine Systemverwaltung in der Anbieterverwaltung eingibt — getrennt von `OPAA_INDEXING_TARGET_VALIDATION_*` |
| `OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST` | — (leer) | nicht gesetzt | Kommagetrennte Hostnamen weiterer interner Identitätsanbieter. Die beiden Adressen aus `OPAA_OIDC_ISSUER_URI`/`OPAA_OIDC_JWK_SET_URI` (Schema, Host, Port) sind ohne Eintrag erlaubt |
| `OPAA_CSP_CONNECT_SRC_EXTRA` | — (kein Spring-Property; leer als Image-Default des Frontend-nginx-`envsubst`-Templates) | nicht gesetzt (auskommentiert) — der Compose-Standardfall `docker,dev` startet keinen Keycloak (Compose-Profil `oidc`); der Kommentar in `.env.docker.example` verweist auf den Wechsel zu `docker,oidc` | Zusätzliche Origin(s) in der `connect-src`-Richtlinie des Frontend-nginx, leerzeichengetrennt bei mehreren. Erforderlich, wenn die OIDC-Authority auf einem anderen Origin liegt als das Frontend selbst — sonst blockiert die Content-Security-Policy die OIDC-Anmeldung stillschweigend |
| `OPAA_DEMO_MODE` | — (kein Spring-Property; `"false"` als Image-Default des Frontend-nginx-`envsubst`-Templates, siehe `frontend/Dockerfile`) | nicht gesetzt (auskommentiert) | Schaltet den Quellen- und Demo-Hinweis in der Fußzeile der Oberfläche ein (`frontend/src/layouts/DemoNotice.tsx`) — **nur für Demo-Instanzen gedacht**, nicht für reguläre OPAA-Installationen. Nach demselben Muster wie `OPAA_CSP_CONNECT_SRC_EXTRA`: `frontend/nginx.conf` setzt den Wert beim Containerstart per `envsubst` in eine kleine, gleichen-Origin-JavaScript-Antwort unter `/runtime-config.js` (normalisiert über eine nginx-`map`-Direktive auf ein literales `true`/`false`, damit ein fehlerhafter Wert nie ungeprüft in die Antwort gelangt) — kein Rebuild des Images nötig, um den Hinweis umzuschalten |
| **Docker-Compose-Ports** | | | |
| `OPAA_BACKEND_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8081`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — `.env.docker.example` lässt die Variable deshalb bewusst auskommentiert; ohne Shell-Export gilt der Compose-Default `8081` | Backend-Host-Port |
| `OPAA_FRONTEND_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `3000`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — `.env.docker.example` lässt die Variable deshalb bewusst auskommentiert; ohne Shell-Export gilt der Compose-Default `3000` | Frontend-Host-Port |
| `OPAA_KEYCLOAK_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8180`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `8180` | Keycloak-Host-Port (Compose-Profile `oidc` und `demo` — `keycloak` gehört beiden an) |
| `OPAA_DEMO_CORPUS_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8091`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `8091` | Host-Port des Rheinfurt-Demo-Korpus-Webservers `demo-corpus` (nur `demo`-Compose-Profil), zum Prüfen der drei `HTTP_DIRECTORY`-Verzeichnislistings im Browser — an `127.0.0.1` gebunden, kein öffentlicher Zugang |
| `OPAA_DEMO_PRESSE_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8092`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `8092` | Host-Port des Rheinfurt-Demo-Pressestelle-Webservers `demo-presse` (nur `demo`-Compose-Profil), zum Prüfen von RSS-Feed und HTML-Detailseiten im Browser — an `127.0.0.1` gebunden, kein öffentlicher Zugang |
| `OPAA_DEMO_MINIO_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8093`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `8093` | Host-Port der S3-API des Demo-Objektspeichers `minio` (nur `demo`-Compose-Profil) — zum Prüfen mit `mc`/`aws s3` vom Host; das Backend erreicht `minio` über das Compose-Netzwerk unter dem Servicenamen, der dafür in `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST` stehen muss |
| `OPAA_DEMO_MINIO_CONSOLE_PORT` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `8094`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` (siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt; ohne Shell-Export gilt der Compose-Default `8094` | Host-Port der MinIO-Konsole des Demo-Objektspeichers (nur `demo`-Compose-Profil) — zum Ansehen des Buckets `rheinfurt-archiv` im Browser, Anmeldung mit den Demo-Zugangsdaten des Compose-Stacks |
| `OPAA_ENV_FILE` | — (kein Spring-Property; nur `docker-compose.yml`, dort Compose-Default `.env.docker`) | wirkt nur aus Prozessumgebung/`.env`, **nicht** aus `.env.docker` selbst (zirkulär — siehe Hinweis oben) — nicht in `.env.docker.example` gesetzt | Wählt die `env_file`, aus der `docker-compose.yml` die Container-Umgebung lädt (Standard `.env.docker`). Die E2E-Suite setzt sie per Prozessumgebung auf `e2e/e2e.env`, um denselben `docker-compose.yml` mit einem eigenen, von der Entwickler-`.env.docker` unabhängigen Umgebungssatz zu betreiben |

**Laufzeit und Speicher eines RSS-Laufs.** Die Politeness-Wartezeit (`OPAA_INDEXING_RSS_REQUEST_DELAY_MS`, Voreinstellung 1000 ms) gilt für jede Anfrage einzeln — Detailseite und jede einzelne Anlage. Mit den Voreinstellungen (200 Einträge, bis zu 10 Anlagen je Eintrag) dauert ein Lauf, der bei jedem Eintrag das Limit ausschöpft, im ungünstigsten Fall rund 200 × 11 × 1 s ≈ 37 Minuten. Jede Anlage wird beim Herunterladen direkt auf die temporäre Datei gestreamt und dabei bei `OPAA_INDEXING_RSS_MAX_ATTACHMENT_SIZE_BYTES` (Voreinstellung 20 MiB) abgeschnitten (`io.opaa.sourceaccess.BoundedDownloader#downloadBounded`); der Heap hält davon nur den Kopierpuffer. Antwortet eine Quelle mit `429`, wartet der Lauf die in `Retry-After` genannte Zeit — je Anfrage bis zu `OPAA_INDEXING_HTTP_MAX_RATE_LIMIT_RETRIES` × `OPAA_INDEXING_HTTP_MAX_RETRY_AFTER`, mit den Voreinstellungen also bis zu 6 × 2 min = 12 Minuten. Je Lauf ist die Summe dieser Wartezeiten auf `OPAA_INDEXING_HTTP_MAX_RATE_LIMIT_WAIT_PER_RUN` (Voreinstellung 15 Minuten) gedeckelt, die Zahl der Anfragen optional auf `OPAA_INDEXING_HTTP_REQUEST_BUDGET_PER_RUN`: Eine Quelle, die jede Anfrage bis zum Ende drosselt, beendet den Lauf damit nach spätestens 15 Minuten Wartezeit geordnet als „unvollständig, wird fortgesetzt", statt ihn rechnerisch um 200 × 11 × 12 min zu verlängern. Wer schneller aufgeben will, senkt die Werte; Confluence und S3 begrenzen ihre Läufe über eigene Anfragebudgets.

### Netzwerkzugang

Standardmäßig bindet das Backend an `localhost`. Um OPAA von anderen Geräten im Netzwerk zugänglich zu machen, setzen Sie:

```env
OPAA_SERVER_ADDRESS=0.0.0.0
```

> **Hinweis:** In Docker Compose **muss** `OPAA_SERVER_ADDRESS` auf `0.0.0.0` gesetzt werden, damit das Backend vom Nginx-Reverse-Proxy des Frontend-Containers erreichbar ist.

> **TLS-terminierender Reverse-Proxy davor?** Das Backend wertet `X-Forwarded-*` aus (`server.forward-headers-strategy: framework`), damit Browser-Anfragen desselben Origins hinter dem Proxy nicht fälschlich als cross-origin behandelt werden. Der äußere Proxy **muss** `X-Forwarded-Proto` dabei autoritativ setzen (`proxy_set_header X-Forwarded-Proto $scheme;`) und darf den Wert nicht vom Client durchlassen — ein gespooftes `https` würde sonst die CORS-Prüfung umgehen. Der nginx im Frontend-Container reicht ein eingehendes `X-Forwarded-Proto` unverändert weiter (Fallback: eigenes Schema). Dieselbe Auflage gilt für **`X-Forwarded-For`** (`proxy_set_header X-Forwarded-For $remote_addr;` — die eigene Sicht des Proxys, nicht die vom Client mitgelieferte Kette): Das Rate-Limit des Backends schlüsselt je Client-Adresse nach diesem Header, und die Webhook-Eingänge für Confluence und S3 sind ohne Sitzung erreichbar — ein Client, der den Header selbst setzen darf, bekäme mit jedem Wert einen frischen Zähler und liefe nur noch gegen die globale Grenze.

#### Sicherheits-Header und `Strict-Transport-Security`

Der nginx im Frontend-Container (`frontend/nginx.conf`) setzt `Content-Security-Policy`, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` und `server_tokens off` auf jede Antwort (auch Fehlerantworten, über `add_header ... always;`). Die Content-Security-Policy setzt technisch durch, dass das Frontend alle Schriften, Icons und statischen Ressourcen selbst ausliefert und zur Laufzeit kein CDN lädt: Skripte, Stile, Schriften und Verbindungen sind auf die eigene Herkunft begrenzt, keine externe Quelle ist erlaubt (bis auf die unten beschriebene, gezielte Ausnahme für den OIDC-Anbieter). `style-src` erlaubt zusätzlich `'unsafe-inline'`, weil MUIs Emotion-Engine Stile zur Laufzeit über eingebettete `<style>`-Tags einfügt — dafür gibt es ohne Nonce-Unterstützung in Emotion keinen strikteren Weg. `object-src 'none'` ist gesetzt, weil die Anwendung keine `<object>`/`<embed>`-Inhalte einbettet.

Auf `/api/`-Antworten setzt zusätzlich Spring Security eigene `X-Content-Type-Options`/`X-Frame-Options`-Header; `proxy_hide_header` in `location /api/` entfernt diese, damit der Client nur die eine, am nginx gesetzte Kopie sieht statt beide Werte doppelt.

**`Strict-Transport-Security` wird hier bewusst nicht gesetzt.** Der Frontend-Container terminiert im Compose-Betrieb kein TLS — er spricht selbst nur `http` (siehe Hinweis zu `X-Forwarded-Proto` oben). Diesen Header trotzdem hier zu setzen wäre wirkungslos für Installationen ohne vorgelagerten TLS-Weg und schädlich für solche mit einem: HSTS an zwei Stellen im selben Antwortpfad zu pflegen — hier und am vorgelagerten Proxy — schafft nur eine weitere Möglichkeit, dass beide auseinanderlaufen (z. B. unterschiedliches `max-age` oder `includeSubDomains`), ohne einen Sicherheitsgewinn gegenüber einer einzigen, korrekt gepflegten Stelle. Wer OPAA hinter einem TLS-terminierenden Proxy betreibt (siehe Hinweis oben), setzt `Strict-Transport-Security` an genau diesem äußeren Proxy — dort, wo TLS tatsächlich endet.

**`connect-src` und ein OIDC-Anbieter auf fremdem Origin.** `oidc-client-ts` holt die OIDC-Discovery-Metadaten und tauscht später den Auth-Code gegen Tokens jeweils per `fetch` direkt aus dem Browser gegen die Authority — liegt die Authority nicht auf demselben Origin wie das Frontend, blockiert eine reine `connect-src 'self'`-Richtlinie diese Aufrufe **stillschweigend** (kein Fehler in der Oberfläche, die Anmeldung tut einfach nichts). `frontend/nginx.conf` ist deshalb kein fest gebackenes Ergebnis mehr, sondern ein `envsubst`-Template (`/etc/nginx/templates/default.conf.template`, siehe `frontend/Dockerfile`): Die Umgebungsvariable `OPAA_CSP_CONNECT_SRC_EXTRA` wird beim Containerstart in `connect-src 'self' ${OPAA_CSP_CONNECT_SRC_EXTRA}` eingesetzt (leer per Voreinstellung im Image; `.env.docker.example` führt den Origin des mitgelieferten Keycloak als auskommentiertes Beispiel, siehe [„OIDC (Keycloak)"](#oidc-keycloak) unten für den Fall, in dem er tatsächlich gebraucht wird). `NGINX_ENVSUBST_FILTER=^OPAA_(CSP|DEMO)_` im Dockerfile begrenzt die Ersetzung auf `OPAA_CSP_*`- und `OPAA_DEMO_*`-Variablen (Letztere für den Demo-Hinweis, siehe `OPAA_DEMO_MODE` oben), damit `envsubst` nicht versehentlich echte nginx-Variablen (`$scheme`, `$host`, `$remote_addr`, …) in derselben Datei anfasst. Details zum Betrieb mit einem eigenen Behörden-Identitätsanbieter: Abschnitt „OIDC (Keycloak)" unten.

Die genannten Header sind gegen den Produktions-Build (`pnpm run build`) und das gebaute Docker-Image verprobt: `dist/index.html` referenziert ausschließlich selbst gehostete, gehashte `<script>`- und `<link>`-Dateien (keine Inline-Skripte), und ein Abruf des laufenden Containers zeigt alle Header sowohl auf `/` als auch auf `/api/`-Antworten (Vererbung über den `server`-Block, siehe Kommentare in `frontend/nginx.conf`).

Für die lokale Entwicklung auch das Frontend mit `pnpm run dev --host` starten und den Zugriffsursprung zu CORS hinzufügen:

```env
OPAA_CORS_ALLOWED_ORIGINS=http://localhost:5173,http://your-hostname:5173
```

### LLM-Anbieter

Es gibt einen einzigen Anbindungsweg für Chat
und Einbettung: die **openai-kompatible Schnittstelle**. Das ist eine Protokollangabe, keine
Anbieterangabe — Ollama bedient dieselbe Schnittstelle unter ihrem eigenen `/v1`-Pfad, ebenso vLLM,
LiteLLM, Azure und die üblichen Zwischenschichten. Einen zweiten, nativen Anbindungsweg speziell für
Ollama gibt es nicht.

> **Für den Chat-Aufruf gilt eine wichtige
> Einschränkung: `OPAA_OPENAI_CHAT_*`/`OPAA_OPENAI_API_KEY`/`OPAA_OPENAI_BASE_URL` steuern das
> Chat-Modell nur beim allerersten Start einer Installation.** Dabei übernimmt
> `io.opaa.llm.LlmModelSeeder` die damalige Umgebungskonfiguration einmalig als ersten Eintrag der
> Tabelle `llm_models` und aktiviert ihn. Danach ist **ausschließlich diese Tabelle** maßgeblich für
> das Chat-Modell — zur Laufzeit gelesen von `io.opaa.llm.ActiveChatModelResolver`, der Basis-Adresse,
> Modell-Kennung, Temperatur, maximale Antwortlänge und (falls hinterlegt) den entschlüsselten
> Zugangsschlüssel des jeweils **aktiven** Eintrags zu einem `ChatClient` zusammenbaut, zwischenspeichert
> und bei jeder Aktivierung oder Änderung des jeweils aktiven Eintrags über die
> Administrationsoberfläche neu auflöst — ohne Neustart. Löschen ist dabei kein eigener Auslöser:
> Das aktive Modell kann nicht gelöscht werden (409), und das Löschen eines inaktiven Eintrags
> berührt die Auflösung nicht. Ein Ändern von `OPAA_OPENAI_CHAT_*` **nach** diesem ersten Start hat
> keine Wirkung mehr;
> die Modellauswahl läuft über die Administrationsoberfläche (`SYSTEM_ADMIN`, Modellverwaltung), nicht
> mehr über Umgebungsvariablen. Die Umgebungsvariablen bleiben unten dokumentiert, weil sie beim
> Erststart nach wie vor gelesen werden und weil ihre Werte den anfänglichen aktiven Eintrag bilden.
>
> **Die Einbettung ist von dieser Umstellung nicht betroffen**: `OPAA_OPENAI_EMBEDDING_*`/
> `OPAA_OPENAI_BASE_URL` steuern das Embedding-Modell weiterhin unverändert und fortlaufend über die
> native Spring-AI-Autoconfiguration — es gibt (bewusst) keine verwaltete Einbettungsmodell-Tabelle:
> Ein Wechsel des Einbettungsmodells macht alle vorhandenen Vektoren unvergleichbar und erzwingt eine
> vollständige Neuindizierung; eine Auswahlliste, die eine Wissensbasis unbrauchbar macht, sobald
> jemand sie versehentlich verstellt, wäre eine Falle. Die Oberfläche zeigt die Einbettungskonfiguration
> deshalb nur an.

**Voreingestellt sind lokal betriebene Modelle** über genau diesen Weg, für Chat und für Einbettung.
Eine Installation, an der niemand etwas konfiguriert, ruft kein Modell außerhalb des Hauses auf —
`OPAA_OPENAI_BASE_URL` zeigt ohne weitere Angabe auf `http://localhost:11434/v1` (im Spring-Profil
`docker`: `http://ollama:11434/v1`), `OPAA_OPENAI_CHAT_MODEL`/`OPAA_OPENAI_EMBEDDING_MODEL` auf
`phi3:mini`/`nomic-embed-text`, und Ollama braucht keinen Zugangsschlüssel (der voreingestellte
`OPAA_OPENAI_API_KEY`-Platzhalter wird nie geprüft). Diese Voreinstellung ist so gewollt und bleibt:
Betrieb ohne Netzanbindung ist der vorgesehene Fall, nicht der Notbetrieb; es gibt kein automatisches
Ausweichen auf ein Modell außerhalb des Hauses.

```env
# Kein Eintrag nötig - dies ist bereits der Anwendungs-Default.
```

Um stattdessen einen anderen openai-kompatiblen Anbieter zu verwenden, ist die **Zieladresse**
anzugeben — sie überschreibt die lokale Voreinstellung:

```env
OPAA_OPENAI_BASE_URL=https://modellserver.example.internal/v1
OPAA_OPENAI_API_KEY=sk-your-key-here
```

`OPAA_OPENAI_CHAT_BASE_URL` und `OPAA_OPENAI_EMBEDDING_BASE_URL` überschreiben die Adresse je
Funktion; ohne sie gilt `OPAA_OPENAI_BASE_URL` für beide.

> **Es gibt keine technische Sperre**, die einen Aufruf außerhalb festgelegter Netzbereiche
> verhindert. Wer die Voreinstellung ändert, kann jedes erreichbare Ziel eintragen, und OPAA hält
> ihn nicht auf. Wer zusichern muss, dass keine Daten das Haus verlassen, weist die Konfiguration nach
> und sichert den Netzweg außerhalb von OPAA ab, etwa über die Firewall-Regeln der Umgebung, in der
> das Backend läuft. Jede Änderung des Chat-Modells über die Administrationsoberfläche ist
> protokolliert und einer Person zurechenbar.

### Lokal betriebenes Ollama im Compose-Stack

Der Compose-Stack (`docker-compose.yml`) enthält einen optionalen `ollama`-Service unter dem
eigenen Compose-Profil **`ollama`** — er startet nur, wenn er ausdrücklich angefordert wird, damit
der unveränderte Stack (kein Profil oder nur `oidc`/`demo`) keinen zusätzlichen Download und keinen
zusätzlichen, ressourcenhungrigen Container voraussetzt:

```bash
docker compose --profile ollama up --build
```

Das startet zusätzlich zu den üblichen Services zwei weitere:

- **`ollama`** — der eigentliche Ollama-Server, mit einem benannten Volume (`opaa-ollama-data`) für
  die heruntergeladenen Modelle. Nicht auf einen Host-Port veröffentlicht: Backend und der
  Init-Schritt unten erreichen ihn ausschließlich über das Compose-Netz unter seinem Servicenamen
  (`ollama`) — genau der Hostname, auf den `OPAA_OPENAI_BASE_URL` im Spring-Profil `docker` bereits
  standardmäßig zeigt (siehe oben). Es sind deshalb **keine** Umgebungsvariablen-Änderungen nötig,
  um dieses Profil zu nutzen.
- **`ollama-pull`** — ein einmaliger Init-Schritt, der `nomic-embed-text` (Embedding-Default,
  `OPAA_OPENAI_EMBEDDING_MODEL`) und `phi3:mini` (Chat-Default, `OPAA_OPENAI_CHAT_MODEL`) zieht,
  sobald `ollama` bereit ist, und danach beendet wird. Beide Modelle sind nötig, nicht nur das
  Embedding-Modell: `io.opaa.llm.LlmModelSeeder` übernimmt `phi3:mini` beim allerersten
  Backend-Start als aktives, verwaltetes Chat-Modell (siehe [„LLM-Anbieter"](#llm-anbieter) oben) —
  ohne es schlägt die erste Chat-Anfrage fehl, obwohl `ollama` selbst bereits läuft. Der Pull-Schritt
  ist **idempotent**: `ollama pull` vergleicht gegen das, was `ollama` bereits im Volume
  `opaa-ollama-data` gespeichert hat, und lädt bei einem erneuten Start desselben Stacks nichts noch
  einmal herunter.

**Ressourcen- und Downloadhinweis:** Beide Modelle zusammen laden beim allerersten Start mehrere
Gigabyte herunter (`phi3:mini` rund 2,2 GiB, `nomic-embed-text` rund 274 MiB, jeweils komprimiert)
und benötigen anschließend entsprechend Plattenplatz im Volume sowie Arbeitsspeicher/CPU (oder GPU)
für den laufenden Ollama-Server. Mit `docker compose logs -f ollama-pull` lässt sich der
Download-Fortschritt verfolgen; der Stack selbst ist bereits nutzbar, sobald `ollama` gesund ist
(`ollama` beantwortet dann `ollama list`), auch während `ollama-pull` noch lädt — die erste
Indizierung/Chat-Anfrage kann in diesem Fenster aber noch fehlschlagen, bis beide Modelle vorliegen.

**Nur mit Registry-Zugriff, auch bei bereits vorhandenen Modellen:** `ollama pull` prüft bei jedem
Aufruf gegen die Ollama-Registry im Internet, ob die lokal gespeicherten Layer noch aktuell sind —
das macht den Schritt idempotent (siehe oben), setzt aber Netzzugang zu dieser Registry voraus. In
einem abgeschotteten Netz ohne diesen Zugang **exitet `ollama-pull` mit einem Fehler, obwohl beide
Modelle bereits im Volume liegen**. Betroffen ist jeder erneute Start, nicht nur der erste. Für einen
solchen Betrieb entweder das `command` in `docker-compose.yml` um einen vorangestellten
`ollama list | grep -q ... ||`-Kurzschluss ergänzen (überspringt den Netzzugriff, wenn beide Modelle
bereits vorhanden sind) oder `ollama-pull` bei einem erneuten Start ganz weglassen
(`docker compose --profile ollama up ollama backend frontend postgres`, ohne `ollama-pull` in der
Serviceliste).

**Ein fehlgeschlagener `ollama-pull` bleibt sonst unauffällig:** Exitet der Init-Schritt mit einem
Fehler (Registry nicht erreichbar wie oben, Plattenplatz erschöpft, o. ä.), laufen `backend` und
`frontend` trotzdem unbeeindruckt weiter — der Stack wirkt oberflächlich normal gestartet, nur
Indizierung/Chat schlagen dann fehl, weil ein oder beide Modelle fehlen. Ursache und Fehlermeldung
stehen in `docker compose logs ollama-pull`; das ist der erste Blick bei einer sonst unerklärten
`FAILED`-Indizierung direkt nach `docker compose --profile ollama up`.

**GPU-Nutzung:** Ohne eigene Ergänzung nutzt `ollama` ausschließlich die CPU — `docker-compose.yml`
enthält keinen `deploy.resources.reservations.devices`-Eintrag für eine GPU. Wer eine NVIDIA-GPU
mit installiertem Container-Toolkit durchreichen will, ergänzt einen solchen Eintrag am
`ollama`-Service selbst (analog zum auskommentierten `ports`-Eintrag dort) - nicht Teil dieser
Voreinstellung, weil sie ein GPU-fähiges Docker-Setup auf dem Host voraussetzt, das nicht jede
Installation hat.

**Abwägung Imagegröße/Ressourcen vs. „ein Befehl, alles läuft":** Das `ollama`-Image selbst ist mit
rund 3,4 GB (amd64) bereits größer als beide Modelle zusammen (rund 2,5 GB, siehe oben) — Image und
Modelle zusammen machen den gesamten zusätzlichen Umfang dieses Profils aus, nicht nur Letztere. Wer
keinen lokal betriebenen Ollama-Server braucht oder bereits einen extern betreibt, bleibt beim
unveränderten Standard (kein Profil) und spart sich Download und laufenden Container vollständig —
deshalb ist `ollama` ein eigenes, nicht standardmäßig aktives Profil und keine Voreinstellung.

**Alternative: externer Ollama-Server statt des Compose-Profils.** Läuft Ollama bereits auf dem
Host oder in einem anderen Netz, ist das Profil `ollama` nicht nötig — stattdessen die Zieladresse
auf den externen Server umbiegen, z. B. bei einer Installation auf dem Host selbst:

```env
OPAA_OPENAI_BASE_URL=http://host.docker.internal:11434/v1
```

(`docker-compose.yml` setzt den dafür nötigen `extra_hosts`-Eintrag für den `backend`-Service
bereits.) Beide Wege sind gleichwertig — das Compose-Profil ist der bequemere Einstieg ohne
Ersteinrichtung außerhalb des Stacks, ein externer Ollama-Server eignet sich besser, wenn er von
mehreren Stacks/Installationen gemeinsam genutzt werden soll oder bereits vorhanden ist.

Kein Port-Expose über `127.0.0.1` hinaus: `ollama` veröffentlicht standardmäßig **keinen** Port; der
in `docker-compose.yml` auskommentierte `ports`-Eintrag für den direkten Host-Zugriff bindet, falls
aktiviert, ebenfalls nur auf `127.0.0.1` (siehe die dortigen Kommentare und [„Härtung für erreichbare
Deployments"](#härtung-für-erreichbare-deployments), Punkt 5).

### vLLM / OpenAI-kompatible Server

Bei Verwendung von vLLM oder anderen OpenAI-kompatiblen Servern, die HTTP/2 nicht unterstützen, HTTP/1.1-Modus aktivieren:

```env
OPAA_HTTP_FORCE_HTTP1=true
OPAA_OPENAI_BASE_URL=http://your-vllm-server:8000/v1
```

Dies ist erforderlich, weil Spring Boots Standard-HTTP-Client HTTP/2 bevorzugt, was bei Uvicorn-basierten Servern wie vLLM zu Verbindungsfehlern führt.

### Zugangsdaten-Verschlüsselung

Eine Bibliothek vom Quellentyp `HTTP_DIRECTORY`, `RSS_FEED`, `CONFLUENCE` oder `S3` kann Zugangsdaten
(`sourceCredentials`, bei HTTP-Quellen im Basic-Auth-Format `user:password`) tragen. Diese liegen **verschlüsselt** in
der Datenbank (AES-256-GCM, zufälliger Initialisierungsvektor je Wert) und erscheinen in keiner
API-Antwort und keinem Log — die API behandelt das Feld als
Nur-Schreiben-Feld.

**Schlüssel erzeugen und setzen:**

```bash
openssl rand -base64 32
```

Das Ergebnis (ein Base64-kodierter 256-Bit-Schlüssel) als `OPAA_CREDENTIALS_ENCRYPTION_KEY` setzen
— **nicht** ins Repository committen, wie jede andere Zugangsinformation außerhalb von
Umgebungsvariablen behandeln (Passwort-Tresor, Secret-Manager der Zielumgebung o. Ä.).

**Kein Zwang, keinen zu haben:** Ohne gesetzten Schlüssel startet das Backend normal — die meisten
Bibliotheken (`UPLOAD`, `FILESYSTEM`, oder ein `HTTP_DIRECTORY`/`RSS_FEED` ohne Zugangsdaten)
brauchen keinen. Erst der Versuch, eine Bibliothek **mit** Zugangsdaten anzulegen oder zu ändern,
schlägt ohne gültigen Schlüssel mit `503` fehl (`io.opaa.security.CredentialsEncryptor`) — eine klare
Fehlermeldung statt eines unspezifischen `500`. Für lokale Entwicklung und Tests (nur Profil `dev`,
das jede Testsuite und `bootRun` aktivieren — nicht `local`) ist ein fest hinterlegter, **ausdrücklich
nicht produktionstauglicher** Schlüssel voreingestellt (`backend/src/main/resources/application.yml`),
damit beide ohne Betreiber-Eingriff laufen.

**Lesen scheitert weich, Schreiben hart:** Ein Wert, der mit dem aktuellen Schlüssel nicht mehr
entschlüsselt werden kann (Schlüssel verloren, rotiert, oder ein beschädigter Datenbankwert), lässt
das **Lesen** dieser einen Bibliothek nicht scheitern — `SourceCredentialsConverter` protokolliert
eine Warnung (ohne den Wert, ohne die Bibliotheks-ID) und behandelt `sourceCredentials` für diese
Bibliothek als nicht gesetzt; `GET /api/v1/libraries` liefert die übrige Liste normal, auch wenn eine
einzelne Bibliothek betroffen ist. Ein **Schreibvorgang** (Anlegen oder Ändern von Zugangsdaten)
scheitert dagegen weiterhin hart mit `503`, statt Zugangsdaten stillschweigend zu verlieren.

**Bei Schlüsselverlust:** Bereits verschlüsselte `sourceCredentials`-Werte sind ohne den
ursprünglichen Schlüssel nicht wiederherstellbar — es gibt keinen Wiederherstellungsweg außerhalb des
Schlüssels selbst. Betroffen sind ausschließlich die Zugangsdaten selbst, nicht die übrige
Bibliothekskonfiguration oder der bereits indizierte Bestand. Abhilfe: Zugangsdaten über die
bestehende Bibliotheks-API (`PATCH /api/v1/libraries/{id}`) neu setzen — derselbe Weg, über den
Zugangsdaten ohnehin rotiert werden. Dank des weichen Lesefehlers oben funktioniert dieser
Reparaturweg tatsächlich: das vorausgehende Laden der Bibliothek scheitert nicht mehr an genau dem
Wert, der repariert werden soll. Bis zur Reparatur läuft ein laufender oder künftiger
Indizierungslauf für diese Bibliothek ohne Anmeldung und kann entsprechend scheitern, wenn die
Quelle Zugangsdaten verlangt.

**Altbestand im Klartext:** Werte, die vor Einführung dieser Verschlüsselung im Klartext geschrieben
wurden, erkennt `CredentialsEncryptor` beim Lesen an einem fehlenden `enc:`-Präfix und behandelt sie
weiterhin als Klartext — eine Liquibase-Migration kann sie nicht verschlüsseln, da der Schlüssel nur
der Anwendung bekannt ist. Der nächste Schreibvorgang auf dieselbe Bibliothek (z. B. eine
Zugangsdaten-Rotation über die bestehende Update-API) verschlüsselt den Wert. Ein Wert mit `enc:`-
Präfix, aber unbekannter Version (nicht `enc:v1:`), wird dagegen nie als Klartext behandelt — Lesen
scheitert weich (siehe oben), Schreiben hart.

### Verschlüsselung der Zugangsschlüssel verwalteter Chat-Modelle

Chat-Modelle liegen in der Tabelle `llm_models`, nicht in
Umgebungsvariablen (siehe [„LLM-Anbieter"](#llm-anbieter) oben). Ein hinterlegter Zugangsschlüssel (optional — ein lokal betriebener Endpunkt
läuft regelmäßig ohne Authentifizierung) liegt **verschlüsselt** in der Datenbank (AES-256-GCM,
zufälliger Initialisierungsvektor je Wert, `io.opaa.security.SettingsEncryptor`) und erscheint in
keiner API-Antwort und keinem Log — die Verwaltung behandelt das Feld als Nur-Schreiben-Feld.

**Ein eigener Schlüssel, getrennt von `OPAA_CREDENTIALS_ENCRYPTION_KEY`:**

```bash
openssl rand -base64 32
```

Das Ergebnis (ein Base64-kodierter 256-Bit-Schlüssel) als `OPAA_SETTINGS_ENCRYPTION_KEY` setzen —
**nicht** ins Repository committen, wie jede andere Zugangsinformation außerhalb von
Umgebungsvariablen behandeln. Ein eigener Schlüssel statt Wiederverwendung von
`OPAA_CREDENTIALS_ENCRYPTION_KEY`, weil beide unterschiedliche Geheimnisse schützen (Zugangsdaten
einer Wissensquelle vs. der Zugangsschlüssel eines Chat-Modells) und keinen Grund haben, dieselbe
Rotation zu teilen.

**Kein Zwang, keinen zu haben:** Ohne gesetzten Schlüssel startet das Backend normal — eine
Installation, die ausschließlich lokale Modelle ohne Zugangsschlüssel führt (der Normalfall), braucht ihn nie.
Erst der Versuch, ein Chat-Modell **mit** Zugangsschlüssel anzulegen oder zu ändern, schlägt ohne
gültigen Schlüssel mit einer klaren deutschen Meldung fehl (`io.opaa.security.SettingsEncryptor`),
die die fehlende oder ungültige Variable benennt. Für die Seed-Migration, die beim ersten Start eine
bestehende `openai`-Konfiguration samt Zugangsschlüssel übernimmt (siehe [„LLM-Anbieter"](#llm-anbieter) oben), gilt das
mit einer Einschränkung: Dort führt derselbe fehlende oder ungültige Schlüssel **nicht** zum
Startabbruch, sondern nur zu einer ERROR-Log-Zeile — siehe den Update-Hinweis unten. Für lokale
Entwicklung und Tests (nur Profil `dev`, das jede Testsuite und `bootRun` aktivieren — nicht
`local`) ist ein
fest hinterlegter, **ausdrücklich nicht produktionstauglicher** Schlüssel voreingestellt
(`backend/src/main/resources/application.yml`), damit beide ohne Betreiber-Eingriff laufen.

> **Update-Hinweis für Bestandsinstallationen.** Eine Installation, die heute
> ausschließlich Ollama oder einen `openai`-kompatiblen Endpunkt ohne Zugangsschlüssel nutzt, läuft
> nach dem Update auf diese Version unverändert weiter, **ohne** `OPAA_SETTINGS_ENCRYPTION_KEY`
> gesetzt haben zu müssen. Nur wer die openai-kompatible Anbindung bereits **mit** gesetztem
> Zugangsschlüssel betreibt, sollte den Schlüssel **vor** dem Update setzen — sonst schlägt die
> einmalige Übernahme dieser Konfiguration als initiales Modell fehl: Der Start selbst bricht nicht
> ab, aber das Modell bleibt unangelegt und es erscheint eine ERROR-Meldung im Log. Kein
> Seed-Marker wird dabei geschrieben — sobald der Schlüssel gesetzt ist, holt der **nächste Neustart**
> die Übernahme automatisch nach, ohne dass etwas anderes zu tun wäre. Wer nicht neu starten will
> oder kann, legt das Modell in der Zwischenzeit **ohne** Zugangsschlüssel über die
> Verwaltungsoberfläche an (`OPAA_SETTINGS_ENCRYPTION_KEY` bleibt auch dafür Voraussetzung, sobald
> tatsächlich ein Zugangsschlüssel eingetragen werden soll — die Oberfläche ruft dafür dieselbe
> `SettingsEncryptor#encrypt`-Prüfung auf wie die Seed-Migration).

**Bei Schlüsselverlust:** Bereits verschlüsselte Zugangsschlüssel sind ohne den ursprünglichen
Schlüssel nicht wiederherstellbar — es gibt keinen Wiederherstellungsweg außerhalb des Schlüssels
selbst. Betroffen ist ausschließlich der Zugangsschlüssel des jeweiligen Modells, nicht der übrige
Modelleintrag (Basis-Adresse, Modell-Kennung, Parameter) oder andere verschlüsselte Werte. Abhilfe:
den Zugangsschlüssel für das betroffene Modell über die Verwaltungsoberfläche neu eintragen.

## Authentifizierung

OPAA kennt genau zwei Auth-Modi. Der Modus
wird über das aktive Spring-Profil gewählt; ist weder `oidc` noch `dev` gesetzt, **bricht das
Backend den Start mit einer Fehlermeldung ab**.

| Modus | Profil | Zweck |
|-------|--------|-------|
| `oidc` | `oidc` | Der einzige für den Betrieb zulässige Modus |
| `dev` | `dev` | Lokale Entwicklung und automatisierte Tests — **keinerlei Prüfung von Anmeldedaten** |

### Entwicklungsmodus (`dev`)

```env
SPRING_PROFILES_ACTIVE=docker,dev
```

Es gibt keinen Anmeldevorgang und kein Token: Jede Anfrage wird als einer der konfigurierten
Entwicklungsnutzer authentifiziert. Vorkonfiguriert sind `dev-admin` (E-Mail `admin@opaa.local`,
wird durch `OPAA_INITIAL_ADMIN_EMAIL` zum `SYSTEM_ADMIN`) und `dev-user` (regulärer Nutzer).

Den Nutzer wechselt man im Browser über den Query-Parameter `?devUser=dev-user`, der für die Dauer
der Browser-Session gemerkt wird; direkte API-Aufrufe setzen stattdessen den Header
`X-OPAA-Dev-User`. Ein unbekannter Nutzername führt zu `401`.

> **Warnung:** Dieser Modus deaktiviert die Authentifizierung vollständig. Er gehört ausschließlich
> auf Arbeitsplätze und in Testumgebungen, nie auf eine erreichbare Instanz. Das Backend
> protokolliert beim Start eine entsprechende Warnung.

### OIDC (Keycloak)

Um OIDC-Authentifizierung mit dem gebündelten Keycloak zu aktivieren:

```bash
docker compose --profile oidc up --build
```

Erforderliche Variablen in `.env.docker` (siehe [„Bestandsübernahme"](#bestandsübernahme-aus-opaa_oidc_) unten für ihre Rolle):

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_OIDC_ISSUER_URI=http://localhost:8180/realms/opaa
OPAA_OIDC_CLIENT_ID=opaa-frontend
OPAA_OIDC_JWK_SET_URI=http://keycloak:8180/realms/opaa/protocol/openid-connect/certs
OPAA_CSP_CONNECT_SRC_EXTRA=http://localhost:8180
```

> **Wichtig:** `OPAA_OIDC_JWK_SET_URI` muss den Docker-internen Hostnamen `keycloak` (nicht `localhost`) verwenden, weil der Backend-Container JWT-Tokens verifiziert, indem er Schlüssel von Keycloak abruft. `OPAA_OIDC_ISSUER_URI` bleibt `http://localhost:8180/...`, da der Browser diese Adresse verwendet — sie ist zugleich die Authority des Anmeldeflusses. `OPAA_OIDC_AUTHORITY` ist ohne Wirkung und kann entfallen. Ohne `OPAA_CSP_CONNECT_SRC_EXTRA` blockiert die Content-Security-Policy des Frontend-nginx den Aufruf gegen Keycloak stillschweigend, siehe [„Sicherheits-Header und `Strict-Transport-Security`"](#sicherheits-header-und-strict-transport-security) oben; mehrere Origins werden durch Leerzeichen getrennt, und eine Änderung wirkt erst nach einem Neustart des Frontend-Containers.

Vorkonfiguriert sind zwei Realms (`keycloak/realm-export.json`, `keycloak/realm-partner-export.json`): Der Realm `opaa` mit dem Client `opaa-frontend` ist der beim Start übernommene Standardanbieter; der Realm `partner` mit dem Client `opaa-partner` ist ein zweiter Anbieter auf demselben Origin, den die Systemverwaltung über die Anbieterverwaltung anlegt (so, wie ein Betreiber den Anbieter eines Partners anbindet) — er dient dem Demo-Smoke-Lauf und lokalen Versuchen mit mehreren Anbietern. Ein Testbenutzer im Realm `opaa`: `testuser`/`testpass`. Die Keycloak-Admin-Konsole ist unter http://localhost:8180 verfügbar (admin/admin).

#### Anbieterverwaltung

OPAA akzeptiert Anmeldungen von **mehreren OIDC-Anbietern**. Die Anbieter liegen in der Datenbank und werden unter **Administration → Identitätsanbieter** (nur `SYSTEM_ADMIN`; API `/api/v1/admin/oidc-providers`) gepflegt; jede Änderung wirkt ohne Neustart. Je Anbieter:

| Feld | Bedeutung |
|------|-----------|
| Anzeigename | Beschriftung der Schaltfläche auf der Anmeldeseite („Anmelden bei …") |
| Issuer-URI | Der `iss`-Claim seiner Tokens, byteweise wie der Anbieter ihn prägt (bei Keycloak die Realm-Adresse, bei Auth0 mit abschließendem Schrägstrich); zugleich die Adresse, an der der Browser die Anmeldung startet. Eindeutig je Anbieter |
| Client-ID | Der beim Anbieter angelegte **öffentliche** Client (Authorization Code Flow mit PKCE, **kein Secret** — die SPA kann keines geheim halten) |
| JWK-Set-Adresse | Optional: die Backend-seitige Adresse des JWK-Sets, wenn das Backend den Anbieter unter einer anderen Adresse erreicht als der Browser (Compose: `keycloak` statt `localhost`). Ohne Angabe liest das Backend die Adresse aus dem Discovery-Dokument des Issuers. Sie ist der **Vertrauensanker** jedes Kontos dieses Anbieters |
| Claim-Zuordnung | Aus welchen Token-Claims E-Mail und Anzeigename gelesen werden (Vorgaben `email`/`name`, Rückfall `preferred_username`) und optional Rollen- und Gruppen-Claim, siehe unten |
| Reihenfolge, Standard, aktiviert | Reihenfolge der Anmeldeseite; genau ein **Standardanbieter**; ein deaktivierter Anbieter wird ab dem nächsten Token abgewiesen |

**Beim Anbieter einzutragen** (die Verwaltungsoberfläche zeigt die Werte mit dem eigenen Origin an): Weiterleitungs-URI `<Origin>/auth/callback` (für alle Anbieter dieselbe), erlaubter Web-Origin und Abmelde-Weiterleitung `<Origin>`. Zusätzlich im Betrieb: den Origin des Anbieters in `OPAA_CSP_CONNECT_SRC_EXTRA` aufnehmen und den Frontend-Container neu starten, und — wenn der Anbieter in einem privaten Netz liegt — seinen Host in `OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST` (siehe unten). Der **Verbindungstest** im Formular prüft Discovery-Dokument und JWK-Set vor dem Speichern; ist die Issuer-URI vom Backend aus nicht erreichbar (Compose-Aufteilung), prüft er das JWK-Set über die Backend-seitige Adresse und sagt das — ein Discovery-Dokument, das erreichbar ist und nicht passt (anderer Issuer, Fehlerstatus, gesperrte Adresse), bleibt ein Fehler.

**Standardanbieter.** Der erste Anbieter ist automatisch der Standard; er kann weder deaktiviert noch gelöscht werden, bevor ein anderer aktivierter, erreichbarer Anbieter Standard ist — es gibt keinen Zustand ohne anmeldefähigen Anbieter. Nur über ihn gilt `OPAA_INITIAL_ADMIN_EMAIL` (die Erstadministrator-Regel greift beim ersten Anmelden dieser Adresse, und nur über den Standardanbieter), und nur seine Konten löst der Verzeichnisabgleich auf.

**Konten.** Die Identität eines Kontos ist das Paar (Issuer, Subject). Dieselbe Person bei zwei Anbietern hat **zwei Konten** — eine Zusammenführung über die E-Mail findet nie statt. Wird ein Anbieter gelöscht, bleiben seine Konten und werden wieder nutzbar, sobald ein Anbieter mit derselben Issuer-URI existiert; die Issuer-URI eines Anbieters, über den bereits Konten angelegt wurden, ist deshalb nicht änderbar (409). Konten eines deaktivierten Anbieters können sich nicht mehr anmelden; laufende Sitzungen enden mit der nächsten Anfrage (`unknown_issuer`).

**Rollen und Gruppen aus dem Token** (optional, je Anbieter). Ist ein **Rollen-Claim** gesetzt (Pfad in Punktnotation, z. B. `realm_access.roles`, mit den Rollenwerten für `SYSTEM_ADMIN` und `AUDITOR`), ist der Anbieter für diese Systemrollen führend: Die Rolle wird bei jeder Anfrage aus dem Token übernommen, die manuelle Rollenvergabe ist für Konten dieses Anbieters gesperrt, und der letzte `SYSTEM_ADMIN` der Installation wird nie per Token entzogen (der abgelehnte Entzug wird protokolliert und auditiert). Das Setzen des Rollen-Claims verlangt in der Oberfläche eine Bestätigung. Ist ein **Gruppen-Claim** gesetzt, werden die Gruppennamen des Tokens bei jeder Anmeldung zu Mitgliedschaften in Gruppen der Art „Gruppe aus dem Identitätsanbieter" im Namensraum dieses Anbieters — gleichnamige Gruppen zweier Anbieter sind zwei Gruppen; diese Gruppen sind in der Gruppenverwaltung schreibgeschützt und nicht Gegenstand des Verzeichnisabgleichs.

#### Bestandsübernahme aus `OPAA_OIDC_*`

Die Variablen `OPAA_OIDC_ISSUER_URI`, `OPAA_OIDC_CLIENT_ID` und `OPAA_OIDC_JWK_SET_URI` sind **Bootstrap-Werte**: Beim ersten Start im `oidc`-Modus übernimmt OPAA sie einmalig als ersten, aktivierten Standardanbieter „Verzeichnisdienst" — vor dem Start des Webservers, sodass keine Anmeldung der Übernahme zuvorkommt. Danach führt die Datenbank; eine Änderung der Variablen wirkt nicht mehr. Eine Bestandsinstallation, deren Konten unter diesem Issuer angelegt wurden, behält damit jedes Konto (kein Eingriff an `users`). Fehlt beim Start eine der drei Variablen oder ist die Issuer-URI keine http(s)-Adresse, wird nichts übernommen, das Backend protokolliert, was zu setzen ist, und holt die Übernahme beim nächsten Start nach.

**Die Variablen bleiben gesetzt.** Sie sind der Notanker: `OPAA_OIDC_BOOTSTRAP=force` stellt den Anbieter aus der Umgebung einmalig wieder her (Zeile mit diesem Issuer überschrieben bzw. angelegt, aktiviert, Standard) — der dokumentierte Weg zurück aus einer vertippten Issuer-URI des einzigen Anbieters. Die Variable danach wieder entfernen; jeder weitere Start würde die Anbieterverwaltung erneut überschreiben. Außerdem sind die **Adressen** der beiden Variablen (Schema, Host und Port von Issuer-URI und JWK-Set-Adresse) für die Adressprüfung immer erlaubt.

**Adressprüfung.** Jede von der Systemverwaltung eingegebene Issuer- und JWK-Set-Adresse durchläuft dieselbe Prüfung gegen private Netzbereiche wie die Datenquellen (SSRF-Schutz), mit eigener Konfiguration `OPAA_OIDC_TARGET_VALIDATION_ENABLED` (Vorgabe `true`) und `OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST` (Hosts, kommagetrennt). Ein hausinterner Anbieter mit privater Adresse gehört in die Allowlist; die Bootstrap-Adressen brauchen keinen Eintrag. Discovery-Dokument und JWK-Set werden vom Backend selbst abgerufen, ohne Weiterleitungen zu folgen.

#### Grenzen

- **Keine Kontenzusammenführung:** Zwei Anbieter, dieselbe E-Mail — zwei Konten mit getrennten Rechten. Wer eine Person von einem Anbieter zum anderen umzieht, vergibt ihre Rechte neu.
- **Kein SAML, kein reines OAuth2:** Ausschließlich OpenID Connect mit Discovery-Dokument und Authorization Code Flow (PKCE, öffentlicher Client).
- **Ein Verzeichnisabgleich je Installation:** Der Abgleich ist an den Standardanbieter gebunden; Gruppen anderer Anbieter kommen nur über deren Gruppen-Claim.
- **`AUDITOR` ist nicht geschützt:** Ein per Token entzogener letzter Prüfer ist nur im Anbieter wiederherstellbar.
- **Eine Organisation:** Alle Anbieter provisionieren in dieselbe Organisation.

### Testkonten im Überblick

Im Repository existieren mehrere Testkonto-Muster nebeneinander. Sie sind **bewusst nicht
vereinheitlicht** — jedes gehört zu einem anderen Auth-Modus bzw. einer anderen Konfigurationsebene
und folgt dessen jeweils eigenem Mechanismus:

| Muster | Geltungsbereich | Nutzer |
|--------|------------------|--------|
| Entwicklungsnutzer des `dev`-Profils (`opaa.auth.dev.users`) | Lokale Entwicklung, `dev`-Auth-Modus (siehe [„Entwicklungsmodus (dev)"](#entwicklungsmodus-dev) oben) | `dev-admin` (`admin@opaa.local`, `SYSTEM_ADMIN`), `dev-user` (regulärer Nutzer) |
| Keycloak-Realm-Nutzer (`keycloak/realm-export.json`) | `oidc`-Auth-Modus mit dem gebündelten Keycloak (siehe [„OIDC (Keycloak)"](#oidc-keycloak) oben) | `testuser`/`testpass` (E-Mail `test@opaa.local`) — wird zum `SYSTEM_ADMIN`, sobald `OPAA_INITIAL_ADMIN_EMAIL` in der lokalen `.env.docker` auf dieselbe Adresse gesetzt ist, sonst ein regulärer Nutzer |
| Demo-Realm-Nutzer (`keycloak/realm-export.json`) | Demo-Instanz „Stadt Rheinfurt" (Compose-Profil `demo`, befüllt durch das Seed-Skript `demo/seed/seed.py`) | `demo-admin` (`admin@stadt-rheinfurt.example`, `SYSTEM_ADMIN` bei entsprechend gesetztem `OPAA_INITIAL_ADMIN_EMAIL`), `maria.weber`, `selin.kaya`, `thomas.klein`, `andrea.vogt` — alle mit dem offenen Demo-Passwort `RheinfurtDemo!2026`. Zusätzlich der Client `opaa-seed` (Resource Owner Password Grant, `directAccessGrantsEnabled: true`) — ausschließlich für das Seed-Skript, nie für eine reguläre Anmeldung |
| Partner-Realm-Nutzer (`keycloak/realm-partner-export.json`) | Zweiter Identitätsanbieter (Realm `partner`, Client `opaa-partner`) auf demselben Keycloak — nicht beim Start übernommen, sondern über die Anbieterverwaltung anzulegen (der Demo-Smoke-Lauf tut genau das) | `maria.weber` mit **derselben E-Mail** wie im Realm `opaa` und dem Demo-Passwort `RheinfurtDemo!2026` — ein eigenes, zweites Konto ohne die Rechte der Demo-Maria: der Nachweis, dass Konten zweier Anbieter nie zusammengeführt werden |
| E2E-Suite (`e2e/e2e.env`, `e2e/docker-compose.e2e.yml`) | Playwright-Suite | Wiederverwendet `dev-admin` und `dev-user` aus dem `dev`-Profil, ergänzt um `dev-outsider` und `dev-format-pipelines` (nur für diese Suite, über `OPAA_AUTH_DEV_USERS_*` hinzugefügt) |
| Quellenzugangsdaten (`sourceCredentials`, siehe [„Zugangsdaten-Verschlüsselung"](#zugangsdaten-verschlüsselung) oben) | Kein Testkonto für OPAA selbst — Basic-Auth-Zugangsdaten (`user:password`), mit denen eine `HTTP_DIRECTORY`- oder `RSS_FEED`-Bibliothek eine *externe* Dokumentenquelle abruft | Kein fester Beispielwert; frei je Bibliothek |

Warum keine Vereinheitlichung:

- Der `dev`-Modus prüft grundsätzlich **keine** Anmeldedaten — er authentifiziert jede Anfrage
  ungeprüft als einen der konfigurierten Nutzer (siehe oben). Ein Passwort dafür zu vergeben wäre
  irreführend, weil keines abgefragt wird.
- Der Keycloak-`testuser` ist dagegen ein echtes, wenn auch bewusst schwaches Credential innerhalb
  eines vollständigen OIDC-Identitätsanbieters — ein anderer Mechanismus mit eigenem Lebenszyklus
  (Realm-Import), der sich nicht auf `dev`-Nutzer abbilden lässt.
- `sourceCredentials` ist kein Konto zum Anmelden bei OPAA, sondern die Zugangsdaten, mit denen das
  Backend selbst eine externe Quelle kontaktiert — fachlich und im Lebenszyklus unabhängig von den
  beiden Auth-Modi oben.
- Die E2E-Suite legt bewusst **kein** eigenes Kontoschema an, sondern läuft im `dev`-Auth-Modus und
  nutzt dessen Nutzer weiter: Die Filterkette ist dort identisch zur OIDC-Konfiguration, die Suite
  übt also dieselben Autorisierungsregeln aus, ohne Keycloak, Realm-Import und Anmeldeablauf im
  Prüfpfad zu haben. Den echten Keycloak-Login prüft allein der separate Demo-Smoke-Lauf.

## Dokumente

Dokumente im `./documents`-Verzeichnis ablegen (oder `OPAA_INDEXING_DOCUMENT_PATH_HOST` in `.env.docker` ändern). Das Verzeichnis wird in den Backend-Container unter `/app/documents` gemountet.

## Datenbank

PostgreSQL-Daten werden in einem Docker-Volume (`opaa-postgres-data`) gespeichert. Daten überleben `docker compose down`- und `docker compose up`-Zyklen.

Datenbank zurücksetzen:

```bash
docker compose down -v
```

> **Hinweis:** `docker compose down -v` muss ausgeführt werden, wenn `OPAA_DB_USERNAME` oder `OPAA_DB_PASSWORD` geändert wird, weil PostgreSQL den initialen Benutzer nur beim ersten Start erstellt. Ohne Volume-Entfernung werden Anmeldeinformationsänderungen ignoriert.

> **Mit aktivem Compose-Profil `ollama` löscht `docker compose down -v` zusätzlich das
> benannte Volume `opaa-ollama-data`** — die dort gespeicherten Modelle (`nomic-embed-text`,
> `phi3:mini`, zusammen rund 2,5 GB). Ein danach erneut gestarteter Stack lädt beide Modelle über
> `ollama-pull` vollständig neu herunter, siehe [„Lokal betriebenes Ollama im Compose-Stack"](#lokal-betriebenes-ollama-im-compose-stack).

## Volltextsuche (lexikalischer Suchpfad)

Neben der Vektorsuche läuft eine klassische Volltextsuche direkt in PostgreSQL — `tsvector` mit der
`german`-Konfiguration und einem GIN-Index auf der Tabelle `chunk_full_text`. Es gibt **kein zweites
System**: derselbe Sicherungslauf, dieselbe Wiederherstellung, derselbe Verschlüsselungsnachweis. Der
Pfad findet, woran eine Vektorsuche strukturell scheitert — Paragrafenverweise, Aktenzeichen,
Erlassnummern, seltene Fachbegriffe.

> **Stand:** Der Pfad ist gebaut und **wirkt auf die Antwort**. Je Teilfrage liefert er
> eine zweite Trefferliste, die zusammen mit der Liste der Vektorsuche rangbasiert fusioniert wird
> (Reciprocal Rank Fusion). Ein Chunk, den beide Pfade finden, ist dabei **ein** Treffer mit zwei
> Beiträgen, kein doppelter. Fällt die Volltextabfrage aus, läuft die Fusion mit den verbleibenden
> Listen weiter: schlechtere Suchqualität, nie ein Fehler für die fragende Person.

### Was zu tun ist

Im laufenden Betrieb nichts. Jeder indexierte Chunk bekommt seinen Volltexteintrag in derselben
Transaktion wie den Vektor; auf diesem Weg entsteht kein Abschnitt, der vektorisiert, aber nicht
volltextindiziert ist.

**Ändert ein Update die Art, wie der Volltextindex gebildet wird**, gelten die betroffenen Zeilen als
fehlend, und die Seite „Suche & Indexierung" zeigt die betroffenen Bibliotheken als **unvollständig**
an. Der lexikalische Pfad findet diese Zeilen weiterhin — ihnen fehlen nur die Lexeme, die die neue
Fassung hinzufügt, bis der Nachzug sie neu schreibt (ADR-0028). Einen Hintergrundlauf, der das von selbst
nachzieht, gibt es nicht — **nötig ist dann der Nachzug auf der Administrationsseite**
(„Suche & Indexierung", Pipeline-Nachzug). Er erfasst solche Abschnitte ausdrücklich, auch wenn sich
an der Aufbereitung des Dokuments sonst nichts geändert hat.

**Was das kostet:** Der Nachzug liest jedes betroffene Dokument neu ein, zerlegt es erneut in
Abschnitte und **bettet diese neu ein** — er verursacht also Aufrufe beim Einbettungsmodell und ist
in derselben Größenordnung teuer wie eine Neuindizierung dieser Dokumente. Er ist damit teurer als
das reine Neuschreiben der Volltextspalte wäre, aber der einzige Weg, der dieselben Abschnitte
lückenlos wiederherstellt. Der Lauf ist stapelweise, unterbrechbar und wiederaufnehmbar; bis er
durch ist, sucht der lexikalische Pfad in den betroffenen Beständen mit den Lexemen der alten
Fassung weiter. Ein Update, das
diesen Nachzug nötig macht, wird in den Release-Hinweisen ausdrücklich genannt.

### Bekannte Grenze: `ts_rank` ist kein BM25

PostgreSQL bewertet Volltexttreffer mit `ts_rank`. Das ist **nicht** das BM25-Verfahren, das
spezialisierte Suchmaschinen verwenden, und der Unterschied ist im Betrieb spürbar:

- **Keine Normalisierung auf die Textlänge.** Lange Abschnitte werden systematisch überbewertet.
- **Keine Gewichtung nach Seltenheit.** Ein Wort, das in fünf von 50 000 Abschnitten vorkommt, zählt
  kaum mehr als eines, das überall steht. Bei einem Bestand aus vielen ähnlich formulierten Satzungen
  ist genau das die schwache Stelle.

Diese Grenze ist bewusst in Kauf genommen, aus zwei Gründen. Erstens braucht die Ergebnis-Fusion keine
richtige *Bewertung*, sondern nur eine brauchbare *Reihenfolge* — eine deutlich schwächere Anforderung.
Zweitens wird die Schwäche dort, wo der Pfad seinen Zweck erfüllt, kaum wirksam: Bei einer exakten
Kennung ist der richtige Abschnitt meist der einzige, der die Zeichenfolge überhaupt enthält.

Gegen den zweiten Punkt ist zusätzlich vorgesorgt: Paragrafenverweise, Aktenzeichen, Dienstanweisungs-
und Formularnummern sowie Erlass- und Drucksachennummern werden **zusätzlich als unzerlegte Kennungen**
geführt und höher gewichtet als Fließtext. Ohne das gewönne ein Abschnitt, der die Wörter der Frage nur
oft genug wiederholt, gegen den einen Abschnitt, der die gesuchte Kennung tatsächlich führt — genau die
Schwäche von `ts_rank`. Dabei ist gleichgültig, ob das Dokument die Kennung hinter einem Schlüsselwort
nennt („mit dem Aktenzeichen BAU-DA-2/2024") und die Frage sie nackt („Was regelt die Dienstanweisung
BAU-DA-2/2024?") — beide Schreibweisen führen auf dieselbe Kennung.

**Wann diese Grenze relevant wird:** Wenn Fragen mit exakten Kennungen die falsche Fundstelle liefern,
ist sie der erste Verdacht. Ob der Wechsel auf eine echte BM25-Erweiterung nötig ist, wird gemessen und
nicht vermutet. Die festgelegte Eintrittsbedingung: Die Fallgruppen „Kompositum" und „exakte Kennung"
des Suchqualitäts-Benchmarks bleiben — mit aktivem Kennungsschutz und aktivem Reranker — je einzeln
unter Hit Rate@5 = 0,80, obwohl der Vektorpfad die betreffenden Kandidaten im gemessenen Fenster zeigt.
Erst dann ist eine BM25-Erweiterung im selben PostgreSQL (eigenes Datenbank-Image) der nächste Schritt;
eine externe Suchengine neben PostgreSQL bleibt die letzte Stufe, weil sie den Rechtefilter verdoppelt.

**Keine Kompositazerlegung.** „Genehmigung" findet „Baugenehmigungsverfahren" im Volltextpfad nicht.
Auch das ist eine bewusste Festlegung: Eine Zerlegung, die „Gebührenordnung" in „Gebühr" und „Ordnung"
auflöst, verwässert auch Treffer. Ob sich der Tausch lohnt, entscheidet eine Messung, keine Vermutung.

## Reranking einschalten

Reranking ist voreingestellt **aus** (`OPAA_RERANK_ENABLED=false`). Die Modellrolle ist gebaut, aber
nicht aktiviert: Sie braucht einen eigenen Endpunkt, und ob sie auf einem gegebenen Bestand etwas
bringt, wird gemessen und nicht vermutet. Der Qualitätsnutzen ist auf den Korpora des Projekts
gezeigt; die Voreinstellung bleibt trotzdem aus, solange kein Latenz-/Hardwareprofil vorliegt — ein
Qualitätsgewinn, dessen Preis niemand kennt, ist keine Grundlage für eine Aktivierungsempfehlung.
Nach demselben Prinzip — nur, was gemessen besser ist, wird voreingestellt aktiv — ist auch die
Vielfaltsauswahl MMR per Voreinstellung abgeschaltet.

Zum Einschalten gehören drei Angaben zusammen: `OPAA_RERANK_ENABLED=true`, `OPAA_RERANK_BASE_URL`
und `OPAA_RERANK_MODEL`. Fehlt eine der beiden Endpunktangaben oder antwortet der Endpunkt nicht,
meldet die Anwendung das im Log und führt den Zustand auf der Seite „Suche & Indexierung“
fortlaufend mit; die Suche läuft dann ohne Reranking weiter — ein Ausfall kostet die Sortierung,
nie die Antwort.

> **`OPAA_QUERY_FETCH_K` mit anheben.** Der Reranker bewertet nur, was die Suchstufen zuvor
> zurückgegeben haben. Bei `OPAA_QUERY_FETCH_K=25` liefern Vektor- und Volltextpfad je Teilfrage
> zusammen höchstens 50 verschiedene Abschnitte; ein Kandidatenfenster von 50
> (`OPAA_QUERY_RERANK_CANDIDATE_COUNT`) ist damit gerade gedeckt, ein größeres läuft ins Leere. Wer
> dem Reranking mehr Material geben will — und genau dafür ist es da: eine Fundstelle aus der Tiefe
> nach vorn zu holen —, hebt `OPAA_QUERY_FETCH_K` mit an. Reranking allein einzuschalten und die
> Abrufbreite zu lassen, wie sie ist, wirkt nur zur Hälfte.

> **Reverse-Proxy-Timeouts mit anheben.** `OPAA_RERANK_TIMEOUT` bindet nur den Rerank-Aufruf
> selbst, nicht die HTTP-Anfrage der Suche als Ganzes: Eine Suche mit CPU-Reranker kann beim
> voreingestellten Zeitbudget (`240s`) rund drei Minuten still warten, bevor die Antwort kommt. Ein
> Reverse-Proxy vor dem Backend (z. B. nginx mit seinem Standard-Read-Timeout von 60s) bricht die
> Verbindung dann ab, lange bevor die Suche selbst fertig ist — sichtbar als Verbindungsabbruch beim
> Client, nicht als Fehler im Backend-Log. Wer Reranking auf CPU einschaltet, muss den
> Proxy-seitigen Read-/Response-Timeout auf mindestens `OPAA_RERANK_TIMEOUT` anheben.

## Fehlerbehebung

### Backend gibt leere Antworten oder "Connection refused" zurück

Das Backend bindet standardmäßig an `localhost`, was nur von innerhalb des Containers erreichbar ist. `OPAA_SERVER_ADDRESS=0.0.0.0` in `.env.docker` setzen.

### POST-Anfragen geben 403 Forbidden zurück

CORS ist wahrscheinlich falsch konfiguriert. Sicherstellen, dass `OPAA_CORS_ALLOWED_ORIGINS` mit der Frontend-URL übereinstimmt (z. B. `http://localhost:3000`). GET-Anfragen können funktionieren, weil sie keinen CORS-Preflight auslösen, während POST-Anfragen mit `Content-Type: application/json` dies tun.

### Passwort-Authentifizierung für Benutzer fehlgeschlagen

Das PostgreSQL-Volume enthält noch Daten von einer früheren Initialisierung mit anderen Anmeldeinformationen. `docker compose down -v` ausführen, um das Volume zu entfernen und neu zu starten.

### OIDC: "Completing sign in..." hängt

- Sicherstellen, dass Keycloak läuft (`docker compose --profile oidc ps`)
- Wenn Keycloak neu gestartet wurde, ist das Access-Token möglicherweise abgelaufen — Seite neu laden und erneut anmelden
- Prüfen, dass `OPAA_OIDC_JWK_SET_URI` `keycloak:8180` (nicht `localhost:8180`) verwendet

### Umgebungsvariablen-Änderungen treten nicht in Kraft

Nach dem Ändern von `.env.docker` `docker compose up -d <service>` (nicht `restart`) verwenden, um den Container mit der neuen Umgebung neu zu erstellen. `restart` verwendet den vorhandenen Container erneut und ignoriert `.env.docker`-Änderungen.

## Stoppen

```bash
docker compose down
```
