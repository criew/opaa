# Deployment & Infrastruktur

## Motivation

OPAA ist für Organisationen ausgelegt, die Datensouveränität und Kontrolle benötigen. Ob On-Premises im Rechenzentrum, in privater Cloud-Infrastruktur oder als verwalteter Dienst — dasselbe OPAA-System passt sich an verschiedene Deployment-Modelle an.

Dieses Feature beschreibt, wie OPAA in verschiedenen Infrastrukturumgebungen deployt, konfiguriert, skaliert und betrieben wird.

---

## Überblick

OPAA unterstützt drei Deployment-Modelle:

1. **On-Premises** — Vollständige Kontrolle, auf Ihrer Infrastruktur
2. **Private Cloud** — Ihr Cloud-Account, Ihre Kontrolle (AWS, Azure, GCP)
3. **Managed Service** — Vom OPAA-Team gehostet, gemeinsame Infrastruktur (optional, zukünftig)

Alle verwenden dieselbe Codebasis. Die Modellwahl erfolgt zum Deployment-Zeitpunkt.

---

## On-Premises-Deployment

### Architektur

```
┌─────────────────────────────────────┐
│  Organisations-Firewall / Proxy     │
└────────────────┬────────────────────┘
                 │
    ┌────────────▼──────────────┐
    │  OPAA Kubernetes-Cluster  │
    │  (oder Docker Compose)    │
    │                           │
    │ ┌─────────────────────┐   │
    │ │  Web-UI-Service     │   │
    │ │  Chat-Bot-Services  │   │
    │ │  API-Server         │   │
    │ └──────────┬──────────┘   │
    │            │              │
    │ ┌──────────▼──────────┐   │
    │ │  Orchestrierungs-   │   │
    │ │  Service            │   │
    │ └──────────┬──────────┘   │
    │            │              │
    │ ┌──────────▼──────────┐   │
    │ │  RAG-Engine         │   │
    │ │  LLM-Integrationen  │   │
    │ └──────────┬──────────┘   │
    │            │              │
    │ ┌──────────▼──────────┐   │
    │ │  Vektordatenbank    │   │
    │ │  Cache-Schicht      │   │
    │ │  Speicher           │   │
    │ └─────────────────────┘   │
    └────────────────────────────┘
                 │
    ┌────────────▼──────────────────────────┐
    │  Datenquellen (Confluence, E-Mail, FS)│
    │  (können außerhalb oder innerhalb     │
    │   der Firewall liegen)                │
    └───────────────────────────────────────┘
```

### Deployment-Optionen

#### Option A: Kubernetes (Empfohlen für große Organisationen)

Produktionsreifes On-Premises-Deployment.

**Infrastruktur:**
- Load Balancer für Ingress
- Persistente Volumes (lokal, NFS, Block-Speicher)
- Secrets-Management (etcd, HashiCorp Vault)
- Netzwerk-Policies für Sicherheit
- Monitoring (Prometheus, ELK-Stack)

**Deployment:**
- Helm Charts für einfache Installation bereitgestellt
- Health Checks, Ressourcenlimits, Auto-Scaling konfiguriert
- Log-Aggregation vorkonfiguriert

#### Option B: Docker Compose (Kleine bis mittelgroße Organisationen)

Einfacheres Deployment für kleinere Teams.

**Services:**
- opaa-app (Hauptanwendung: REST-API, Chat-Server, Web-UI)
- postgres (Datenbank für Metadaten)
- postgres-pgvector (Vektorspeicher mit pgvector)
- redis (Caching)

**Beispiel:**
```yaml
version: '3.8'
services:
  postgres:
    image: postgres:15-pgvector
    environment:
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql

  redis:
    image: redis:7-alpine

  opaa-app:
    image: opaa:latest
    ports:
      - "8080:8080"
    environment:
      DATABASE_URL: postgres://...
      REDIS_URL: redis://redis:6379
      LLM_PROVIDER: ${LLM_PROVIDER}
      LLM_API_KEY: ${LLM_API_KEY}
    depends_on:
      - postgres
      - redis

volumes:
  postgres_data:
```

#### Option C: Bare Metal (Spezialisierte Anforderungen)

Deployment auf VMs oder physischen Servern:
- Systempakete (Python 3.11+, PostgreSQL, Redis)
- Systemd-Services für Prozessverwaltung
- Manuelle Health Checks und Neustart-Logik
- Komplex, aber volle Kontrolle

---

## Konfigurationsverwaltung

### Umgebungsvariablen

