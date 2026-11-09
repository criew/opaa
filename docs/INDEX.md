# OPAA Dokumentations-Index

OPAA ist die souveräne, quelloffene KI-Plattform für die öffentliche Verwaltung. Dieser Index führt durch
die vollständige Dokumentation.

Die Dokumentation ist nach fünf Achsen sortiert — jedes Dokument gehört zu genau einer:

| Achse | Ort | Frage, die sie beantwortet |
|---|---|---|
| **Vision & Plan** | [VISION.md](./VISION.md), [ROADMAP.md](./ROADMAP.md), [USE-CASES.md](./USE-CASES.md) | Wohin soll es gehen? |
| **Spezifikation** | im Epic (GitHub), offene Ziele in der [Roadmap](./ROADMAP.md) | Wie soll sich ein Bereich im Zielbild verhalten? |
| **Entscheidungen** | [`decisions/`](./decisions/) | Was wurde verbindlich entschieden, und warum? |
| **Stand & Nachweis** | [`fortschritt/`](./fortschritt/) | Was ist heute tatsächlich gebaut — belegt? |
| **Handbuch** | [`handbuch/`](./handbuch/) | Wie installiere, betreibe und benutze ich das Gebaute? |

## Hier anfangen

1. **[README](../README.md)** — was OPAA ist und für wen
2. **[VISION.md](./VISION.md)** — Nordstern, die beiden Leitprinzipien, die elf Themenbereiche, die vier
   Phasen
3. **[USE-CASES.md](./USE-CASES.md)** — wie sich das im Arbeitsalltag anfühlt
4. **[Glossar](handbuch/referenz/glossar.md)** — Begriffe
5. **[Gesamtstand](./fortschritt/gesamtstand.md)** — was davon heute tatsächlich gebaut ist
6. **[GETTING-STARTED.md](./GETTING-STARTED.md)** — welche Lesepfade es je Publikum gibt

> **Vision und Stand nicht verwechseln.** VISION.md beschreibt das Zielbild, der
> [Gesamtstand](./fortschritt/gesamtstand.md) den Code. Wo eine Feature-Spezifikation etwas beschreibt,
> heißt das nicht, dass es gebaut ist.

---

## Ausrichtung und Strategie

- **[VISION.md](./VISION.md)** — Produktvision: Wissen, Agenten, KI für Teams und Organisation
- **[USE-CASES.md](./USE-CASES.md)** — Abläufe aus dem Verwaltungsalltag
- **[ROADMAP.md](./ROADMAP.md)** — was noch kommt, nach Phasen
- **[Gesamtstand](./fortschritt/gesamtstand.md)** — inventurbelegter Umsetzungsstand; die
  Zeitraumsberichte unter [`fortschritt/`](./fortschritt/) liefern den Nachweis je Stichtag
- **[decisions/0014-produktausrichtung-oeffentliche-verwaltung.md](./decisions/0014-produktausrichtung-oeffentliche-verwaltung.md)**
  — die Entscheidung, die die Ausrichtung trägt

---

## Die elf Themenbereiche

Die Gliederung folgt [VISION.md](./VISION.md). Je Bereich stehen Handbuch oder ADR für das Gebaute und die
Roadmap für die Ziele.

### A · Wissensschicht & Retrieval

**[`handbuch/referenz/wie-die-suche-arbeitet.md`](handbuch/referenz/wie-die-suche-arbeitet.md)** · **[Roadmap](./ROADMAP.md)** ·
**[ADR-0065](./decisions/0065-hybride-suche-reranking-und-diagnose.md)**

Belegvalidierung, Konfidenz und Quellenbindung · hybride Suche mit Reranking · erklärbares Chunking ·
Dokumentenverarbeitung von der Extraktion bis zur Vektorablage · Messbarkeit der Suchqualität gegen ein
Golden Dataset · Deep Research.

### B · Wissensquellen & Konnektoren

**[`handbuch/referenz/wie-indexierung-arbeitet.md`](handbuch/referenz/wie-indexierung-arbeitet.md)** · **[ADR-0069](./decisions/0069-texterkennung-gescannter-dokumente.md)** · **[Roadmap](./ROADMAP.md)**

Uploads und Konnektoren · Texterkennung gescannter Dokumente · selbst aktualisierende Wissensblöcke · Zeitpläne und ereignisbasierte
Aktualisierung · Zielbibliothek als Rechteanker, Quellrechte nur einschränkend und nur wo ein
Quellsystem sie belastbar liefert · Zuordnung einer Quelle zu genau einer Wissensbibliothek.

### C · Spaces, Assets & Verteilung

**[`handbuch/benutzer/space-mitglieder.md`](handbuch/benutzer/space-mitglieder.md)** · **[Roadmap](./ROADMAP.md)**

