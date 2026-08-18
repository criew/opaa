# ADR-0015: Quellentypmodell der Indizierung

## Status

Akzeptiert

## Kontext

OPAA kennt heute zwei Wege, an Dokumente zu kommen: ein Verzeichnis im Dateisystem und eine über HTTP
erreichbare Verzeichnisliste (Apache-`mod_autoindex`-Crawling). Beide sind mit einer einzigen
Fallunterscheidung verdrahtet:

- `IndexingController.triggerIndexing` prüft, ob `IndexingTriggerRequest.url` gesetzt ist, und ruft je
  nachdem `DocumentIndexingService.triggerUrlIndexing` oder `triggerIndexing` auf. Der Quellentyp wird
  **nicht übergeben, sondern aus der Belegung eines anderen Feldes erraten**.
- `AsyncIndexingExecutor` (Dateisystem) und `UrlIndexingExecutor` (HTTP-Verzeichnisliste) teilen weder
  ein gemeinsames Interface noch eine Oberklasse. Zähl- und Meldelogik ist wortgleich dupliziert
  (`reportRejected` existiert in beiden Klassen separat).
- `DocumentSourceType` (`FILESYSTEM`, `HTTP_DIRECTORY`) markiert am `Document` das Ergebnis eines
  Laufs, beschreibt aber keinen Weg dorthin. Es gibt keine Quellen-Tabelle; Zugangsdaten, Adresse und
  sonstige Konfiguration werden pro Aufruf im `IndexingTriggerRequest` übergeben und danach verworfen.
- Eine `CHECK`-Constraint in `004-add-source-type-to-documents.yaml` bindet die Datenbank fest an die
  zwei vorhandenen Enum-Werte. Ein dritter Wert scheitert ohne begleitende Migration erst zur Laufzeit
  beim Insert.
- Scheduling existiert im Backend nicht (`@Scheduled`/`@EnableScheduling` kommen nirgends vor). Jeder
  Lauf wird von der Systemverwaltung ausdrücklich angestoßen.

Dieses Modell war tragbar, solange es genau zwei Wege gab, die sich über ein einzelnes Feld
unterscheiden ließen. Mit **RSS-Feeds** (Standard-RSS-2.0, u. a. erzeugt vom Government Site Builder,
dem CMS des Bundes) kommt ein dritter Typ hinzu, für den „URL gesetzt" keine sinnvolle Unterscheidung
mehr ist — sowohl eine HTTP-Verzeichnisliste als auch ein Feed werden über eine URL angesprochen. Ein
Feed unterscheidet sich zudem grundlegender von einer Dateiliste als die beiden bisherigen Typen
voneinander: Sein `<link>` verweist auf eine HTML-Detailseite, nicht auf eine Datei. Was indiziert
werden soll — Artikeltext und die an der Seite hängenden Anlagen — muss der Quellentyp selbst aus der
Seite gewinnen; er liefert keine fertige Liste von Datei-URLs mehr.

Damit entscheidet sich an diesem dritten Typ, ob ein vierter Typ billig wird oder wieder teuer. Dieser
ADR trifft die Entscheidung, bevor sie im Code steht.