Gesamte Konfiguration über Umgebungsvariablen (12-Faktor-App):

```bash
# Datenbank
DATABASE_URL=postgresql://user:pass@localhost/opaa
REDIS_URL=redis://localhost:6379

# LLM-Konfiguration
LLM_PROVIDER=openai
LLM_API_KEY=${OPENAI_API_KEY}
LLM_API_BASE=https://api.openai.com/v1
LLM_MODEL=gpt-4
LLM_EMBEDDING_MODEL=text-embedding-3-small

# Vektordatenbank
VECTOR_DB=pgvector  # oder elasticsearch, milvus
ELASTICSEARCH_HOST=localhost:9200  # bei ES

# Indizierung
INDEXING_SCHEDULE=daily-2am
CONFLUENCE_URL=https://wiki.company.com
CONFLUENCE_TOKEN=${CONFLUENCE_API_TOKEN}
EMAIL_IMAP_HOST=imap.gmail.com
EMAIL_IMAP_PASSWORD=${EMAIL_PASSWORD}

# Sicherheit & Auth
SECRET_KEY=${SECRET_KEY_32_BYTES}
OAUTH_CLIENT_ID=${AUTH0_CLIENT_ID}
OAUTH_CLIENT_SECRET=${AUTH0_CLIENT_SECRET}
CORS_ORIGINS=https://company.intranet

# Features
ENABLE_API=true
ENABLE_WEB_UI=true
ENABLE_CHAT_INTEGRATIONS=true
MAX_CONCURRENT_INDEXING_JOBS=4
LOG_LEVEL=info
```

### Konfigurationsdateien (Optional)

Für komplexe Setups, YAML-Konfigurationsdatei:

```yaml
# config.yaml
llm:
  provider: openai
  api_key: ${LLM_API_KEY}
  models:
    qa_generation: gpt-4
    summarization: gpt-3.5-turbo
    embeddings: text-embedding-3-small
  temperature: 0.3

vector_db:
  type: elasticsearch
  hosts:
    - elasticsearch.company.com:9200
  index_prefix: opaa

data_sources:
  confluence:
    enabled: true
    url: https://wiki.company.com
    auth_token: ${CONFLUENCE_TOKEN}
    schedule: "0 2 * * *"  # Täglich 2 Uhr

  email:
    enabled: true
    imap_host: imap.gmail.com
    email: archive@company.com
    password: ${EMAIL_PASSWORD}
    schedule: "*/6 * * * *"  # Alle 6 Stunden

  file_system:
    enabled: true
    paths:
      - /mnt/shared-docs
      - /mnt/team-wikis
    schedule: "*/30 * * * *"  # Alle 30 Minuten

security:
  enable_auth: true
  auth_type: oauth2
  oauth_provider: auth0
  api_key_enabled: true

performance:
  max_concurrent_indexing: 4
  embedding_batch_size: 100
  vector_search_top_k: 20
```

---

## Skalierungsüberlegungen

OPAA ist so konzipiert, dass es von kleinen Teams bis hin zu großen Unternehmen skaliert. Konkrete Hardware-Anforderungen und Sizing-Empfehlungen werden definiert, sobald der Technologie-Stack etabliert ist. Die Architektur unterstützt:

- **Kleine Deployments:** Single-Server Docker-Compose-Setup für Teams und kleine Organisationen
- **Mittlere Deployments:** Mehrknoten-Kubernetes-Cluster für mittelgroße Organisationen
- **Große Deployments:** Verteilte Infrastruktur mit horizontaler Skalierung für Unternehmen

### Kostensparstrategien

- Leichtgewichtige Vektordatenbank (z. B. PostgreSQL + pgvector) für kleinere Deployments verwenden
- Kosteneffiziente Embedding-Modelle verwenden
- Einfache Abfragen an schnellere/günstigere LLM-Anbieter weiterleiten
- Häufige Antworten cachen, um LLM-API-Kosten zu reduzieren
- Batch-Indizierung während verkehrsarmer Zeiten

---

## Private-Cloud-Deployment

### AWS-Deployment

Typische AWS-Architektur:

```
Application Load Balancer
  ↓
ECS-Cluster (OPAA-Services)
  ↓
RDS PostgreSQL (Metadaten)
  ↓
OpenSearch (Vektordatenbank)
  ↓
S3 (Dokumentenspeicher)
  ↓
Datenquellen (S3, Confluence usw.)
```

