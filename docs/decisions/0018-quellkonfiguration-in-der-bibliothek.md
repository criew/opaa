# ADR-0018: Quellkonfiguration in der Bibliothek

## Status

Akzeptiert

## Kontext

[ADR-0017](0017-quellentypmodell-indizierung.md) hat das Quellentypmodell der Indizierung geordnet:
Der Quellentyp wird ausdrücklich übergeben (`IndexingSourceType`), Executoren werden über eine
Registry aufgelöst (umgesetzt mit #465), und die Löschsemantik beim Verschwinden aus der Quelle ist
je Typ entschieden. **Ausdrücklich offen** blieb dort die Quellen-Tabelle: Adresse, Zugangsdaten und
Zeitplan sind bis heute Teil des einzelnen Anstoß-Requests (`IndexingTriggerRequest`), der
Dateisystempfad ist eine globale Betriebseinstellung (`opaa.indexing.document-path`), und nach jedem
Lauf ist die Konfiguration wieder weg. ADR-0017 nannte diesen Zustand einen Übergang, „bis eine
Quellen-Tabelle existiert".

Zugleich ist die Wissensbibliothek (`KnowledgeLibrary`) heute ein reiner Container: Name,
Beschreibung, Eigentümer, Sichtbarkeit, Freigaben — kein Quellenbezug. Welche Quelle in welche
Bibliothek indiziert, entscheidet allein der Aufrufer des Triggers bei jedem einzelnen Anstoß, und
die Oberfläche verteilt zusammengehörige Handgriffe auf drei Orte: Bibliotheksverwaltung
(`/libraries`), Dokumentenseite (`/documents`) und der Indizierungsabschnitt im Admin-Drawer.

Die Spezifikation [`knowledge-sources.md`](../features/knowledge-sources.md) beschreibt als Zielbild
ein Konnektormodell (Konnektor → Quellen → Bibliotheken) und lässt gemischt gespeiste Bibliotheken
zu — mehrere Quellen und Uploads in derselben Bibliothek. Genau diese Mischung hat ADR-0017 als den
wahrscheinlichsten Umsetzungsfehler der Abwesenheitsprüfung identifiziert: Ein Lauf, der versehentlich
bibliotheksweit statt je Quelle vergleicht, löscht Upload-Dokumente, die er nie geliefert hat.

Dieser ADR entscheidet, **wo die dauerhafte Quellkonfiguration lebt** und was das für Bibliotheken,
Anstoß und Oberfläche bedeutet.

## Entscheidung

### 1. Die Bibliothek trägt genau einen Quellentyp und höchstens eine Quellkonfiguration

Es gibt keine eigene Quellen-Tabelle. **Die Wissensbibliothek selbst ist die Quelle:** Sie erhält ein
Feld `sourceType` (`UPLOAD`, `FILESYSTEM`, `HTTP_DIRECTORY`, künftig `RSS_FEED` — dieselben Werte,
die `DocumentSourceType` als Ergebnis-Enum kennt) und, für die lauf-basierten Typen, die zugehörige
Konfiguration: Verzeichnispfad bzw. URL, Proxy, Zugangsdaten, SSL-Schalter. `UPLOAD`-Bibliotheken
tragen keine Quellkonfiguration.

Als Typ verwendet die Bibliothek **`DocumentSourceType`** — kein drittes Enum: Mit der Ein-Typ-Regel
ist der Quellentyp jedes Dokuments per Konstruktion der Typ seiner Bibliothek, die Wertemengen fallen
zusammen. Die Enum-Grenze aus ADR-0017 bleibt: Für den Anstoß wird der lauf-basierte Typ der
Bibliothek auf `IndexingSourceType` abgebildet, `UPLOAD` hat weiterhin keinen Executor. Die neue
Spalte erhält eine `CHECK`-Constraint nach dem Migrationsmuster aus ADR-0017; API-seitig wird das
Enum nach ADR-0006 über `typeMappings` gemappt.

Der Typ wird **bei der Anlage gewählt** — die Oberfläche präsentiert das als Template-Auswahl — und
ist danach **unveränderlich**. Ein Typwechsel würde Bestand und Löschsemantik vermengen: Eine
Bibliothek, die erst Uploads sammelte und dann ein Verzeichnis abbildet, enthielte Dokumente, für die
die Abwesenheitsprüfung des neuen Typs keine Aussage treffen kann. Wer den Typ ändern will, legt eine
neue Bibliothek an.

**Damit entfallen gemischt gespeiste Bibliotheken.** Die bisherige Spezifikationsaussage, mehrere
Quellen und Uploads könnten in dieselbe Bibliothek speisen, wird zurückgenommen. Mehrfachverwendung
eines Bestands bleibt, was sie in [`spaces-and-assets.md`](../features/spaces-and-assets.md) immer
war: ein Rechteproblem, gelöst über Freigabe und Bereitstellung derselben Bibliothek — nicht über
mehrere Zuflüsse in einen Topf.

> **Nachtrag (2026-09-03, #1131):** Mit dem Confluence-Konnektor
> ([ADR-0023](0023-confluence-konnektor.md), Entscheidung 1) erhält die Quellkonfiguration erstmals
> einen **Listenwert** — die Auswahl mehrerer Spaces, gehalten in der Kindtabelle
> `knowledge_library_confluence_spaces`. Die Bibliothek bleibt die eine Quelle (Adresse, Zugangsdaten,
> Edition, Zeitplan als Einzelwerte an `knowledge_libraries`); die Kindtabelle ist der Wert eines
> Konfigurationsattributs, keine Quellen-Tabelle im Sinne der unten verworfenen Alternative, und der
> Geltungsbereich der Abwesenheitsprüfung bleibt „je Bibliothek und Quellentyp" (ADR-0017,
> Entscheidung 5).

### 2. Der Anstoß eines Laufs verweist nur noch auf die Bibliothek

Der Indizierungsanstoß reduziert sich auf „indiziere diese Bibliothek": Typ und Konfiguration werden
aus der Bibliothek gelesen, der Executor über die Registry aus ADR-0017/Entscheidung 3 aufgelöst.
Die typspezifischen Felder des `IndexingTriggerRequest` (`url`, `proxy`, `credentials`,
`insecureSsl`) und der globale Dateisystempfad entfallen; ein Anstoß für eine `UPLOAD`-Bibliothek
wird abgelehnt, weil es für sie keinen Lauf gibt.

**Anstoß-Berechtigung:** Auslösen darf, wer an der Bibliothek mindestens `EDITOR` ist — dieselbe
Prüfung, die heute `requireEditableLibrary` durchsetzt. Die zusätzliche `SYSTEM_ADMIN`-Schranke des
heutigen Trigger-Endpunkts **fällt damit bewusst**: Ein Anstoß-Knopf in der Detailansicht, den nur
die Systemverwaltung drücken darf, wäre für jeden anderen Eigentümer tot. Diese Öffnung ist die
Ausführungsseite des in Entscheidung 6 benannten Risikos — die Zielprüfung #267 und die
Einschränkung #484 sind deshalb gemeinsam Blocker für den Mehrbenutzer-Produktivbetrieb, nicht nur
für die Konfiguration.

> **Nachtrag (2026-08-19, #484):** Die Anlage-Berechtigung bleibt dauerhaft offen für jeden
> Berechtigten — kein Rollenkonstrukt tritt an ihre Stelle. Für `FILESYSTEM` sichert stattdessen die
> Pfad-Allowlist (`opaa.indexing.filesystem-allowlist`) den Zugriff auf Serverpfade ab; als Blocker
> für den Mehrbenutzer-Produktivbetrieb verbleibt allein #267 für die URL-basierten Quellentypen
> (`HTTP_DIRECTORY`, `RSS_FEED`).

**Das löst ADR-0017, Entscheidung 4 ab** — genau in der dort vorgesehenen Weise: „Sobald eine
Quellen-Tabelle existiert, wandert dauerhafte Konfiguration dorthin, und der Anstoß-Request reduziert
sich auf einen Verweis auf die gespeicherte Quelle." Mit ihr entfällt auch der rückwärtskompatible
`url`-Fallback aus Entscheidung 1, der ausdrücklich als Auslaufmodell eingeführt wurde. Die übrigen
Entscheidungen von ADR-0017 — engeres Request- bzw. Lauftyp-Enum, Registry, Grenze zwischen
Quellentyp und gemeinsamer Kette, Löschsemantik je Typ — bleiben unverändert in Kraft; die Registry
wird durch diesen ADR vom Bequemlichkeitsgewinn zur tragenden Struktur.

Da eine Bibliothek höchstens eine Quelle hat, fällt der Geltungsbereich der Abwesenheitsprüfung aus
ADR-0017/Entscheidung 5 („je Quelle, niemals bibliotheksweit") mit „je Bibliothek" zusammen. Der dort
benannte wahrscheinlichste Umsetzungsfehler — ein Lauf trifft Upload-Dokumente derselben Bibliothek —
ist strukturell ausgeschlossen, weil eine lauf-basierte Bibliothek keine Upload-Dokumente enthalten
kann.

### 3. Die Oberfläche bündelt sich an der Bibliothek

Die Bibliothek wird der eine Ort für alles, was ihren Bestand betrifft:

- **Anlage aus einem Template:** Die Typauswahl ist der erste Schritt der Bibliotheksanlage, gefolgt
  von den typspezifischen Konfigurationsfeldern.
- **Übersicht:** Die Bibliotheksliste zeigt je Bibliothek die Dokumentzahl.
- **Detailansicht je Typ:** `UPLOAD` zeigt Uploadfeld und Dokumentverwaltung (den Funktionsumfang der
  heutigen Dokumentenseite); lauf-basierte Typen zeigen ihre Konfiguration, einen Anstoß-Knopf und
  den Stand des letzten Laufs.
- **Abgelöst werden** die separate Dokumentenseite (`/documents`) und der Indizierungsabschnitt im
  Admin-Drawer.

### 4. Zugangsdaten werden persistent — mit festen Grundsätzen

Bisher lagen Zugangsdaten nur transient im einzelnen Request; mit der Konfiguration an der
Bibliothek werden sie erstmals gespeichert. Dafür gilt ab dem ersten gespeicherten Wert:

- **Keine Rückgabe:** Zugangsdaten erscheinen in keiner API-Antwort und keinem Log. Die API behandelt
  sie als Nur-Schreiben-Feld; die Oberfläche zeigt höchstens an, *dass* Zugangsdaten hinterlegt sind.
- **Verschlüsselte Ablage:** Die Ablageform (Verschlüsselung ruhender Daten, Schlüssel aus der
  Umgebung) ist vor einem Produktivbetrieb umzusetzen; sie ist als eigenes Ticket (#483) geführt und
  bis dahin ein benannter Blocker, kein stillschweigend akzeptierter Zustand.

### 5. Löschregel je Typ

Das Löschen einer Bibliothek ist heute blockiert, solange sie Dokumente enthält. Diese Regel bleibt
für `UPLOAD` — dort sind Dokumente einzeln löschbar, die Sperre schützt vor dem versehentlichen
Wegwerfen eines kuratierten Bestands. Für lauf-basierte Bibliotheken wäre sie eine Sackgasse: Eine
Einzellöschung ist dort zwar technisch möglich, aber **wirkungslos** — der nächste Lauf nimmt das
Dokument wieder auf, solange der Ausschluss-Mechanismus aus
[`knowledge-sources.md`](../features/knowledge-sources.md#lebenszyklus-der-dokumente) nicht gebaut
ist. **Das Löschen einer
lauf-basierten Bibliothek nimmt deshalb ihren Bestand mit** — Dokumentzeilen, Chunks im
Vektorspeicher — nach ausdrücklicher Bestätigung und mit Protokolleintrag.

### 6. Anlage zunächst für jeden Berechtigten — Einschränkung ist ein benanntes Folgeticket

Wer Bibliotheken anlegen darf, darf zunächst jeden Typ anlegen. Das ist eine bewusste, **befristete**
Entscheidung zugunsten eines schnellen Umbaus — und sie verschiebt zwei bekannte Risiken von der
Systemverwaltung auf alle Nutzer: Ein frei wählbarer Dateisystempfad macht jeden lesbaren Serverpfad
indizierbar, und eine frei wählbare URL weitet die in #267 beschriebene SSRF-Lage aus. Beides ist als
#484 (Pfad-Allowlist, Berechtigungsregel) erfasst und **vor einem Mehrbenutzer-Produktivbetrieb**
nachzuholen; #267 bleibt davon unabhängig nötig.

> **Nachtrag (2026-08-19, #484):** Umgesetzt wurde die Pfad-Allowlist, kein Berechtigungsregel- bzw.
> Rollenkonstrukt — die Maintainer-Entscheidung ist, dass die Anlage-Berechtigung dauerhaft offen
> bleibt. Für `FILESYSTEM` ist die Allowlist (`opaa.indexing.filesystem-allowlist`) damit die
> alleinige Sicherung. Für die URL-basierten Quellentypen steht die entsprechende Absicherung noch
> aus; dort bleibt #267 der offene Blocker.
>
> **Nachtrag (2026-08-21, #267):** Umgesetzt wurde die analoge Absicherung für `HTTP_DIRECTORY`/
> `RSS_FEED`: `TargetAddressValidator` lehnt eine aufgelöste Adresse im Loopback-, Link-Local-,
> privaten oder anderweitig nicht routbaren Bereich ab (`opaa.indexing.target-validation`, siehe
> [deployment.md](../handbuch/deployment.md)), konfigurierbar und standardmäßig aktiv — mit derselben
> Grundhaltung wie die Pfad-Allowlist: die Sicherung greift unabhängig davon, wer die Bibliothek
> anlegt, nicht über eine zusätzliche Rolle. Damit ist dieser Abschnitt kein offener Blocker mehr.

## Ausdrücklich offen

- **Zeitplan je Bibliothek.** Mit der Konfiguration an der Bibliothek hat ein Zeitplan erstmals einen
  natürlichen Ort; entschieden wird er hier nicht (#485), einschließlich der Frage verteilter
  Ausführung.

  > **Nachtrag (2026-08-21, #485):** Umgesetzt als feste Intervallstufen (stündlich / täglich um
  > HH:MM / wöchentlich am Wochentag X um HH:MM / aus), an- und abschaltbar je Bibliothek, intern
  > als Cron-Ausdruck gespeichert (`knowledge_libraries.schedule_cron`, Migration 054) und nur für
  > Konnektorbibliotheken verfügbar — für `UPLOAD` ergäbe ein Zeitplan keinen Sinn, es gibt keinen
  > Lauf. Ein periodischer Tick (`LibraryIndexingScheduler`, Vorbild `AuditRetentionScheduler`)
  > ermittelt fällige Bibliotheken und löst den vorhandenen Indizierungsanstoß aus. Verteilte
  > Ausführung bei mehreren Backend-Instanzen bekommt bewusst **keinen** Leader-/Lock-Aufbau: Die
  > bestehende Datenbanksperre `uk_indexing_jobs_library_running` (Migration 028) verhindert
  > Doppelstarts bereits; ein Tick, der eine laufende Bibliothek trifft, wird übersprungen und als
  > Ereignis im Laufprotokoll festgehalten (`IndexingEventCategory.SCHEDULE_SKIPPED`). Vorrangregeln
  > entfallen bewusst — ohne Warteschlange wäre eine Prioritätsspalte tote Vorbereitung. Geplante
  > Läufe versuchen bei Fehlschlag automatisch weiter (keine automatische Deaktivierung), aber
  > wiederholtes Scheitern (zwei aufeinanderfolgende fehlgeschlagene geplante Läufe) macht die
  > Detailansicht sichtbar (`LibraryResponse.lastScheduledRunsFailed`).
  >
  > **Verpasste Termine werden ausgelassen, nicht nachgeholt.** Der Tick läuft minütlich; ein
  > Fälligkeitsfenster reicht vom Ende des vorigen Ticks bis zum aktuellen (`lastTickAt` in
  > `LibraryIndexingScheduler`, nicht ein starres „letzte 60 Sekunden") — das schließt die Lücke,
  > die ein minimal verspäteter Tick (GC-Pause, ein länger laufender vorheriger Tick,
  > Scheduler-Thread-Kontention) sonst zwischen zwei festen 60-Sekunden-Fenstern reißen könnte, ohne
  > Termine doppelt auszulösen. Ein Prozessneustart setzt `lastTickAt` dagegen zurück: der erste
  > Tick nach dem Neustart blickt bewusst nur ein Tick-Intervall (60 Sekunden) zurück, nicht über die
  > gesamte Ausfallzeit. Ein Wartungsfenster, das mehrere fällige Termine überspringt, holt sie also
  > nicht gebündelt nach — das würde bei vielen zeitplangesteuerten Bibliotheken einen Ausbruch
  > gleichzeitiger Läufe direkt nach dem Neustart erzeugen. Wer nach einer Wartung sofort aktuellen
  > Inhalt braucht, nutzt weiterhin den manuellen „Jetzt indizieren"-Anstoß.
- **Die Obergrenze der Freigabe** für konnektorgespeiste Bibliotheken (#207). Sie wird durch die
  freie Anlage dringlicher, bleibt aber dort zu entscheiden.
- **Mehrere Quellen desselben Typs je Bibliothek** (etwa zwei Verzeichnispfade). Der Schnitt „eine
  Bibliothek, eine Quelle" ist bewusst streng; sollte sich echter Bedarf zeigen, wäre eine
  Quellen-Tabelle n:1 zur Bibliothek die Erweiterung. Sie hätte einen Preis, der dann bewusst zu
  zahlen wäre: Die Abwesenheitsprüfung dürfte nicht mehr je Bibliothek laufen, sondern müsste auf
  die einzelne Quelle (Bibliothek + Quelladresse) zurückgestellt werden — genau die Sorgfalt, die
  ADR-0017/Entscheidung 5 beschreibt und die dieses Modell derzeit überflüssig macht.
- **Duplikaterkennung über Bibliotheksgrenzen**, wenn derselbe Bestand einmal als Upload und einmal
  als Konnektor existiert.

## Konsequenzen

### Einfacher

- **Ein Ort statt drei.** Anlage, Konfiguration, Upload bzw. Anstoß und Bestandsübersicht liegen an
  der Bibliothek; die Erklärung „was ist eine Bibliothek?" beantwortet zugleich „wo kommt ihr Inhalt
  her?".
- **Die Abwesenheitsprüfung wird trivial adressierbar:** je Bibliothek, weil je Bibliothek höchstens
  eine Quelle existiert. Der gefährlichste Umsetzungsfehler aus ADR-0017 ist konstruktiv unmöglich.
- **Konfiguration überlebt den Lauf.** Wiederholte Läufe brauchen keine erneute Eingabe von Adresse
  und Zugangsdaten; ein künftiger Zeitplan hat einen Ort, an dem er hängen kann.
- **Ein neuer Quellentyp erscheint automatisch als neues Template**, sobald er Enum-Wert, Executor
  und Registrierung mitbringt (ADR-0017) — die Oberfläche leitet die Auswahl aus den vom Backend
  angebotenen Typen ab, statt sie zu verdrahten.

### Schwieriger

- **Zugangsdaten sind jetzt Daten.** Was vorher mit dem Request verschwand, liegt nun in der
  Datenbank und braucht Verschlüsselung, Maskierung und einen Wechselweg (#483) — Aufwand, den das
  transiente Modell nicht hatte.
- **Die Ein-Typ-Regel ist eine Produktfestlegung mit Migrationskante.** Bestehende Bibliotheken
  werden `UPLOAD`; eine Bibliothek, in die bereits per Trigger indiziert *und* hochgeladen wurde,
  behält ihren gemischten Altbestand, nimmt aber künftig nur noch Uploads an. Wichtiger noch: Jede
  bislang **rein lauf-gespeiste** Bibliothek — einschließlich der Systembibliothek, in die die
  Dateisystem-Indizierung aus `opaa.indexing.document-path` schreibt — wird durch den Backfill
  **lauf-los**; ihr Bestand bleibt liegen und wird von keinem Lauf mehr aktualisiert oder entfernt.
  Der Betriebsweg dafür ist Teil der Umsetzung (#476) und der Spezifikation: eine neue, typisierte
  Bibliothek anlegen, neu indizieren, den eingefrorenen Altbestand löschen.
- **Die Spezifikation muss zurückgebaut werden** (#482), und zwar über das Konnektor-mit-Quellen-
  Zielbild und die gemischt gespeisten Bibliotheken hinaus: Die Zuständigkeitsregel „die
  Systemverwaltung entscheidet, wohin indiziert wird" (Überblick und Zuständigkeitstabelle in
  `knowledge-sources.md`) wird durch Entscheidung 6 aufgehoben; der Abschnitt „Wenn die
  Zielbibliothek fehlt" wird gegenstandslos (die Quelle *ist* die Bibliothek); Zeitplan und
  Schonzeitraum „je Konnektor" verlieren ihr Bezugsobjekt (#485 plant je Bibliothek); und die
  Obergrenze der Freigabe in `spaces-and-assets.md` beruht auf der Trennung „Admin speist ein,
  Eigentümer gibt frei", die es so nicht mehr gibt — das verschärft #207 und ist dort zu
  entscheiden. Mehrere bisher offene Fragen in `knowledge-sources.md` sind durch diesen ADR
  beantwortet (Verzeichnisliste auf das Konfigurationsmodell gehoben; Proxy-/Anmeldefelder in die
  Konfiguration; Obergrenze bei gemischt gespeisten Bibliotheken entfällt) und werden in „Geklärte
  Fragen" überführt.
- **Ein Lauf je Bibliothek statt global einer** verlangt eine kleine, aber echte Änderung an der
  Job-Verwaltung, damit viele Bibliotheken einander nicht blockieren.

## Verworfene Alternativen

**Eine eigene Quellen-Tabelle mit Zuordnung zur Bibliothek.** Das ausformulierte Konnektormodell der
Spezifikation: Konnektoren mit Zugangsdaten und Zeitplan, darunter Quellen, jede auf eine Bibliothek
zeigend. Verworfen für den jetzigen Stand, weil es zwei Verwaltungsobjekte einführt, wo eines
genügt: Jede real existierende Quelle hätte heute genau eine Bibliothek, jede Bibliothek höchstens
eine Quelle — die Indirektion wäre reine Vorratshaltung, mit eigener Verwaltungsoberfläche und
eigenem Rechtemodell. Sollten mehrere Quellen je Bibliothek nötig werden, lässt sich die Tabelle
später herausziehen, ohne dieses Modell zu brechen (siehe „Ausdrücklich offen").

**Gemischt gespeiste Bibliotheken beibehalten und nur die Konfiguration persistieren.** Hätte die
Spezifikation unangetastet gelassen. Verworfen, weil die Mischung genau die Komplexität erzeugt, vor
der ADR-0017 warnt (Abwesenheitsprüfung je Quelle statt je Bibliothek, Sonderregeln für
Upload-Dokumente in Konnektorbibliotheken), und weil sie der Oberfläche die klare typabhängige
Detailansicht nimmt: Eine Bibliothek, die zugleich Uploadfeld und Konnektorkonfiguration zeigt,
beantwortet die Frage „woher kommt dieser Bestand?" nicht mehr.

**Konfiguration weiterhin im Anstoß-Request.** Der Status quo aus ADR-0017/Entscheidung 4. Verworfen,
weil er dort selbst als Übergang deklariert wurde und mit jedem Typ breiter wird; ohne Persistenz
gibt es keinen Ort für Zeitpläne, keine Wiederholbarkeit ohne erneute Eingabe und keine Anzeige „so
ist diese Bibliothek konfiguriert".
