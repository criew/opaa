# OPAA: Open Project AI Assistant

**Ein unternehmenstauglicher, selbst gehosteter KI-Assistent, der Ihr organisationales Wissen in sofortige Antworten verwandelt.**

OPAA transformiert verstreutes Wissen — gespeichert in Wikis, E-Mails, Dokumenten und Dateien — in eine einheitliche Intelligenzschicht. Stellen Sie Fragen in natürlicher Sprache und erhalten Sie quellengestützte Antworten aus Ihrer gesamten Wissensbasis, unabhängig davon, wo diese gespeichert ist.

## Was ist OPAA?

OPAA ist ein **quelloffenes RAG-System (Retrieval-Augmented Generation)** für Organisationen, die Folgendes benötigen:
- 🔍 **Einheitliche Suche** über Confluence, E-Mail, Dateisysteme und benutzerdefinierte Quellen
- 🧠 **Intelligentes Q&A** mit konfigurierbaren LLM-Anbietern (OpenAI, Anthropic, lokale Modelle)
- 🏢 **On-Premises-Betrieb** mit vollständiger Datensouveränität
- 🔐 **Multi-Team-Unterstützung** mit Workspace-Isolation und feingranularen Berechtigungen
- ⚙️ **Flexible Architektur** — Datenbanken, LLMs und Datenquellen austauschen ohne Codeänderungen

## Hauptfunktionen

- **Mehrere Benutzeroberflächen:** Web-Chat, Chat-Bot-Integrationen (Mattermost, RocketChat, Slack, Telegram, Signal, WhatsApp), REST-API
- **Flexible Datenquellen:** Confluence, Jira, E-Mail-Archive, Dateisysteme, Cloud-Speicher, Issue-Tracker, benutzerdefinierte APIs
- **Konfigurierbare LLM-Anbieter:** OpenAI, Anthropic, Open-Source-Modelle oder lokale Deployments
- **Mehrere Vektordatenbanken:** Elasticsearch, PostgreSQL + pgvector, Milvus oder Cloud-Optionen
- **Workspace-Isolation:** Multi-Team-Unterstützung mit rollenbasierter Zugriffskontrolle
- **Audit & Compliance:** Vollständige Audit-Protokollierung, Berechtigungsdurchsetzung, DSGVO/HIPAA-Unterstützung
- **Enterprise-Deployment:** Kubernetes, Docker Compose, AWS, Azure, GCP oder Offline-Umgebungen

## Schnellstart

**Dokumentation lesen:**

1. **Neu bei OPAA?** Hier anfangen: [GETTING-STARTED.md](docs/GETTING-STARTED.md) (5 Min.)
2. **Schlüsselkonzepte erlernen:** [CONCEPTS.md](docs/CONCEPTS.md) (10 Min.)
3. **Die vollständige Vision:** [VISION.md](docs/VISION.md) (15 Min.)
4. **Tiefer in Features eintauchen:** Siehe [INDEX.md](docs/INDEX.md) für rollenbasierte Lesepfade

## Dokumentation

Vollständige Dokumentation in `docs/`:

### Kernvision & Konzepte
- **[VISION.md](docs/VISION.md)** — Vollständige Produktvision, Anwendungsfälle, Architektur, Prinzipien
- **[CONCEPTS.md](docs/CONCEPTS.md)** — Glossar und Erklärung der Schlüsselkonzepte
- **[GETTING-STARTED.md](docs/GETTING-STARTED.md)** — Anleitung zur richtigen Dokumentation
- **[INDEX.md](docs/INDEX.md)** — Vollständiger Dokumentationsindex mit rollenbasierten Lesepfaden

### Feature-Spezifikationen
Detaillierte Spezifikationen für jedes Hauptfeature:

1. **[Benutzeroberflächen](docs/features/user-frontends.md)** — Web-UI, Chat-Integrationen, REST-API
2. **[Datenindizierung & RAG](docs/features/data-indexing-rag.md)** — Dokumentindizierung, semantische Suche, Retrieval
3. **[LLM-Integration](docs/features/llm-integration.md)** — Modellkonfiguration, Anbieter, Kostenoptimierung
4. **[Deployment & Infrastruktur](docs/features/deployment-infrastructure.md)** — On-Premises, Cloud, Betrieb, Skalierung
5. **[Zugriffskontrolle & Workspaces](docs/features/access-control-workspaces.md)** — Berechtigungen, Mandantenfähigkeit, Audit-Protokollierung