**Verwendete Services:**
- ECS oder EKS für Container-Orchestrierung
- RDS für PostgreSQL
- OpenSearch für Vektordatenbank
- S3 für Datenspeicherung und Backups
- Lambda für geplante Indizierungsjobs
- CloudWatch für Monitoring
- VPC für Netzwerkisolation

**Vorteile:**
- Verwaltete Services reduzieren den Betriebsaufwand
- Auto-Scaling integriert
- Backup und Disaster Recovery einfach
- IAM für Zugangskontrolle
- Gleicher Datenschutz wie On-Premises (innerhalb von AWS)

### Azure-Deployment

Ähnlich wie AWS:
- Azure Container Instances oder AKS
- Azure Database for PostgreSQL
- Azure Search (Vektorsuche)
- Azure Blob Storage
- Gleiche Muster, anderer Anbieter

### GCP-Deployment

Ähnliches Muster:
- GKE für Kubernetes
- Cloud SQL für PostgreSQL
- Vertex AI Vector Search
- Cloud Storage

---

## Hochverfügbarkeit & Notfallwiederherstellung

### Hochverfügbarkeit (HA)

Für Produktions-Deployments:

**Mehrere Replikas:**
- API-Server: 3+ Replikas
- Vektordatenbank: Repliziert/gesharded
- PostgreSQL: Primär + Standby-Replikas
- Redis: Sentinel-Modus oder Cluster

**Load Balancing:**
- Load Balancer verteilt Traffic
- Health Checks ermöglichen automatisches Failover
- Circuit Breaker für Service-Degradierung

**Datenbankreplikation:**
- PostgreSQL-Streaming-Replikation
- Vektordatenbank-Replikation (je nach Backend unterschiedlich)
- Regelmäßige Backup-Validierung

### Notfallwiederherstellung (DR)

**Backup-Strategie:**
- Tägliche Vollbackups von PostgreSQL
- Inkrementelle Backups der Vektor-Embeddings
- Dokumenten-Backups (Quelle der Wahrheit, nicht kritisch)
- Backup in separater Region/Account gespeichert

**Wiederherstellung:**
- RTO (Recovery Time Objective): 1 Stunde
- RPO (Recovery Point Objective): 1 Tag
- Regelmäßige DR-Übungen (vierteljährlich)
- Runbooks für häufige Fehler

**Failover:**
- Automatisches Failover für k8s-Services
- Manuelles Failover für Datenbanken (< 30 Minuten)
- Dokumentierte Verfahren für alle Services

---

## Sicherheit

### Netzwerksicherheit

**Firewall-Regeln:**
- OPAA-Cluster nur aus internem Netzwerk erreichbar
- Ausgehend: Nur zu konfigurierten Datenquellen und LLM-APIs
- VPN/SSH-Zugang für Administration
- DDoS-Schutz am Perimeter

**Datenverschlüsselung:**
- TLS 1.3 für den gesamten Netzwerkverkehr (intern und extern)
- Verschlüsseltes Secrets-Management (Vault, k8s Secrets)
- Datenbankverschlüsselung at Rest
- Festplattenverschlüsselung auf Servern

### Zugangskontrolle

**Authentifizierung:**
- SSO-Integration (OIDC, SAML)
- API-Tokens mit Scopes
- Service-Accounts für Automatisierung

**Autorisierung:**
- RBAC für Admin-Funktionen
- Dokumentenebenen-Berechtigungen
- Workspace-Isolation
- Audit-Logging aller Zugriffe

### Compliance

OPAA ist ausgelegt, folgendes zu unterstützen:
- **DSGVO:** Datenaufbewahrungsrichtlinien, Datenlöschung
- **HIPAA:** Verschlüsselung, Audit-Trails
- **SOC 2:** Zugangskontrolle, Monitoring
- **ISO 27001:** Sicherheitskontrollen-Framework

---

## Monitoring & Betrieb

### Zu überwachende Metriken

Wichtige Leistungsindikatoren:

```
Performance:
  - API-Antwortzeit (p50, p95, p99)
  - Vektorsuche-Latenz
  - LLM-Generierungszeit
  - Seitenladezeit (Web-UI)

Zuverlässigkeit:
  - API-Verfügbarkeit %
  - Fehlerquoten (5xx, 4xx)
  - Fehlgeschlagene Indizierungsjobs
  - Warteschlangenlängen

Kosten:
  - LLM-Token/Tag
  - Embedding-Kosten
  - Infrastrukturkosten
  - Kosten pro Abfrage

Nutzung:
  - Abfragen pro Tag
  - Aktive Benutzer
  - Häufigste Fragen
  - Meistgenutzte Dokumente
```