Spaces als Arbeitsräume, Assets als eigenständige Objekte · Zuordnung als harte Grenze gegen Enthaltensein ·
Wissensbibliotheken als Rechteanker · Verteilungsstufen und Sichtbarkeit · ein Katalog nur mit Lesbarem ·
Favoriten · Freigabekette, Versionierung, Rückruf · Chats und Artefakte · Mitbestimmung und
Personalvertretung. Entscheidung zu Katalog und Zuordnung:
[ADR-0039](./decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md).

### D · Agenten, Prompts & Werkzeuge

**[ADR-0070](./decisions/0070-werkzeugaufrufe-im-chat.md)** · **[Roadmap](./ROADMAP.md)**

Agenten als teilbare Pakete mit Aufgabenbeschreibung · geführtes Onboarding · Agenten-Prüfstand vor der
Freigabe · Prüfagenten · isolierte Ausführungsumgebung · Werkzeuge und MCP · schreibende Aktionen mit
menschlicher Freigabe.

### E · Modelle & zentrale Steuerung

**[`handbuch/betrieb/modell-endpunkte.md`](handbuch/betrieb/modell-endpunkte.md)** · **[ADR-0067](./decisions/0067-modellanbindung-und-verwaltete-chat-modelle.md)** ·
**[Roadmap](./ROADMAP.md)**

Jede OpenAI-kompatible Schnittstelle · lokal betriebene Modelle als Voreinstellung · Modellverwaltung ·
Modell-Policy als Obergrenze · Beschränkungen, die an den Daten hängen · Schutz vor Weitergabe
personenbezogener Daten.

### F · Identität, Rechte & Mandanten

**[`handbuch/administration/konten.md`](handbuch/administration/konten.md)** · **[`handbuch/referenz/rechte-matrix.md`](handbuch/referenz/rechte-matrix.md)** ·
**[Roadmap](./ROADMAP.md)**

Anmeldung über den Verzeichnisdienst · Kontenlebenszyklus über SCIM · Gruppen als Rechtesubjekt ·
Berechtigungsdurchsetzung zur Abfragezeit · Organisation als harte Mandantengrenze.

### G · Sicherheit, Nachweis & Prüfbarkeit

**[`handbuch/betrieb/nachvollziehbarkeit.md`](handbuch/betrieb/nachvollziehbarkeit.md)** · **[ADR-0068](./decisions/0068-mitbestimmungsfaehigkeit.md)** ·
**[Roadmap](./ROADMAP.md)**

Revisionssicheres Protokoll · Vollständigkeit nach DSGVO einschließlich Löschrecht und Datenexport · sichere
Voreinstellungen · Software-Stückliste und signierte Builds · C5-Fähigkeit · Mitbestimmungsfähigkeit.

### H · Monitoring, Kosten & Governance

**[Roadmap](./ROADMAP.md)**

Betriebsmetriken und Gesundheitsendpunkt · Grenzen je Nutzerin und Nutzer · Transparenz über Token- und
Sitzungskosten · Auswertung des KI-Rollouts, durchgehend aggregiert und ohne Personenbezug.

### I · Kanäle & Oberflächen

**[`handbuch/referenz/wie-die-suche-arbeitet.md`](handbuch/referenz/wie-die-suche-arbeitet.md)** · **[ADR-0072](./decisions/0072-live-transkript-ueber-websocket.md)** · **[Roadmap](./ROADMAP.md)**

Spracheingabe mit Live-Transkript · Web-Oberfläche mit Chat, Fundstellen und Dokumentenübersicht · REST-API für eigene Anbindungen · Anbindung
an self-hosted Team-Chats · einheitliche Anmeldung und Rechte über alle Kanäle.


### J · Betrieb & Deployment

**[`handbuch/betrieb/README.md`](handbuch/betrieb/README.md)** ·
**[`handbuch/betrieb/kubernetes.md`](handbuch/betrieb/kubernetes.md)** · **[Roadmap](./ROADMAP.md)**

Docker Compose · Kubernetes mit Hochverfügbarkeit · Betrieb ohne Netzanbindung · mandantenfähiger Betrieb
durch Rechenzentren · Konfiguration, Sicherung, Aktualisierung.

### K · Verwaltungs-Spezifika

**[Roadmap](./ROADMAP.md)** ·
**[`behoerden/README.md`](./behoerden/README.md)** ·
**[`handbuch/betrieb/nachvollziehbarkeit.md`](handbuch/betrieb/nachvollziehbarkeit.md)**

