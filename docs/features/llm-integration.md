# Modelle und zentrale Steuerung

> **Status: Entwurf — wesentliche offene Fragen verbleiben.**

**Themenbereich E** der [Produktvision](../VISION.md). **Phasenlage:** Modellverwaltung, Vorrang eigener
Modelle, Vorgaben als Obergrenze, Voreinstellungen je Aufgabe und der Schutz vor Weitergabe
personenbezogener Daten gehören in **Phase 1**. Sie sind die Voraussetzung dafür, dass eine Behörde OPAA
überhaupt in Betrieb nehmen kann — nicht ein späterer Ausbau.

## Motivation

Die Frage, welches Sprachmodell antwortet, ist in der öffentlichen Verwaltung keine
Geschmacksentscheidung. Steuerdaten dürfen das Haus nicht in eine fremde Cloud verlassen; Sozialdaten
und Personalvorgänge ebenso wenig. Zugleich soll eine Sachbearbeiterin nicht vor einer Auswahlliste
sitzen, deren Einträge sie fachlich nicht beurteilen kann.

Die bisherige Fassung dieser Spezifikation ging vom Gegenteil aus: Anbieter seien gleichwertig,
Cloud-Modelle die Regel, die Konfiguration eine Sache von Umgebungsvariablen beim Aufsetzen. Das trägt
nicht. Es fehlen drei Dinge:

1. **Modelle müssen verwaltbar sein**, nicht in der Konfiguration eines Dienstes verdrahtet — mit
   Eigenschaften, Zuständigkeit und Freigabestatus.
2. **Eigene, lokal betriebene Modelle sind der Standard.** Ein Cloud-Modell ist die begründete Ausnahme,
   die eine Behörde ausdrücklich erlaubt.
3. **Eine Beschränkung muss an den Daten hängen**, nicht am Arbeitsraum. Eine Regel, die sich durch einen
   Raumwechsel umgehen lässt, ist keine.

Dieses Dokument beschreibt die Modellverwaltung und die zentrale Steuerung, die daraus folgt — und
zugleich den zweiten Hebel der Verteilbarkeit: Was einmal zentral festgelegt ist, gilt überall, ohne dass
irgendein Team seine Agenten anfassen muss.

**Lesehinweis zum Umsetzungsstand.** Diese Spezifikation beschreibt überwiegend das Zielbild. Wo sie
bereits ausgelieferte Funktionalität beschreibt, ist das ausdrücklich mit **(gebaut)** gekennzeichnet.
Alles ohne diese Kennzeichnung ist noch nicht vorhanden.

---

## Überblick

1. **Modelle sind verwaltete Objekte**, keine Konfigurationszeilen. Sie werden hinterlegt, beschrieben,
   freigegeben, ersetzt und abgeschaltet.
