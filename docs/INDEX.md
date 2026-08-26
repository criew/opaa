# OPAA Dokumentations-Index

OPAA ist die souveräne, quelloffene KI-Plattform für die öffentliche Verwaltung. Dieser Index führt durch
die vollständige Dokumentation.

Die Dokumentation ist nach fünf Achsen sortiert — jedes Dokument gehört zu genau einer:

| Achse | Ort | Frage, die sie beantwortet |
|---|---|---|
| **Vision & Plan** | [VISION.md](./VISION.md), [USE-CASES.md](./USE-CASES.md), [CONCEPTS.md](./CONCEPTS.md) | Wohin soll es gehen, in welchen Begriffen? |
| **Spezifikation** | [`features/`](./features/) | Wie soll sich ein Bereich im Zielbild verhalten? |
| **Entscheidungen** | [`decisions/`](./decisions/) | Was wurde verbindlich entschieden, und warum? |
| **Ideen & Recherche** | [`discussions/`](./discussions/) | Was ist erst erörtert, noch nicht entschieden? |
| **Stand & Nachweis** | [`fortschritt/`](./fortschritt/) | Was ist heute tatsächlich gebaut — belegt? |
| **Handbuch** | [`handbuch/`](./handbuch/) | Wie installiere, betreibe und benutze ich das Gebaute? |

## Hier anfangen

1. **[README](../README.md)** — was OPAA ist und für wen
2. **[VISION.md](./VISION.md)** — Nordstern, die beiden Leitprinzipien, die elf Themenbereiche, die vier
   Phasen
3. **[USE-CASES.md](./USE-CASES.md)** — wie sich das im Arbeitsalltag anfühlt
4. **[CONCEPTS.md](./CONCEPTS.md)** — Begriffe und Glossar
5. **[Gesamtstand](./fortschritt/gesamtstand.md)** — was davon heute tatsächlich gebaut ist
6. **[GETTING-STARTED.md](./GETTING-STARTED.md)** — welche Lesepfade es je Publikum gibt

> **Vision und Stand nicht verwechseln.** VISION.md beschreibt das Zielbild, der
> [Gesamtstand](./fortschritt/gesamtstand.md) den Code. Wo eine Feature-Spezifikation etwas beschreibt,
> heißt das nicht, dass es gebaut ist.

---

## Ausrichtung und Strategie

- **[VISION.md](./VISION.md)** — Produktvision: Wissen, Agenten, KI für Teams und Organisation
- **[USE-CASES.md](./USE-CASES.md)** — Abläufe aus dem Verwaltungsalltag
- **[CONCEPTS.md](./CONCEPTS.md)** — Glossar der tragenden Begriffe
- **[Gesamtstand](./fortschritt/gesamtstand.md)** — inventurbelegter Umsetzungsstand; die
  Zeitraumsberichte unter [`fortschritt/`](./fortschritt/) liefern den Nachweis je Stichtag
- **[`fortschritt/tagesreport.md`](./fortschritt/tagesreport.md)** — daneben der ticketbasierte
  Tagestakt: täglich generierter Report über abgeschlossene Vorgänge und gemergte Pull Requests,
  als Atom-Feed abonnierbar
- **[decisions/0014-produktausrichtung-oeffentliche-verwaltung.md](./decisions/0014-produktausrichtung-oeffentliche-verwaltung.md)**
  — die Entscheidung, die die Ausrichtung trägt

---

## Die elf Themenbereiche

Die Gliederung folgt [VISION.md](./VISION.md). Zu jedem Bereich gehört genau eine zuständige
Spezifikation — oder zwei, wo der Bereich zwei Fragen beantwortet.

### A · Wissensschicht & Retrieval

**[`features/data-indexing-rag.md`](./features/data-indexing-rag.md)** ·
**[`features/search-quality-evaluation.md`](./features/search-quality-evaluation.md)**

