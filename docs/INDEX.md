# OPAA Dokumentations-Index

Willkommen bei OPAA (Open Project AI Assistant)! Dieser Index hilft Ihnen, die vollständige Dokumentation zu navigieren.

## Erste Schritte (Hier anfangen!)

Neu bei OPAA? Mit diesen Dokumenten in dieser Reihenfolge beginnen:

1. **[README](../README.md)** — Was ist OPAA und warum ist es wichtig (2 Min. Lesezeit)
2. **[GETTING STARTED](./GETTING-STARTED.md)** — Welches Dokument basierend auf Ihrer Rolle zu lesen ist (5 Min. Lesezeit)
3. **[CONCEPTS](./CONCEPTS.md)** — Schlüsselkonzepte und Terminologie verstehen (10 Min. Lesezeit)
4. **[VISION](./VISION.md)** — Vollständige Produktvision und Architektur (15 Min. Lesezeit)

## Dokumentationsstruktur

### Kernvision & Strategie
- **[VISION.md](./VISION.md)** — Vollständige Produktvision, Anwendungsfälle, Architektur, Designprinzipien
- **[CONCEPTS.md](./CONCEPTS.md)** — Glossar und Erklärung der Schlüsselkonzepte
- **[GETTING-STARTED.md](./GETTING-STARTED.md)** — Leitfaden, was basierend auf Ihrer Rolle zu lesen ist

### Feature-Spezifikationen (Detailliert)

Jede Feature-Spezifikation enthält:
- **Motivation** — Warum dieses Feature existiert
- **Design** — Wie es aus Benutzerperspektive funktioniert
- **Konfiguration** — Was angepasst werden kann
- **Integrationspunkte** — Wie es sich mit anderen Features verbindet
- **Offene Fragen** — Zukünftige Überlegungen

#### 1. Benutzer-Frontends
**[`features/user-frontends.md`](./features/user-frontends.md)** — Wie Benutzer mit OPAA interagieren

- Web-Chat-Schnittstelle mit Dokument-Browser
- Chat-Plattform-Integrationen (Mattermost, RocketChat, Signal, Slack-kompatibel)
- REST-API für benutzerdefinierte Integrationen
- Einheitliche Authentifizierung & Berechtigungen über alle Schnittstellen

**Für:** Product Manager, UX-Designer, Frontend-Entwickler

---

#### 2. Daten-Indizierung & RAG
**[`features/data-indexing-rag.md`](./features/data-indexing-rag.md)** — Wie Dokumente indiziert und abgerufen werden

- 5 Datenquellen-Kategorien (Wikis, E-Mail, Dateisysteme, APIs, benutzerdefiniert)
- Benutzer-Dokument-Uploads (über Web-UI, Chat, REST-API)
- Dokumentenverarbeitungs-Pipeline (Extraktion → Chunking → Embedding → Speicherung)
- Speicher-Backend-Abstraktion (S3, Netzlaufwerk, lokales Dateisystem)
- Mehrere Vektor-Datenbank-Backends (Elasticsearch, PostgreSQL, Milvus, Cloud-Optionen)
- Retrieval & Ranking mit Konfidenz-Scoring
- Erweiterte Features (mehrsprachig, Caching, semantische Deduplizierung)

**Für:** Data Engineers, DevOps, Backend-Entwickler

---

#### 3. LLM-Integration
**[`features/llm-integration.md`](./features/llm-integration.md)** — Modellkonfiguration & Intelligenz

- OpenAI-kompatible API-Unterstützung (kein Vendor-Lock-in)
- Antwortgenerierungs-Pipeline mit Streaming
- Multi-Modell-Strategie (verschiedene Modelle für verschiedene Aufgaben)
- Embedding-Modell-Konfiguration
- Kostenoptimierungstechniken
- Sicherheit & verantwortungsvolle Nutzung
- Einfaches Anbieter-Wechseln

