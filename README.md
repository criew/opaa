# OPAA: Open Project AI Assistant

**Antworten mit Fundstelle. KI, die im ganzen Haus ankommt.**

OPAA ist die quelloffene KI-Plattform für die öffentliche Verwaltung: Sie macht das Wissen Ihres Hauses
belegbar befragbar, lässt Agenten wiederkehrende Aufgaben übernehmen und verteilt beides über die ganze
Organisation — im eigenen Rechenzentrum, ohne dass Daten das Haus verlassen.

## Drei Säulen

- **Wissen** — verstreutes Wissen aus Akten, Wikis, Postfächern und Dateiablagen wird befragbar und
  **nachweisbar**. Jede Aussage nennt ihre Fundstelle.
- **Agenten** — wiederkehrende Aufgaben werden automatisiert, von reinem Lesen bis zu schreibenden
  Aktionen mit Freigabe, immer an das Wissen des Hauses gebunden.
- **KI für Teams und Organisation** — gemeinsame Arbeitsräume, teilbare Agenten und Prompt-Bibliotheken,
  zentral gesetzte Modellvorgaben.

## Zwei Leitprinzipien

**Belegbarkeit.** Eine Auskunft in der Verwaltung ist keine Meinung — jemand steht mit seinem Namen dafür
gerade, und Jahre später muss nachvollziehbar sein, worauf sie sich stützte. OPAA bindet jede Aussage an
ihre Quelle und lässt sich für haftungskritische Zusammenhänge so schalten, dass ohne Beleg keine Antwort
ergeht.

**Verteilbarkeit.** Das reale Problem ist nicht, ob es ein gutes Modell gibt, sondern wie das Können von
wenigen zu allen kommt. OPAA macht Agenten, Prompts und Wissensbestände zu benannten, teilbaren,
versionierbaren Objekten, die über Freigabestufen von einer Person bis in die ganze Organisation wandern.

Die vollständige Begründung steht in
[ADR-0014](docs/decisions/0014-produktausrichtung-oeffentliche-verwaltung.md).

## Anwendungsfälle

Zehn ausgearbeitete Fälle stehen in [USE-CASES.md](docs/USE-CASES.md). Drei davon in Kurzform:

**Fachfrage zur Rechtslage belegt beantworten.** Die Antwort steckt in einem Schreiben, einem
Anwendungserlass, zwei Verfügungen und einer internen Arbeitsanweisung — verteilt über Netzlaufwerk,
Intranet und eine Ordnerstruktur, die niemand mehr vollständig kennt. OPAA nennt jede Aussage mit
Fundstelle und verweigert die Antwort, wo nichts belegt ist.

**Aus einem guten Agenten den Standard machen.** Ein Sachgebiet hat eine Arbeitsweise entwickelt, die
trägt. Statt sie herumzumailen, wird sie als geprüftes, freigegebenes Asset eine Stufe höher gereicht —
nachvollziehbar, wer wann welche Fassung freigegeben hat.

**KI zentral steuern statt lokal dulden.** Ohne zentrale Vorgabe basteln Einzelne private Prompts und
kopieren Amtsdaten in Verbraucherwerkzeuge. Einmal festgelegt, welche Modelle erlaubt sind und welche
Vorgaben gelten — alle erben es, Änderungen wirken sofort überall.

## Dokumentation

**Einstieg**

1. [GETTING-STARTED.md](docs/GETTING-STARTED.md) — welches Dokument für welche Rolle
2. [CONCEPTS.md](docs/CONCEPTS.md) — Begriffe und Glossar
3. [VISION.md](docs/VISION.md) — Nordstern, elf Themenbereiche, vier Phasen
4. [STATUS.md](docs/STATUS.md) — was davon heute gebaut ist
5. [INDEX.md](docs/INDEX.md) — vollständiger Dokumentationsindex

**Spezifikationen** — je Themenbereich eine, verlinkt aus der Übersichtstabelle in
[VISION.md](docs/VISION.md#die-elf-themenbereiche).

**Entscheidungen** — [docs/decisions/](docs/decisions/).

## Ausprobieren

Eine öffentliche Test- und Demo-Instanz der Demo „Stadt Rheinfurt" läuft unter
[opaa.ewerlin.com](https://opaa.ewerlin.com) — Anmeldung erforderlich, kein anonymer Zugang; die
Demo-Konten stehen in [docs/demo-walkthrough.md](docs/demo-walkthrough.md#nutzerkonten). Details zum
Betrieb in der [Deployment-Dokumentation](docs/deployment.md#öffentliche-testinstanz).

Die eigene, lokal installierbare Demo-Instanz „Stadt Rheinfurt" — fiktiver Verwaltungskorpus, vier
Demo-Nutzer mit Berechtigungsgrenze, ein ausformuliertes Drehbuch mit acht Vorführfragen — startet mit
einem Befehl: [docs/demo-walkthrough.md](docs/demo-walkthrough.md).

## Stand

OPAA ist im Aufbau. Das Fundament steht — Aufnahme und Indizierung von Dokumenten, Abfrage mit
Quellenangaben, Anmeldung über den Verzeichnisdienst, Spaces und Wissensbibliotheken mit eigenen Rechten,
Betrieb über Docker Compose. Das Space- und Asset-Modell und die Messbarkeit der Suchqualität sind im Bau.

Tragende Teile der Vision sind noch nicht gebaut, darunter der Zitierzwang, die hybride Suche, die Agenten
und das revisionssichere Protokoll. [STATUS.md](docs/STATUS.md) führt das je Themenbereich auf, ohne es zu
beschönigen.

## Technologie

Die Entscheidungen sind getroffen und in [ADR-0002](docs/decisions/0002-mvp-technology-stack.md)
begründet:

- **Backend:** Java 21, Spring Boot, Spring AI (Gradle, Kotlin DSL)
- **Datenbank:** PostgreSQL mit pgvector, Schemaverwaltung über Liquibase
- **Frontend:** React, TypeScript, Material UI, Vite
- **Modelle:** jede OpenAI-kompatible Schnittstelle. **Voreingestellt sind lokal betriebene Modelle** für
  Chat und Einbettung; ein anderer Anbieter ist konfigurierbar, aber nicht voreingestellt — und wer
  ihn wählt, gibt dessen Adresse ausdrücklich an, weil es dafür keine Voreinstellung gibt.
- **Betrieb:** Docker Compose; Kubernetes und Betrieb ohne Netzanbindung sind Ziel, aber noch nicht gebaut

## Mitwirken

[CONTRIBUTING.md](CONTRIBUTING.md) beschreibt den Weg von der Idee bis zum Merge. Menschen und KI-Agenten
verwenden denselben Workflow: dieselben Issues, dieselbe Branch-Benennung, dasselbe PR-Template.

**Für KI-Agenten:** [AGENTS.md](AGENTS.md) enthält die Projektkonventionen,
[docs/AGENT-ORGANIZATION.md](docs/AGENT-ORGANIZATION.md) die Rollen und den Arbeitsfluss.

Ohne unterzeichnete [Contributor License Agreement](CLA.md) kann kein Pull Request zusammengeführt werden.

## Lizenz

[GNU Affero General Public License v3.0](LICENSE). Der Funktionsumfang ist vollständig quelloffen.
