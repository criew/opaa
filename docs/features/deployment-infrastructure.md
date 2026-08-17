# Betrieb & Deployment

> **Status: Entwurf.** Themenbereich J der Produktvision. Phasenlage: Betrieb mit Docker Compose,
> Kubernetes mit Hochverfügbarkeit und der Betrieb ohne Netzanbindung gehören in **Phase 1**; die
> Andockung an die Bausteine des souveränen Arbeitsplatzes in **Phase 4**. Der Umfang der
> Speicher-Abstraktion für Dokumente ist entschieden (siehe [Speicher-Backends](#speicher-backends)).
>
> Diese Spezifikation beschreibt das **Zielbild**. Der tatsächlich verfügbare Betriebsweg ist in
> [deployment.md](../deployment.md) beschrieben; wo beide auseinandergehen, gilt für den Betrieb
> heute jenes Dokument.

## Motivation

Souveränität ist keine Eigenschaft der Anwendung, sondern des Betriebs. Ein System, das jede Antwort
belegt und jeden Zugriff protokolliert, hilft einer Behörde nicht, wenn die Frage selbst das Haus
verlässt. Der Betrieb entscheidet deshalb über das Kernversprechen — nicht das Modell und nicht die
Oberfläche.

Zugleich sind die Betreiber sehr unterschiedlich. Ein Amt mit einer zweistelligen Zahl von
Beschäftigten und einer Person für die IT braucht etwas anderes als ein Rechenzentrum, das viele
Häuser gleichzeitig versorgt. Und ein spürbarer Teil der Zielumgebungen hat aus guten Gründen keinen
Weg ins Internet.

Dieses Dokument beschreibt die Betriebsformen, ihre Voraussetzungen und ihre Grenzen.

---

## Überblick

1. **Betrieb im eigenen Verantwortungsbereich ist der Standard.** Alles Weitere ist eine Abweichung,
   die begründet werden muss — nicht umgekehrt. Maßgeblich ist dabei die Verantwortlichkeit, nicht
   die Entfernung zum eigenen Serverraum.
2. **Zwei Größenordnungen, eine Codebasis.** Docker Compose für kleine Installationen, Kubernetes mit
   Hochverfügbarkeit für große. Der Unterschied liegt in der Betriebsform, nicht im Produkt.
3. **Betrieb ohne Netzanbindung ist ein vorgesehenes Szenario**, keine Ausnahme und kein Sonderfall.
   Er bestimmt Entwurfsentscheidungen mit, statt nachträglich ermöglicht zu werden.
4. **Der Dateispeicher ist austauschbar — durch Einhängen, nicht durch eine Abstraktion.** OPAA
   schreibt und liest gegen genau ein konfiguriertes Verzeichnis; ein Netzlaufwerk hängt der Betrieb
   dort ein. Objektbasierter Speicher ist als eigener Weg vorgesehen, aber **nicht gebaut**. Der
   **Vektorspeicher** ist davon ausgenommen — er ist auf pgvector festgelegt.
5. **Mandantenfähiger Betrieb ist der Hebel für die Fläche.** Ein Rechenzentrum betreibt eine
   Installation für viele Häuser, die einzeln nie beschaffen würden.
6. **Die gesamte Konfiguration liegt außerhalb des Abbilds** — in Umgebungsvariablen und
   Konfigurationsdateien, nicht im Container.

---

## Betriebsformen nach Größe

### Docker Compose — kleine Installationen

Der Einstieg. Ein Stapel aus Anwendungs-Backend, Web-Oberfläche, PostgreSQL mit pgvector und, sofern
die Anmeldung über einen mitgelieferten Verzeichnisdienst laufen soll, einem
Identitätsanbieter-Dienst. Vorgebaute Abbilder machen einen Bau auf dem Zielsystem entbehrlich; auf
dem Server genügt eine Compose-Datei.

Geeignet für ein Haus, das OPAA für sich betreibt, für Erprobungen und für Schulungsumgebungen. Die
Grenzen sind ehrlich zu benennen: ein Wirtsystem, damit ein Ausfallpunkt, und Wartungsarbeiten mit
kurzer Unterbrechung. Für eine Installation, deren Ausfall einen Fachbereich stillstellt, ist das
nicht die richtige Form.

Der heute tatsächlich unterstützte Weg, einschließlich Umgebungsvariablen, Aktualisierung und
Datenhaltung, steht in [deployment.md](../deployment.md).

### Kubernetes mit Hochverfügbarkeit — große Installationen

Für Installationen mit vielen Nutzenden oder mit Verfügbarkeitszusagen. Kennzeichen:

- **Mehrfach vorgehaltene Dienste** hinter einem Lastverteiler, mit Zustandsprüfungen und
  automatischer Übernahme.
- **Datenbank mit Replikation** und wiederkehrend geprüfter Wiederherstellung. Der Vektorindex ist
  aus dem Bestand neu erzeugbar, aber nicht billig — er wird gesichert, nicht neu berechnet.
- **Rollierende Aktualisierung** ohne spürbare Unterbrechung, mit Rückfallweg. Schemaänderungen
  laufen vorwärtsverträglich, damit eine alte und eine neue Fassung kurzzeitig nebeneinander
  bestehen können.
- **Getrennte Netzbereiche** zwischen Oberfläche, Anwendung, Datenhaltung und Modellbetrieb.
- **Angebundene Betriebsüberwachung** an die vorhandenen Werkzeuge des Rechenzentrums, nicht an einen
  mitgelieferten Sonderweg.

Die Ausbaustufen der Anwendung sind dieselben. Was sich unterscheidet, ist der Betrieb: Zustand liegt
außerhalb der Prozesse, jeder Dienst ist neustartbar, und keine Instanz hält Wissen, das nicht auch
woanders steht.

### Bare-Metal

Betrieb ohne Containerlaufzeit ist möglich, wo Vorgaben eines Hauses es erfordern, aber der
aufwendigste Weg: Die Betriebssystempakete, die Prozessverwaltung und die Aktualisierung liegen dann
vollständig beim Betreiber. Er wird unterstützt, aber nicht empfohlen.

---

## Betrieb ohne Netzanbindung

Der Betrieb in einem Netz ohne Weg ins Internet ist ein **vorgesehenes Szenario**. Das ist eine
Entwurfsvorgabe und keine nachträgliche Möglichkeit — sie schließt bestimmte Lösungen von vornherein
aus.

**Was daraus folgt:**

- **Modelle laufen lokal.** Sprach- und Einbettungsmodelle werden im Haus betrieben. Ein Zugang zu
  einem Modell außerhalb ist eine Freigabe, die eine Behörde ausdrücklich erteilt — nie eine
  Voreinstellung (siehe [llm-integration.md](./llm-integration.md)).
- **Keine Aufrufe nach außen zur Laufzeit.** Weder Anmeldung, noch Oberfläche, noch Lizenzprüfung,
  noch Schriftarten oder Symbole werden aus dem Netz nachgeladen. Alle Ressourcen der Oberfläche
  liegen in der Installation (siehe
  [ADR-0004](../decisions/0004-self-hosted-frontend-resources.md)).
- **Übertragbare Lieferung.** Anwendungsabbilder, Modellgewichte und Datenbankschema müssen sich als
  Dateien in das abgeschottete Netz bringen lassen und dort ohne weiteren Zugriff installieren.
  Nachvollziehbar erzeugte Abbilder und eine mitgelieferte Stückliste der Bestandteile sind dafür
  Voraussetzung, nicht Zusatz.
- **Aktualisierung als Vorgang, nicht als Automatik.** Eine Installation ohne Netzanbindung zieht
  keine Aktualisierung; sie bekommt sie geliefert. Der Weg dorthin ist ein dokumentierter,
  wiederholbarer Ablauf mit definiertem Rückfall.
- **Eingeschränkte Konnektoren.** Quellsysteme, die nur außerhalb erreichbar wären, entfallen. Das
  ist kein Mangel, sondern die Folge der Abschottung, und muss in der Oberfläche sichtbar sein statt
  als Fehler zu erscheinen.

Der praktische Prüfstein: Eine Installation, die nach dem Trennen der Netzverbindung weiterläuft,
weiter indiziert und weiter belegte Antworten liefert, erfüllt diese Anforderung. Alles andere ist
eine Absichtserklärung.

---

## Speicher-Backends

Die Wirklichkeit der Rechenzentren ist unterschiedlich: Das eine hat objektbasierten Speicher, das
nächste ein Netzlaufwerk, und die kleine Installation hat ein Verzeichnis. Der Weg dorthin führt
jedoch nicht über eine Speicher-Abstraktion in der Anwendung.

**Das Dateisystem ist der Vertrag.** OPAA schreibt und liest Quelldokumente gegen genau ein
konfiguriertes Verzeichnis (`OPAA_INDEXING_DOCUMENT_PATH`, Standard `./documents`). Was hinter diesem
Verzeichnis hängt, entscheidet der Betrieb durch Einhängen — nicht die Anwendung durch eine
Konfigurationsvariante. Eine Abstraktion über mehrere Speicherarten ist **nicht gebaut** und für die
beiden dateibasierten Fälle auch nicht vorgesehen.

Der Upload durch Beschäftigte (#420) schreibt in ein eigenes, zweites konfiguriertes Verzeichnis
(`OPAA_UPLOAD_STORAGE_PATH`, Standard `./uploads`), bewusst getrennt vom betriebsverwalteten
Indizierungsverzeichnis — Originale aus dem Upload gehören der Anwendung, Originale im
Indizierungsverzeichnis bleiben Eigentum der Quelle, aus der sie stammen (siehe
[`deleteDocument`](../features/knowledge-sources.md#upload), das genau diese Grenze durchsetzt).
`docker-compose.yml` hängt beide Verzeichnisse als eigene Volumes ein
(`OPAA_UPLOAD_STORAGE_PATH_HOST`, Standard `./uploads`, analog zu
`OPAA_INDEXING_DOCUMENT_PATH_HOST`) — ohne dieses Volume verschwindet der Upload-Bestand beim
Neuaufsetzen des Containers, während Datenbankzeilen und Chunks im Vektorspeicher bestehen bleiben
und auf eine nicht mehr vorhandene Datei zeigen.

Das löst den größeren Teil der Frage ohne eine Zeile Arbeit: **Ein Netzlaufwerk braucht keine
Abstraktion.** SMB und NFS werden vom Betriebssystem eingehängt und sehen für die Anwendung aus wie
ein gewöhnliches Verzeichnis.

| Backend | Typischer Einsatz | Heute gebaut |
|---|---|---|
| **Lokales Dateisystem** | kleine Installationen, Erprobung, Betrieb ohne Netzanbindung | **ja** — das konfigurierte Verzeichnis ist der einzige Weg |
| **Netzlaufwerk** (SMB/NFS) | Häuser, deren Bestände ohnehin auf einem Dateiserver liegen | **ja**, ohne eigenen Pfad im Code — das Netzlaufwerk wird auf das konfigurierte Verzeichnis eingehängt |
| **Objektspeicher** (S3-kompatibel, auch selbst betrieben) | Rechenzentrumsbetrieb, mandantenfähige Installationen, große Bestände | **nein** — Zielbild, braucht als einziges einen eigenen Pfad im Code |

Unabhängig vom Backend gilt: **Das Quelldokument ist der Beleg.** Es wird nicht nur eingebettet,
sondern bleibt greifbar, damit der Sprung von der Antwort zur Fundstelle möglich bleibt. Ein Speicher,
der nur den Index hält, macht die Belegbarkeit unmöglich.

Der Metadatenbestand und der Vektorindex liegen in PostgreSQL mit pgvector (siehe
[ADR-0002](../decisions/0002-mvp-technology-stack.md)). Anders als der Dateispeicher ist der
**Vektorspeicher nicht wählbar**: pgvector ist der einzige unterstützte. Der Zugriff läuft zwar über
eine portable Schnittstelle des eingesetzten Rahmenwerks, ein Wechsel wird aber nicht unterstützt, nicht
geprüft und nicht dokumentiert — Begründung in
[Daten-Indizierung & RAG](./data-indexing-rag.md#der-vektorspeicher-postgresql-mit-pgvector-und-sonst-keiner).

### Betriebshinweise zum eingehängten Netzlaufwerk

Dass die Anwendung keinen Unterschied sieht, heißt nicht, dass es keinen gibt. Drei Punkte gehören in
die Betriebsplanung:

- **Rechte des Dienstkontos auf der Freigabe.** Das Konto, unter dem OPAA läuft, braucht auf dem
  eingehängten Pfad Lese- und Schreibrechte. Bei SMB entscheiden zusätzlich die Zuordnung von
  Benutzerkennungen beim Einhängen und die Rechte der Freigabe selbst; ein Bestand, der für Menschen
  lesbar ist, ist deshalb nicht automatisch für den Dienst lesbar.
- **Verhalten bei Verbindungsabbruch während eines Indizierungslaufs.** Reißt die Verbindung mitten im
  Lauf, meldet das Dateisystem Ein-/Ausgabefehler statt „Datei nicht vorhanden". Der Lauf bricht ab und
  muss wiederholt werden; der Betrieb sollte die Einhängeoptionen so wählen, dass ein Abbruch zu einem
  Fehler führt und nicht zu einem unbegrenzt hängenden Prozess.
- **Keine Sperren wie auf einem lokalen Dateisystem.** Ein eingehängtes Netzlaufwerk garantiert keine
  Dateisperren. Wo zwei Installationen oder zwei Instanzen auf dasselbe Verzeichnis zeigen, ist die
  gegenseitige Abgrenzung eine Sache der Betriebsführung, nicht des Dateisystems.

### Objektbasierter Speicher: eigener Weg, ohne Termin

Objektbasierter Speicher ist der einzige der drei Fälle, der wirklich einen eigenen Pfad im Code
braucht. Er wird als eigener Weg geführt, jedoch **ohne Termin**.

Der Grund, warum er kommt, ist der **mandantenfähige Rechenzentrumsbetrieb**: Dort ist ein geteiltes
Netzlaufwerk über viele Häuser hinweg der unangenehmere Weg — bei der Trennung der Mandanten, bei
Kontingenten je Haus und bei der Sicherung. Er gehört damit in dieselbe Phase wie der mandantenfähige
Betrieb (**Phase 1**), ohne dass daraus eine Reihenfolge oder ein Zeitpunkt folgt.

Einhänge-Werkzeuge, die objektbasierten Speicher als Verzeichnis erscheinen lassen, gibt es. Sie
haben aber Nachteile bei Latenz und Konsistenz und bilden die Semantik eines Dateisystems nur
näherungsweise ab. Sie sind eine Notlösung für den Einzelfall, kein Ersatz für den eigenen Weg.

### Kein Objektspeicher-Dienst im mitgelieferten Compose-Stapel

Der mitgelieferte Stapel bleibt schlank: Datenbank, Anmeldung, Backend, Frontend. Ein zusätzlicher
Dienst für objektbasierten Speicher wäre etwas, das kleine Installationen nie brauchen und das den
Einstieg schwerer macht, statt ihn zu erleichtern. Wer objektbasierten Speicher betreibt, bringt ihn
ohnehin mit.

---

## Mandantenfähiger Betrieb durch ein Rechenzentrum

Dies ist der Betriebsweg mit der größten Reichweite. Ein Landes- oder Bundesrechenzentrum oder ein
kommunaler IT-Dienstleister betreibt **eine** Installation für viele Häuser: ein Vertragspartner,
viele Nutzerorganisationen. Häuser, die eine solche Plattform einzeln nie beschaffen und nie betreiben
würden, bekommen sie damit als Angebot ihres ohnehin zuständigen Dienstleisters — und der Betreiber
bringt Rechenzentrum, Betriebsprozesse und bestehende Prüfnachweise mit.

**Was das voraussetzt:**

1. **Mandantentrennung auf Ebene der Organisation.** Die Organisation ist die harte Grenze, die nichts
   überschreitet: keine Freigabe, keine Suche, kein Katalogtreffer, keine Systemverwaltung. Sie ist
   keine Betriebseinstellung, sondern eine Eigenschaft des Datenmodells (siehe
   [spaces-and-assets.md](./spaces-and-assets.md)).
2. **Rechteschichtung, die dahinter greift.** Innerhalb einer Organisation entscheiden die Rechte an
   den Wissensbibliotheken, wer was sieht — geprüft in der Suche, nicht danach (siehe
   [access-control.md](./access-control.md)).
3. **Getrennte Verwaltungsebenen.** Der Betreiber verwaltet die Plattform, jedes Haus verwaltet seine
   Organisation. Eine Betreiberrolle, die fachliche Inhalte eines Hauses einsehen kann, wäre der
   Bruch des Versprechens und ist nicht vorgesehen.
4. **Zurechenbarkeit je Organisation.** Protokolle, Kennzahlen, Aufbewahrungsfristen und Löschungen
   sind je Haus getrennt auswertbar und getrennt exportierbar — auch, weil jedes Haus seine eigene
   Dienstvereinbarung hat.
5. **Belastbare Trennung der Ressourcen.** Grenzen für Anfragen, Indizierung und Modellnutzung wirken
   je Organisation, damit ein Haus die Plattform nicht für die anderen ausbremst.

Die Zertifizierung des Betriebs erfolgt beim Betreiber; OPAA ist so gebaut und dokumentiert, dass es
in dessen Prüfumfang aufgenommen werden kann. Das Produkt selbst wird nie zertifiziert — der
Kriterienkatalog prüft den Betrieb.

---

## Andockung an die Bausteine des souveränen Arbeitsplatzes

Wo ein Haus einen souveränen Arbeitsplatz aus offenen Bausteinen einführt, sind Dateiablage, Wiki,
Postfach und Chat bereits vorhanden und im eigenen Betrieb. Was dort **nicht** vorhanden ist, ist eine
KI-Schicht und ein Wissensmanagement über die einzelnen Bausteine hinweg — für Wissen steht dort im
Wesentlichen das Wiki, also ein weiterer Ablageort, kein Zugang über alle Bestände.

Genau dort dockt OPAA an: als **KI- und Wissensschicht über** dem Arbeitsplatz, nicht als weiterer
Baustein daneben.

| Baustein | Anknüpfungspunkt |
|---|---|
| Dateiablage | lesender Konnektor auf freigegebene Bestände, mit Übernahme der Rechte aus dem Quellsystem |
| Wiki | lesender Konnektor auf Seitenbestände |
| Postfach | lesender Konnektor auf Archive, mit den Einschränkungen, die für Postfachdaten gelten |
| Chat (Matrix-Protokoll) | Kanal für Fragen und Antworten (siehe [user-frontends.md](./user-frontends.md)) |
| Zentrale Anmeldung | derselbe Verzeichnisdienst, dieselben Gruppen als Rechtesubjekt |

Das ist eine Beschreibung von Schnittstellen, keine Empfehlung für einen Anbieter und keine Bewertung
gegenüber anderen Umgebungen. Der Umfang dieser Anbindung gehört in Phase 4 und ist bedarfsgetrieben.

---

## Wo eine Installation stehen darf

OPAA braucht eine Container-Umgebung, PostgreSQL mit pgvector und, wo Modelle im Haus laufen,
Rechenleistung für den Modellbetrieb. Mehr nicht. Weil die gesamte Konfiguration außerhalb des
Abbilds liegt und der Dateispeicher austauschbar ist, ist eine Installation technisch dieselbe, gleich wo
sie steht.

Daraus folgt: **Der Ort ist keine Produktentscheidung.** OPAA schreibt ihn nicht vor, nennt keine
Betreiber und liefert keine anbieterspezifischen Anleitungen. Wo eine Installation steht, entscheidet
die verantwortliche Stelle — und mit ihr die rechtliche Zulässigkeit. Es gibt deshalb kein zweites
Betriebsmodell neben dem Betrieb im eigenen Verantwortungsbereich, sondern nur die Frage, wie weit
dieser Verantwortungsbereich reicht.

### Nicht der Ort entscheidet, sondern die Verantwortlichkeit

Ein Rechenzentrum des Landes oder ein kommunaler IT-Dienstleister ist ebenfalls nicht das eigene
Haus — und trotzdem ist der Betrieb dort unproblematisch. Der Grund ist nicht die Entfernung, sondern
die Zurechnung: Die Verantwortung ist vertraglich geregelt, die betreibende Stelle ist selbst Teil
der Verwaltung, und die Weisungslage ist eindeutig. Genau darin unterscheidet sich dieser Fall vom
Betrieb bei einem beliebigen Anbieter angemieteter Infrastruktur.

Die Frage lautet also nicht „im eigenen Haus oder außerhalb", sondern: **Wer ist Verantwortlicher der
Verarbeitung, und in welchem Rahmen ist er es?**

### Die rechtliche Schranke

Für einen erheblichen Teil der Verwaltungsdaten ist die Verarbeitung außerhalb der eigenen
Verantwortungssphäre rechtlich ausgeschlossen. Das Steuergeheimnis nach § 30 AO ist das schärfste,
aber nicht das einzige Beispiel; das Sozialgeheimnis und die Bestände aus Sicherheitsüberprüfungen
stehen unter vergleichbaren Bindungen.

Das ist hier als Feststellung genannt und nicht als Auslegung. Welche Daten wo verarbeitet werden
dürfen, prüft die verantwortliche Stelle mit ihrem Datenschutzbeauftragten — dieses Dokument
entscheidet das nicht und ersetzt die Prüfung nicht.

### Erprobung und Schulung

Wer OPAA vor einer Beschaffung ausprobieren oder Beschäftigte daran schulen will, tut das nicht im
Produktivnetz und nicht mit echten Vorgangsdaten. Eine Umgebung außerhalb des eigenen Hauses ist für
diesen Fall ein legitimer Weg, und er wird hier ausdrücklich benannt.

Die Bedingung, die ihn trägt, ist einfach und nicht verhandelbar: **Dort liegen keine echten Daten.**
Testbestände, öffentlich verfügbare Unterlagen oder synthetische Beispiele — mehr nicht. Sobald echte
Vorgänge ins Spiel kommen, gilt wieder der vorstehende Abschnitt, und die Umgebung ist die falsche.

### Kein vom Projekt betreuter Dienst

Das Projekt liefert Software, keinen Betrieb. Ein vom Projektteam betreuter Dienst ist nicht
vorgesehen — weder heute noch als Ausblick. Er würde das Projekt selbst zum Betreiber machen und eine
Erwartung an Verfügbarkeit, Erreichbarkeit und Haftung erzeugen, die ein quelloffenes Vorhaben nicht
einlöst.

Der Bedarf dahinter ist real und hat bereits seinen Weg: Wer betreuten Betrieb braucht, beauftragt
ein Rechenzentrum. Das ist oben als
[mandantenfähiger Betrieb](#mandantenfähiger-betrieb-durch-ein-rechenzentrum) beschrieben und bleibt
unverändert der vorgesehene Pfad.

---

## Konfiguration und Betriebsführung

- **Konfiguration über Umgebungsvariablen** und Konfigurationsdateien, nie im Abbild. Dasselbe Abbild
  läuft in Erprobung und Betrieb; nur die Umgebung unterscheidet sich.
- **Geheimnisse gehören in die Geheimnisverwaltung** des Betreibers, nicht in eine Datei neben der
  Compose-Datei und nie in ein Repository.
- **Schemaänderungen laufen versioniert und vorwärts.** Sie werden beim Start angewendet und löschen
  keine Bestandsdaten; eine Wiederherstellung ist der Weg zurück, keine automatische Rücknahme.
- **Ein Wechsel des Einbettungsmodells erzwingt eine Neuindizierung.** Bestehende Vektoren stammen aus
  einem anderen Modell und sind nicht vergleichbar; die Vektorbreite muss mitgezogen werden. Das ist
  der teuerste Wartungsvorgang und gehört entsprechend geplant.
- **Sichere Voreinstellungen.** Eine Installation ist in ihrem Auslieferungszustand nicht offen; der
  Betrieb ohne geprüfte Anmeldung ist Entwicklungs- und Testumgebungen vorbehalten und wird vom
  System sichtbar gemeldet (siehe [ADR-0005](../decisions/0005-authentication-strategy.md)).
- **Betriebsdaten und Fachdaten sind getrennt.** Was zur Überwachung des Betriebs erhoben wird, ist
  keine Auswertung der Nutzung durch Beschäftigte — der personenbezogene Auswertungspfad ist nicht
  vorgesehen (siehe [spaces-and-assets.md](./spaces-and-assets.md)).

---

## Aktualisierung im laufenden Betrieb

Für ein Haus, das ein Wartungsfenster beantragen und ankündigen muss, ist die Frage „wie lange steht
das System" keine Randnotiz, sondern der Unterschied zwischen einer Aktualisierung im Monatsrhythmus
und einer im Jahresrhythmus. Je länger der Abstand, desto größer der Sprung — und desto größer das
Risiko genau des Ausfalls, den das Wartungsfenster verhindern sollte.

### Was heute gilt

Der Compose-Stapel wird aktualisiert, indem neue Abbilder bezogen und die betroffenen Container neu
erstellt werden. Das ist eine **kurze Unterbrechung**, keine unterbrechungsfreie Umschaltung: Es gibt
eine Instanz je Dienst, und während des Neustarts ist sie nicht erreichbar. Der genaue Ablauf und was
er mit dem Index macht, steht in [deployment.md](../deployment.md).

### Zielbild für große Installationen

- **Rollierende Aktualisierung.** Instanzen werden nacheinander ersetzt, jede neue muss ihre
  Bereitschaftsprüfung bestehen, bevor die nächste alte weicht. Fällt die Prüfung durch, hält der
  Vorgang an und der bisherige Stand läuft weiter. Voraussetzung ist, dass keine Instanz Zustand
  hält, den nicht auch eine andere kennt.
- **Blau-Grün-Umschaltung** als Alternative, wo eine Änderung nicht instanzweise ausrollbar ist: Der
  neue Stand wird vollständig daneben aufgebaut und geprüft, dann schaltet der Lastverteiler um. Der
  Rückweg ist die Umschaltung zurück, solange der alte Stand steht. Der Preis ist die doppelte
  Ausstattung während der Umschaltung; der Gewinn ist ein Rückfallweg, der Sekunden statt Stunden
  braucht.
- **Ein Wartungsfenster bleibt für die Fälle**, die beides nicht erlauben — allen voran ein Wechsel
  des Einbettungsmodells mit anschließender Neuindizierung und ein Hauptversionswechsel der
  Datenbank. Diese Fälle werden benannt und nicht als „normale Aktualisierung" ausgegeben.

### Abwärtskompatibilität zwischen Anwendung und Schema

Beide Verfahren setzen voraus, dass **zwei Anwendungsstände kurzzeitig auf demselben Schema laufen
können**. Das ist keine Betriebseinstellung, sondern eine Regel für die Entwicklung:

1. **Schemaänderungen laufen nur vorwärts** und werden versioniert über Liquibase beim Start
   angewendet. Es gibt keinen automatischen Rückbau; der Weg zurück ist die Wiederherstellung aus
   der Sicherung.
2. **Erweitern, dann umstellen, dann aufräumen — in getrennten Ausgaben.** Eine neue Spalte wird
   zuerst hinzugefügt und optional befüllt, während der alte Stand sie ignoriert. Erst der nächste
   Stand benutzt sie verbindlich. Entfernt wird erst, wenn kein laufender Stand sie mehr braucht.
   Eine Änderung, die Spalten in derselben Ausgabe hinzufügt und entfernt, macht jede rollierende
   Aktualisierung unmöglich.
3. **Änderungssätze sind unveränderlich, sobald sie ausgeliefert sind.** Ein nachträglich
   bearbeiteter Änderungssatz bringt jede bestehende Installation zum Stehen.
4. **Die Schnittstelle bleibt in ihrer Version stabil.** Innerhalb einer Version werden Felder
   ergänzt, nicht entfernt oder umgedeutet; eine brechende Änderung bekommt eine neue Version, und
   die alte läuft eine benannte Übergangszeit weiter. Das gilt besonders für maschinelle Zugänge, die
   niemand kurzfristig anpassen kann (siehe [user-frontends.md](./user-frontends.md)).

---

## Netzsicherheit und Transportverschlüsselung

Betriebsthemen: Wer das Netz trennt, den vorgelagerten Zugangsweg betreibt und die Zertifikate
verwaltet, ist der Betreiber — nicht die Anwendung. Deshalb steht dies hier und nicht in der
Spezifikation zu Sicherheit und Nachweisführung; die endgültige Zuordnung ist offen (siehe
[Offene Fragen](#offene-fragen--zukünftige-erweiterungen)).

### Netztrennung

- **Die Installation ist aus dem Behördennetz erreichbar, nicht aus dem Internet** — es sei denn, ein
  Haus entscheidet ausdrücklich anders und trägt die Folgen. Der Standard ist die Erreichbarkeit von
  innen.
- **Getrennte Bereiche für Zugangsweg, Anwendung, Datenhaltung und Modellbetrieb.** Die Datenbank
  nimmt Verbindungen nur von der Anwendung an, der Modellbetrieb nur von der Anwendung. Kein Dienst
  ist aus dem Netz erreichbar, der es nicht sein muss.
- **Ausgehende Verbindungen sind die Ausnahme und werden benannt.** In einer Installation ohne
  Netzanbindung gibt es sie gar nicht; wo es sie gibt, sind es genau die freigegebenen Quellsysteme
  und, sofern ausdrücklich erlaubt, ein Modellzugang außerhalb. Alles andere wird geblockt — das ist
  zugleich die wirksamste Begrenzung des Risikos, dass ein Indizierungsauftrag den Server zu
  Aufrufen an ungewollte Ziele bewegt.
- **Verwaltungszugänge laufen getrennt** vom Nutzungsweg und nicht über dieselbe öffentlich
  erreichbare Adresse.

### Transportverschlüsselung

- **Verschlüsselt auf allen Wegen**, auch innerhalb des Rechenzentrums. „Intern, also
  vertrauenswürdig" ist keine tragfähige Annahme, wenn im selben Netz weitere Verfahren laufen.
- **Ein vorgelagerter Zugangsweg terminiert die Verschlüsselung** und reicht an die Anwendung weiter;
  die Anwendungscontainer binden nicht selbst nach außen. Genau so läuft es heute, einschließlich der
  Bindung aller Container ausschließlich auf die lokale Adresse (siehe
  [deployment.md](../deployment.md)).
- **Zertifikate stammen aus der Verwaltung des Betreibers** — in einer abgeschotteten Umgebung
  regelmäßig aus einer eigenen Zertifizierungsstelle des Hauses. Eine Installation, die für ihr
  Zertifikat einen Dienst im Internet braucht, ist ohne Netzanbindung nicht betreibbar.
- **Eine Abschaltung der Zertifikatsprüfung ist Erprobungen vorbehalten**, wird als solche kenntlich
  gemacht und ist im Regelbetrieb ein Befund.
- **Verschlüsselung im Ruhezustand** — Datenträger, Datenbank, Sicherungen — ist Sache der
  Betriebsplattform und wird vom Produkt vorausgesetzt, nicht ersetzt.

---

## Betriebsüberwachung und Alarmierung

Die Kennzahlen des Betriebs werden an die vorhandenen Werkzeuge des Rechenzentrums übergeben, nicht
in einem mitgelieferten Sonderweg gehalten. Sie beschreiben den **Zustand des Systems**, nicht das
Verhalten von Beschäftigten; der personenbezogene Auswertungspfad ist nicht vorgesehen.

Die eigentliche Entscheidung ist nicht, was gemessen wird, sondern was jemanden **weckt**. Ein
Alarmwesen, das zu viel meldet, wird stummgeschaltet, und dann meldet es gar nichts mehr.

| Zustand | Folge |
|---|---|
| Anwendung nicht erreichbar oder Bereitschaftsprüfung dauerhaft negativ | **weckt** — der Dienst steht |
| Datenbank nicht erreichbar oder Replikation abgerissen | **weckt** — Datenverlustrisiko |
| Speicher der Datenbank oder des Dokumentenbestands vor dem Volllaufen | **weckt** — mit Vorlauf, nicht erst beim Anschlag |
| Letzte Sicherung fehlgeschlagen oder ausgeblieben | **weckt** — eine ausgebliebene Sicherung fällt sonst erst im Ernstfall auf |
| Anmeldung gegen den Verzeichnisdienst dauerhaft fehlerhaft | **weckt** — niemand kommt mehr hinein |
| Fehlerquote der Abfragen dauerhaft über der gesetzten Schwelle | **weckt** |
| Einzelner fehlgeschlagener Indizierungslauf | Auswertung — wiederholt fehlschlagende Läufe wecken |
| Einzelne nicht verarbeitbare Dokumente | Auswertung — sie gehören in einen Bericht für die Fachverantwortlichen, nicht in die Nacht |
| Antwortzeiten über dem Zielwert, ohne Ausfall | Auswertung — Grundlage für Ausbauentscheidungen |
| Modellzugang zeitweise nicht erreichbar | Auswertung, sofern die Anwendung es sichtbar abfängt; dauerhaft weckt es |
| Ausgeschöpftes Anfragekontingent | Auswertung — das Kontingent tut genau das, wofür es da ist |
| Verbrauch gegenüber einem gesetzten Kontingent des Modellbetriebs | Auswertung, mit Schwellenmeldung vor dem Erreichen |

Jeder weckende Alarm braucht eine hinterlegte Handlungsanweisung. Ein Alarm ohne beschriebene Reaktion
erzeugt Wachdienst, aber keine Wiederherstellung.

---

## Skalierung und Zielwerte

Die folgenden Werte sind **Zielwerte für den Entwurf**, keine Messergebnisse und keine Zusage. Sie
sagen, woraufhin gebaut und geprüft wird; belastbare Zahlen entstehen erst in einer Installation
unter Last. Aussagen zu Aufwand oder Kosten gehören ausdrücklich nicht hierher.

| Größenordnung | Betriebsform | Zielwert gleichzeitig arbeitende Personen | Zielwert Bestand |
|---|---|---|---|
| **Klein** | Docker Compose, ein Wirtsystem | einige Dutzend angemeldete, davon eine einstellige Zahl gleichzeitig laufender Abfragen | Bestände in der Größenordnung eines Fachbereichs |
| **Mittel** | Kubernetes, mehrere Instanzen je Dienst | mehrere Hundert angemeldete, mehrere Dutzend gleichzeitig laufender Abfragen | Bestände eines ganzen Hauses |
| **Groß / mandantenfähig** | Kubernetes, waagerechte Skalierung, getrennte Ressourcen je Organisation | über die mittlere Größenordnung hinaus, begrenzt durch den Modellbetrieb | Bestände vieler Häuser nebeneinander |

**Woran skaliert wird:**

- **Waagerecht bei der Anwendung.** Die Anwendungsinstanzen halten keinen Zustand; mehr Last wird mit
  mehr Instanzen beantwortet. Ein Zielwert, der nur durch eine größere Einzelmaschine erreichbar
  wäre, ist ein Entwurfsfehler.
- **Der Modellbetrieb ist der eigentliche Engpass.** Bei lokal betriebenen Modellen bestimmt die
  verfügbare Rechenleistung die Zahl gleichzeitiger Antworten, nicht die Anwendung. Wer aufrüstet,
  rüstet dort auf.
- **Die Indizierung ist von der Abfrage zu entkoppeln.** Ein laufender Indizierungslauf darf die
  Antwortzeiten nicht spürbar verschlechtern; große Läufe gehören in verkehrsarme Zeiten.
- **Anfragekontingente je Organisation** sind das Mittel gegen gegenseitige Behinderung im
  mandantenfähigen Betrieb.

**Zielwerte für das Verhalten:**

- Die Suche über den Bestand liefert ihre Treffer, bevor das Modell zu formulieren beginnt — der
  spürbare Teil der Wartezeit entsteht beim Modell, nicht beim Abruf.
- Eine vollständige Neuindizierung des Bestands ist innerhalb eines nächtlichen Zeitfensters
  abzuschließen; ist sie das nicht, ist der Bestand zu groß für die gewählte Größenordnung.
- Die laufende Fortschreibung des Index — nur geänderte Dokumente — bleibt weit darunter und läuft im
  Regelbetrieb mit.
- Ein Ausfall einer einzelnen Anwendungsinstanz bleibt für die Nutzenden folgenlos, sobald mehr als
  eine Instanz läuft.

---

## Sicherung und Wiederherstellung

| Bestandteil | Wiederherstellbarkeit |
|---|---|
| Metadaten, Rechte, Chats, Artefakte, Protokolle | **nicht rekonstruierbar** — Sicherung zwingend |
| Vektorindex | aus dem Dokumentenbestand neu erzeugbar, aber aufwendig — Sicherung empfohlen |
| Quelldokumente aus Konnektoren | aus dem Quellsystem erneut beziehbar |
| Hochgeladene Dokumente | **nicht rekonstruierbar** — Sicherung zwingend |
| Konfiguration | versioniert außerhalb der Installation zu führen |

Eine Sicherung, deren Wiederherstellung nie geprüft wurde, ist keine Sicherung. Wiederherstellungs-
und Ausfallübungen gehören in den Betriebsplan des Betreibers; das Produkt liefert dafür die
Beschreibung der Bestandteile und ihrer Abhängigkeiten.

### Notfallwiederherstellung

Sichern ist die eine Hälfte, der Wiederanlauf die andere — und nur die zweite entscheidet, ob ein
Ausfall überstanden wird. Die folgenden Werte sind **Zielwerte** und je Installation zwischen Haus
und Betreiber zu vereinbaren; das Produkt ist so gebaut, dass sie erreichbar sind.

| Kennzahl | Zielwert | Bedeutung |
|---|---|---|
| **Wiederherstellungszeit** (RTO) | wenige Stunden bis zum arbeitsfähigen System | Bis zu diesem Zeitpunkt sind Anmeldung, Arbeitsräume und belegte Antworten wieder verfügbar |
| **Wiederherstellungspunkt** (RPO) | höchstens ein Tag für den Metadatenbestand | So viel Arbeit darf im schlimmsten Fall verloren gehen — betroffen sind Chats, Artefakte und Rechteänderungen seit der letzten Sicherung |
| **Wiederherstellungszeit des Vektorindex** | nachrangig | Der Index darf länger brauchen: Er ist aus dem Bestand neu erzeugbar, und das System ist ohne ihn eingeschränkt, aber nicht unbrauchbar |

Ein kürzerer Wiederherstellungspunkt ist erreichbar, kostet aber häufigere Sicherungen oder eine
fortlaufende Übertragung der Datenbankänderungen. Das ist eine bewusste Entscheidung des Hauses und
keine Voreinstellung.

**Was ein Wiederherstellungstest umfasst.** Ein Test, der nur prüft, ob die Sicherungsdatei lesbar
ist, prüft nichts:

1. **Wiederherstellung auf ein leeres System**, nicht auf das laufende — nur so zeigt sich, ob die
   Sicherung vollständig ist oder unbemerkt von vorhandenem Zustand zehrt.
2. **Anwendung starten und Schemastand prüfen.** Die Versionsverwaltung des Schemas muss den Stand
   erkennen und darf keine bereits angewendete Änderung erneut ausführen.
3. **Fachliche Stichprobe:** Anmeldung, ein Arbeitsraum mit seinen Mitgliedern, eine Wissensbibliothek
   mit ihren Freigaben, eine Abfrage mit belegter Antwort und ein Sprung in das Quelldokument. Erst
   damit ist gezeigt, dass Metadaten, Rechte, Index und Dokumentenbestand **zueinander passen** — der
   häufigste Fehler ist eine Sicherung, deren Teile aus verschiedenen Zeitpunkten stammen.
4. **Rechteprobe:** Eine Person ohne Freigabe darf nach der Wiederherstellung nicht mehr sehen als
   vorher. Ein Wiederanlauf, der Rechte verliert, ist schlimmer als ein Ausfall.
5. **Zeit messen und festhalten**, gegen den vereinbarten Zielwert.
6. **Ergebnis dokumentieren** — mit Datum, Stand und aufgetretenen Abweichungen. Der Nachweis
   gegenüber einer Prüfung ist das Protokoll der Übung, nicht die Absichtserklärung.

Die Übung wird wiederkehrend durchgeführt und zusätzlich nach jeder Änderung, die den Aufbau der
Installation berührt.

---

## Integrationspunkte

- **[deployment.md](../deployment.md)** — der tatsächlich verfügbare Betriebsweg mit allen
  Umgebungsvariablen; diese Spezifikation beschreibt das Zielbild darüber hinaus
- **[access-control.md](./access-control.md)** — Identität, Rollen und Mandantengrenze
- **[spaces-and-assets.md](./spaces-and-assets.md)** — die Organisation als harte Trennung, auf der
  der mandantenfähige Betrieb aufsetzt
- **[llm-integration.md](./llm-integration.md)** — lokal betriebene Modelle als Voraussetzung des
  Betriebs ohne Netzanbindung
- **[data-indexing-rag.md](./data-indexing-rag.md)** — Indizierung, deren Kosten die Planung von
  Aktualisierungen bestimmen
- **[user-frontends.md](./user-frontends.md)** — welche Kanäle in einer abgeschotteten Installation
  überhaupt erreichbar sind
- **[public-sector.md](./public-sector.md)** — Revisionssicherheit und Aufbewahrung als
  Betriebsanforderung

---

## Offene Fragen / Zukünftige Erweiterungen

- Wie der Weg zu objektbasiertem Speicher im Einzelnen aussieht — Kontingente, Trennung der Mandanten
  und Sicherung je Organisation —, ist offen. Dass er als eigener Weg geführt wird und dass kein
  solcher Dienst in den mitgelieferten Stapel gehört, ist entschieden (siehe
  [Speicher-Backends](#speicher-backends)).
- **Zuordnung von Netzsicherheit und Transportverschlüsselung ist zu klären.** Beides steht hier,
  weil Netztrennung, vorgelagerter Zugangsweg und Zertifikatsverwaltung Betriebsthemen sind und beim
  Betreiber liegen. Ebenso vertretbar wäre die Spezifikation zu Sicherheit und Nachweisführung, die
  die übrigen Schutzziele führt. Zu vermeiden ist beides gleichzeitig — eine doppelt gepflegte
  Beschreibung veraltet an einer der beiden Stellen.
- Werden Wiederherstellungszeit- und Wiederherstellungspunktziele als Voreinstellung mitgeliefert
  oder ausschließlich je Installation vereinbart?
- Werden Bereitstellungsbeschreibungen für Kubernetes mitgeliefert und gepflegt, oder bleibt das
  Sache des Betreibers?
- Wie wird eine Lieferung in ein abgeschottetes Netz praktisch gebündelt — Abbilder, Modellgewichte
  und Stückliste als ein Paket oder als getrennte Lieferwege?
- Wie werden Modelle in einer mandantenfähigen Installation zugeteilt: gemeinsamer Modellbetrieb für
  alle Organisationen oder getrennter je Haus?

---

## Erfolgs-Metriken

- Eine kleine Installation ist von der leeren Maschine bis zur ersten belegten Antwort in
  überschaubarer Zeit betriebsbereit, ohne Rückfragen an das Projekt.
- Eine Installation läuft nach dem Trennen der Netzverbindung unverändert weiter — einschließlich
  Indizierung und Antworten.
- Eine Aktualisierung erfordert keine Neuindizierung, solange das Einbettungsmodell unverändert
  bleibt.
- Eine Aktualisierung einer großen Installation ist ohne beantragtes Wartungsfenster möglich; die
  Fälle, in denen das nicht gilt, sind vorher benannt.
- Eine Wiederherstellung aus der Sicherung wurde geprüft und ist dokumentiert, nicht angenommen —
  einschließlich der gemessenen Dauer gegen den vereinbarten Zielwert.
- In einer mandantenfähigen Installation lässt sich für jedes Haus getrennt nachweisen, wer worauf
  zugegriffen hat.