**Für:** ML Engineers, DevOps, kostenorientierte Organisationen

---

#### 4. Deployment & Infrastruktur
**[`features/deployment-infrastructure.md`](./features/deployment-infrastructure.md)** — Betrieb und Deployment

- On-Premises-Deployments (Kubernetes, Docker Compose, Bare Metal)
- Private Cloud (AWS, Azure, GCP)
- Konfigurationsmanagement (Umgebungsvariablen, YAML)
- Skalierungsanleitung (kleine → große Organisationen)
- Hochverfügbarkeit & Disaster Recovery
- Sicherheits-, Monitoring-, Backup-Strategien
- Zero-Downtime-Upgrades

**Für:** DevOps Engineers, Systemadministratoren, Plattform-Teams

---

#### 5. Spaces, Assets & Zugangskontrolle
**[`features/spaces-and-assets.md`](./features/spaces-and-assets.md)** — das Rechte- und Verteilungsmodell

- Spaces als Arbeitsräume; Assets als eigenständige, teilbare Objekte
- Assoziation gegen Enthaltensein — zwei Objektklassen mit unterschiedlicher Rechtelogik
- Wissensbibliotheken als Dokumentencontainer und Anker der rechtebewussten Suche
- Nutzer und Gruppen als Rechtesubjekt; Verteilungsstufen bis zum Fachbereich
- Chats und Artefakte im Space, Ableitungsleck und seine Behandlung
- Freigabekette beim Teilen eines Agenten
- Mitbestimmung und Personalvertretung

**Ergänzend: [`features/access-control.md`](./features/access-control.md)** — Systemverwaltung und Nachweis

- System-Admin-Rolle und Dokumentenfluss
- Benutzerverzeichnis- und Gruppensynchronisation, Offboarding
- Berechtigungsdurchsetzung zur Abfragezeit
- Audit-Logging, Compliance, DSGVO

**Für:** Sicherheits-Engineers, Compliance-Beauftragte, IT-Administratoren

---

### UI-Design-Entwürfe

- **[`design/README.md`](./design/README.md)** — UI-Design-Prototypen aus Google Stitch (HTML + Screenshots)
  - Chat-Schnittstelle, Dokument-Browser, Systemeinstellungen
  - Design-Thema: Dunkelmodus, `#137fec`, Inter-Schrift

### Architektur & Entscheidungen

- **[`decisions/0001-collaboration-workflow.md`](./decisions/0001-collaboration-workflow.md)** — Wie Menschen und KI an diesem Projekt zusammenarbeiten
- **[`AGENT-ORGANIZATION.md`](./AGENT-ORGANIZATION.md)** — Agenten-Rollen (PM, Entwickler, Reviewer, QA, Marketing), der Idee-bis-Merge-Workflow und Kollaborationsregeln

## Feature-Abhängigkeitskarte

Wie Features verbunden sind:

```
Benutzer-Frontends (Web, Chat, API)
    ↓
Orchestrierungsschicht
    ├→ Spaces, Assets & Zugangskontrolle (Berechtigungen prüfen)
    ├→ Daten-Indizierung & RAG (Dokumente abrufen)
    └→ LLM-Integration (Antwort generieren)

Daten-Indizierung & RAG
    ├→ Unterstützte Datenquellen (Konnektoren)
    ├→ Benutzer-Dokument-Uploads (über Frontends)
    └→ Dokumentenspeicher-Backends + Vektor-Datenbanken

Deployment & Infrastruktur
    └→ Alle anderen Features (Infrastruktur für alle)
```

## Lesepfade nach Rolle

### Ich bin Product Manager
→ Mit [VISION.md](./VISION.md) beginnen und alle Feature-Spezifikationen überfliegen (30 Min.)
→ Dann in Anwendungsfälle, Designprinzipien, Offene Fragen in jeder Spezifikation eintauchen

