# Daten-Indizierung & RAG

## Motivation

OPAAs Wert entsteht durch den Zugang zu organisationalem Wissen. Dieses Feature beschreibt, wie Dokumente aus verschiedenen Quellen (Wikis, E-Mail, Dateisysteme) entdeckt, verarbeitet und über semantische Embeddings auffindbar gemacht werden. Neben organisationalen Datenquellen, die von Konnektoren erschlossen werden, unterstützt OPAA auch das direkte Hochladen von Dokumenten durch einzelne Benutzer, sodass persönliches Wissen in die RAG-Pipeline eingespeist werden kann.

Die Retrieval-Augmented-Generation-Pipeline (RAG) stellt sicher, dass Antworten auf tatsächlichen Organisationsdokumenten basieren — mit vollständiger Quellenangabe und Nachvollziehbarkeit.

---

## Überblick

Das Daten-Indizierungs- & RAG-System besteht aus drei Phasen:

1. **Quellen-Erkennung, Upload & Ingestion** — Dokumente in verschiedenen Quellen finden oder über Benutzer-Uploads empfangen
2. **Dokumentenverarbeitung** — Dokumente extrahieren, aufteilen und einbetten
3. **Retrieval & Ranking** — Relevante Dokumente für Benutzerfragen finden

Dokumente gelangen über zwei Wege in die Pipeline:
- **Konnektor-basierte Ingestion:** OPAA zieht Dokumente aus konfigurierten Datenquellen (Confluence, E-Mail, Dateisysteme) nach Zeitplan oder ereignisgesteuert.
- **Benutzer-Upload-Ingestion:** Benutzer übertragen Dokumente direkt über Frontends (Web-UI, Chat, REST-API) in OPAA.

---

## Unterstützte Datenquellen

### Quellkategorien

OPAA verbindet sich mit mehreren Quelltypen:

#### 1. **Wissensmanagement-Systeme**
- **Confluence** — Wiki-Seiten, Spaces, Anhänge
- **Notion** — Seiten, Datenbanken, Wikis
- **MediaWiki** — Wikipedia-artige Wikis
- **Eigene Wikis** — Über REST-API

#### 2. **E-Mail-Archive**
- **E-Mail-Server** — IMAP/SMTP (Gmail, Office 365, On-Premises-Exchange)
- **E-Mail-Exporte** — MBOX-, PST-Dateien
- **E-Mail-Dienste** — Gmail-API, Microsoft Graph API

#### 3. **Dateisysteme & Cloud-Speicher**
- **Lokale Dateisysteme** — On-Premises-Server
- **HTTP-Verzeichnislisten** — Apache-mod_autoindex- / nginx-autoindex-Server (siehe unten)
- **Cloud-Speicher** — S3, Azure Blob, Google Cloud Storage, Google Drive, Dropbox
- **Netzlaufwerke** — SMB/CIFS-Freigaben
- **Git-Repositories** — Dokumentation in GitHub/GitLab

#### 4. **Issue-Tracker & Projektmanagement**
- **Jira** — Issues, Kommentare, Anhänge
- **GitHub Issues / GitLab Issues** — Issues, Diskussionen, Pull Requests
- **Eigene Issue-Tracker** — Über REST-API

#### 5. **Dokumentformate**
Automatisch erkannt und verarbeitet:
- **Markdown** (.md)
- **AsciiDoc** (.adoc)
- **PDF** (.pdf) — Textextraktion per OCR falls nötig
- **Microsoft Office** (.docx, .xlsx, .pptx)
- **Klartext** (.txt)
- **HTML** (.html)
- **Strukturierte Daten** (.json, .csv, .xml)

#### 6. **APIs & Eigene Quellen**
- **REST-APIs** — Jedes System mit dokumentierter API
- **Webhooks** — Updates an OPAA senden
- **Eigene Konnektoren** — Erweiterbares Plugin-System

### HTTP-Verzeichnislisten

OPAA kann Dokumente von HTTP-Servern crawlen und indizieren, die Apache-mod_autoindex- (oder kompatible) Verzeichnislisten bereitstellen. Dies ist nützlich, um Dokument-Repositories auf internen Webservern zu erschließen, ohne spezialisierte Konnektoren zu benötigen.

**So funktioniert es:**

