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

| Bereich | Zweck |
|---|---|
| **Fragen und Antworten** | Frage stellen, Antwort mit Fundstellen erhalten, zur Quellstelle springen, Konfidenz und durchsuchten Bereich sehen |
| **Arbeitsräume** | Chats und Artefakte eines Themas, Entwurf und Ablage getrennt (siehe [spaces-and-assets.md](./spaces-and-assets.md)) |
| **Wissen** | Dokumente einer Wissensbibliothek einsehen, hochladen, Indizierungsstand erkennen |
| **Assets** | Agenten, Prompt-Bibliotheken und Wissensbibliotheken anlegen, beschreiben, freigeben, finden |
| **Rückmeldung** | Antworten und Treffer bewerten; die Rückmeldung fließt in die Suchqualität ein (siehe [search-quality-evaluation.md](./search-quality-evaluation.md)) |
| **Systemverwaltung** | Modellvorgaben, Konnektoren, Rechte, Protokolle (siehe [access-control.md](./access-control.md) und [llm-integration.md](./llm-integration.md)) |

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

## REST-API

Die REST-API ist zugleich Zugang für eigene Anwendungen **und** die Grundlage aller weiteren Kanäle.
Beide Rollen fallen zusammen, und das ist beabsichtigt: Ein Kanal, der eine Sonderschnittstelle
bräuchte, würde eine zweite Rechteprüfung und eine zweite Fehlerbehandlung erzeugen.

### Eigenschaften

- **Spezifikation zuerst.** Die Schnittstelle ist in OpenAPI beschrieben; die verwendeten Datentypen
  werden daraus erzeugt (siehe [ADR-0006](../decisions/0006-openapi-dto-generation.md)). Änderungen
  beginnen an der Spezifikation, nicht am Code.
- **Anmeldung wie überall.** Zugang über den Verzeichnisdienst des Hauses; maschinelle Zugänge sind
  eigene, nachvollziehbare Identitäten mit eigenen Rechten und nicht das Konto einer Person.
- **Rechte der aufrufenden Person.** Ein Aufruf sieht genau die Wissensbibliotheken, die der
  aufrufenden Identität freigegeben sind. Die Prüfung sitzt in der Suche, nicht davor.
- **Belege im Antwortformat.** Fundstellen, Konfidenz und der durchsuchte Bereich sind Teil der
  Antwort, nicht eine gesonderte Abfrage.
- **Protokollierung.** Jeder Aufruf ist zurechenbar und erscheint im revisionssicheren Protokoll.
- **Grenzen.** Anfragekontingente schützen den Betrieb und begrenzen Kosten; die Werte sind eine
  Betriebsentscheidung und stehen nicht in dieser Spezifikation.

Die tatsächlich vorhandenen Endpunkte ergeben sich aus der OpenAPI-Spezifikation im Backend. Sie werden
hier bewusst nicht doppelt geführt — eine zweite Beschreibung veraltet und wird dann geglaubt.

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