### Architektur & Entscheidungen
- **[Architekturentscheidungen](docs/decisions/)** — Designbegründungen und technische Entscheidungen

## Anwendungsfälle

### Enterprise-Wissensdrehscheibe
Ein Fortune-500-Unternehmen mit mehr als 5.000 Mitarbeitern nutzt OPAA, um interne Wikis, Dokumentationen und archivierte E-Mails durchsuchbar zu machen. Mitarbeiter fragen „Was ist unser Genehmigungsverfahren für internationale Einstellungen?" und erhalten sofortige, quellengestützte Antworten bei vollständiger Daten-Governance-Compliance.

### Team-Produktivitätsmultiplikator
Ein SaaS-Unternehmen mit 50 Mitarbeitern setzt OPAA mit Mattermost-Integration ein. Teammitglieder stellen Fragen an „@opaa-bot". Das System durchsucht Wikis, Projektdokumentationen und Entscheidungsprotokolle. Wöchentliche Berichte werden durch automatisierte Abfragen generiert.

### Customer-Success-Wissensdatenbank
Das Support-Team nutzt OPAA für bessere Kundenantworten. Anstatt mehrere Systeme zu durchsuchen, fragen sie OPAA nach Produktinformationen und teilen quellengestützte Antworten mit Kunden.

### Compliance & Audit-Trail
Eine Gesundheitsorganisation nutzt OPAA, um Compliance-Richtlinien und Audit-Dokumente zu indizieren. Auf Nachfrage liefert das System genaue Quellenangaben für Audit-Trails.

## Kerndesignprinzipien

- 🔧 **Konfigurierbarkeit zuerst** — Jede Komponente ist austauschbar (LLM, Vektordatenbank, Datenquellen)
- 🏢 **On-Premises als Standard** — Daten verbleiben in Ihrer Infrastruktur, nicht bei externen Diensten
- 🔌 **Erweiterbare Architektur** — Plugin-System für Datenquellen, LLM-Adapter, benutzerdefinierte Frontends
- 🔐 **Sicherheit & Datenschutz eingebaut** — Workspace-Isolation, Berechtigungen, Audit-Trails, keine Datenprotokollierung
- 📖 **Quellenangabe immer** — Jede Antwort enthält Quelldokumente und Konfidenzwerte

## Status

OPAA befindet sich in der **frühen Produktdefinitionsphase**. Die Dokumentation beschreibt die vollständige Vision und den Funktionsumfang. Die Implementierungs-Roadmap folgt in Kürze.

## Mitwirken

Siehe [CONTRIBUTING.md](CONTRIBUTING.md) für Richtlinien zur Mitarbeit.

**Für KI-Agenten:** Lesen Sie [AGENTS.md](AGENTS.md) für Projektkonventionen und Kollaborationsrichtlinien.

## Technologiestack

Technologieentscheidungen werden während der Implementierung getroffen. OPAA ist bewusst **technologieagnostisch**:

- **LLM-Anbieter:** Beliebige OpenAI-kompatible API (OpenAI, Anthropic Claude, Ollama, vLLM, usw.)
- **Vektordatenbank:** Elasticsearch, PostgreSQL + pgvector, Milvus, Cloud-Alternativen
- **Deployment:** Kubernetes, Docker Compose, AWS, Azure, GCP oder On-Premises
- **Datenquellen:** Confluence, Jira, Gmail, S3, SharePoint, Google Drive, Dropbox, Issue-Tracker und mehr

## Lizenz

[GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE) — Frei und quelloffen. Kommerzielle Lizenzen für Organisationen verfügbar, die die AGPL-Bedingungen nicht einhalten können. Siehe [CLA.md](CLA.md) für Anforderungen an Beitragende.

## Nächste Schritte

- **Mehr erfahren?** Beginnen Sie mit [CONCEPTS.md](docs/CONCEPTS.md)
- **Beitragen?** Siehe [CONTRIBUTING.md](CONTRIBUTING.md)
- **Feedback zur Vision?** Öffnen Sie ein Issue oder eine Diskussion auf GitHub
