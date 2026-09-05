# Metadaten: Titel, Dokumentart und Datum/Stand je Dokument

> **Entwurf.** Dieses Kapitel beschreibt die drei Kernfelder, die jedes Dokument trägt, die
> Formatfelder und die Felder, die eine Bibliothek selbst definiert — woher ihre Werte kommen, wie
> sie gepflegt werden und was sie in Suche, Kontextpräfix und Beleg bewirken. Die
> Struktur-Metadaten je Chunk (Ortsangabe, Space und Gliederungspfad) sind etwas
> anderes und stehen im Kapitel [Indexierung](indexierung.md), Schritt 5.

## 1. Wofür Metadaten hier gedacht sind

Zwei Fassungen derselben Gebührensatzung unterscheiden sich in zwei Ziffern. Für die Suche sind
beide Texte gleich gut; welche gilt, steht nicht im Text, sondern am Dokument. Metadaten sind
deshalb **keine Suchbegriffe, sondern Bedingungen an das Ergebnis**: „nur Dienstanweisungen",
„Stand nach 2024". Sie wirken an drei Stellen:

| Wirkstelle | Was passiert |
|---|---|
| **Filter** | Ein gesetzter Filter schränkt beide Suchpfade (Vektor und Volltext) ein, bevor gerankt wird |
| **Kontextpräfix** | Ausgewählte Werte stehen dem eingebetteten und volltextindizierten Text voran, auch ohne gesetzten Filter (Abschnitt 9) |
| **Beleg** | Die Fundstelle einer Antwort zeigt Titel, Dokumentart und Datum/Stand des zitierten Dokuments |
| **Pflege** | Die Bibliothek zeigt, für wie viele Dokumente ein Feld leer ist, damit sich der Bestand nachpflegen lässt |

Die eine Regel, die alles Weitere prägt: **lieber leer als geraten.** Ein geratener Wert, der als
Filter wirkt, macht ein Dokument unsichtbar, ohne dass irgendwo eine Fehlermeldung entsteht. Ein
leeres Feld ist dagegen ein sichtbarer, behebbarer Zustand. Daraus folgt: Kein Wert wird auf den
nächstähnlichen abgebildet, kein Feld bekommt einen Vorgabewert, und ein Dokument ohne Wert wird von
einem Filter **nicht** ausgeschlossen (Abschnitt 8).

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

### 2a. Formatfelder

Neben den Kernfeldern gibt es einen kleinen, fest eingebauten Kreis von **Formatfeldern**: Felder,
die nur bestimmte Formate erklären und deshalb nur von deren Pipeline befüllt werden. Sie werden
gelesen, nicht gedeutet, und tragen deshalb immer die Herkunft „deterministisch".

| Feld | Format | Typ | Filterbar |
|---|---|---|---|
| **Absender** | E-Mail | Kennung nach Muster (E-Mail-Adresse) | ja, als Genau-Treffer |
| **An** | E-Mail | Text, auf 200 Zeichen gekürzt | nein |
| **Betreff** | E-Mail | Text | nein |

Ein Formatfeld hängt wie ein Kernfeld am Dokument; der filterbare Absender wird zusätzlich an alle
seine Chunks vererbt, damit beide Suchpfade dieselbe Bedingung tragen. Ein Dokument eines anderen
Formats hat schlicht keinen Wert und wird von einem Filter darauf nie ausgeschlossen — und auch
nicht als „ohne Angabe" gekennzeichnet, weil die Frage nach seinem Absender nie gestellt war. Der Absender wird auf die reine Adresse reduziert und kleingeschrieben, damit
„genau diese Adresse" prüfbar bleibt. Betreff und An sind **Anzeigefelder**: Sie stehen im Beleg,
filtern aber nie — ein Teilstring-Filter würde aus einem prüfbaren Feld wieder eine Textsuche
machen, und die leistet die Volltextsuche über den Kopfblock im Chunk-Text ohnehin.

### 2b. Bibliotheksfelder

Kernfelder und Formatfelder sind fest eingebaut. Darüber hinaus definiert **jede Bibliothek bis zu
fünf eigene Felder** — „Fassung", „Rechtsebene", „Aktenzeichen" —, verwaltet in den
Bibliothekseinstellungen unter **„Metadatenfelder"** mit dem Verwaltungsrecht (MANAGER).