2. **Lokal betriebene Modelle sind die Voreinstellung** — das ist umgesetzt und entschieden. Im Zielbild
   ist ohne ausdrückliche Freigabe der Behörde kein Aufruf außerhalb des Hauses möglich; heute trägt
   diese Zusicherung die Konfiguration, nicht eine technische Durchsetzung (siehe
   [Was heute gilt und was nicht](#was-heute-gilt-und-was-nicht-gebaut)). Der Betrieb ohne
   Netzanbindung ist vorgesehen, nicht behelfsweise.
3. **Vorgaben wirken ausschließlich als Obergrenze.** Es gilt immer die restriktivste Festlegung aus
   Systemvorgabe, Space, den beteiligten Wissensbibliotheken und dem eingesetzten Agenten. **Keine Ebene
   kann erweitern, was eine andere eingeschränkt hat.**
4. **Datenschutzrelevante Beschränkungen hängen an den Daten.** Eine Wissensbibliothek führt ihre Vorgabe
   „nur lokale Modelle" selbst mit sich, unabhängig davon, wer wo fragt.
5. **Je Aufgabe gibt es eine Voreinstellung** — Antwort, Einbettung, Reranking, Zusammenfassung,
   Klassifizierung — samt Parametern. Nutzende bekommen eine sinnvolle Vorgabe statt einer Auswahl.
6. **Mehrere Modelle nebeneinander sind der Normalfall**, weil die Aufgaben verschiedene Eigenschaften
   verlangen.
7. **Personenbezogene Daten werden vor der Weitergabe an ein Modell außerhalb des Hauses geprüft** — und
   im Zweifel wird der Aufruf verweigert statt bereinigt.
8. **Eine zentrale Änderung wirkt sofort überall**, ohne Nacharbeit in Spaces und Agenten.
9. **Die Passagen werden einzeln und mit ihrer Herkunft übergeben**, und die Belege kommen im Text
   zurück, nicht als Liste am Ende. Nur so lässt sich die Belegprüfung überhaupt ansetzen.
10. **Die Ausgabe läuft im Fluss** — außer im Zitierzwang, wo erst nach der Belegprüfung ausgegeben wird.
11. **Die Absicherung gegen Missbrauch liegt nicht im Modell**, sondern in der Rechteprüfung davor, dem
    unveränderlichen Systemvorspann und der Belegprüfung danach.

---

## Modellverwaltung

### Der Modelleintrag

Ein Modell ist ein verwaltetes Objekt mit Eigenschaften, das die Systemverwaltung anlegt und pflegt. Der
Eintrag hält, was für Auswahl, Vorgabe und Nachweis gebraucht wird:

| Angabe | Wozu |
|---|---|
| Bezeichnung und Zweck | wofür das Modell im Haus vorgesehen ist, in Fachsprache |
| Betriebsart | im eigenen Haus betrieben, im eigenen Rechenzentrumsverbund, oder außerhalb |
| Endpunkt und Zugangsdaten | technische Anbindung |
| Aufgabenarten | Antwort, Einbettung, Reranking, Bildverständnis |
| Fähigkeiten | Kontextlänge, Werkzeugaufrufe, Bild- und Handschriftenverständnis, unterstützte Sprachen |
| Freigabestatus | freigegeben, eingeschränkt freigegeben, gesperrt |
| Datenklassen | welche Schutzstufen mit diesem Modell verarbeitet werden dürfen |
| Zuständigkeit | wer im Haus für dieses Modell einsteht |
| Stand und Nachfolge | wann eingeführt, wodurch ersetzt |

Die technische Anbindung erfolgt über eine **OpenAI-kompatible Schnittstelle**. Das ist hier eine
Protokollbezeichnung und keine Aussage über einen Anbieter: Lokal betriebene Modellserver stellen
dieselbe Schnittstelle bereit, weshalb sich Modelle unterschiedlicher Herkunft ohne
Anwendungsänderung anbinden lassen.

Was bewusst **nicht** stattfindet: eine Empfehlung bestimmter Modelle oder Anbieter durch das Produkt.
Welche Modelle geeignet und zulässig sind, entscheidet die Behörde; OPAA liefert dafür die Verwaltung,
die Vorgaben und die Messbarkeit (siehe [Suchqualität](./search-quality-evaluation.md)).

### Eigene Modelle zuerst

Die Grundeinstellung einer Installation ist: **Es sind nur Modelle nutzbar, die im eigenen Haus oder im
eigenen Rechenzentrumsverbund betrieben werden.**

Ein Modell außerhalb wird erst nutzbar, wenn die Behörde es ausdrücklich erlaubt. Diese Erlaubnis ist
ein Verwaltungsvorgang mit Zuständigem, Zeitpunkt und Begründung — kein Häkchen, das beiläufig gesetzt
wird — und sie steht im Protokoll.

Daraus folgen drei Eigenschaften, die zusammengehören:

- **Betrieb ohne Netzanbindung ist der vorgesehene Fall**, nicht der Notbetrieb. Es gibt keine Fähigkeit,
  die zwingend einen Aufruf nach außen verlangt. Fähigkeiten, die nur bestimmte Modelle mitbringen —
  etwa Handschriftenverständnis —, entfallen dann sichtbar, statt heimlich ersetzt zu werden.
- **Kein automatisches Ausweichen nach außen.** Ist das vorgesehene Modell nicht verfügbar, wird nicht
  auf ein Cloud-Modell umgeschaltet. Ein Ausweichweg bleibt immer innerhalb dessen, was die Vorgaben für
  den konkreten Vorgang zulassen.
- **Keine automatische Auswahl des jeweils stärksten Modells.** Ein Verfahren, das je nach Frage das
  beste verfügbare Modell wählt, führt genau an dem Punkt nach außen, an dem es fachlich anspruchsvoll
  wird — und das ist regelmäßig der Punkt mit den schutzbedürftigsten Daten.

### Was heute gilt und was nicht **(gebaut)**

Das Zielbild oben beschreibt verwaltete Modelle mit Freigabestatus. Davon ist heute die
Voreinstellung umgesetzt, und zwar in der Konfiguration:

**Lokal betriebene Modelle sind die Voreinstellung, für Chat und für Einbettung.** Eine Installation,
an der niemand etwas konfiguriert, ruft kein Modell außerhalb des Hauses auf. Das ist entschieden und
bleibt so; es ist keine Zwischenlösung.

**Eine technische Durchsetzung gibt es nicht.** Es existiert kein Mechanismus, der einen Modellaufruf
an ein Ziel außerhalb festgelegter Netzbereiche verweigert. Wer die Voreinstellung ändert, kann jedes
erreichbare Ziel eintragen, und OPAA hält ihn nicht auf. Das ist bewusst so entschieden: Die
Voreinstellung ist bereits lokal, und wer sie ändert, tut es absichtlich.

Daraus folgt eine Aussage, die nicht beschönigt gehört: **Die Zusicherung, dass keine Daten an ein
Modell außerhalb des Hauses gehen, ruht heute auf der Konfiguration und nicht auf einer technischen
Durchsetzung.** Wer sie gegenüber Prüfern nachweisen muss, weist die Konfiguration nach — und sichert
den Netzweg außerhalb von OPAA ab, etwa über die Firewall-Regeln der Umgebung, in der das Backend
läuft.

Das ist eine Festlegung für den heutigen Stand, kein Verzicht auf Dauer. Die
[zentralen Vorgaben als Obergrenze](#vorgaben-als-obergrenze) bleiben Teil von Phase 1; sie sind der
Ort, an dem eine Durchsetzung später sinnvoll einhängt, weil dort ohnehin entschieden wird, welche
Modelle für einen Vorgang zulässig sind.

### Anbietername und Zieladresse sind zwei verschiedene Dinge **(gebaut)**

Die Anbieterangabe (`ollama` oder `openai`) benennt das Protokoll, über das OPAA das Modell anspricht
— nicht das Ziel, an das die Daten gehen. Dieselbe openai-kompatible Schnittstelle bedienen auch
lokal betriebene Modellserver.

Deshalb hat die Basis-Adresse **keine Voreinstellung**. Wer für Chat oder Einbettung den
openai-kompatiblen Anbieter wählt, muss die Adresse angeben; fehlt sie, bricht der Start mit einer
Meldung ab, die die fehlende Variable benennt.

Der Grund liegt im Fehlerfall: Wer im Haus einen Modellserver mit openai-kompatibler Schnittstelle
einbindet und dabei nur den Anbieter setzt, erbte mit einer Voreinstellung stillschweigend ein Ziel
außerhalb des Hauses — die Installation liefe, die Daten gingen an die falsche Stelle, und niemand
würde es an einer Fehlermeldung bemerken. Ein lautes Scheitern beim Start ist dem vorzuziehen.

Die Ableitung je Funktion bleibt erhalten: Eine Adresse für Chat und eine für Einbettung sind
getrennt setzbar; ohne sie gilt die gemeinsame Adresse für beide. Die Betriebssicht dazu steht in
[deployment.md](../deployment.md#llm-anbieter).

---

## Vorgaben als Obergrenze

### Die Verrechnungsregel

Vier Ebenen können festlegen, welche Modelle für einen Vorgang zulässig sind. Sie werden **geschnitten**,
nie vereinigt:

```
erlaubte Modelle = Systemvorgabe
                 ∩ Vorgabe des Space
                 ∩ Vorgabe jeder Wissensbibliothek im Suchbereich
                 ∩ Vorgabe des eingesetzten Agenten
```

Dieselbe Regel gilt für die übrigen Vorgaben, die an Modelle gebunden sind — zulässige Werkzeuge,
Weitergabe nach außen, Zitierzwang. Es gibt genau eine Richtung: **Jede Ebene kann verschärfen, keine
kann lockern.**

Der Vorteil ist, dass sich die Frage „warum wurde hier dieses Modell verwendet?" immer beantworten lässt,
und zwar ohne Kenntnis der Reihenfolge, in der die Einstellungen entstanden sind. Der Preis ist, dass
eine einzelne strenge Bibliothek einen ganzen Vorgang einschränken kann — und genau das ist beabsichtigt.

Eine **Erklärung ist Teil der Antwort**: Nutzende können einsehen, welches Modell verwendet wurde und
welche Ebene die Auswahl begrenzt hat. Ohne das entsteht der Eindruck von Willkür.

### Wenn die Schnittmenge leer ist

Der Fall tritt auf: Eine Bibliothek verlangt lokale Modelle, der Agent ist auf ein Modell festgelegt, das
außerhalb läuft. Dann gibt es kein zulässiges Modell.

OPAA **verweigert den Vorgang und benennt den Grund**. Es wird nicht auf ein anderes Modell
zurückgefallen, und es wird nicht stillschweigend die strengere Bibliothek aus dem Suchbereich genommen,
um den Vorgang doch noch zu ermöglichen — das wäre die gefährlichere Auflösung, weil sie eine
Schutzvorgabe durch eine schlechtere Antwort ersetzt, ohne dass es jemand merkt.

Die Meldung nennt die Ebene, an der es scheitert, und die zuständige Stelle. Sie nennt **keine
Anzahlen** von Beständen, auf die die fragende Person keinen Zugriff hat.

### Beschränkungen hängen an den Daten

> **Datenschutzrelevante Modellbeschränkungen gehören an die Daten, nicht an den Raum.**

Eine Wissensbibliothek mit Steuerdaten führt ihre Vorgabe „nur lokale Modelle" selbst mit sich. Sie gilt
überall, wo diese Daten verwendet werden — in jedem Space, mit jedem Agenten, für jede Person.

Der Grund liegt im Rechtemodell: Der Space hat keine Hoheit darüber, welche Bibliotheken in ihm auftauchen.
Wer in einem Space kuratieren darf, kann jede Bibliothek bereitstellen, auf die er selbst Zugriff hat.
Eine Bibliothek mit besonders geschützten Daten kann damit in einem Space landen, dessen Vorgabe
Cloud-Modelle erlaubt. **Eine ausschließlich raumgebundene Vorgabe schützt genau diesen Fall nicht** —
und es ist der Fall, der zählt.

Die Vorgabe des Space bleibt sinnvoll: Ein Raum kann strenger sein als das Haus, etwa in der Revision.
Aber er ist nicht die Sicherung. Dieselbe Festlegung steht aus der Sicht des Rechtemodells in
[Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md#modell-policies).

**Praktische Folge:** Die Modellvorgabe ist eine Eigenschaft der Bibliothek und wird beim Anlegen
gesetzt — von der Stelle, die den Bestand verantwortet. Bei konnektorgespeisten Bibliotheken setzt sie
die Systemverwaltung mit der Zuordnung (siehe
[Wissensquellen und Konnektoren](./knowledge-sources.md#eine-quelle-eine-wissensbibliothek)); der
Eigentümer kann sie verschärfen, nicht lockern.

### Sofortige Wirkung

Eine zentrale Änderung wirkt **beim nächsten Vorgang**, überall. Wird ein Modell abgeschaltet — weil es
abgekündigt wurde, weil ein Nachfolger bereitsteht oder weil eine Prüfung es untersagt —, greift das
sofort, ohne dass ein Team seine Agenten anfassen muss.

Damit das nicht zum Betriebsrisiko wird, gehören drei Dinge dazu:

- **Vorher sichtbare Auswirkung.** Vor dem Abschalten zeigt die Systemverwaltung, wie viele Agenten,
  Bibliotheken und Spaces betroffen sind und was an ihre Stelle tritt.
- **Benannte Nachfolge.** Ein abgeschaltetes Modell trägt einen Nachfolger; Vorgänge laufen auf diesem
  weiter, soweit die Vorgaben es zulassen. Wo kein zulässiger Nachfolger existiert, wird der Vorgang
  verweigert — und die betroffene Stelle wird benachrichtigt, statt es an schlechteren Antworten zu
  merken.
- **Nachvollziehbarkeit alter Vorgänge.** Zu jeder erzeugten Antwort ist festgehalten, mit welchem Modell
  und welchen Parametern sie entstanden ist. Ein Modellwechsel ändert nichts rückwirkend an dieser
  Angabe.

Ein Wechsel des **Einbettungsmodells** ist die eine Ausnahme von der sofortigen Wirkung: Er erfordert
eine vollständige Neuindizierung, weil bestehende Vektoren nicht vergleichbar bleiben. Er wird deshalb
als geplanter Vorgang behandelt und ist in
[Wissensschicht und Retrieval](./data-indexing-rag.md#speicherung-und-filterachse) sowie in der
Qualitätsmessung verankert.

---

## Voreinstellungen und Parameter je Aufgabe

Nutzende sollen keine Modellauswahl treffen müssen. Sie sollen eine Aufgabe haben und ein Ergebnis
bekommen. Die Zuordnung von Aufgabe zu Modell und Parametern trifft die Systemverwaltung einmal.

| Aufgabe | Was zählt | Typische Vorgabe |
|---|---|---|
| **Antwort im Chat** | Belegtreue, Sprachqualität, Kontextlänge | wenig Streuung in der Erzeugung, ausreichende Kontextlänge für die übergebenen Passagen |
| **Einbettung** | Trefferqualität, Stabilität über die Zeit | ein Modell, das selten gewechselt wird — jeder Wechsel kostet eine Neuindizierung |
| **Reranking** | Genauigkeit bei kurzen Texten, Geschwindigkeit | ein spezialisiertes, kleineres Modell |
| **Zusammenfassung** | Treue zum Ausgangstext | geringe Streuung, längenbegrenzt |
| **Klassifizierung und Erkennung** | Verlässlichkeit, Geschwindigkeit | kleines Modell, feste Ausgabestruktur |
| **Bildverständnis** | Fähigkeit des Modells | nur, wenn ein Modell mit dieser Fähigkeit freigegeben ist |

Zu jeder Aufgabe gehören **Parameter** — Streuung der Erzeugung, Längenbegrenzung der Antwort,
Kontextgrenze — und ein **Systemvorspann**, der Verhalten und Ton festlegt. Beides ist Teil der zentralen
Vorgabe, nicht der Entscheidung im Einzelfall.

Ein Agent kann davon abweichen, aber nur innerhalb der Obergrenze. Wo eine Aufgabe besondere Parameter
braucht, ist das Teil seiner Aufgabenbeschreibung und damit prüfbar und versionierbar.

Für den Systemvorspann gilt eine harte Regel: **Er ist nicht über den Chat änderbar.** Anweisungen aus
Nutzereingaben oder aus dem Inhalt abgerufener Dokumente ersetzen ihn nicht. Das ist die Grundlage
dafür, dass ein geprüfter Agent auch nach der Prüfung noch das tut, wofür er geprüft wurde.

### Mehrere Modelle nebeneinander

Es ist der Normalfall, dass eine Installation mehrere Modelle betreibt — und zwar nicht, um die stärkste
Antwort zu finden, sondern weil die Aufgaben unterschiedliche Eigenschaften verlangen. Ein Modell für
Einbettungen muss stabil sein, eines für das Reranking schnell, eines für die Antwort sprachfähig.

Die Aufteilung hat zwei Nebenwirkungen, die bewusst in Kauf genommen werden:

- **Mehr Betriebsaufwand.** Mehrere Modelle brauchen Rechenleistung, Überwachung und Pflege. Deshalb ist
  die Zahl der Aufgabenarten begrenzt und jede Zuordnung begründet.
- **Getrennte Beurteilung der Qualität.** Ein Wechsel am Einbettungsmodell wirkt anders als einer am
  Antwortmodell; beide werden getrennt gegen dieselben Referenzfälle gemessen.

Was ausdrücklich nicht vorgesehen ist: eine Verteilung von Anfragen auf Modelle nach geschätzter
Schwierigkeit. Sie macht das Ergebnis unvorhersehbar, ist nicht reproduzierbar und würde die
Modellvorgaben faktisch aushöhlen.

---

## Schutz vor Weitergabe personenbezogener Daten

Wo ein Modell außerhalb des Hauses erlaubt ist, entsteht die Frage, was ihm übergeben wird. Frage,
abgerufene Passagen und Verlauf enthalten in der Verwaltung regelmäßig Namen, Aktenzeichen,
Steuernummern, Anschriften und Gesundheitsangaben.

Drei Umgangsweisen kommen in Betracht.

**Option 1 — Verweigern.** Wird in einem Aufruf an ein Modell außerhalb des Hauses ein personenbezogenes
Merkmal erkannt, wird der Aufruf nicht ausgeführt. Die Person erhält den Hinweis, dass die Anfrage nur
mit einem lokalen Modell möglich ist. Wirksam und einfach zu erklären, aber im Alltag hinderlich, wenn
die Erkennung übervorsichtig ist.

**Option 2 — Ersetzen und zurückübersetzen.** Erkannte Merkmale werden vor dem Aufruf durch Platzhalter
ersetzt und in der Antwort wieder eingesetzt. Erhält die Arbeitsfähigkeit, verlagert aber das Risiko auf
die Erkennungsgüte: Was nicht erkannt wird, geht hinaus. Und in der Verwaltung ist der Personenbezug oft
nicht an einem Merkmal festzumachen, sondern ergibt sich aus dem Zusammenhang, den ein Erkennungsverfahren
nicht sieht.

**Option 3 — Nur lokale Modelle für alles.** Vollständig sicher und für viele Häuser die richtige
Entscheidung. Als Produktvorgabe zu grob, weil sie auch dort greift, wo eine Behörde bewusst anders
entschieden hat.

**Empfehlung:** Option 1 als Verhalten in der Voreinstellung, kombiniert mit der eigentlichen Sicherung —
den Beschränkungen an den Daten. Ein Bestand mit Personenbezug trägt „nur lokale Modelle" ohnehin selbst,
sodass der Prüfschritt an der Grenze nach außen nur noch die Reste auffängt: Freitext in der Frage,
eingefügte Ausschnitte, Anhänge.

Option 2 wird ausdrücklich **nicht** verworfen, aber nur als bewusst zuschaltbare Erleichterung mit klar
benannter Restunsicherheit — und nie als Ersatz für die Beschränkung am Bestand. Ein Verfahren, das
Vertraulichkeit auf eine Mustererkennung stützt, ist keine Zusicherung, sondern eine Wahrscheinlichkeit.

Unabhängig von der gewählten Option gilt:

- **Jeder Aufruf nach außen ist protokolliert** — Modell, Zeitpunkt, Anlass, Umfang. Ohne Inhalte, aber
  nachweisbar.
- **Nutzende sehen vorher, dass ein Vorgang das Haus verlässt.** Diese Anzeige ist nicht abschaltbar.
- **Kein Training mit Hausdaten.** Übergebene Daten dürfen beim Betreiber eines externen Modells nicht in
  ein Training einfließen; wo das nicht zugesichert ist, kommt das Modell nicht in Frage. Sicherstellen
  kann das nur der Betreibervertrag — OPAA kann es lediglich als Eigenschaft am Modelleintrag führen und
  sichtbar machen.

---

## Antwortgenerierung

Die Erzeugung der Antwort ist der Punkt, an dem Modellsteuerung und Belegbarkeit zusammentreffen.

```
Frage
  ↓
Suchbereich und Rechteprüfung          → spaces-and-assets.md
  ↓
Hybride Suche, Reranking, Auswahl      → data-indexing-rag.md
  ↓
Bestimmung des zulässigen Modells      ← Schnitt aller Vorgaben
  ↓
Zusammenstellung: Systemvorspann + Passagen mit Fundstellen + Frage
  ↓
Aufruf; Ausgabe im Fluss, außer im Zitierzwang
  ↓
Belegprüfung: trägt jede Aussage eine Fundstelle?
  ↓
Ausgabe mit Fundstellen und Konfidenz — oder Verweigerung im Zitierzwang
```

Wesentlich ist die Reihenfolge: **Die Bestimmung des Modells folgt der Bestimmung des Suchbereichs.**
Erst wenn feststeht, aus welchen Beständen geantwortet wird, steht fest, welche Modellvorgaben gelten.
Eine Installation, die das Modell vorher festlegt, kann die datengebundene Beschränkung nicht einhalten.

Die **Belegprüfung nach der Erzeugung** ist in
[Wissensschicht und Retrieval](./data-indexing-rag.md#zitierzwang) beschrieben. Für dieses Dokument ist
nur festzuhalten: Sie ist kein Bestandteil des Systemvorspanns und verlässt sich nicht darauf, dass das
Modell die Anweisung befolgt.

**Bei Ausfall des Modells** wird nicht auf ein unzulässiges ausgewichen. Steht kein zulässiges Modell
bereit, gibt OPAA die gefundenen Fundstellen ohne erzeugten Text aus und sagt, dass keine Antwort
formuliert werden konnte. Das ist ein brauchbares Zwischenergebnis — die Recherche ist getan, nur die
Formulierung fehlt.

### Übergabe der Passagen und Form der Antwort

Wie die abgerufenen Passagen an das Modell übergeben werden, ist keine Feinheit der Umsetzung, sondern
die **Nahtstelle zwischen Retrieval und Belegprüfung**: Woran die Prüfung ansetzt, entsteht genau hier.
Ohne eine beschriebene Übergabe ist der Zitierzwang nicht beschreibbar.

Die Zuständigkeit ist deshalb so geschnitten: **[Wissensschicht und Retrieval](./data-indexing-rag.md)
bestimmt, welche Passagen übergeben werden** — Suche, Zusammenführung, Reranking, Schwelle. **Dieses
Dokument bestimmt, wie sie übergeben werden**, weil das eine Eigenschaft des Modellaufrufs ist und mit
Systemvorspann, Parametern und Kontextgrenze zusammen festgelegt wird.

Der Aufruf wird aus vier Teilen zusammengesetzt:

1. **Systemvorspann** — Rolle, Ton, Umgang mit Nichtwissen und die verbindlichen Belegregeln. Nicht über
   den Chat änderbar (siehe [Absicherung des Modells](#absicherung-des-modells-gegen-missbrauch)).
2. **Die Passagen, jede mit einem eigenen Kopf.** Der Kopf trägt die Angaben, die den späteren Beleg
   tragen: Dokument, Stelle im Dokument, Bezeichnung — und die **Zeichenfolge, mit der genau diese
   Passage zu zitieren ist**. Die Passagen sind voneinander sichtbar getrennt, damit das Modell sie
   nicht zu einem Fließtext verschmilzt. **(gebaut)**
3. **Der bisherige Gesprächsverlauf**, soweit er in die Kontextgrenze passt. **(gebaut)**
4. **Die Frage.**

Die Antwort kommt **mit Belegen im Text zurück, nicht mit einer Liste am Ende**: Jede tragende Aussage
trägt die Zeichenfolge der Passage, auf die sie sich stützt, unmittelbar bei sich. OPAA löst diese
Zeichenfolgen anschließend gegen die tatsächlich übergebenen Passagen auf und macht Sprungmarken
daraus; was sich nicht auflösen lässt, gilt als nicht belegt. **(gebaut, in der Grundform)**

Diese Form ist bewusst gewählt und trägt die Belegbarkeit:

- **Eine Quellenliste am Ende ließe sich nicht prüfen.** Sie sagt nicht, welcher Satz woher stammt —
  genau die Zuordnung, die die Belegprüfung braucht.
- **Erfundene Belege fallen auf.** Eine Zeichenfolge, die keiner übergebenen Passage entspricht, wird
  beim Auflösen zu einer Fehlstelle statt zu einem Verweis.
- **Der Beleg bleibt an der Aussage**, auch wenn Nutzende die Antwort kürzen, zitieren oder in einen
  Vermerk übernehmen.

Für die Zuschneidung auf die Kontextgrenze gilt: Passt die Menge der ausgewählten Passagen nicht, wird
**von unten gekürzt** — die schwächsten Treffer entfallen zuerst — und die Kürzung wird ausgewiesen. Eine
stillschweigend gekürzte Grundlage wäre die schlechteste Form der Verkürzung, weil die Antwort
vollständig aussieht.

### Ausgabe im Fluss

Eine Antwort, die erst nach mehreren Sekunden am Stück erscheint, wird als langsam erlebt, auch wenn sie
es nicht ist. OPAA gibt sie deshalb **im Fluss** aus: Der Text erscheint, während er entsteht, und der
Vorgang lässt sich abbrechen, sobald erkennbar ist, dass die Antwort in die falsche Richtung läuft. Für
die empfundene Antwortzeit ist das der wirksamste Einzelfaktor.

Das steht in einer Spannung zum Zitierzwang, die benannt gehört: **Die Belegprüfung setzt an der
fertigen Antwort an.** Wer im Fluss ausgibt, hat schon ausgegeben, wenn die Prüfung urteilt. Drei
Auflösungen stehen zur Wahl.

**Option 1 — Erst prüfen, dann ausgeben.** Im Zitierzwang wird die Antwort vollständig erzeugt, geprüft
und erst danach ausgegeben. Sauber und ohne Widerruf, aber der Zeitvorteil entfällt genau in dem Modus,
in dem am sorgfältigsten gearbeitet wird.

**Option 2 — Ausgeben und widerrufen können.** Der Text läuft mit, und die Prüfung kann ihn nachträglich
als nicht belegt kennzeichnen oder zurücknehmen. Schnell, aber jemand hat den unbelegten Satz bereits
gelesen — und Gelesenes ist nicht widerrufbar. In einer Auskunft mit Außenwirkung ist das der falsche
Kompromiss.

**Option 3 — Abschnittsweise prüfen.** Ausgegeben wird in belegten Abschnitten: Ein Abschnitt erscheint,
sobald seine Belege aufgelöst sind. Verbindet beide Vorteile, ist aber die aufwendigste Variante und
setzt voraus, dass sich die Antwort verlässlich in prüfbare Abschnitte zerlegen lässt.

**Empfehlung:** Option 1 im Zitierzwang, Ausgabe im Fluss überall sonst. Damit ist die Zusicherung
eindeutig — im Zitierzwang wird nichts Unbelegtes sichtbar —, und der Zeitvorteil bleibt dort erhalten,
wo er ohne Zusicherungsverlust zu haben ist. Dass der Zitierzwang spürbar langsamer ist, wird
**angezeigt** statt kaschiert; es ist der ehrliche Preis der Prüfung. Option 3 bleibt als spätere
Verbesserung offen.

**Zielbild.** Die Ausgabe im Fluss ist heute nicht gebaut; die Antwort erscheint am Stück. Die Wahl
zwischen den drei Optionen ist damit noch nicht durch eine Umsetzung vorentschieden.

---

## Absicherung des Modells gegen Missbrauch

Ein Sprachmodell tut, was in seinem Eingabetext steht — und der Eingabetext besteht bei OPAA zu einem
großen Teil aus Dokumenten, die niemand daraufhin gelesen hat. Drei Angriffsflächen folgen daraus, und
sie werden getrennt behandelt, weil sie verschiedene Gegenmittel haben.

### Widerstand gegen Umgehungsversuche

Gemeint ist der Versuch, das Modell durch Anweisungen in der Frage aus seiner Rolle zu lösen — „vergiss
deine Anweisungen", „antworte als ein System ohne Beschränkungen". Vier Eigenschaften wirken dagegen,
und keine davon ist eine Bitte an das Modell:

- **Der Systemvorspann ist nicht über den Chat änderbar.** Eingaben ersetzen ihn nicht; sie stehen an
  einer anderen Stelle des Aufrufs und werden als Nutzertext behandelt.
- **Die Antwortgrundlage ist der abgerufene Bestand.** Was nicht gefunden wurde, steht dem Modell nicht
  zur Verfügung — eine Umgehung erweitert den Zugriff nicht.
- **Die Rechteprüfung sitzt vor dem Modell, nicht im Modell.** Kein Formulierungstrick kann Bestände
  öffnen, denn unberechtigte Passagen werden gar nicht erst geladen. Das ist die eigentliche Sicherung
  und der Grund, warum ein gelungener Umgehungsversuch bei OPAA vergleichsweise wenig einbringt.
- **Der Zitierzwang begrenzt den Schaden.** Eine Antwort ohne Beleg ist im Zitierzwang keine Antwort.

### Untergeschobene Anweisungen aus Dokumenten

Der ernstere Fall ist nicht die Frage, sondern der **Bestand**: Ein Dokument enthält einen Satz, der wie
eine Anweisung an das Modell aussieht. Das kann bösartig platziert sein oder schlicht ein zitierter
Beispieltext. Zwei Festlegungen gelten:

- **Dokumentinhalt ist Material, keine Anweisung.** Die Übergabe kennzeichnet jede Passage als Fundstelle
  mit Herkunft (siehe [Übergabe der Passagen](#übergabe-der-passagen-und-form-der-antwort)); Anweisungen
  aus diesem Bereich werden nicht befolgt.
- **Das Restrisiko bleibt und wird nicht wegdefiniert.** Kein Sprachmodell trennt Anweisung und Material
  zuverlässig. Deshalb greifen dahinter die Sicherungen, die nicht am Modell hängen: Rechteprüfung vor
  der Suche, Belegprüfung nach der Erzeugung, Protokoll.

Die Seite, die für **Agenten mit Werkzeugen** hinzukommt — eine untergeschobene Anweisung, die eine
Handlung im Quellsystem auslöst —, ist ungleich folgenreicher und wird im Prüfstand für Agenten
(Themenbereich D) behandelt, nicht hier. Für diesen Zusammenhang gilt der Grundsatz aus
[Wissensquellen und Konnektoren](./knowledge-sources.md#lesender-und-schreibender-zugriff): Lesen ist
folgenlos, Schreiben verlangt eine menschliche Freigabe.

### Erfundene Aussagen

Die frühere Fassung führte dies als eigenes Thema. Es ist inhaltlich in der **Belegbarkeit**
aufgegangen: Antworten sind an Fundstellen gebunden, die Belegdeckung wird ausgewiesen, und im
Zitierzwang gibt es ohne Beleg keine Antwort. Beschrieben ist das in
[Wissensschicht und Retrieval](./data-indexing-rag.md#belegbarkeit). Hier steht es nur noch als Verweis,
damit die Verlagerung nicht als Wegfall gelesen wird.

### Filterung von Inhalten

Manche Häuser verlangen zusätzliche Prüfschritte auf dem Weg zur Ausgabe: Unterdrückung anstößiger
Formulierungen, Schwärzung personenbezogener Merkmale in der Ausgabe, Maskierung besonders
schutzbedürftiger Angaben. Vorgesehen ist das als **zuschaltbarer Nachbearbeitungsschritt**, ausgeschaltet
in der Voreinstellung, mit drei Einschränkungen:

- **Ein Filter ersetzt keine Zugriffsbeschränkung.** Was jemand nicht sehen darf, gehört nicht in die
  Antwort, weil es nicht in den Suchbereich gehört — nicht, weil ein Filter es hinterher entfernt.
- **Eine Schwärzung im Beleg macht den Beleg unbrauchbar.** Wird gefiltert, muss erkennbar bleiben, dass
  gefiltert wurde, und der Sprung ins Original bleibt rechtegeprüft möglich.
- **Filter arbeiten auf Mustern und irren.** Sie sind eine Erleichterung, keine Zusicherung — dieselbe
  Einordnung wie beim Ersetzen personenbezogener Merkmale vor einem Aufruf nach außen.

---

## Grenzen und Kontingente

Grenzen je Person, je Gruppe und für das Haus insgesamt sind vorgesehen. Sie schützen den Betrieb — bei
lokalen Modellen ist die knappe Größe die Rechenleistung, nicht ein Budget.

Zwei Festlegungen gehören dazu:

- **Eine überschrittene Grenze ist eine Auskunft, kein Fehler.** Die betroffene Person erfährt, was gilt
  und wann die Grenze wieder greift.
- **Grenzen sind kein Auswertungspfad.** Die Verbrauchsmessung dient der Steuerung von Ressourcen, nicht
  der Beobachtung von Personen. Auswertungen sind aggregiert und ohne Ranglisten; die Festlegungen dazu
  stehen in
  [Mitbestimmung und Personalvertretung](./spaces-and-assets.md#mitbestimmung-und-personalvertretung).

Die weitergehende Betrachtung von Verbrauch, Auslastung und Steuerung gehört zu Themenbereich H und wird
dort beschrieben.

---

## Integrationspunkte

- **[Wissensschicht und Retrieval](./data-indexing-rag.md)** — Einbettungs-, Rerank- und Antwortmodell;
  Zitierzwang und Belegprüfung; die Fähigkeitsabhängigkeit des Bildverständnisses. Dort wird bestimmt,
  **welche** Passagen übergeben werden; hier, **wie**.
- **[Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md)** — die Vorgaben von Space, Bibliothek
  und Agent, ihre Verrechnung als Obergrenze und die Zuständigkeit für ihre Festlegung.
- **[Wissensquellen und Konnektoren](./knowledge-sources.md)** — die Modellvorgabe einer
  konnektorgespeisten Bibliothek wird mit der Quellzuordnung gesetzt.
- **[Zugangskontrolle](./access-control.md)** — Protokollierung von Modellaufrufen, Freigaben für
  Modelle außerhalb des Hauses, Verwahrung der Zugangsdaten.
- **[Deployment und Infrastruktur](./deployment-infrastructure.md)** — Betrieb lokaler Modelle,
  Rechenleistung, Netzwege und der Betrieb ohne Netzanbindung.
- **[Suchqualität messbar machen](./search-quality-evaluation.md)** — jeder Modellwechsel ist ein
  Eingriff mit Regressionsrisiko und wird gegen dieselben Referenzfälle gemessen.
- **[ADR-0002](../decisions/0002-mvp-technology-stack.md)** — die gewählte Technologiebasis und die
  Abstraktion, über die Modelle angebunden werden.

---

## Offene Fragen / Zukünftige Erweiterungen

- Wie werden **Schutzstufen von Daten** benannt und gepflegt, damit die Zuordnung „welches Modell darf
  diese Klasse verarbeiten" mehr ist als ein Freitextfeld? Ohne ein knappes, verbindliches Schema wird
  die Zuordnung uneinheitlich gesetzt.
- Darf ein Agent eine Modellvorgabe **verschärfen**, oder ist das allein Sache von Systemverwaltung und
  Bibliothek? Verschärfen ist folgerichtig, kann aber dazu führen, dass ein geteilter Agent beim
  Empfänger nicht läuft.
- Wie wird ein **Modellwechsel geprüft**, bevor er hausweit gilt — Vergleichsläufe gegen Referenzfälle,
  Freigabe für einen begrenzten Kreis, oder beides?
- Soll eine Installation **eigens angepasste Modelle** aufnehmen können, und wie werden sie im
  Modelleintrag von einem Standardmodell unterschieden?
- Wie wird die **Erklärung der Modellauswahl** dargestellt, ohne Bestände preiszugeben, auf die die
  fragende Person keinen Zugriff hat?
- Wie werden **Fähigkeitsunterschiede** behandelt, wenn ein Agent eine Fähigkeit voraussetzt, die das
  zulässige Modell nicht hat — Verweigerung, eingeschränkter Lauf mit Hinweis, oder Auswahl nach
  Fähigkeit innerhalb der Obergrenze?
- Wie belastbar lässt sich eine **Zusicherung „kein Training mit unseren Daten"** technisch abbilden,
  oder bleibt sie eine reine Vertrags- und Dokumentationsangabe?
- Ab welcher Größe lohnt eine **getrennte Betriebsumgebung für Modelle** gegenüber dem gemeinsamen
  Betrieb mit der Anwendung?
- Bleibt es im Zitierzwang bei der **vollständigen Prüfung vor der Ausgabe**, oder lohnt die
  abschnittsweise Prüfung während der Ausgabe? Letztere hält den Zeitvorteil, verlangt aber eine
  verlässliche Zerlegung der Antwort in prüfbare Abschnitte.
- Soll die **Filterung von Inhalten** im Zielbild bleiben? Sie war im früheren Bestand als Erweiterung
  geführt und ist hier unverändert übernommen. Dagegen spricht, dass sie eine Sicherheit suggeriert, die
  eine Mustererkennung nicht leisten kann, und dass sie mit der Belegbarkeit in Konflikt gerät.
- Wie wird eine **Kürzung an der Kontextgrenze** dargestellt, ohne die Antwort mit technischen Hinweisen
  zu überfrachten — und ab welchem Anteil entfallener Passagen ist die Antwort besser zu verweigern?
- Wird der Anteil **untergeschobener Anweisungen aus Dokumenten** überhaupt messbar, oder bleibt es bei
  der Feststellung eines nicht bezifferbaren Restrisikos?

---

## Erfolgs-Metriken

- **Anteil der Vorgänge auf lokal betriebenen Modellen** — das unmittelbare Maß für die Souveränität der
  Installation.
- **Zahl der Vorgänge, die wegen leerer Schnittmenge verweigert wurden**, aufgeschlüsselt nach der
  auslösenden Ebene. Dauerhaft hohe Werte deuten auf widersprüchliche Vorgaben hin, nicht auf ein
  Nutzerproblem.
- **Zeit bis zur Wirksamkeit einer zentralen Änderung** und Zahl der dafür nötigen Eingriffe in Spaces
  und Agenten — die Zielgröße ist null.
- **Anteil der Antworten mit nachvollziehbarer Modellangabe** im Protokoll.
- **Verfügbarkeit der lokal betriebenen Modelle** und Anteil der Vorgänge ohne verfügbares Modell.
- **Zahl der Aufrufe an Modelle außerhalb des Hauses** und deren Anlass, als Nachweis gegenüber Prüfung
  und Personalvertretung.