1. OPAA crawlt die HTML-Verzeichnisliste unter der angegebenen URL rekursiv
2. Entdeckt alle Dateien in Unterverzeichnissen
3. Lädt jede Datei an einen temporären Speicherort für die Verarbeitung herunter
4. Verwendet den `lastModified`-Zeitstempel aus der Verzeichnisliste, um den Download unveränderter Dateien zu überspringen (Bandbreiten-Optimierung)
5. Berechnet nach dem Download einen SHA-256-Prüfsumme der Datei für inhaltsbasierte Deduplizierung (erkennt Umbenennung, stellt Inhaltsintegrität sicher)
6. Verarbeitet jede Datei durch die Standard-Pipeline (Extraktion, Chunking, Embedding)
7. Bereinigt temporäre Dateien nach der Verarbeitung

**Unterstützte Funktionen:**
- Basic-Authentifizierung (Benutzername:Passwort)
- HTTP-Proxy-Unterstützung (Host:Port)
- Unsicherer SSL-Modus (Zertifikatsprüfung für selbstsignierte Zertifikate überspringen)
- Rekursives Verzeichnis-Traversal
- Robuster HTML-Parser für verschiedene Apache/nginx-autoindex-Ausgabeformate

**URL-basierte Indizierung auslösen:**

Über die Admin-UI: Admin-Drawer öffnen, „URL-Quelle (optional)" aufklappen, URL und optionalen Proxy/Anmeldedaten eingeben, dann auf „Dokumente indizieren" klicken.

Über die API:
```bash
curl -X POST http://localhost:8080/api/v1/indexing/trigger \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://files.example.com/documents/",
    "proxy": "proxy.example.com:8080",
    "credentials": "user:password",
    "insecureSsl": false
  }'
```

Wenn keine URL angegeben wird, wird stattdessen die standardmäßige dateisystembasierte Indizierung ausgelöst.

### Konnektor-Modell und Zuordnung zu Wissensbibliotheken

Ein **Konnektor** definiert den Typ und die gemeinsame Konfiguration (Anmeldedaten, Zeitplan). Jeder Konnektor hat eine oder mehrere **Quellen**, von denen jede **genau einer Wissensbibliothek** zugeordnet wird. Nur **System-Admins** können Konnektoren erstellen und Quellzuordnungen definieren; wer die Bibliothek anschließend sehen darf, entscheidet deren Eigentümer im Rahmen der vom System-Admin gesetzten Freigabe-Obergrenze.

Wird derselbe Bestand an mehreren Stellen gebraucht, wird **die Bibliothek** in weiteren Spaces bereitgestellt oder an weitere Nutzer und Gruppen freigegeben — die Quelle wird nicht mehrfach zugeordnet und das Dokument nicht vervielfacht.

Manche Konnektor-Typen haben eine natürliche Instanzebene mit Untereinheiten (z. B. Confluence-Server mit Spaces). Andere haben keine gemeinsame Instanz — jede Quelle ist eigenständig (z. B. einzelne Dateipfade oder URLs).

```
Beispiel 1: Confluence (Instanz mit Untereinheiten)
  Konnektor: "Confluence Produktion"
    Typ: confluence
    URL: https://wiki.company.com
    Anmeldedaten: Service-Account / API-Token
    Zeitplan: Täglich 2 Uhr
    Quellen:
      Space "ENG"  → Bibliothek: "Engineering"
      Space "MKT"  → Bibliothek: "Marketing"
      Space "HR"   → Bibliothek: "Personal"
      Space "ALL"  → Bibliothek: "Hausweite Regelungen"

Beispiel 2: Dateisystem / Netzlaufwerk (ein Pfad pro Quelle)
  Konnektor: "Netzlaufwerk Engineering"
    Typ: filesystem
    Zeitplan: Täglich 3 Uhr
    Quellen:
      Pfad "//fileserver/engineering/docs" → Bibliothek: "Engineering"

Beispiel 3: HTTP-Verzeichnis (eine URL pro Quelle)
  Konnektor: "Docs-Server Engineering"
    Typ: http
    Zeitplan: Täglich 4 Uhr
    Quellen:
      URL "https://docs.internal/engineering/" → Bibliothek: "Engineering"
```

#### Konnektor-Typen und ihre Quellen

