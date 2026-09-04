# ADR-0022: Ein Anhang ist ein eigenes Dokument

## Status

Akzeptiert (02.09.2026) — die fünf offenen Punkte des ersten Entwurfs (Sichtbarkeit, Bestandsmigration,
`file_size`-Bilanzierung, kaskadierendes Löschen, `file_path`-Syntax) hat der Maintainer noch am selben
Tag entschieden; die Entscheidungen sind unten eingearbeitet. Maintainer-Entscheidung: Ein Anhang — an
einer E-Mail wie an einer Confluence-Seite — ist eine eigene `Document`-Zeile, nie ein verschachtelter
Chunk seines Elterndokuments.

## Kontext

Heute existieren zwei Muster nebeneinander, für dieselbe fachliche Sache:

- **RSS macht es bereits richtig.** `io.opaa.indexing.source.attachment.AttachmentIndexer` lädt jeden
  von `DetailPageExtractor`/`AttachmentProfile` gefundenen Anhang einzeln und schickt ihn durch
  `FileProcessingService#processUrlFile` — eigene `Document`-Zeile, eigene Prüfsumme, eigene
  Speicherquote, korrekte `pipeline_id`/`pipeline_version` durch die zuständige Format-Pipeline. Die
  Klasse ist aber ausdrücklich „an implementation detail of the RSS executor" (eigene Javadoc-Aussage)
  und hängt an `RssFeedRunContext`/`RssPoliteness`.
- **Mail macht es anders.** `MailDocumentPipeline#processAttachment` routet einen Anhang über
  `DocumentPipelineRegistry` durch die zuständige Format-Pipeline, hängt die entstehenden Chunks aber an
  die flache Chunk-Liste der Mail. `FileProcessingService#storeChunks` prägt darauf **eine**
  `pipeline_id`/`pipeline_version` — die der Mail-Pipeline (`email`) — auf jeden Chunk, auch auf die
  eines PDF-Anhangs. Das ist Befund 2 von Issue #1130.

Issue #1129 (Confluence-Epic) hat das RSS-Muster bereits als Zielbild übernommen: Issue #1137 fordert in
seinen Abnahmekriterien wörtlich „Anhänge laufen über den bestehenden Anhangs-Weg (`AttachmentIndexer`,
`HtmlDocumentPipeline`)". Ohne diesen ADR entstünde ein dritter, erneut eigenständiger Anhangsweg für
Confluence, oder Confluence würde auf das Mail-Muster ausweichen und dessen Schwächen erben.

Der Maintainer hat entschieden: Ein Anhang ist immer eine eigene `Document`-Zeile. Dieser ADR denkt diese
Entscheidung zu Ende — Identität, Löschsemantik, Elternbeziehung, Sichtbarkeit, Quote, Änderungserkennung,
Paketschnitt, Bestandsmigration — und legt fest, was beim Umsetzen entschieden sein muss.

### Vorentscheidungen, an die dieser ADR anschließt

