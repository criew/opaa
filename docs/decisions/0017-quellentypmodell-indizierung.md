# ADR-0017: Quellentypmodell der Indizierung

## Status

Akzeptiert (25.08.2026) — Entscheidung 5 (Löschung durch Abwesenheit, RSS ausgenommen) ist mit PR #900 Produktivverhalten.

## Kontext

OPAA kennt heute drei Werte für `DocumentSourceType`: `FILESYSTEM`, `HTTP_DIRECTORY` und `UPLOAD`.
Nur die ersten beiden stehen für einen **Indizierungslauf** — einen wiederholbaren Zugriff auf ein
Quellsystem, der eine Liste von Inhalten liefert. `UPLOAD` markiert dagegen ein Dokument, das über den
Upload-Endpunkt hereinkam (`FileProcessingService#processUploadedFile`); es entsteht nie durch einen
Lauf und hat keinen Executor. Diese Unterscheidung — Ergebnis-Enum am `Document` versus Executor-Typ
eines Laufs — ist für das Folgende zentral und wird in Entscheidung 1 aufgegriffen.

Für die beiden lauf-basierten Typen ist die Auswahl des Wegs eine einzige Fallunterscheidung:

- `IndexingController.triggerIndexing` prüft, ob `IndexingTriggerRequest.url` gesetzt ist, und ruft je
  nachdem `DocumentIndexingService.triggerUrlIndexing` oder `triggerIndexing` auf. Der Quellentyp wird
  **nicht übergeben, sondern aus der Belegung eines anderen Feldes erraten**.
- `AsyncIndexingExecutor` (Dateisystem) und `UrlIndexingExecutor` (HTTP-Verzeichnisliste) teilen weder
  ein gemeinsames Interface noch eine Oberklasse. Zähl- und Meldelogik ist strukturgleich dupliziert
  (`reportRejected` existiert in beiden Klassen separat, mit unterschiedlichen Parametertypen —
  `List<Path>` bzw. `List<AutoindexCrawlerService.CrawledFileEntry>`).
- Es gibt keine Quellen-Tabelle; Zugangsdaten, Adresse und sonstige Konfiguration eines Laufs werden
  pro Aufruf im `IndexingTriggerRequest` übergeben und danach verworfen.
- Eine `CHECK`-Constraint bindet die Datenbank an die zulässigen Enum-Werte. Sie wurde mit
  `004-add-source-type-to-documents.yaml` für `FILESYSTEM`/`HTTP_DIRECTORY` eingeführt und mit dem
  changeSet `020-allow-upload-source-type` (in `020-add-upload-metadata-to-documents.yaml`) auf
  `UPLOAD` erweitert — durch Drop und Neuanlage der Constraint, da Liquibase-changeSets, die bereits
  ausgeführt wurden, nie nachträglich bearbeitet werden. Ein Wert ohne begleitende Migration scheitert
  erst zur Laufzeit beim Insert.