| Konnektor-Typ | Gemeinsame Konfiguration (Konnektor) | Quelle (eine oder mehrere pro Konnektor) |
|---|---|---|
| Confluence | Server-URL, Anmeldedaten | Space-Key |
| Jira | Server-URL, Anmeldedaten | Projekt-Key |
| E-Mail (IMAP) | Server-URL, Anmeldedaten | Ordner / Label |
| Dateisystem / Netzlaufwerk | optional Zeitplan | Pfad (lokal oder UNC) |
| HTTP-Verzeichnis | optional Proxy, Auth | URL |
| Git | optional Anmeldedaten | Repository-URL + Branch |

#### Zuordnungsregeln

- **1:1** — Jede Quelle wird **genau einer Wissensbibliothek** zugeordnet. Wird derselbe Bestand an mehreren Stellen gebraucht, wird die Bibliothek in weiteren Spaces assoziiert oder an weitere Nutzer und Gruppen freigegeben — das Dokument wird nicht vervielfacht.
- **Freigabe-Obergrenze:** Der System-Admin setzt je konnektor-gespeister Bibliothek, wie weit ihr Eigentümer sie höchstens freigeben darf.
- **Nicht zugeordnete Untereinheiten** werden ignoriert (z. B. Confluence-Spaces ohne Zuordnung werden nicht indiziert)
- **Mehrere Konnektoren** können in dieselbe Wissensbibliothek indizieren (z. B. Confluence-Space „ENG" + Netzlaufwerk-Pfad beide → „Engineering")

#### Quellenfilterung

Jede Quelle kann optional Einschluss-/Ausschlussmuster definieren:
```
Quelle: Confluence Space "ENG" → Bibliothek "Engineering"
Filterung:
  - Einschlussmuster: ["public/*", "team/*"]
  - Ausschlussmuster: ["draft/*", "archive/*"]
Inkrementell: Nur neue/geänderte Dokumente
```

---

## Benutzer-Dokument-Upload

### Konzept

Zusätzlich zur konnektor-basierten Ingestion können Benutzer Dokumente direkt über jedes Frontend (Web-UI, Chat, REST-API) in OPAA hochladen. Hochgeladene Dokumente werden auf einem konfigurierbaren Speicher-Backend gespeichert und durchlaufen dieselbe Dokumentenverarbeitungs-Pipeline wie konnektor-bezogene Dokumente.

### Unterschied zu Konnektoren

| Aspekt | Konnektoren | Benutzer-Upload |
|--------|-----------|-------------|
| Richtung | OPAA zieht aus Quellen | Benutzer überträgt an OPAA |
| Auslöser | Zeitplan- oder ereignisbasiert | Auf Abruf (Benutzeraktion) |
| Umfang | Organisationale Datenquellen | Individuelle Benutzerdokumente |
| Wissensbibliothek | Pro Quelle konfiguriert (genau eine) | Persönliche Bibliothek des Benutzers (Standard) |
| Speicherung | Original verbleibt im Quellsystem | Auf OPAAs Speicher-Backend gespeichert |

### Upload-Ablauf

1. Benutzer wählt Datei(en) über das Frontend aus (Web-UI Drag-and-Drop, Chat-Anhang oder API Multipart-Upload)
2. Datei wird validiert (Format, Größenbeschränkungen, Virenscan)
3. Datei wird auf dem konfigurierten Speicher-Backend gespeichert (S3, Netzlaufwerk, lokales FS)
4. Dokument durchläuft die Standard-Verarbeitungs-Pipeline (Extraktion, Chunking, Embedding, Vektorspeicherung)
5. Dokument wird standardmäßig in die persönliche Wissensbibliothek des Benutzers indiziert
6. Benutzer kann es stattdessen in eine Bibliothek hochladen, an der er mindestens EDITOR ist

### Speicher-Backend-Abstraktion

Hochgeladene Dateien werden auf einem austauschbaren Speicher-Backend gespeichert, das zum Zeitpunkt des Deployments gewählt wird. Dies ist getrennt von der Vektordatenbank — das Speicher-Backend hält die ursprünglich hochgeladenen Dateien (PDF, DOCX usw.) für den Download und die Wiederverarbeitung, während die Vektordatenbank die Embeddings und den Chunk-Text für die Suche enthält.