Zitierzwang, Konfidenz und Quellenbindung · hybride Suche mit Reranking · erklärbares Chunking ·
Dokumentenverarbeitung von der Extraktion bis zur Vektorablage · Messbarkeit der Suchqualität gegen ein
Golden Dataset · Deep Research.

### B · Wissensquellen & Konnektoren

**[`features/knowledge-sources.md`](./features/knowledge-sources.md)**

Uploads und Konnektoren · selbst aktualisierende Wissensblöcke · Zeitpläne und ereignisbasierte
Aktualisierung · Spiegelung der Rechte aus dem Quellsystem · Zuordnung einer Quelle zu genau einer
Wissensbibliothek.

### C · Spaces, Assets & Verteilung

**[`features/spaces-and-assets.md`](./features/spaces-and-assets.md)**

Spaces als Arbeitsräume, Assets als eigenständige Objekte · Assoziation gegen Enthaltensein ·
Wissensbibliotheken als Rechteanker · Verteilungsstufen bis zum organisationsweiten Katalog · Freigabekette,
Versionierung, Rückruf · Chats und Artefakte · Mitbestimmung und Personalvertretung.

### D · Agenten, Prompts & Werkzeuge

**[`features/agents-and-tools.md`](./features/agents-and-tools.md)**

Agenten als teilbare Pakete mit Aufgabenbeschreibung · geführtes Onboarding · Agenten-Prüfstand vor der
Freigabe · Prüfagenten · isolierte Ausführungsumgebung · Werkzeuge und MCP · schreibende Aktionen mit
menschlicher Freigabe.

### E · Modelle & zentrale Steuerung

**[`features/llm-integration.md`](./features/llm-integration.md)**

Jede OpenAI-kompatible Schnittstelle · lokal betriebene Modelle als Voreinstellung · Modellverwaltung ·
Modell-Policy als Obergrenze · Beschränkungen, die an den Daten hängen · Schutz vor Weitergabe
personenbezogener Daten.

### F · Identität, Rechte & Mandanten

**[`features/access-control.md`](./features/access-control.md)**

Anmeldung über den Verzeichnisdienst · Kontenlebenszyklus über SCIM · Gruppen als Rechtesubjekt ·
Berechtigungsdurchsetzung zur Abfragezeit · Organisation als harte Mandantengrenze.

### G · Sicherheit, Nachweis & Prüfbarkeit

**[`features/security-and-compliance.md`](./features/security-and-compliance.md)**

Revisionssicheres Protokoll · Vollständigkeit nach DSGVO einschließlich Löschrecht und Datenexport · sichere
Voreinstellungen · Software-Stückliste und signierte Builds · C5-Fähigkeit · Mitbestimmungsfähigkeit.

### H · Monitoring, Kosten & Governance

**[`features/monitoring-and-governance.md`](./features/monitoring-and-governance.md)**

Betriebsmetriken und Gesundheitsendpunkt · Grenzen je Nutzerin und Nutzer · Transparenz über Token- und
Sitzungskosten · Auswertung des KI-Rollouts, durchgehend aggregiert und ohne Personenbezug.

### I · Kanäle & Oberflächen

**[`features/user-frontends.md`](./features/user-frontends.md)**

Web-Oberfläche mit Chat, Fundstellen und Dokumentenübersicht · REST-API für eigene Anbindungen · Anbindung
an self-hosted Team-Chats · einheitliche Anmeldung und Rechte über alle Kanäle.

### J · Betrieb & Deployment

**[`features/deployment-infrastructure.md`](./features/deployment-infrastructure.md)** ·
ergänzend **[`handbuch/deployment.md`](./handbuch/deployment.md)**

Docker Compose · Kubernetes mit Hochverfügbarkeit · Betrieb ohne Netzanbindung · mandantenfähiger Betrieb
durch Rechenzentren · Konfiguration, Sicherung, Aktualisierung.

### K · Verwaltungs-Spezifika