### Ich bin Backend-/Full-Stack-Entwickler
→ Zuerst [CONCEPTS.md](./CONCEPTS.md) lesen (10 Min.)
→ Dann [VISION.md](./VISION.md) Abschnitt Systemarchitektur (5 Min.)
→ Dann alle Feature-Spezifikationen in der Reihenfolge: [Benutzer-Frontends](./features/user-frontends.md) → [Daten-Indizierung](./features/data-indexing-rag.md) → [LLM-Integration](./features/llm-integration.md) → [Deployment](./features/deployment-infrastructure.md) → [Zugangskontrolle](./features/access-control.md)

### Ich bin DevOps-/Plattform-Engineer
→ [CONCEPTS.md](./CONCEPTS.md) lesen (10 Min.)
→ Dann [VISION.md](./VISION.md) Systemarchitektur (5 Min.)
→ Fokus auf: [Deployment & Infrastruktur](./features/deployment-infrastructure.md) und [Zugangskontrolle](./features/access-control.md)
→ Überfliegen: Daten-Indizierung, LLM-Integration für Integrationspunkte

### Ich bin Data-/ML-Engineer
→ [CONCEPTS.md](./CONCEPTS.md) lesen (10 Min.)
→ Dann auf [Daten-Indizierung & RAG](./features/data-indexing-rag.md) und [LLM-Integration](./features/llm-integration.md) fokussieren
→ Verstehen: Wie Embeddings funktionieren, Vektor-Datenbank-Auswahl, Modellauswahl

### Ich bin Sicherheits-/Compliance-Beauftragter
→ [CONCEPTS.md](./CONCEPTS.md) lesen (10 Min.)
→ Dann auf [Spaces, Assets & Zugangskontrolle](./features/spaces-and-assets.md) fokussieren
→ Auch lesen: Sicherheitsabschnitt in [Deployment & Infrastruktur](./features/deployment-infrastructure.md)
→ Prüfen: Datenverarbeitung in [Daten-Indizierung & RAG](./features/data-indexing-rag.md)

### Ich bin KI-/ML-Forscher
→ [CONCEPTS.md](./CONCEPTS.md) lesen (10 Min.)
→ Auf [LLM-Integration](./features/llm-integration.md) und [Daten-Indizierung & RAG](./features/data-indexing-rag.md) fokussieren
→ Prüfen: Offene Fragen in jeder Spezifikation für Forschungsmöglichkeiten

## Häufige Fragen

**Wo fange ich an?**
→ [GETTING-STARTED.md](./GETTING-STARTED.md) lesen

**Was ist RAG?**
→ Siehe [CONCEPTS.md](./CONCEPTS.md) — RAG-Abschnitt, dann [Daten-Indizierung & RAG](./features/data-indexing-rag.md)

**Wie deployee ich OPAA?**
→ [Deployment & Infrastruktur](./features/deployment-infrastructure.md) lesen

**Wie kontrolliere ich, wer was sieht?**
→ [Spaces, Assets & Zugangskontrolle](./features/spaces-and-assets.md) lesen

**Welche LLM-Modelle werden unterstützt?**
→ [LLM-Integration](./features/llm-integration.md) lesen — Abschnitt Unterstützte LLM-Anbieter

**Wie indiziere ich meine Dokumente?**
→ [Daten-Indizierung & RAG](./features/data-indexing-rag.md) lesen — Abschnitt Unterstützte Datenquellen

**Können Benutzer eigene Dokumente hochladen?**
→ Ja! [Daten-Indizierung & RAG](./features/data-indexing-rag.md) lesen — Abschnitt Benutzer-Dokument-Upload und [Spaces, Assets & Zugangskontrolle](./features/spaces-and-assets.md) — Abschnitt Dokumente und rechtebewusste Suche

**Kann ich mein eigenes LLM verwenden?**
→ Ja! Siehe [LLM-Integration](./features/llm-integration.md) — Abschnitt OpenAI-kompatible APIs