#### Option 1: S3-kompatibler Objektspeicher
- AWS S3, MinIO oder ein beliebiger S3-kompatibler Speicher
- Optimal für Cloud- und Hybrid-Deployments
- Integrierte Redundanz und Lifecycle-Management

#### Option 2: Netzlaufwerk (SMB/NFS)
- Gemeinsames Dateisystem-Mount
- Optimal für On-Premises-Deployments mit vorhandenen Dateiservern
- Vertraut für Betriebsteams

#### Option 3: Lokales Dateisystem
- Direkter Festplattenspeicher auf dem OPAA-Server
- Einfachste Option für kleine Deployments und Entwicklung
- Erfordert separate Backup-Strategie

**Speicher-Backend-Konfiguration:**
```yaml
storage:
  backend: "s3"  # oder "network-drive" oder "local"
  s3:
    endpoint: "https://s3.company.com"
    bucket: "opaa-uploads"
    region: "eu-central-1"
  network-drive:
    path: "//fileserver/opaa-uploads"
  local:
    path: "/data/opaa/uploads"
  limits:
    max_file_size: "50MB"
    allowed_formats: ["pdf", "docx", "md", "txt", "pptx", "xlsx"]
```

### Unterstützte Upload-Formate

Dieselben Dokumentformate wie konnektor-bezogene Dokumente (siehe Abschnitt Dokumentformate oben), mit folgenden Ergänzungen für den Upload-Kontext:
- Maximale Dateigröße konfigurierbar (Standard: 50 MB)
- Batch-Upload-Unterstützung (mehrere Dateien gleichzeitig)
- Drag-and-Drop in der Web-UI
- Dateianhang in Chat-Plattformen

### Upload-Metadaten

Jedes hochgeladene Dokument speichert:
```json
{
  "document_id": "upload-456",
  "filename": "design-review-q1.pdf",
  "uploaded_by": "user-123",
  "uploaded_at": "2026-02-16T10:30:00Z",
  "library_id": "lib-personal-user-123",
  "storage_backend": "s3",
  "storage_path": "s3://opaa-uploads/user-123/design-review-q1.pdf",
  "file_size_bytes": 2048576,
  "content_type": "application/pdf",
  "source_type": "user_upload"
}
```

---

## Dokumentenverarbeitungs-Pipeline

### Schritt 1: Erkennung & Extraktion

Für jede Quelle führt OPAA folgendes durch:
- Verbindung zum Quellsystem herstellen
- Alle verfügbaren Dokumente auflisten
- Änderungszeitstempel mit dem letzten Index vergleichen
- Neue/geänderte Dokumente herunterladen
- Textinhalt extrahieren (verarbeitet Binärformate wie PDF)

**Bei Benutzer-Uploads:** Der Erkennungsschritt wird durch das Upload-Ereignis selbst ersetzt. Die hochgeladene Datei wird vom Speicher-Backend abgerufen und tritt in der Extraktionsphase in die Pipeline ein. Alle nachfolgenden Schritte (Chunking, Embedding, Speicherung) sind identisch mit konnektor-bezogenen Dokumenten.

**Fehlerbehandlung:**
- Überspringt Dokumente, die nicht extrahiert werden können
- Protokolliert Fehler zur Admin-Überprüfung
- Wiederholt fehlgeschlagene Dokumente beim nächsten Durchlauf

### Schritt 2: Chunking

Große Dokumente werden in kleinere Chunks aufgeteilt:
- **Strategie:** Semantisches Chunking (Aufteilung an natürlichen Grenzen)
- **Chunk-Größe:** 512–1024 Token (konfigurierbar)
- **Überlappung:** 10% Überlappung zwischen Chunks zum Kontexterhalt
- **Metadaten:** Jeder Chunk bewahrt:
  - Quelldokument-ID
  - Dokumenttitel
  - Chunk-Position
  - Zeitstempel

**Beispiel:**
```
Dokument: "Enterprise Architecture Guide" (15.000 Wörter)
↓
Chunks:
  1. "Einführung & Grundsätze" (Chunk 0)
  2. "Infrastrukturschicht" (Chunk 1)
  3. "Anwendungsarchitektur" (Chunk 2)
  ...
  15. "Anhang & Referenzen" (Chunk 14)
```

### Schritt 3: Embedding-Generierung