| Typ | Wert | Filter |
|---|---|---|
| **Auswahl** | genau ein Eintrag einer gepflegten Werteliste (Code und deutsche Bezeichnung, höchstens 100 Einträge) | Mehrfachauswahl |
| **Datum** | Datum mit Genauigkeit Tag, Monat oder Jahr, wie Datum/Stand | Fenster von/bis |
| **Kennung** | Text gegen ein selbst gesetztes Muster, etwa `^RF-[A-Z]+-[0-9]+$` | Genau-Treffer, nie Teiltreffer |

Vier Regeln, die die Konfiguration eng führen:

- **Aufnahmeregel.** Ein Feld wird nur angenommen, wenn es **filtert** oder **im Kontextpräfix
  wirkt**. „Nur im Beleg anzeigen" wird abgewiesen — ein Feld, das nichts findbar macht, erzeugt
  Pflegearbeit ohne Gegenwert. Die Beleg-Anzeige ist eine Zugabe: höchstens zwei Felder je
  Bibliothek tragen eine Belegposition (1 oder 2).
- **Die Feldidentität ist Bibliothek plus Schlüssel.** Zwei Bibliotheken dürfen beide `fassung`
  führen, mit verschiedenen Wertelisten; ein Filter benennt deshalb immer beides. Ein Dokument einer
  anderen Bibliothek wird nie gegen eine fremde Werteliste geprüft.
- **Abbildungsregel.** Ein benutzter Listenwert lässt sich nicht einfach entfernen. Er wird auf einen
  anderen Wert oder auf „leer" abgebildet, und **wie viele Dokumente das betrifft, steht vor der
  Bestätigung**. Umgeschriebene Werte tragen danach die Herkunft „manuell" und den auslösenden
  Akteur; jedes Dokument bekommt sein eigenes Audit-Ereignis. Denselben Weg geht das Löschen eines
  ganzen Feldes: Es entfernt die Werte aller Dokumente und protokolliert je Dokument den Altwert.
- **Ein Wert außerhalb der Liste ist nicht speicherbar** — weder von Hand noch modellgestützt, und
  auch nicht als Rest einer gelöschten Liste. Die Datenbank hält diese Zusage selbst.

Ein Bibliotheksfeld wird von Hand gepflegt wie ein Kernfeld (Abschnitt 7); ein Auswahlfeld füllt
zusätzlich die modellgestützte Ermittlung, wenn sie eingeschaltet ist. Pflege-Anker,
Sammelzuweisung, „kein Wert ermittelbar" und das Audit-Ereignis gelten unverändert.

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
| abgeleitet | „abgeleitet" | von einem Sprachmodell mit Konfidenz und Modell-Kennung; entsteht nur in einer Bibliothek, die die modellgestützte Ermittlung eingeschaltet hat (Abschnitt 5.3) |
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
| E-Mail | Betreff | `Date`-Kopf als Dokumentdatum | | | Absender, An und Betreff als Formatfelder (Abschnitt 2a) |
| Feed-Eintrag | Überschrift des Eintrags | Veröffentlichungsdatum als Dokumentdatum | | | Name gilt nicht als Dateiname |
| Confluence-Seite | Seitentitel | Zeitpunkt der aktuellen Seitenversion als Änderungsdatum | | | Name gilt nicht als Dateiname |
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
  Titel; das Datum kommt bei beiden aus den Eigenschaften der Quelle (Veröffentlichungsdatum des
  Eintrags, Zeitpunkt der Seitenversion). Betroffen sind genau diese beiden Zuflüsse; Dateien, Uploads, Dateien eines
  Webverzeichnisses und jeder Anhang tragen echte Dateinamen.
- **Datumsschreibweisen**, in dieser Reihenfolge je Quelle: `2026-03-12`, `12.03.2026`, `2026-03`,
  „März 2026", dann ein Jahr 1900 bis 2099. Ein unmögliches Datum (ein Aktenzeichen wie
  `12.34.5678`) wird übersprungen. Ein nacktes Jahr zählt nur im Dateinamen und im Frontmatter; in
  einer Überschrift braucht es einen Anker wie „Stand 2026" oder „Fassung 2024", weil eine
  unverankerte Zahl dort ein Betrag oder ein Paragraf ist.
