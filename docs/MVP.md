# OPAA MVP-Definition

## Überblick

Dieses Dokument definiert das Minimum Viable Product (MVP) für OPAA — den ersten implementierbaren Schritt zur vollständigen Produktvision, die in [VISION.md](./VISION.md) beschrieben ist.

Das MVP konzentriert sich auf einen einzigen, durchgehenden Anwendungsfall: **Ein Benutzer stellt eine Frage über eine Web-Schnittstelle und erhält eine KI-generierte Antwort basierend auf indizierten Dokumenten, einschließlich Quellenreferenzen mit Relevanz-Scores.**

---

## Kern-Anwendungsfall

> Ein Benutzer öffnet die OPAA-Web-Schnittstelle, gibt eine natürlichsprachige Frage ein und erhält eine Antwort, die aus indizierten Dokumenten generiert wurde. Jede Antwort enthält die Quelldokumente (Dateiname, Relevanz-Score und einen Textauszug), die die Antwort informiert haben.

### Benutzerfluss

```
1. Admin legt Dokumente in einem konfigurierten Ordner ab
2. System indiziert Dokumente automatisch (manueller Auslöser im MVP)
3. Benutzer öffnet Web-UI
4. Benutzer gibt eine Frage ein
5. Backend bettet die Frage ein, sucht nach relevanten Chunks
6. Backend sendet relevante Chunks + Frage an LLM
7. Benutzer erhält eine Antwort mit Quellenreferenzen
   (Dateiname, Relevanz-Score, Textauszug)
```

---

## Architektur

### Prinzipien

- **API-first**: Das Backend stellt eine REST-API bereit. Die Web-UI ist einer von vielen möglichen Clients.
- **Modularer Monolith**: Eine Spring Boot-Anwendung mit klar getrennten internen Modulen, konzipiert für spätere Zerlegung in Microservices.
- **Kein Vendor-Lock-in**: OpenAI-kompatible API-Schnittstelle unterstützt sowohl Cloud-(OpenAI) als auch lokale (Ollama) Anbieter.
- **Trennung von Anliegen**: LLM-Konfiguration und Embedding-Konfiguration sind unabhängig — für jede können verschiedene Modelle/Anbieter verwendet werden.

### Systemdiagramm

```
┌──────────────────────────────┐
│       Web-UI (React)         │
│   TypeScript + Material UI   │
└─────────────┬────────────────┘
              │ REST API (JSON)
              │
┌─────────────▼────────────────┐
│     Spring Boot Backend      │
│                              │
│  ┌─────────┐  ┌───────────┐ │
│  │ Query   │  │ Indexing   │ │
│  │ Module  │  │ Module    │ │
│  └────┬────┘  └─────┬─────┘ │
│       │             │       │
│  ┌────▼─────────────▼────┐  │
│  │     Spring AI         │  │
│  │  (LLM + Embeddings)  │  │
│  └───────────────────────┘  │
│                              │
│  ┌───────────────────────┐  │
│  │   Apache Tika         │  │
│  │  (Document Parsing)   │  │
│  └───────────────────────┘  │
└─────────────┬────────────────┘
              │
┌─────────────▼────────────────┐
│   PostgreSQL + pgvector      │
│  (Daten + Vektor-Speicher)   │
└──────────────────────────────┘
```

### Backend-Module

| Modul | Verantwortung |
|-------|---------------|
| `api` | REST-Endpunkte, Request-/Response-DTOs, Fehlerbehandlung |
| `indexing` | Dokumentenaufnahme, Tika-Parsing, Chunking, Embedding, Speicherung |
| `query` | Fragen-Embedding, Vektorsimilaritätssuche, LLM-Prompt-Konstruktion, Antwortgenerierung |

### Container-Layout (Docker Compose)

```yaml
services:
  frontend:   # React-App über Nginx bereitgestellt
  backend:    # Spring Boot-Anwendung
  postgres:   # PostgreSQL mit pgvector-Erweiterung
```

---

## Technologie-Stack

| Komponente | Technologie | Begründung |
|------------|-------------|------------|
| **Backend** | Java, Spring Boot, Spring AI | Enterprise-tauglich, Spring AI liefert LLM-/Embedding-/Vektorspeicher-Abstraktionen |
| **Frontend** | React, TypeScript, Material UI | Industriestandard, reichhaltige Komponentenbibliothek, sauberes Design-System |
| **Datenbank** | PostgreSQL + pgvector | Einzelne Datenbank für relationale Daten und Vektorsuche |
| **Dokument-Parsing** | Apache Tika (über Spring AI) | Unterstützt alle gängigen Formate über eine Integration |
| **LLM-Schnittstelle** | OpenAI-kompatible API | Funktioniert mit OpenAI (Cloud) und Ollama (lokal) über dieselbe Schnittstelle |
| **Deployment** | Docker Compose | Einfaches `docker compose up` für den vollständigen Stack |
| **Lokale Entwicklung** | Standard-Tooling | `mvn spring-boot:run` + `npm start` + lokales PostgreSQL |

---

## Features

### Im MVP enthalten

#### Dokumenten-Indizierung
- Dokumente aus einem **lokalen Dateisystem-Verzeichnis** indizieren (konfigurierbarer Pfad)
- Dokumente über **Apache Tika** parsen (Markdown, Klartext, PDF, Word, PowerPoint und mehr)
- Dokumente in Chunks aufteilen (konfigurierbare Chunk-Größe)
- Embeddings generieren und in **pgvector** speichern
- Manueller Indizierungs-Auslöser über API-Endpunkt (automatische/geplante Indizierung liegt außerhalb des Rahmens)

