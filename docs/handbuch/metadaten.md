# Metadaten: Titel, Dokumentart und Datum/Stand je Dokument

> **Entwurf.** Dieses Kapitel beschreibt die drei Kernfelder, die jedes Dokument trägt, woher ihre
> Werte kommen, wie sie gepflegt werden und was sie in Suche und Beleg bewirken. Die
> Struktur-Metadaten je Chunk (Ortsangabe, Mail-Kopfdaten, Space und Gliederungspfad) sind etwas
> anderes und stehen im Kapitel [Indexierung](indexierung.md), Schritt 5.

## 1. Wofür Metadaten hier gedacht sind

Zwei Fassungen derselben Gebührensatzung unterscheiden sich in zwei Ziffern. Für die Suche sind
beide Texte gleich gut; welche gilt, steht nicht im Text, sondern am Dokument. Metadaten sind
deshalb **keine Suchbegriffe, sondern Bedingungen an das Ergebnis**: „nur Dienstanweisungen",
„Stand nach 2024". Sie wirken an drei Stellen:

| Wirkstelle | Was passiert |
|---|---|
| **Filter** | Ein gesetzter Filter schränkt beide Suchpfade (Vektor und Volltext) ein, bevor gerankt wird |
| **Beleg** | Die Fundstelle einer Antwort zeigt Titel, Dokumentart und Datum/Stand des zitierten Dokuments |
| **Pflege** | Die Bibliothek zeigt, für wie viele Dokumente ein Feld leer ist, damit sich der Bestand nachpflegen lässt |

Die eine Regel, die alles Weitere prägt: **lieber leer als geraten.** Ein geratener Wert, der als
Filter wirkt, macht ein Dokument unsichtbar, ohne dass irgendwo eine Fehlermeldung entsteht. Ein
leeres Feld ist dagegen ein sichtbarer, behebbarer Zustand. Daraus folgt: Kein Wert wird auf den
nächstähnlichen abgebildet, kein Feld bekommt einen Vorgabewert, und ein Dokument ohne Wert wird von
einem Filter **nicht** ausgeschlossen (Abschnitt 7).

## 2. Die drei Kernfelder

Fest eingebaut, für jedes Dokument jeder Bibliothek, Anhänge eingeschlossen:

| Feld | Typ | Filterbar | Woher der Wert kommt (in dieser Reihenfolge) |
|---|---|---|---|
| **Titel** | Text, höchstens 1000 Zeichen | nein | Titel-Eigenschaft des Formats, Frontmatter `titel`, erste Überschrift erster Ebene, humanisierter Dateiname; deshalb praktisch immer befüllt |
| **Dokumentart** | ein Wert aus dem Vokabular (Abschnitt 3) | ja | Frontmatter `dokumentart`, Dateiname, Titelzeile des Dokuments, Dateiformat |
| **Datum/Stand** | Datum mit Genauigkeit Tag, Monat oder Jahr | ja | Frontmatter `stand_datum` / `fassung`, formateigenes Dokumentdatum (Mail-Datum, Feed-Veröffentlichung), erste Überschrift, Dateiname, Änderungs-, dann Erstelldatum der Dokumenteigenschaften |

„Datum/Stand" ist bewusst **ein** Feld: Die Frage lautet „welcher Stand gilt", nicht „wann wurde
die Datei zuletzt geöffnet". Ein Dateisystem-Änderungsdatum ist deshalb keine Quelle. „Fassung 2024"
wird als Jahr gespeichert und als „2024" angezeigt, nie als „01.01.2024".

Ein Wert hängt am **Dokument**, nicht am Chunk; alle Chunks eines Dokuments tragen dieselben Werte.
Eine Korrektur findet an genau einer Stelle statt.

## 3. Das Vokabular der Dokumentart

Die Dokumentart ist eine ausgelieferte Liste mit stabilen Codes und deutschen Bezeichnungen:

| Code | Anzeige |
|---|---|
| `SATZUNG_ORDNUNG` | Satzung/Ordnung |
| `DIENSTANWEISUNG` | Dienstanweisung |
| `VERMERK` | Vermerk |
| `PROTOKOLL` | Protokoll |
| `BESCHEID_VORLAGE` | Bescheid-Vorlage |
| `FORMULAR` | Formular |
| `GEBUEHRENVERZEICHNIS` | Gebührenverzeichnis |
| `PRAESENTATION` | Präsentation |
| `SONSTIGES` | Sonstiges |