Jeder Chunk wird in ein semantisches Embedding umgewandelt:
- **Modellwahl:** Konfigurierbar (OpenAI, Open-Source-Alternativen)
- **Dimension:** 1536 für OpenAI, konfigurierbar für andere
- **Caching:** Embeddings werden zwischengespeichert, um Neuberechnungen zu vermeiden
- **Batch-Verarbeitung:** In Batches für Effizienz verarbeitet
- **Fehlerwiederherstellung:** Fehlgeschlagene Embeddings für Wiederholung protokolliert

**Kostenbetrachtung:** Die Embedding-Generierung verursacht minimale Kosten im Vergleich zu LLM-Inferenz. Organisationen können günstigere Embedding-Modelle verwenden.

### Schritt 4: Speicherung in der Vektordatenbank

Verarbeitete Chunks werden gespeichert mit:
- Embedding-Vektor
- Chunk-Text
- Metadaten (Quelle, Dokument-ID, Zeitstempel, Chunk-Index)
- Dokument-URL (für Retrieval)
- Bibliotheks-ID (Filterachse der rechtebewussten Suche) und Organisations-ID (Mandantengrenze)

**Gespeicherte Metadaten:**
```json
{
  "chunk_id": "doc-123-chunk-5",
  "document_id": "doc-123",
  "document_title": "Enterprise Architecture Guide",
  "library_id": "lib-eng",
  "organization_id": "org-1",
  "source": "confluence",
  "source_type": "connector",
  "source_url": "https://wiki.company.com/pages/view/123456",
  "chunk_index": 5,
  "chunk_text": "...",
  "embedding": [0.123, -0.456, ...],
  "indexed_at": "2024-02-16T14:30:00Z"
}
```

**Führender Speicher ist die relationale Datenbank**; der Vektorspeicher ist abgeleitet. Das ist keine Nebensächlichkeit, sondern bestimmt das Sicherungsverfahren: Nach dem Einspielen einer Datenbanksicherung können Chunks mit Bibliotheks-Kennungen existieren, deren Bibliothek inzwischen anders berechtigt oder gelöscht ist. Bei pgvector in derselben Datenbank entschärft sich das; bei getrennt gesicherten Vektorspeichern nicht. Ein Konsistenzprüflauf gleicht beide Seiten ab.