- **Extraktionsversion.** Die Regeln tragen eine Versionsnummer (heute 4), die an jedem Dokument
  gespeichert wird. Ändert sich eine Regel, steigt die Version, und der Bestand wird damit als
  nachzuziehen erkennbar (Abschnitt 6).

Die Chunks eines Dokuments bekommen die filterbaren Werte (`doc_type`, `doc_date`,
`doc_date_precision`) mitgeschrieben; eine spätere Änderung wird per Aktualisierung an den Chunks
nachgezogen, ohne neu zu zerlegen oder neu einzubetten. Der Titel steht nur am Dokument.

### 5.3 Modellgestützte Ermittlung und freie Schlagworte

Zwei Schalter je Bibliothek, **beide voreingestellt aus**, in den Bibliothekseinstellungen unter
**„Modellgestützte Extraktion"** (Verwaltungsrecht). Sie sind der einzige Teil der Ermittlung, der
Geld kostet, ausfallen kann und Dokumentinhalte an ein Sprachmodell übergibt.

> **Datenschutz.** Wird die aktive Chat-Rolle extern betrieben, verlässt mit eingeschaltetem
> Schalter **der Inhalt jedes aufgenommenen Dokuments dauerhaft das Haus** — anders als im Chat,
> ohne dass eine Person den Vorgang auslöst. Der Hinweis am Schalter benennt das und zeigt die
> Adresse und Modell-Kennung der aktiven Chat-Rolle (nie einen Zugangsschlüssel). Liegt die Rolle
> auf einem lokal betriebenen Modell, läuft die Ermittlung ohne ausgehende Verbindung; der Hinweis
> sagt auch das.

**Modellgestützte Ermittlung.** Läuft nach der regelbasierten Ermittlung und nur für Felder, die
diese leer gelassen hat — und nur für die unscharfen: Dokumentart und die Auswahlfelder der
Bibliothek. Titel, Datum/Stand und Musterfelder werden nie gefragt. Ein Aufruf je Dokument über die
zentrale Chat-Rolle, Zeitlimit 30 Sekunden; das Modell bekommt die Werteliste mit Codes und Labels,
die Titelzeile und die ersten 4.000 Zeichen des Textes und antwortet je Feld mit einem Code und
einer Konfidenz zwischen 0 und 1.

Der Dokumenttext steht im Prompt zwischen Markierungen und ist als Inhalt, nicht als Anweisung
gekennzeichnet; verlassen kann man sich darauf nicht — die verbindliche Schranke ist die Prüfung
gegen die Werteliste.

Der Modellschritt trägt eine **eigene Versionsnummer** neben der Extraktionsversion der Regeln: Eine
korrigierte Regel in Schritt 1 macht damit nicht jedes Dokument jeder eingeschalteten Bibliothek
erneut zu einem bezahlten Modellaufruf, und ein abgeleiteter Wert weist aus, welcher Schritt ihn
erzeugt hat.

Übernommen wird ein Wert nur, wenn seine Konfidenz **mindestens 0,80** beträgt **und** er in der
Werteliste steht. Alles andere bleibt leer — kein nächstähnlicher Wert, kein Vorgabewert. Übernommene
Werte tragen die Herkunft **„abgeleitet"** mit Konfidenz und Modell-Kennung; ein von Hand gesetzter
Wert wird nie überschrieben.

