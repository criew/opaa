# Wissensquellen und Konnektoren

> **Status: Entwurf — wesentliche offene Fragen verbleiben.**

**Themenbereich B** der [Produktvision](../VISION.md). **Phasenlage:** Uploads und **lesende**
Konnektoren zu Dateiablagen, Wikis, Postfächern und Vorgangssystemen gehören in **Phase 1**;
**schreibende** Integrationen mit Freigabeschritt und die Spiegelung der Rechte aus dem Quellsystem
folgen in **Phase 2**.

## Motivation

Das Wissen einer Behörde liegt nicht an einer Stelle. Es steckt in Netzlaufwerken mit gewachsener
Ordnerstruktur, im Intranet, in Postfächern, in Vorgangssystemen und in Wikis, die einmal jemand
angelegt hat. Wer eine Frage hat, sucht in mehreren Systemen nacheinander oder fragt eine erfahrene
Kollegin — und wenn die in Rente geht, geht das Wissen mit.

Ein Assistent, der nur beantworten kann, was jemand ihm gerade hochgeladen hat, löst dieses Problem
nicht. Er verschiebt es: Statt zu suchen, sammelt man vorher zusammen. Der Nutzen entsteht erst, wenn
sich Bestände **selbst aktuell halten** und die Zuständigkeit für ihre Pflege dort bleibt, wo sie
ohnehin liegt.

Zugleich ist der Zugriff auf Quellsysteme der Punkt, an dem die meisten Fehler eines
Wissensmanagementsystems entstehen: Ein Dienstkonto mit weitreichenden Rechten liest alles ein, und
danach findet jemand Dinge, die er nie hätte sehen dürfen. Dieses Dokument beschreibt daher nicht nur,
**wie** Wissen hereinkommt, sondern ebenso, **unter welcher Sicherung**.

Was mit den eingegangenen Dokumenten geschieht — Formaterkennung, Zerlegung, Einbettung, Suche —, steht
in [Wissensschicht und Retrieval](./data-indexing-rag.md). Wem ein Bestand gehört und wer ihn lesen
darf, in [Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md).

**Lesehinweis zum Umsetzungsstand.** Diese Spezifikation beschreibt überwiegend das Zielbild. Wo sie
bereits ausgelieferte Funktionalität beschreibt, ist das ausdrücklich mit **(gebaut)** gekennzeichnet.
Alles ohne diese Kennzeichnung ist noch nicht vorhanden.

---

## Überblick

1. **Zwei Wege führen Wissen in OPAA:** der **Upload** durch Menschen und der **Konnektor**, der aus
   einem Quellsystem zieht. Beide Wege sind heute gebaut. Eine Wissensbibliothek trägt dabei **genau
   einen Quellentyp** — `UPLOAD`, ein Verzeichnis im Dateisystem, eine erreichbare Verzeichnisliste im
   Netz oder künftig ein RSS-Feed —, gewählt bei ihrer Anlage aus einem Template und danach
   unveränderlich (**gebaut**, [ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md)).
   Die Bibliothek **ist** die Quelle; es gibt keine davon getrennte Konnektor- oder Quellen-Tabelle.
2. **Konnektorbestände aktualisieren sich selbst**, Uploads bleiben statisch. Das ist der wesentliche
   Unterschied und bestimmt, welcher Weg sich für welchen Zweck eignet.
3. **Eine Bibliothek hat höchstens einen Zufluss.** Gemischt gespeiste Bibliotheken — Upload und
   Konnektor oder mehrere Quellen in derselben Bibliothek — gibt es nicht (**gebaut**, ADR-0018).
   Mehrfachverwendung eines Bestands geschieht über die Freigabe derselben Bibliothek, nicht über
   mehrere Zuflüsse in einen Topf.