- Scheduling-**Infrastruktur** existiert im Backend bereits: `OpaaApplication` trägt
  `@EnableScheduling`, und `AuditRetentionScheduler` nutzt `@Scheduled(cron = ...)` für die monatliche
  Aufbewahrungslöschung (#395). Was fehlt, ist ein **Zeitplan für Indizierungsläufe** und eine Antwort
  auf verteilte Ausführung, wenn mehrere Backend-Instanzen laufen — dafür gibt es heute keinen
  Mechanismus.

Dieses Modell für Indizierungsläufe war tragbar, solange es genau zwei lauf-basierte Wege gab, die sich
über ein einzelnes Feld unterscheiden ließen. Mit **RSS-Feeds** (Standard-RSS-2.0, erzeugt u. a. vom
CMS des Bundes, das den größten Teil dieser Feeds erzeugt) kommt ein dritter Lauftyp hinzu, für den
„URL gesetzt" keine sinnvolle Unterscheidung mehr ist — sowohl eine HTTP-Verzeichnisliste als auch ein
Feed werden über eine URL angesprochen. Ein Feed unterscheidet sich zudem grundlegender von einer
Dateiliste als die beiden bisherigen Typen voneinander: Sein `<link>` verweist auf eine
HTML-Detailseite, nicht auf eine Datei. Was indiziert werden soll — Artikeltext und die an der Seite
hängenden Anlagen — muss der Quellentyp selbst aus der Seite gewinnen; er liefert keine fertige Liste
von Datei-URLs mehr.

Damit entscheidet sich an diesem dritten Lauftyp, ob ein vierter Typ billig wird oder wieder teuer.
Dieser ADR trifft die Entscheidung, bevor sie im Code steht.

Zu Ingestion und Konnektoren existierte bislang kein einziger ADR (vgl. Epic #463).

## Entscheidung

### 1. Der Quellentyp wird ausdrücklich übergeben — über ein engeres Request-Enum

`IndexingTriggerRequest` erhält ein Feld `sourceType`, das den Lauf-Weg bestimmt — nicht mehr die
Belegung von `url`. Dieses Feld verwendet **nicht** `DocumentSourceType`, sondern ein neues, engeres
Enum (Arbeitstitel `IndexingSourceType`) mit ausschließlich den Werten, für die ein Lauf existiert
(`FILESYSTEM`, `HTTP_DIRECTORY`, künftig `RSS_FEED`). `UPLOAD` ist darin **nicht** enthalten:
`/api/v1/indexing/trigger` darf keinen Typ anbieten, für den es keinen Lauf gibt — ein Aufruf mit
`sourceType: UPLOAD` wäre eine Anfrage ohne Ausführungsweg und damit ein Modellierungsfehler, den ein
gemeinsames Enum erst zur Laufzeit sichtbar machen würde, ein engeres Enum dagegen bereits am Typ.

`DocumentSourceType` bleibt das Ergebnis-Enum am `Document` und wächst mit jedem Lauftyp
(`IndexingSourceType`) sowie mit `UPLOAD` weiter — ein Lauf schreibt beim Anlegen des Dokuments seinen
`IndexingSourceType` 1:1 in das entsprechende `DocumentSourceType`-Feld.

**Rückwärtskompatibler Fallback:** Fehlt `sourceType` in der Anfrage, gilt die bisherige Ableitung
weiter (`url` gesetzt → `HTTP_DIRECTORY`, sonst → `FILESYSTEM`). Das hält vorhandene Aufrufer und Tests
lauffähig. Der Fallback deckt ausschließlich die beiden bestehenden Lauftypen ab; ein neuer Typ wie
`RSS_FEED` hat keine sinnvolle Ableitung aus vorhandenen Feldern und **muss** `sourceType` ausdrücklich
setzen. Der Fallback ist damit von Beginn an ein Auslaufmodell für zwei Werte, nicht ein allgemeiner
Ratemechanismus, der mit jedem Typ mitwächst.

### 2. Grenze zwischen Quellentyp und gemeinsamer Kette

Die Grenze verläuft an der Frage: **Woraus entsteht die Liste der zu verarbeitenden Inhalte?**

- Ein Quellentyp ist dafür zuständig, aus seinem jeweiligen Ursprung eine Liste von **Inhaltselementen**
  zu erzeugen — jedes mit den Mindestangaben, die die gemeinsame Kette braucht: ein Name, ein Bezug zum
  Ursprung (Pfad oder URL) und ein Änderungsmerkmal (Zeitstempel, Versionskennung oder Prüfsumme) für
  die Unverändert-Prüfung. Er liefert am Ende **Bytes einer Datei**, die die gemeinsame Kette
  entgegennehmen kann — nicht zwingend eine Datei im Ursprung selbst.
  - Beim Dateisystem ist dieser Schritt trivial: die Datei liegt schon vor.
  - Bei der HTTP-Verzeichnisliste ist er ein Download.
  - Beim RSS-Feed ist er zweistufig: das Parsen des Feeds liefert Detailseiten-URLs, und ein weiterer
    Schritt löst jede Detailseite zu Artikeltext und ggf. verlinkten Anlagen auf. Diese Auflösung ist
    Teil des Quellentyps, weil sie ursprungsspezifisch ist (das Muster, wie ein Feed seine Anlagen
    verlinkt, ist kein Allgemeinwissen der Indizierungskette) — **nicht**, weil sie zur gemeinsamen
    Kette gehört. `RSS_FEED` nutzt dafür dasselbe vorhandene `url`-Feld des Requests wie
    `HTTP_DIRECTORY` — die Feed-Adresse ist strukturell dieselbe Art von Angabe, nur die Interpretation
    dahinter unterscheidet sich.
- Alles danach — Formatprüfung, Extraktion, Zerlegung, Einbettung, Prüfsummenvergleich, Ablage — bleibt
  **typunabhängig** und läuft für jeden Typ durch dieselbe Verarbeitung (`FileProcessingService` und
  nachgelagerte Schritte). Kein Quellentyp bekommt eine eigene Kopie dieser Schritte.

### 3. Registrierung über eine Registry statt Fallunterscheidung

Jeder Lauftyp implementiert ein gemeinsames Interface (Arbeitstitel `SourceIndexingExecutor`) mit
einer einzigen Ausführungsmethode. Eine Registry bildet `IndexingSourceType → SourceIndexingExecutor`
ab, gespeist aus den vorhandenen Spring-Beans (z. B. über eine Map, die Spring aus benannten oder
qualifizierten Beans befüllt, oder über eine explizite Registrierungsmethode je Executor). Der
Schlüsselraum der Registry ist bewusst `IndexingSourceType`, nicht `DocumentSourceType`: `UPLOAD` hat
keinen Executor, und die Registry muss dafür keine Sonderbehandlung vorsehen, weil `UPLOAD` als
Schlüssel gar nicht erst vorkommen kann — das fehlende Mapping ist kein zur Laufzeit zu prüfender
Fehlerfall, sondern durch die Typgrenze aus Entscheidung 1 ausgeschlossen.

`DocumentIndexingService` und `IndexingController` fragen die Registry nach dem im Request benannten
`sourceType` und delegieren an den zurückgegebenen Executor. Die Fallunterscheidung verschwindet aus
dem Kontrollfluss der Anwendung; sie existiert nur noch als der in Entscheidung 1 beschriebene,
begrenzte Rückwärtskompatibilitäts-Fallback.

**Auswirkung auf die Duplikation:** Die geteilte Logik aus `AsyncIndexingExecutor` und
`UrlIndexingExecutor` (Zählung, `reportRejected`, Fortschrittsmeldung) wandert in eine gemeinsame Basis
oder einen gemeinsamen Helfer, den jeder Executor verwendet, statt sie zu duplizieren. Das ist keine
neue Entscheidung dieses ADR, sondern eine Konsequenz aus Entscheidung 3 und wird im Folgeticket (#465)
umgesetzt.

### 4. Typspezifische Konfiguration ohne Quellen-Tabelle

> **Abgelöst durch [ADR-0018](0018-quellkonfiguration-in-der-bibliothek.md):** Die dauerhafte
> Quellkonfiguration lebt in der Wissensbibliothek; der Anstoß-Request reduziert sich auf den
> Verweis auf die Bibliothek. Mit ihr entfällt auch der Fallback aus Entscheidung 1.

Solange es keine Quellen-Tabelle gibt (siehe „Ausdrücklich offen"), bleibt Konfiguration Teil des
Anstoß-Requests. `IndexingTriggerRequest` wächst um je Typ optionale Felder (analog zu den heutigen
`url`, `proxy`, `credentials`, `insecureSsl` für `HTTP_DIRECTORY`) statt um ein generisches
Konfigurationsobjekt. Die OpenAPI-Spezifikation bleibt damit die einzige Quelle der Wahrheit für
Validierung und Struktur (ADR-0006), und jeder Typ dokumentiert seine Felder dort ausdrücklich. Ein
generisches `Map<String, String>`-Feld würde diese Prüfung umgehen und wird deshalb nicht gewählt.

Dieser Weg ist ausdrücklich ein Übergang: Sobald eine Quellen-Tabelle existiert (außerhalb dieses
ADR), wandert dauerhafte Konfiguration dorthin, und der Anstoß-Request reduziert sich auf einen
Verweis auf die gespeicherte Quelle.

### 5. Typabhängiges Verhalten beim Verschwinden aus der Quelle

**Diese Entscheidung legt eine Zielsemantik fest, die in keinem Ticket des Epics #463 gebaut wird.**
Weder `AsyncIndexingExecutor` noch `UrlIndexingExecutor` haben heute einen Löschpfad für Dokumente, die
aus der Quelle verschwunden sind — Löschung findet nur statt, wenn sich ein bereits bekanntes Dokument
inhaltlich ändert (`FileProcessingService`, Ersetzen der alten Zerlegung). Die folgende Regel bindet
also einen künftigen Aufräum-Mechanismus, entscheidet aber nicht, wann er gebaut wird.

Nicht jeder Quellentyp beantwortet die Frage „ist ein Dokument, das in diesem Lauf nicht mehr auftaucht,
aus dem Index zu nehmen?" gleich. Drei Kategorien werden unterschieden, und jeder Quellentyp erklärt
sich bei seiner Registrierung zu genau einer davon:

- **Vollständig auflistend** (`FILESYSTEM`, `HTTP_DIRECTORY`): Ein Lauf liefert bei jedem Durchgang den
  **vollständigen aktuellen Bestand** des Ursprungs. Fehlt ein zuvor indiziertes Dokument in der
  aktuellen Auflistung, ist das eine verlässliche Aussage — es wurde im Ursprung gelöscht oder verschoben
  — und das Dokument wird aus dem Index genommen. Das ist die Zielsemantik aus
  [`docs/features/knowledge-sources.md`](../features/knowledge-sources.md#selbst-aktualisierende-wissensbl%C3%B6cke)
  („Löschen in der Quelle wirkt durch"); dieser ADR legt fest, dass sie für diese beiden Typen gilt,
  sobald sie gebaut wird.
- **Ergänzend** (`RSS_FEED`): Ein Feed liefert bei jedem Abruf nur einen **Ausschnitt** — üblicherweise
  die jüngsten Einträge, oft in fester, begrenzter Anzahl. Fehlt ein zuvor indizierter Eintrag im
  aktuellen Abruf, ist das **keine** Aussage über sein Fortbestehen; er kann weiterhin gültig sein und
  ist lediglich aus dem geführten Fenster herausgerutscht. Ein automatisches Löschen anhand des
  Fehlens im Feed würde nach wenigen Läufen jeden älteren, weiterhin gültigen Artikel aus dem Index
  nehmen. Für `RSS_FEED` findet deshalb **keine Löschung durch Abwesenheit** statt; ein einmal
  aufgenommener Eintrag bleibt, bis er ausdrücklich ausgeschlossen wird (siehe
  [Lebenszyklus der Dokumente](../features/knowledge-sources.md#lebenszyklus-der-dokumente)) oder ein
  künftiger, hier nicht entschiedener Mechanismus (z. B. HTTP-Status der Detailseite) etwas anderes
  belegt.
- **Nicht lauf-basiert** (`UPLOAD`): Ein hochgeladenes Dokument entsteht außerhalb jedes
  Indizierungslaufs und wird deshalb von **keinem** Lauf als „nicht mehr in der Auflistung enthalten"
  gezählt. Es darf niemals durch Abwesenheit in einem Lauf gelöscht werden — ein Verzeichnislauf oder
  RSS-Abruf hat keine Kenntnis von Upload-Dokumenten und darf sie folglich auch nicht als verschwunden
  behandeln. Dies ist ausdrücklich festzuhalten, weil Upload- und Konnektor-Dokumente in derselben
  `documents`-Tabelle derselben Bibliothek liegen können: Ohne diese Festlegung ist eine
  Abwesenheitsprüfung, die versehentlich über die ganze Bibliothek statt über die eine Quelle läuft,
  der wahrscheinlichste Umsetzungsfehler.

**Geltungsbereich der Abwesenheitsprüfung.** Aus demselben Grund gilt: Der Vergleich „was war zuletzt
da, was ist jetzt da" läuft **je Quelle** — das heißt über die Kombination aus `sourceType` und
Quelladresse (Verzeichnispfad bzw. URL) eines einzelnen Laufs — und **niemals bibliotheksweit**. Eine
bibliotheksweite Prüfung würde jedes Dokument als verschwunden behandeln, das der aktuelle Lauf nicht
selbst geliefert hat — einschließlich der `UPLOAD`-Dokumente derselben Bibliothek und der Dokumente
jeder anderen Quelle, die in dieselbe Bibliothek indiziert. Diese Festlegung ist Teil der
Löschsemantik dieses ADR, nicht nur eine spätere Implementierungsdetailfrage.

Die Kategorie eines Typs wird als Teil seiner Registrierung (Entscheidung 3) deklariert — nicht als
versteckte Fallunterscheidung im Aufräumcode. Ein künftiger Lauftyp muss sich beim Hinzufügen
ausdrücklich für „vollständig auflistend" oder „ergänzend" entscheiden; es gibt keinen impliziten
Standardwert. `UPLOAD` braucht keine solche Registrierung, da es ohnehin keinen Executor hat
(Entscheidung 3).

## Ausdrücklich offen

Dieser ADR entscheidet **nicht**:

- **Eine Quellen-Tabelle.** Adresse, Zugangsdaten und Zeitplan bleiben bis auf Weiteres Teil des
  einzelnen Anstoß-Requests (siehe Entscheidung 4).
- **Ein Zeitplan für Indizierungsläufe.** Die Scheduling-Infrastruktur (`@EnableScheduling`,
  `AuditRetentionScheduler`) existiert bereits im Backend, wird aber für Indizierungsläufe nicht
  genutzt; ein RSS-Lauf wird wie jeder andere ausdrücklich angestoßen. Ebenso offen bleibt die Frage
  verteilter Ausführung, sobald mehrere Backend-Instanzen laufen.
- **Die Verwahrung von Zugangsdaten** über den heutigen Stand hinaus (Klartext-Felder im Request).
- **Die Plugin-Fähigkeit von Konnektoren.** Das Verhältnis zu
  [`docs/discussions/discussion-plugin-architecture.md`](../discussions/discussion-plugin-architecture.md)
  bleibt unangetastet: Eine Registry, die Enum-Werte auf Executor-Beans abbildet, verbaut eine spätere
  Öffnung für extern geladene Konnektoren nicht, entscheidet sie aber auch nicht. Das Interface aus
  Entscheidung 3 ist bewusst schmal gehalten, damit es später Grundlage einer Plugin-Schnittstelle
  sein könnte, ohne dass diese Möglichkeit hier zugesagt wird.

## Konsequenzen

### Einfacher

- **Ein neuer Lauftyp lässt sich ergänzen, ohne eine bestehende Fallunterscheidung anzufassen.** Er
  bringt einen neuen Enum-Wert in `IndexingSourceType` (und `DocumentSourceType`), einen Executor, der
  das gemeinsame Interface implementiert, und seine Registrierung mit — der Aufrufer in
  `IndexingController`/`DocumentIndexingService` ändert sich nicht.
- **Der RSS-Parser ist unabhängig prüfbar.** Da der Quellentyp exakt bis zum Punkt „Bytes einer Datei"
  zuständig ist, lässt sich das Feed-Parsing ohne Netzwerk und ohne Datenbank testen, wie im Epic
  vorgesehen.
- **Die Löschsemantik ist einmal entschieden**, statt bei jedem neuen Typ implizit aus dem
  vorhandenen Code übernommen zu werden (mit dem Risiko, RSS versehentlich wie ein Verzeichnis zu
  behandeln und laufend Artikel zu verlieren, oder eine Abwesenheitsprüfung versehentlich
  bibliotheksweit statt je Quelle laufen zu lassen und dabei Upload-Dokumente zu treffen).
- **`/api/v1/indexing/trigger` kann `UPLOAD` gar nicht erst anbieten.** Das engere Request-Enum macht
  einen Aufruf ohne Ausführungsweg zu einem Typfehler statt zu einem Laufzeitfehler.

### Schwieriger

- **Jede Erweiterung von `DocumentSourceType` erfordert weiterhin eine Migration.** Die
  `CHECK`-Constraint auf `documents.source_type` — zuletzt neu angelegt durch das changeSet
  `020-allow-upload-source-type` — wird bei jedem neuen Wert um ein **neues** changeSet erweitert, das
  die Constraint nach demselben Muster droppt und neu anlegt; das ausgeführte changeSet `020-*` selbst
  wird nicht angefasst. Das ist eine bewusste Entscheidung: Eine Datenbank, die stillschweigend jeden
  beliebigen String akzeptiert, verschiebt einen Tippfehler von der Migration in fehlerhafte
  Datensätze.
- **Zwei verwandte Enums (`IndexingSourceType`, `DocumentSourceType`) müssen synchron gepflegt werden.**
  Jeder neue Lauftyp existiert in beiden; das Mapping zwischen ihnen ist eine zusätzliche Stelle, an
  der ein neuer Typ eingetragen werden muss.
- **`IndexingTriggerRequest` wächst** um typspezifische Felder, solange keine Quellen-Tabelle existiert.
  Das Schema wird mit jedem Typ etwas breiter, bis Entscheidung 4 durch eine Quellen-Tabelle abgelöst
  wird.
- **Das gemeinsame Interface muss von Anfang an das RSS-Zweistufenmuster tragen können** (Feed-Eintrag
  → Detailseite → Inhalt + Anlagen), nicht nur das einfachere Muster „Liste von Datei-URLs". Wird das
  Interface zu eng um die beiden heutigen Typen herum geschnitten, wiederholt sich die Enge, die dieser
  ADR gerade beheben soll.

## Verworfene Alternativen

**Ein drittes `if` in `IndexingController`.** Die einfachste, kurzfristig billigste Option — genau der
Status quo, um einen Zweig erweitert. Verworfen, weil sie das eigentliche Problem nicht behebt, sondern
fortschreibt: Der vierte Typ träfe auf drei verschachtelte Bedingungen statt auf eine Registry, und die
Duplikation zwischen den Executoren bliebe unangetastet. Das Epic benennt genau diesen Punkt als
Kriterium: Ein neuer Typ muss ohne Anfassen einer bestehenden Fallunterscheidung hinzukommen können.

**`DocumentSourceType` direkt als Request-Enum verwenden, mit benannter Ausnahme für `UPLOAD`.** Spart
ein zweites Enum und dessen Pflege. Verworfen, weil sie die Ausnahme in Prüfcode statt in den Typ
verlegt: Jede Stelle, die das Feld verarbeitet, müsste wissen und durchsetzen, dass `UPLOAD` nicht
zulässig ist — eine Regel, die bei einem neuen, ebenfalls nicht lauf-basierten Wert erneut vergessen
werden kann. Ein engeres Enum macht denselben Fehler zur Übersetzungszeit sichtbar.

**Universelle Löschung durch Abwesenheit, unabhängig vom Typ und bibliotheksweit statt je Quelle.** Die
einheitlichste Regel wäre, jedes Dokument einer Bibliothek zu entfernen, das im aktuellen Lauf nicht
mehr auftaucht. Verworfen aus zwei Gründen: Für RSS-Feeds ist sie sachlich falsch — ein Feed, der nur
die jüngsten zwanzig Einträge führt, würde nach dem zweiten Lauf bereits alle älteren, weiterhin
gültigen Artikel aus dem Index nehmen. Bibliotheksweit wäre sie zusätzlich für **jeden** Typ falsch,
sobald dieselbe Bibliothek auch Upload-Dokumente oder Dokumente einer zweiten Quelle enthält: Ein
Verzeichnislauf würde dann Dokumente löschen, die er nie geliefert hat und auch nicht kennt. Eine
Regel, die den gefährlichsten Fall des Features — ein noch gültiges Dokument verschwindet unbemerkt aus
der Antwort — für den ersten neuen Typ oder die erste gemischt gespeiste Bibliothek sofort
reproduziert, ist keine Vereinfachung, sondern ein neuer Fehler.

**Ein generisches Konfigurationsfeld (`Map<String, String>`) statt typspezifischer Request-Felder.**
Würde das Wachstum von `IndexingTriggerRequest` vermeiden, aber die OpenAPI-Spezifikation als einzige
Quelle der Wahrheit (ADR-0006) für genau den Teil des Requests aushebeln, der mit jedem neuen Typ
wichtiger wird. Verworfen zugunsten expliziter, typisierter Felder je Typ, auch auf Kosten eines
wachsenden Schemas bis zur Einführung einer Quellen-Tabelle.