- [ADR-0017](0017-quellentypmodell-indizierung.md), Entscheidung 5: Löschung durch Abwesenheit gilt nur
  für „vollständig auflistende" Quellentypen (`FILESYSTEM`, `HTTP_DIRECTORY`), niemals für `RSS_FEED`
  („ergänzend") und nie bibliotheksweit, sondern je Quelle. **Wichtige Folge für diesen ADR:** Weil RSS
  `StaleDocumentCleanupService#cleanupVanished` nie aufruft, hat das RSS-Anhangsmuster die Frage
  „wie verhindert ein Anhang, dass er als verschwunden gilt" nie beantworten müssen — dieser ADR ist die
  erste Stelle, die das tut (siehe Entscheidung 3).
- [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md): eine Bibliothek hat genau einen Quellentyp,
  Quellkonfiguration lebt an der Bibliothek. Dieser ADR ändert daran nichts — ein Anhang bleibt in
  derselben Bibliothek wie sein Elterndokument.
- `docs/features/ingestion-pipelines.md`, Teil 3 Punkt 5, benennt die heutige Mail-Abweichung bereits
  ausdrücklich als „bewusste Abweichung vom gewöhnlichen Anlagenweg-Muster" mit genau der hier behobenen
  Konsequenz (Pipeline-Version-Nachzug erreicht Mail-Anhänge nicht). Teil 4 Regel (d) ist die
  Selektionsmechanik, mit der die Bestandsmigration dieses ADR arbeitet (siehe Entscheidung 9).

## Entscheidung

### 1. Grundsatz

Jeder Anhang — an einer E-Mail, an einer Confluence-Seite, an einem RSS-Detailseiten-Eintrag — wird über
denselben, verallgemeinerten Anhangsweg zu einer eigenen `Document`-Zeile: eigene Prüfsumme, eigener
Lebenszyklus, eigene `pipeline_id`/`pipeline_version` durch die für sein Format zuständige Pipeline. Kein
Anhang wird künftig als Chunk seines Elterndokuments gespeichert.

### 2. Identität: `file_path` eines Anhangs

`uk_documents_library_path` macht `(library_id, file_path)` eindeutig; das gilt unverändert weiter.

- **RSS:** bereits gelöst — `file_path` ist die Anhangs-URL selbst (`AttachmentCandidate.url()`), stabil
  über Läufe und eindeutig je Bibliothek.
- **Confluence:** ein Anhang hat in beiden Editionen eine eigene, stabile Kennung/Download-Adresse der
  Confluence-API — dieselbe Art von externer, stabiler Identität wie bei RSS. Kein neues Muster nötig.
- **Mail:** ein Anhang hat keine URL. Die Grundregel für seinen `file_path`: **er muss den `file_path`
  seines Elterndokuments einschließen**, nicht nur seinen eigenen Dateinamen — daraus folgen
  Eindeutigkeit (zwei gleichnamige Anhänge zweier verschiedener Mails erben je die eindeutige Identität
  ihrer Mail) und Stabilität (derselbe Elternpfad über Läufe hinweg, solange die Mail-Datei selbst nicht
  verschoben wird) automatisch. Für rekursive Verschachtelung (Mail in Mail mit Anhang) gilt dieselbe
  Regel rekursiv: Der `file_path` eines Anhangs einer weitergeleiteten Mail enthält bereits den
  synthetischen `file_path` dieser weitergeleiteten Mail selbst, der wiederum den `file_path` der
  äußersten Mail enthält — eine Kette, kein Sonderfall.
  Zwei gleichnamige Anhänge **derselben** Mail (z. B. zwei `anlage.pdf` in unterschiedlichen MIME-Teilen)
  brauchen zusätzlich einen Disambiguator innerhalb dieser einen Mail. **Entschieden:** Die genaue Syntax
  (Trennzeichen, ob Positions-Index oder Content-ID) bleibt bewusst ein Umsetzungsdetail des
  Mail-Umstellungstickets, keine Architekturfrage dieses ADR — die Grundregel „schließt den Elternpfad
  ein" ist die Architekturaussage. Das Ticket muss die Syntax ausdrücklich festlegen; sie darf nicht
  implizit im Code entstehen (etwa als Nebenwirkung der ersten Implementierung, die zufällig
  funktioniert).

### 3. Löschsemantik

`StaleDocumentCleanupService#cleanupVanished` vergleicht `currentFilePaths` (was der aktuelle Lauf
vorgefunden hat) gegen den bestehenden `(library, sourceType)`-Bestand. Ein Anhang wird nicht durch eine
eigene Auflistung entdeckt, sondern nur beim Verarbeiten seines Elterndokuments — er kann deshalb nie
selbst in der Quelle „aufgelistet" werden, so wie eine Datei im Verzeichnisbaum.

**Entscheidung:** Der verallgemeinerte Anhangsweg meldet jeden Anhangspfad, den er in diesem Lauf
antrifft (neu angelegt oder unverändert bestätigt), an den aufrufenden Executor zurück, der ihn zusammen
mit den eigentlichen Elternpfaden in `currentFilePaths` aufnimmt — kein Sonderfall in
`StaleDocumentCleanupService` selbst, das Verfahren bleibt für Eltern- und Anhangszeilen identisch.

Zwei Fälle daraus, beide durch dieselbe Mitgliedschaft in `currentFilePaths` gelöst, ohne
Sonderbehandlung:

- **Elterndokument verschwunden** (Mail-Datei aus dem Dateisystem gelöscht, Confluence-Seite gelöscht/in
  den Papierkorb verschoben/aus der Space-Auswahl entfernt): Es wird in diesem Lauf nicht mehr
  verarbeitet, also werden auch seine Anhänge nicht erneut zu `currentFilePaths` hinzugefügt — Eltern-
  **und** Anhangszeile fallen im selben Durchlauf von `cleanupVanished` weg, ohne eigene Kaskadenlogik.
- **Anhang aus dem Elterndokument entfernt** (eine Confluence-Seite verliert einen Anhang bei einer
  Bearbeitung; bei Mail praktisch nicht der Normalfall, da eine einmal empfangene Mail sich nicht
  nachträglich ändert, aber die Regel muss trotzdem gelten): Das Elterndokument wird weiterhin
  verarbeitet und zu `currentFilePaths` hinzugefügt, der entfernte Anhang aber nicht mehr — er fällt
  einzeln weg.

**Die eine echte Falle:** Ein Elterndokument, das als **unverändert übersprungen** wird (Prüfsummentreffer,
`FileProcessingResult.SKIPPED`), wird nicht neu geparst — seine Anhänge werden dann nicht neu entdeckt.
Ihre Pfade müssen trotzdem in `currentFilePaths` landen, sonst räumt `cleanupVanished` sie im selben Lauf
fälschlich weg, obwohl Elterndokument und Anhänge unverändert fortbestehen. Das verlangt, dass der
Aufrufer für ein übersprungenes Elterndokument die Pfade seiner bereits vorhandenen Anhangszeilen aus der
Datenbank nachträgt statt sie neu zu entdecken — was eine abfragbare Elternbeziehung voraussetzt (siehe
Entscheidung 4).

**Regel für jeden Pfad außerhalb des Vollabgleichs, der ein Elterndokument entfernt oder ersetzt.** Die
oben beschriebene, automatikfreie Löschung gilt ausdrücklich nur für `StaleDocumentCleanupService`s
eigenen, `currentFilePaths`-basierten Abgleich. **Jeder andere Codepfad, der ein Elterndokument entfernt
oder unter seiner bestehenden ID ersetzt — eine selektive Pipeline-Neuindizierung über
`PipelineReindexService`, eine künftige Einzeldokument-Löschfunktion für Konnektor-Bestände, jede weitere,
heute noch nicht existierende Stelle —, muss seine Anhangszeilen ausdrücklich mitbehandeln.** Es gibt
keinen impliziten Automatismus dafür (kein datenbankseitiges `ON DELETE CASCADE` auf
`parent_document_id` ist Teil dieser Entscheidung, siehe Entscheidung 4 — eine reine DB-Kaskade würde die
Dokumentzeile entfernen, aber die zugehörigen pgvector-Chunks verwaist zurücklassen). Das ist eine
Auflage an das jeweilige Umsetzungsticket, nicht eine Annahme, die im Code entstehen darf: Jeder Pfad,
der ein Elterndokument anfasst, muss bei seiner Umsetzung ausdrücklich entscheiden und umsetzen, was mit
dessen Anhangszeilen geschieht (mitlöschen, unverändert lassen, oder — bei einer Ersetzung unter
gleicher ID — auf Fortbestand prüfen), statt sich auf eine Kaskade zu verlassen, die es nicht gibt.

### 4. Elternbeziehung

`Document` braucht eine abfragbare Beziehung zu seinem Elterndokument — nicht nur zur Anzeige, auch für
Entscheidung 3's Nachtrag unveränderter Anhänge. Der Vorläufer dafür existiert bereits: `sourceEntryUrl`
verknüpft einen RSS-Anhang mit seinem Eintrag über dessen `file_path` (String, kein FK) und wird in
`QueryService`/`LibraryDocumentResponseMapper`/`ChatSource` bereits für die Beleg-Anzeige gelesen.

**Entscheidung:** Diese Beziehung wird zu einer echten Fremdschlüsselspalte verallgemeinert
(`documents.parent_document_id`, nullable, `REFERENCES documents(id)`), von jedem Anhang jedes
Quellentyps gesetzt — RSS, Mail, künftig Confluence. Ein FK statt eines Pfad-Strings, weil er direkt
abfragbar ist („alle Anhänge von Elterndokument X", ohne String-Gleichheit) und weil er robuster gegen
eine künftige Änderung der Pfad-Syntax des Elterndokuments ist. Ob `sourceEntryUrl` dabei durch
`parent_document_id` abgelöst oder als RSS-spezifisches Feld daneben bestehen bleibt (beide zeigen bei
RSS-Anhängen auf dieselbe Information), ist eine Umsetzungsfrage des Migrationstickets, keine
Architekturfrage dieses ADR. **Kein `ON DELETE CASCADE` auf `parent_document_id`:** Eine
datenbankseitige Kaskade würde die Anhangszeile entfernen, aber die zugehörigen pgvector-Chunks
verwaist zurücklassen, die nur über Anwendungscode (`VectorChunkStore#deleteByDocumentId`) erreichbar
sind — Löschung eines Elterndokuments bleibt deshalb durchgängig Anwendungscode, mit der Auflage aus
Entscheidung 3.

Die Beleg-Anzeige (`withAttachmentLocation`, „Anhang: …") wandert von einem in den Chunk-Text
eingebackenen Textpräfix zu einer über `parent_document_id` aufgelösten Angabe auf dem Anhangsdokument
selbst — dieselbe fachliche Aussage, aber am Dokument statt im Fundort-Text, und für jeden Anhangsweg
einheitlich statt mail-spezifisch.

### 5. Sichtbarkeit — nutzersichtbare Änderung, gruppiert dargestellt

Ein Anhang wird künftig zu einer eigenen `Document`-Zeile und zählt eigenständig in
`IndexingRunProgress` (das Feld `documentsIndexedTotal` unterscheidet bereits heute „verarbeitete
Einträge" von „tatsächlich indizierte Dokumente" — genau für den RSS-Anhangsfall gebaut, siehe
`recordDocumentIndexed`). Für RSS ändert sich dadurch nichts. **Für Mail ist das eine echte,
nutzersichtbare Verhaltensänderung**: Eine Mail mit drei PDF-Anhängen wird nach dieser Umstellung vier
Dokumentzeilen statt einer.

**Entschieden: Die Dokumentliste zeigt Anhänge gruppiert unter ihrem Elterndokument, nicht als flache,
unabhängige Zeilen.** Die Liste bleibt auf Elternebene so lang wie heute; ein Anhang klappt unter seiner
Mail bzw. Confluence-Seite auf. Begründung: Ohne Gruppierung verdreifacht (oder vervielfacht) sich für
eine Mail-Bibliothek die Zeilenzahl der Liste ohne erkennbaren Grund — genau der Zusammenhang zwischen
Anhang und Herkunft (die `parent_document_id` aus Entscheidung 4 trägt), den die Gruppierung sichtbar
macht, ist die Information, die diesen Anstieg für die Nutzerin verständlich macht, statt ihn als
unerklärte Vervielfachung wirken zu lassen.

Die Gruppierung selbst ist Frontend-Arbeit und braucht ein eigenes Umsetzungsticket, getrennt von der
Backend-Umstellung (siehe Issue-Zuschnittsvorschlag im zugehörigen PR). Nebenbedingungen, die dieses
Ticket ausdrücklich klären muss,
weil sie sich nicht von selbst aus „gruppiert anzeigen" ergeben:

- **Filterung/Suche innerhalb der Liste:** Trifft ein Suchbegriff nur auf einen Anhang, nicht auf sein
  Elterndokument (z. B. ein Dateiname-Treffer im Anhang, aber nicht im Betreff der Mail) — bleibt die
  Elternzeile trotzdem sichtbar (aufgeklappt, mit dem treffenden Anhang hervorgehoben), oder verschwindet
  sie mangels eigenen Treffers?
- **Paginierung:** Zählt eine Seite der Liste Elterndokumente (Anhänge zählen nicht gegen die Seitengröße,
  können sie aber überschreiten) oder Zeilen insgesamt (ein Elterndokument mit vielen Anhängen kann dann
  allein eine Seite füllen)? Die heutige `Page<Document>`-Paginierung in `DocumentRepository` zählt
  Dokumentzeilen undifferenziert — eine gruppierte Ansicht braucht eine bewusste Entscheidung, welche
  Ebene die Seitengröße bemisst.
- **Sortierung:** Sortiert die Liste über Elterndokumente (Anhänge folgen ihrem Elternteil, unabhängig von
  ihrem eigenen Zeitstempel/Namen), oder bricht ein Sortierkriterium wie „zuletzt indiziert" die
  Gruppierung auf, sobald ein Anhang neuer ist als sein Elterndokument?

### 6. Quote und Budgets

`AttachmentBudget`/`MailProperties` deckeln heute zwei verschiedene Dinge, die getrennt werden müssen:

- **Parse-seitige DoS-Härtung** (`max-attachment-bytes` beim Kopieren in eine temporäre Datei,
  `max-attachments-per-message` in der Extraktionsschleife von `EmlReader`/`MsgReader`,
  `max-message-bytes` vor dem Parsen): bleibt unverändert bei `MailProperties` — sie schützt das Parsen
  der `.eml`/`.msg`-Datei selbst, unabhängig davon, was mit den extrahierten Anhängen danach geschieht.
- **Wie viele der extrahierten Anhänge tatsächlich indiziert werden** (`max-attachment-depth` für
  Mail-in-Mail-Rekursion): wandert auf die Ebene des verallgemeinerten Anhangswegs, analog zu
  `IndexingProperties.Rss#maxAttachmentsPerEntry`, das heute schon dieselbe Rolle für RSS spielt. Der
  Grund: Sobald `MailDocumentPipeline` ihre Anhänge nicht mehr selbst rekursiv über
  `DocumentPipelineRegistry` verarbeitet (siehe Entscheidung 10), sondern nur noch die extrahierten
  Anhänge an den gemeinsamen Weg zurückgibt, ist die Rekursionstiefen-Zählung — eine Mail, deren Anhang
  selbst wieder eine Mail mit Anhängen ist — eine Eigenschaft des Anhangswegs, nicht mehr von
  `MailDocumentPipeline`s eigenem `ThreadLocal`. Der Standardwert (5) wandert mit; der Konfigurationsschlüssel
  selbst ist Umsetzungsdetail.

**Speicherquote:** `LibraryStorageQuotaService#usedBytes` summiert `Document#getFileSize` über die
Bibliothek. Sobald ein Anhang eine eigene Zeile mit eigenem `fileSize` ist, zählt er darüber automatisch
zur Quote — genauer als heute, wo sein Gewicht implizit im `fileSize` der ganzen `.eml`/`.msg`-Datei
steckt. Das öffnet aber eine Doppelzählung: Die rohe `.eml`/`.msg`-Datei enthält die Anhangsbytes
(Base64-kodiert) bereits in ihrer eigenen Dateigröße. **Entschieden:** Das `fileSize` des Elterndokuments
zählt nach dieser Umstellung nur noch Kopfdaten und Nachrichtentext, der Anhang zählt seine eigenen Bytes
— sonst zählt ein Anhang doppelt gegen die Quote einer Bibliothek, und es gibt keine sinnvolle
Alternative dazu.

### 7. Änderungserkennung — der eigentliche fachliche Gewinn

Mit einer eigenen `Document`-Zeile bekommt ein Anhang seine eigene Prüfsumme und (bei Confluence) seine
eigene Versionsangabe, unabhängig von seinem Elterndokument. Eine geänderte Confluence-Seite mit einem
unveränderten Anhang lässt sich dann exakt so behandeln, wie `processUrlFile` das heute schon für
`HTTP_DIRECTORY`/`RSS_FEED` tut: Die Seite wird neu verarbeitet, ihr Text neu zerlegt und eingebettet; der
Anhang wird über `findByLibraryIdAndFilePath` gefunden, sein Prüfsummenvergleich schlägt fehl zu „gleich",
und er wird **unverändert übersprungen** — kein erneutes Herunterladen, kein erneutes Parsen, kein
erneutes Embedding.

**Das geht mit verschachtelten Chunks strukturell nicht:** Solange ein Anhang als Chunks seines
Elterndokuments gespeichert ist, löst jede Änderung am Elterndokument (heute bei Mail: jede
Prüfsummenänderung der `.eml`-Datei) den kompletten Neuaufbau **aller** seiner Chunks aus —
`FileProcessingService#processFile`/`#processUrlFile` löschen bei einer Änderung immer den gesamten
Chunk-Bestand des Dokuments und schreiben ihn neu (`vectorChunkStore.deleteByDocumentId`, dann
`storeChunks`). Es gibt keine Granularität unterhalb eines Dokuments — ein unverändert gebliebener Anhang
hat keine eigene Identität, gegen die ein Prüfsummenvergleich überhaupt laufen könnte. Genau das ist die
Voraussetzung, die Issue #1139 (inkrementeller Confluence-Abgleich) für seine „Neues, Geändertes und
Verschobenes wird aufgenommen" -Abnahmekriterien braucht — ohne eigene Anhangsidentität würde jeder
inkrementelle Seiten-Treffer alle seine Anhänge unnötig neu verarbeiten.

### 8. Wo lebt der verallgemeinerte Anhangsweg

`io.opaa.indexing.source.attachment` (heute: `AttachmentCandidate`, `AttachmentIndexer`,
`AttachmentProfile`, alle package-private bzw. an `RssFeedRunContext`/`RssPoliteness` gebunden) wird zum
gemeinsamen, quellentyp-übergreifenden Anhangsweg — für Mail, RSS und künftig Confluence. Dafür muss
`AttachmentIndexer` von seiner heutigen RSS-Bindung gelöst werden: Statt direkt gegen
`RssFeedRunContext`/`RssPoliteness` zu arbeiten, hängt er an einer schmaleren, gemeinsamen Abstraktion
(Bibliothek, Ereignisprotokoll, ggf. HTTP-Zugriff/Politeness für Konnektoren, die tatsächlich herunterladen
— Mail-Anhänge liegen dagegen bereits als extrahierte Bytes vor und brauchen keinen Download-Schritt). RSS
und Confluence implementieren diese Abstraktion über ihren eigenen Zugriffsweg; Mail liefert ihre bereits
extrahierten `ParsedMailAttachment`-Bytes direkt hinein, ohne den Download-Teil zu durchlaufen.

**Randbedingung aus Issue #1117:** Die Paketabhängigkeit zwischen `io.opaa.indexing` und
`io.opaa.indexing.pipeline` ist bereits nicht sauber (beidseitige Abhängigkeit). Der verallgemeinerte
Anhangsweg braucht Zugriff auf `DocumentPipelineRegistry` (zum Routen eines Anhangs auf seine
Format-Pipeline) und auf `FileProcessingService`/`processUrlFile`-artige Speicherlogik — er darf dabei
**keine neue Kante** zwischen `io.opaa.indexing.pipeline` und `io.opaa.indexing.source.*` einziehen, die
über die heute schon bestehenden hinausgeht. Der genaue Paketschnitt (ob `source.attachment` unverändert
bleibt, in ein `io.opaa.indexing`-nahes Paket wandert, oder anders) ist Aufgabe des Umsetzungstickets und
sollte mit #1117 zusammen entschieden werden, nicht diesem ADR vorgreifen — die Leitplanke steht hier
fest, die konkrete Paketwahl nicht.

**Eine zweite architektonische Folge**, die aus Entscheidung 10 folgt: `DocumentPipelineResult` kennt
heute nur `chunks`. Damit `MailDocumentPipeline` ihre extrahierten Anhänge an den gemeinsamen Weg
zurückgeben kann, statt sie selbst rekursiv zu verarbeiten, braucht `DocumentPipelineResult` (oder ein
Geschwisterkonzept am `DocumentPipeline`-Vertrag) einen zweiten Rückgabekanal: „diese eingebetteten Objekte
wurden gefunden, hier sind ihre Bytes/Namen" — getrennt von „das sind meine eigenen Chunks". Das ist eine
Erweiterung der Pipeline-Abstraktion selbst (`docs/features/ingestion-pipelines.md`, Teil 1), nicht nur
ein Detail von `MailDocumentPipeline`.

### 9. Bestandsmigration

Ein bereits indizierter Mail-Anhang liegt heute als Chunks seiner Elternmail im Index, mit
`pipeline_id=email`. Es gibt keinen separaten Migrations-/Rückwirkungsmechanismus — die Umstellung nutzt
das bereits gebaute Verfahren aus `docs/features/ingestion-pipelines.md`, Teil 4 Regel (d):
`MailDocumentPipeline#version()` wird mit dieser Änderung erhöht.

**Korrektur (Review zu PR #1166):** Diese ADR ging bei ihrer ursprünglichen Fassung von v1 → v2 für den
hier beschriebenen Anhangsumbau aus. Zwischenzeitlich hat #1130 Befund 1 (Mail-Kopfdaten als
Kontextzeilen im Chunk-Text, PR #1166) bereits v2 belegt — dieser PR lag zeitlich vor der Umsetzung
dieses ADR. Der hier beschriebene Anhangsumbau geht deshalb auf **v3**, nicht v2; die Bestandsmigration
in diesem Abschnitt gilt inhaltlich unverändert, nur mit der verschobenen Versionsnummer.

**Zweite Korrektur (Review zu PR #1201):** #1164 hat v3 zwischenzeitlich für einen eigenen, unabhängigen
Zweck belegt (mail_date wird seither auf Sekundenpräzision gekürzt geschrieben, damit ein
späterer Zeitraumfilter lexikografisch sortieren kann) - dieser PR lag wiederum zeitlich vor der
Umsetzung des hier beschriebenen Anhangsumbaus. Der Anhangsumbau geht deshalb auf **v4**, nicht v3;
die Bestandsmigration in diesem Abschnitt gilt inhaltlich unverändert, nur mit der abermals
verschobenen Versionsnummer. Bestehende
`email`-Chunks unterhalb v4 sind damit über die vorhandenen Administrationsendpunkte
(`GET /pipeline-versions`, `POST /pipeline-reindex`) als nachzuziehen erkennbar und lassen sich gezielt
neu erzeugen — kein Sonderfall, dieselbe Mechanik, die für jede andere Pipeline-Versionsänderung bereits
gilt.

**Entschieden: Kein dediziertes Migrationsskript, und kein selbsttätiger Start.** Der Nachzug wird von
der Betreiberin ausgelöst, genau wie jede andere Pipeline-Versionsänderung (Teil 4 Regel (d):
„Ausgelöst wird nichts von selbst"). Ein gemischter Bestand — alte, weiterhin verschachtelte Mail-Chunks
neben neuen, eigenständigen Anhangsdokumenten — ist über die Version am Chunk erkennbar und selektiv
nachziehbar; das genügt. Bei großen Mail-Bibliotheken ist der von der Betreiberin gewählte Zeitpunkt für
den Nachlauf (Ressourcen, Betriebsfenster) mehr wert als eine erzwungene, sofortige Einheitlichkeit des
Bestands.

### 10. `MailDocumentPipeline#processAttachment` und Befund 2 von #1130

`MailDocumentPipeline#processAttachment` verliert seine heutige Aufgabe, Anhänge selbst rekursiv über
`DocumentPipelineRegistry` zu verarbeiten und ihre Chunks mit `withAttachmentLocation` zu präfixieren.
`EmlReader`/`MsgReader` extrahieren Anhänge weiterhin (Parse-Zeit-Aufgabe, unverändert) — die Pipeline
gibt sie aber über den neuen Rückgabekanal aus Entscheidung 8 an den Aufrufer zurück, statt sie selbst zu
verarbeiten. Routing, Fehlerbehandlung je Anhang („ein defekter Anhang kostet nur ihn selbst, nicht die
ganze Mail") und Formatzulassung wandern in den gemeinsamen Anhangsweg — dieselbe Logik, die
`AttachmentIndexer#indexOne` heute schon für RSS leistet, nicht länger zweimal mit leicht
unterschiedlicher Form gebaut.

**Befund 2 aus #1130 (Anhang-Chunks tragen die Pipeline-Kennung der Mail-Pipeline) löst sich damit
strukturell auf, nicht durch einen gezielten Fix.** Sobald ein Anhang eine eigene `Document`-Zeile ist,
die durch ihre eigene Format-Pipeline läuft, prägt `FileProcessingService#storeChunks` seine
`pipeline_id`/`pipeline_version` genau wie für jedes andere eigenständige Dokument — derselbe Mechanismus,
der RSS-Anhänge heute schon korrekt attribuiert. Es gibt danach keinen `mail_*`/PDF-Chunk-Kennungskonflikt
mehr, den ein gezielter Fix noch beheben müsste.

**Befund 1 aus #1130 (Mail-Kopfdaten `mail_from`/`mail_to`/`mail_subject`/`mail_date` haben keinen Leser)
ist von diesem ADR unberührt** — er betrifft die Formfrage der Struktur-Metadaten (Teil 5 Punkt 1 von
`ingestion-pipelines.md`), nicht die Anhangsfrage, und bleibt ein eigenständiges, hier nicht
entschiedenes Problem.

**Verhältnis zu PR #1165 (Issue #1126, `routing_extension`-Chunk-Metadatum).** PR #1165 schreibt den beim
Indizieren tatsächlich verwendeten Routing-Schlüssel auf jeden Chunk und ersetzt damit
`PipelineReindexService`s bisherige Dateiendungs-Näherung durch einen exakten Vergleich. Die PR-Beschreibung
stellt selbst klar, dass sie an der Anhangs-Attribution nichts ändert: Ein Mail-Anhang trägt weiterhin die
Pipeline-Kennung der äußeren Mail-Pipeline, wie vor diesem PR. Das ist mit der hier getroffenen Aussage
konsistent — `routing_extension` behebt die Näherung des Reindex-**Auswahl**mechanismus für Chunks, deren
Dateiname von ihrem tatsächlichen Inhalt abweicht; es behebt nicht, welche Pipeline-Kennung ein
verschachtelter Anhang-Chunk überhaupt trägt. Beide Änderungen ergänzen sich, ohne sich zu widersprechen:
`routing_extension` bleibt für jeden Chunk korrekt, den dieser ADR erzeugt (ein Anhang, der über seine
eigene Pipeline läuft, trägt seinen eigenen, exakten Routing-Schlüssel wie jedes andere eigenständige
Dokument), und für den Altbestand vor der Umstellung dieses ADR bleibt `routing_extension` unverändert
das, was PR #1165 bereits festgelegt hat.

## Verworfene Alternativen

**Verschachtelte Chunks mit korrigierter Kennung (der Minimalfix aus #1130 Befund 2).** Statt jeden Anhang
zu einem eigenen `Document` zu machen, könnte `MailDocumentPipeline` beim Zurückgeben eines Anhang-Chunks
dessen `pipeline_id`/`pipeline_version`-Metadatum gezielt auf die Sub-Pipeline umschreiben, die ihn
tatsächlich erzeugt hat — technisch machbar, weil diese Felder bereits Chunk-, nicht Dokument-Metadaten
sind, und deutlich kleiner als der hier gewählte Weg. Verworfen aus vier Gründen:

- Er behebt nur das Symptom aus Befund 2 (Nachzieh-Erreichbarkeit über die Pipeline-Version), nicht die
  tieferliegenden Probleme: Identität, Speicherquote und Änderungserkennung bleiben dokumentweit auf die
  ganze Mail bezogen (Entscheidungen 6/7) — ein unveränderter Anhang in einer geänderten Mail wird
  weiterhin komplett neu geparst und eingebettet, sein Speicherbedarf bleibt in der Dokumentliste
  unsichtbar.
- Er gibt Confluence keine unabhängige Anhangsversionierung — verschachtelte Chunks haben keinen Platz für
  eine eigene, vom Elterndokument unabhängige Prüfsumme/Version, die Issue #1139s inkrementeller Abgleich
  aber ausdrücklich braucht (Entscheidung 7).
- Er schreibt die Inkonsistenz fort, die #1130 selbst als Befund benennt: RSS und Mail blieben zwei
  verschiedene Modelle für dieselbe fachliche Sache — Dokument-pro-Anhang hier, chunk-mit-umgeschriebener-
  Kennung dort.
- Er hält die Beleg-Anzeige uneinheitlich: Ein RSS-Anhang öffnet schon heute als eigenes Dokument mit
  eigenem Deep Link, ein Mail-Anhang bliebe nur als fundort-präfixierter Chunk seiner Mail erreichbar —
  dieselbe fachliche Sache, zwei unterschiedliche Modellierungen je nachdem, woher sie kam.

Der Minimalfix ist real und kurzfristig billiger — er wird zugunsten des größeren, konsistenten Wegs
verworfen, weil er die eigentliche Ursache (zwei Anhangsmodelle) unangetastet lässt und die
Confluence-Anforderung an unabhängiger Versionierung gar nicht erfüllen kann.

## Konsequenzen

### Einfacher

- Confluence (#1136/#1137/#1139) kann von Anfang an gegen einen einzigen, konsistenten Anhangsweg bauen,
  statt eine dritte Variante zu entwerfen oder das fehlerhafte Mail-Muster zu erben.
- #1130 Befund 2 braucht keinen eigenen, gezielten Fix mehr — er verschwindet mit der Umstellung.
- Die Beleg-/Zitat-Anzeige wird für jeden Anhangsweg einheitlich (eigenes Dokument, eigener Deep Link,
  Elternbezug über `parent_document_id`), statt mail-spezifisch textbasiert zu sein.
- Das Original eines Anhangs bleibt öffenbar, ohne dass seine Bytes gespeichert werden: Der
  Content-Endpunkt extrahiert den Anhang beim Zugriff aus dem Original seines Elterndokuments nach
  (#1239, siehe `docs/features/ingestion-pipelines.md`) — dieselbe Extraktion wie beim Indizieren,
  daher kein zweiter Speicherort und keine doppelte Quotenzählung.
- Änderungserkennung wird für Anhänge granular — Voraussetzung für #1139, sonst nicht erreichbar.

### Schwieriger

- `DocumentPipeline`/`DocumentPipelineResult` braucht einen neuen Rückgabekanal für „eingebettete Objekte,
  keine eigenen Chunks" — eine Erweiterung der Pipeline-Abstraktion selbst, nicht nur von
  `MailDocumentPipeline`.
- `MailDocumentPipeline` wird umgebaut: Sie verliert ihre rekursive Eigenverarbeitung von Anhängen,
  `AttachmentBudget`/Tiefenzählung wandert teilweise auf die gemeinsame Ebene.
- `io.opaa.indexing.source.attachment` muss von seiner RSS-Bindung gelöst werden, ohne die
  Paketabhängigkeit aus #1117 zu verschärfen — eine zusätzliche Nebenbedingung für den Umsetzungsschnitt.
- Ein Mail-Bestand mit vielen Anhängen zeigt nach der Umstellung (und dem Nachzug über Regel (d)) deutlich
  mehr Dokumentzeilen als heute — eine sichtbare, zu kommunizierende Änderung für bestehende
  Mail-Bibliotheken.
- `Document#fileSize` bekommt für Mail-Elterndokumente eine neue Bedeutung (ohne Anhangsbytes) — ein
  Verhaltensunterschied gegenüber dem heutigen, undifferenzierten `Files.size(file)`.
- Jeder Lesezugriff auf ein Anhangsoriginal kostet ein erneutes Parsen des Elterndokuments (aus
  einer bis zu `max-message-bytes` großen Nachricht, Vorgabe 100 MiB); bei Konnektor-Beständen kommt
  ein vollständiger Abruf des Elternoriginals in eine weitere temporäre Datei hinzu — bewusst in Kauf
  genommener Aufwand an Rechenzeit, temporärem Plattenplatz und Last auf der Quelle gegen doppelte
  Speicherung (#1239). **Seit #1243 ist dieser Aufwand gedeckelt statt unbegrenzt:** Die Pipeline
  materialisiert nur noch die angeforderte Anlage als temporäre Datei statt alle (bis
  `max-attachments-per-message`, Vorgabe 50). Die Positionszählung bleibt dabei exakt die des
  unfilterten Laufs: Der gespeicherte Index ist die Listenposition in `discoveredAttachments`, und
  eine Anlage, die der unfilterte Lauf gar nicht erst meldet (zu groß, nicht dekodierbar, bei MSG ein
  eingebettetes Outlook-Objekt), verbraucht deshalb auch im filternden Lauf **keine** Position —
  würde sie mitgezählt, verschöbe sich die Position gegenüber der gespeicherten. Zusätzlich laufen
  Abrufe desselben Elterndokuments nacheinander, und ein globaler Deckel
  (`opaa.documents.attachment-extraction.max-concurrent`, Vorgabe 4) begrenzt die gleichzeitig
  **laufenden** Nachextraktionen — nicht die Lebensdauer der geschriebenen Datei, die erst beim
  Schließen des Antwortstroms endet, und damit nicht die Zahl gleichzeitig offener Antworten (das
  begrenzt das Rate-Limit dieses Endpunkts). Wer nicht innerhalb der konfigurierten Wartezeit an die
  Reihe kommt, erhält 429 mit deutscher Meldung statt einer unbegrenzten Wartezeit. Ein Bytes-Cache
  wiederholter Abrufe bleibt bewusst ungebaut (Begründung in
  `docs/features/ingestion-pipelines.md`).
- Löschen eines Elterndokuments außerhalb von `StaleDocumentCleanupService` (z. B. eine selektive
  Neuindizierung über `PipelineReindexService`, eine künftige Einzeldokument-Löschfunktion für
  Konnektor-Bestände) muss seine Anhangszeilen ausdrücklich mitbehandeln — es gibt keinen impliziten
  Automatismus dafür (Entscheidung 3, Regel für Nebenpfade).
- Die gruppierte Darstellung in der Dokumentliste (Entscheidung 5) ist ein eigenes Frontend-Ticket mit
  eigenen Nebenentscheidungen zu Filterung, Paginierung und Sortierung — nicht mit der Backend-Umstellung
  miterledigt.

## Ausdrücklich offen

Dieser ADR entscheidet **nicht**:

- **Der genaue Paketschnitt** für den verallgemeinerten Anhangsweg (Entscheidung 8) — nur die
  Leitplanke, keine neue Kante zwischen `io.opaa.indexing.pipeline` und `io.opaa.indexing.source.*`
  einzuziehen; die konkrete Paketwahl fällt mit #1117 im Umsetzungsticket.
- **Die genaue `file_path`-Syntax für Mail-Anhänge** (Entscheidung 2) — bewusst Umsetzungsdetail des
  Mail-Umstellungstickets, muss dort aber ausdrücklich festgelegt werden.
- **Befund 1 aus #1130** (fehlender Leser der Mail-Kopfdaten) — unberührt, eigenständiges Problem.
- **Die visuelle Gestaltung der Gruppierung** in der Dokumentliste (Entscheidung 5) — nur, dass gruppiert
  statt flach dargestellt wird, nicht wie.