Leichte Sprache und Amtssprache · Barrierefreiheit nach BITV · Revisionssicherheit im Verwaltungssinn ·
Anbindung an die elektronische Akte und an Dokumentenmanagement · Unterlagen entlang der Behördenreise,
die mit dem Produkt mitziehen: Fakten-Manifest mit CI-Wächter, Update-Paket mit kumuliertem Delta und
Stufenvorschlag je Release, Muster-Dienstvereinbarung mit statischen Anlagen.

---

## Weitere Dokumentation

### Handbuch — das gebaute Produkt benutzen

- **[`handbuch/README.md`](./handbuch/README.md)** — Einstieg: Zielgruppen, was OPAA ist, Aufbau
  und Lesewege. Die Ordner folgen den Kapiteln:
  - Benutzer ([`benutzer/`](handbuch/benutzer/README.md)): eine Seite je Aufgabe für jedes Konto,
    von der ersten Frage bis zu Spaces, Bibliotheken und Gruppen, dazu
    [`konnektoren/`](handbuch/benutzer/konnektoren/README.md)
  - Administration ([`administration/`](handbuch/administration/README.md)): eine Seite je Aufgabe
    der Systemverwaltung und der Revision
  - Referenz ([`referenz/`](handbuch/referenz/README.md)): Rechte-Matrix, Glossar,
    Konfiguration, Formate, Metadaten, wie Indexierung und Suche arbeiten
  - Betrieb ([`betrieb/`](handbuch/betrieb/README.md), Bausteine und Wegweiser) mit
    [`images-und-laufzeit.md`](handbuch/betrieb/images-und-laufzeit.md),
    [`netz-und-sicherheit.md`](handbuch/betrieb/netz-und-sicherheit.md) (Härtung),
    [`sicherung-und-wiederherstellung.md`](handbuch/betrieb/sicherung-und-wiederherstellung.md),
    [`aktualisierung.md`](handbuch/betrieb/aktualisierung.md),
    [`ueberwachung-und-fehlerbehebung.md`](handbuch/betrieb/ueberwachung-und-fehlerbehebung.md),
    [`docker-compose.md`](handbuch/betrieb/docker-compose.md),
    [`kubernetes.md`](handbuch/betrieb/kubernetes.md) (Helm-Chart),
    [`konfiguration.md`](handbuch/referenz/konfiguration.md) (Umgebungsvariablen, Grenzwerte),
    [`modelle.md`](handbuch/betrieb/modell-endpunkte.md),
    [`authentifizierung.md`](handbuch/betrieb/authentifizierung.md)
  - Regeln für Schreibende: [`handbuch/AGENTS.md`](./handbuch/AGENTS.md)
- **[`sbom.md`](./sbom.md)** — Software Bill of Materials: Image-Attestierungen,
  CycloneDX-CI-Artefakte für Backend und Frontend und die Lizenzprüfung der Abhängigkeiten
- **[`opencode.md`](./opencode.md)** — Ablage auf openCode: Bedingungen der Plattform, Spiegel des
  Repositorys, `publiccode.yml` und die Schritte der Einrichtung
- **[`cve-scanning.md`](./cve-scanning.md)** — CVE-Erkennung: Dependency Graph, Dependabot-Alerts,
  Trivy-Image-Scan und die Triage von Image-Alerts
- **[`security-pruefung.md`](./security-pruefung.md)** — Sicherheitsprüfung des eigenen Codes:
  Prüfrhythmus je Pull Request, nächtlich, wöchentlich und im Tiefen-Audit; was es nicht gibt
- **[`SECURITY.md`](../SECURITY.md)** — Sicherheitsrichtlinie: vertraulicher Meldeweg für
  Sicherheitslücken, unterstützte Versionen und Ablauf bis zum Security Advisory

### Unterlagen für Behörden

- **[`behoerden/README.md`](./behoerden/README.md)** — Vorlagen für Behörden entlang des
  Einführungswegs (Entscheidung, Markterkundung, Vergabe, Datenschutz, Mitbestimmung,
  Informationssicherheit, Betrieb), geschrieben für Lesende in der Behörde; Regeln für Kopfvermerk,
  Haftungshinweis und Faktenblatt-Platzhalter in [`behoerden/AGENTS.md`](./behoerden/AGENTS.md)

### Architekturentscheidungen