Hinweis: `library_id` ist **einwertig** — jedes Dokument gehört zu genau einer Wissensbibliothek. Die Mehrfachverwendung eines Bestands wird eine Ebene höher gelöst (dieselbe Bibliothek in mehreren Spaces assoziiert) und muss deshalb nicht je Chunk materialisiert werden. Die Berechtigungsprüfung verwendet dieses Feld als Metadatenfilter in der Vektorsuche (siehe [Spaces, Assets & Zugangskontrolle](./spaces-and-assets.md#durchsetzung-zur-abfragezeit)).

### Schritt 5: Index-Aktualisierungen

Inkrementelle Verarbeitung:
- Nur neue/geänderte Dokumente werden verarbeitet
- Geänderte Chunks im Vektorspeicher aktualisiert
- Gelöschte Dokumente aus dem Index entfernt
- Vollständige Neuindizierung verfügbar (Force-Option)

---

## Unterstützte Vektordatenbanken

OPAA unterstützt mehrere Vektordatenbank-Backends. Organisationen wählen basierend auf:
- Infrastrukturbeschränkungen (On-Premises vs. Cloud)
- Skalierungsanforderungen
- Kostenüberlegungen
- Integration mit bestehenden Systemen

### Option 1: **Elasticsearch mit Vektorsuche**
- Self-Hosted oder verwaltet
- Hybridsuche (Vektor + Keyword)
- Erweiterte Filterung und Aggregation
- Vielen Betriebsteams vertraut

### Option 2: **PostgreSQL + pgvector**
- Leichtgewichtig, läuft in der bestehenden Datenbank
- Keine zusätzliche Infrastruktur
- Gut für kleine bis mittelgroße Deployments
- SQL-native Integration

### Option 3: **Milvus**
- Open-Source-Vektordatenbank
- Entwickelt für groß angelegte Ähnlichkeitssuche
- Self-Hosted, horizontal skalierbar
- Für hohen Durchsatz optimiert

### Option 4: **Cloud-Vektordatenbanken**
- Pinecone, Weaviate, Qdrant (verwaltet)
- Einfache verwaltete Option
- Integrierte Skalierbarkeit
- Kann mit On-Premises-Fallback kombiniert werden

### Implementierungsdetail
Die Wahl der Vektordatenbank erfolgt zum **Deployment-Zeitpunkt**, nicht beim Anwendungsdesign. Kein Vendor-Lock-in. Der Wechsel der Datenbank erfordert eine Neuindizierung, aber keine Code-Änderungen.

OPAA verwendet die `VectorStore`-Abstraktion von Spring AI für alle Indizierungs- und Retrieval-Operationen. Embedding-Generierung, Speicherung und Ähnlichkeitssuche werden an das `VectorStore`-Interface delegiert, wodurch das Vektordatenbank-Backend über die Konfiguration austauschbar ist.

---

## Retrieval & Ranking

### Retrieval-Prozess

Wenn ein Benutzer eine Frage stellt:

1. **Suchbereich bestimmen:** Die für den Benutzer lesbaren Wissensbibliotheken laden und mit dem Bereich des Kontexts schneiden — bei einem Chat ohne Agent sind das die im Space assoziierten Bibliotheken, bei einem Chat mit Agent die vom Agenten gebundenen. Der Rechtekontext ist immer der des aufrufenden Nutzers.
2. **Embedding-Generierung:** Frage in Embedding umgewandelt (gleiches Modell wie bei Dokumenten)
3. **Vektorsuche mit Bibliotheks-Filter:** Die Top-K ähnlichsten Chunks finden, gefiltert nach `library_id`. Der Berechtigungsfilter ist Teil der Vektorsuche selbst, kein nachgelagerter Verarbeitungsschritt.
4. **Deduplizierung:** Doppelte Informationen aus demselben Dokument entfernen
5. **Quellen-Deduplizierung:** Wenn mehrere Chunks aus derselben Datei stammen, wird nur der Chunk mit der höchsten Relevanzbewertung als Quellenreferenz behalten (implementiert in `QueryService.mapSources()`)
6. **Re-Ranking:** Ergebnisse nach Relevanz bewerten

### Retrieval-Konfiguration

```
Retrieval:
  similarity_threshold: 0.6
  top_k: 20
  apply_permissions: true
  chunk_recency_boost: true
  source_diversity: true
```

### Re-Ranking-Strategie

Nach dem initialen Retrieval werden Ergebnisse bewertet nach:
- **Semantische Ähnlichkeit:** Wie nah das Embedding an der Frage ist
- **Dokumentaktualität:** Neuere Dokumente werden höher eingestuft (optional)
- **Quellen-Vertrauenswert:** Häufig aktualisierte Quellen werden höher eingestuft (optional)
- **Keyword-Überlappung:** Exakte Phrasentreffer im Dokument (optional)

**Score-Berechnung:**
```
final_score = (
  0.6 * semantic_similarity +
  0.2 * recency_boost +
  0.1 * source_trust +
  0.1 * keyword_overlap
)
```

### Konfidenz-Bewertung

Das System liefert einen Konfidenzwert für jedes abgerufene Dokument:
- **Hoch (> 0,85):** Definitiv relevant für die Frage
- **Mittel (0,6 – 0,85):** Wahrscheinlich relevant
- **Niedrig (< 0,6):** Fraglich relevant, als unsicher markiert

Benutzer sehen die Bewertungen und können nach Konfidenz filtern.

---

## Erweiterte Funktionen

### Mehrsprachige Unterstützung

Dokumente in verschiedenen Sprachen werden indiziert und durchsucht:
- Jedes Dokument wird mit der Sprache gekennzeichnet
- Das Embedding-Modell muss die Sprache unterstützen
- Abfragen in beliebiger Sprache werden mit Dokumenten abgeglichen
- Ergebnisse werden in der Originalsprache zurückgegeben

### Dokumenten-Metadaten-Extraktion

Aus jedem Dokument extrahiert das System automatisch:
- Titel
- Autor (falls verfügbar)
- Erstellungs-/Änderungsdatum
- Dokumenttyp (Bericht, Besprechungsnotizen, Richtlinie usw.)
- Schlüsselthemen/Tags (per NLP)

Diese Metadaten ermöglichen:
- Bessere Suchfilterung
- Vertrauenswürdigkeitssignale
- Verwandte Dokumente entdecken

### Semantisches Caching

Häufig gestellte Fragen werden zwischengespeichert:
- Die gleiche Frage innerhalb von N Stunden gibt die zwischengespeicherte Antwort zurück
- Cache ist sich Dokumentenaktualisierungen bewusst (Invalidierung bei Quellenänderung)
- Reduziert Embedding- & LLM-Aufrufe
- Benutzer kann eine frische Antwort erzwingen

### Dokumentenablauf & Archivierung

Dokumente können markiert werden als:
- **Aktiv:** In Suchen eingeschlossen
- **Archiviert:** Durchsuchbar, aber als älter gekennzeichnet
- **Abgelaufen:** Aus Suchen entfernt (aber für Prüfzwecke behalten)
- **Sensibel:** Durch Berechtigungen eingeschränkt

---

## Indizierungsstatus & Überwachung

### Admin-Sichtbarkeit

Admins können einsehen:
- Welche Quellen aktiv sind, wann zuletzt indiziert wurde
- Gesamtanzahl der Dokumente in jeder Quelle
- Fehlgeschlagene Dokumente und Fehlerprotokolle
- Status der Indizierungswarteschlange
- Ressourcennutzung (CPU, Speicher, Festplatte)

### Indizierungswarnungen

Das System warnt Admins bei:
- Verbindungsfehlern zur Quelle (3 fehlgeschlagene Versuche)
- Großer Anzahl von Verarbeitungsfehlern (> 10% der Dokumente)
- Längerer Indizierung als erwartet (> 2 Stunden)
- Nahezu vollständiger Vektordatenbank-Speicher

### Indizierungsauslöser

Indizierung kann starten:
- Nach Zeitplan (täglich, stündlich usw.)
- Auf Abruf (manueller Admin-Auslöser)
- Per Webhook (Quellsystem benachrichtigt OPAA)
- Bei Dokumentänderung (Streaming, sofern unterstützt)
- Bei Benutzer-Upload (sofortige Verarbeitung, wenn ein Benutzer eine Datei hochlädt)

---

## Berechtigungen & Multi-Tenancy

### Bibliotheksbasierte Berechtigungen

Jedes indizierte Dokument gehört genau einer **Wissensbibliothek** (bestimmt durch die Quellzuordnung des Konnektors oder das Upload-Ziel). Berechtigungen werden auf Bibliotheksebene durchgesetzt:

- Benutzer finden nur Dokumente in Bibliotheken, auf die sie ein Recht haben — direkt, über eine Gruppe oder über eine organisationsweite Freigabe
- Der Bibliotheks-Filter ist in die Vektorsuche integriert (kein Nachfilter)
- Suchergebnisse lecken niemals über Bibliotheks- oder Organisationsgrenzen

### Bestände mehrfach verwenden

Ein Bestand, der an mehreren Stellen gebraucht wird, wird nicht kopiert: Dieselbe Wissensbibliothek wird in mehreren Spaces assoziiert oder an weitere Nutzer und Gruppen freigegeben. Eine Fassung, eine Pflegestelle, keine Chunk-Vervielfachung. Das frühere Konzept eines workspace-übergreifenden Dokument-Teilens ist damit gegenstandslos — siehe [Dokument-Teilen](./document-sharing.md) (überholt).

### Berechtigungen für Benutzer-hochgeladene Dokumente

Von Benutzern hochgeladene Dokumente folgen einem spezifischen Berechtigungsmodell:
- **Standard:** in die persönliche Wissensbibliothek des hochladenden Benutzers
- **Upload in eine geteilte Bibliothek:** möglich, wo der Benutzer mindestens `EDITOR` am Asset ist
- **Owner:** Der hochladende Benutzer ist immer der Dokument-Owner
- **Upload-Kontingente:** Pro Benutzer konfigurierbar mit einem globalen Standard
- **Weitergabe:** über die Rechte der Bibliothek, nicht über ein Teilen einzelner Dokumente

### Berechtigungen für Konnektor-Dokumente

Konnektor-indizierte Dokumente erben ihre Bibliothek aus der Quellzuordnung:
- Jede Quell-Untereinheit (z. B. Confluence-Space) wird genau einer Wissensbibliothek zugeordnet
- Wer an der Bibliothek `MANAGER` ist, kann einzelne Dokumente aus dem Index ausschließen; der Ausschluss wirkt an genau einer Stelle
- Die Freigabe-Obergrenze des System-Admins begrenzt, wie weit die Bibliothek geöffnet werden darf

### Duplikaterkennung

Wenn ein Benutzer ein Dokument hochlädt, führt OPAA eine Ähnlichkeitsprüfung gegen bestehende Dokumente durch, auf die der Benutzer Zugriff hat. Werden ähnliche Dokumente gefunden, wird der Benutzer vor Abschluss des Uploads benachrichtigt — dies hilft, doppelte Indizierung zu vermeiden (z. B. zwei Benutzer laden dieselben Besprechungsnotizen hoch).

---

## Performance & Skalierbarkeit

### Indizierungs-Performance

- **Kleine Organisation (100 Dokumente):** 5–10 Minuten
- **Mittelgroß (10.000 Dokumente):** 30–60 Minuten
- **Groß (100.000+ Dokumente):** Parallele Verarbeitung, nach Bedarf

### Abfrage-Performance

- **Vektorsuche (inkl. Bibliotheks-Filter):** < 500 ms für typische Abfragen
- **Re-Ranking:** + 50–100 ms
- **Gesamte Retrieval-Zeit:** < 1 Sekunde

Hinweis: Die Berechtigungsfilterung ist über den Metadatenfilter auf `library_id` in die Vektorsuche integriert und fügt keinen separaten Verarbeitungsschritt hinzu.

### Skalierbarkeit

Das System skaliert auf:
- Millionen von Dokumenten (über horizontale Skalierung)
- Tausende gleichzeitiger Benutzer (über verteilte Vektordatenbank)
- Mehrere Datenquellen gleichzeitig
- Große oder kleine Chunks (konfigurierbar)

---

## Integrationspunkte

- **Benutzer-Frontends:** Abgerufene Dokumente und Antworten bereitstellen
- **LLM-Integration:** Abgerufene Dokumente an LLM weitergeben
- **Zugangskontrolle:** Bibliotheksberechtigungen zur Abfragezeit durchsetzen
- **Deployment-Infrastruktur:** Speicherkonfiguration, Ressourcenzuweisung

---

## Geklärte Fragen

- **Speicherkontingente:** Ja, für manuelle Uploads. Upload-Limit ist pro Benutzer mit einem globalen Standard konfigurierbar.
- **Dokumentenversionierung:** Ja, idealerweise. Zusätzlich werden ähnliche Dokumente, die für den Benutzer sichtbar sind, beim Upload angezeigt, um Duplikate zu erkennen (siehe [Duplikaterkennung](#duplikaterkennung) oben).

---

## Offene Fragen / Zukünftige Erweiterungen

- Sollen wir Echtzeit-Indizierung (bei Dokumentenänderungen) vs. geplante Batch-Verarbeitung unterstützen?
- Soll Re-Ranking ein gelerntes Modell oder einfache Bewertung verwenden?
- Sollen wir Dokument-Clustering unterstützen (um verwandte Dokumente automatisch zu entdecken)?
- Sollen wir semantische Deduplizierung anbieten (redundante Dokumente automatisch entfernen)? *(Hinweis: grundlegende Quellenreferenz-Deduplizierung nach Dateiname ist bereits implementiert — siehe Issue #42)*
- Wie sollen sehr große Dokumente (100.000+ Seiten) behandelt werden?
- Sollen wir hybrides Retrieval unterstützen (Vektor- + Keyword-Suche kombiniert)?
- Sollen wir Massen-Import von einem lokalen Laufwerk des Benutzers unterstützen?

---

## Erfolgs-Metriken

- **Indizierungsvollständigkeit:** % der Quelldokumente erfolgreich indiziert
- **Retrieval-Latenz:** P95-Suchzeit < 500 ms
- **Relevanz:** % der abgerufenen Dokumente, die tatsächlich in der endgültigen Antwort verwendet wurden
- **Abdeckung:** Durchschnittliche Anzahl relevanter Dokumente pro Abfrage
- **Aktualität:** Medianzeit zwischen Dokumentänderung und Neuindizierung
