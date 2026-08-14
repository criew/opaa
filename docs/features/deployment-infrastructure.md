# Betrieb & Deployment

> **Status: Entwurf.** Themenbereich J der Produktvision. Phasenlage: Betrieb mit Docker Compose,
> Kubernetes mit Hochverfügbarkeit und der Betrieb ohne Netzanbindung gehören in **Phase 1**; die
> Andockung an die Bausteine des souveränen Arbeitsplatzes in **Phase 4**. Der Umfang der
> Speicher-Abstraktion wird in [#351](https://github.com/criew/opaa/issues/351) geklärt, die Zukunft
> von Cloud-Deployment und betreutem Dienst in [#350](https://github.com/criew/opaa/issues/350).
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
   die begründet werden muss — nicht umgekehrt.
2. **Zwei Größenordnungen, eine Codebasis.** Docker Compose für kleine Installationen, Kubernetes mit
   Hochverfügbarkeit für große. Der Unterschied liegt in der Betriebsform, nicht im Produkt.
3. **Betrieb ohne Netzanbindung ist ein vorgesehenes Szenario**, keine Ausnahme und kein Sonderfall.
   Er bestimmt Entwurfsentscheidungen mit, statt nachträglich ermöglicht zu werden.
4. **Speicher ist austauschbar.** Objektspeicher, Netzlaufwerk und lokales Dateisystem bedienen
   unterschiedliche Rechenzentren; der Umfang der Abstraktion ist offen.
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

Die Anwendung schreibt keine Dokumente an einen festen Ort, sondern gegen eine Abstraktion. Der
Grund ist nicht Wahlfreiheit als Selbstzweck, sondern die Wirklichkeit der Rechenzentren: Das eine
hat objektbasierten Speicher, das nächste ein Netzlaufwerk, und die kleine Installation hat ein
Verzeichnis.

| Backend | Typischer Einsatz |
|---|---|
| **Objektspeicher** (S3-kompatibel, auch selbst betrieben) | Rechenzentrumsbetrieb, mandantenfähige Installationen, große Bestände |
| **Netzlaufwerk** (SMB/NFS) | Häuser, deren Bestände ohnehin auf einem Dateiserver liegen |
| **Lokales Dateisystem** | kleine Installationen, Erprobung, Betrieb ohne Netzanbindung |

Unabhängig vom Backend gilt: **Das Quelldokument ist der Beleg.** Es wird nicht nur eingebettet,
sondern bleibt greifbar, damit der Sprung von der Antwort zur Fundstelle möglich bleibt. Ein Speicher,
der nur den Index hält, macht die Belegbarkeit unmöglich.

Der Metadatenbestand und der Vektorindex liegen in PostgreSQL mit pgvector (siehe
[ADR-0002](../decisions/0002-mvp-technology-stack.md)). Welche Backends dauerhaft geführt werden und
ob ein selbst betriebener Objektspeicher in den mitgelieferten Compose-Stapel gehört, ist offen und
wird in [#351](https://github.com/criew/opaa/issues/351) entschieden.

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

## Cloud-Deployment und betreuter Dienst

Der bisherige Stand dieses Dokuments nannte neben dem Betrieb im eigenen Haus zwei weitere Modelle:
den Betrieb in einer angemieteten Cloud-Umgebung und einen vom Projektteam betreuten Dienst. Beide
stehen **weiterhin hier** und werden nicht stillschweigend gestrichen.

Offen ist, wie sie sich zur Ausrichtung auf Souveränität verhalten:

- Ist der Betrieb in einer angemieteten Cloud-Umgebung für die Zielgruppe ein realistischer Weg, oder
  untergräbt das Angebot die Zusage, dass Daten das Haus nicht verlassen?
- Passt ein vom Projektteam betreuter Dienst zu einem quelloffenen Produkt, das von Rechenzentren
  mandantenfähig betrieben werden soll?

Diese Fragen werden in [#350](https://github.com/criew/opaa/issues/350) entschieden. Bis dahin wird
hier weder etwas entfernt noch etwas zugesagt. Festhalten lässt sich unabhängig davon nur das
Technische: Da die gesamte Konfiguration außerhalb des Abbilds liegt und der Speicher austauschbar
ist, wäre ein solcher Betrieb technisch dieselbe Installation an einem anderen Ort. Die Frage ist
keine technische, sondern eine der Zusage.

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

- Cloud-Deployment und betreuter Dienst: bleiben, entfallen oder werden umformuliert? Entscheidung in
  [#350](https://github.com/criew/opaa/issues/350).
- Umfang der Speicher-Abstraktion und Zusammensetzung des mitgelieferten Stapels: Entscheidung in
  [#351](https://github.com/criew/opaa/issues/351).
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
- Eine Wiederherstellung aus der Sicherung wurde geprüft und ist dokumentiert, nicht angenommen.
- In einer mandantenfähigen Installation lässt sich für jedes Haus getrennt nachweisen, wer worauf
  zugegriffen hat.