Ein Wert außerhalb der Liste ist nicht speicherbar, weder automatisch noch von Hand. Je Eintrag
gibt es **Synonyme** (exakter Abgleich, ohne Rücksicht auf Groß-/Kleinschreibung und Umlaute) und
**Kompositum-Endungen** mit einer Mindestlänge des Vorderteils: `verwaltungsgebuehrensatzung` ist
eine Satzung, `anordnung` keine Ordnung. Eine Ausschlussliste hält Komposita fern, die eine Endung
sonst zu Unrecht beanspruchen würde (`Tagesordnung`, `Größenordnung`, `Eingangsvermerk`). Die
Endungsregel gilt nur für Dateinamen; eine Titelzeile, ein Frontmatter-Wert und eine manuelle
Eingabe werden ausschließlich exakt abgeglichen.

Die Liste lebt in der Datenbank (`document_type_vocabulary` mit `document_type_synonyms`,
`document_type_suffixes`, `document_type_suffix_exclusions`) und wird je Installation durch
Einfügen von Zeilen erweitert; ein Code darf nie umbenannt werden, solange Dokumente ihn tragen.
Eine Oberfläche zur Pflege des Vokabulars gibt es nicht. Ein Synonym ist nie kürzer als vier
Buchstaben; „DA" wäre vom Füllwort „da" nicht zu unterscheiden.

## 4. Herkunft und Zustand eines Werts

Jeder Wert trägt, wo er herkommt:

| Herkunft | Anzeige | Bedeutung |
|---|---|---|
| deterministisch | „automatisch ermittelt" | aus Dateiname, Dokumenteigenschaften oder Dokumentkopf nach festen Regeln, mit Extraktionsversion |
| abgeleitet | „abgeleitet" | von einem Sprachmodell mit Konfidenz; wird heute nicht erzeugt, ist aber vorgesehen |
| manuell | „manuell" | von einer Person gesetzt, mit Kennung und Zeitpunkt |

Je Feld und Dokument gibt es drei Zustände:

| Zustand | Bedeutung | Zählt als offen |
|---|---|---|
| Wert gesetzt | ein Wert liegt vor, mit Herkunft | nein |
| leer | noch nicht ermittelt | **ja** |
| kein Wert ermittelbar | eine Person hat festgestellt, dass es keinen gibt | nein |

Drei Regeln folgen daraus:

- **Ein manueller Wert wird von keiner automatischen Ermittlung überschrieben**, auch nicht durch
  eine Neuaufnahme oder einen Bestandslauf. Wer korrigiert, korrigiert einmal.
- **„Kein Wert ermittelbar" wird nur von Hand gesetzt** und von keiner Automatik zurückgenommen. Für
  Filter und Beleg verhält es sich wie leer; in der Metadatenansicht steht es ausdrücklich.
- **Löschen macht das Feld wieder leer.** Die nächste automatische Ermittlung darf es erneut
  befüllen; das Dokument wird dafür in die Auswahl des Bestandslaufs zurückgestellt. Eine Löschung
  ist keine Sperre.

## 5. Automatische Ermittlung

Die Ermittlung läuft **bei jeder Aufnahme eines Dokuments**, für jede Quelle, als Systemprozess
ohne Rechtekontext einer Person: Sie liest, was die Dokumentstrecke ohnehin liest, und zeigt
niemandem Inhalte. Kein Sprachmodell ist beteiligt.

### 5.1 Was die Formate liefern

Jede Format-Pipeline gibt nur weiter, was ihr Format selbst erklärt; interpretiert wird zentral.

| Format | Titel-Eigenschaft | Datumseigenschaften | Erste Überschrift | Titelzeile | Weitere Quellen |
|---|---|---|---|---|---|
| PDF | Info-Dictionary | Erstellung, Änderung | erster Lesezeichen-Eintrag der obersten Ebene | erste Textzeile der ersten Seite | |
| DOCX, PPTX | Dokumenteigenschaften | Erstellung, Änderung | DOCX: erste Überschrift 1; PPTX: Titel der ersten Folie | DOCX: erster Absatz | |
| ODT, ODP | `meta.xml` | Erstellung, Änderung | ODT: erste Überschrift 1 | ODT: erster Absatz | |
| Markdown | Frontmatter `titel` | Frontmatter `stand_datum`, `fassung` | erste `#`-Überschrift | erste Zeile nach dem Frontmatter | Frontmatter `dokumentart` |
| HTML | `<title>` | | erste `<h1>` | erster Textblock des Hauptinhalts | |
| E-Mail | Betreff | `Date`-Kopf als Dokumentdatum | | | |
| Feed-Eintrag | Überschrift des Eintrags | Veröffentlichungsdatum als Dokumentdatum | | | Name gilt nicht als Dateiname |
| Confluence-Seite | Seitentitel | | | | Name gilt nicht als Dateiname |
| Tabellen, TXT, DOC | | | | TXT, DOC: erste Textzeile | |