4. **Wer eine Bibliothek anlegen darf, wählt Typ und Konfiguration selbst; der Eigentümer der Bibliothek
   entscheidet, wer sie liest** — bei lauf-basierten Bibliotheken begrenzt durch eine Obergrenze der
   Freigabe. Dass die Anlage nicht auf die Systemverwaltung beschränkt ist, ist eine bewusste,
   **befristete** Entscheidung (ADR-0018, Entscheidung 6); ihre Einschränkung ist als eigenes Ticket
   (#484) vor einem Mehrbenutzer-Produktivbetrieb erfasst.
5. **Lesen ist der Normalfall, Schreiben die Ausnahme.** Schreibende Integrationen werden je Integration
   eigens freigeschaltet, laufen über einen menschlichen Freigabeschritt und werden vollständig
   protokolliert.
6. **Rechte aus dem Quellsystem werden gespiegelt, wo das Quellsystem sie belastbar liefert** — und wo
   nicht, wird das benannt statt unterstellt.
7. **Dokumente haben einen Lebenszyklus:** aufgenommen, aktualisiert, ausgeschlossen, archiviert,
   gelöscht. Jeder Übergang ist ausgelöst und protokolliert, keiner geschieht stillschweigend.
8. **Indizierung hat Zeitpläne und Vorrang.** Ein großer Erstlauf darf die tägliche Aktualisierung eines
   kleinen, wichtigen Bestands nicht blockieren.

---

## Die zwei Wege

| | **Upload** | **Konnektor** |
|---|---|---|
| Richtung | Mensch übergibt an OPAA | OPAA zieht aus dem Quellsystem |
| Auslöser | Handlung einer Person | Zeitplan, Ereignis oder ausdrücklicher Anstoß |
| Aktualität | statisch — die Fassung bleibt, wie sie übergeben wurde | selbst aktualisierend bei Änderung in der Quelle |
| Ziel | persönliche Bibliothek oder eine, an der die Person mindestens `EDITOR` ist | die eigene Bibliothek — sie **ist** die Quelle, Typ aus einem Template bei der Anlage gewählt |
| Ablage des Originals | im Dokumentenspeicher von OPAA | im Quellsystem; OPAA hält Extrakt und Verweis |
| Einrichtung | keine | Verzeichnispfad bzw. Adresse, Zugangsdaten, Proxy — an der Bibliothek hinterlegt; Zeitplan noch offen (#485) |
| Typischer Zweck | einzelner Vorgang, Anlage zu einer Frage, kurzlebiges Material | dauerhaft gepflegte Bestände, Rechtsquellen, Dienstanweisungen |

Die beiden Wege stehen nicht in Konkurrenz. Der Fehler wäre, Dauerbestände über Uploads zu führen: Dann
liegen Fassungen in OPAA, die niemand nachzieht, und die Antworten werden mit der Zeit leise falsch.
Umgekehrt lohnt für die Anlagen eines einzelnen Einspruchs kein Konnektor.

### Upload

Ablauf beim Hochladen:

1. Auswahl der Dateien über die Web-Oberfläche, als Anhang im Chat oder über die Schnittstelle.
   **Gebaut (#420, #422):** `POST /api/v1/libraries/{libraryId}/documents` als `multipart/form-data`-
   Schnittstelle sowie die Web-Oberfläche dazu (`LibraryDetailPage.tsx`) — Auswahl per Dateidialog und
   Drag-and-drop. Ein Anhang im Chat ist ein eigener, noch offener Vorgang.
2. Prüfung: Format, Größe, Schadsoftware. Abgelehnte Dateien werden mit Grund gemeldet.
   **Format und Größe sind gebaut** (#420) — dieselbe Formatliste (`SupportedDocumentFormats`) wie
   Verzeichnis- und URL-Aufnahme, eine konfigurierbare Größenobergrenze. **Die Schadsoftwareprüfung
   fehlt noch bewusst** — sie braucht eine eigene Entscheidung über Prüfdienst und Betriebsweg und ist
   als eigenes Issue vorzuziehen, bevor ein Produktivbetrieb möglich ist.
3. Ablage im Dokumentenspeicher der Installation, getrennt je Bibliothek. **Gebaut.**
4. Übergabe an die Verarbeitungskette (siehe [Wissensschicht](./data-indexing-rag.md)). **Gebaut** —
   dieselbe Pipeline (`FileProcessingService`) wie die anderen Aufnahmewege.
5. Ziel ist standardmäßig die **persönliche Wissensbibliothek**. Ein anderes Ziel ist wählbar, wo die
   Person am Ziel mindestens `EDITOR` ist. **Gebaut** — die Vorauswahl der persönlichen Bibliothek ist
   eine Client-Entscheidung (`personal`-Feld der Bibliotheksliste), kein zweiter Serverpfad. Es gibt
   dazu genau eine Bibliotheksauswahl auf der Dokumentenseite, die zugleich Anzeige- und Upload-Ziel
   ist: Der Ablagebereich erscheint nur, solange die dort gewählte Bibliothek mindestens `EDITOR`
   gewährt, sodass sich beide Zwecke nie widersprechen können.

Ein hochgeladenes Dokument lässt sich über `DELETE /api/v1/libraries/{libraryId}/documents/{documentId}`
auch wieder entfernen (`EDITOR` erforderlich) — die Dokumentzeile, ihre Chunks im Vektorspeicher und die
abgelegte Datei. **Gebaut (#420).**

Zwei Sicherungen gehören zusätzlich dazu, beide noch offen:

- **Hinweis auf ähnliche Bestände.** Vor dem Abschluss zeigt OPAA an, ob ein inhaltlich sehr ähnliches
  Dokument bereits vorliegt — beschränkt auf Bestände, die die hochladende Person ohnehin sehen darf.
  Der Hinweis blockiert nicht, er verhindert das stille Nebeneinander zweier Fassungen. Näheres unter
  [Duplikate erkennen](#duplikate-erkennen). Prüfsummengleiche Dateien werden bereits abgewiesen (#420);
  dieser inhaltliche Ähnlichkeitshinweis ist etwas anderes und bleibt offen.
- **Kontingente je Person** mit hausweitem Standardwert. Ohne sie wird der persönliche Bereich zur
  Ausweichablage für ganze Netzlaufwerke, und zwar an der Kuratierung vorbei.

Beide sind entschieden und als **Issue #119** erfasst (siehe [Geklärte Fragen](#geklärte-fragen)), aber
noch nicht gebaut. #420 hat dafür die Voraussetzung geschaffen: Jedes hochgeladene Dokument führt jetzt
seine einbringende Person. Die Originale liegen in einem eigenen, vom Indizierungsverzeichnis
getrennten Verzeichnis, das die Installation über `OPAA_UPLOAD_STORAGE_PATH` konfiguriert (Standard
`./uploads`, im Docker-Compose-Aufbau als eigenes Volume eingehängt); ob dahinter ein lokales
Dateisystem oder ein eingehängtes Netzlaufwerk steht, entscheidet der Betrieb und nicht die Anwendung
(siehe [Deployment und Infrastruktur](./deployment-infrastructure.md#speicher-backends)). Getrennt vom
Indizierungsverzeichnis deshalb, weil `DELETE .../documents/{documentId}` nur Dateien löscht, die es
selbst dorthin geschrieben hat — ein hochgeladenes Dokument darf verschwinden, ein vom Betrieb
gepflegter Bestand nicht.

Ein Upload ist **statisch**. Ändert sich das Original außerhalb von OPAA, merkt das niemand. Deshalb
führt jedes hochgeladene Dokument seinen Übergabezeitpunkt sichtbar mit, und die Antwort weist bei
älteren Uploads darauf hin.

### Konnektor

Ein **Konnektor** ist der Sammelbegriff für die lauf-basierten Quellentypen: OPAA zieht selbst aus
einem Quellsystem, statt dass jemand eine Datei übergibt. Anders als eine frühere Fassung dieses
Dokuments vorsah, ist ein Konnektor **kein eigenes Verwaltungsobjekt** mit mehreren Quellen, die auf
Bibliotheken zeigen: **Die Wissensbibliothek selbst ist die Quelle** (**gebaut**,
[ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md)). Sie trägt genau einen
**Quellentyp** — Dateisystem-Verzeichnis, Verzeichnisliste im Netz, künftig RSS-Feed — und, für diesen
Typ, die zugehörige Konfiguration: Verzeichnispfad bzw. Adresse, Zugangsdaten, Proxy, SSL-Schalter. Der
Typ wird bei der Anlage aus einem Template gewählt und ist danach unveränderlich; ein Typwechsel
verlangt eine neue Bibliothek.

Eine eigene Konnektor-Tabelle mit mehreren Quellen je Konnektor — das frühere Zielbild dieses
Abschnitts, mit Konnektoren wie „Netzlaufwerk Kämmerei" oder „Intranet-Wiki", die mehrere Pfade auf
mehrere Bibliotheken abbilden — wurde geprüft und **verworfen**: Jede real existierende Quelle hat
heute genau eine Bibliothek, jede Bibliothek höchstens eine Quelle, sodass die Indirektion reine
Vorratshaltung wäre. Näheres unter [ADR-0018, Verworfene
Alternativen](../decisions/0018-quellkonfiguration-in-der-bibliothek.md#verworfene-alternativen).
Sollte sich echter Bedarf für mehrere Quellen je Bibliothek zeigen, bleibt eine spätere Quellen-Tabelle
möglich — der ADR nennt das ausdrücklich als offene Erweiterung, mit dem Preis, dass die
Abwesenheitsprüfung dann nicht mehr je Bibliothek laufen dürfte.

**Quellklassen der ersten Ausbaustufe:** Dateiablagen und Netzlaufwerke über die gängigen
Netzdateiprotokolle, Wiki- und Intranetsysteme über deren Schnittstelle, Postfächer und E-Mail-Archive,
Vorgangs- und Ticketsysteme sowie einfache Webinhalte einschließlich offener Verzeichnislisten und
RSS-Feeds — für beide Web-Wege ist die Erschließung bereits gebaut, siehe
[Erreichbare Verzeichnislisten im Netz](#erreichbare-verzeichnislisten-im-netz-gebaut) und
[Feeds als Quelle](#feeds-als-quelle-gebaut). Weitere Quellklassen kommen bedarfsgetrieben hinzu, jede
als neuer Bibliothekstyp (Template); die Anbindung an Dokumentenmanagement und elektronische Akte
gehört in den Ausblick der Produktvision.

Diese Spezifikation nennt bewusst **Systemklassen und Protokolle statt Produkte**. Welche
Einzelprodukte eine Installation anbindet, ist eine Frage der Umsetzung und keine Produktzusage.

Eine Bibliothek kann **Einschluss- und Ausschlussmuster** tragen — Pfadmuster, Dateitypen,
Änderungsalter (**Zielbild**, noch nicht gebaut). Sie sind das wirksamste Mittel gegen den häufigsten
Fehler bei der Erschließung von Netzlaufwerken: zehntausend Dateien einzulesen, von denen dreihundert
gemeint waren.

Wie ein Quellentyp beschrieben, ausgewählt und erweitert wird — einschließlich der typabhängigen
Behandlung verschwundener Dokumente —, ist in
[ADR-0017](../decisions/0017-quellentypmodell-indizierung.md) festgehalten; wo seine dauerhafte
Konfiguration lebt, in [ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md).

### Verzeichnis im Dateisystem (gebaut)

Eine Bibliothek vom Typ `FILESYSTEM` liest ein Verzeichnis, das der Betrieb dem OPAA-Server zugänglich
gemacht hat — ein lokaler Pfad oder ein eingehängtes Netzlaufwerk, siehe [Deployment und
Infrastruktur](./deployment-infrastructure.md#speicher-backends). Der Pfad wird bei der Anlage
angegeben und ist Teil der Bibliothekskonfiguration; ein bibliotheksweiter Fallback auf einen einzigen,
global konfigurierten Pfad (wie er vor ADR-0018 bestand) entfällt.

**Sicherung des Pfads.** Ein frei wählbarer Dateisystempfad macht grundsätzlich jeden für den
OPAA-Server lesbaren Pfad indizierbar. Deshalb prüft der Betrieb jeden angegebenen Pfad gegen eine
**Allowlist** (`OPAA_INDEXING_FILESYSTEM_ALLOWLIST`); ein Pfad außerhalb der Allowlist wird bei Anlage
und bei jedem Lauf abgelehnt. Ist die Allowlist leer, ist die Prüfung **deaktiviert** — das ist der
Übergangszustand, den ADR-0018, Entscheidung 6 als befristet beschreibt, bis #484 die Anlageberechtigung
selbst einschränkt. Ablauf und Löschsemantik eines Laufs entsprechen der [Verzeichnisliste im
Netz](#erreichbare-verzeichnislisten-im-netz-gebaut): vollständige Auflistung bei jedem Lauf,
Löschung durch Abwesenheit eingeschlossen (siehe [ADR-0017](../decisions/0017-quellentypmodell-indizierung.md)).

### Erreichbare Verzeichnislisten im Netz (gebaut)

Viele Häuser stellen Dokumentbestände schlicht über einen Webserver bereit, der ein Verzeichnis als
HTML-Liste ausgibt. Für diese Bestände gibt es kein Quellsystem mit Schnittstelle — die Liste selbst
**ist** das Verzeichnis.

**Einordnung.** Dieser Weg wird als **Sonderform des Konnektors** geführt, nicht als dritter Weg neben
Upload und Konnektor. Der Grund: Er teilt alle bestimmenden Eigenschaften des Konnektors — OPAA zieht,
der Lauf ist wiederholbar, der Bestand hält sich selbst aktuell, das Original bleibt beim Quellsystem.
Ihn als eigenen Weg zu führen, würde diese Gemeinsamkeiten verdecken und für jede spätere Festlegung
zu Zeitplan, Vorrang, Zuordnung und Lebenszyklus eine zweite Regel erzwingen. Er unterscheidet sich vom
gewöhnlichen Konnektor nur darin, **woraus** die Liste der abzuholenden Dateien entsteht.

**Ablauf eines Laufs:**

1. Die Verzeichnisliste unter der angegebenen Adresse wird abgerufen und **rekursiv** durch die
   Unterverzeichnisse verfolgt.
2. Die gefundenen Einträge werden auf die verarbeitbaren Dateitypen gefiltert (siehe
   [Welche Dateien OPAA verarbeitet](./data-indexing-rag.md#welche-dateien-opaa-verarbeitet)).
3. Der **Änderungszeitpunkt aus der Liste** entscheidet, ob überhaupt geladen wird. Ein unverändertes
   Dokument wird übersprungen, bevor Bandbreite anfällt.
4. Geladen wird in einen temporären Bereich; anschließend wird eine **Prüfsumme über den Inhalt**
   gebildet. Sie erkennt Umbenennungen und Verschiebungen und sichert gegen einen unzuverlässigen
   Änderungszeitpunkt ab.
5. Die Datei durchläuft dieselbe Verarbeitungskette wie jedes andere Dokument.
6. Der temporäre Bereich wird nach der Verarbeitung geräumt — auch bei einem Fehler.

**Was der Weg heute kann:** rekursives Durchlaufen, einfache Anmeldung mit Benutzername und Kennwort,
Zugriff über einen Netzvermittler (Proxy), auf Wunsch das Aussetzen der Zertifikatsprüfung für
Bestände hinter selbstsignierten Zertifikaten, und ein Parser, der die verbreiteten
Verzeichnislistenformate der gängigen Webserver verträgt.

**Auslösung.** Der Lauf wird **an der Bibliothek** angestoßen — über `POST
/api/v1/libraries/{id}/indexing` oder die Detailseite der Bibliothek. Auslösen darf, wer an der
Bibliothek mindestens `EDITOR` ist; die frühere Beschränkung auf die Systemverwaltung ist mit ADR-0018
(Entscheidung 2) bewusst gefallen — ein Knopf, den nur die Systemverwaltung drücken darf, wäre für
jeden anderen Eigentümer einer Bibliothek tot. Adresse, Zugangsdaten, Proxy und SSL-Schalter sind Teil
der Bibliothekskonfiguration und werden nicht mehr je Lauf übergeben; ein Anstoß ohne Adresse wie beim
früheren globalen Dateisystem-Fallback gibt es nicht mehr. Ein eigenes, je Bibliothek gesetztes
Kontingent schützt gegen Überlastung, und es läuft höchstens ein Lauf gleichzeitig je Bibliothek. Der
Fortschritt ist über `GET /api/v1/libraries/{id}/indexing/status` abrufbar.

**Was noch fehlt** — und zwar so, dass es benannt gehört:

- **Zielprüfung.** Die angegebene Adresse wird heute nicht gegen private, lokale und nicht routbare
  Adressbereiche geprüft, und die zulässigen Schemata werden nicht ausdrücklich eingegrenzt.
  Weiterleitungen werden gefolgt. Mit der Öffnung des Anstoßes auf jeden `EDITOR` (ADR-0018) und der
  zunächst freien Anlageberechtigung (ADR-0018, Entscheidung 6) ist diese Härtung dringlicher als zuvor
  und gemeinsam mit der Einschränkung der Anlage (#484) Blocker für den Mehrbenutzer-Produktivbetrieb.
  Erfasst als **Issue #267**.
- **Zeitplan.** Der Lauf wird angestoßen, nicht geplant. Die Selbstaktualisierung im Sinne des
  nächsten Kapitels ist damit noch nicht erreicht. Mit der Konfiguration an der Bibliothek hat ein
  Zeitplan erstmals einen natürlichen Ort; entschieden wird das in **Issue #485**.

### Feeds als Quelle (gebaut)

Viele Häuser veröffentlichen Neuigkeiten, Bekanntmachungen und Pressemitteilungen als RSS-2.0-Feed —
darunter verbreitete Redaktionssysteme der Verwaltung. Anders als bei der Verzeichnisliste liefert ein
Feed keine fertige Liste abzuholender Dateien: Sein `<link>` verweist auf eine HTML-Detailseite, nicht auf
ein Dokument. Was indiziert werden soll, muss OPAA erst aus dieser Seite gewinnen.

**Ablauf eines Laufs — dreistufig, und die dritte Stufe nur bei tatsächlicher Verarbeitung:**

1. Der **Feed** wird abgerufen und in seine Einträge zerlegt (Titel, Verweis auf die Detailseite,
   Kurzbeschreibung, Veröffentlichungsdatum).
2. Für jeden Eintrag wird die verlinkte **Detailseite** abgerufen. Navigations-, Kopf- und Fußbereiche
   werden entfernt; der verbleibende Hauptinhalt liefert den Artikeltext, der indiziert wird — **ohne**
   die Datei- und Formatprüfung, die sonst jedem Dokument vorausgeht (siehe
   [Welche Dateien OPAA verarbeitet](./data-indexing-rag.md#welche-dateien-opaa-verarbeitet)): Es gibt
   keine Datei, nur bereits extrahierten Text.
3. Innerhalb desselben Inhaltsbereichs werden **Anlagen** gesucht — Verweise auf Dokumente im
   unterstützten Format. Welche Verweise als Anlage zählen, entscheidet ein konfigurierbares Profil: das
   allgemeine Profil (Endung im Verweisziel, gleicher Ursprung wie die Detailseite) oder das Profil für
   den Government Site Builder, dessen Verweise Anlagen stattdessen über einen Abfrageparameter
   kennzeichnen. Jede gefundene Anlage durchläuft dieselbe Verarbeitungskette wie eine Datei aus einer
   Verzeichnisliste. Diese dritte Stufe läuft nicht bei jedem Eintrag: Sie folgt entweder aus einer
   tatsächlichen Neuverarbeitung (Stufe 2) oder — bei einem unveränderten Eintrag ohne bisherige Anlagen —
   aus dem Nachholmechanismus der Änderungserkennung unten.

**Änderungserkennung — gestuft, je nach Sicherheit der Angabe:**

- Der **Feed selbst** wird mit einer bedingten Anfrage abgerufen (ETag/`If-Modified-Since`); meldet die
  Gegenstelle „unverändert", endet der Lauf nach dieser einen Anfrage.
- Jeder **Eintrag** wird zuerst gegen sein zuletzt gesehenes `pubDate` geprüft — bevor die Detailseite
  überhaupt angefragt wird. Ein Eintrag mit unverändertem `pubDate`, der bereits Anlagen-Dokumente
  besitzt, kostet damit nichts über den bereits geladenen Feed hinaus. **Fehlen ihm noch Anlagen** —
  dauerhaft bei einem Eintrag ohne Anlagen, einmalig bei einem vor Einführung der Anlagenverarbeitung
  indizierten Altbestand —, wird seine Detailseite trotzdem einmal abgerufen, ausschließlich um Anlagen
  nachzuholen; der Artikeltext selbst wird dabei nicht erneut verarbeitet. Ein unveränderter Feed erzeugt
  also nicht zwangsläufig keine weiteren Anfragen.
- Erst nach dem Abruf entscheidet eine **Prüfsumme über den Inhalt**, ob eine neue Fassung vorliegt —
  dieselbe Absicherung wie bei der Verzeichnisliste, hier zusätzlich zur Datumsprüfung, weil ein `pubDate`
  fehlen oder sich als unzuverlässig erweisen kann.
- Das **ETag/Last-Modified des Feeds** wird nur gespeichert, wenn der Lauf **nichts zurückgestellt** hat —
  keine Kürzung durch eine Obergrenze, keine Abweisung durch die Gegenstelle, keine verlorene Anlage, und
  kein fehlgeschlagener Eintrag. Sonst würde die bedingte Anfrage des nächsten Laufs genau die Einträge
  dauerhaft verbergen, die dieser Lauf nicht vollständig verarbeiten konnte — ein unvollständiger Lauf löst
  deshalb beim nächsten Mal bewusst einen erneuten vollständigen Abruf des Feeds aus.

**Verhalten gegenüber fremden Zielen.** Ein Feed und seine Detailseiten liegen bei einer Stelle, die OPAA
nicht betreibt und deren Schutzmaßnahmen zu erwarten sind. Der Weg ist entsprechend zurückhaltend gebaut:

- Ein konfigurierbarer **Mindestabstand** zwischen zwei Anfragen an Detailseiten und Anlagen.
- Eine **wahrheitsgemäße Kennung** (`User-Agent`) — kein Vortäuschen eines Browsers.
- **Zwei Zählgrenzen** — die Zahl verarbeiteter Einträge je Lauf und die Zahl der Anlagen je Eintrag.
  Überschreitungen werden protokolliert und abgeschnitten, nicht als Fehler des Laufs gewertet.
- **Drei Größengrenzen mit je eigener Wirkung**, weil eine zu große Antwort an unterschiedlichen Stellen
  ankommt: Überschreitet der **Feed selbst** seine Obergrenze, **scheitert der gesamte Lauf** — es gibt
  ohne einen geparsten Feed keine Einträge, über die einzeln entschieden werden könnte. Überschreitet
  eine **Detailseite** ihre Obergrenze, wird nur der **betroffene Eintrag übersprungen**, der Lauf läuft
  weiter. Überschreitet eine **Anlage** ihre Obergrenze, wird nur die **Anlage verworfen**; der Eintrag
  selbst bleibt davon unberührt.
- Eine **Same-Host-Regel für Anlagenverweise**, die Schema, Rechnername und Port der Detailseite
  gegenprüft — ein Verweis auf einen anderen Ursprung zählt unter keinem Profil als Anlage.
- Eine **Content-Type-Prüfung** — sowohl der Detailseite als auch jeder einzelnen Anlage. Zeigt der
  `<link>` eines Eintrags direkt auf ein Dokument statt auf eine HTML-Seite, wird der Eintrag
  übersprungen statt als verstümmelter Text indiziert. Antwortet ein Anlagenverweis stattdessen mit HTML
  — typischerweise eine Bot-Schutzseite oder eine mit Status 200 ausgelieferte Fehlerseite —, wird die
  Anlage verworfen statt als vermeintliches Dokument indiziert. Beim Government-Site-Builder-Profil, dessen
  Anlagenverweise keine Dateiendung tragen, bestimmt zusätzlich der Content-Type der Antwort den
  verwendeten Dateinamen samt Endung.
- **Abweisungen der Gegenstelle** — HTTP 403/429 oder eine Weiterleitung auf einen fremden Rechnername,
  etwa durch eine Bot-Schutzmaßnahme — werden getrennt von eigenen Verarbeitungsfehlern protokolliert.
  Sie sind bei einem gegen fremde Ziele laufenden Weg **zu erwarten**, kein Anzeichen eines Defekts, und
  brechen den Lauf nicht ab; der betroffene Eintrag oder die betroffene Anlage wird übersprungen und
  gezählt.

**Löschausnahme.** Anders als die Verzeichnisliste führt ein Feed bei jedem Abruf nur einen
Ausschnitt — üblicherweise die jüngsten Einträge in fester, begrenzter Zahl. Ein zuvor indizierter
Eintrag, der im aktuellen Abruf fehlt, ist deshalb keine verlässliche Aussage über sein Fortbestehen: Er
kann weiterhin gültig sein und ist nur aus dem geführten Fenster gerutscht. Für den Feed findet deshalb
**keine Löschung durch Abwesenheit** statt (siehe [Löschen in der Quelle wirkt
durch](#selbst-aktualisierende-wissensblöcke) unten und
[ADR-0017](../decisions/0017-quellentypmodell-indizierung.md)).

**Auslösung.** Wie bei der Verzeichnisliste wird der Lauf **an der Bibliothek** angestoßen — über `POST
/api/v1/libraries/{id}/indexing`. Die Bibliothek trägt den Typ `RSS_FEED` und die Feed-Adresse als
gespeicherte Konfiguration (**gebaut**, [ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md));
Auslösen darf, wer an der Bibliothek mindestens `EDITOR` ist, wie bei jedem lauf-basierten Typ.

**Was noch fehlt** — und zwar so, dass es benannt gehört:

- **Zeitplan.** Der Lauf wird angestoßen, nicht geplant, wie bei der Verzeichnisliste. Erfasst als
  **Issue #485**.
- **Herkunftsanzeige.** Eine Anlage führt intern fest, zu welchem Eintrag sie gehört
  (`source_entry_url`), aber weder die Schnittstelle noch die Oberfläche zeigen das an. Erfasst als
  **Issue #493**.
- **Zielprüfung.** Wie bei der Verzeichnisliste wird die angegebene Feed-Adresse nicht gegen private,
  lokale und nicht routbare Adressbereiche geprüft. Erfasst als **Issue #267**.

---

## Selbst aktualisierende Wissensblöcke

Der eigentliche Wert eines Konnektors liegt nicht im ersten Einlesen, sondern darin, dass der Bestand
danach **von allein richtig bleibt**. Zuständig für den Inhalt bleibt die Stelle, die das Quellsystem
ohnehin pflegt. Sie ändert eine Dienstanweisung dort, wo sie sie immer geändert hat, und OPAA antwortet
ab dem nächsten Lauf mit der neuen Fassung.

Damit das trägt, gelten drei Regeln:

1. **Der Stand ist sichtbar.** Jeder Bestand führt mit, wann er zuletzt abgeglichen wurde und wann er es
   das nächste Mal wird. Ein Beleg aus einem Bestand, dessen letzter Lauf gescheitert ist, wird
   gekennzeichnet.
2. **Löschen in der Quelle wirkt durch — für vollständig auflistende Quellentypen.** Ein in der Quelle
   entferntes Dokument wird aus dem Index genommen. Andernfalls bliebe eine zurückgezogene Weisung
   antwortfähig — der gefährlichste Fall überhaupt. Diese Regel gilt uneingeschränkt für
   **vollständig auflistende** Quellentypen (Verzeichnis im Dateisystem, Verzeichnisliste im Netz), die
   bei jedem Lauf den **gesamten** Bestand liefern: Fehlt ein zuvor indiziertes Dokument im aktuellen
   Lauf, ist das eine verlässliche Aussage über sein Verschwinden. Der **Feed** ist demgegenüber ein
   **ergänzender** Quellentyp — er liefert bei jedem Abruf nur einen Ausschnitt, üblicherweise die
   jüngsten Einträge —, für den dieselbe Schlussfolgerung falsch wäre; die Begründung und die
   Löschausnahme selbst stehen unter [Feeds als Quelle](#feeds-als-quelle-gebaut). Der **Upload** bildet
   eine dritte, **nicht lauf-basierte** Kategorie: Ein hochgeladenes Dokument entsteht außerhalb jedes
   Laufs und wird deshalb von keinem Lauf als verschwunden gezählt. Die vollständige Herleitung aller
   drei Kategorien steht in [ADR-0017](../decisions/0017-quellentypmodell-indizierung.md).
3. **Änderungen sind nachvollziehbar.** Die Abfolge der Fassungen bleibt erkennbar, damit eine ältere
   Antwort auf die Fassung verweisen kann, mit der sie erzeugt wurde.

**Erkennung von Änderungen** geschieht gestuft, weil nicht jede Quelle dasselbe hergibt: Änderungsmarke
oder Version aus dem Quellsystem, sonst Änderungszeitpunkt, sonst Prüfsumme des Inhalts. Die Prüfsumme
ist zugleich die Absicherung gegen Umbenennungen und Verschiebungen, die sonst als Neuaufnahme zählen
würden.

**Ereignisgesteuerte Aktualisierung** — das Quellsystem meldet Änderungen — ist der bessere Weg, wo das
Quellsystem sie anbietet, und wird dann mit dem Zeitplan kombiniert: Ereignisse halten den Bestand
tagsüber aktuell, der geplante Lauf gleicht nachts vollständig ab und fängt verlorene Meldungen auf.
Ein System, das sich allein auf Ereignisse verlässt, driftet unbemerkt.

---

## Eine Quelle, eine Wissensbibliothek

**Die 1:1-Zuordnung von Quelle und Wissensbibliothek ist strukturell erzwungen** (**gebaut**,
[ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md)): Es gibt keine von der
Bibliothek getrennte Quellzuordnung mehr, die auf mehr als ein Ziel zeigen könnte — die Bibliothek
**ist** die Quelle. Eine frühere Fassung dieses Abschnitts beschrieb dieselbe Regel noch als
Policy-Entscheidung mit offener Mehrfachzuordnung; das ist mit ADR-0018 gegenstandslos geworden, siehe
[Issue #207](https://github.com/criew/opaa/issues/207).

Der Grund bleibt derselbe wie zuvor, ist jetzt aber durch das Datenmodell erzwungen statt nur
empfohlen:

- **Technisch:** Eine Quelle, die in mehrere Ziele indiziert, vervielfacht jeden Chunk. Derselbe Absatz
  läge mehrfach im Index, würde mehrfach als Treffer erscheinen und müsste bei jeder Änderung an
  mehreren Stellen nachgezogen werden.
- **Fachlich:** Die Mehrfachverwendung eines Bestands ist ein Rechte-, kein Speicherproblem. Wird
  derselbe Bestand an mehreren Stellen gebraucht, wird **dieselbe Bibliothek** in weiteren Spaces
  bereitgestellt oder weiteren Gruppen freigegeben — eine Fassung, eine Pflegestelle.

**Gemischt gespeiste Bibliotheken gibt es nicht mehr.** Eine frühere Fassung dieses Abschnitts erlaubte
ausdrücklich, dass mehrere Quellen — oder Upload und Konnektor — in dieselbe Bibliothek zusammentreffen.
Das ist zurückgenommen: Die Ein-Typ-Regel (ADR-0018, Entscheidung 1) schließt genau diese Mischung aus,
weil sie die Abwesenheitsprüfung aus [ADR-0017](../decisions/0017-quellentypmodell-indizierung.md) —
„je Quelle, niemals bibliotheksweit" — am ehesten unterlaufen hätte: Ein Lauf, der versehentlich
bibliotheksweit statt je Quelle vergleicht, hätte Upload-Dokumente gelöscht, die er nie geliefert hat.
Weil jede Bibliothek höchstens eine Quelle hat, fällt „je Quelle" mit „je Bibliothek" zusammen, und
dieser Fehler ist strukturell ausgeschlossen.

### Zuständigkeit und Obergrenze der Freigabe

Die frühere Trennung „die Systemverwaltung entscheidet, wohin indiziert wird" gilt in dieser Form
**nicht mehr**: Wer eine Bibliothek anlegen darf, wählt Typ und Konfiguration selbst (ADR-0018,
Entscheidung 6 — eine bewusst befristete Öffnung, siehe [Überblick](#überblick)). Was bleibt:

| Wer | Entscheidet |
|---|---|
| Wer die Bibliothek anlegt | Quellentyp und Konfiguration — heute jeder mit Anlageberechtigung, eingeschränkt mit #484 |
| Eigentümer der Bibliothek | wer den Bestand lesen darf, bis zur **Obergrenze der Freigabe** bei lauf-basierten Bibliotheken |

Ohne die Obergrenze könnte ein Bibliothekseigentümer einen lauf-basierten Bestand organisationsweit
öffnen. Sie ist die einzige technische Sicherung zwischen „von einem Konnektor eingespeist" und
„hausweit lesbar" und deshalb kein Randthema. Ihre genaue Definition — was sie begrenzt und was beim
nachträglichen Absenken mit bereits erteilten Freigaben geschieht — ist Gegenstand von **Issue #207**
und wird dort entschieden; die Grundannahme, auf der sie beruhte — „die Systemverwaltung speist ein,
der Eigentümer gibt frei" —, gilt mit der freien Anlageberechtigung nicht mehr uneingeschränkt, was die
Entscheidung dort **dringlicher** macht, nicht überflüssig. Die frühere Frage nach der Obergrenze bei
gemischt gespeisten Bibliotheken entfällt dagegen ersatzlos — diese Bibliotheken gibt es nicht mehr
(siehe [Geklärte Fragen](#geklärte-fragen)).

Der frühere Abschnitt „Wenn die Zielbibliothek fehlt" ist mit ADR-0018 **gegenstandslos**: Er
beschrieb, was geschieht, wenn eine separat zugeordnete Zielbibliothek unter einer laufenden
Konnektorquelle gelöscht wird. Diese Situation kann nicht mehr eintreten, weil die Quelle die
Bibliothek selbst ist — wird sie gelöscht, gibt es keinen Lauf mehr, der ins Leere zeigen könnte. Was
das Löschen einer lauf-basierten Bibliothek stattdessen bedeutet, steht unter [Lebenszyklus der
Dokumente](#lebenszyklus-der-dokumente) und in
[ADR-0018, Entscheidung 5](../decisions/0018-quellkonfiguration-in-der-bibliothek.md).

---

## Lesender und schreibender Zugriff

Integrationen werden **je Integration** in eine von zwei Stufen eingeordnet. Es gibt keine dritte und
keinen Automatismus, der von der einen in die andere führt.

| Stufe | Was möglich ist | Freigabe |
|---|---|---|
| **Lesend** | Bestände abholen, indizieren, aktuell halten | Einrichtung durch die Systemverwaltung |
| **Schreibend** | im Quellsystem etwas anlegen oder ändern — Vorgang eröffnen, Vermerk ablegen, Antwortentwurf hinterlegen | ausdrückliche Freischaltung je Integration **und** je Aktionsart, zusätzlich menschliche Freigabe im Einzelfall |

**Lesen ist folgenlos, Schreiben nicht.** Ein Lesefehler erzeugt einen schlechten Treffer; ein
Schreibfehler erzeugt einen Verwaltungsakt. Deshalb ist die Autonomie abgestuft und die Grundeinstellung
ist immer die niedrigere Stufe.

Für schreibende Aktionen gilt:

- **Menschliche Freigabe vor der Ausführung.** OPAA legt den beabsichtigten Vorgang zur Bestätigung vor
  — vollständig sichtbar, mit Ziel, Inhalt und Wirkung. Ohne Bestätigung geschieht nichts.
- **Eigene Zugangsdaten für den Schreibweg**, getrennt vom lesenden Zugang. Ein Konto, das nur lesen
  darf, kann auch bei einem Fehler nicht schreiben.
- **Vollständige Protokollierung** — wer freigegeben hat, was ausgeführt wurde, mit welchem Ergebnis und
  auf welcher Grundlage.
- **Rücknahme ist Sache des Quellsystems.** OPAA verspricht kein Zurücknehmen einer ausgeführten Aktion;
  es protokolliert sie so, dass sie im Quellsystem nachvollzogen und dort rückabgewickelt werden kann.

Die Ausgestaltung der Freigabeschritte, der Werkzeugrechte eines Agenten und der Anbindung über ein
standardisiertes Werkzeugprotokoll gehört zu Themenbereich D und wird dort beschrieben.

**Phase 2.**

---

## Spiegelung der Rechte aus dem Quellsystem

Wenn ein Dienstkonto einen Bestand einliest, hat OPAA danach Inhalte, deren ursprüngliche
Zugriffsbeschränkung nur im Quellsystem stand. Ohne Behandlung entsteht daraus der klassische Fehler:
Das Personalverzeichnis war im Netzlaufwerk auf ein Referat beschränkt, in OPAA ist es hausweit
auffindbar.

Es gibt drei Umgangsweisen, und sie schließen einander nicht aus.

**Option 1 — Zuschnitt der Quelle.** Es wird nur eingelesen, was ohnehin für den Leserkreis der
Zielbibliothek bestimmt ist; die Beschränkung entsteht durch die Auswahl des Pfades und die
Ausschlussmuster. Einfach, sofort verfügbar, ohne Abhängigkeit vom Quellsystem — aber grob, und der
Schutz hängt an der Sorgfalt bei der Einrichtung.

**Option 2 — Übernahme der Rechte in die Bibliothek.** Beim Einlesen werden die Berechtigungen des
Quellsystems ausgelesen und auf Gruppen aus dem Verzeichnisdienst abgebildet; die Bibliothek erhält
daraus ihre Rechteliste. Trifft die Wirklichkeit gut, funktioniert aber nur, wo das Quellsystem Rechte
überhaupt maschinell herausgibt und wo sich seine Subjekte auf Verzeichnisgruppen abbilden lassen.
Bei gewachsenen Ablagen mit Einzelfreigaben ist das oft nicht der Fall.

**Option 3 — Prüfung gegen das Quellsystem zur Abfragezeit.** Vor der Ausgabe eines Treffers wird beim
Quellsystem nachgefragt, ob die fragende Person das Dokument sehen darf. Am genauesten und immer aktuell
— aber langsam, macht die Suche von der Erreichbarkeit des Quellsystems abhängig und verträgt sich
schlecht mit dem Grundsatz, dass die Rechteprüfung **in** der Vektorsuche sitzt und unberechtigte Chunks
gar nicht erst geladen werden.

**Empfehlung:** Option 1 in Phase 1 als verbindliche Grundlage — die Zielbibliothek und ihr Leserkreis
sind der maßgebliche Rechteanker, und die Auswahl der Quelle richtet sich danach. Option 2 in Phase 2
für Quellsysteme, die Rechte belastbar liefern, und dann **nur einschränkend**: Übernommene Rechte
können den Leserkreis der Bibliothek verengen, nie erweitern. Option 3 wird verworfen, solange die
rechtebewusste Suche in der Vektorsuche selbst durchgesetzt wird.

Verbindlich gilt in jedem Fall: **Die Zielbibliothek ist der Rechteanker.** Was in sie hineinindiziert
wird, ist für ihren Leserkreis sichtbar. Diese Aussage muss der Person, die eine lauf-basierte
Bibliothek anlegt, **an Ort und Stelle angezeigt** werden, samt der Frage, ob der Zuschnitt der Quelle
dazu passt. Ein Konnektor, der ohne diese Bestätigung eingerichtet wird, ist die wahrscheinlichste
Fehlerquelle des ganzen Systems.

Wo die Spiegelung nicht möglich ist, wird das **benannt**: Die Bibliothek weist aus, dass ihre Rechte
nicht aus dem Quellsystem stammen, sondern eigenständig gesetzt sind.

---

## Lebenszyklus der Dokumente

Ein Dokument in OPAA durchläuft Zustände. Jeder Übergang hat einen Auslöser, eine zuständige Rolle und
einen Protokolleintrag.

| Zustand | Bedeutung | Wirkung auf die Suche |
|---|---|---|
| **aktiv** | regulär indiziert | wird gefunden |
| **ausgeschlossen** | von einer verantwortlichen Person aus dem Bestand genommen | wird nicht gefunden; bei künftigen Läufen übersprungen |
| **archiviert** | weiterhin gültig, aber erkennbar älterer Stand | wird gefunden und als älterer Stand gekennzeichnet |
| **entfernt** | in der Quelle gelöscht oder Bestand aufgelöst | wird nicht gefunden |

**Ausschluss** ist die wichtigste Einzelfunktion dieses Kapitels. Wer an einer Bibliothek `MANAGER` ist,
kann einzelne Dokumente aus dem Bestand nehmen — etwa einen versehentlich in der Ablage liegenden
Personalvorgang. Der Ausschluss wirkt an genau einer Stelle, überdauert jeden weiteren Lauf und wird
nicht durch die nächste Aktualisierung stillschweigend aufgehoben. Er ist jederzeit einsehbar und
rücknehmbar.

**Löschung** unterscheidet Fälle, die nicht vermischt werden dürfen:

- **Aus der Quelle verschwunden** — OPAA nimmt das Dokument aus dem Index. Das Protokoll behält den
  Vorgang; der Inhalt ist weg.
- **Löschverlangen nach Datenschutzrecht** — greift auf alle Ableitungen durch: Chunks, Einbettungen,
  zwischengespeicherte Extrakte und Vorschauen. Verfahren und Fristen stehen in
  [Zugangskontrolle](./access-control.md#datenlöschung-dsgvo).
- **Löschung der ganzen Bibliothek (gebaut, [ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md), Entscheidung 5).**
  Sie ist typabhängig geregelt, weil eine Einzellöschung bei lauf-basierten Bibliotheken wirkungslos
  wäre — der nächste Lauf nähme das Dokument wieder auf, solange es dessen vollständige Quelle bleibt.
  `UPLOAD`-Bibliotheken behalten deshalb die Löschsperre, solange sie Dokumente enthalten (Dokumente
  sind einzeln löschbar). Das Löschen einer **lauf-basierten** Bibliothek nimmt dagegen ihren
  gesamten Bestand mit — Dokumentzeilen und Chunks im Vektorspeicher —, nach ausdrücklicher Bestätigung
  und mit Protokolleintrag.

### Duplikate erkennen

Derselbe Inhalt liegt in gewachsenen Ablagen regelmäßig mehrfach: als Kopie im Netzlaufwerk, als Anhang
in mehreren Vorgängen, als zweiter Upload durch eine zweite Person. Ohne Behandlung erscheint dieselbe
Passage mehrfach als Treffer, und niemand weiß, welche der Fassungen gilt.

OPAA setzt dagegen zwei Mittel ein, die verschiedene Fälle abdecken:

- **Prüfsumme über den Inhalt (gebaut).** Jedes verarbeitete Dokument führt eine Prüfsumme mit. Ist sie
  unverändert und war der letzte Lauf erfolgreich, wird das Dokument übersprungen; hat sie sich
  geändert, werden die alten Zerlegungen entfernt, bevor die neuen entstehen. Das erkennt **exakte**
  Doppel — auch nach Umbenennung oder Verschiebung — und verhindert zugleich, dass eine geänderte Datei
  doppelt im Index steht.
- **Hinweis auf ähnliche Bestände beim Upload (Zielbild).** Prüfsummen greifen nicht, sobald sich ein
  Dokument in einem Zeichen unterscheidet — und genau das ist bei zwei Fassungen derselben
  Besprechungsnotiz der Normalfall. Vor dem Abschluss eines Uploads zeigt OPAA deshalb inhaltlich sehr
  ähnliche Dokumente an, **beschränkt auf Bestände, die die hochladende Person ohnehin sehen darf**.
  Die Beschränkung ist wesentlich: Ein Hinweis auf ein Dokument, das jemand nicht sehen darf, verrät
  dessen Existenz. Der Hinweis blockiert nicht; er verhindert das stille Nebeneinander zweier
  Fassungen. Erfasst als **Issue #119**.

Eine **automatische** Entfernung erkannter Duplikate findet nicht statt. Welche von zwei Fassungen
gilt, ist eine fachliche Entscheidung, und ein Indizierungslauf kann sie nicht treffen.

**Neuindizierung** wird ausgelöst, wenn sich die Verarbeitung geändert hat — anderes Einbettungsmodell,
andere Zerlegungsstrategie, korrigierte Formaterkennung. Sie ist je Bibliothek auslösbar und läuft mit
niedrigerem Vorrang als die laufende Aktualisierung, damit ein großer Nachlauf den Betrieb nicht
anhält.

---

## Zeitpläne, Vorrang und Betrieb

### Auslöser

Ein Lauf beginnt auf vier Wegen: nach **Zeitplan je Bibliothek** (Zielbild, **Issue #485** — heute wird
angestoßen, nicht geplant), durch eine **Meldung des Quellsystems** (Zielbild), durch **ausdrücklichen
Anstoß** — `POST /api/v1/libraries/{id}/indexing`, EDITOR an der Bibliothek genügt (**gebaut**,
ADR-0018) — oder, beim Upload, **unmittelbar** mit der Übergabe.

### Vorrang

Ohne Vorrangregel gewinnt der Lauf, der zuerst gestartet ist, und das ist regelmäßig der falsche: Der
Erstlauf über ein großes Netzlaufwerk läuft stundenlang, während die stündliche Aktualisierung der
Dienstanweisungen wartet. Drei Stufen ordnen das:

| Stufe | Beispiel | Verhalten |
|---|---|---|
| **sofort** | Upload durch eine Person, die auf das Ergebnis wartet | wird vorgezogen |
| **regulär** | geplante Aktualisierung eines gepflegten Bestands | Regelbetrieb |
| **nachrangig** | Erstlauf, vollständige Neuindizierung | läuft in den Lücken, wird bei Bedarf ausgesetzt |

Zusätzlich soll ein **Schonzeitraum je Bibliothek** (Zielbild, mit dem Zeitplan aus #485) die Last auf
das Quellsystem begrenzen. Ein Fachverfahren, das tagsüber im Wirkbetrieb steht, soll nicht in der
Kernzeit vollständig gelesen werden — die Einführung von OPAA darf kein Fachverfahren ausbremsen.

### Fehlerbehandlung

- Ein Dokument, das nicht verarbeitet werden kann, wird **übersprungen und protokolliert**; der Lauf
  läuft weiter.
- Wiederholt scheiternde Dokumente kommen auf eine Liste mit Grund, statt bei jedem Lauf erneut
  aufzuhalten.
- **Ein Lauf, der scheitert, lässt den bisherigen Bestand stehen.** Ein halb aktualisierter Bestand ohne
  Kennzeichnung wäre schlimmer als ein erkennbar veralteter.
- Nach mehreren erfolglosen Versuchen an derselben Quelle wird der Zeitplan gedrosselt und die
  Systemverwaltung benachrichtigt.

### Sicht der Systemverwaltung

Einsehbar ist je Bibliothek: letzter und nächster Lauf, Dauer, Zahl aufgenommener, geänderter,
entfernter und übersprungener Dokumente, Fehlerliste und — bei lauf-basierten Bibliotheken — deren
Obergrenze der Freigabe. Gemeldet wird bei nicht erreichbarer Quelle, auffällig hoher Fehlerquote,
ungewöhnlich langem Lauf und knappem Speicher.

Diese Auswertungen sind **bestands-, nicht personenbezogen**. Sie zählen Dokumente und Läufe, nicht
Menschen.

---

## Integrationspunkte

- **[Wissensschicht und Retrieval](./data-indexing-rag.md)** — übernimmt jedes eingehende Dokument:
  Formaterkennung, Extraktion, Zerlegung, Einbettung, Suche.
- **[Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md)** — Wissensbibliothek als Ziel und
  Rechteanker, Obergrenze der Freigabe, Bereitstellung desselben Bestands an mehreren Stellen.
- **[Zugangskontrolle](./access-control.md)** — Zugangsdaten und Dienstkonten, Protokollpflicht,
  Löschverlangen, Trennung von Konnektor- und Upload-Dokumenten.
- **[Modelle und zentrale Steuerung](./llm-integration.md)** — die Beschränkung einer Bibliothek auf
  bestimmte Modelle wirkt auch auf die Verarbeitung ihrer Dokumente.
- **[Deployment und Infrastruktur](./deployment-infrastructure.md)** — Dokumentenspeicher, Netzwege zu
  Quellsystemen, Ressourcen für Indizierungsläufe.
- **[Benutzer-Frontends](./user-frontends.md)** — Upload-Wege, Anzeige von Bestandsstand und
  Verarbeitungsfehlern.

---

## Geklärte Fragen

Entscheidungen, die bereits getroffen sind. Sie stehen hier, damit sie nicht in einem Jahr als neue
Idee wieder aufgemacht werden.

- **Speicherkontingente — ja, für manuelle Uploads.** Es gibt eine Obergrenze je Person mit einem
  hausweit konfigurierbaren Standardwert; einzelne Personen können davon abweichend gesetzt werden.
  Ohne Kontingent wird der persönliche Bereich zur Ausweichablage für ganze Netzlaufwerke, und zwar an
  der Kuratierung vorbei. Konnektorbestände sind davon nicht betroffen — sie werden über den Zuschnitt
  der Quelle begrenzt, nicht über ein Kontingent. Erfasst als **Issue #119**.
- **Anzeige ähnlicher Dokumente beim Upload — ja**, beschränkt auf Bestände, die die hochladende Person
  sehen darf, und als Hinweis ohne Blockade (siehe [Duplikate erkennen](#duplikate-erkennen)). Ebenfalls
  **Issue #119**.
- **Dokumentenversionierung — ja.** Die Abfolge der Fassungen bleibt erkennbar, damit eine ältere
  Antwort auf die Fassung verweisen kann, mit der sie erzeugt wurde. Sie gehört fachlich mit der
  Duplikatanzeige zusammen: Beides beantwortet die Frage, welche von mehreren Fassungen gilt — die eine
  über die Zeit, die andere über den Bestand.
- **Die 1:1-Zuordnung von Quelle und Wissensbibliothek ist strukturell erzwungen.** Eine Bibliothek trägt
  höchstens eine Quelle, eine Quelle speist höchstens eine Bibliothek; Mehrfachverwendung geschieht über
  die Bereitstellung derselben Bibliothek, nicht über mehrere Zuflüsse. Mit ADR-0018 ist das kein
  Policy-Beschluss mehr, sondern eine Eigenschaft des Datenmodells; offen bleibt in **Issue #207**
  ausschließlich die Obergrenze der Freigabe.
- **Lesen ist der Normalfall, Schreiben die Ausnahme** mit ausdrücklicher Freischaltung je Integration
  und menschlichem Freigabeschritt im Einzelfall.
- **Eine Bibliothek trägt genau einen Quellentyp und höchstens eine Quellkonfiguration — gewählt bei der
  Anlage aus einem Template, danach unveränderlich.** Gemischt gespeiste Bibliotheken (mehrere Quellen,
  oder Upload und Konnektor zusammen) entfallen ersatzlos. Erfasst und entschieden mit
  [ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md).
- **Die Konfiguration einer erreichbaren Verzeichnisliste im Netz (und jedes anderen lauf-basierten
  Typs) lebt an der Bibliothek, nicht im einzelnen Anstoß-Request.** Verzeichnispfad bzw. Adresse,
  Zugangsdaten, Proxy und das Aussetzen der Zertifikatsprüfung sind Teil der Bibliothekskonfiguration
  und überleben den einzelnen Lauf. Damit ist auch geklärt, dass diese Felder **nicht** in ein
  gesondertes Konnektor-Objekt gehören, sondern an das Objekt, das ohnehin schon existiert — die
  Bibliothek. Entschieden mit ADR-0018.
- **Die Obergrenze der Freigabe bei gemischt gespeisten Bibliotheken stellt sich nicht mehr.** Da es
  keine gemischt gespeisten Bibliotheken mehr gibt, entfällt die frühere Frage, ob eine Obergrenze für
  den gesamten Inhalt oder nur den konnektorgespeisten Teil gilt.

---

## Offene Fragen / Zukünftige Erweiterungen

- Wie wird die Obergrenze der Freigabe genau definiert, und was geschieht mit bereits erteilten,
  weiter reichenden Freigaben, wenn sie nachträglich abgesenkt wird? Für eine Prüfstelle ist das der
  Unterschied zwischen „behoben" und „nicht behoben". Mit der zunächst freien Anlageberechtigung
  (ADR-0018, Entscheidung 6) ist diese Frage dringlicher geworden, nicht weniger relevant. Entschieden
  wird das in **Issue #207**.
- Welche Quellsysteme geben Rechte belastbar genug heraus, dass Option 2 der Spiegelung sich lohnt?
- Wie werden Zugangsdaten für Quellsysteme **gewechselt**, ohne dass ein Lauf ausfällt? Die Verwahrung
  selbst ist geklärt — verschlüsselt, write-only, keine Rückgabe in keiner API-Antwort
  ([ADR-0018](../decisions/0018-quellkonfiguration-in-der-bibliothek.md), Entscheidung 4) —, der
  Wechselweg (Rotation eines kompromittierten oder ablaufenden Zugangs) bleibt offen.
- Soll ein Konnektor Dokumente aus dem Quellsystem **zwischenspeichern** dürfen, damit Belegsprünge auch
  bei nicht erreichbarem Quellsystem funktionieren? Das erhöht den Nutzen und zugleich die
  Datenhaltung.
- Wie wird mit Quellsystemen umgegangen, die dasselbe Dokument mehrfach führen — Kopien im
  Netzlaufwerk, Anhänge in mehreren Vorgängen?
- Braucht es eine Massenübernahme aus einem lokalen Laufwerk, oder ist das genau der Weg, der
  ungepflegte Bestände in den persönlichen Bereich spült?
- Wie werden sehr große Erstläufe abgeschätzt und angekündigt, damit Betrieb und Fachbereich vorher
  wissen, womit sie rechnen?
- Soll eine Bibliothek mehrere Quellen desselben Typs tragen dürfen — etwa zwei Verzeichnispfade? Der
  heutige Schnitt „eine Bibliothek, eine Quelle" ist bewusst streng (ADR-0018); sollte sich echter
  Bedarf zeigen, wäre eine Quellen-Tabelle n:1 zur Bibliothek die Erweiterung, mit dem Preis, dass die
  Abwesenheitsprüfung dann nicht mehr je Bibliothek laufen dürfte, sondern je einzelne Quelle.
- Sollen Einschluss- und Ausschlussmuster (Pfadmuster, Dateitypen, Änderungsalter) Teil der
  Bibliothekskonfiguration werden? Noch nicht gebaut, siehe [Konnektor](#konnektor).

---

## Erfolgs-Metriken

- **Aktualitätsabstand** — Zeit zwischen der Änderung in der Quelle und ihrer Wirksamkeit in der
  Antwort, im Mittel und im schlechtesten Fall je Bestand.
- **Erschließungsgrad** — Anteil der in einer Quelle vorhandenen Dokumente, die tatsächlich verarbeitet
  wurden.
- **Fehlerquote je Lauf** und Zahl dauerhaft nicht verarbeitbarer Dokumente.
- **Anteil der Bestände mit gültigem Zeitplan**, die ohne Eingriff aktuell bleiben — das eigentliche
  Versprechen dieses Themenbereichs.
- **Zahl nachträglicher Ausschlüsse** je Bibliothek als Hinweis auf einen zu weit gefassten
  Quellzuschnitt.
- **Anteil schreibender Aktionen, die bei der Freigabe abgelehnt wurden** — dauerhaft hohe Werte deuten
  auf einen ungeeigneten Zuschnitt der Integration hin.