### Alarmierung

Alarm auslösen bei:
- API-Fehlerquote > 1%
- Antwortzeit P95 > 2 Sekunden
- Vektordatenbank-Festplatte > 80% voll
- Indizierungsjob-Fehler > 3 hintereinander
- LLM-API-Fehler
- Hohe Kosten (Budget überschritten)

### Logging

Zentrales Logging von:
- API-Anfragen und -Antworten (keine sensiblen Daten)
- Fehler und Ausnahmen
- Indizierungsfortschritt und -fehler
- Admin-Aktionen
- Benutzer-Feedback

Standard-Logformat: JSON mit Zeitstempeln, Service, Schweregrad

---

## Backup & Wiederherstellung

### Was zu sichern ist

**Kritisch:**
- PostgreSQL-Datenbank (Metadaten, Benutzereinstellungen)
- Vektor-Embeddings (regenerierbar, aber aufwändig)

**Wichtig:**
- Konfigurationsdateien
- Eigene Integrationen/Plugins
- Admin-Einstellungen

**Nicht erforderlich:**
- Quelldokumente (können aus der Quelle neu indiziert werden)
- Gecachte Embeddings (können neu generiert werden)

### Backup-Häufigkeit

- **PostgreSQL:** Tägliches Vollbackup + stündliches inkrementelles Backup
- **Vektordatenbank:** Täglich nach jedem Indizierungslauf
- **Konfiguration:** In Git versioniert (separates Repository)

### Wiederherstellungstests

- Monatliche Wiederherstellungstests (um sicherzustellen, dass Backups funktionieren)
- Dokumentierte Wiederherstellungsverfahren
- Geschätzte Wiederherstellungszeit: < 4 Stunden für vollständige Wiederherstellung

---

## Upgrades & Wartung

### Blue-Green-Deployment

Für unterbrechungsfreie Upgrades:

1. Neue Version in „grüner" Umgebung deployen
2. Tests in Grün durchführen
3. Load Balancer von Blau auf Grün umschalten
4. Blau als Rollback behalten

**Ausfallzeit:** 0 (für Benutzer), einige Minuten insgesamt

### Rolling Deployment (Kubernetes)

Alternative: Schrittweiser Rollout
- 1 Pod stoppen, 1 neue Version starten
- Auf Health Checks warten
- Wiederholen bis alle Pods aktualisiert
- Automatischer Rollback bei fehlgeschlagenen Health Checks

### Abwärtskompatibilität

- API-Versionen beibehalten (v1, v2 usw.)
- Datenbankschema-Migrationen nicht brechend
- Alte Features schrittweise als veraltet markieren, nicht abrupt entfernen

---

## Multi-Tenancy (Zukünftig)

Für den Betrieb mehrerer Organisationen:

**Isolationsstufen:**
1. Separate Instanzen (einfachste, höchste Isolation)
2. Gemeinsame Infrastruktur, separate Datenbanken (mittlere Isolation)
3. Gemeinsame Datenbank, Row-Level-Security (maximale Dichte)

OPAA ist für Option 3 ausgelegt:
- Workspace-IDs in allen Daten
- Row-Level-Security-Policies
- Separate Vektor-Embeddings pro Workspace (optional)
- Kostenzuordnung pro Mandant

---

## Integrationspunkte

- **Datenquellen:** Dokumente abrufen, Anmeldedaten verwalten
- **Authentifizierung:** SSO-Anbieter-Integration
- **Monitoring:** Metriken an Observability-Stack senden
- **LLM-Anbieter:** API-Zugriff für Generierung und Embeddings

---

## Offene Fragen / Zukünftige Erweiterungen

- Sollen wir OPAA als verwalteten Service anbieten?
- Sollen Deployments automatisch aktualisiert werden?
- Sollen wir GitOps unterstützen (Konfiguration in Git)?
- Sollen wir Terraform-/CloudFormation-Templates bereitstellen?
- Sollen wir Multi-Region-Deployments unterstützen?
- Sollen wir Helm Charts für die Community bereitstellen?

---

## Erfolgs-Metriken

- **Verfügbarkeit:** 99,9% Uptime
- **Performance:** P95-Antwortzeit < 2 Sekunden
- **Deployment-Zeit:** Neue Version in < 15 Minuten deployt
- **Skalierung:** Kann 10-faches Abfragevolumen mit 3-fachen Infrastrukturkosten bewältigen
- **Wiederherstellung:** Aus Backup in < 4 Stunden wiederherstellen