**[`features/public-sector.md`](./features/public-sector.md)**

Leichte Sprache und Amtssprache · Barrierefreiheit nach BITV · Revisionssicherheit im Verwaltungssinn ·
Anbindung an die elektronische Akte und an Dokumentenmanagement.

---

## Weitere Dokumentation

### Handbuch — das gebaute Produkt benutzen

- **[`handbuch/deployment.md`](./handbuch/deployment.md)** — Installation und Betrieb der vorhandenen
  Software: Docker Compose, Umgebungsvariablen, Härtung, öffentliche Testinstanz
- **[`handbuch/demo-walkthrough.md`](./handbuch/demo-walkthrough.md)** — Anwenderdokumentation der
  Demo-Instanz: Installation mit einem Befehl, Nutzerkonten, Vorführ-Drehbuch

### Architekturentscheidungen

- **[`decisions/`](./decisions/)** — alle ADRs. Einstiege:
  - [0001](./decisions/0001-collaboration-workflow.md) — Zusammenarbeit von Menschen und KI im Projekt
  - [0002](./decisions/0002-mvp-technology-stack.md) — Technologiestapel
  - [0005](./decisions/0005-authentication-strategy.md) — Anmeldung und getrennte Auth-Profile
  - [0006](./decisions/0006-openapi-dto-generation.md) — DTOs aus der OpenAPI-Spezifikation
  - [0010](./decisions/0010-ein-chunk-invariante-evaluierungskorpus.md) bis
    [0013](./decisions/0013-fehlerkriterium-retrieval-regression.md) — Evaluierung der Suchqualität

### Demo und Vorführung

- **[`handbuch/demo-walkthrough.md`](./handbuch/demo-walkthrough.md)** — Anwenderdokumentation:
  Installation mit einem Befehl, Nutzerkonten mit Anmeldedaten, ausformuliertes Drehbuch mit acht
  Vorführfragen, Korpus-Aktualisierung
- **[`features/demo-instance.md`](./features/demo-instance.md)** — Konzept: Demo-Instanz „Stadt
  Rheinfurt", fiktiver Verwaltungskorpus, Bibliotheken je Konnektortyp, Demo-Nutzer mit
  Berechtigungsgrenze, Seed-Mechanismus

### Ideen und Recherche

- **[`discussions/`](./discussions/)** — offene Erörterungen und Recherchen, noch nicht entschieden;
  darunter **[`discussions/GraphRAG.md`](./discussions/GraphRAG.md)** — Wissensgraph als Ergänzung des
  Vektor-Retrievals: Funktionsweise, Vergleich quelloffener Implementierungen, Betriebsaspekte.
  Entscheidungsgrundlage, keine getroffene Entscheidung

### Oberflächenentwürfe

- **[`design/README.md`](./design/README.md)** — Entwürfe für Chat, Dokumentenübersicht und Einstellungen

### Projekt und Mitarbeit

- **[`AGENT-ORGANIZATION.md`](./AGENT-ORGANIZATION.md)** — Agenten-Rollen, Idee-bis-Merge-Workflow,
  Kollaborationsregeln
- **[`../CONTRIBUTING.md`](../CONTRIBUTING.md)** — Leitfaden für Beitragende
- **[`../AGENTS.md`](../AGENTS.md)** — Anweisungen für KI-Agenten
- **[`renovate.md`](./renovate.md)** — selbst betriebene Abhängigkeits-Updates: Betrieb, Token-Zuschnitt,
  Dry-Run, Fehlerbilder

### Überholt

- **[`features/document-sharing.md`](./features/document-sharing.md)** — raumübergreifendes Dokument-Teilen.
  Durch das Asset-Modell in Bereich C gegenstandslos, nur noch als Historie erhalten

---

## Wie die Bereiche zusammenhängen

Nicht alle elf Bereiche stehen nebeneinander. Einige tragen, einige setzen auf, zwei liegen quer über
allem.