Zu Ingestion und Konnektoren existierte bislang kein einziger ADR (vgl. Epic #463).

## Entscheidung

### 1. Der Quellentyp wird ausdrücklich übergeben

`IndexingTriggerRequest` erhält ein Feld `sourceType` (Enum, entspricht `DocumentSourceType`). Es
bestimmt den Weg — nicht mehr die Belegung von `url`.

**Rückwärtskompatibler Fallback:** Fehlt `sourceType` in der Anfrage, gilt die bisherige Ableitung
weiter (`url` gesetzt → `HTTP_DIRECTORY`, sonst → `FILESYSTEM`). Das hält vorhandene Aufrufer und
Tests lauffähig. Der Fallback deckt ausschließlich die beiden bestehenden Typen ab; ein neuer Typ wie
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
    Teil des Quellentyps, weil sie ursprungsspezifisch ist (das Muster, wie ein GSB-Feed seine Anlagen
    verlinkt, ist kein Allgemeinwissen der Indizierungskette) — **nicht**, weil sie zur gemeinsamen
    Kette gehört.
- Alles danach — Formatprüfung, Extraktion, Zerlegung, Einbettung, Prüfsummenvergleich, Ablage — bleibt
  **typunabhängig** und läuft für jeden Typ durch dieselbe Verarbeitung (`FileProcessingService` und
  nachgelagerte Schritte). Kein Quellentyp bekommt eine eigene Kopie dieser Schritte.

### 3. Registrierung über eine Registry statt Fallunterscheidung

Jeder Quellentyp implementiert ein gemeinsames Interface (Arbeitstitel `SourceIndexingExecutor`) mit
einer einzigen Ausführungsmethode. Eine Registry bildet `DocumentSourceType → SourceIndexingExecutor`
ab, gespeist aus den vorhandenen Spring-Beans (z. B. über eine Map, die Spring aus benannten oder
qualifizierten Beans befüllt, oder über eine explizite Registrierungsmethode je Executor).

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

Nicht jeder Quellentyp beantwortet die Frage „ist ein Dokument, das in diesem Lauf nicht mehr auftaucht,
aus dem Index zu nehmen?" gleich. Zwei Kategorien werden unterschieden, und jeder Quellentyp erklärt
sich bei seiner Registrierung zu genau einer davon:

- **Vollständig auflistend** (`FILESYSTEM`, `HTTP_DIRECTORY`): Ein Lauf liefert bei jedem Durchgang den
  **vollständigen aktuellen Bestand** des Ursprungs. Fehlt ein zuvor indiziertes Dokument in der
  aktuellen Auflistung, ist das eine verlässliche Aussage — es wurde im Ursprung gelöscht oder verschoben
  — und das Dokument wird aus dem Index genommen. Das ist die Zielsemantik aus
  [`docs/features/knowledge-sources.md`](../features/knowledge-sources.md#selbst-aktualisierende-wissensbl%C3%B6cke)
  („Löschen in der Quelle wirkt durch"); dieser ADR legt fest, dass sie für diese beiden Typen gilt.
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

Diese Eigenschaft wird als Teil der Registrierung eines Typs (Entscheidung 3) deklariert — nicht als
versteckte Fallunterscheidung im Aufräumcode. Ein künftiger Typ muss sich beim Hinzufügen ausdrücklich
für eine der beiden Kategorien entscheiden; es gibt keinen impliziten Standardwert.

## Ausdrücklich offen

Dieser ADR entscheidet **nicht**:

- **Eine Quellen-Tabelle.** Adresse, Zugangsdaten und Zeitplan bleiben bis auf Weiteres Teil des
  einzelnen Anstoß-Requests (siehe Entscheidung 4).
- **Zeitpläne für Läufe.** Im Backend existiert keinerlei Scheduling-Infrastruktur; ein RSS-Lauf wird
  wie jeder andere ausdrücklich angestoßen.
- **Die Verwahrung von Zugangsdaten** über den heutigen Stand hinaus (Klartext-Felder im Request).
- **Die Plugin-Fähigkeit von Konnektoren.** Das Verhältnis zu
  [`docs/discussions/discussion-plugin-architecture.md`](../discussions/discussion-plugin-architecture.md)
  bleibt unangetastet: Eine Registry, die Enum-Werte auf Executor-Beans abbildet, verbaut eine spätere
  Öffnung für extern geladene Konnektoren nicht, entscheidet sie aber auch nicht. Das Interface aus
  Entscheidung 3 ist bewusst schmal gehalten, damit es später Grundlage einer Plugin-Schnittstelle
  sein könnte, ohne dass diese Möglichkeit hier zugesagt wird.

## Konsequenzen

### Einfacher

- **Ein neuer Quellentyp lässt sich ergänzen, ohne eine bestehende Fallunterscheidung anzufassen.** Er
  bringt einen neuen Enum-Wert, einen Executor, der das gemeinsame Interface implementiert, und seine
  Registrierung mit — der Aufrufer in `IndexingController`/`DocumentIndexingService` ändert sich nicht.
- **Der RSS-Parser ist unabhängig prüfbar.** Da der Quellentyp exakt bis zum Punkt „Bytes einer Datei"
  zuständig ist, lässt sich das Feed-Parsing ohne Netzwerk und ohne Datenbank testen, wie im Epic
  vorgesehen.
- **Die Löschsemantik ist einmal entschieden**, statt bei jedem neuen Typ implizit aus dem
  vorhandenen Code übernommen zu werden (mit dem Risiko, RSS versehentlich wie ein Verzeichnis zu
  behandeln und laufend Artikel zu verlieren).

### Schwieriger

- **Jede Erweiterung des Enums erfordert weiterhin eine Migration.** Die `CHECK`-Constraint aus
  `004-add-source-type-to-documents.yaml` bleibt bestehen und wird bei jedem neuen Typ um den Wert
  erweitert. Das ist eine bewusste Entscheidung: Eine Datenbank, die stillschweigend jeden beliebigen
  String akzeptiert, verschiebt einen Tippfehler von der Migration in fehlerhafte Datensätze.
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

**Universelle Löschung durch Abwesenheit, unabhängig vom Typ.** Die einheitlichste Regel wäre, jedes
Dokument zu entfernen, das im aktuellen Lauf nicht mehr auftaucht — unabhängig davon, ob die Quelle
eine vollständige Auflistung oder nur einen Ausschnitt liefert. Verworfen, weil sie für RSS-Feeds
sachlich falsch ist: Ein Feed, der nur die jüngsten zwanzig Einträge führt, würde nach dem zweiten Lauf
bereits alle älteren, weiterhin gültigen Artikel aus dem Index nehmen. Eine Regel, die den
gefährlichsten Fall des Feature — eine noch gültige Weisung verschwindet unbemerkt aus der Antwort —
für den ersten neuen Typ sofort reproduziert, ist keine Vereinfachung, sondern ein neuer Fehler.

**Ein generisches Konfigurationsfeld (`Map<String, String>`) statt typspezifischer Request-Felder.**
Würde das Wachstum von `IndexingTriggerRequest` vermeiden, aber die OpenAPI-Spezifikation als einzige
Quelle der Wahrheit (ADR-0006) für genau den Teil des Requests aushebeln, der mit jedem neuen Typ
wichtiger wird. Verworfen zugunsten expliziter, typisierter Felder je Typ, auch auf Kosten eines
wachsenden Schemas bis zur Einführung einer Quellen-Tabelle.