#### Frage-Antwort (RAG)
- Natürlichsprachige Fragen über REST-API akzeptieren
- Frage mit dem konfigurierten Embedding-Modell einbetten
- **Top-K** ähnlichste Dokument-Chunks aus pgvector abrufen
- Prompt mit abgerufenem Kontext konstruieren und an LLM senden
- Generierte Antwort mit **Quellenreferenzen** zurückgeben:
  - Dateiname und Pfad
  - Relevanz-Score (Ähnlichkeitsabstand)
  - Trefferanzahl (Anzahl übereinstimmender Chunks pro Datei)
  - Indizierungs-Zeitstempel
  - Zitations-Flag (ob das LLM die Quelle tatsächlich in seiner Antwort zitiert hat)

#### Web-UI
- Chat-artige Frage-Antwort-Schnittstelle
- Antworten mit formatierten Quellenreferenzen anzeigen
- Relevanz-Score pro Quelle anzeigen
- Responsives Design (Material UI)

#### UI-Platzhalter (nicht funktional, sichtbar)
- **Ergebnis-Feedback**: Daumen-hoch/Daumen-runter-Buttons für jede Antwort (angezeigt, aber keine Backend-Logik)
- **Zugangsstufen-Abzeichen**: Visuelle Indikatoren auf Quelldokumenten, die Berechtigungsstufen vorschlagen (z. B. "Intern", "Vertraulich", "Öffentlich") — statisches Mockup, keine echte Zugangskontrolle dahinter

#### Konfiguration
- **LLM-Konfiguration**: Anbieter-URL, Modellname, Parameter (Temperatur, maximale Tokens)
- **Embedding-Konfiguration**: Separate Anbieter-URL und Modellname (unabhängig von LLM-Konfiguration)
- Beides über Umgebungsvariablen / Anwendungseigenschaften konfigurierbar

### Explizit außerhalb des Rahmens

| Feature | Grund |
|---------|-------|
| Authentifizierung / Autorisierung | Erhöht Komplexität; API so konzipiert, dass Auth später einfach ergänzbar ist |
| Mehrere Datenquellen (Confluence, E-Mail, usw.) | MVP nutzt nur Dateisystem; Plugin-Architektur kommt später |
| Chat-Integrationen (Slack, Mattermost, usw.) | REST-API ermöglicht diese später; Web-UI ist das MVP-Frontend |
| Re-Ranking / Hybridsuche | Einfache Top-K-Ähnlichkeit ist für MVP ausreichend |
| Automatische / geplante Indizierung | Manueller Auslöser reicht aus; ereignisbasierte Indizierung kommt später |
| Multi-Tenancy / Workspaces | Kein Auth bedeutet kein Multi-Tenancy; Architektur unterstützt es später |
| Kubernetes-Deployment | Docker Compose deckt MVP-Anforderungen ab |
| Audit-Logging | Kein Auth-Kontext zum Loggen; Struktur wird vorbereitet |

---

## Erfolgs-Kriterien

Das MVP gilt als vollständig, wenn:

1. **Indizierung funktioniert**: Dokumente in einem Ordner können über einen API-Aufruf indiziert werden
2. **Frage-Antwort funktioniert durchgehend**: Ein Benutzer kann eine Frage in der Web-UI stellen und eine relevante Antwort erhalten
3. **Quellen werden angezeigt**: Jede Antwort zeigt Quelldateiname, Relevanz-Score, Trefferanzahl und Zitations-Status
4. **Duale LLM-Unterstützung**: Das System funktioniert sowohl mit der OpenAI-API als auch mit Ollama (lokal)
5. **Separate Konfigurationen**: LLM und Embedding-Modell sind unabhängig konfigurierbar
6. **Docker Compose läuft**: `docker compose up` startet den vollständigen Stack
7. **Lokale Entwicklung funktioniert**: Entwickler können Frontend und Backend lokal ohne Docker ausführen
8. **UI-Platzhalter sichtbar**: Feedback-Buttons und Zugangsstufen-Abzeichen werden in der UI angezeigt

---

## Bezug zur Produktvision

Diese Tabelle ordnet MVP-Entscheidungen der vollständigen Vision zu und zeigt den Upgrade-Pfad:

| MVP | Vollständige Vision | Upgrade-Pfad |
|-----|---------------------|--------------|
| Dateisystem-Datenquelle | Confluence, E-Mail, SharePoint, usw. | Plugin-/Adapter-System für Datenquellen |
| Nur Web-UI | Mattermost, Slack, Telegram, usw. | Zusätzliche Clients, die dieselbe REST-API nutzen |
| Kein Auth | RBAC, SSO, Workspaces | Spring Security + Keycloak-Integration |
| Einfaches Top-K-RAG | Re-Ranking, Hybridsuche, Konfidenz-Scores | Query-Modul verbessern, Re-Ranking-Schritt hinzufügen |
| Einzelnes Backend | Separate Indexer + Query-Dienste | Module in unabhängige Spring Boot-Apps extrahieren |
| Docker Compose | Kubernetes, Cloud-Deployment | Helm-Charts, cloud-native Konfiguration |
| Manuelle Indizierung | Ereignisbasierte, geplante Neu-Indizierung | File-Watcher, Cron-Jobs, Webhook-Integrationen |
| Feedback-Platzhalter | Echte Feedback-Sammlung + Modellverbesserung | Backend-Endpunkte, Feedback-Speicherung, Analytics |
| Zugangsstufen-Mockup | Berechtigungen auf Dokumentenebene | Integration mit Auth-System, berechtigungsbewusstes Retrieval |