Bei allen Formaten kommt der **Dateiname** hinzu, und die geroutete **Formatkennung** entscheidet
als letzte Quelle der Dokumentart: PPTX und ODP sind Präsentationen; PDF und DOCX tragen jede
Dokumentart und liefern nichts.

### 5.2 Regeln der Ermittlung

- **Erste Quelle mit genau einem Treffer gewinnt.** Nennt eine Quelle zwei verschiedene
  Dokumentarten (ein Dateiname mit „Protokoll" und „Vermerk"), liefert diese Quelle nichts, die
  nächste wird noch gefragt. Ausnahme Frontmatter: Ein deklarierter Wert außerhalb des Vokabulars
  lässt das Feld leer und fällt nicht auf den Dateinamen durch.
- **Nur die Titelzeile zählt.** Aus dem Dokumenttext wird für die Dokumentart genau eine Zeile
  gelesen: die erste nicht leere. Eine Beschriftung darunter („Formular: RF-KFZ-001") oder ein Satz
  („nach der Dienstanweisung zur Terminvergabe") ist eine Referenz auf ein anderes Dokument. Eine
  Überschrift erster Ebene, die nicht die Titelzeile ist, liefert keine Dokumentart; sie wirkt nur
  als Veto, wenn sie einen anderen Wert nennt als die Titelzeile.
- **Ein Name, der kein Dateiname ist**, liefert weder Dokumentart noch Datum: Die Überschrift eines
  Feed-Eintrags („Rat beschließt neue Hundesteuersatzung") ist keine Satzung, und der Titel einer
  Confluence-Seite („Gebührensatzung 2024") ist weder eine Satzung noch ein Stand. Er bleibt der
  Titel. Betroffen sind genau diese beiden Zuflüsse; Dateien, Uploads, Dateien eines
  Webverzeichnisses und jeder Anhang tragen echte Dateinamen.
- **Datumsschreibweisen**, in dieser Reihenfolge je Quelle: `2026-03-12`, `12.03.2026`, `2026-03`,
  „März 2026", dann ein Jahr 1900 bis 2099. Ein unmögliches Datum (ein Aktenzeichen wie
  `12.34.5678`) wird übersprungen. Ein nacktes Jahr zählt nur im Dateinamen und im Frontmatter; in
  einer Überschrift braucht es einen Anker wie „Stand 2026" oder „Fassung 2024", weil eine
  unverankerte Zahl dort ein Betrag oder ein Paragraf ist.
- **Extraktionsversion.** Die Regeln tragen eine Versionsnummer (heute 3), die an jedem Dokument
  gespeichert wird. Ändert sich eine Regel, steigt die Version, und der Bestand wird damit als
  nachzuziehen erkennbar (Abschnitt 6).

Die Chunks eines Dokuments bekommen die filterbaren Werte (`doc_type`, `doc_date`,
`doc_date_precision`) mitgeschrieben; eine spätere Änderung wird per Aktualisierung an den Chunks
nachgezogen, ohne neu zu zerlegen oder neu einzubetten. Der Titel steht nur am Dokument.

## 6. Bestandslauf: den vorhandenen Bestand nachziehen

Ein Bestand, der vor dieser Fähigkeit aufgenommen wurde, ist leer; ebenso liegen nach einem
Software-Update mit neuer Extraktionsversion alle Dokumente auf dem alten Regelstand. Beides zieht
der **Bestandslauf** nach. Er läuft nicht von selbst: Ein Systemadministrator startet ihn je
Bibliothek auf der Seite **„Suche & Indexierung"** über „Kernfelder nachrüsten"; „Anhalten" und
„Weiter" stehen an derselben Schaltfläche.

Was ein Lauf tut:

- Er wählt jedes indizierte Dokument, dessen Extraktionsversion fehlt oder unter der aktuellen
  liegt, und liest dessen **Originaldatei** erneut, denn die ergiebigsten Quellen (Dateiname,
  Dokumenteigenschaften) stehen nicht in den Chunk-Texten. Kein Chunk wird gelöscht, neu zerlegt
  oder neu eingebettet; die Suche bleibt die ganze Zeit verfügbar.
- Je Dokument sind Werte, Chunk-Aktualisierung und Extraktionsversion **eine** Transaktion. Ein
  Fehler kostet nur dieses Dokument; es wird beim nächsten Aufruf erneut versucht. Manuelle Werte
  bleiben unberührt.
- Eine seit der Indizierung **geänderte Datei** wird übersprungen (Prüfsumme gegen die Zeile): Die
  Chunks stammen aus dem alten Inhalt, und die Werte eines anderen Textes gehörten nicht daran. Der
  nächste Indexierungslauf nimmt sie neu auf und ermittelt dabei.
- **Entfernte Quellen:** Ein Feed-Eintrag wird aus seiner Zeile (Überschrift, Veröffentlichungsdatum)
  ohne Download erneut ermittelt. Alles andere Entfernte (Dateien eines Webverzeichnisses,
  Confluence-Seiten, jeder entfernte Anhang samt Elternkette) kann nur der eigene Konnektorlauf neu
  lesen und wird dafür vorgemerkt; es bleibt bis dahin als „wartend auf den nächsten Konnektorlauf"
  ausgewiesen. Für eine Confluence-Seite gelten dabei dieselben Regeln wie beim Aufnehmen, weil
  genau dieser Weg noch einmal durchlaufen wird — insbesondere gilt ihr Titel auch dort nicht als
  Dateiname.
- Anhänge lokaler Quellen werden über ihre Elternkette erneut ausgepackt, wie beim Pipeline-Nachzug.
- Jeder Aufruf wird auditiert (`INDEXING_METADATA_BACKFILL_TRIGGERED`, ein Eintrag je Aufruf).

Die Seite zeigt je Bibliothek in der Spalte „Kernfelder": Dokumente insgesamt, auf aktueller
Version, ausstehend, davon wartend auf den Konnektorlauf, zuletzt übersprungen, und den
**Füllgrad je Feld**. Sie ruft Pakete zu 50 Dokumenten ab und bricht nach drei Paketen ohne
Fortschritt oder 1000 Paketen von selbst ab. Über die API läuft dasselbe als wiederholter Aufruf:

| Aufruf | Zweck |
|---|---|
| `POST /api/v1/admin/indexing/metadata-backfill` | ein Paket (`libraryId`, `batchSize` 1 bis 100) nachziehen; wiederholen, bis `done` gemeldet wird |
| `GET /api/v1/admin/search/status` | je Bibliothek der Extraktionsstand und der Füllgrad je Kernfeld |

## 7. Manuelle Pflege

**Wer ein Dokument bearbeiten darf, darf seine Metadaten korrigieren:** die Rolle EDITOR an der
Bibliothek, kein Verwaltungsrecht. Lesen genügt VIEWER.

- **Metadatenansicht je Dokument.** In der Dokumentliste der Bibliothek klappt „Metadaten von …
  anzeigen" die drei Kernfelder auf: Wert, Herkunft (Akteur und Zeitpunkt bzw. Konfidenz und Modell
  als Tooltip) und mit Bearbeitungsrecht „Bearbeiten" (Dokumentart als Auswahl aus dem Vokabular,
  Datum mit Genauigkeit Tag, Monat oder Jahr, Titel als Text) und „Löschen". Ein Wert außerhalb des
  Vokabulars, ein leerer Titel oder ein Datum ohne Genauigkeit wird abgewiesen.
- **„Kein Wert ermittelbar"** ist an derselben Stelle wählbar, für jedes Feld, ohne Wert.
- **Sammelzuweisung.** Mit Bearbeitungsrecht trägt jede Zeile der Dokumentliste ein Auswahlkästchen;
  „Feld setzen" auf der Auswahl setzt **ein** Feld auf **einen** Wert für bis zu 1000 Dokumente,
  mit Bestätigung und Anzahl. Ein Dokument, das zwischen Auswahl und Bestätigung gelöscht wurde,
  wird in der Antwort benannt, die übrigen werden trotzdem verarbeitet.
- **Audit.** Jede wirksame Setzung, Änderung und Löschung schreibt ein Ereignis
  `DOCUMENT_METADATA_CHANGED` mit Dokument, Feld, Alt- und Neuwert, Akteur und Zeitpunkt; Objekt
  ist die Bibliothek, eine Sammelzuweisung teilt sich eine Korrelationsreferenz. Ein identischer
  Wert, der bereits steht, ist keine Änderung. Aus der Ereignisfolge einer Bibliothek lässt sich ihr
  manueller Stand nach einer Wiederherstellung rekonstruieren; das Verfahren steht im
  [Deployment](deployment.md).

Für Konnektorbibliotheken gilt dasselbe; der Wert hängt am Dokument, nicht an der Datei, und
überlebt eine Neuaufnahme.

### 7.1 Der Pflege-Anker

Auf der Bibliotheksseite steht über der Dokumentliste der Abschnitt
**„Metadaten-Pflege"**: je Feld „N Dokumente ohne Wert (x %)" oder „vollständig gepflegt", mit
einer Schaltfläche, die genau diese Dokumente in der Liste darunter öffnet. Gezählt werden nur leere
Felder; „kein Wert ermittelbar" zählt nicht mit, deshalb kann die Zahl null erreichen. Die Liste
„ohne Wert für Feld X" führt Anhänge als eigene Zeilen und enthält kein Dokument mit Wert, damit
„alle auswählen" plus Sammelzuweisung keinen gepflegten Wert überschreibt.

Die Zahlen werden bei jeder Abfrage gezählt, nie vorberechnet, und nur für Bibliotheken, die die
fragende Person lesen darf. Es gibt keine Erinnerungen, keine Pflichtfelder beim Hochladen und
keine automatische Nachbefüllung.

## 8. Wirkung in der Suche

Neben den Suchbereichs-Chips steht **„Filter"**. Das Popover zeigt je Kernfeld:

- **Dokumentart** als Mehrfachauswahl aus den **im Bestand vorkommenden** Werten mit Anzahl, nie das
  ganze Vokabular.
- **Datum/Stand** als Fenster von/bis; die Spanne der vorhandenen Werte ist angegeben.
- den **Füllstand** („Datum/Stand bei 92 % der Dokumente vorhanden"). Ein Feld unter der
  Schwelle, Standard 90 % für die Dokumentart und 75 % für das Datum, wird **nicht angeboten**, und
  das Popover sagt warum. Ein Filter auf ein zu 12 % befülltes Feld sähe aus wie eine Einschränkung
  des Bestands und wäre keine.

Der aktive Filter erscheint als entfernbare Chips („Dokumentart: Vermerk", „Datum: 01.01.2024 –
31.12.2024") und bleibt am Chat gespeichert. Die Werte setzt die Person; aus der Frage wird kein
Filter abgeleitet.

Was der Filter tut:

- Er wirkt in **beiden** Suchpfaden vor dem Ranking, nie als Nachfilter auf einer Trefferliste, und
  ist dem Rechtefilter nachgeordnet: Er kann die lesbare Menge verkleinern, nie vergrößern.
- **Genauigkeit des Datums.** Ein Wert gilt für den ganzen Zeitraum, den seine Genauigkeit
  offenlässt: „Fassung 2024" liegt im Fenster 2024, im Fenster „ab 15.06.2024", nicht im Fenster
  2023. Im Zweifel zu weit, nie zu eng.
- **Leerwerte schließen nicht aus.** Ein Dokument ohne Wert für das gefilterte Feld wird gefunden
  und in Fundstellenzeile und Belegfenster als **„ohne Angabe"** gekennzeichnet. Ein zu weiter
  Filter ist ein sichtbares Ärgernis, ein zu enger ein unsichtbarer Fehler.
- Das Erklärprotokoll einer Antwort nennt den aktiven Filter und je Pfad, wie viele Kandidaten nur
  wegen der Leerwert-Regel enthalten sind. Die Diagnose auf „Suche & Indexierung" nimmt einen
  Filter entgegen, damit „Sicht als" die gefilterte Frage einer Person prüfen kann.

Die Optionen werden je Person und Suchbereich für fünf Minuten zwischengespeichert und bei jeder
Rechteänderung, die die Person betrifft, verworfen.

## 9. Beleg-Anzeige

Die Fundstellenzeile und das Belegfenster einer Antwort zeigen Titel, Dokumentart und Datum/Stand
des zitierten Dokuments, mit „ · " verbunden; ein leeres Feld erscheint gar nicht, ein abgeleiteter
Wert ist als „(abgeleitet)" gekennzeichnet. Die Ortsangabe im Dokument bleibt daneben bestehen. Bei
E-Mails stehen zusätzlich die vier Kopfdaten (siehe [E-Mail](format-mail.md)).

## 10. Rechte und Sichtbarkeit

| Was | Wer |
|---|---|
| Metadaten eines Dokuments sehen, Pflege-Anker der Bibliothek sehen | VIEWER an der Bibliothek |
| Wert setzen, löschen, „kein Wert ermittelbar", Sammelzuweisung | EDITOR an der Bibliothek |
| Vokabular als Auswahlliste | jede angemeldete Person; es ist Schema, kein Bestand |
| Bestandslauf starten, Extraktionsstand und Füllgrad aller Bibliotheken der Organisation | Systemadministrator |
| Filter-Optionen und Füllstand | im Rechtekontext der fragenden Person über ihren Suchbereich |

Eine Bibliothek, die eine Person nicht lesen darf, ist in allen Zahlen abwesend; schon „412
Dokumente ohne Wert" verriete den Umfang eines fremden Bestands.

## 11. API

| Aufruf | Zweck |
|---|---|
| `GET /api/v1/libraries/{libraryId}/documents/{documentId}/metadata` | alle drei Kernfelder, auch leere, mit Herkunft |
| `PUT …/metadata/{fieldKey}` | Wert setzen oder „kein Wert ermittelbar"; `fieldKey` ist `title`, `document_type` oder `document_date` |
| `DELETE …/metadata/{fieldKey}` | Wert löschen |
| `POST /api/v1/libraries/{libraryId}/documents/metadata/bulk` | Sammelzuweisung |
| `GET /api/v1/libraries/{libraryId}/metadata/maintenance` | Pflege-Anker je Feld |
| `GET /api/v1/libraries/{libraryId}/documents?missingMetadataField=…` | die gezählten Dokumente ohne Wert |
| `GET /api/v1/metadata/document-types` | das Vokabular |
| `GET /api/v1/search/metadata-filter-options` | Füllstand, angebotene Felder, vorkommende Werte für den Suchbereich |
| `QueryRequest.metadataFilter`, `PATCH /api/v1/chats/{chatId}` | Filter je Anfrage bzw. am Chat |

## 12. Konfiguration

| Schlüssel | Umgebungsvariable | Standard | Wirkung |
|---|---|---|---|
| `opaa.query.metadata-filter.document-type-offer-threshold` | `OPAA_QUERY_METADATA_FILTER_DOCUMENT_TYPE_THRESHOLD` | 0.90 | Füllstand, ab dem die Dokumentart als Filter angeboten wird |
| `opaa.query.metadata-filter.document-date-offer-threshold` | `OPAA_QUERY_METADATA_FILTER_DOCUMENT_DATE_THRESHOLD` | 0.75 | Füllstand, ab dem Datum/Stand angeboten wird |
| `opaa.query.metadata-filter.options-cache-ttl` | `OPAA_QUERY_METADATA_FILTER_OPTIONS_CACHE_TTL` | 5m | Lebensdauer des Optionen-Zwischenspeichers je Person und Suchbereich |

Das Vokabular wird über die Datenbank erweitert (Abschnitt 3); die Extraktionsregeln sind nicht
konfigurierbar.

## 13. Nicht gebaut

- **Bibliotheksfelder** (bis zu fünf eigene typisierte Felder je Bibliothek, etwa Fassung und
  Rechtsebene) und deren Verwaltung in den Bibliothekseinstellungen (Ticket #1071)
- **Mail-Kopfdaten als Schemafelder**; heute stehen sie als eigene Anzeigefelder am Beleg
  (Ticket #1242)
- **Metadaten im Kontextpräfix** des Embeddings (Ticket #1072)
- **Modellgestützte Ermittlung** mit Konfidenz und **freie Schlagworte**, je Bibliothek
  einschaltbar, voreingestellt aus (Ticket #1073); die Herkunft „abgeleitet" ist dafür vorgesehen
- Ableitung eines Filters aus der Frage; Filter auf den Titel; Freitextfelder; eine Oberfläche zur
  Pflege des Vokabulars; ein amtliches Metadatenmodell für die Aktenführung
