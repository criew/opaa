# Bausteine mit Befund — Zeitraum 31.08.–30.09.2026

Die Fortschreibung zum Stichtag 30.09.2026 hat **616 Vorgänge** geprüft — die 502 im Zeitraum
geschlossenen Issues und die 114 gemergten Pull Requests ohne Issue-Verknüpfung (davon 54
Renovate-Updates). Diese Datei führt die **43 Vorgänge mit Befund**: solche, bei denen die
Lieferung wesentlich vom Vorgang abweicht. Aufnahmekriterium ist eines der folgenden drei
(Kennziffer in der Zeile „Befund"):

1. **Nicht geliefert.** Der Vorgang wurde geschlossen, ohne dass das Beschriebene entstand — als
   `not planned`, als Duplikat oder als `completed` durch Ablösung.
2. **Anders geschnitten.** Die Lieferung weicht in Umfang oder Zuschnitt wesentlich ab: Wesentliches
   fehlt, ein Abnahmekriterium blieb unerfüllt, oder der eingeschlagene Weg ist ein anderer als der
   beschriebene — bis hin zum gegenteiligen.
3. **Nicht mehr vorhanden.** Das Gelieferte existiert zum Stichtag nicht mehr oder wurde ersetzt —
   auch eine Lieferung des Vorzeitraums, die in diesem Zeitraum zurückgebaut wurde.

Die übrigen **573 Vorgänge sind ohne Befund**: geliefert wie beschrieben, und das Gelieferte steht
noch. Ihre Nummern stehen am Ende dieser Datei.

Suche mit `Issue #NNN` bzw. `PR #NNN`; jeder Abschnitt trägt den Anker `#issue-NNN` bzw. `#pr-NNN`.

---

<a id="issue-1047"></a>

## Issue #1047 — feat(retrieval): Volltextspalte, GIN-Index und wiederaufnehmbarer Backfill des Bestands
- Geschlossen: 2026-09-01 (completed)
- Labels: enhancement, backend, size:L
- PRs: #1091 (2026-09-01)
- Befund: 3 (Backfill zurückgebaut)

**Laut Issue:** Arbeitspaket 2a der Hybrid-Suche: eine `tsvector`-Spalte mit GIN-Index (per `CREATE INDEX CONCURRENTLY`) an der Chunk-Tabelle, Neuzugänge in derselben Transaktion wie Text und Vektor indizieren, dazu ein idempotenter, wiederaufnehmbarer und nachrangiger Backfill des Bestands. Der Füllstand je Bibliothek sollte als abfragbarer Zustand vorliegen und den Volltextpfad je Bibliothek erst nach abgeschlossenem Backfill freigeben.

**Geliefert:** PR #1091 weicht beim Schema bewusst ab: Statt Spalten an `vector_store` (von Spring AI verwaltet) entsteht eine eigene, Liquibase-verwaltete Tabelle `chunk_full_text` mit GIN-Index. `VectorChunkStore.addChunks` schreibt Vektor und Volltext atomar, ein `FullTextBackfillService` mit `FullTextBackfillScheduler` (alle 5 s, Chargen zu 200) zieht den Bestand nach, `FullTextBackfillProgressService` liefert den Füllstand, und ein Tor (`FullTextBackfillGate`) sperrt unvollständige Bibliotheken für den Volltextpfad. Im Übrigen geliefert wie beschrieben.

**Verifikation:** `chunk_full_text` und `FullTextChunkStore` (`io.opaa.indexing.chunk`) bestehen. Der Nachzug existiert zum Stichtag nicht mehr: PR #1290 (04.09.2026, Commit `6be12557a`, „Volltext-Nachzug samt Tor, Skip-Tabelle und Fortschrittsanzeige entfernen“) hat `FullTextBackfillService`, `FullTextBackfillScheduler`, `FullTextBackfillGate` und die Skip-Tabelle gelöscht. Begründung dort: Da jede Zeile beim Schreiben entsteht, hatte der Nachzug im Regelbetrieb keinen Gegenstand mehr (zugleich die Linie „keine Bestandssysteme“). Übrig ist nur die Füllstandsanzeige, umbenannt in `FullTextIndexFillStateService`.

**Themen:** retrieval, volltext, hybride-suche, backfill, rückbau, datenbank

---

<a id="issue-1051"></a>

## Issue #1051 — feat(retrieval): Latenz-/Hardwareprofil für Reranking auf Referenzhardware
- Geschlossen: 2026-09-02 (not planned)
- Labels: backend, size:M, evaluation
- PRs: #1155 (2026-09-02)
- Befund: 1 (zurückgestellt)

**Laut Issue:** Es sollte ein eigener Messaufbau auf benannter Referenzhardware außerhalb der Testcontainers-CI entstehen. Gemessen werden sollte die zusätzliche Latenz je Rerank-Aufruf nach Modell, Kandidatenzahl und Chunk-Länge, auch unter gleichzeitigen Anfragen. Ergebnis wäre eine Empfehlung je Hardwareklasse gewesen, ob Reranking aktivierbar ist. Das Profil war Voraussetzung jeder Voreinstellung „Reranking an“.

**Geliefert:** Nicht umgesetzt. Auf Entscheidung des Maintainers wurde der Vorgang ungebaut geschlossen, weil die Aktivierung von Reranking grundsätzlich neu überdacht wird. PR #1155 ändert nur die Dokumentation: `hybrid-retrieval.md` kennzeichnet das Arbeitspaket als zurückgestellt und hält bereits Bekanntes fest (nDCG@8 0,726 → 0,867, aber rund drei Minuten je Frage auf CPU) sowie die Messfallen #1153 und #1154. Der Zuschnitt des Pakets bleibt als Anforderung stehen.

**Verifikation:** `backend/src/main/resources/application.yml` setzt `OPAA_RERANK_ENABLED:false`. `docs/features/hybrid-retrieval.md` führt das Latenz-/Hardwareprofil als einziges zurückgestelltes Paket. Es gibt keinen Messaufbau und keine Hardwareempfehlung.

**Themen:** retrieval, reranking, latenz, evaluation, zurückgestellt

---

<a id="issue-1052"></a>

## Issue #1052 — feat(auth): Befugnis- und Protokollmodell für "Sicht als" in der Suchdiagnose
- Geschlossen: 2026-09-01 (completed)
- Labels: enhancement, backend, size:L, security, auth
- PRs: #1121 (2026-09-01)
- Befund: 2 (Abnahmekriterium Diagnosesperre bewusst nicht erfüllt)

**Laut Issue:** Leitplanken als Baubedingung für die Diagnose „Sicht als (Person)“: eine einzeln vergebbare, befristete Befugnis mit Geltungsbereich, eine Pflichtbegründung und ein unveränderliches Protokoll nach ADR-0015. Dazu Zweckbindung ohne Auswertung je Zielperson, Einsichtsrecht der Betroffenen und eine Löschfrist von 12 Monaten. Außerdem sollten Bibliotheken als diagnosegesperrt kennzeichenbar sein; die Sperre sollte **die zuständige Stelle selbst, nicht die Administration** setzen und lösen.

**Geliefert:** PR #1121 liefert das Backend-Modell im neuen Paket `io.opaa.diagnosticaccess`: Befugnistabelle mit DB-Constraint (höchstens zwölf Monate), Protokoll, Einsichtsrecht und Aufbewahrung. Die Sperre ist als Voreinstellung für jede Bibliothek gesetzt. Das Kriterium zur Diagnosesperre ist jedoch nach Review wieder offen und wurde am 04.09.2026 bewusst **nicht** umgesetzt (Entscheidung in #1124, dokumentiert mit PR #1249). Ein `SYSTEM_ADMIN` kann sich per `POST /api/v1/groups/{id}/members` selbst in die Eigentümergruppe einer Bibliothek eintragen und die Sperre danach allein lösen. Tragend ist damit die Nachvollziehbarkeit über zwei Protokollzeilen, nicht das Vier-Augen-Prinzip.

**Verifikation:** `ForeignDiagnosticContextService` und `LibraryDiagnosticsLockService` (`io.opaa.diagnosticaccess`) bestehen. Die Knowledge-Baseline führt `diagnostics_locked boolean DEFAULT true NOT NULL`. Einen Selbstausschluss in der Gruppenverwaltung gibt es nicht.

**Themen:** sicht-als, suchdiagnose, befugnis, protokoll, personalrat, vier-augen, bewusste-abweichung

---

<a id="issue-1054"></a>

## Issue #1054 — feat(indexing): Ingestion-Pipelines je Dokumenttyp
- Geschlossen: 2026-09-01 (completed)
- Labels: enhancement, epic, backend, size:L
- PRs: keine (Epic)
- Befund: 2 (Phase 5 Docling-PoC nicht geliefert)

**Laut Issue:** Das Epic war in fünf Phasen geplant:
1. Scan-Erkennung
2. `DocumentPipeline`-Abstraktion mit Pipeline-Version
3. Formate ODF, XLSX/CSV, HTML, EML/MSG
4. strukturbewusster Zuschnitt
5. ein **Docling-PoC**, der über einen layoutbewussten PDF-Pfad entscheidet und Voraussetzung des Folge-Epics OCR ist

**Geliefert:** Die Phasen 1 bis 4 wurden über die Sub-Issues #1055–#1061 geliefert. Nachträge sind #1103 (Markdown), #1107 (Open-Closed-Kriterium) und #1110 (ODT/ODP). Die Phase 5 fehlt: Der Docling-PoC (#1062) wurde aus dem Epic gelöst und als eigenständiges Ticket weitergeführt. Das Epic schloss am 01.09.2026 ohne ihn.

**Verifikation:** #1062 („Docling-PoC als Vorbereitung für OCR/Bilderkennung (standalone)“) ist zum Stichtag offen. `docs/features/ingestion-pipelines.md` führt Docling als „vermerkte Option … nicht im ersten Ausbau“. Die Format-Pipelines bestehen, nach der Umbenennung in Epic #1316 als `*DocumentFormat` unter `io.opaa.format.file.*`.

**Themen:** ingestion, formate, epic, docling, ocr, zuschnitt

---

<a id="issue-1060"></a>

## Issue #1060 — feat(indexing): EML- und MSG-Pipeline mit Kopfdaten als Metadaten und rekursiven Anhängen
- Geschlossen: 2026-09-01 (completed)
- Labels: enhancement, backend, size:L
- PRs: #1101 (2026-09-01)
- Befund: 3 (Anhangsmodell im Zeitraum ersetzt)

**Laut Issue:** Eine eigene Pipeline für EML/MSG mit folgenden Punkten: Kopfdaten als Chunk-Metadaten statt Fließtext, ein Chunk je Nachricht im Thread und Anhänge rekursiv durch die Pipeline ihres Typs. Für Rechte und Herkunft der Anhänge sollten die bestehenden Regeln des Anlagenwegs gelten.

**Geliefert:** PR #1101 liefert `MailDocumentPipeline` (EML über mime4j, MSG über POI HSMF) mit `mail_*`-Metadaten, Thread-Zerlegung und rekursiver Anhangsverarbeitung. Dabei gibt es eine dokumentierte Abweichung: Anhänge wurden **weitere Chunks derselben Dokumentzeile** statt eigener `Document`-Zeilen und trugen die Pipeline-Kennung der Mail. Die Kopfdaten-Metadaten hatten zunächst keinen Leser (Befund #1130).

**Verifikation:** Das Anhangsmodell existiert zum Stichtag nicht mehr. Mit Epic #1178 (ADR-0022) hat PR #1214 (#1183, 03.09.2026) `processAttachment` ersetzt. Die Mail-Pipeline (heute `MailDocumentFormat`, `io.opaa.format.file.mail`) meldet Anhänge über `DocumentFormatResult#discoveredAttachments()`, und jeder Anhang wird ein eigenes Dokument mit `parent_document_id` (Spalte am `Document`) und der Kennung seiner eigenen Pipeline. Die Kopfdaten erhielten einen Leser mit #1166/#1201 und eine Filterung mit #1211.

**Themen:** ingestion, e-mail, anhänge, adr-0022, ersetzt

---

<a id="issue-1066"></a>

## Issue #1066 — feat(indexing): Kernfelder — Datenmodell, Herkunftsangabe und deterministische Extraktion
- Geschlossen: 2026-09-04 (completed)
- Labels: enhancement, backend, size:L
- PRs: #1237 (2026-09-04)
- Befund: 2 (Rechtekontext-Kriterium durch Gegenentscheidung ersetzt)

**Laut Issue:** Die drei Kernfelder Titel, Dokumentart und Datum/Stand sollten am Dokument hängen, mit Herkunft, Akteur, Konfidenz und Extraktionsversion je Wert. Dazu kam eine deterministische Extraktion beim Aufnehmen und die Anzeige im Beleg. Ein Abnahmekriterium verlangte, dass die Extraktion im Rechtekontext läuft und keine Dokumente liest, die die auslösende Person nicht lesen darf.

**Geliefert:** PR #1237 liefert Datenmodell (`document_metadata_values`, Vokabular als Fremdschlüssel), `CoreMetadataExtractor` und die Beleg-Anzeige wie beschrieben. Das Rechtekontext-Kriterium wurde nicht erfüllt, sondern durch Maintainer-Beschluss 1 zu Epic #1065 (04.09.2026, ADR-0024 Entscheidung 6) ins Gegenteil gewendet. Die deterministische Extraktion ist ein Systemprozess des Ingest **ohne** Personenrechtekontext; die Rechte-Invariante gilt nur für Aggregate, Stichproben und die Modell-Extraktion.

**Verifikation:** `CoreMetadataExtractor` (`io.opaa.metadata`) besteht, ebenso `MetadataBackfillService` (`io.opaa.indexing.maintenance`) für den Bestandslauf, der als Systemprozess arbeitet.

**Themen:** metadaten, kernfelder, extraktion, rechtekontext, bewusste-abweichung

---

<a id="issue-1093"></a>

## Issue #1093 — fix(indexing): Gift-Chunk-Isolation im Volltext-Backfill
- Geschlossen: 2026-09-02 (completed)
- Labels: enhancement, backend
- PRs: #1168 (2026-09-02)
- Befund: 3 (mit dem Volltext-Nachzug entfernt)

**Laut Issue:** Ein einzelner fehlerhafter Chunk sollte den `FullTextBackfillService` nicht dauerhaft blockieren. Vorgeschlagen war, die Charge schrittweise zu halbieren und die fehlerhafte Zeile protokolliert zu überspringen.

**Geliefert:** PR #1168 liefert eine rekursive Halbierung (`indexWithIsolation`), eine SQLSTATE-Allowlist für zeilenbezogene Fehler und die Skip-Tabelle `chunk_full_text_skip`. Er war zugleich Vorbedingung für die Anhebung der `tsvector`-Version (#1166).

**Verifikation:** Die Lieferung existiert zum Stichtag nicht mehr. PR #1290 (04.09.2026) hat `FullTextBackfillService` samt Isolation, Scheduler und Tor entfernt und `chunk_full_text_skip` per Changeset gelöscht (siehe Baustein #1047).

**Themen:** volltext, backfill, robustheit, rückbau

---

<a id="issue-1127"></a>

## Issue #1127 — Bestandsnachzug: reaktivierte Fremd-Berechtigungen behalten den alten Vergeber
- Geschlossen: 2026-09-04 (completed)
- Labels: backend, size:S, security, auth
- PRs: keine
- Befund: 1 (ohne Umsetzung geschlossen)

**Laut Issue:** Ein Changeset `009` mit Delta-Test sollte `asset_grants.granted_by_user_id` für reaktivierte fremde Grants aus `asset_grant_history` rekonstruieren. Changeset `008` erfasste nur Rollenwechsel, sodass solche Bestandszeilen `holdsIndependentOwnerRole` täuschen konnten.

**Geliefert:** Nicht umgesetzt. Der Vorgang wurde laut Schließkommentar vom 04.09.2026 auf Maintainer-Entscheidung ohne Umsetzung geschlossen: Es gibt keine Bestandssysteme, auf die ein Nachzug Rücksicht nehmen müsste. Die Laufzeitkorrektur aus PR #1121 deckt künftige Zeilen ab, Changeset `008` bleibt eingefroren.

**Verifikation:** `AssetGrant#updateRole` (`io.opaa.permission`) schreibt den Vergeber bei Rollenwechsel **oder** Reaktivierung fort (`revived`). Ein Rekonstruktions-Changeset für den Bestand existiert nicht. Die Liquibase-Historie ist inzwischen zu Baselines zusammengefasst (#1504).

**Themen:** berechtigungen, bestandsnachzug, keine-bestandssysteme, nicht-geliefert

---

<a id="issue-1170"></a>

## Issue #1170 — fix(indexing): document_id = NULL haelt das Volltext-Gate einer Bibliothek dauerhaft geschlossen
- Geschlossen: 2026-09-03 (completed)
- Labels: bug, backend, size:S
- PRs: #1186 (2026-09-03)
- Befund: 3 (mit dem Volltext-Tor entfernt)

**Laut Issue:** Eine `vector_store`-Zeile mit `document_id = NULL` wurde vom Backfill nie ausgewählt, aber als fehlend gezählt. Dadurch hielt `FullTextBackfillGate` die ganze Bibliothek dauerhaft aus dem lexikalischen Pfad. Solche Zeilen sollten als bestätigter Skip verbucht werden.

**Geliefert:** PR #1186 streicht den `IS NOT NULL`-Filter in `selectPending`, lässt den Schlüssel im RowMapper aus und verbucht die Zeile als Skip. Geliefert wie beschrieben, mit Reproduktionstest.

**Verifikation:** Die Lieferung existiert zum Stichtag nicht mehr. PR #1290 (04.09.2026) hat Backfill, Tor (`FullTextBackfillGate`) und Skip-Tabelle entfernt, damit entfiel der Fehlermodus samt Fix. Heute gibt es nur noch die Anzeige `FullTextIndexFillStateService` ohne sperrendes Tor.

**Themen:** volltext, backfill, tor, bugfix, rückbau


---

<a id="issue-1211"></a>

## Issue #1211 — feat(query): Mail-Filterung nach Absender/Zeitraum/Betreff (#1164 Stufe 2)
- Geschlossen: 2026-09-05 (completed)
- Labels: enhancement, backend, frontend
- PRs: keine (erledigt durch #1339 zu #1242, 2026-09-05)
- Befund: 2 (anders geschnitten)

**Laut Issue:** Zweite Stufe von #1164: Mails nach Absender und Betreff (Teilstring) sowie nach Zeitraum filtern. Beide Suchpfade (Vektor und Volltext) sollen dieselbe Kandidatenmenge sehen. Dazu war ein Vorab-Resolver vorgeschlagen, der per `ILIKE` über die Chunk-Metadaten `document_id`s ermittelt und als identischen Filter in beide Pfade einspeist.

**Geliefert:** Kein eigener PR. Laut Abgleichkommentar vom 05.09.2026 ist die Filterung über das Metadatenschema gebaut (PR #1339 zu #1242), nicht auf dem skizzierten Sonderweg; der Vorab-Resolver entfällt ersatzlos. Abweichungen: Der Absender filtert als **Genau-Treffer** auf die normalisierte Adresse (Formatfeld „Kennung nach Muster"), nicht als Teilstring. Ein **Betreff-Filter entfällt** bewusst, weil der Betreff ein nicht filterbares Anzeigefeld ist und der Volltext ihn abdecken soll. Der Zeitraum läuft über das Kernfeld Datum/Stand (#1070). Beide Pfade tragen dieselbe Bedingung, das belegt ein Integrationstest.

**Verifikation:** `FormatMetadataField` existiert (15 Fundstellen in Backend/Frontend/API). Die alten Sonderfelder `formatMailSummary` und `ChunkMailMetadata` sind entfernt. Teilstring-Filter auf Absender oder Betreff gibt es im Code nicht.

**Themen:** retrieval, metadatenfilter, mail, formatfelder, schnitt

---

<a id="issue-1215"></a>

## Issue #1215 — feat(indexing): Routing-Schluessel-Nachtrag fuer den Altbestand (betreiberausgeloest)
- Geschlossen: 2026-09-03 (completed)
- Labels: enhancement, backend, size:M
- PRs: keine
- Befund: 1 (nicht geliefert)

**Laut Issue:** Ein betreiberausgelöster Bestandslauf sollte den Routing-Schlüssel (`routing_extension`) nachtragen, und zwar für Chunks, die vor #1126 indiziert wurden. Der Lauf sollte ohne erneutes Parsen oder Embedding auskommen und nach dem Vorbild des Volltext-Backfills gebaut werden (Batch, Fortschritt, Gift-Chunk-Isolation). Ausgelöst werden sollte er über einen Admin-Endpunkt.

**Geliefert:** Nichts gebaut, trotz `completed`. Maintainer-Entscheidung vom 03.09.2026: In der Alpha-Phase gibt es keinen schützenswerten Altbestand. Die Demo-Instanz wird stattdessen komplett neu bespielt, weil eine Neuindizierung den Schlüssel ohnehin auf jeden Chunk schreibt. Die Endungsheuristik aus #1105/#1125 bleibt als dokumentierter Rückfallweg. Die Entscheidung ist ein Vorläufer des allgemeinen Beschlusses „keine Bestandssysteme" vom 04.09.2026.

**Verifikation:** Im Code gibt es keinen Nachtrag-Lauf für den Routing-Schlüssel. Auch das Vorbild `FullTextBackfill*` ist seit #1270 entfernt (0 Fundstellen).

**Themen:** indexing, bestandslauf, routing, keine-bestandssysteme, not-delivered

---

<a id="issue-1226"></a>

## Issue #1226 — chore(ci): Build- und CI-Laufzeiten senken — Messung, Sharding, Kontext-Audit, e2e-Pfadfilter (Sammelticket)
- Geschlossen: 2026-09-08 (completed)
- Labels: enhancement, backend, size:M, ci
- PRs: keine (Umsetzung in #1411, 2026-09-08, ohne Closes-Verknüpfung)
- Befund: 2 (anders geschnitten)

**Laut Issue:** Das Sammelticket nannte sieben Kandidaten zur Senkung der CI-Wartezeit: (1) Messung, (2) Sharding des Backend-Jobs, (3) E2E aus dem kritischen Pfad, (4) Kontext-Audit, (5) Migrationstest-Container teilen, (6) JUnit-Parallelität, (7) Testcontainers-Reuse lokal.

**Geliefert:** Punkte 1–3 wurden umgesetzt: Messung im Issue-Kommentar, dazu Sharding in drei Shards und E2E aus dem PR-Pfad (#1411). Punkt 4 wurde geprüft und verworfen (< 5 % Gewinn), ebenso 5 und 6 nach der Nachmessung. Punkt 7 wurde als #1414 abgetrennt und dort ebenfalls nicht umgesetzt. Nachmessung auf `main` laut Abschlusskommentar: Backend-Pfad 3:58 statt 7:07 min, Gesamt-CI 4:09 statt 7:20 min.

**Verifikation:** `.github/workflows/ci.yml` enthält die Matrix `shard: [api, indexing, core]`. In den Testbasisklassen gibt es kein `withReuse` (0 Fundstellen).

**Themen:** ci, build-performance, sharding, e2e, sammelticket

---

<a id="issue-1263"></a>

## Issue #1263 — feat(indexing): Dokumentart auch aus Dokumentkopf und Dateiformat ableiten (Füllstand 0 % auf der Demo)
- Geschlossen: 2026-09-04 (completed)
- Labels: enhancement, backend, size:S
- PRs: #1272 (2026-09-04)
- Befund: 3 (nicht mehr vorhanden, teilweise)

**Laut Issue:** Die Dokumentart sollte zusätzlich aus dem Dokumentkopf abgeleitet werden: erste Überschrift und die ersten ~300 Zeichen, exakter Wortabgleich gegen das Vokabular. Außerdem sollte das Dateiformat als Quelle dienen (PPTX/ODP → `PRAESENTATION`). Anlass war ein Füllstand der Dokumentart von 0 % auf der Demo.

**Geliefert:** PR #1272 liefert die Kopfregel über einen 300-Zeichen-Kopftext (`DocumentHeadText`). Hinzu kommen eine Kompositum-Endung für Dateinamen, die nicht im Issue stand, und die Formatquelle. Extraktionsversion 2.

**Verifikation:** Die Kopfregel wurde noch am selben Tag zurückgenommen. Nach dem Deployment erzeugte sie falsche deterministische Werte: 83 Leistungsbeschreibungen erhielten `FORMULAR`, ein FAQ erhielt `DIENSTANWEISUNG`. #1289 (PR #1299) beschränkt die Kopfquelle deshalb auf die **Titelzeile** (`DocumentTitleLine`, Extraktionsversion 3). Der 300-Zeichen-Kopfabgleich für die Dokumentart existiert nicht mehr. `CoreMetadataExtractor` (heute `io.opaa.metadata`, Extraktionsversion 5) liest für die Dokumentart nur `titleLine()`. Formatquelle (`.pptx`/`.odp` → `PRAESENTATION`) und Kompositum-Regel bestehen fort. Der heutige `DocumentHeadText` ist ein anderer Kopftext: bis 4.000 Zeichen, eingeführt mit #1360, nur für verankerte Datumsangaben.

**Themen:** metadatenschema, dokumentart, deterministische-extraktion, rueckbau-im-zeitraum

---

<a id="issue-1264"></a>

## Issue #1264 — SearchIndexingAdminPage rendert bei jedem Tastendruck vollständig neu
- Geschlossen: 2026-09-04 (completed)
- Labels: enhancement, frontend, size:S
- PRs: #1266 (2026-09-04)
- Befund: 3 (nicht mehr vorhanden)

**Laut Issue:** Eingaben in den Diagnosefeldern der Seite „Suche & Indexierung" sollten nur den Diagnosebereich neu rendern. Teure Teilbäume wie `LibraryStatusTable` sollten dafür memoisiert werden. Ein Render-Zähler-Test sollte das belegen.

**Geliefert:** PR #1266 umschließt `LibraryStatusTable` mit `React.memo`, stabilisiert `onStartBackfill` per `useCallback` und führt die referenzstabile Konstante `EMPTY_LIBRARIES` ein. Dazu kommt ein Render-Zähler-Test über `LibraryStatusTable.type`.

**Verifikation:** Die Memo-Kette wurde noch am selben Tag ersetzt. #1267 (PR #1284) zieht das Diagnoseformular in eine eigene Komponente mit eigenem Zustand (`DiagnosisForm.tsx`, `DocumentChunkSection.tsx`, `LibraryStatusTable.tsx` unter `frontend/src/components/searchadmin/`). `React.memo`, `useCallback` und `EMPTY_LIBRARIES` sind dabei entfallen (0 Fundstellen). Das Ziel „Tabelle rendert beim Tippen nicht neu" gilt weiterhin, erreicht wird es jetzt über den Zuschnitt statt über Memoisierung.

**Themen:** frontend, performance, searchadmin, rueckbau-im-zeitraum

---

<a id="issue-1288"></a>

## Issue #1288 — Zerlegende Pipeline-Baseline: zweite Domäne messen und über den Festpunktwechsel entscheiden
- Geschlossen: 2026-09-17 (completed)
- Labels: backend, size:M, evaluation
- PRs: #1679 (2026-09-17)
- Befund: 2 (anders geschnitten)

**Laut Issue:** Die Zerlegungsqualität sollte auf mindestens einer zweiten Domäne (`city-landmarks` oder `comic-characters`) gemessen werden. Danach sollte entschieden werden, ob die Pipeline-Baselines auf `queryDecompositionEnabled=true` umgestellt werden. Bei „dagegen" sollte die Entscheidung in `retrieval-benchmark.md` festgehalten werden.

**Geliefert:** PR #1679 (Doku) entscheidet **gegen** den Festpunktwechsel: Die Einzelfragen-Baselines bleiben zerlegungsfrei. Die Messung auf einer zweiten Domäne wurde **nicht durchgeführt**, weil die Entscheidung laut PR nicht mehr an ihr hängt. Begründung: Die Zerlegung hat seit #1553 einen eigenen Mehrrunden-Pfad mit eigener Baseline und eigenem CI-Job, und auf eigenständigen Fragen bringt sie messbar nichts (Nachmessung aus #1254). Derselbe PR schließt #1210 (Rangreserve bleibt report-only).

**Verifikation:** Reine Doku-Entscheidung, am Code nichts zu prüfen. Die Kennzahl `marginAtK` (#1210) besteht weiterhin nur als Ausweis. Das Abnahmekriterium „zweite Domäne messen" ist nicht erfüllt.

**Themen:** evaluation, baseline, query-decomposition, entscheidung

---

<a id="issue-1330"></a>

## Issue #1330 — feat(auth): Anmeldefluss und Kontenmodell je Anbieter — keine Vermischung von Identitäten
- Geschlossen: 2026-09-06 (completed)
- Labels: enhancement, backend, size:L, auth
- PRs: #1343 (2026-09-06)
- Befund: 3 (nicht mehr vorhanden, teilweise)

**Laut Issue:** Identität strikt als (Anbieter, Subject), keine Zusammenführung über die E-Mail. Tokens deaktivierter Anbieter werden abgewiesen. Die Erstadministrator-Regel (`opaa.auth.initial-admin-email`) gilt je Anbieter und darf nicht über einen zweiten Anbieter gekapert werden. Die Abmeldung erfolgt beim jeweiligen Anbieter.

**Geliefert:** PR #1343 liefert `InitialAdminPolicy`: Die Erstadmin-Regel bindet an den Issuer des Standardanbieters. Getrennte Konten bei gleicher E-Mail und die Abweisung unbekannter Issuer (`unknown_issuer`) sind mit Tests belegt. Die Abmeldung ist in die SPA verlagert (#1332).

**Verifikation:** Kontentrennung und Issuer-Abweisung bestehen fort (`OidcProviderRegistry`, 37 Fundstellen). Die **Erstadministrator-Regel für OIDC** ist dagegen seit ADR-0033 (lokale Benutzerverwaltung, Epic #1529, Mitte September) entfallen. `backend/src/main/java/io/opaa/auth/InitialAdminPolicy.java` gewährt im `oidc`-Modus nichts mehr, auch nicht über den Standardanbieter. Der erste Administrator ist jetzt das lokale Notanker-Konto aus dem Seed. Nur im `dev`-Modus hat die Regel noch Wirkung.

**Themen:** auth, oidc, mehranbieter, erstadministrator, rueckbau-im-zeitraum

---

<a id="issue-1364"></a>

## Issue #1364 — feat(indexing): Metadatenschema nachkalibrieren — Extraktionsgüte, Konfidenzschwelle, Umschlüsselung
- Geschlossen: 2026-09-17 (completed)
- Labels: enhancement, epic, backend, size:M
- PRs: keine (Epic; Lieferung in #1360/PR #1699 und #1361/PR #1701)
- Befund: 2 (anders geschnitten)

**Laut Issue:** Das Epic sah drei Phasen vor: deterministische Quellen richtigstellen (#1360), Modell-Extraktion nachkalibrieren (#1359: Schwelle 0,90, Vokabular erweitern, zweite Handstichprobe mit 100 Dokumenten) und die Umschlüsselung wiederaufnehmbar machen (#1361). Abnahme: Modell-Fehlerquote < 5 % oder dokumentiert nicht abgenommen, deterministische Fehlerquote < 5 %, alle Nachläufe mit Betriebszusagen.

**Geliefert:** Am 17.09.2026 wurde der Umfang eingekürzt. #1359 (Modell-Kalibrierung) und #1702 wurden unter das geparkte Epic #1704 „Metadaten-Nacharbeiten — geparkt bis das Produkt weiter ist" umgehängt, zusammen mit #1362, #1363 und #1260. Geliefert wurden #1360 (Generator-Datumsvorgaben verworfen, Inkrafttreten aus dem Kopf, späterer Titel-Fallback; laut Abschlusskommentar Titelfehler 14 % → 0 %) und #1361 (zweiphasige Umschlüsselung und Feldlöschung). Die zweite Handstichprobe und die Abnahme der Modell-Extraktion fehlen. Die Abnahmekriterien gelten laut Kommentar in #1704 unverändert weiter.

**Verifikation:** `GeneratorDefaultDate` und `DocumentBatchLoop` existieren, `EXTRACTION_VERSION = 5` in `CoreMetadataExtractor`. #1704 und #1359 sind zum Stichtag offen.

**Themen:** metadatenschema, modell-extraktion, geparkt, epic-eingekuerzt

---

<a id="issue-1366"></a>

## Issue #1366 — Schema-Portabilität der Liquibase-Changesets: reine SQL-Blöcke und hartes `public.`-Präfix
- Geschlossen: 2026-09-18 (completed)
- Labels: enhancement, backend
- PRs: #1730 (2026-09-18)
- Befund: 2 (anders geschnitten)

**Laut Issue:** Per ADR sollte über den Changeset-Stil (reines SQL oder native Liquibase-Changes) und das Schemapräfix entschieden werden. DDL und JPA sollten konsistent gemacht werden. Abnahmekriterium unter anderem: „Bestehende Installationen migrieren ohne Checksummenfehler weiter", notfalls über `validCheckSum` oder einen Umstellungs-Changeset.

**Geliefert:** PR #1730 und ADR-0034: Die Changesets bleiben bewusst reines PostgreSQL-SQL. `public.` ist aus Baseline und allen Deltas entfernt. Das Zielschema ist über `opaa.database.schema`/`OPAA_DB_SCHEMA` konfigurierbar (Verbindung mit `currentSchema`, Liquibase-Default-Schema, Vektorspeicher). `DatabaseSchemaGuard` prüft das Schema beim Start. Nebenbei wurde ein Rechtefehler (`USAGE` für `opaa_audit_owner`) gefunden. Abweichend vom Abnahmekriterium ist die Änderung **nicht abwärtskompatibel**, mit Maintainer-Absprache: Alle Checksummen ändern sich, bestehende Installationen werden neu aufgesetzt.

**Verifikation:** In `backend/src/main/resources/db/changelog` gibt es 0 Vorkommen von `public.`. `application.yml` enthält `schema: ${OPAA_DB_SCHEMA:public}`, `DatabaseSchemaGuard` existiert.

**Themen:** datenbank, liquibase, schema-portabilitaet, betrieb, keine-bestandssysteme

---

<a id="issue-1414"></a>

## Issue #1414 — chore(test): Testcontainers-Reuse für lokale Läufe als Opt-in
- Geschlossen: 2026-09-08 (completed)
- Labels: enhancement, backend, size:S
- PRs: keine
- Befund: 1 (nicht geliefert)

**Laut Issue:** Aus #1226 (Punkt 7) abgetrennt: Postgres- und MinIO-Container der kanonischen Test-Meta-Annotationen sollten per `withReuse(true)` wiederverwendbar werden, als Opt-in über `~/.testcontainers.properties`. Dazu gehörten die Doku in AGENTS.md und eine unveränderte CI-Laufzeit.

**Geliefert:** Nichts. Schließkommentar vom 08.09.2026: „Wird nicht umgesetzt, zu unwichtig." Geschlossen als `completed` statt `not planned`.

**Verifikation:** Kein `withReuse` in `backend/src/test` (0 Fundstellen).

**Themen:** testinfrastruktur, testcontainers, not-delivered

---

<a id="issue-1428"></a>

## Issue #1428 — Rechteprobe je Anfrage entfernen; Historien-Schreibpfade per Test absichern
- Geschlossen: 2026-09-11 (completed)
- Labels: enhancement, backend, size:S, security, auth
- PRs: #1480 (2026-09-11)
- Befund: 3 (Rückbau einer Vorzeitraum-Lieferung)

**Laut Issue:** Kritisch prüfen, ob die Rechteprobe je Chat-Frage nötig ist. Sie stammt aus #238 (Historisierung von Rechten, geschlossen 17.08.2026) und wurde später auf eine Stichprobe umgestellt (#889). Die Probe vergleicht die live berechnete Menge lesbarer Bibliotheken mit der Rekonstruktion aus der Rechtehistorie. Ergebnis sollten Messwerte und eine Empfehlung sein, gegebenenfalls ein Folge-Issue.

**Geliefert:** PR #1480 setzt die Maintainer-Entscheidung vom 11.09.2026 direkt um, ohne getrenntes Folge-Issue. Die Rechteprobe je Anfrage ist **entfernt**: `maybeCheckAgainstPermissionHistory`/`checkAgainstPermissionHistory`, `QueryProperties#permissionHistorySampleRate` und `OPAA_QUERY_PERMISSION_HISTORY_SAMPLE_RATE`. Als Ersatz dient ein `@TestFactory`-Integrationstest in `PermissionHistoryServiceIntegrationTest`. Er hält für 17 Schreiboperationen Live-Formel und Historien-Rekonstruktion gegeneinander. Die Historie selbst und `readableLibraryIdsAsOf` bleiben.

**Verifikation:** `permissionHistorySampleRate` und `PERMISSION_HISTORY_SAMPLE_RATE` haben 0 Fundstellen. Die Laufzeit-Prüfung aus der Vorzeitraum-Lieferung #238 existiert damit nicht mehr, die Absicherung liegt jetzt im Test.

**Themen:** rechte, historisierung, performance, rueckbau-vorzeitraum, security


---

<a id="issue-1482"></a>

## Issue #1482 — feat: Gesprächsgedächtnis — Gesprächsfenster und sichtbare Gesprächsnotiz
- Geschlossen: 2026-09-12 (completed)
- Labels: enhancement, epic, backend, frontend, evaluation
- PRs: keine (Epic; Lieferung über #1483–#1490 und #1553)
- Befund: 2 (Abnahmeziel verfehlt)

**Laut Issue:** OPAA soll in langen Unterhaltungen und bei Themenwechseln berechenbar werden. Dafür werden ein kurzes, wörtliches Gesprächsfenster (Suchfenster zwei Runden) und eine sichtbare, punktweise löschbare Gesprächsnotiz je Chat gebaut. Die Wirkung soll mit Mehrrunden-Golden-Fällen vorher und nachher gemessen werden. Erwartet war: `topic_switch` steigt, `anaphora_resolution` fällt nicht, `constraint_carryover` erreicht mindestens den Stand aus Phase 1.

**Geliefert:** Alle Bauteile sind gebaut: Spezifikation und ADR-0031 (PR #1494), Mehrrunden-Messpfad (PR #1499), 27 Golden-Fälle (PR #1521), Fenster (PR #1523), Notiz in Backend und Oberfläche (PR#1570, #1572), E2E (PR #1580), CI-Verdrahtung (PR #1583) und Nachmessung (PR #1585). Schon der Abschlusskommentar vom 12.09. hält fest, dass `constraint_carryover` die Referenz verfehlt (4 → 1 von 9 gelösten Fällen). Danach wurde die Messung selbst entwertet. Laut #1587 war der gemessene Gesamtgewinn eine Hardware-Signatur (AVX-512-Host). Nach Neumessung mit fester CPU-Variante (#1662) ist für keinen der beiden Bausteine eine Verbesserung belegt. Die Ablationskette (PR#1670, #1678) zeigt mit dem Eval-Modell sogar Verluste: Das Verkürzen des Suchfensters von 10 auf 2 Runden kostet 2 gelöste Fälle, die Notiz weitere 4 („sie schadet der Suche“). Gegen ein Produktionsmodell trägt die Notiz die Rahmenangabe dagegen (PR #1682). Das Epic ist damit funktional geliefert, sein Qualitätsziel aber nicht belegt.

**Verifikation:** `ChatNoteExtractionService`, `ChatNoteService` (`io.opaa.chat`), `ConversationNoteBlock` und `DecompositionContext` (`io.opaa.retrieval`) existieren. `application.yml` führt `conversation-window-messages` (20) und `search-window-turns` (2). Der nächtliche Job `conversations` in `retrieval-regression.yml` läuft. Die Baseline wurde nach PR #1585 mehrfach neu gezogen (#1662, #1664, #1690).

**Themen:** gesprächsgedächtnis, retrieval, evaluation, messbarkeit, mehrrunden

---

<a id="issue-1490"></a>

## Issue #1490 — test(eval): Nachmessung Mehrrunden-Klassen nach Fenster und Notiz — Baseline, Zustandswechsel, Befund
- Geschlossen: 2026-09-12 (completed)
- Labels: backend, size:S, evaluation
- PRs: #1585 (2026-09-12)
- Befund: 3 (Messung zurückgezogen)

**Laut Issue:** Den Endstand der drei Mehrrunden-Klassen nach Fenster und Notiz messen (Median aus drei Läufen, CPU-Testcontainer). Außerdem die Baseline neu ziehen, Zustandswechsel datiert eintragen und den Abschlussbefund vorher/nachher in #1446 posten.

**Geliefert:** PR #1585 hat gemessen, die Baseline `eval/baseline/pipeline-verwaltung-conversations.json` neu gezogen und den Befund gepostet (u. a. overall Hit Rate@5 0,880, auf CI-Hardware mit Delta ±0,000 bestätigt). Diese Messung wurde im selben Zeitraum zurückgezogen. Sie lief auf einem Host mit AVX-512, und unter fester ggml-CPU-Variante liegt derselbe Stand bei 0,831 statt 0,880 und 5 statt 8 gelösten Fällen (#1587, Nachtrag 16.09.). Der Referenzwert vom 11.09. lag ebenfalls bei 0,831. Der berichtete Vorher/Nachher-Gewinn existiert damit nicht mehr.

**Verifikation:** Die Baseline-Datei trägt seit Commit `3e7bad193` (#1662, 15.09.2026, „Mehrrunden-Messung durch feste ggml-CPU-Variante stabilisieren und Baseline neu ziehen“) nicht mehr den Stand aus PR #1585. Danach folgten #1664 und #1690.

**Themen:** evaluation, baseline, reproduzierbarkeit, gesprächsgedächtnis

---

<a id="issue-1492"></a>

## Issue #1492 — chore(db): Liquibase-Historie ein zweites Mal zu einer Baseline zusammenfassen (56 Changesets aus 33 Dateien → thematisch neu gruppierte Baseline)
- Geschlossen: 2026-09-11 (completed)
- Labels: enhancement, backend, size:L
- PRs: #1504 (2026-09-11)
- Befund: 3 (ersetzt)

**Laut Issue:** Die 56 Changesets aus 33 Dateien sollen ein zweites Mal zu einer Baseline zusammengefasst werden, gegliedert in wenige thematische Changesets. Ein Schemavergleich soll die Äquivalenz nachweisen. Danach gilt wieder: ein Changeset je Änderung mit Delta-Test.

**Geliefert:** PR #1504 ersetzt `001-baseline.yaml` durch 13 thematische Gruppen plus zwei geschützte Einzel-Changesets. Die Delta-Tests `Migration002…033` entfallen. Umgesetzt wie beschrieben.

**Verifikation:** `backend/src/main/resources/db/changelog/changes/` existiert nicht mehr. Am 27.09.2026 wurde die Historie ein drittes Mal zusammengefasst (#2007) und je logischem Modul aufgeteilt (#2010). Heute gibt es `db/changelog/<modul>/2026-09-27-baseline.yaml` (assistant, connectors, external, foundation, identity, knowledge, rights, workspace). Der Schemainhalt der zweiten Baseline lebt darin fort; die Datei selbst und ihre Gruppierung a–o sind ersetzt.

**Themen:** liquibase, datenbank, baseline, migration

---

<a id="issue-1496"></a>

## Issue #1496 — Sichtbarkeit einer Bibliothek strukturell auf io.opaa.library beschränken (Historienzeile nicht umgehbar)
- Geschlossen: 2026-09-11 (completed)
- Labels: backend, size:S, security, auth
- PRs: #1502 (2026-09-11)
- Befund: 3 (ersetzt)

**Laut Issue:** `KnowledgeLibrary#updateDetails` war `public`. Damit hätte jede Klasse die `visibility` einer Bibliothek ändern können, ohne `LibraryChanged` zu veröffentlichen und ohne Historienzeile. Die Änderung sollte nur noch aus `io.opaa.library` möglich sein, abgesichert durch den Compiler.

**Geliefert:** PR #1502 macht `updateDetails` paketsichtbar. `visibility` und `listed` sind danach nur noch aus dem Bibliothekspaket veränderbar, und die Invariante steht als Kommentar an der Methode.

**Verifikation:** `KnowledgeLibrary` (heute `io.opaa.knowledge`) hat weder `updateDetails` noch ein Feld `visibility`. Mit #1945 (24.09.2026, „Reichweite als Freigabe an ‚Alle Konten‘ — visibility entfällt“) wurde die Verteilungsstufe `PRIVATE/SHARED/ORGANIZATION` abgeschafft. Organisationsweite Reichweite ist jetzt ein Grant an `ALL_ACCOUNTS` in derselben Tabelle und Historie wie jede andere Berechtigung. Die abgesicherte Tür existiert damit nicht mehr, und ihr Zweck wird strukturell über das Grant-Modell erfüllt.

**Themen:** rechte, rechtehistorie, bibliothek, kapselung, nachweisbarkeit

---

<a id="issue-1497"></a>

## Issue #1497 — fix(library): Rechtehistorie verliert Zustände, die kürzer als ein Uhr-Tick bestanden — Stichtags-Rekonstruktion antwortet falsch
- Geschlossen: 2026-09-11 (completed)
- Labels: bug, backend, size:M, security
- PRs: #1507 (2026-09-11)
- Befund: 2 (gegenteiliger Weg)

**Laut Issue:** Die Intervallgrenzen der Rechtehistorie stammten aus `Instant.now()`. Auf Windows springt diese Uhr in Schritten von rund 3,6 ms, sodass ein Zustand als leeres Intervall verloren ging. Vorgeschlagen war eine streng monotone Zeitquelle über die Datenbankuhr (`clock_timestamp()`). **Ausdrücklich verworfen** wurde die Variante, die Grenze in der JVM um eine Mikrosekunde anzuheben: Sie erfinde Zeitstempel, helfe nicht bei nebenläufigen Schreibern und sei nur unter der Single-Instance-Annahme (ADR-0021) zulässig.

**Geliefert:** PR #1507 baut genau die verworfene Variante: `PermissionHistoryClock` liefert prozesslokal „letzte Grenze + 1 µs“. ADR-0032 begründet die Wahl gegen die Datenbankuhr. Die datenbankseitige Absicherung derselben Invariante (#1517, `EXCLUDE`-Constraint) wurde am selben Tag als „not planned“ geschlossen. Die Zusage hängt damit allein am Anwendungsprozess und an ADR-0021. Der Fehler selbst ist behoben, die Abnahmekriterien sind bis auf den Zehnfachlauf abgehakt.

**Verifikation:** `backend/src/main/java/io/opaa/permission/PermissionHistoryClock.java` existiert, ADR-0032 liegt unter `docs/decisions/`.

**Themen:** rechtehistorie, nachweisbarkeit, zeitquelle, single-instance, adr

---

<a id="issue-1501"></a>

## Issue #1501 — fix(eval): Nächtliche Retrieval-Regression rot — Rohvektor-Pfad verliert literal_term_weak_embedding
- Geschlossen: 2026-09-11 (not planned)
- Labels: bug, backend, evaluation
- PRs: keine
- Befund: 1 (Duplikat)

**Laut Issue:** Der nächtliche Job `evaluate (verwaltung)` war seit dem 05.09.2026 durchgehend rot, weil der Rohvektor-Pfad in `literal_term_weak_embedding` recall@10 von 0,611 auf 0,333 verlor. Ursache benennen und den Lauf wieder grün bekommen.

**Geliefert:** Nicht in diesem Vorgang. Geschlossen als Doppelanlage von #1308, das der automatische Lauf bereits am 05.09. eröffnet hatte. Eingrenzung und Klärungsfragen wurden dorthin übertragen.

**Verifikation:** Entfällt (keine Lieferung). Der Stand der Regression ist bei #1308 zu verfolgen.

**Themen:** evaluation, regression, rohvektor, duplikat

---

<a id="issue-1505"></a>

## Issue #1505 — Existenz einer Bibliothek strukturell absichern: Anlegen und Löschen am Historieneintrag vorbei ist möglich (allgemeine Regel + ADR)
- Geschlossen: 2026-09-11 (not planned)
- Labels: backend, size:L, security, auth
- PRs: keine
- Befund: 1 (nicht geliefert)

**Laut Issue:** Die zweite Hälfte der Formeleingabe sollte abgesichert werden: Bibliothekszeilen entstehen und verschwinden über das öffentliche `KnowledgeLibraryRepository`, also ohne Historienzeile. Gefordert waren eine allgemeine Regel samt ADR und ein Netz, das neue Schreiber erkennt.

**Geliefert:** Nicht umgesetzt. Maintainer-Entscheidung vom 11.09.2026: Der Aufwand steht in keinem Verhältnis. Nur vier Klassen schreiben tatsächlich, keine davon berührt Bestand oder Sichtbarkeit, und der einzige Beleg ist eine Eval-Fixture. Die Lücke bleibt eine „Beobachtung“ im Javadoc des Bohnen-Scans.

**Verifikation:** Entfällt. Seit #1945 ist die organisationsweite Reichweite ein historisierter Grant. Eine am Service vorbei angelegte Bibliothek ist damit ohne Grant nicht mehr organisationsweit lesbar, was das Risiko weiter senkt.

**Themen:** rechtehistorie, bibliothek, bewusster-schnitt

---

<a id="issue-1517"></a>

## Issue #1517 — Invariante der Rechtehistorie datenbankseitig absichern (EXCLUDE-Constraint je Objekt)
- Geschlossen: 2026-09-11 (not planned)
- Labels: enhancement, backend, size:M, security
- PRs: keine
- Befund: 1 (nicht geliefert)

**Laut Issue:** Die mit #1497/ADR-0032 zugesagte Invariante (streng aufsteigende Zustandsintervalle) sollte zusätzlich per `EXCLUDE`-Constraint mit `btree_gist` abgesichert werden. So hielte sie auch gegen Migrationen, SQL-Handkorrekturen und weitere Instanzen.

**Geliefert:** Nicht umgesetzt. Laut Maintainer ist das Szenario „zu exotisch“, und die Bestandszeilen von vor #1497 hätten den Constraint verletzt. Die Invariante bleibt anwendungsseitig über `PermissionHistoryClock` zugesichert, beschränkt auf Zeilen ab #1497 und einen Prozess.

**Verifikation:** Entfällt. Die Grenzen der Zusage sind in ADR-0032 („bewusst in Kauf genommen“) und im Javadoc von `PermissionHistoryService` benannt.

**Themen:** rechtehistorie, datenbank-invariante, bewusster-schnitt, nachweisbarkeit

---

<a id="issue-1535"></a>

## Issue #1535 — feat(auth): Rate-Limiting mit Trusted-Proxy-Auflösung und Kontosperre nach Fehlversuchen
- Geschlossen: 2026-09-12 (completed)
- Labels: enhancement, backend, size:M, security, auth
- PRs: keine verknüpft (Stapel-Lieferung über PR#1599, 2026-09-12; Commits `a8aa1c268`, `cbf60e823`, `4891efa8e`)
- Befund: 2 (anderer Weg als beschlossen)

**Laut Issue:** Das Issue fordert Rate-Limiting je IP und Konto für Anmelde- und Passwortendpunkte, eine Kontosperre nach Fehlversuchen und eine Client-IP-Auflösung, die `X-Forwarded-For` nur von vertrauten Proxys annimmt. Den Weg dafür legt ADR-0033, Entscheidung 9, in der Fassung nach #1556/PR #1557 fest: `server.forward-headers-strategy: native` mit Tomcats `RemoteIpValve`, kein eigener Filter. „`framework` behalten und nur die Adresse gegenprüfen“ steht dort ausdrücklich unter „Verworfene Alternativen“.

**Geliefert:** Rate-Limiting, Kontosperre und Trusted-Proxy-Auflösung sind gebaut. Die Auflösung folgt aber nicht dem ADR. `application.yml` behält `forward-headers-strategy: framework`, und ein eigener `TrustedProxyClientIpResolver` liest Verbindungsadresse und Roh-Header „unterhalb aller Wrapper“ von Springs `ForwardedHeaderFilter` (Commit `cbf60e823`). Das ist die im ADR verworfene Variante, umgangen über das Auspacken der Wrapper. Das ADR wurde dazu nicht nachgezogen: Entscheidung 9, die Zuschnitt-Tabelle für #1535 und die Konsequenzen nennen weiter `native`/`RemoteIpValve`.

**Verifikation:** `backend/src/main/resources/application.yml:7` steht auf `forward-headers-strategy: framework`. `io.opaa.security.TrustedProxyClientIpResolver` und `io.opaa.ratelimit.TrustedProxyStartupGuard` existieren. `docs/decisions/0033-lokale-benutzerverwaltung.md` (Zeilen 581 ff., 1006, 1095, 1163) beschreibt weiterhin `native`.

**Themen:** rate-limiting, trusted-proxy, adr-abweichung, lokale-anmeldung, sicherheit

---

<a id="issue-1578"></a>

## Issue #1578 — fix(ci): MinIO-Image von Docker Hub verschwunden — CI der S3-Suite bricht ab
- Geschlossen: 2026-09-11 (completed)
- Labels: bug, backend, size:S, ci
- PRs: #1579 (2026-09-11)
- Befund: 3 (ersetzt)

**Laut Issue:** Das Docker-Hub-Repository `minio/minio` war verschwunden. Fixture, Compose-Dienste (`minio`, `minio-seed`, `upload-store`, `upload-store-init`) und Renovate sollten auf eine erreichbare Registry umgestellt werden, bei gleichem Tag.

**Geliefert:** PR #1579 stellt alle Fundstellen auf `quay.io/minio/minio` um (gleicher Tag, gleicher Digest) und zieht Renovate-Regel und Doku nach. Geliefert wie beschrieben.

**Verifikation:** `docker-compose.yml` enthält kein MinIO-Image mehr. Die Objektspeicher-Dienste laufen mit `rustfs/rustfs:1.0.0`, die Init-Schritte mit `amazon/aws-cli`. Ersetzt am 24.09.2026 durch #1951 („MinIO als Test- und Compose-Image durch RustFS ablösen“). Die Community-Linie von MinIO war mit dem gepinnten Release beendet, was schon das Issue festhielt.

**Themen:** ci, objektspeicher, testcontainer, abhängigkeiten, s3

---

<a id="issue-1586"></a>

## Issue #1586 — fix(chat): Gesprächsnotiz verliert die Rahmenangabe — Verdichtungsformat und Aufnahme in die Teilfrage
- Geschlossen: 2026-09-16 (completed)
- Labels: bug, backend, size:M, evaluation
- PRs: keine
- Befund: 1 (nicht geliefert)

**Laut Issue:** In sechs von neun Messfällen hielt die Gesprächsnotiz die Rahmenangabe nicht fest. Das Eval-Modell füllte die Artenliste des Prompts als Vorlage aus, sodass Punkte wie „Arzt“ entstanden. Wo die Angabe festgehalten wurde, erreichte sie in zwei von drei Fällen die Teilfrage nicht. Verdichtungsformat und Aufnahme in die Zerlegung sollten korrigiert werden.

**Geliefert:** Keine Codeänderung, geschlossen als „completed“. Beide Beobachtungen wurden als Eigenschaft des gepinnten Eval-Modells `qwen2.5:1.5b-instruct` eingestuft. Mit `claude-haiku-4-5` entstehen 9 von 9 korrekte `RAHMEN`-Punkte (PR #1672), und 5 von 9 Zielrunden tragen die Angabe in der Teilfrage (PR #1682). `ChatNoteExtraction` blieb unverändert. Offen bleibt, dass der nächtliche Mehrrunden-Lauf eine Modellkonfiguration misst, die in keiner Installation läuft.

**Verifikation:** `io.opaa.chat.ChatNoteExtraction` existiert in unveränderter Logik (zwei Punkte je Runde, unbekanntes Präfix → `ANTWORTFORM`).

**Themen:** gesprächsnotiz, evaluation, modellabhängigkeit, eval-modell

---

<a id="issue-1594"></a>

## Issue #1594 — feat(auth): Übergabe eines lokalen Kontos an eine Anbieteridentität — administrativ angestoßen, von der Person eingelöst
- Geschlossen: 2026-09-12 (not planned)
- Labels: enhancement, backend, frontend, size:L, security, auth
- PRs: keine
- Befund: 1 (Duplikat)

**Laut Issue:** Ein lokales Konto sollte nach ADR-0033, Entscheidung 12, an eine OIDC-Identität übergeben werden. Der Systemverwalter stößt an, die Person löst mit Einmalcode und Anbieter-Token selbst ein.

**Geliefert:** Nicht in diesem Vorgang. Beim Epic-Abschluss als Duplikat von #1563 angelegt und geschlossen. Die Übergabe wurde über #1563 mit PR #1642 (2026-09-13) gebaut.

**Verifikation:** Die Übergabe ist vorhanden (Einlöseseite `/handover` im Frontend, Endpunkte `…/handover` und `…/handover/redeem`, siehe #1563).

**Themen:** lokale-konten, kontoübergabe, duplikat


---

<a id="issue-1629"></a>

## Issue #1629 — fix(auth): „Mit anderem Konto anmelden" fordert eine Re-Authentifizierung statt eines Kontowechsels
- Geschlossen: 2026-09-17 (completed)
- Labels: bug, frontend, size:S, auth
- PRs: #1687 (2026-09-17)
- Befund: 2 (Link entfernt statt repariert)

**Laut Issue:** Der Link „Mit anderem Konto bei … anmelden" schickte `prompt=login` und führte damit zur erneuten Anmeldung desselben Kontos statt zu einem Kontowechsel. Zur Wahl standen drei Wege: `prompt=select_account` samt passender Beschriftung, `max_age=0`/`login` mit ehrlicher Beschriftung „Erneut anmelden" oder den Link ganz streichen. Das Schwester-Issue #1630 wollte den Link zusätzlich nur nach einer vorherigen Anmeldung an diesem Browser zeigen.

**Geliefert:** Der Kontowechsel wurde nicht repariert, sondern **abgeschafft**. PR #1687 (Merge-PR zu #1631, automatische Anmeldung per `prompt=none`) enthält den Commit `9e62c0781` „‚Mit anderem Konto anmelden' entfernen — den Kontowechsel übernimmt der Anbieter". Begründung dort: Seit der automatischen Anmeldung ist die Anmeldeseite bei laufender Anbieter-Sitzung fort, bevor jemand den Link benutzen kann. `select_account` hilft gegen Keycloak nicht. Der funktionierende Weg zum Kontowechsel ist das Abmelden (RP-initiierter Logout). `switchAccount` entfällt im `authStore`, und ADR-0025 bekommt einen Nachtrag. Damit ist auch #1630 gegenstandslos: Der Link ist weg, statt nur an `lastUsedProviderId()` gebunden zu werden. Gewählt wurde also die dritte Option des Issues, nicht die als standardkonform beschriebene erste.

**Verifikation:** `frontend/src/pages/LoginPage.tsx` enthält weder „anderem Konto" noch `switchAccount`. `frontend/src/stores/authStore.ts` sendet `prompt` nur noch als `none` für den automatischen Versuch (`attemptSilentSignIn`). Einen Kontowechsel gibt es nur noch über Abmelden und erneutes Anmelden.

**Themen:** auth, oidc, anmeldeseite, kontowechsel, automatische-anmeldung

---

<a id="issue-1689"></a>

## Issue #1689 — fix(auth): Rücksprungadresse geht bei jeder Anmeldung über einen Identitätsanbieter verloren
- Geschlossen: 2026-09-17 (not planned)
- Labels: bug, frontend, size:S, auth
- PRs: keine
- Befund: 1 (Duplikat)

**Laut Issue:** Nach einer Anmeldung über einen Identitätsanbieter führt `AuthCallbackPage` fest auf `/chat`, und das von `ProtectedRoute` gemerkte Ziel geht verloren. Seit der automatischen Anmeldung (#1631) wiegt das schwerer. Das Ziel soll im OIDC-`state` mitreisen und beim Zurücklesen erneut durch `safeRedirectPath` laufen.

**Geliefert:** Nicht über dieses Issue. Laut Schließkommentar ist es ein Duplikat von #1685. Dessen PR #1686 war rund eine Viertelstunde vor dem Anlegen dieses Issues gemergt; der Befund stammte aus dem Review von PR #1687 und war nicht gegen `main` geprüft. Die Übernahme des Ziels in den automatischen Anmeldeversuch hat PR #1687 nachgezogen.

**Verifikation:** `frontend/src/pages/AuthCallbackPage.tsx` navigiert auf `outcome.returnTo` (Zeile 36). Die beschriebene Lücke ist damit über #1685/#1686 geschlossen.

**Themen:** auth, oidc, direktlink, duplikat


---

<a id="pr-1174"></a>

## PR #1174 — feat(indexing): Quellentyp CONFLUENCE — Datenmodell, Migration und Quellkonfiguration an der Bibliothek
- Gemergt: 2026-09-03
- Labels: enhancement, backend, frontend, size:M
- Bezug: #1135, #1136, #1134, #1133, #1129, #1173
- Befund: 3 (ersetzt)

**Laut PR:** Confluence als fünfte Herkunft nach ADR-0023: `DocumentSourceType.CONFLUENCE`, Enum `ConfluenceEdition`, Schema `ConfluenceSpaceRef`, Felder `confluenceEdition`/`confluenceSpaces` in `LibraryRequest`/`LibraryUpdateRequest`/`LibraryResponse`; Migration 010 mit Spalte `source_confluence_edition`, Confluence-Zweig der Konfigurations-Constraint und Kindtabelle `knowledge_library_confluence_spaces`; Edition und Space-Auswahl als `@ElementCollection` an `KnowledgeLibrary`.

**Geliefert:** Wie beschrieben gemergt, mit Delta-Tests und Mehrfach-Bibliotheken-Szenario. Das Datenmodell ist im selben Zeitraum wieder ersetzt worden: #1977 (PRs #1986/#1997, ADR-0038) hat die konnektorspezifischen Spalten, die Kindtabelle und die flachen API-Felder entfernt. Confluence-Einstellungen liegen seitdem als Konnektor-eigener Record in `source_settings` (jsonb), die API führt ein freies `sourceSettings`-Objekt, und das geschlossene Enum `DocumentSourceType` ist durch einen offenen Typ-Schlüssel ersetzt. Die Funktion (Confluence-Bibliothek mit Edition und Space-Auswahl) besteht fort.

**Verifikation:** `source_confluence_edition` und `knowledge_library_confluence_spaces` kommen unter `backend/src/main` nicht mehr vor. Stattdessen existiert `io.opaa.indexing.source.confluence.ConfluenceSourceSettings`; `ConfluenceSpaceSelection` und `ConfluenceEdition` liegen im Konnektorpaket. Auch der Auflistungs-Endpunkt aus PR #1175 (`POST /api/v1/libraries/confluence/spaces`) existiert nicht mehr, er heißt jetzt typneutral `POST /api/v1/source-types/{sourceType}/browse` (`opaa-api/src/main/openapi/libraries.yaml`).

**Themen:** confluence, konnektor, datenmodell, steckbare-konnektoren, adr-0038

---

<a id="issue-1875"></a>

## Issue #1875 — feat(group): Ansprechstellen an Anbietergruppen — Schutzkennzeichen ohne Pflegerechte setzen und lösen
- Geschlossen: 2026-09-22 (completed)
- Labels: enhancement, backend, frontend, size:M, security, auth
- PRs: #1881 (2026-09-22)
- Befund: 3 (zurückgebaut)

**Laut Issue:** ADR-0036, Entscheidung 9: Die Systemverwaltung benennt an Anbietergruppen Ansprechstellen (Mitglieder der Gruppe, ohne Pflegerechte, mit Audit-Ereignis). Nur Ansprechstellen setzen und lösen dort das Schutzkennzeichen, die Systemverwaltung nicht. Wer einer geschützten Gruppe ein Recht einräumt, sieht die Ansprechstelle statt der Mitgliederliste.

**Geliefert:** PR #1881 baute das vollständig: Tabelle `group_contacts` (Changeset 077), `POST`/`DELETE /api/v1/admin/groups/{groupId}/contacts[/{userId}]`, Audit-Ereignisse `GROUP_CONTACT_APPOINTED`/`…DISMISSED`, Verzweigung von `PUT /groups/{groupId}/protection` nach Herkunft (`403 CONTACT_REQUIRED`), Anzeige in „Meine Gruppen" und in der Gruppenverwaltung. Vier Tage später wurde das Konzept wieder entfernt: Mit dem Nachtrag zu ADR-0036, Entscheidung 9 (Maintainer-Entscheidung vom 26.09.2026) setzt und löst allein die Systemverwaltung das Schutzkennzeichen, für interne wie für Anbietergruppen. Umgesetzt im Commit f8302104 innerhalb von PR #1982 (#1978). Entfernt wurden Tabelle `group_contacts` (Changeset 077 gestrichen, Fixture-Nachzug in #1996), Entitäten, Dienst, die Endpunkte `/admin/groups/{id}/contacts` und `/me/contacted-groups`, die Audit-Ereignisse `GROUP_CONTACT_*` und die Oberfläche. Mitbetroffen ist Begrenzung (d) aus #1880: Grant-Geber einer geschützten Anbietergruppe sehen keine Ansprechstelle mehr, sondern niemanden; Auskunft gibt die Systemverwaltung.

**Verifikation:** `group_contacts`, `GroupContact` und `CONTACT_REQUIRED` kommen in `backend/src/main`, `opaa-api/src/main` und `frontend/src` nicht mehr vor. `PUT /api/v1/groups/{groupId}/protection` besteht weiter (`opaa-api/src/main/openapi/groups.yaml`), jetzt nur für die Systemverwaltung (`PROTECTION_ADMIN_ONLY`).

**Themen:** gruppen, schutzkennzeichen, ansprechstelle, adr-0036, rückbau

---

<a id="issue-1906"></a>

## Issue #1906 — feat: Backend und Frontend modularisieren – Grenzen erzwingen, Kreise auflösen, gezielt in Module aufteilen
- Geschlossen: 2026-09-28 (completed)
- Labels: enhancement, epic, backend, frontend, size:L
- PRs: keine (Epic; Lieferung über 19 Sub-Issues)
- Befund: 2 (anderer Weg)

**Laut Issue:** Backend und passend dazu Frontend in fachlich getrennte Einheiten zerlegen. Ausgangsidee: mehrere Gradle-Module (Kern plus `query`, `indexing`, `auth` …) mit vom Compiler erzwungenen Grenzen. Erhofft wurden kürzere Builds, weniger Konflikte zwischen parallelen Strängen und weniger Lesekontext für Agenten. Die erste Analyse sah das Auflösen der Paketzyklen als eigentlichen Aufwand.

**Geliefert:** Die Zyklen sind aufgelöst: 0 Paketzyklen, 0 Abhängigkeiten nach oben, eingefrorene Unterpaket-Zyklen von 100 auf 4 Kanten, 0 Konnektornamen im Kern, steckbare Konnektoren (ADR-0038). Dazu kommen Sammeldateien je Thema (OpenAPI, Changelog, Frontend-Services/Mocks, `AGENTS.md` je Modul) und Themenschnitte (`account`/`directory`, `format`, `metadata`, `retrieval`). Der Weg weicht vom Epic-Titel ab: **Echte Gradle-Module wurden bewusst nicht gebaut.** Die Messung ergab, dass je Commit weiterhin 86 % der Tests laufen, und die Probe mit `format` (#2046) brachte keinen Buildzeit-Gewinn. Die Grenzen werden stattdessen als **logische Module per ArchUnit** im Build erzwungen (#2000). **Das Frontend** wurde nur bei den Sammeldateien geteilt (#2004); die Gliederung nach Fachthemen ist in das Folge-Epic #2057 ausgelagert. Die API-Trennung nach Nutzergruppen liegt in #2058.

**Verifikation:** Unter `backend/src/main/java/io/opaa/` liegen die neuen Top-Level-Pakete (`account`, `directory`, `format`, `metadata`, `retrieval`, `knowledge`, `ratelimit`, `s3`, `health`). `settings.gradle.kts`-Module außer `opaa-api` gibt es nicht. Die Durchsetzung liegt in `io.opaa.architecture.ModularArchitecture*` (Tests).

**Themen:** modularisierung, architektur, archunit, paketzyklen, gradle, epic

---

<a id="issue-1931"></a>

## Issue #1931 — refactor(asset): Verteilungsstufe visibility entfällt — organisationsweite Reichweite als Grant an die Pseudo-Gruppe „Alle", listed bleibt
- Geschlossen: 2026-09-24 (completed)
- Labels: enhancement, backend, frontend, size:L, security
- PRs: #1945 (2026-09-24)
- Befund: 3 (Rückbau)

**Laut Issue:** Maintainer-Entscheidung vom 24.09.2026: Die Verteilungsstufe `visibility` (`PRIVATE`/`SHARED`/`ORGANIZATION`) entfällt, weil im Code nur `ORGANIZATION` eine Wirkung hatte. Organisationsweite Reichweite wird ein Grant an „Alle" in derselben Rechteliste und Grant-Historie. `LibraryVisibilityHistory`, `LIBRARY_VISIBILITY_CHANGED` und die Spec-Regel „`listed` erst ab Fachbereichsebene" entfallen. `visibility_cap` wird zu „Freigabe an Alle erlaubt", `listed` bleibt.

**Geliefert:** PR #1945 setzt das um, aber nicht als Pseudo-Gruppe, sondern als dritte Subjektart `ALL_ACCOUNTS` an der Freigabe, nach dem Vorbild von `CapabilitySubjectType.ALL_ACCOUNTS` (ADR-0037, Status `proposed`; das Issue ließ beide Varianten zu). Das ist ein **Rückbau** eines Konzepts, das über mehrere Stufen gebaut worden war: der Verteilungsstufen aus dem Vorzeitraum und ihrer gerade erst erfolgten Übertragung auf die Asset-Schale. Betroffen sind die Spalte `visibility` in `assets` (#1899, PR#1907, am selben Tag), die Stufenauswahl im gemeinsamen Freigabeabschnitt (#1902, PR #1936) und der Haken „In der Organisation geteilt" der Bibliotheksübersicht, der `visibility = ORGANIZATION` abbildete (#1916, PR #1933). Im Zeitraum gebaute Leistungen zu `visibility` sind deshalb keine Posten des Endzustands.

**Verifikation:** Die Tabelle `assets` (`backend/src/main/resources/db/changelog/rights/2026-09-27-baseline.yaml`) hat `listed`, aber keine Spalte `visibility` mehr. `asset_grants`/`asset_grant_history` erlauben `subject_type = 'ALL_ACCOUNTS'`. `asset_visibility_history` besteht unter altem Namen weiter, führt aber nur noch `listed` und den Fremdzugangsstatus.

**Themen:** asset-schale, verteilungsstufe, freigabe, alle-konten, adr-0037, rückbau

---

<a id="issue-1948"></a>

## Issue #1948 — fix(ci): MinIO-Image aus eigenem GHCR-Spiegel ziehen — quay.io/minio/minio ist nicht mehr erreichbar
- Geschlossen: 2026-09-24 (completed)
- Labels: bug, backend, size:S, ci
- PRs: #1950 (2026-09-24)
- Befund: 3 (ersetzt)

**Laut Issue:** Seit 24.09.2026 antwortet `quay.io/minio/minio` mit 401; alle drei `backend-test`-Shards fielen um, weil `SpringContextSignatureTest` die MinIO-Fixture mitlud. Sofort-Fix: das exakte letzte Release als öffentlichen Spiegel `ghcr.io/criew/minio` ziehen (Fixture, Compose, Renovate-Regel).

**Geliefert:** PR #1950 stellte Fixture, vier Compose-Zeilen, Renovate-Regel und Demo-README auf den GHCR-Spiegel um, mit rotem CI-Lauf als Reproduktionsnachweis. Als ausdrücklicher Überbrückungsfix wurde er noch am selben Tag durch #1949 (PR #1951) ersetzt: MinIO ist in Tests, Compose und Demo durch `rustfs/rustfs:1.0.0` abgelöst, der Spiegel ist entfernt.

**Verifikation:** `ghcr.io/criew/minio` und `minio/minio` kommen in `docker-compose.yml`, `renovate.json5` und `backend/src` nicht mehr vor. `docker-compose.yml` nutzt `rustfs/rustfs:1.0.0`, und die Tests unter `backend/src/test/java/io/opaa/indexing/source/s3/` laufen ohne `MinioFixture`.

**Themen:** ci, testcontainer, minio, rustfs, überbrückung

---

<a id="issue-1978"></a>

## Issue #1978 — feat(admin): UI-Politur der Benutzer- und Gruppenverwaltung
- Geschlossen: 2026-09-26 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #1982 (2026-09-26)
- Befund: 2 (Umfang überschritten)

**Laut Issue:** Politur-Runde für Konten- und Gruppenverwaltung am Demo-Stack. Befunde sammeln, einzeln entscheiden, umsetzen. Ausdrücklich: „keine neuen Verwaltungsfunktionen, es geht um Struktur, Begriffe und Konsistenz"; Labels nur `frontend`.

**Geliefert:** PR #1982 setzt 27 Oberflächenbefunde um (Hinweis-Link statt Auflagen-Kasten, Spalten „Ablauf"/„Angelegt", „unbefristet", Dialogtitel, Übergabe-Erklärung u. a.). Darüber hinaus trägt er eine **fachliche Änderung am Rechtemodell mit Backend-, Schema- und ADR-Anteil**, den Commit f8302104 „Schutz entscheidet die Systemverwaltung, Ansprechstellen entfallen" (Nachtrag zu ADR-0036, Entscheidung 9). Das Schutzkennzeichen setzt seitdem allein die Systemverwaltung, Verantwortliche interner Gruppen nicht mehr (Rücknahme eines Teils von #1814). Das Ansprechstellen-Konzept aus #1875 entfällt vollständig, samt Tabelle, Endpunkten und Audit-Ereignissen. Die Oberfläche bekommt den Schalter „Geschützte Gruppe" im Dialog „Bearbeiten". Diese Entscheidung ist unter einem Frontend-Politur-Issue gelandet, nicht unter einem eigenen Vorgang.

**Verifikation:** PR #1982 enthält f8302104 (Merge de83f08ad). `group_contacts`/`GroupContact` fehlen im Code, und `PUT /api/v1/groups/{groupId}/protection` ist nur noch für die Systemverwaltung offen. Folgekorrektur: #1996 (Fixture-Kette ohne Changeset 077).

**Themen:** ui-politur, gruppenverwaltung, schutzkennzeichen, adr-0036, zuschnitt

---

<a id="issue-1992"></a>

## Issue #1992 — Mitgliederliste für Ansprechpersonen klären
- Geschlossen: 2026-09-26 (not planned)
- Labels: question, backend, size:S, security
- PRs: keine
- Befund: 1 (gegenstandslos)

**Laut Issue:** Aus dem Review von PR #1990: `GET /api/v1/groups/{id}` lieferte einer Ansprechperson einer Anbietergruppe die vollständige Mitgliederliste, ohne dass ADR-0036 das vorsah. Zu entscheiden war, ob Ansprechpersonen die Mitglieder sehen dürfen (dann ADR ergänzen) oder nicht (dann `members: null`).

**Geliefert:** Nicht entschieden, sondern gegenstandslos geschlossen: Mit dem Nachtrag zu ADR-0036, Entscheidung 9 (26.09.2026, Commit f8302104 in PR #1982) entfällt das Konzept der Ansprechstellen vollständig. Damit gibt es auch keinen Lesepfad für sie mehr.

**Verifikation:** Kein Ansprechstellen-Code mehr vorhanden (siehe Baustein #1875).

**Themen:** gruppen, mitgliederliste, ansprechstelle, adr-0036

---

<a id="issue-2026"></a>

## Issue #2026 — fix(e2e): Demo-Smoke startet nicht – run-e2e.mjs verlangt Dienst „minio", Compose nennt ihn „objectstore"
- Geschlossen: 2026-09-27 (not planned)
- Labels: bug, ci, demo
- PRs: keine
- Befund: 1 (Duplikat)

**Laut Issue:** `demo-smoke.yml` scheitert seit dem 25.09.2026 nightly, weil `e2e/scripts/run-e2e.mjs` die Dienste `minio`/`minio-seed` startet, die in `docker-compose.yml` inzwischen `objectstore`/`objectstore-seed` heißen.

**Geliefert:** Nicht unter diesem Vorgang. Er wurde als Duplikat von #2025 geschlossen; die Behebung kam mit PR #2027.

**Verifikation:** siehe #2025 / PR #2027 (Harness und `e2e/demo-smoke.env` auf `objectstore` umgestellt).

**Themen:** e2e, demo-smoke, duplikat


---

<a id="pr-1670"></a>

## PR #1670 — test(eval): Wirkung der Gesprächsnotiz getrennt messen — sie schadet der Suche
- Gemergt: 2026-09-16
- Labels: documentation
- Bezug: #1587, #1586, #1490, #1652, #1446, #1482
- PRs: #1670 (2026-09-16)
- Befund: 3 (Aussage eingeschränkt)

**Laut Issue:** Weiche Referenz #1587: den Beitrag der Gesprächsnotiz zur Suchqualität im Mehrrunden-Pfad isoliert messen, nachdem die Nachmessung vom 12.09. (#1490) Suchfenster, Notiz und Instruktionszeile gemeinsam bewegt hatte.

**Geliefert:** Schalter `-Dopaa.eval.conversationNoteCap` im Eval-Harness (`ConversationHarnessSupport`), zwei deterministische Läufe (mit/ohne Notiz, `qwen2.5:1.5b-instruct`, gepinnte CPU-Variante). Ergebnis im Titel und in `eval/corpus/verwaltung/MAINTENANCE.md`: ohne Notiz 9 statt 5 gelöste Fälle, kein Fall verloren — „die Notiz schadet der Suche". Noch am selben Tag schränkte PR #1672 diese Aussage ein: Mit dem produktiv betriebenen Modell (`claude-haiku-4-5`) trugen 9 von 9 Notizpunkten die Rahmenangabe korrekt; der gemessene Schaden ist eine Eigenschaft des 1,5B-Eval-Modells, keine Aussage über den Produktionsbetrieb. Eine Entscheidung über Ausbau oder Reparatur der Notiz darf laut #1672 nicht auf diesem Datensatz allein fallen.

**Verifikation:** Messschalter vorhanden (`backend/src/evalTest/java/io/opaa/eval/ConversationBaseline.java`, `ConversationBaselineComparator.java`: `conversationNoteCap`); die Gesprächsnotiz selbst ist unverändert produktiv (`backend/src/main/java/io/opaa/chat/ChatNoteExtraction.java`, `frontend/src/components/chat/ConversationNote.tsx`). Die Einschränkung steht in `eval/reports/chat-note-condensation-modelcheck-2026-09-16.md` und im Nachtrag in `MAINTENANCE.md`. Die Messinfrastruktur steht, die Kernaussage des PR-Titels gilt nur für die Eval-Konfiguration.

**Themen:** eval, gesprächsnotiz, mehrrunden-retrieval, messmethodik, eval-modell


---

## Anhang: geprüfte Vorgänge ohne Befund

**Issues (461):**

#797, #945, #1013, #1029, #1032, #1033, #1036, #1037, #1039, #1040, #1041, #1042, #1043, #1044,
#1045, #1046, #1048, #1049, #1050, #1053, #1055, #1056, #1057, #1058, #1059, #1061, #1063,
#1065, #1067, #1068, #1069, #1070, #1071, #1072, #1073, #1076, #1078, #1079, #1081, #1085,
#1089, #1102, #1103, #1105, #1107, #1108, #1109, #1110, #1112, #1113, #1117, #1119, #1120,
#1123, #1124, #1126, #1129, #1130, #1131, #1132, #1133, #1134, #1135, #1136, #1137, #1138,
#1139, #1140, #1141, #1142, #1144, #1145, #1147, #1150, #1151, #1152, #1153, #1154, #1160,
#1162, #1164, #1167, #1169, #1171, #1178, #1180, #1181, #1182, #1183, #1184, #1191, #1197,
#1198, #1200, #1207, #1209, #1210, #1218, #1219, #1222, #1223, #1229, #1230, #1236, #1238,
#1239, #1242, #1243, #1245, #1254, #1256, #1257, #1259, #1261, #1267, #1268, #1269, #1270,
#1271, #1273, #1277, #1287, #1289, #1291, #1294, #1295, #1301, #1305, #1307, #1308, #1309,
#1310, #1311, #1312, #1313, #1314, #1315, #1316, #1317, #1318, #1325, #1327, #1329, #1331,
#1332, #1333, #1334, #1337, #1338, #1345, #1346, #1349, #1354, #1357, #1360, #1361, #1368,
#1369, #1371, #1373, #1374, #1375, #1376, #1377, #1378, #1379, #1380, #1381, #1382, #1383,
#1395, #1396, #1397, #1398, #1399, #1400, #1401, #1415, #1416, #1417, #1418, #1420, #1421,
#1423, #1424, #1429, #1431, #1440, #1443, #1444, #1446, #1447, #1449, #1450, #1454, #1455,
#1456, #1457, #1458, #1459, #1460, #1461, #1464, #1466, #1471, #1474, #1475, #1476, #1477,
#1478, #1481, #1483, #1484, #1485, #1486, #1487, #1488, #1489, #1495, #1500, #1503, #1509,
#1510, #1515, #1518, #1519, #1520, #1522, #1524, #1525, #1526, #1529, #1531, #1532, #1533,
#1534, #1536, #1537, #1538, #1539, #1540, #1541, #1542, #1543, #1544, #1552, #1553, #1556,
#1561, #1563, #1565, #1573, #1574, #1581, #1582, #1584, #1587, #1589, #1592, #1595, #1598,
#1600, #1601, #1603, #1604, #1606, #1607, #1608, #1609, #1610, #1612, #1614, #1616, #1617,
#1619, #1621, #1623, #1625, #1627, #1630, #1631, #1635, #1640, #1641, #1643, #1645, #1647,
#1649, #1650, #1652, #1655, #1657, #1658, #1660, #1669, #1671, #1674, #1684, #1685, #1697,
#1705, #1707, #1708, #1710, #1711, #1715, #1716, #1717, #1718, #1719, #1720, #1721, #1722,
#1723, #1725, #1726, #1728, #1731, #1732, #1733, #1739, #1745, #1759, #1762, #1766, #1768,
#1769, #1770, #1771, #1773, #1776, #1780, #1781, #1785, #1786, #1787, #1788, #1789, #1796,
#1799, #1801, #1802, #1804, #1805, #1806, #1807, #1808, #1809, #1810, #1811, #1812, #1813,
#1814, #1815, #1816, #1817, #1818, #1819, #1820, #1821, #1822, #1823, #1824, #1828, #1830,
#1832, #1833, #1834, #1835, #1838, #1839, #1840, #1841, #1842, #1844, #1850, #1851, #1852,
#1853, #1856, #1861, #1863, #1869, #1876, #1879, #1880, #1895, #1899, #1900, #1901, #1902,
#1903, #1904, #1910, #1911, #1912, #1913, #1914, #1915, #1916, #1917, #1918, #1919, #1920,
#1921, #1922, #1924, #1927, #1929, #1939, #1940, #1941, #1942, #1943, #1944, #1949, #1953,
#1958, #1959, #1960, #1961, #1970, #1971, #1973, #1974, #1975, #1976, #1977, #1985, #1989,
#1993, #2000, #2001, #2002, #2003, #2004, #2005, #2012, #2013, #2014, #2015, #2016, #2017,
#2018, #2019, #2020, #2025, #2030, #2031, #2033, #2034, #2037, #2044, #2045, #2046, #2047,
#2048, #2052, #2054, #2062, #2064

**Pull Requests ohne Issue-Verknüpfung (112):**

PR#1027, PR#1028, PR#1098, PR#1099, PR#1111, PR#1157, PR#1159, PR#1163, PR#1166, PR#1173,
PR#1175, PR#1176, PR#1177, PR#1179, PR#1185, PR#1192, PR#1199, PR#1202, PR#1205, PR#1208,
PR#1225, PR#1251, PR#1283, PR#1298, PR#1304, PR#1321, PR#1322, PR#1323, PR#1358, PR#1365,
PR#1367, PR#1409, PR#1411, PR#1430, PR#1433, PR#1434, PR#1435, PR#1436, PR#1437, PR#1438,
PR#1453, PR#1511, PR#1512, PR#1513, PR#1514, PR#1530, PR#1545, PR#1560, PR#1569, PR#1596,
PR#1597, PR#1605, PR#1611, PR#1615, PR#1618, PR#1620, PR#1622, PR#1624, PR#1626, PR#1628,
PR#1633, PR#1651, PR#1653, PR#1654, PR#1663, PR#1665, PR#1666, PR#1672, PR#1683, PR#1688,
PR#1691, PR#1692, PR#1709, PR#1724, PR#1735, PR#1736, PR#1742, PR#1757, PR#1761, PR#1763,
PR#1765, PR#1772, PR#1791, PR#1792, PR#1793, PR#1794, PR#1795, PR#1847, PR#1848, PR#1862,
PR#1873, PR#1891, PR#1897, PR#1898, PR#1908, PR#1909, PR#1956, PR#1957, PR#1965, PR#1966,
PR#1967, PR#1968, PR#1983, PR#1986, PR#1996, PR#1998, PR#1999, PR#2039, PR#2040, PR#2059,
PR#2060, PR#2061