```
I  Kanäle & Oberflächen  (Web, REST-API, Team-Chats)
        │  Frage geht ein
        ▼
   ORCHESTRIERUNG
        ├─► F  Identität & Rechte      — wer fragt, was darf er lesen
        ├─► C  Spaces & Assets         — welcher Suchbereich, welches Asset
        ├─► A  Wissensschicht          — Fundstellen holen, ranken, belegen
        ├─► E  Modelle                 — womit formuliert wird, in welchen Grenzen
        └─► D  Agenten & Werkzeuge     — wenn nicht gefragt, sondern erledigt wird

A  Wissensschicht
        ▲ speist sich aus
        └─ B  Wissensquellen & Konnektoren  ─► Wissensbibliotheken (Rechteanker in C)

G  Sicherheit & Nachweis   ── quer über allem: protokolliert jede Handlung
H  Monitoring & Governance ── quer über allem: aggregierte Auswertung, keine Person
K  Verwaltungs-Spezifika   ── wirkt in A (Amtssprache), I (Barrierefreiheit), B (e-Akte)
J  Betrieb & Deployment    ── trägt alles übrige
```

**Die drei Abhängigkeiten, die wirklich binden:**

1. **C vor A.** Die Wissensbibliothek ist der Rechteanker; ohne sie ist rechtebewusste Suche nicht möglich.
2. **F vor C.** Ohne Personen und Gruppen aus dem Verzeichnisdienst gibt es kein Rechtesubjekt.
3. **A vor D.** Ein Agent ohne belegte Wissensbindung erfüllt keines der beiden Leitprinzipien.

Den tatsächlichen Stand führt der [Gesamtstand](./fortschritt/gesamtstand.md).

---

## Häufige Fragen

**Wo fange ich an?**
→ [GETTING-STARTED.md](./GETTING-STARTED.md), Lesepfad nach Publikum

**Was heißt Belegbarkeit, Verteilbarkeit, Zitierzwang?**
→ [CONCEPTS.md](./CONCEPTS.md), Abschnitt „Die beiden Leitbegriffe"

**Was ist heute wirklich gebaut?**
→ [Gesamtstand](./fortschritt/gesamtstand.md) — und nur dort

**Wie stelle ich sicher, dass niemand sieht, was er nicht sehen darf?**
→ [`features/spaces-and-assets.md`](./features/spaces-and-assets.md) und
[`features/access-control.md`](./features/access-control.md)

**Läuft OPAA ohne Internetverbindung?**
→ Ja, das ist ein vorgesehenes Szenario. Siehe
[`features/deployment-infrastructure.md`](./features/deployment-infrastructure.md)

**Welche Modelle werden unterstützt?**
→ Jede OpenAI-kompatible Schnittstelle, einschließlich lokal betriebener Modelle. Siehe
[`features/llm-integration.md`](./features/llm-integration.md)

**Können mehrere Häuser dieselbe Installation nutzen?**
→ Ja, die Organisation ist die harte Mandantengrenze. Siehe
[`features/access-control.md`](./features/access-control.md)

**Was bedeutet „C5-fähig"?**
→ Auf die Prüfung des Betreibers ausgelegt, ausdrücklich **nicht** zertifiziert. Siehe
[CONCEPTS.md](./CONCEPTS.md) und
[`features/security-and-compliance.md`](./features/security-and-compliance.md)

**Was heißt das für die Personalvertretung?**
→ [CONCEPTS.md](./CONCEPTS.md), Abschnitt „Mitbestimmungsfähigkeit", und
[`features/spaces-and-assets.md`](./features/spaces-and-assets.md)

**Kann ich eigene Dokumente hochladen?**
→ Ja. Siehe [`features/knowledge-sources.md`](./features/knowledge-sources.md) und
[`features/data-indexing-rag.md`](./features/data-indexing-rag.md)
