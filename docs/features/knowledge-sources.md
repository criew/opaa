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
   einem Quellsystem zieht. Die Indizierung aus einer erreichbaren Verzeichnisliste im Netz ist eine
   **Sonderform des Konnektors** und zusammen mit der Indizierung eines Verzeichnisses im Dateisystem
   heute der gebaute Stand; der Upload gehört zum Zielbild.
2. **Konnektorbestände aktualisieren sich selbst**, Uploads bleiben statisch. Das ist der wesentliche
   Unterschied und bestimmt, welcher Weg sich für welchen Zweck eignet.
3. **Eine Konnektorquelle speist genau eine Wissensbibliothek.** Kein Bestand wird vervielfacht;
   Mehrfachverwendung geschieht über die Bereitstellung derselben Bibliothek.
4. **Die Systemverwaltung entscheidet, wohin indiziert wird; der Eigentümer der Bibliothek entscheidet,
   wer es sieht** — begrenzt durch eine Obergrenze der Freigabe.
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
| Ziel | persönliche Bibliothek oder eine, an der die Person mindestens `EDITOR` ist | genau eine Bibliothek, festgelegt von der Systemverwaltung |
| Ablage des Originals | im Dokumentenspeicher von OPAA | im Quellsystem; OPAA hält Extrakt und Verweis |
| Einrichtung | keine | Zugangsdaten, Zuordnung, Zeitplan |
| Typischer Zweck | einzelner Vorgang, Anlage zu einer Frage, kurzlebiges Material | dauerhaft gepflegte Bestände, Rechtsquellen, Dienstanweisungen |

Die beiden Wege stehen nicht in Konkurrenz. Der Fehler wäre, Dauerbestände über Uploads zu führen: Dann
liegen Fassungen in OPAA, die niemand nachzieht, und die Antworten werden mit der Zeit leise falsch.
Umgekehrt lohnt für die Anlagen eines einzelnen Einspruchs kein Konnektor.

### Upload

Ablauf beim Hochladen:

1. Auswahl der Dateien über die Web-Oberfläche, als Anhang im Chat oder über die Schnittstelle.
   **Gebaut (#420, #422):** `POST /api/v1/libraries/{libraryId}/documents` als `multipart/form-data`-
   Schnittstelle sowie die Web-Oberfläche dazu (`DocumentsPage.tsx`) — Auswahl per Dateidialog und
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

Ein **Konnektor** beschreibt das Quellsystem und die gemeinsame Konfiguration: Adresse, Zugangsdaten,
Zeitplan, Netzwegangaben. Ein Konnektor hat eine oder mehrere **Quellen** — die konkret abzuholenden
Ausschnitte, etwa ein Verzeichnispfad, ein Wiki-Bereich, ein Postfachordner oder ein Vorgangsbereich.

```
Konnektor  "Netzlaufwerk Kämmerei"
  Zugang:    Dienstkonto, nur lesend
  Zeitplan:  werktäglich 03:00
  Quellen:
    //fileserver/kaemmerei/haushalt      → Bibliothek "Haushalt"
    //fileserver/kaemmerei/dienstanw     → Bibliothek "Dienstanweisungen Kämmerei"

Konnektor  "Intranet-Wiki"
  Zugang:    Dienstkonto, nur lesend
  Zeitplan:  stündlich
  Quellen:
    Bereich "Organisation"               → Bibliothek "Hausweite Regelungen"
    Bereich "Personalrecht"              → Bibliothek "Personalrecht"
```

**Quellklassen der ersten Ausbaustufe:** Dateiablagen und Netzlaufwerke über die gängigen
Netzdateiprotokolle, Wiki- und Intranetsysteme über deren Schnittstelle, Postfächer und E-Mail-Archive,
Vorgangs- und Ticketsysteme sowie einfache Webinhalte einschließlich offener Verzeichnislisten — für
letztere ist der Weg bereits gebaut, siehe
[Erreichbare Verzeichnislisten im Netz](#erreichbare-verzeichnislisten-im-netz-gebaut). Weitere
Quellklassen kommen bedarfsgetrieben hinzu; die Anbindung an Dokumentenmanagement und elektronische Akte
gehört in den Ausblick der Produktvision.

Wie ein Quellentyp beschrieben, ausgewählt und erweitert wird — einschließlich der typabhängigen
Behandlung verschwundener Dokumente —, ist in
[ADR-0015](../decisions/0015-quellentypmodell-indizierung.md) festgehalten.

Diese Spezifikation nennt bewusst **Systemklassen und Protokolle statt Produkte**. Welche
Einzelprodukte eine Installation anbindet, ist eine Frage der Umsetzung und keine Produktzusage.

Jede Quelle kann **Einschluss- und Ausschlussmuster** tragen — Pfadmuster, Dateitypen, Änderungsalter.
Sie sind das wirksamste Mittel gegen den häufigsten Fehler bei der Erschließung von Netzlaufwerken:
zehntausend Dateien einzulesen, von denen dreihundert gemeint waren.

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

**Auslösung.** Der Lauf wird von der **Systemverwaltung** angestoßen — über die Verwaltungsoberfläche
oder über die Schnittstelle unter `/api/v1/indexing`. Der Anstoß ist der Rolle der Systemverwaltung
vorbehalten und trägt ein eigenes, enges Kontingent gegen Überlastung. Der Fortschritt eines Laufs ist
abrufbar. Wird keine Adresse angegeben, läuft stattdessen die Indizierung des im Dateisystem
konfigurierten Verzeichnisses.

**Was noch fehlt** — und zwar so, dass es benannt gehört:

- **Zielprüfung.** Die angegebene Adresse wird heute nicht gegen private, lokale und nicht routbare
  Adressbereiche geprüft, und die zulässigen Schemata werden nicht ausdrücklich eingegrenzt.
  Weiterleitungen werden gefolgt. Solange nur die Systemverwaltung anstoßen darf, ist die Lage
  beherrschbar; sobald mehrere Personen diese Rolle halten, fällt sie mit der Fähigkeit zusammen, aus
  dem Serverkontext beliebige interne Adressen abzurufen. Die Härtung ist als **Issue #267** erfasst.
- **Zuordnung zu einer Wissensbibliothek.** Der gebaute Weg kennt die Zuordnung noch nicht; alles
  landet im einen vorhandenen Bestand. Die in diesem Dokument beschriebene Regel *eine Quelle, eine
  Bibliothek* ist für ihn Zielbild.
- **Zeitplan.** Der Lauf wird angestoßen, nicht geplant. Die Selbstaktualisierung im Sinne des
  nächsten Kapitels ist damit noch nicht erreicht.

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
2. **Löschen in der Quelle wirkt durch.** Ein in der Quelle entferntes Dokument wird aus dem Index
   genommen. Andernfalls bliebe eine zurückgezogene Weisung antwortfähig — der gefährlichste Fall
   überhaupt.
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

**Jede Konnektorquelle wird genau einer Wissensbibliothek zugeordnet.** Mehrfachzuordnungen werden
abgelehnt. Diese Festlegung ist bereits als **Issue #207** erfasst und hier nur beschrieben, nicht neu
entschieden.

Der Grund ist doppelt:

- **Technisch:** Eine Quelle, die in mehrere Ziele indiziert, vervielfacht jeden Chunk. Derselbe Absatz
  läge mehrfach im Index, würde mehrfach als Treffer erscheinen und müsste bei jeder Änderung an
  mehreren Stellen nachgezogen werden.
- **Fachlich:** Die Mehrfachverwendung eines Bestands ist ein Rechte-, kein Speicherproblem. Wird
  derselbe Bestand an mehreren Stellen gebraucht, wird **dieselbe Bibliothek** in weiteren Spaces
  bereitgestellt oder weiteren Gruppen freigegeben — eine Fassung, eine Pflegestelle.

Umgekehrt ist es zulässig, dass **mehrere Quellen in dieselbe Bibliothek** indizieren: ein
Netzlaufwerkpfad und ein Wiki-Bereich können gemeinsam den Bestand „Personalrecht" bilden. Auch Upload
und Konnektor können in derselben Bibliothek zusammentreffen.

### Zuständigkeit und Obergrenze der Freigabe

Die Trennung ist ausdrücklich:

| Wer | Entscheidet |
|---|---|
| Systemverwaltung | ob ein Konnektor besteht, mit welchem Zugang er liest, welche Quelle in welche Bibliothek indiziert — und die **Obergrenze der Freigabe** dieser Bibliothek |
| Eigentümer der Bibliothek | wer den Bestand lesen darf, bis zu dieser Obergrenze |

Ohne die Obergrenze könnte ein Bibliothekseigentümer einen Bestand organisationsweit öffnen, den die
Systemverwaltung aus einem Fachverfahren eingespeist hat. Sie ist die einzige technische Sicherung
zwischen „aus dem Fachverfahren übernommen" und „hausweit lesbar" und deshalb kein Randthema. Ihre
genaue Definition — was sie begrenzt, was beim nachträglichen Absenken mit bereits erteilten
Freigaben geschieht und wie sie bei gemischt gespeisten Bibliotheken wirkt — ist Gegenstand von
**Issue #207** und wird dort entschieden.

### Wenn die Zielbibliothek fehlt

Wird eine Bibliothek gelöscht, in die eine Quelle indiziert, zeigt deren Zuordnung ins Leere. Der Lauf
**bricht deshalb nicht ab**: Er protokolliert eine Warnung und **überspringt die betroffene Quelle**,
bis die Zuordnung korrigiert ist. Die übrigen Quellen desselben Konnektors laufen normal weiter.

Ein Abbruch des ganzen Laufs wäre der schlechtere Fehler — eine einzelne gelöschte Bibliothek würde die
Aktualisierung aller anderen Bestände stilllegen. Ein stillschweigendes Anlegen einer Ersatzbibliothek
scheidet ebenfalls aus: Wer sie sehen darf, ist eine fachliche Entscheidung und keine, die ein
Indizierungslauf treffen kann. Der Vorgang erscheint mit Frist auf der Arbeitsliste der
Systemverwaltung.

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
wird, ist für ihren Leserkreis sichtbar. Diese Aussage muss der Systemverwaltung bei der Einrichtung
einer Quelle **an Ort und Stelle angezeigt** werden, samt der Frage, ob der Zuschnitt der Quelle dazu
passt. Ein Konnektor, der ohne diese Bestätigung eingerichtet wird, ist die wahrscheinlichste
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

**Löschung** unterscheidet zwei Fälle, die nicht vermischt werden dürfen:

- **Aus der Quelle verschwunden** — OPAA nimmt das Dokument aus dem Index. Das Protokoll behält den
  Vorgang; der Inhalt ist weg.
- **Löschverlangen nach Datenschutzrecht** — greift auf alle Ableitungen durch: Chunks, Einbettungen,
  zwischengespeicherte Extrakte und Vorschauen. Verfahren und Fristen stehen in
  [Zugangskontrolle](./access-control.md#datenlöschung-dsgvo).

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
andere Zerlegungsstrategie, korrigierte Formaterkennung. Sie ist je Bibliothek und je Quelle auslösbar
und läuft mit niedrigerem Vorrang als die laufende Aktualisierung, damit ein großer Nachlauf den Betrieb
nicht anhält.

---

## Zeitpläne, Vorrang und Betrieb

### Auslöser

Ein Lauf beginnt auf vier Wegen: nach **Zeitplan** je Konnektor oder je Quelle, durch eine **Meldung des
Quellsystems**, durch **ausdrücklichen Anstoß** aus der Systemverwaltung oder — beim Upload —
**unmittelbar** mit der Übergabe.

### Vorrang

Ohne Vorrangregel gewinnt der Lauf, der zuerst gestartet ist, und das ist regelmäßig der falsche: Der
Erstlauf über ein großes Netzlaufwerk läuft stundenlang, während die stündliche Aktualisierung der
Dienstanweisungen wartet. Drei Stufen ordnen das:

| Stufe | Beispiel | Verhalten |
|---|---|---|
| **sofort** | Upload durch eine Person, die auf das Ergebnis wartet | wird vorgezogen |
| **regulär** | geplante Aktualisierung eines gepflegten Bestands | Regelbetrieb |
| **nachrangig** | Erstlauf, vollständige Neuindizierung | läuft in den Lücken, wird bei Bedarf ausgesetzt |

Zusätzlich begrenzt ein **Schonzeitraum je Konnektor** die Last auf das Quellsystem. Ein Fachverfahren,
das tagsüber im Wirkbetrieb steht, wird nicht in der Kernzeit vollständig gelesen — die Einführung von
OPAA darf kein Fachverfahren ausbremsen.

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

Einsehbar ist je Quelle: letzter und nächster Lauf, Dauer, Zahl aufgenommener, geänderter, entfernter
und übersprungener Dokumente, Fehlerliste, Zielbibliothek und deren Obergrenze der Freigabe. Gemeldet
wird bei nicht erreichbarer Quelle, auffällig hoher Fehlerquote, ungewöhnlich langem Lauf und knappem
Speicher.

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
- **Eine Konnektorquelle speist genau eine Wissensbibliothek.** Mehrfachzuordnungen werden abgelehnt;
  Mehrfachverwendung geschieht über die Bereitstellung derselben Bibliothek. Erfasst als
  **Issue #207**.
- **Lesen ist der Normalfall, Schreiben die Ausnahme** mit ausdrücklicher Freischaltung je Integration
  und menschlichem Freigabeschritt im Einzelfall.

---

## Offene Fragen / Zukünftige Erweiterungen

- Wie wird die Obergrenze der Freigabe genau definiert, und was geschieht mit bereits erteilten,
  weiter reichenden Freigaben, wenn sie nachträglich abgesenkt wird? Für eine Prüfstelle ist das der
  Unterschied zwischen „behoben" und „nicht behoben". Entschieden wird das in **Issue #207**.
- Trägt eine Bibliothek, die aus einem Konnektor **und** aus Uploads gespeist wird, die Obergrenze für
  ihren gesamten Inhalt oder nur für den konnektorgespeisten Teil?
- Welche Quellsysteme geben Rechte belastbar genug heraus, dass Option 2 der Spiegelung sich lohnt?
- Wie werden Zugangsdaten für Quellsysteme verwahrt und gewechselt, ohne dass ein Lauf ausfällt?
- Soll ein Konnektor Dokumente aus dem Quellsystem **zwischenspeichern** dürfen, damit Belegsprünge auch
  bei nicht erreichbarem Quellsystem funktionieren? Das erhöht den Nutzen und zugleich die
  Datenhaltung.
- Wie wird mit Quellsystemen umgegangen, die dasselbe Dokument mehrfach führen — Kopien im
  Netzlaufwerk, Anhänge in mehreren Vorgängen?
- Braucht es eine Massenübernahme aus einem lokalen Laufwerk, oder ist das genau der Weg, der
  ungepflegte Bestände in den persönlichen Bereich spült?
- Wie werden sehr große Erstläufe abgeschätzt und angekündigt, damit Betrieb und Fachbereich vorher
  wissen, womit sie rechnen?
- Soll die Indizierung aus einer Verzeichnisliste im Netz auf das volle Konnektormodell gehoben werden
  — Zeitplan, Zuordnung zu einer Wissensbibliothek, Einschluss- und Ausschlussmuster —, oder bleibt sie
  ein angestoßener Sonderweg? Die Zielprüfung aus **Issue #267** ist davon unabhängig und in jedem Fall
  nötig.
- Bleiben die Felder für Netzvermittler, Anmeldung und das Aussetzen der Zertifikatsprüfung in ihrer
  heutigen Form, oder gehören diese Angaben in die Konnektorkonfiguration statt in den Anstoß eines
  einzelnen Laufs?

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
