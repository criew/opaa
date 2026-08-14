# Kanäle & Oberflächen

> **Status: Entwurf.** Themenbereich I der Produktvision. Phasenlage: Web-Oberfläche und REST-API
> gehören in **Phase 1**, die Anbindung an self-hosted Team-Chats in **Phase 3**, Erweiterungen für
> Office und Browser in **Phase 4**. Das Zielbild der Chat-Kanäle ist offen und wird in
> [#352](https://github.com/criew/opaa/issues/352) geklärt.

## Motivation

Ein Assistent, den man erst aufsuchen muss, wird in der Verwaltung wenig genutzt. Sachbearbeitung
arbeitet in einem Vorgang, nicht in einem Werkzeugkasten — je weiter der Weg zur Antwort, desto eher
bleibt die Frage ungestellt oder wandert in ein Werkzeug außerhalb des Hauses. Der zweite Grund ist
Schatten-KI: Wo kein zugelassener Kanal erreichbar ist, entsteht ein nicht zugelassener.

Zugleich ist jeder zusätzliche Kanal dauerhafter Aufwand — eigene Anmeldung, eigene Darstellung von
Quellen, eigene Fehlerbilder, eigene Pflege bei jeder Änderung der Plattform. OPAA baut deshalb nicht
möglichst viele Kanäle, sondern ein tragfähiges Fundament und darauf eine begründete Auswahl.

Dieses Dokument beschreibt, welche Oberflächen es gibt, welche Eigenschaften sie teilen und in welcher
Reihenfolge sie entstehen.

---

## Überblick

1. **Die Web-Oberfläche ist die vollständige Oberfläche.** Alles, was OPAA kann, ist dort erreichbar —
   Fragen, Quellen, Assets, Verwaltung. Jeder weitere Kanal zeigt einen Ausschnitt davon.
2. **Die REST-API ist das Fundament aller weiteren Kanäle.** Auch die Web-Oberfläche benutzt sie. Ein
   Kanal, der etwas könnte, was die API nicht anbietet, ist ein Konstruktionsfehler.
3. **Kein Kanal hat eigene Rechte.** Jede Anfrage läuft unter der Identität einer angemeldeten Person
   und mit deren Leserechten; ein Kanal-Konto mit erweiterter Sicht gibt es nicht.
4. **Jede Antwort führt ihre Belege mit** — in jedem Kanal. Wo ein Kanal Quellenangaben nicht
   darstellen kann, ist er kein geeigneter Kanal.
5. **Team-Chats sind Ausbau, nicht Fundament.** Sie holen OPAA an den Ort, an dem Teams ohnehin
   sprechen, setzen aber die tragenden Fähigkeiten voraus.
6. **Erweiterungen für Office und Browser bleiben eine spätere Option** mit hohem Aufwand je
   Erweiterung und entsprechend hoher Begründungslast.

---

## Web-Oberfläche

Die Web-Oberfläche ist der Kanal, an dem sich das Produkt entscheidet. Sie ist die einzige Stelle, an
der alle Fähigkeiten vollständig sichtbar sind, und der Maßstab für alles Weitere.

### Was sie leistet

| Bereich | Zweck | Heute gebaut |
|---|---|---|
| **Fragen und Antworten** | Frage stellen, Antwort mit Fundstellen erhalten, Relevanz und Trefferzahl je Quelle sehen, erkennen, welche Quelle tatsächlich zitiert wurde | ja |
| **Gesprächsverlauf** | Rückfragen im laufenden Gespräch, die den bisherigen Verlauf berücksichtigen | teilweise — der Verlauf besteht nur innerhalb der geöffneten Sitzung |
| **Suchfilter** | die Abfrage auf ausgewählte Arbeitsräume eingrenzen | ja — Eingrenzung auf Arbeitsräume; weitere Filter siehe unten |
| **Arbeitsräume** | Chats und Artefakte eines Themas, Entwurf und Ablage getrennt (siehe [spaces-and-assets.md](./spaces-and-assets.md)) | teilweise — Übersicht, Mitglieder, Rollen und Eigentumsübergabe sind vorhanden |
| **Wissen** | Dokumente einer Wissensbibliothek einsehen, hochladen, Indizierungsstand erkennen | nein — die Rechte- und Bestandsverwaltung besteht in der Schnittstelle, die Dokumentenseite der Oberfläche ist ein Platzhalter |
| **Assets** | Agenten, Prompt-Bibliotheken und Wissensbibliotheken anlegen, beschreiben, freigeben, finden | nein — Zielbild |
| **Rückmeldung** | Antworten und Treffer bewerten; die Rückmeldung fließt in die Suchqualität ein (siehe [search-quality-evaluation.md](./search-quality-evaluation.md)) | teilweise — Bedienelement vorhanden, ohne Wirkung (siehe unten) |
| **Systemverwaltung** | Gruppen und Verzeichnisabgleich, Rollen, Auslösen und Stand der Indizierung | teilweise — Gruppen, Verzeichnisabgleich, Rollen und Indizierung sind vorhanden; Modellvorgaben und Protokolleinsicht sind Zielbild (siehe [access-control.md](./access-control.md) und [llm-integration.md](./llm-integration.md)) |
| **Persönliche Einstellungen** | Darstellung, später eigene Zugänge zur Schnittstelle | teilweise — nur die Darstellung; eine Verwaltung eigener API-Zugänge gibt es nicht |

### Dokumentenübersicht, Gesprächsverwaltung und Suchfilter

Drei Bereiche, die der frühere Stand dieses Dokuments beschrieben hat und die hier mit dem
tatsächlichen Stand abgeglichen sind. Dokumentenübersicht und Gesprächsverwaltung sind **Zielbild**
und heute nicht gebaut; die Dokumentenseite der Oberfläche zeigt einen Platzhalter.

**Dokumentenübersicht.** Wer eine Antwort prüft, will den Bestand dahinter sehen können: welche
Dokumente einer Wissensbibliothek indiziert sind, wann zuletzt, mit welchem Ergebnis — ausstehend,
indiziert, fehlgeschlagen — und mit der Möglichkeit, ein Dokument im Original zu öffnen. Ohne diese
Sicht ist eine ausbleibende Antwort nicht von einem lückenhaften Bestand zu unterscheiden, und genau
diese Verwechslung untergräbt das Vertrauen in das System schneller als eine falsche Antwort.

Die zugrunde liegende Abfrage besteht bereits in der Schnittstelle (`GET
/api/v1/libraries/{id}/documents`); was fehlt, ist die Darstellung.

**Gesprächsverwaltung.** Ein Gespräch überlebt heute das Neuladen der Seite nicht. Im Zielbild
gehört ein Gespräch in einen Arbeitsraum: benannt, wiederauffindbar, für die Mitglieder des
Arbeitsraums sichtbar, sobald der Autor es dort ablegt — bis dahin bleibt es Entwurf und damit beim
Autor (siehe [spaces-and-assets.md](./spaces-and-assets.md)). Dazu gehören das Löschen des eigenen
Verlaufs und ein Export des Gesprächs samt Fundstellen, weil ein Gesprächsergebnis in der Verwaltung
regelmäßig in einen Vorgang übernommen wird.

Die Aufbewahrungsdauer abgelegter Gespräche ist eine Betriebs- und Mitbestimmungsfrage, keine
Voreinstellung des Produkts.

**Suchfilter.** Gebaut ist die Eingrenzung auf ausgewählte Arbeitsräume. Im Zielbild kommen die
Eingrenzung auf einzelne Wissensbibliotheken, auf den Dokumenttyp und auf den Stand der Indizierung
hinzu. Ein Filter, der die Rechteprüfung ersetzen würde, ist ausgeschlossen: Filter verengen die
Sicht, sie erweitern sie nie.

### Belegbarkeit ist Oberfläche, nicht Beiwerk

Das Leitprinzip der Belegbarkeit steht und fällt mit der Darstellung. Eine Fundstelle, die man nicht
öffnen kann, ist kein Beleg. Die Oberfläche zeigt deshalb zu jeder Antwort, worauf sie beruht, macht
den Sprung in das Quelldokument möglich und benennt sichtbar, wenn eine Aussage nicht belegt werden
konnte. Im Zitierzwang verweigert das System die Antwort, statt sie plausibel zu formulieren — auch
das ist ein Zustand, der dargestellt werden muss und nicht als Fehler aussehen darf.

### Barrierefreiheit

Die Web-Oberfläche ist der Ort, an dem sich die Verpflichtung zur Barrierefreiheit einlöst. Die
Anforderungen und ihre Rechtsgrundlage stehen in [public-sector.md](./public-sector.md); ihre
Umsetzung ist eine Eigenschaft dieses Kanals, keine nachgelagerte Prüfung.

---

## Rückmeldung zur Antwortqualität

Die Rückmeldung schließt die Rückkopplungsschleife aus Themenbereich A: Ohne sie ist die einzige
verfügbare Aussage über die Antwortqualität die Vermutung derer, die das System gebaut haben.

**Stand:** Das Bedienelement — Zustimmung oder Ablehnung zu einer Antwort — ist in der Oberfläche
vorhanden und beschriftet, hat aber **keine Wirkung**: Es gibt keinen Endpunkt, der eine Bewertung
entgegennimmt, und keine Speicherung. Alles Weitere in diesem Abschnitt ist Zielbild.

### Was bewertet wird

- **Die Antwort als Ganzes** — zutreffend oder nicht. Das ist die niedrigschwelligste Form und
  deshalb die einzige, die verlässlich genutzt wird.
- **Die einzelne Fundstelle** — trug sie zur Antwort bei oder war sie ein Fehltreffer? Diese Angabe
  ist die fachlich wertvollere, weil sie auf den Abruf zeigt und nicht auf die Formulierung.
- **Ein freier Hinweis** in Textform, ausdrücklich freiwillig.

### Was mit der Bewertung geschieht

1. **Sie wird zur Frage gespeichert, nicht zur Person.** Festgehalten werden die Frage, die
   gelieferten Fundstellen und die Bewertung — nicht, wer bewertet hat.
2. **Sie fließt in die Suchqualitäts-Evaluierung ein.** Negativ bewertete Fragen sind die besten
   Kandidaten für den Golden-Datensatz, gegen den Änderungen am Abruf geprüft werden (siehe
   [search-quality-evaluation.md](./search-quality-evaluation.md)).
3. **Sie zeigt der Systemverwaltung Muster, keine Fälle.** Häufungen — eine Wissensbibliothek mit
   auffällig vielen Fehltreffern, ein Bestand, der veraltet ist — sind der eigentliche Ertrag.
4. **Sie verändert keine Rechte und kein Ranking im laufenden Betrieb.** Eine Rückmeldung, die
   unmittelbar auf die Trefferreihenfolge durchschlägt, wäre manipulierbar und nicht mehr
   nachvollziehbar. Der Weg führt über eine geprüfte Änderung, nicht über die Bewertung selbst.

### Die Grenze zur Mitbestimmung

Rückmeldungen dürfen **keinen personenbezogenen Auswertungspfad** eröffnen. Es gibt weder eine
Auswertung nach bewertender Person noch eine Rangfolge von Beschäftigten nach Zustimmung oder Menge
der Rückmeldungen; Auswertungen sind aggregiert und nicht auf Einzelne rückführbar. Eine Bewertung
ist eine Aussage über das System, nicht über die Person, die es bedient — wäre sie es, entstünde ein
zur Leistungs- und Verhaltenskontrolle geeignetes Instrument und die Rückmeldung würde schlicht
unterbleiben.

---

## REST-API

Die REST-API ist zugleich Zugang für eigene Anwendungen **und** die Grundlage aller weiteren Kanäle.
Beide Rollen fallen zusammen, und das ist beabsichtigt: Ein Kanal, der eine Sonderschnittstelle
bräuchte, würde eine zweite Rechteprüfung und eine zweite Fehlerbehandlung erzeugen.

### Eigenschaften

- **Spezifikation zuerst.** Die Schnittstelle ist in OpenAPI beschrieben; die verwendeten Datentypen
  werden daraus erzeugt (siehe [ADR-0006](../decisions/0006-openapi-dto-generation.md)). Änderungen
  beginnen an der Spezifikation, nicht am Code.
- **Anmeldung wie überall.** Zugang über den Verzeichnisdienst des Hauses; maschinelle Zugänge sind
  eigene, nachvollziehbare Identitäten mit eigenen Rechten und nicht das Konto einer Person
  (Einzelheiten und Stand unter [Authentifizierung und Zugang](#authentifizierung-und-zugang)).
- **Rechte der aufrufenden Person.** Ein Aufruf sieht genau die Wissensbibliotheken, die der
  aufrufenden Identität freigegeben sind. Die Prüfung sitzt in der Suche, nicht davor.
- **Belege im Antwortformat.** Fundstellen, Konfidenz und der durchsuchte Bereich sind Teil der
  Antwort, nicht eine gesonderte Abfrage.
- **Protokollierung.** Jeder Aufruf ist zurechenbar und erscheint im revisionssicheren Protokoll.
- **Grenzen.** Anfragekontingente schützen den Betrieb und begrenzen den Modellverbrauch.

### Was die Schnittstelle anbietet

Der folgende Katalog beschreibt den **Zweck** der Endpunkte — wozu man sie aufruft. Die formale
Beschreibung, also Pfade, Felder und Fehlerbilder, steht in der OpenAPI-Spezifikation des Backends
und wird hier nicht wiederholt; sie beantwortet das *Wie*, nicht das *Wozu*. Gruppiert ist nach
Zweck, nicht nach Pfad.

**Abfragen**

| Zweck | Endpunkt | Heute gebaut |
|---|---|---|
| Frage stellen und belegte Antwort erhalten — mit Fundstellen, Relevanz je Quelle, Kennzeichnung der tatsächlich zitierten Quellen und einer Gesprächskennung für Rückfragen; optional auf ausgewählte Arbeitsräume eingegrenzt | `POST /api/v1/query` | ja |
| Antwort auf eine Antwort geben (Bewertung, Fehltreffer melden) | — | nein — Zielbild, siehe [Rückmeldung](#rückmeldung-zur-antwortqualität) |

**Wissensbestände verwalten**

| Zweck | Endpunkt | Heute gebaut |
|---|---|---|
| Wissensbibliotheken anlegen, umbenennen, beschreiben, auflisten, löschen | `/api/v1/libraries` und `/api/v1/libraries/{id}` | ja |
| Bestand einer Wissensbibliothek einsehen — welche Dokumente sind drin, in welchem Indizierungsstand | `GET /api/v1/libraries/{id}/documents` | ja (ohne Oberfläche) |
| Lesezugriff auf eine Wissensbibliothek erteilen, einsehen und entziehen — Rechte hängen an der Bibliothek, nicht am einzelnen Dokument | `/api/v1/libraries/{id}/grants` | ja |
| Indizierung auslösen, wahlweise für den konfigurierten Bestand oder für eine angegebene Adresse; der Systemverwaltung vorbehalten | `POST /api/v1/indexing/trigger` | ja |
| Stand des letzten Indizierungslaufs abfragen — verarbeitet, übersprungen, fehlgeschlagen, mit Fehlertext | `GET /api/v1/indexing/status` | ja |
| Dokument hochladen und wieder entfernen | — | nein — Zielbild; Bestände kommen heute über Konnektoren und den Indizierungslauf |

**Arbeitsräume und Gruppen**

| Zweck | Endpunkt | Heute gebaut |
|---|---|---|
| Arbeitsräume anlegen, ändern, auflisten, löschen | `/api/v1/spaces` und `/api/v1/spaces/{id}` | ja |
| Mitglieder eines Arbeitsraums führen, Rolle ändern, Eigentum übergeben — damit ein Arbeitsraum beim Ausscheiden einer Person nicht verwaist | `/api/v1/spaces/{id}/members`, `/api/v1/spaces/{id}/transfer-ownership` | ja |
| Gruppen als Rechtesubjekt führen und ihre Mitglieder verwalten | `/api/v1/admin/groups` | ja |

**Systemverwaltung**

| Zweck | Endpunkt | Heute gebaut |
|---|---|---|
| Benutzende auflisten und die Systemrolle einer Person setzen | `/api/v1/admin/users` | ja |
| Abgleich mit dem Verzeichnisdienst des Hauses — zuerst als Probelauf ohne Wirkung, dann scharf, dazu der Stand des letzten Laufs | `/api/v1/admin/directory-sync` | ja |
| Anmeldeverfahren der Installation erfragen, bevor eine Anmeldung beginnt | `GET /api/v1/auth/config` | ja |
| Eigene Identität, Rollen und Zugehörigkeiten erfragen | `GET /api/v1/auth/me` | ja |
| Betriebsbereitschaft prüfen — für Lastverteiler und Betriebsüberwachung | `GET /api/health` | ja |

**Was der frühere Stand dieses Dokuments nannte und heute nicht existiert:** ein Endpunkt zum
Hochladen von Dokumenten, ein eigener Such-Endpunkt neben der Abfrage, das Abrufen eines einzelnen
Dokuments, das Auflisten der eigenen Uploads, ein Endpunkt für Rückmeldungen sowie
Sammelverarbeitung mehrerer Fragen in einem Aufruf. Ersatzlos entfallen sind die Endpunkte zum
**Teilen und Entteilen einzelner Dokumente über Workspace-Grenzen**: Zugriff wird an der
Wissensbibliothek erteilt, nicht am einzelnen Dokument — das Modell dahinter ist abgelöst (siehe
[spaces-and-assets.md](./spaces-and-assets.md)).

### Authentifizierung und Zugang

**Gebaut:**

- **Anmeldung über den Verzeichnisdienst des Hauses.** Die Schnittstelle nimmt ein Zugangsmerkmal
  entgegen, das der Identitätsanbieter ausgestellt hat, und prüft es gegen dessen Signaturschlüssel.
  Eine eigene Benutzer- und Passwortverwaltung gibt es nicht.
- **Ein Entwicklungsmodus ohne echte Prüfung**, der ausschließlich Entwicklungs- und Testumgebungen
  vorbehalten ist. Er muss ausdrücklich gewählt werden, und das System bricht den Start ab, wenn gar
  kein Verfahren gesetzt ist — eine Installation ist nie versehentlich offen (siehe
  [ADR-0005](../decisions/0005-authentication-strategy.md)).
- **Rechte der aufrufenden Person.** Der Aufruf sieht, was dieser Identität freigegeben ist; einzelne
  Endpunkte sind zusätzlich der Systemverwaltung vorbehalten.
- **Anfragekontingente.** Sie greifen je aufrufender Netzadresse und zusätzlich für die Installation
  insgesamt, in einem gleitenden Zeitfenster. Die Abfrage und das Auslösen der Indizierung haben getrennte, für
  sich gesetzte Kontingente — der Indizierungspfad ist deutlich enger begrenzt, weil ein einzelner
  Aufruf dort viel Arbeit auslöst. Alle Werte sind über Umgebungsvariablen einstellbar; die
  ausgelieferten Voreinstellungen und ihre Bedeutung stehen in [deployment.md](../deployment.md).
  Ein überschrittenes Kontingent führt zu einer klaren Ablehnung, nicht zu einer langsamen Antwort.

**Zielbild:**

- **Maschinelle Zugänge als eigene Identität.** Ein Fachverfahren, das OPAA abfragt, bekommt einen
  eigenen, benannten Zugang mit eigenen Rechten, eigener Gültigkeitsdauer und eigener
  Widerrufbarkeit — nicht das Konto einer Person und nicht ein Dauerschlüssel ohne Ablauf. Ein
  Zugang, der beim Ausscheiden einer Person weiterläuft, ist ein Prüfungsbefund.
- **Selbstverwaltung eigener Zugänge** in den persönlichen Einstellungen: anlegen, benennen,
  Gültigkeit sehen, widerrufen. Das Merkmal ist genau einmal sichtbar, danach nur noch sein Name.
- **Kontingente je Zugang statt nur je Netzadresse**, damit ein einzelnes Fachverfahren die
  Installation nicht für die Beschäftigten ausbremst. Hinter einem gemeinsamen Ausgangspunkt im
  Behördennetz teilen sich heute alle dieselbe Adresse — das Kontingent trifft dann die Falschen.

---

## Anbindung an Team-Chats

Der Ausbau bringt OPAA in den Chat, in dem ein Team ohnehin arbeitet: Frage im Kanal stellen,
Rückfragen im selben Strang, Antwort mit Fundstellen für alle sichtbar. Der Gewinn ist nicht
Bequemlichkeit, sondern Sichtbarkeit — eine beantwortete Frage steht dort, wo die nächste Person sie
findet.

### Was ein Chat-Kanal leisten muss

Nicht jede Plattform ist als Kanal geeignet. Verbindliche Bedingungen:

1. **Zuordenbare Identität.** Die Person hinter einer Nachricht muss eindeutig auf ein Konto im
   Verzeichnisdienst abbildbar sein. Ohne diese Zuordnung gibt es keine rechtebewusste Suche, sondern
   nur eine Vermutung.
2. **Darstellbare Belege.** Fundstellen mit Sprungziel müssen im Nachrichtenformat unterzubringen sein.
3. **Betrieb im Verantwortungsbereich des Hauses.** Der Weg einer Frage darf die Grenze nicht
   überschreiten, die für die zugrunde liegenden Daten gilt.
4. **Protokollierbarkeit.** Anfrage und Antwort müssen zurechenbar im Protokoll landen.

### Self-hosted Team-Chats

Tragend sind die selbst betriebenen Team-Chats, weil sie alle vier Bedingungen erfüllen können. Dazu
gehört ausdrücklich der **Chat-Baustein des souveränen Arbeitsplatzes**, der auf dem offenen
**Matrix**-Protokoll aufsetzt: Wo ein Haus diesen Arbeitsplatz einführt, ist der Chat bereits
vorhanden, im eigenen Betrieb und an den Verzeichnisdienst angebunden. Ein Kanal dorthin ist damit
eine Anbindung an vorhandene Infrastruktur und nicht die Einführung eines weiteren Systems.

Daneben stehen die verbreiteten, selbst betriebenen Team-Chat-Plattformen, die dieselben Bedingungen
erfüllen und in vielen Häusern bereits laufen.

### Die heute genannten Kanäle

Der bisherige Stand dieses Dokuments nannte eine Reihe von Chat-Plattformen. Sie werden hier **nicht
stillschweigend gestrichen** — die Entscheidung darüber steht aus:

| Kanal | Heute gebaut | Einordnung |
|---|---|---|
| Web-Oberfläche | ja | Fundament, Phase 1 |
| REST-API | ja | Fundament, Phase 1, Grundlage aller weiteren Kanäle |
| Chat-Baustein des souveränen Arbeitsplatzes (Matrix) | nein | Ausbau, Phase 3 — Zielbild in [#352](https://github.com/criew/opaa/issues/352) |
| Weitere self-hosted Team-Chats (Mattermost, Rocket.Chat) | nein | Ausbau, Phase 3 — Zielbild in [#352](https://github.com/criew/opaa/issues/352) |
| Slack | nein | offen — Zielbild in [#352](https://github.com/criew/opaa/issues/352) |
| Telegram | nein | offen — Zielbild in [#352](https://github.com/criew/opaa/issues/352) |
| Signal | nein | offen — Zielbild in [#352](https://github.com/criew/opaa/issues/352) |
| WhatsApp | nein | offen — Zielbild in [#352](https://github.com/criew/opaa/issues/352) |

**Zwei Feststellungen, die unabhängig von der Entscheidung gelten:**

- **Gebaut ist heute keiner dieser Chat-Kanäle.** Die Web-Oberfläche und die REST-API sind die einzigen
  realen Zugänge. Frühere Aufzählungen in diesem Repository beschrieben eine Absicht, keinen Zustand.
- **Für Kanäle über fremd betriebene Verbraucherdienste sind die Bedingungen 3 und 4 oben zu klären**,
  bevor über ihren Nutzen gesprochen wird. Diese Klärung ist Gegenstand von
  [#352](https://github.com/criew/opaa/issues/352); hier wird sie weder vorweggenommen noch
  präjudiziert.

---

## Erweiterungen für Office und Browser

Erweiterungen, die OPAA in Textverarbeitung, Tabellenkalkulation, Postfach oder Browser holen, sind
eine **spätere Option** (Phase 4) und ausdrücklich keine Zusage.

Der Bedarf ist plausibel: Ein Vermerk entsteht im Textprogramm, nicht in einem Chatfenster. Dagegen
steht der Aufwand — **je Erweiterung** eine eigene Erweiterungsschnittstelle, ein eigener
Freigabeprozess der jeweiligen Plattform, eine eigene Verteilung auf die Arbeitsplätze und eine eigene
Pflege bei jedem Plattformwechsel. Vier Erweiterungen sind vier Produkte, nicht ein Produkt mit vier
Ausgaben.

Daraus folgt die Reihenfolge: Erst wenn ein konkretes Einführungsvorhaben den Bedarf trägt und die
Zielumgebung feststeht, wird über die erste Erweiterung entschieden. Bis dahin bleibt der Zugang über
die Web-Oberfläche und die REST-API.

---

## Kanalübergreifende Eigenschaften

Was in einem Kanal gilt, gilt in allen. Andernfalls wäre der schwächste Kanal die tatsächliche
Sicherheitsgrenze des Systems.

| Eigenschaft | Regel |
|---|---|
| **Identität** | Anmeldung über den Verzeichnisdienst des Hauses; ein Kanal führt keine eigene Nutzerverwaltung |
| **Rechte** | Gefiltert wird über die lesbaren Wissensbibliotheken, bereits in der Suche; ein Agent liest immer mit den Rechten der aufrufenden Person |
| **Belege** | Fundstellen, Konfidenz und durchsuchter Bereich gehören zur Antwort |
| **Vorgaben** | Modell- und Werkzeugvorgaben der Systemverwaltung wirken in jedem Kanal; kein Kanal kann sie erweitern |
| **Protokoll** | Jede Anfrage und jede schreibende Aktion ist zurechenbar protokolliert |
| **Sichtbarkeit** | Was als Entwurf entsteht, bleibt beim Autor, bis er es ablegt — auch bei Nutzung über einen Chat-Kanal |

---

## Integrationspunkte

- **[spaces-and-assets.md](./spaces-and-assets.md)** — Arbeitsräume, Assets und die Trennung von
  Entwurf und Ablage, die jeder Kanal abbilden muss
- **[access-control.md](./access-control.md)** — Identität, Rollen und rechtebewusste Suche, an die
  jeder Kanal gebunden ist
- **[data-indexing-rag.md](./data-indexing-rag.md)** — Herkunft der Antworten und ihrer Fundstellen
- **[llm-integration.md](./llm-integration.md)** — Modellvorgaben, die in jedem Kanal gelten
- **[public-sector.md](./public-sector.md)** — Barrierefreiheit, Leichte Sprache und Amtssprache als
  Anforderungen an die Oberfläche
- **[deployment-infrastructure.md](./deployment-infrastructure.md)** — welche Kanäle in einer
  Installation ohne Netzanbindung überhaupt erreichbar sind

---

## Offene Fragen / Zukünftige Erweiterungen

- Welche Chat-Kanäle bleiben im Zielbild, welche entfallen, welche werden zur Option ohne Zusage?
  Entscheidung in [#352](https://github.com/criew/opaa/issues/352).
- Wie weit reicht ein maschineller Zugang: nur Abfragen, oder auch das Verwalten von Beständen? Ein
  Zugang, der indizieren darf, ist betrieblich etwas anderes als einer, der nur fragt.
- Wie lange werden Rückmeldungen aufbewahrt, und wer darf die aggregierte Auswertung sehen — die
  Systemverwaltung des Hauses, die Verantwortlichen einer Wissensbibliothek oder beide?
- Wie werden Rückfragen in einem Chat-Strang einem Arbeitsraum zugeordnet — über eine feste Zuordnung
  des Kanals oder über eine Angabe je Strang?
- Wie stellt ein Kanal mit knappem Nachrichtenformat mehrere Fundstellen dar, ohne dass die Antwort
  unlesbar wird?
- Ein Assistent für Bürgerinnen und Bürger und ein öffentlich eingebettetes Widget wären ein Kanal mit
  anderem Nutzerkreis und anderen Haftungsfragen. Sie sind Ausblick, nicht Fundament; siehe
  [#357](https://github.com/criew/opaa/issues/357) und [public-sector.md](./public-sector.md).
- Native Anwendungen für Mobilgeräte sind bewusst nicht vorgesehen (siehe [VISION.md](../VISION.md)).

---

## Erfolgs-Metriken

- Anteil der Antworten, die von der fragenden Person über die Fundstelle bis in das Quelldokument
  verfolgt werden — der Beleg wird benutzt, nicht nur angezeigt.
- Anteil der Beschäftigten mit Zugang, die OPAA regelmäßig nutzen, je Organisationseinheit und
  ausschließlich aggregiert.
- Anteil der über einen Kanal gestellten Fragen, die ohne Wechsel in die Web-Oberfläche beantwortet
  werden.
- Rückläufige Zahl der Fragen, die außerhalb zugelassener Werkzeuge gestellt werden — messbar nur
  indirekt, aber der eigentliche Zweck der Kanalvielfalt.