- **[`decisions/`](./decisions/)** — alle ADRs. Einstiege:
  - [0001](./decisions/0001-collaboration-workflow.md) — Zusammenarbeit von Menschen und KI im Projekt
  - [0002](./decisions/0002-mvp-technology-stack.md) — Technologiestapel
  - [0005](./decisions/0005-authentication-strategy.md) — Anmeldung und getrennte Auth-Profile
  - [0006](./decisions/0006-openapi-dto-generation.md) — DTOs aus der OpenAPI-Spezifikation
  - [0010](./decisions/0010-ein-chunk-invariante-evaluierungskorpus.md) bis
    [0013](./decisions/0013-fehlerkriterium-retrieval-regression.md) — Evaluierung der Suchqualität
  - [0051](./decisions/0051-architekturvertrag-des-backends.md) — Architekturvertrag des Backends:
    Regeln, Prüfinstanzen und akzeptierte Abweichungen
  - [0052](./decisions/0052-compliance-manifest.md) — Compliance-Manifest: Ablage, Schema,
    Fristen und Prüfsummen der System-Prompts
  - [0053](./decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md) — Mehrinstanzbetrieb: Aktiv/Aktiv in
    Stufen, koordiniert über Postgres; ersetzt ADR-0021

### Demo und Vorführung

- **[`../demo/README.md`](../demo/README.md)** — Anwenderdokumentation: Installation mit einem
  Befehl, Nutzerkonten mit Anmeldedaten, öffentliche Instanz, Korpus-Aktualisierung, Seed-Mechanismus
- **[`market/demo-drehbuch.md`](./market/demo-drehbuch.md)** — ausformuliertes Vorführ-Drehbuch mit
  acht Fragen und acht Vorführschritten

### Gestaltung

- **[`design/README.md`](./design/README.md)** — Gestaltungsleitlinien, Barrierefreiheit und UI-Prüfliste

### Projekt und Mitarbeit

- **[`AGENT-ORGANIZATION.md`](./AGENT-ORGANIZATION.md)** — Agenten-Rollen, Idee-bis-Merge-Workflow,
  Kollaborationsregeln
- **[`../CONTRIBUTING.md`](../CONTRIBUTING.md)** — Leitfaden für Beitragende
- **[`../AGENTS.md`](../AGENTS.md)** — Anweisungen für KI-Agenten
- **[`../backend/AGENTS.md`](../backend/AGENTS.md)** — Backend-Regeln für KI-Agenten
  (Abhängigkeitsverwaltung, API-/Mapper-Konvention, Spring-Testkontexte, Datenbankmigrationen, Fakten-Manifest)
- **[`../backend/docs/`](../backend/docs/)** — Nachschlagewissen dazu:
  [Register des prozesslokalen Zustands](../backend/docs/prozesslokaler-zustand.md),
  [Spring-Testkontexte](../backend/docs/testkontexte.md), [Datenbankmigrationen](../backend/docs/migrationen.md),
  [Fakten-Manifest nachpflegen](../backend/docs/compliance-manifest.md)
- **[`entwicklung/build-und-ci.md`](./entwicklung/build-und-ci.md)** — Befehle außerhalb des täglichen
  Builds: Integrationstest-Suiten, Helm-Chart-Prüfungen, Kubernetes-Erprobungsaufbau, E2E-Suite,
  Image-Rauchtest

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

**Was heißt Belegbarkeit, Verteilbarkeit, Belegvalidierung?**
→ [VISION.md](./VISION.md), Abschnitt „Zwei Leitprinzipien"

**Was ist heute wirklich gebaut?**
→ [Gesamtstand](./fortschritt/gesamtstand.md) — und nur dort

**Wie stelle ich sicher, dass niemand sieht, was er nicht sehen darf?**
→ [Berechtigungen](handbuch/referenz/rechte-matrix.md)

**Läuft OPAA ohne Internetverbindung?**
→ Ja, das ist ein vorgesehenes Szenario. Siehe
[Images und Laufzeit](handbuch/betrieb/images-und-laufzeit.md)

**Welche Modelle werden unterstützt?**
→ Jede OpenAI-kompatible Schnittstelle, einschließlich lokal betriebener Modelle. Siehe
[Modelle](handbuch/betrieb/modell-endpunkte.md)

**Können mehrere Häuser dieselbe Installation nutzen?**
→ Ja, die Organisation ist die harte Mandantengrenze. Siehe
[ADR-0014, Entscheidung 15](./decisions/0014-produktausrichtung-oeffentliche-verwaltung.md#15-nachweis-der-organisationsgrenze)

**Was bedeutet „C5-fähig"?**
→ Auf die Prüfung des Betreibers ausgelegt, ausdrücklich **nicht** zertifiziert. Siehe
[VISION.md](./VISION.md#häufige-fragen), Häufige Fragen

**Was heißt das für die Personalvertretung?**
→ [ADR-0068](./decisions/0068-mitbestimmungsfaehigkeit.md) und
[Behördenunterlagen](./behoerden/README.md)

**Kann ich eigene Dokumente hochladen?**
→ Ja. Siehe [Wissensbibliotheken](handbuch/referenz/wie-indexierung-arbeitet.md)