**Ein Ausfall hält nichts auf.** Zeitüberschreitung, nicht erreichbares Modell oder unbrauchbare
Antwort: Das Feld bleibt leer, das Dokument wird regulär aufgenommen und ist durchsuchbar. Es gibt
keinen erneuten Versuch und keine Warteschlange; nachgeholt wird über den Bestandslauf (Abschnitt 6).
Ein überschrittener Aufruf wird aufgegeben: Seine Antwort wird verworfen, beim Anbieter läuft er zu
Ende und erscheint auf dessen Rechnung; spätestens nach denselben 30 Sekunden bricht die Verbindung
ab. Sind alle Fäden der Ermittlung belegt, unterbleibt der Aufruf ganz („nicht angefragt
(ausgelastet)") statt eingereiht zu werden — auch das steht im Zählwerk, damit ein Engpass nicht wie
ein Modellausfall aussieht. Ein so übergangenes Dokument gilt weiterhin als offen und wird beim
nächsten Bestandslauf erneut angefasst.

**Zählwerk.** Je Bibliothek werden Aufrufe, übernommene Werte, wegen Konfidenz und wegen Werteliste
verworfene Werte, Fehler, nicht angefragte Dokumente und vergebene Schlagworte geführt — sichtbar in den Bibliothekseinstellungen
und auf der Seite „Suche & Indexierung". Ohne diese Zahlen ist die einzige Rückmeldung über die
Kosten die Rechnung des Modellanbieters. Verworfene Werte werden zusätzlich mit ihrer Konfidenz
protokolliert (je Bibliothek die 1.000 jüngsten), damit die Schwelle an einem echten Bestand
bewertbar bleibt.

**Freie Schlagworte.** Ist der zweite Schalter an, vergibt dasselbe Modell im selben Aufruf bis zu
fünf Schlagworte je Dokument, je höchstens 40 Zeichen. Sie stehen als eigenes Segment im
**Kontextpräfix** und erreichen darüber Einbettung und **Volltextindex** — eine Frage in
Alltagssprache findet damit ein Dokument in Amtssprache. Der gespeicherte Text des Dokuments bleibt
unverändert. Was sie nicht tun:

- **Sie filtern nie** — weder in der Suche noch als Facette; ein Filter, der ein Schlagwort benennt,
  wird abgewiesen.
- **Sie erscheinen nicht im Beleg** und nicht im Stichproben-Export.
- **Sie werden nicht zu Bibliotheksfeldern befördert**, auch nicht als Vorschlag aus der Häufigkeit.

### 5.4 Extraktionsgüte

In den Bibliothekseinstellungen steht neben den Schaltern die **Extraktionsgüte**: je Feld, wie viele
der indizierten Dokumente ihren Wert regelbasiert, modellbefüllt oder von Hand tragen, wie viele als
„kein Wert ermittelbar" gekennzeichnet und wie viele leer sind. Sie beantwortet die Frage, die ein
Suchergebnis allein nicht beantwortet: ob ein schwacher Filter am Filter liegt oder an der
Ermittlung. Die Zahlen werden bei jeder Abfrage gezählt, im Rechtekontext der fragenden Person.

Für die Handauswertung liefert `GET /api/v1/libraries/{libraryId}/metadata/sample?size=100`
(Verwaltungsrecht) eine Stichprobe in stabiler Reihenfolge: je Dokument die Titelzeile und jeden Wert
mit Herkunft, Konfidenz und Modell-Kennung.

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
- **Der Modellschritt läuft mit**, wenn einer der beiden Schalter aus Abschnitt 5.3 an ist: Ein
  eingeschalteter Schalter macht den vorhandenen Bestand genau einmal fällig, und ein Dokument,
  dessen Aufruf nichts ergab, wird kein zweites Mal bezahlt. Beide Fähigkeiten werden dabei
  **getrennt geführt** — wer erst die Schlagworte und später die modellgestützte Ermittlung
  einschaltet, erreicht den Altbestand mit beiden. Die Zahl „ausstehend" auf der Seite „Suche &
  Indexierung" und damit der Startknopf berücksichtigen diese Schalter. Gefragt wird nur für leer gebliebene
  Felder. Ein dort vergebenes Schlagwort ändert den Kontextpräfix und stellt das Dokument damit in
  den Kontextpräfix-Nachlauf; erst dieser bettet neu ein — der Bestandslauf selbst nie.
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

Dazu kommt das Formatfeld **Absender** mit den im Suchbereich vorkommenden Adressen und ihrer
Anzahl — höchstens den **20 häufigsten**, denn ein Postfach hat so viele Absender wie Korrespondenten;
daneben steht ein Eingabefeld für genau eine weitere Adresse. Das Feld wird angeboten, sobald
mindestens ein Dokument des Suchbereichs einen Absender trägt — ein Anteil am gemischten Bestand
würde dort die Formatverteilung messen, nicht die Metadatenqualität. Ein Suchbereich ohne Mails zeigt
den Abschnitt gar nicht.

Dazu kommen die **Bibliotheksfelder** der Bibliotheken im Suchbereich (Abschnitt 2b), jedes in der
Form seines Typs: Auswahl als Mehrfachauswahl der vorkommenden Werte, Datum als Fenster, Kennung als
Eingabefeld für genau einen Wert. Ein Bibliotheksfeld wirkt nur auf die Dokumente seiner eigenen
Bibliothek; Dokumente anderer Bibliotheken im selben Suchbereich schränkt es nie ein. Eine
Füllstandsschwelle gibt es für Bibliotheks- und Formatfelder nicht — sie beschreiben von vornherein
nur einen Teil des Bestands.

Der aktive Filter erscheint als entfernbare Chips („Dokumentart: Vermerk", „Datum: 01.01.2024 –
31.12.2024", „Absender: poststelle@stadt.de") und bleibt am Chat gespeichert. Die Werte setzt die Person; aus der Frage wird kein
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

## 9. Wirkung im Kontextpräfix

Ein Filter wirkt nur, wenn jemand ihn setzt. Die zweite Wirkstelle wirkt immer: Jeder Abschnitt geht
mit einem **Kontextpräfix** in Einbettung und Volltextindex — `Zweite Gebührensatzung › Fassung 2026
› § 7 Gebühren`. Der **gespeicherte Text bleibt unverändert**; der Auszug im Beleg ist weiterhin der
Originalwortlaut.

- **Der Titel ist immer präfixwirksam.** Er ersetzt die frühere Humanisierung des Dateinamens; ein
  Präfix ohne ihn benennt nichts.
- **Dokumentart und Datum/Stand sind je Bibliothek schaltbar und ab Werk aus**, ebenso jedes
  Bibliotheksfeld. Die Wirkstelle „Kontextpräfix" ist je Feld eine bewusste Entscheidung — eine
  Voreinstellung für alle Felder machte jede spätere Schemaänderung zu einem Neu-Einbetten, das
  niemand beauftragt hat.
- **Freie Schlagworte** stehen als eigenes Segment im Präfix (Abschnitt 5.3).

**Die Folgekosten stehen vor dem Speichern.** Der Bestätigungsdialog jeder Schemaänderung — Feld
anlegen, bearbeiten, löschen, Wert abbilden, Kernfeld schalten — nennt betroffene Dokumente,
betroffene Abschnitte, die Zahl der Einbettungsaufrufe und die erwartete Laufzeit („4.812 Abschnitte
in 12 Dokumenten neu einzubetten, rund 40 Minuten"), und sagt dazu, ob die zugrunde liegende Rate
gemessen oder geschätzt ist. Eine Werteliste zu erweitern kostet nichts; ein neu angelegtes Feld
zunächst auch nicht, weil noch kein Dokument einen Wert trägt.

**Das Speichern setzt nichts in Bewegung.** Es merkt nur die betroffenen Dokumente vor — genau die,
die die Vorschau gezählt hat, denn die Auswahl steht auf Dokumentebene über einen Abdruck des
zuletzt eingebetteten Präfix. Den **Kontextpräfix-Nachlauf** startet ein Systemadministrator je
Bibliothek auf der Seite **„Suche & Indexierung"**; die Bibliothekseinstellungen zeigen dafür „N
Dokumente warten auf Neu-Einbetten". Der Lauf bettet die Abschnitte **unter ihren eigenen Kennungen**
neu ein, ohne neu zu zerlegen: Belege und Deep Links überleben, die Suche bleibt durchgehend
verfügbar, und ein Dokument, das nicht verarbeitet werden kann, behält alles, was es hatte. Anhalten
ist schlicht das Ausbleiben des nächsten Aufrufs; ein zweiter Lauf über bereits verarbeitete
Dokumente kostet keinen Einbettungsaufruf.

Eine manuelle Korrektur eines präfixwirksamen Wertes bettet **nicht sofort** neu ein: Sie stellt das
eine Dokument in den Nachlauf, dessen Start eine ausdrückliche Freigabe bleibt. Ein Feld, das nur
filtert oder nur im Beleg steht, lässt den Abdruck unberührt.

## 10. Beleg-Anzeige

Die Fundstellenzeile und das Belegfenster einer Antwort zeigen Titel, Dokumentart und Datum/Stand
des zitierten Dokuments, mit „ · " verbunden; ein leeres Feld erscheint gar nicht, ein abgeleiteter
Wert ist als „(abgeleitet)" gekennzeichnet. Die Ortsangabe im Dokument bleibt daneben bestehen. Die
Formatfelder eines Dokuments stehen als weitere Einträge derselben Liste dahinter; ein Wert, den der
Titel bereits wörtlich zeigt — bei einer Mail der Betreff —, wird nicht zweimal aufgeführt. Der
Empfänger einer Mail erscheint **nur im Belegfenster**, nicht in der Fundstellenzeile: Eine
Verteilerliste ist lang und ordnet die Fundstelle nicht ein.

## 11. Rechte und Sichtbarkeit

| Was | Wer |
|---|---|
| Metadaten eines Dokuments sehen, Pflege-Anker und Extraktionsgüte der Bibliothek sehen | VIEWER an der Bibliothek |
| Modellgestützte Ermittlung und freie Schlagworte ein- oder ausschalten, Stichproben-Export | MANAGER an der Bibliothek |
| Bibliotheksfelder anlegen, ändern, löschen, Wertelisten pflegen und abbilden, Kontextpräfix-Wirkstellen schalten | MANAGER an der Bibliothek |
| Wert setzen, löschen, „kein Wert ermittelbar", Sammelzuweisung | EDITOR an der Bibliothek |
| Vokabular als Auswahlliste | jede angemeldete Person; es ist Schema, kein Bestand |
| Bestandslauf und Kontextpräfix-Nachlauf starten, Extraktionsstand und Füllgrad aller Bibliotheken der Organisation | Systemadministrator |
| Filter-Optionen und Füllstand | im Rechtekontext der fragenden Person über ihren Suchbereich |

Eine Bibliothek, die eine Person nicht lesen darf, ist in allen Zahlen abwesend; schon „412
Dokumente ohne Wert" verriete den Umfang eines fremden Bestands.

## 12. API

| Aufruf | Zweck |
|---|---|
| `GET /api/v1/libraries/{libraryId}/documents/{documentId}/metadata` | alle drei Kernfelder, auch leere, mit Herkunft |
| `PUT …/metadata/{fieldKey}` | Wert setzen oder „kein Wert ermittelbar"; `fieldKey` ist `title`, `document_type` oder `document_date` |
| `DELETE …/metadata/{fieldKey}` | Wert löschen |
| `POST /api/v1/libraries/{libraryId}/documents/metadata/bulk` | Sammelzuweisung |
| `GET /api/v1/libraries/{libraryId}/metadata/maintenance` | Pflege-Anker je Feld |
| `GET`/`PUT /api/v1/libraries/{libraryId}/metadata/extraction-settings` | die beiden Schalter mit der aktiven Chat-Rolle |
| `GET /api/v1/libraries/{libraryId}/metadata/quality` | Extraktionsgüte je Feld und Zählwerk |
| `GET /api/v1/libraries/{libraryId}/metadata/sample?size=100` | Stichprobe für die Handauswertung |
| `GET /api/v1/libraries/{libraryId}/documents?missingMetadataField=…` | die gezählten Dokumente ohne Wert |
| `GET`/`POST /api/v1/libraries/{libraryId}/metadata-fields` | Bibliotheksfelder lesen und anlegen |
| `PUT`/`DELETE …/metadata-fields/{fieldKey}` | Feld ändern oder löschen |
| `POST`/`PATCH …/metadata-fields/{fieldKey}/values[/{code}]` | Werteliste erweitern, Bezeichnung ändern |
| `POST …/metadata-fields/{fieldKey}/values/{code}/remap` | bestätigte Abbildung eines Listenwerts |
| `GET …/metadata-fields/change-impact?fieldKey=&change=` | Folgekosten einer geplanten Änderung |
| `PUT …/metadata-fields/core-context-prefix` | Kontextpräfix-Wirkstelle von Dokumentart und Datum/Stand |
| `POST /api/v1/admin/indexing/context-prefix-rerun` | ein Paket des Kontextpräfix-Nachlaufs |
| `GET /api/v1/metadata/document-types` | das Vokabular |
| `GET /api/v1/search/metadata-filter-options` | Füllstand, angebotene Felder, vorkommende Werte für den Suchbereich |
| `QueryRequest.metadataFilter`, `PATCH /api/v1/chats/{chatId}` | Filter je Anfrage bzw. am Chat |

## 13. Konfiguration

| Schlüssel | Umgebungsvariable | Standard | Wirkung |
|---|---|---|---|
| `opaa.query.metadata-filter.document-type-offer-threshold` | `OPAA_QUERY_METADATA_FILTER_DOCUMENT_TYPE_THRESHOLD` | 0.90 | Füllstand, ab dem die Dokumentart als Filter angeboten wird |
| `opaa.query.metadata-filter.document-date-offer-threshold` | `OPAA_QUERY_METADATA_FILTER_DOCUMENT_DATE_THRESHOLD` | 0.75 | Füllstand, ab dem Datum/Stand angeboten wird |
| `opaa.query.metadata-filter.options-cache-ttl` | `OPAA_QUERY_METADATA_FILTER_OPTIONS_CACHE_TTL` | 5m | Lebensdauer des Optionen-Zwischenspeichers je Person und Suchbereich |

Das Vokabular wird über die Datenbank erweitert (Abschnitt 3); die Extraktionsregeln sind nicht
konfigurierbar.

## 14. Gebaut, aber nicht abgenommen

**Die modellgestützte Ermittlung der Dokumentart ist gebaut und arbeitet — abgenommen ist sie
nicht.** Eine handausgewertete Stichprobe über 100 Dokumente der Demo-Instanz (05.09.2026,
`eval/reports/metadata-extraction-sample-2026-09-05.md`) hat gemessen: Von 49 modellbefüllten Werten
waren **46 falsch** (93,9 %), während die **regelbasiert** ermittelte Dokumentart auf derselben
Stichprobe fehlerfrei blieb (0 von 23). Fehlerfrei ist damit die Dokumentart, nicht die
regelbasierte Ermittlung insgesamt: Titel (14 %) und Datum/Stand (27 %) tragen falsche Werte, die
sämtlich deterministisch entstanden sind — dazu die beiden Befunde unter #1360. Die Konfidenz trennt dabei nicht richtig von falsch, sondern nur „geantwortet" von
„enthalten": Auf der Stufe 0,85 war kein einziger der 42 Werte richtig. Ursache ist nicht die
Schwelle, sondern eine Vokabularlücke — für 63 der 100 Dokumente gibt es keinen passenden Wert, und
das Modell greift dann zum nächstbesten.

Daraus folgt für den Betrieb:

- Der Schalter bleibt **voreingestellt aus**, und er sollte auf einem Bestand ohne passendes
  Vokabular ausgeschaltet bleiben. Die regelbasierte Ermittlung und die Sammelzuweisung (Abschnitt 7)
  sind dort der verlässliche Weg.
- Die Konfidenzschwelle wird mit **Ticket #1359** von 0,80 auf **0,90** angehoben, das Vokabular um
  Verwaltungswerte erweitert und der Prompt um Negativbeispiele ergänzt. Bis dahin gilt der in
  Abschnitt 5.3 beschriebene Stand.
- Zwei weitere Befunde derselben Stichprobe betreffen die regelbasierte Ermittlung und sind in
  **Ticket #1360** erfasst: Datumsangaben aus Dateieigenschaften sind bei generierten Dokumenten
  unbrauchbar, und der Titel-Fallback auf den Dateinamen greift zu früh.

## 15. Nicht gebaut

- Ableitung eines Filters aus der Frage (Ticket #1363); ein geführter Assistent zum Anlegen eines
  Bibliotheksschemas (Ticket #1362)
- Filter auf den Titel; Freitextfelder als Feldtyp; eine Oberfläche zur Pflege des Vokabulars; ein
  amtliches Metadatenmodell für die Aktenführung
- Vererbung eines Feldschemas über Bibliotheken hinweg; die Beförderung häufiger Schlagworte zu
  Bibliotheksfeldern
