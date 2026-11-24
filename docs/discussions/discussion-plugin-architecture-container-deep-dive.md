# Deep Dive: Container-basierte Plugin-Architektur für OPAA Connectors

## 1. Einleitung

Dieses Dokument vertieft **Variante A (Container-basiert / Out-of-Process)** aus dem übergeordneten [Plugin-Architektur-Konzept](discussion-plugin-architecture.md) und beschreibt die konkrete Umsetzung als Plugin-Strategie für OPAA.

Behandelt werden:

- API-Contract zwischen Plugin und Host
- Datentransport inkl. großer Binärdateien
- Plugin-Paketierung und Distribution
- Marketplace-Konzept
- Plugin-SDK für Entwickler (Java + Python)
- Bewertung mit Pros und Cons

**Referenz:** GitHub Issue [#106](https://github.com/criew/opaa/issues/106) (Epic) und Sub-Issues #126–#130.

Dieses Dokument ist das Gegenstück zum [Wasm Deep Dive](discussion-plugin-architecture-wasm-deep-dive.md) und zum [PF4J Deep Dive](discussion-plugin-architecture-pf4j-deep-dive.md) und dient dem direkten Vergleich aller drei Ansätze.

---

## 2. Warum Container als Alternative

In dieser Variante wird jedes Plugin als eigenständiger Container (Docker/Podman) ausgeführt und kommuniziert per gRPC mit dem Spring Boot Backend. Das bringt maximale Isolation und vollständige Sprachunabhängigkeit.

- **Vollständige Prozess-Isolation** — Plugin-Abstürze, Memory-Leaks oder Sicherheitslücken betreffen nur den eigenen Container
- **Beliebige Programmiersprache** — Java, Python, Go, Rust, Node.js, Ruby — alles was in einem Container laufen kann
- **Beliebige Libraries** — keine Einschränkungen bei Dependencies, Reflection, nativen Bindings
- **Eigene Ressourcen-Limits** — CPU, RAM, Netzwerk pro Container steuerbar via Docker/Kubernetes
- **Bekanntes Deployment-Modell** — Container sind der De-facto-Standard für Microservice-Deployment

**Kommunikation:** gRPC (empfohlen für Performance und Streaming) mit protobuf-definiertem Contract.

---

## 3. API-Contract: Host ↔ Plugin

### 3.1 Architekturüberblick

```
┌──────────────────────────────┐     ┌──────────────────────────────┐
│     Host (Spring Boot)       │     │    Plugin (Container)        │
│                              │     │                              │
│  ├─ gRPC Client             ◄─────►  gRPC Server                 │
│  ├─ Container Manager        │     │  ├─ ConnectorService (impl)  │
│  │  (Docker/Podman API)      │     │  ├─ Beliebige Libraries      │
│  ├─ Scheduler (Pull-Polling) │     │  └─ Beliebige Sprache        │
│  ├─ Webhook Router (Push)    │     │                              │
│  ├─ Document Processor       │     │  Eigenes Netzwerk-Namespace  │
│  │  (Tika + Embeddings)      │     │  Eigenes Filesystem          │
│  ├─ Registry Client          │     │  Eigene Ressourcen-Limits    │
│  └─ Lifecycle Management     │     │                              │
└──────────────────────────────┘     └──────────────────────────────┘
        │                                       │
        └──── Docker Network (isoliert) ────────┘
```

### 3.2 Protocol Buffers Definition (der Contract)

Bei Container-Plugins **ist die protobuf-Definition der Contract**. Sie ist sprach-unabhängig und generiert Client/Server-Stubs für jede Sprache automatisch.

```protobuf
syntax = "proto3";
package opaa.connector.v1;

option java_package = "io.opaa.connector.grpc";
option go_package = "github.com/opaa/connector-sdk-go/grpc";

// ──────────────────────────────────────────────
// Der zentrale Service, den jedes Plugin implementiert
// ──────────────────────────────────────────────

service ConnectorService {

  // Liefert statische Metadaten über den Connector.
  rpc GetMetadata(Empty) returns (ConnectorMetadata);

  // Empfängt die Konfiguration vom Host.
  rpc Configure(PluginConfig) returns (ConfigureResult);

  // Prüft ob Konfiguration gültig und Verbindung möglich.
  rpc Validate(Empty) returns (ValidationResult);

  // Holt Dokumente von der Datenquelle (PULL).
  // Server-Streaming: Plugin sendet Dokumente als Stream.
  rpc Fetch(FetchRequest) returns (stream FetchResponse);

  // Verarbeitet eingehende Webhook-Events (PUSH).
  // Server-Streaming: Plugin kann mehrere Dokumente pro Event liefern.
  rpc OnEvent(WebhookEvent) returns (stream FetchResponse);

  // Health-Check für Container-Liveness.
  rpc Health(Empty) returns (HealthResponse);
}

// ──────────────────────────────────────────────
// Messages
// ──────────────────────────────────────────────

message Empty {}

message ConnectorMetadata {
  string name = 1;
  string version = 2;
  ConnectorMode mode = 3;
  string description = 4;
}

enum ConnectorMode {
  PULL = 0;
  PUSH = 1;
  HYBRID = 2;
}

message PluginConfig {
  // Konfiguration als JSON-String (flexibel, schema-frei)
  string config_json = 1;
}

message ConfigureResult {
  bool ok = 1;
  string message = 2;
}

message ValidationResult {
  bool ok = 1;
  string message = 2;
}

message FetchRequest {
  // Opakes Paginierungs-Token, leer beim ersten Aufruf
  string cursor = 1;
}

message FetchResponse {
  oneof payload {
    // Ein einzelnes Dokument
    DocumentMessage document = 1;
    // Löschung eines Dokuments
    DeletionMessage deletion = 2;
    // Paginierungs-Info (letztes Element im Stream)
    CursorMessage cursor = 3;
  }
}

message DocumentMessage {
  string id = 1;
  string title = 2;
  string content_type = 3;
  map<string, string> metadata = 4;
  string hash = 5;

  oneof content {
    // Textinhalt direkt
    string text_content = 10;
    // Binärdaten als Bytes (kleine Dateien)
    bytes binary_content = 11;
    // Referenz für große Dateien — Host lädt selbst herunter
    FetchInstruction fetch_instruction = 12;
  }
}

message FetchInstruction {
  string url = 1;
  string method = 2;
  map<string, string> headers = 3;
  int64 expected_size_bytes = 4;
}

message DeletionMessage {
  string document_id = 1;
}

message CursorMessage {
  string cursor = 1;
  bool has_more = 2;
}

message WebhookEvent {
  string type = 1;
  string payload_json = 2;
}

message HealthResponse {
  bool healthy = 1;
  string message = 2;
}
```

### 3.3 Warum gRPC und nicht REST

| Aspekt | gRPC | REST |
|--------|------|------|
| **Streaming** | Nativ (Server-Streaming für Fetch) | Nur via SSE oder Chunked Transfer |
| **Contract** | protobuf — typsicher, Code-Generierung | OpenAPI — weniger strikt |
| **Performance** | Binäres Protokoll (protobuf), HTTP/2 | JSON-Overhead, HTTP/1.1 |
| **Code-Generierung** | Automatisch für alle Sprachen | Tooling-abhängig (openapi-generator) |
| **Große Dateien** | `bytes`-Feld oder Streaming | Base64 in JSON oder Multipart |

gRPC's **Server-Streaming** ist der Schlüssel: Das Plugin kann Hunderte Dokumente einzeln streamen, ohne sie alle im Speicher zu halten. Der Host verarbeitet jedes Dokument sofort, sobald es ankommt.

### 3.4 Connector-Modi

Identisch zu den anderen Varianten:

| Modus | Beschreibung | Pflicht-RPCs |
|-------|-------------|--------------|
| `PULL` | Host pollt periodisch | `GetMetadata`, `Configure`, `Validate`, `Fetch` |
| `PUSH` | Externe Events via Webhook | `GetMetadata`, `Configure`, `Validate`, `OnEvent` |
| `HYBRID` | Beides | Alle RPCs |

Zusätzlich: `Health` RPC für Container-Liveness-Probes — der Host kann erkennen, ob ein Plugin-Container noch läuft und reagiert.

### 3.5 Datentransport via gRPC-Streaming

Der `Fetch` RPC nutzt **Server-Streaming**: Das Plugin sendet eine Sequenz von `FetchResponse`-Messages, jede enthält entweder ein Dokument, eine Löschung oder den Cursor.

```
Host                          Plugin-Container
────                          ────────────────
Fetch(cursor: "")  ──────►
                   ◄──────    FetchResponse { document: Page1 }
                   ◄──────    FetchResponse { document: Page2 }
                   ◄──────    FetchResponse { document: Attachment1 }
                   ◄──────    FetchResponse { cursor: "25", has_more: true }
                              (Stream endet)

Fetch(cursor: "25") ─────►
                   ◄──────    FetchResponse { document: Page3 }
                   ◄──────    FetchResponse { deletion: "old-page-7" }
                   ◄──────    FetchResponse { cursor: "", has_more: false }
                              (Stream endet)
```

Das hat einen massiven Vorteil: Weder Plugin noch Host müssen alle Dokumente gleichzeitig im Speicher halten. Bei 1000 Confluence-Seiten pro Fetch wird jede einzeln gestreamt und sofort verarbeitet.

---

## 4. Transport großer Binärdateien

### 4.1 Drei Transportmodi

| Modus | Wann | Wie | Overhead |
|-------|------|-----|----------|
| **Inline Text** | Markdown, HTML, Plain Text | `text_content` Feld in protobuf | Minimal (protobuf-Encoding) |
| **Inline Bytes** | Kleine Binärdaten (< 5 MB) | `binary_content` Feld (native `bytes`) | Minimal (kein Base64!) |
| **Reference** | Große Dateien (> 5 MB) | `fetch_instruction` mit URL + Headers | Keiner im Plugin |

### 4.2 Vorteil gegenüber Wasm: Native Bytes

protobuf hat einen nativen `bytes`-Typ — Binärdaten werden **ohne Base64-Encoding** als raw Bytes übertragen. Das spart 33% Overhead gegenüber JSON/Base64 im Wasm-Ansatz.

Für kleine bis mittlere Dateien (bis ~5 MB) ist Inline-Bytes effizient. Für größere Dateien bleibt Reference-based Fetching die beste Option, da gRPC-Messages standardmäßig auf 4 MB begrenzt sind (konfigurierbar, aber nicht empfehlenswert für sehr große Messages).

### 4.3 Reference-based Fetching

Identisch zum Wasm-Ansatz — das Plugin liefert eine Download-Anweisung, der Host lädt die Datei selbst herunter:

```protobuf
message FetchInstruction {
  string url = 1;           // https://wiki.example.com/download/att/99
  string method = 2;        // GET
  map<string, string> headers = 3;  // Authorization: Bearer eyJ...
  int64 expected_size_bytes = 4;    // 52428800
}
```

Der Host streamt die Datei direkt in Tika — kein doppeltes Buffering.

### 4.4 Sicherheit bei Netzwerkzugriff

Container bieten **Netzwerk-Isolation auf OS-Ebene** — die stärkste Form aller drei Varianten:

```yaml
# docker-compose.yml — Plugin-Container
services:
  confluence-connector:
    image: ghcr.io/opaa-plugins/confluence-connector:1.2.0
    networks:
      - plugin-network    # Isoliertes Netzwerk, nur Zugriff auf Host-gRPC
    dns:
      - 10.0.0.1          # Kontrollierter DNS — nur erlaubte Domains auflösbar
    deploy:
      resources:
        limits:
          cpus: "1.0"
          memory: 256M
```

Alternativ können **Network Policies** (Kubernetes) oder **iptables-Regeln** (Docker) den Netzwerkzugriff pro Plugin auf bestimmte Domains beschränken — erzwungen auf OS-Ebene, nicht auf Anwendungsebene wie bei Wasm.

### 4.5 Verantwortungsteilung

Identisch zu den anderen Varianten — das Plugin ist ein Daten-Lieferant:

```
Plugin-Container                Host
────────────────                ────
Datenquelle abfragen       →    gRPC-Stream empfangen
Dokumente streamen          →    Jedes Dokument einzeln verarbeiten
Content-Type liefern       →    Tika-Parsing (PDF, DOCX, etc.)
Source-URL, Hash liefern   →    Embedding generieren
                                In pgvector speichern
```

---

## 5. Plugin-Paketierung

### 5.1 Docker-Image als Plugin-Format

Container-Plugins sind **Docker-Images** — das natürlichste OCI-Format:

```dockerfile
# Dockerfile für ein Java-basiertes Plugin
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/confluence-connector-1.0.0.jar app.jar
COPY plugin.json /app/plugin.json
EXPOSE 50051
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```dockerfile
# Dockerfile für ein Python-basiertes Plugin
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY src/ /app/src/
COPY plugin.json /app/plugin.json
EXPOSE 50051
ENTRYPOINT ["python", "-m", "src.server"]
```

### 5.2 Image-Konventionen

Jedes Plugin-Image muss:

1. Einen **gRPC-Server auf Port 50051** starten
2. Den `ConnectorService` implementieren
3. Eine `/app/plugin.json` Datei enthalten (OPAA-Manifest)
4. Auf den `Health` RPC reagieren (Liveness-Probe)

### 5.3 OPAA Plugin-Manifest (`plugin.json`)

```json
{
  "apiVersion": "opaa.io/v1",
  "kind": "ConnectorPlugin",
  "metadata": {
    "name": "confluence-connector",
    "version": "1.2.0",
    "author": "OPAA Community",
    "license": "MIT",
    "description": "Imports pages from Atlassian Confluence",
    "homepage": "https://github.com/opaa-plugins/confluence-connector"
  },
  "runtime": {
    "engine": "container",
    "image": "ghcr.io/opaa-plugins/confluence-connector:1.2.0",
    "minHostVersion": "0.5.0",
    "resources": {
      "cpuLimit": "1.0",
      "memoryLimitMB": 256
    }
  },
  "connector": {
    "mode": "hybrid",
    "webhook": {
      "path": "/webhooks/confluence",
      "events": ["page:created", "page:updated", "page:removed"]
    }
  },
  "permissions": {
    "network": ["*.atlassian.net", "*.confluence.com"]
  },
  "configSchema": {
    "type": "object",
    "properties": {
      "baseUrl": { "type": "string", "title": "Confluence URL" },
      "apiToken": { "type": "string", "title": "API Token", "format": "secret" },
      "spaceKeys": {
        "type": "array",
        "items": { "type": "string" },
        "title": "Space Keys"
      }
    },
    "required": ["baseUrl", "apiToken", "spaceKeys"]
  }
}
```

### 5.4 Distribution

Container-Images **sind bereits OCI-Artifacts** — keine zusätzliche Paketierung nötig:

```bash
# Plugin veröffentlichen
docker build -t ghcr.io/opaa-plugins/confluence-connector:1.2.0 .
docker push ghcr.io/opaa-plugins/confluence-connector:1.2.0

# Plugin installieren (Host macht das automatisch)
docker pull ghcr.io/opaa-plugins/confluence-connector:1.2.0
docker run -d --name confluence-connector \
  --network opaa-plugins \
  --memory 256m --cpus 1.0 \
  ghcr.io/opaa-plugins/confluence-connector:1.2.0
```

Image-Signierung via **Docker Content Trust** oder **Cosign/Sigstore**:

```bash
# Signieren
cosign sign ghcr.io/opaa-plugins/confluence-connector:1.2.0

# Verifizieren (Host-Seite)
cosign verify ghcr.io/opaa-plugins/confluence-connector:1.2.0 \
  --key opaa-plugins-cosign.pub
```

### 5.5 Bundle-Struktur (Entwicklersicht)

```
confluence-connector/
├── src/                          # Plugin-Quellcode (beliebige Sprache)
├── proto/
│   └── connector.proto           # gRPC-Service-Definition (vom SDK)
├── plugin.json                   # OPAA-Manifest
├── Dockerfile
├── docker-compose.dev.yml        # Lokale Entwicklungsumgebung
├── icon.svg
└── README.md
```

---

## 6. Marketplace-Konzept

### 6.1 Stufe 1: Plugin Registry (Index)

Identisch zu den anderen Varianten — ein Git-Repository als zentraler Index:

```
opaa-plugin-registry/
├── plugins/
│   ├── confluence-connector.json
│   ├── dropbox-connector.json
│   └── github-connector.json
└── index.json
```

```json
{
  "name": "confluence-connector",
  "description": "Import pages from Confluence",
  "author": "OPAA Community",
  "verified": true,
  "image": "ghcr.io/opaa-plugins/confluence-connector",
  "latestVersion": "1.2.0",
  "categories": ["documentation", "wiki"],
  "downloads": 1523,
  "runtime": "container"
}
```

### 6.2 Stufe 2: Discovery API im Backend

```
GET    /api/v1/plugins/available            # Marketplace durchsuchen
GET    /api/v1/plugins/available/{name}     # Plugin-Details
POST   /api/v1/plugins/install              # Plugin installieren (docker pull + run)
GET    /api/v1/plugins/installed            # Installierte Plugins
POST   /api/v1/plugins/{id}/configure       # Plugin konfigurieren (gRPC Configure)
DELETE /api/v1/plugins/{id}                 # Plugin deinstallieren (docker rm)
GET    /api/v1/plugins/{id}/health          # Plugin-Status (gRPC Health)
GET    /api/v1/plugins/{id}/logs            # Container-Logs
```

Zusätzlich zu den anderen Varianten bietet Container-basierte Architektur:

- **`/health`-Endpoint** — da Plugins eigenständige Prozesse sind, muss ihr Status aktiv überwacht werden
- **`/logs`-Endpoint** — Container-Logs sind separat und müssen vom Host aggregiert werden

### 6.3 Stufe 3: Marketplace-UI im Frontend

Identisch zu den anderen Varianten, plus:

- Container-Status (Running, Stopped, Error, OOMKilled)
- Ressourcen-Verbrauch pro Plugin (CPU, RAM — via Docker Stats)
- Container-Logs-Ansicht
- Restart-Button

### 6.4 Enterprise-Features

- **Private Registry:** Harbor, GitLab Container Registry, AWS ECR (Air-Gapped)
- **Plugin-Allowlist:** Admin legt fest, welche Images erlaubt sind
- **Image-Signierung:** Docker Content Trust oder Cosign
- **Image-Scanning:** Trivy, Snyk Container — automatische Vulnerability-Scans
- **Network Policies:** Kubernetes NetworkPolicy oder Docker-Netzwerk-Isolation

Container-basierte Plugins haben den Vorteil, dass Enterprise-Kunden ihre bestehende Container-Sicherheits-Infrastruktur (Image-Scanning, Network Policies, Pod Security Standards) direkt wiederverwenden können.

### 6.5 Plugin-Updates

Konfigurierbares Update-Verhalten pro Plugin:

| Policy | Verhalten |
|--------|-----------|
| `manual` | Admin wird benachrichtigt, installiert selbst |
| `auto` | Jede neue Version wird automatisch installiert |
| `auto-patch` | Nur Patch-Versionen (1.2.x) automatisch, Minor/Major manuell |

Update-Ablauf:

1. Neues Image aus der Registry pullen
2. Cosign-Signatur prüfen
3. Laufende Fetch-Streams abschließen lassen (gRPC Graceful Shutdown)
4. Alten Container stoppen und entfernen
5. Neuen Container starten
6. `Health` RPC prüfen — bei Timeout Rollback (altes Image neu starten)
7. `Validate` RPC aufrufen — bei Fehler Rollback

Container-Updates sind naturgemäß langsamer als PF4J (Hot-Reload) oder Wasm (Millisekunden): Ein neuer Container braucht typischerweise 2–10 Sekunden zum Starten.

---

## 7. Plugin-SDK für Entwickler

### 7.1 SDK-Schichten

```
┌──────────────────────────────────────────┐
│  Plugin-Code (Geschäftslogik)            │  ← Entwickler schreibt nur das
│  + beliebige Libraries                   │
├──────────────────────────────────────────┤
│  OPAA Connector SDK                      │  ← Abstrahiert gRPC-Details
│  ├─ ConnectorBase (Basisklasse)          │
│  ├─ Typen (Document, FetchResult, etc.)  │
│  └─ Server-Bootstrap                     │
├──────────────────────────────────────────┤
│  gRPC Server + Protobuf                  │  ← Generierte Stubs
├──────────────────────────────────────────┤
│  Container (Docker/Podman)               │
└──────────────────────────────────────────┘
```

### 7.2 SDK-Varianten

Da Container jede Sprache unterstützen, kann das SDK in beliebig vielen Sprachen angeboten werden:

| Sprache | SDK-Paket | gRPC-Tooling | Image-Größe |
|---------|-----------|-------------|-------------|
| Java | `io.opaa:connector-sdk` (Maven) | `protobuf-maven-plugin` | ~150–300 MB (JRE) |
| Kotlin | `io.opaa:connector-sdk` (Maven) | `protobuf-maven-plugin` | ~150–300 MB (JRE) |
| Python | `opaa-connector-sdk` (PyPI) | `grpcio-tools` | ~80–150 MB |
| Go | `github.com/opaa/connector-sdk-go` | `protoc-gen-go-grpc` | ~10–30 MB (statisch) |
| Rust | `opaa-connector-sdk` (crates.io) | `tonic-build` | ~10–20 MB (statisch) |
| Node.js | `@opaa/connector-sdk` (npm) | `grpc-tools` | ~100–200 MB |

### 7.3 CLI-Tooling: `opaa-plugin`

```bash
# Neues Plugin-Projekt scaffolden
opaa-plugin init my-connector --lang java
opaa-plugin init my-connector --lang python
opaa-plugin init my-connector --lang go
# → Generiert Projektstruktur, Dockerfile, proto-Stubs, docker-compose.dev.yml

# Plugin lokal starten (Docker Compose)
opaa-plugin dev
# → Startet Plugin-Container + Mock-Host-Container
# → Host ruft Fetch/Validate auf, zeigt Ergebnisse
# → File-Watching mit automatischem Rebuild

# Plugin bauen (Docker Build)
opaa-plugin build
# → docker build, validiert Manifest, prüft gRPC-Health

# Plugin testen
opaa-plugin test
# → Startet Container, führt Contract-Tests durch
# → Prüft alle Pflicht-RPCs, Streaming-Verhalten, Health

# Plugin paketieren und veröffentlichen
opaa-plugin publish --registry ghcr.io/opaa-plugins
# → docker push + Cosign-Signatur
```

---

## 8. Konkretes Beispiel: Java Confluence Connector

### 8.1 Projektstruktur

```
confluence-connector/
├── src/main/java/
│   └── com/example/confluence/
│       ├── ConfluenceServer.java          # gRPC Server Bootstrap
│       ├── ConfluenceConnector.java       # ConnectorService-Implementierung
│       ├── ConfluenceConfig.java          # Config-POJO
│       ├── ConfluenceClient.java          # API-Client (Jackson + OkHttp)
│       └── model/
│           ├── ConfluencePage.java
│           └── ConfluenceAttachment.java
├── src/main/proto/
│   └── connector.proto                    # gRPC-Definition (vom SDK)
├── src/main/resources/
│   └── plugin.json
├── src/test/java/
│   └── com/example/confluence/
│       ├── ConfluenceConnectorTest.java
│       └── ConfluenceClientTest.java
├── Dockerfile
├── docker-compose.dev.yml
├── icon.svg
├── build.gradle.kts
└── README.md
```

### 8.2 Build-Konfiguration (`build.gradle.kts`)

```kotlin
plugins {
    java
    id("com.google.protobuf") version "0.9.4"
    id("com.github.johnrengelman.shadow") version "8.1.1"  // Fat-JAR
}

dependencies {
    // OPAA SDK — enthält proto-Definitionen und Basisklassen
    implementation("io.opaa:connector-sdk:1.0.0")

    // gRPC
    implementation("io.grpc:grpc-netty-shaded:1.64.0")
    implementation("io.grpc:grpc-protobuf:1.64.0")
    implementation("io.grpc:grpc-stub:1.64.0")

    // Plugin-eigene Dependencies — volle Freiheit
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.guava:guava:33.0.0-jre")

    // Test
    testImplementation("io.opaa:connector-sdk-testing:1.0.0")
    testImplementation("io.grpc:grpc-testing:1.64.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.assertj:assertj-core:3.26.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.27.0" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.64.0" }
    }
    generateProtoTasks {
        all().forEach { it.plugins { create("grpc") } }
    }
}
```

### 8.3 gRPC Server Bootstrap

```java
package com.example.confluence;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startet den gRPC-Server im Container.
 * Port 50051 ist die Konvention für OPAA-Plugins.
 */
public class ConfluenceServer {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceServer.class);
    private static final int PORT = 50051;

    public static void main(String[] args) throws Exception {
        Server server = ServerBuilder.forPort(PORT)
            .addService(new ConfluenceConnector())
            .build()
            .start();

        log.info("Confluence Connector gRPC server started on port {}", PORT);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down gRPC server...");
            server.shutdown();
        }));

        server.awaitTermination();
    }
}
```

### 8.4 Config-POJO

```java
package com.example.confluence;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ConfluenceConfig {

    @JsonProperty("baseUrl")
    private String baseUrl;

    @JsonProperty("apiToken")
    private String apiToken;

    @JsonProperty("spaceKeys")
    private List<String> spaceKeys;

    public String getBaseUrl() { return baseUrl; }
    public String getApiToken() { return apiToken; }
    public List<String> getSpaceKeys() { return spaceKeys; }
}
```

### 8.5 API-Client

```java
package com.example.confluence;

import com.example.confluence.model.ConfluenceAttachment;
import com.example.confluence.model.ConfluencePage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ConfluenceClient {

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiToken;

    public ConfluenceClient(String baseUrl, String apiToken) {
        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
        this.http = new OkHttpClient();
        this.mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
    }

    public PageResult fetchPages(List<String> spaceKeys, int start, int limit) {
        HttpUrl url = HttpUrl.parse(baseUrl + "/rest/api/content").newBuilder()
            .addQueryParameter("spaceKey", String.join(",", spaceKeys))
            .addQueryParameter("start", String.valueOf(start))
            .addQueryParameter("limit", String.valueOf(limit))
            .addQueryParameter("expand",
                "body.storage,version,metadata.labels,children.attachment")
            .build();

        JsonNode root = executeRequest(url);
        List<ConfluencePage> pages = new ArrayList<>();
        for (JsonNode node : root.get("results")) {
            pages.add(mapper.treeToValue(node, ConfluencePage.class));
        }

        return new PageResult(pages, root.get("size").asInt());
    }

    public ConfluencePage fetchPage(String pageId) {
        HttpUrl url = HttpUrl.parse(baseUrl + "/rest/api/content/" + pageId)
            .newBuilder()
            .addQueryParameter("expand", "body.storage,version,metadata.labels")
            .build();

        JsonNode root = executeRequest(url);
        return mapper.treeToValue(root, ConfluencePage.class);
    }

    public int fetchSpaceCount() {
        HttpUrl url = HttpUrl.parse(baseUrl + "/rest/api/space").newBuilder().build();
        JsonNode root = executeRequest(url);
        return root.get("size").asInt();
    }

    public String getBaseUrl() { return baseUrl; }
    public String getApiToken() { return apiToken; }

    private JsonNode executeRequest(HttpUrl url) {
        Request request = new Request.Builder()
            .url(url)
            .header("Authorization", "Bearer " + apiToken)
            .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ConnectorException(
                    "HTTP " + response.code() + ": " + response.message());
            }
            return mapper.readTree(response.body().string());
        } catch (IOException e) {
            throw new ConnectorException("Request failed: " + e.getMessage(), e);
        }
    }

    public record PageResult(List<ConfluencePage> pages, int size) {}
}
```

### 8.6 Connector-Implementierung (gRPC Service)

```java
package com.example.confluence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.stub.StreamObserver;
import io.opaa.connector.grpc.*;
import io.opaa.connector.grpc.ConnectorServiceGrpc.ConnectorServiceImplBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.HexFormat;

public class ConfluenceConnector extends ConnectorServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceConnector.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private ConfluenceConfig config;
    private ConfluenceClient client;

    // ──────────────────────────────────────────────
    // METADATA
    // ──────────────────────────────────────────────

    @Override
    public void getMetadata(Empty request, StreamObserver<ConnectorMetadata> observer) {
        observer.onNext(ConnectorMetadata.newBuilder()
            .setName("confluence-connector")
            .setVersion("1.0.0")
            .setMode(ConnectorMode.HYBRID)
            .setDescription("Imports Confluence pages and attachments")
            .build());
        observer.onCompleted();
    }

    // ──────────────────────────────────────────────
    // CONFIGURE
    // ──────────────────────────────────────────────

    @Override
    public void configure(PluginConfig request, StreamObserver<ConfigureResult> observer) {
        try {
            this.config = mapper.readValue(
                request.getConfigJson(), ConfluenceConfig.class);
            this.client = new ConfluenceClient(
                config.getBaseUrl(), config.getApiToken());

            observer.onNext(ConfigureResult.newBuilder()
                .setOk(true)
                .setMessage("Configured successfully")
                .build());
        } catch (Exception e) {
            observer.onNext(ConfigureResult.newBuilder()
                .setOk(false)
                .setMessage("Invalid config: " + e.getMessage())
                .build());
        }
        observer.onCompleted();
    }

    // ──────────────────────────────────────────────
    // VALIDATE
    // ──────────────────────────────────────────────

    @Override
    public void validate(Empty request, StreamObserver<ValidationResult> observer) {
        try {
            int count = client.fetchSpaceCount();
            observer.onNext(ValidationResult.newBuilder()
                .setOk(true)
                .setMessage("Connected. " + count + " spaces found.")
                .build());
        } catch (ConnectorException e) {
            observer.onNext(ValidationResult.newBuilder()
                .setOk(false)
                .setMessage("Connection failed: " + e.getMessage())
                .build());
        }
        observer.onCompleted();
    }

    // ──────────────────────────────────────────────
    // PULL (Server-Streaming)
    // ──────────────────────────────────────────────

    @Override
    public void fetch(FetchRequest request, StreamObserver<FetchResponse> observer) {
        int pageSize = 25;
        int start = request.getCursor().isEmpty()
            ? 0
            : Integer.parseInt(request.getCursor());

        try {
            var result = client.fetchPages(config.getSpaceKeys(), start, pageSize);

            for (var page : result.pages()) {
                // Seiten-Inhalt als Text streamen
                observer.onNext(FetchResponse.newBuilder()
                    .setDocument(pageToDocument(page))
                    .build());

                // Attachments als References streamen
                for (var att : page.getAttachments()) {
                    observer.onNext(FetchResponse.newBuilder()
                        .setDocument(attachmentToDocument(page, att))
                        .build());
                }
            }

            // Cursor als letztes Element
            boolean hasMore = result.size() == pageSize;
            String nextCursor = hasMore ? String.valueOf(start + pageSize) : "";

            observer.onNext(FetchResponse.newBuilder()
                .setCursor(CursorMessage.newBuilder()
                    .setCursor(nextCursor)
                    .setHasMore(hasMore)
                    .build())
                .build());

            observer.onCompleted();

        } catch (Exception e) {
            log.error("Fetch failed", e);
            observer.onError(io.grpc.Status.INTERNAL
                .withDescription(e.getMessage())
                .asRuntimeException());
        }
    }

    // ──────────────────────────────────────────────
    // PUSH (Server-Streaming)
    // ──────────────────────────────────────────────

    @Override
    public void onEvent(WebhookEvent request, StreamObserver<FetchResponse> observer) {
        try {
            String pageId = mapper.readTree(request.getPayloadJson())
                .get("page").get("id").asText();

            if ("page:removed".equals(request.getType())) {
                observer.onNext(FetchResponse.newBuilder()
                    .setDeletion(DeletionMessage.newBuilder()
                        .setDocumentId("confluence-page-" + pageId)
                        .build())
                    .build());
            } else {
                var page = client.fetchPage(pageId);
                observer.onNext(FetchResponse.newBuilder()
                    .setDocument(pageToDocument(page))
                    .build());
            }

            observer.onNext(FetchResponse.newBuilder()
                .setCursor(CursorMessage.newBuilder()
                    .setCursor("")
                    .setHasMore(false)
                    .build())
                .build());

            observer.onCompleted();

        } catch (Exception e) {
            log.error("Event handling failed", e);
            observer.onError(io.grpc.Status.INTERNAL
                .withDescription(e.getMessage())
                .asRuntimeException());
        }
    }

    // ──────────────────────────────────────────────
    // HEALTH
    // ──────────────────────────────────────────────

    @Override
    public void health(Empty request, StreamObserver<HealthResponse> observer) {
        observer.onNext(HealthResponse.newBuilder()
            .setHealthy(true)
            .setMessage("Running")
            .build());
        observer.onCompleted();
    }

    // ──────────────────────────────────────────────
    // Hilfsmethoden
    // ──────────────────────────────────────────────

    private DocumentMessage pageToDocument(ConfluencePage page) {
        String body = page.getBodyHtml();

        return DocumentMessage.newBuilder()
            .setId("confluence-page-" + page.getId())
            .setTitle(page.getTitle())
            .setTextContent(body)
            .setContentType("text/html")
            .setHash(sha256(body))
            .putMetadata("author", page.getAuthorName())
            .putMetadata("lastModified", page.getLastModified().toString())
            .putMetadata("sourceUrl",
                config.getBaseUrl() + page.getWebUiLink())
            .build();
    }

    private DocumentMessage attachmentToDocument(
            ConfluencePage page, ConfluenceAttachment att) {

        return DocumentMessage.newBuilder()
            .setId("confluence-att-" + att.getId())
            .setTitle(att.getTitle())
            .setContentType(att.getMediaType())
            .setFetchInstruction(FetchInstruction.newBuilder()
                .setUrl(client.getBaseUrl() + "/download"
                    + att.getDownloadPath())
                .setMethod("GET")
                .putHeaders("Authorization",
                    "Bearer " + client.getApiToken())
                .setExpectedSizeBytes(att.getFileSize())
                .build())
            .setHash("sha256:" + att.getHash())
            .putMetadata("parentDocument",
                "confluence-page-" + page.getId())
            .putMetadata("sourceUrl",
                config.getBaseUrl() + att.getWebUiLink())
            .build();
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
```

### 8.7 Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="OPAA Confluence Connector"
LABEL org.opencontainers.image.version="1.0.0"
LABEL org.opencontainers.image.vendor="OPAA Community"

WORKDIR /app
COPY build/libs/confluence-connector-1.0.0-all.jar app.jar
COPY src/main/resources/plugin.json /app/plugin.json

EXPOSE 50051

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD grpc_health_probe -addr=:50051 || exit 1

ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
```

### 8.8 Tests

```java
package com.example.confluence;

import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.testing.GrpcCleanupRule;
import io.opaa.connector.grpc.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ConfluenceConnectorTest {

    private MockWebServer mockApi;
    private ConnectorServiceGrpc.ConnectorServiceBlockingStub stub;
    private ManagedChannel channel;

    @BeforeEach
    void setUp() throws Exception {
        mockApi = new MockWebServer();
        mockApi.start();

        // In-Process gRPC Server (kein Docker nötig für Tests)
        String serverName = InProcessServerBuilder.generateName();
        var server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(new ConfluenceConnector())
            .build()
            .start();

        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build();

        stub = ConnectorServiceGrpc.newBlockingStub(channel);

        // Plugin konfigurieren
        String configJson = """
            {
              "baseUrl": "%s",
              "apiToken": "test-token",
              "spaceKeys": ["DEV", "OPS"]
            }
            """.formatted(mockApi.url("/").toString().replaceAll("/$", ""));

        ConfigureResult configResult = stub.configure(
            PluginConfig.newBuilder().setConfigJson(configJson).build());
        assertThat(configResult.getOk()).isTrue();
    }

    @AfterEach
    void tearDown() throws IOException {
        channel.shutdownNow();
        mockApi.shutdown();
    }

    @Test
    void metadataReturnsConnectorInfo() {
        ConnectorMetadata metadata = stub.getMetadata(Empty.getDefaultInstance());

        assertThat(metadata.getName()).isEqualTo("confluence-connector");
        assertThat(metadata.getMode()).isEqualTo(ConnectorMode.HYBRID);
    }

    @Test
    void fetchStreamsPagesAndCursor() {
        mockApi.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("api-response.json")));

        List<FetchResponse> responses = collectStream(
            stub.fetch(FetchRequest.getDefaultInstance()));

        // Letzte Response ist der Cursor
        FetchResponse last = responses.get(responses.size() - 1);
        assertThat(last.hasCursor()).isTrue();
        assertThat(last.getCursor().getHasMore()).isFalse();

        // Vorherige Responses sind Dokumente
        List<FetchResponse> documents = responses.subList(0, responses.size() - 1);
        assertThat(documents).isNotEmpty();
        assertThat(documents.get(0).getDocument().getTitle()).isEqualTo("Test Page");
        assertThat(documents.get(0).getDocument().getContentType()).isEqualTo("text/html");
    }

    @Test
    void fetchWithPagination() {
        mockApi.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("page-1.json")));  // size: 25
        mockApi.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("page-2.json")));  // size: 10

        // Erste Seite
        List<FetchResponse> page1 = collectStream(
            stub.fetch(FetchRequest.getDefaultInstance()));
        FetchResponse cursor1 = page1.get(page1.size() - 1);
        assertThat(cursor1.getCursor().getHasMore()).isTrue();
        assertThat(cursor1.getCursor().getCursor()).isEqualTo("25");

        // Zweite Seite
        List<FetchResponse> page2 = collectStream(
            stub.fetch(FetchRequest.newBuilder()
                .setCursor(cursor1.getCursor().getCursor())
                .build()));
        FetchResponse cursor2 = page2.get(page2.size() - 1);
        assertThat(cursor2.getCursor().getHasMore()).isFalse();
    }

    @Test
    void attachmentsUseFetchInstruction() {
        mockApi.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("page-with-attachment.json")));

        List<FetchResponse> responses = collectStream(
            stub.fetch(FetchRequest.getDefaultInstance()));

        FetchResponse attachment = responses.stream()
            .filter(r -> r.hasDocument()
                && r.getDocument().getId().startsWith("confluence-att-"))
            .findFirst().orElseThrow();

        assertThat(attachment.getDocument().hasFetchInstruction()).isTrue();
        assertThat(attachment.getDocument().getFetchInstruction().getUrl())
            .contains("/download");
    }

    @Test
    void onEventDeleteStreamsDelition() {
        WebhookEvent event = WebhookEvent.newBuilder()
            .setType("page:removed")
            .setPayloadJson("""
                {"page": {"id": "42"}}
                """)
            .build();

        List<FetchResponse> responses = collectStream(stub.onEvent(event));

        FetchResponse deletion = responses.stream()
            .filter(FetchResponse::hasDeletion)
            .findFirst().orElseThrow();

        assertThat(deletion.getDeletion().getDocumentId())
            .isEqualTo("confluence-page-42");
    }

    @Test
    void onEventUpdateStreamsUpdatedPage() {
        mockApi.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("single-page.json")));

        WebhookEvent event = WebhookEvent.newBuilder()
            .setType("page:updated")
            .setPayloadJson("""
                {"page": {"id": "42"}}
                """)
            .build();

        List<FetchResponse> responses = collectStream(stub.onEvent(event));

        FetchResponse document = responses.stream()
            .filter(FetchResponse::hasDocument)
            .findFirst().orElseThrow();

        assertThat(document.getDocument().getId()).isEqualTo("confluence-page-42");
    }

    @Test
    void validateSucceeds() {
        mockApi.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {"size": 3, "results": []}
                """));

        ValidationResult result = stub.validate(Empty.getDefaultInstance());

        assertThat(result.getOk()).isTrue();
        assertThat(result.getMessage()).contains("3 spaces");
    }

    @Test
    void validateFailsWithBadCredentials() {
        mockApi.enqueue(new MockResponse().setResponseCode(401));

        ValidationResult result = stub.validate(Empty.getDefaultInstance());

        assertThat(result.getOk()).isFalse();
        assertThat(result.getMessage()).contains("Connection failed");
    }

    @Test
    void healthReturnsHealthy() {
        HealthResponse health = stub.health(Empty.getDefaultInstance());

        assertThat(health.getHealthy()).isTrue();
    }

    private List<FetchResponse> collectStream(Iterator<FetchResponse> iterator) {
        List<FetchResponse> responses = new ArrayList<>();
        iterator.forEachRemaining(responses::add);
        return responses;
    }
}
```

---

## 9. Konkretes Beispiel: Python Confluence Connector

Python ist die natürliche zweite Sprache für Container-Plugins — große Community, exzellente HTTP-Libraries, populär für Integrationen und Automatisierung. Bei Wasm ist Python nicht möglich, bei PF4J ebenfalls nicht — nur Container ermöglichen dies.

### 9.1 Projektstruktur

```
confluence-connector-python/
├── src/
│   ├── __init__.py
│   ├── server.py                  # gRPC Server Bootstrap
│   ├── connector.py               # ConnectorService-Implementierung
│   ├── confluence_client.py       # API-Client (requests)
│   └── generated/                 # Generierte protobuf/gRPC Stubs
│       ├── connector_pb2.py
│       └── connector_pb2_grpc.py
├── tests/
│   ├── test_connector.py
│   └── fixtures/
│       └── api_response.json
├── proto/
│   └── connector.proto
├── plugin.json
├── Dockerfile
├── docker-compose.dev.yml
├── requirements.txt
├── icon.svg
└── README.md
```

### 9.2 Dependencies (`requirements.txt`)

```
grpcio==1.64.0
grpcio-tools==1.64.0
protobuf==5.27.0
requests==2.32.0
opaa-connector-sdk==1.0.0
```

### 9.3 gRPC Server Bootstrap

```python
"""gRPC Server für den Confluence Connector."""

import logging
from concurrent import futures

import grpc

from src.connector import ConfluenceConnector
from src.generated import connector_pb2_grpc

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

PORT = 50051


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    connector_pb2_grpc.add_ConnectorServiceServicer_to_server(
        ConfluenceConnector(), server
    )
    server.add_insecure_port(f"[::]:{PORT}")
    server.start()
    logger.info("Confluence Connector gRPC server started on port %d", PORT)
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
```

### 9.4 API-Client

```python
"""Typsicherer Confluence REST API Client."""

import logging
from dataclasses import dataclass

import requests

logger = logging.getLogger(__name__)


@dataclass
class ConfluencePage:
    id: str
    title: str
    body_html: str
    author_name: str
    last_modified: str
    web_ui_link: str
    labels: list[str]
    attachments: list["ConfluenceAttachment"]


@dataclass
class ConfluenceAttachment:
    id: str
    title: str
    media_type: str
    download_path: str
    file_size: int
    hash: str
    web_ui_link: str


@dataclass
class PageResult:
    pages: list[ConfluencePage]
    size: int


class ConfluenceClient:
    def __init__(self, base_url: str, api_token: str):
        self.base_url = base_url
        self.api_token = api_token
        self.session = requests.Session()
        self.session.headers["Authorization"] = f"Bearer {api_token}"

    def fetch_pages(
        self, space_keys: list[str], start: int, limit: int
    ) -> PageResult:
        response = self.session.get(
            f"{self.base_url}/rest/api/content",
            params={
                "spaceKey": ",".join(space_keys),
                "start": start,
                "limit": limit,
                "expand": "body.storage,version,metadata.labels,"
                "children.attachment",
            },
        )
        response.raise_for_status()
        data = response.json()

        pages = [self._parse_page(item) for item in data["results"]]
        return PageResult(pages=pages, size=data["size"])

    def fetch_page(self, page_id: str) -> ConfluencePage:
        response = self.session.get(
            f"{self.base_url}/rest/api/content/{page_id}",
            params={"expand": "body.storage,version,metadata.labels"},
        )
        response.raise_for_status()
        return self._parse_page(response.json())

    def fetch_space_count(self) -> int:
        response = self.session.get(f"{self.base_url}/rest/api/space")
        response.raise_for_status()
        return response.json()["size"]

    def _parse_page(self, data: dict) -> ConfluencePage:
        attachments = []
        att_data = data.get("children", {}).get("attachment", {})
        for att in att_data.get("results", []):
            attachments.append(
                ConfluenceAttachment(
                    id=att["id"],
                    title=att["title"],
                    media_type=att["mediaType"],
                    download_path=att["_links"]["download"],
                    file_size=att["extensions"]["fileSize"],
                    hash=att["extensions"].get("hash", ""),
                    web_ui_link=att["_links"]["webui"],
                )
            )

        labels = [
            label["name"]
            for label in data.get("metadata", {})
            .get("labels", {})
            .get("results", [])
        ]

        return ConfluencePage(
            id=data["id"],
            title=data["title"],
            body_html=data["body"]["storage"]["value"],
            author_name=data["version"]["by"]["displayName"],
            last_modified=data["version"]["when"],
            web_ui_link=data["_links"]["webui"],
            labels=labels,
            attachments=attachments,
        )
```

### 9.5 Connector-Implementierung (gRPC Service)

```python
"""Confluence Connector — gRPC Service-Implementierung."""

import hashlib
import json
import logging

import grpc

from src.confluence_client import ConfluenceClient
from src.generated import connector_pb2 as pb
from src.generated import connector_pb2_grpc

logger = logging.getLogger(__name__)


class ConfluenceConnector(connector_pb2_grpc.ConnectorServiceServicer):
    def __init__(self):
        self.config = None
        self.client = None

    # ──────────────────────────────────────────────
    # METADATA
    # ──────────────────────────────────────────────

    def GetMetadata(self, request, context):
        return pb.ConnectorMetadata(
            name="confluence-connector",
            version="1.0.0",
            mode=pb.ConnectorMode.HYBRID,
            description="Imports Confluence pages and attachments",
        )

    # ──────────────────────────────────────────────
    # CONFIGURE
    # ──────────────────────────────────────────────

    def Configure(self, request, context):
        try:
            self.config = json.loads(request.config_json)
            self.client = ConfluenceClient(
                base_url=self.config["baseUrl"],
                api_token=self.config["apiToken"],
            )
            return pb.ConfigureResult(ok=True, message="Configured successfully")
        except Exception as e:
            return pb.ConfigureResult(
                ok=False, message=f"Invalid config: {e}"
            )

    # ──────────────────────────────────────────────
    # VALIDATE
    # ──────────────────────────────────────────────

    def Validate(self, request, context):
        try:
            count = self.client.fetch_space_count()
            return pb.ValidationResult(
                ok=True, message=f"Connected. {count} spaces found."
            )
        except Exception as e:
            return pb.ValidationResult(
                ok=False, message=f"Connection failed: {e}"
            )

    # ──────────────────────────────────────────────
    # PULL (Server-Streaming)
    # ──────────────────────────────────────────────

    def Fetch(self, request, context):
        page_size = 25
        start = int(request.cursor) if request.cursor else 0

        try:
            result = self.client.fetch_pages(
                self.config["spaceKeys"], start, page_size
            )

            for page in result.pages:
                # Seiten-Inhalt streamen
                yield pb.FetchResponse(
                    document=self._page_to_document(page)
                )

                # Attachments als References streamen
                for att in page.attachments:
                    yield pb.FetchResponse(
                        document=self._attachment_to_document(page, att)
                    )

            # Cursor als letztes Element
            has_more = result.size == page_size
            next_cursor = str(start + page_size) if has_more else ""

            yield pb.FetchResponse(
                cursor=pb.CursorMessage(
                    cursor=next_cursor, has_more=has_more
                )
            )

        except Exception as e:
            logger.error("Fetch failed: %s", e)
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    # ──────────────────────────────────────────────
    # PUSH (Server-Streaming)
    # ──────────────────────────────────────────────

    def OnEvent(self, request, context):
        try:
            payload = json.loads(request.payload_json)
            page_id = payload["page"]["id"]

            if request.type == "page:removed":
                yield pb.FetchResponse(
                    deletion=pb.DeletionMessage(
                        document_id=f"confluence-page-{page_id}"
                    )
                )
            else:
                page = self.client.fetch_page(page_id)
                yield pb.FetchResponse(
                    document=self._page_to_document(page)
                )

            yield pb.FetchResponse(
                cursor=pb.CursorMessage(cursor="", has_more=False)
            )

        except Exception as e:
            logger.error("Event handling failed: %s", e)
            context.abort(grpc.StatusCode.INTERNAL, str(e))

    # ──────────────────────────────────────────────
    # HEALTH
    # ──────────────────────────────────────────────

    def Health(self, request, context):
        return pb.HealthResponse(healthy=True, message="Running")

    # ──────────────────────────────────────────────
    # Hilfsmethoden
    # ──────────────────────────────────────────────

    def _page_to_document(self, page):
        body = page.body_html
        return pb.DocumentMessage(
            id=f"confluence-page-{page.id}",
            title=page.title,
            text_content=body,
            content_type="text/html",
            hash=self._sha256(body),
            metadata={
                "author": page.author_name,
                "lastModified": page.last_modified,
                "sourceUrl": f"{self.config['baseUrl']}{page.web_ui_link}",
            },
        )

    def _attachment_to_document(self, page, att):
        return pb.DocumentMessage(
            id=f"confluence-att-{att.id}",
            title=att.title,
            content_type=att.media_type,
            fetch_instruction=pb.FetchInstruction(
                url=f"{self.client.base_url}/download{att.download_path}",
                method="GET",
                headers={
                    "Authorization": f"Bearer {self.client.api_token}"
                },
                expected_size_bytes=att.file_size,
            ),
            hash=f"sha256:{att.hash}",
            metadata={
                "parentDocument": f"confluence-page-{page.id}",
                "sourceUrl": (
                    f"{self.config['baseUrl']}{att.web_ui_link}"
                ),
            },
        )

    def _sha256(self, text: str) -> str:
        digest = hashlib.sha256(text.encode()).hexdigest()
        return f"sha256:{digest}"
```

### 9.6 Dockerfile

```dockerfile
FROM python:3.12-slim

LABEL org.opencontainers.image.title="OPAA Confluence Connector (Python)"
LABEL org.opencontainers.image.version="1.0.0"
LABEL org.opencontainers.image.vendor="OPAA Community"

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY src/ /app/src/
COPY plugin.json /app/plugin.json

EXPOSE 50051

HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
  CMD python -c "import grpc; ch=grpc.insecure_channel('localhost:50051'); grpc.channel_ready_future(ch).result(timeout=2)" || exit 1

ENTRYPOINT ["python", "-m", "src.server"]
```

### 9.7 Tests

```python
"""Tests für den Confluence Connector."""

import json
from unittest.mock import patch, MagicMock

import grpc
import pytest
from grpc import StatusCode
from grpc_testing import server_from_dictionary, strict_real_time

from src.connector import ConfluenceConnector
from src.generated import connector_pb2 as pb
from src.generated import connector_pb2_grpc


@pytest.fixture
def connector():
    """Konfigurierter Connector mit Mock-Client."""
    conn = ConfluenceConnector()
    conn.Configure(
        pb.PluginConfig(
            config_json=json.dumps(
                {
                    "baseUrl": "https://wiki.example.com",
                    "apiToken": "test-token",
                    "spaceKeys": ["DEV", "OPS"],
                }
            )
        ),
        context=MagicMock(),
    )
    return conn


class TestMetadata:
    def test_returns_connector_info(self, connector):
        metadata = connector.GetMetadata(pb.Empty(), context=MagicMock())

        assert metadata.name == "confluence-connector"
        assert metadata.mode == pb.ConnectorMode.HYBRID


class TestFetch:
    @patch("src.connector.ConfluenceClient")
    def test_streams_pages(self, mock_client_cls, connector):
        mock_client = connector.client
        mock_client.fetch_pages.return_value = MagicMock(
            pages=[
                MagicMock(
                    id="1",
                    title="Test Page",
                    body_html="<p>Hello</p>",
                    author_name="Admin",
                    last_modified="2026-01-01T00:00:00Z",
                    web_ui_link="/pages/1",
                    labels=[],
                    attachments=[],
                )
            ],
            size=1,
        )

        responses = list(
            connector.Fetch(pb.FetchRequest(), context=MagicMock())
        )

        # Dokument + Cursor
        assert len(responses) == 2
        assert responses[0].document.title == "Test Page"
        assert responses[0].document.content_type == "text/html"
        assert responses[1].cursor.has_more is False

    @patch("src.connector.ConfluenceClient")
    def test_pagination(self, mock_client_cls, connector):
        mock_client = connector.client
        mock_client.fetch_pages.return_value = MagicMock(
            pages=[MagicMock(
                id=str(i), title=f"Page {i}", body_html="<p>...</p>",
                author_name="Admin", last_modified="2026-01-01T00:00:00Z",
                web_ui_link=f"/pages/{i}", labels=[], attachments=[],
            ) for i in range(25)],
            size=25,
        )

        responses = list(
            connector.Fetch(pb.FetchRequest(), context=MagicMock())
        )

        cursor = responses[-1].cursor
        assert cursor.has_more is True
        assert cursor.cursor == "25"

    @patch("src.connector.ConfluenceClient")
    def test_attachments_use_fetch_instruction(
        self, mock_client_cls, connector
    ):
        mock_att = MagicMock(
            id="99",
            title="diagram.pdf",
            media_type="application/pdf",
            download_path="/attachments/99/diagram.pdf",
            file_size=52428800,
            hash="abc123",
            web_ui_link="/attachments/99",
        )
        mock_client = connector.client
        mock_client.fetch_pages.return_value = MagicMock(
            pages=[
                MagicMock(
                    id="1",
                    title="Test Page",
                    body_html="<p>Hello</p>",
                    author_name="Admin",
                    last_modified="2026-01-01T00:00:00Z",
                    web_ui_link="/pages/1",
                    labels=[],
                    attachments=[mock_att],
                )
            ],
            size=1,
        )

        responses = list(
            connector.Fetch(pb.FetchRequest(), context=MagicMock())
        )

        attachment = next(
            r
            for r in responses
            if r.HasField("document")
            and r.document.id.startswith("confluence-att-")
        )

        assert attachment.document.HasField("fetch_instruction")
        assert "/download" in attachment.document.fetch_instruction.url


class TestOnEvent:
    def test_delete_streams_deletion(self, connector):
        event = pb.WebhookEvent(
            type="page:removed",
            payload_json='{"page": {"id": "42"}}',
        )

        responses = list(connector.OnEvent(event, context=MagicMock()))

        deletion = next(r for r in responses if r.HasField("deletion"))
        assert deletion.deletion.document_id == "confluence-page-42"

    @patch("src.connector.ConfluenceClient")
    def test_update_streams_page(self, mock_client_cls, connector):
        mock_client = connector.client
        mock_client.fetch_page.return_value = MagicMock(
            id="42",
            title="Updated Page",
            body_html="<p>Updated</p>",
            author_name="Admin",
            last_modified="2026-03-08T10:00:00Z",
            web_ui_link="/pages/42",
            labels=[],
            attachments=[],
        )

        event = pb.WebhookEvent(
            type="page:updated",
            payload_json='{"page": {"id": "42"}}',
        )

        responses = list(connector.OnEvent(event, context=MagicMock()))

        document = next(r for r in responses if r.HasField("document"))
        assert document.document.id == "confluence-page-42"


class TestValidate:
    def test_succeeds(self, connector):
        connector.client.fetch_space_count.return_value = 3

        result = connector.Validate(pb.Empty(), context=MagicMock())

        assert result.ok is True
        assert "3 spaces" in result.message

    def test_fails_with_bad_credentials(self, connector):
        connector.client.fetch_space_count.side_effect = Exception(
            "401 Unauthorized"
        )

        result = connector.Validate(pb.Empty(), context=MagicMock())

        assert result.ok is False
        assert "Connection failed" in result.message


class TestHealth:
    def test_returns_healthy(self, connector):
        health = connector.Health(pb.Empty(), context=MagicMock())

        assert health.healthy is True
```

### 9.8 Vergleich Java vs. Python

| Aspekt | Java | Python |
|--------|------|--------|
| **Einstiegshürde** | Mittel (Gradle, Protobuf-Plugin) | Niedrig (pip, grpcio-tools) |
| **Typsicherheit** | Streng (Compile-Time) | Dynamisch (Runtime) |
| **HTTP-Client** | OkHttp + Jackson | requests (Batteries-included) |
| **gRPC-Tooling** | protobuf-maven/gradle-plugin | grpcio-tools (pip) |
| **Image-Größe** | ~150–300 MB (JRE) | ~80–150 MB |
| **Startup-Zeit** | 2–5 Sekunden (JVM) | < 1 Sekunde |
| **Performance** | Hoch (JIT-Compiler) | Mittel (für I/O-bound ausreichend) |
| **Ökosystem** | Enterprise-Libraries (Spring, Jackson) | Data-Science, Scripting, Automatisierung |
| **Zielgruppe** | Backend-Devs, Enterprise | Data-Engineers, DevOps, Fullstack |

Der Python-Ansatz zeigt den größten Vorteil der Container-Architektur: **Völlige Sprachfreiheit**. Ein Data-Engineer, der täglich mit Python arbeitet, kann ohne Umlernen einen OPAA-Connector schreiben — mit denselben Libraries, die er bereits kennt.

---

## 10. Bewertung: Pros und Cons des Container-Ansatzes

### 10.1 Vorteile

**Maximale Isolation und Sicherheit**

- Vollständige Prozess-Isolation auf OS-Ebene — Plugin-Abstürze, Memory-Leaks oder Sicherheitslücken betreffen nur den eigenen Container
- Netzwerk-Isolation via Docker Networks oder Kubernetes Network Policies — erzwungen auf OS-Ebene, nicht auf Anwendungsebene
- Ressourcen-Limits (CPU, RAM) pro Container — erzwungen durch cgroups, kein kooperatives Limit wie bei Wasm
- Filesystem-Isolation — Plugin hat keinen Zugriff auf Host-Dateisystem
- Etablierte Container-Sicherheits-Tools (Trivy, Snyk, Falco) sofort nutzbar

**Vollständige Sprachunabhängigkeit**

- Jede Programmiersprache die in einem Container laufen kann: Java, Python, Go, Rust, Node.js, Ruby, C#, etc.
- Breitester potenzieller Entwickler-Pool aller drei Varianten
- Keine Einschränkungen bei Libraries, nativen Bindings, Reflection, Threads, I/O
- Data-Scientists können Python-Plugins mit pandas, numpy, etc. schreiben
- Go/Rust-Entwickler bekommen minimale Container (10–30 MB)

**gRPC-Streaming für Datentransport**

- Server-Streaming ermöglicht effizientes Streamen großer Dokumentenmengen — kein Buffering aller Dokumente im Speicher
- Native `bytes`-Typ in protobuf — kein Base64-Overhead für Binärdaten
- Etabliertes RPC-Framework mit automatischer Code-Generierung für alle Sprachen
- Eingebautes Flow-Control, Deadlines, Error-Handling

**Enterprise-Kompatibilität**

- Container sind der De-facto-Standard in Enterprise-Umgebungen
- Bestehende Infrastruktur (Kubernetes, Container Registry, CI/CD-Pipelines) direkt nutzbar
- Image-Scanning, Network Policies, Pod Security Standards — alles vorhanden
- Compliance-Teams kennen und akzeptieren Container-Isolation
- Multi-Tenancy-fähig: Plugins in separaten Namespaces/Netzwerken

**Betrieb und Observability**

- Container-Logs, Metriken, Health-Checks — alles Standard
- Jedes Plugin hat eigene Ressourcen-Metriken (CPU, RAM, Netzwerk)
- Restart-Policies (always, on-failure) für Selbstheilung
- Horizontale Skalierung möglich (mehrere Instanzen eines Plugins)

### 10.2 Nachteile

**Infrastruktur-Anforderungen**

- **Docker/Podman auf dem Host zwingend erforderlich** — nicht jeder OPAA-Nutzer will oder kann Docker betreiben
- Docker-Socket-Zugriff benötigt oft privilegierte Rechte — in manchen Enterprise-Umgebungen aus Sicherheitsgründen blockiert (ironischerweise das gleiche Problem das gelöst werden soll)
- Kubernetes als Alternative ist für kleine Installationen überdimensioniert
- Erhöhter Betriebsaufwand: Container-Runtime, Netzwerk-Konfiguration, Volume-Management

**Ressourcenverbrauch**

- Jeder Plugin-Container benötigt ein eigenes OS-Environment (Alpine: ~5 MB, Debian: ~50 MB)
- JVM-basierte Plugins: 150–300 MB RAM pro Container (JRE-Overhead)
- Bei 10 Plugins: 1,5–3 GB RAM nur für Plugin-Container — signifikanter Overhead
- Startup-Zeit: 2–10 Sekunden pro Container (vs. Millisekunden bei Wasm)
- Nicht geeignet für On-Demand-Instanziierung — Container müssen dauerhaft laufen

**Netzwerk-Overhead**

- Jeder API-Call zwischen Host und Plugin geht über das Netzwerk (auch wenn lokal)
- gRPC-Serialisierung + Deserialisierung bei jedem Aufruf
- Latenz: ~1–5 ms pro gRPC-Call (vs. ~0,01 ms bei PF4J-Direktaufruf)
- Für Batch-Operationen (Tausende Dokumente) summiert sich der Overhead

**Komplexität**

- gRPC-Tooling muss in jeder Sprache separat eingerichtet werden
- Proto-Definition muss synchron zwischen Host und allen SDKs gehalten werden
- Container-Networking (DNS-Auflösung, Port-Mapping) als zusätzliche Fehlerquelle
- Plugin-Debugging über Container-Grenzen hinweg deutlich schwieriger als In-Process
- Entwickler müssen Docker verstehen und installiert haben

**Docker-Socket-Sicherheitsparadoxon**

- Der Host braucht Zugriff auf den Docker-Socket, um Plugin-Container zu steuern
- Docker-Socket-Zugriff ist de facto Root-Zugriff auf den Host
- Das soll durch Podman (rootless) oder Docker-Socket-Proxy gemildert werden
- Aber: genau dieses Sicherheitsrisiko ist in manchen Unternehmen der Grund, Docker zu blockieren

### 10.3 Sicherheitsvergleich: Container vs. Wasm vs. PF4J

| Sicherheitsaspekt | Container | Wasm | PF4J |
|-------------------|-----------|------|------|
| **Prozess-Isolation** | OS-Ebene (stärkste) | In-Process-Sandbox | Keine |
| **Netzwerk-Isolation** | OS-Level (iptables/NetworkPolicy) | Anwendungs-Level (Allowlist) | Keine |
| **Ressourcen-Limits** | cgroups (erzwungen) | Wasm-Memory-Limit (erzwungen) | Keine |
| **Filesystem-Isolation** | Container-FS (erzwungen) | Kein FS-Zugriff (by design) | Keine |
| **Host-Zugriff nötig** | Docker-Socket (Root-nahe) | Keiner | Keiner |
| **Compliance-Akzeptanz** | Hoch (bekanntes Modell) | Mittel (neues Modell) | Niedrig |

### 10.4 Risikobewertung

| Risiko | Wahrscheinlichkeit | Auswirkung | Mitigation |
|--------|-------------------|------------|------------|
| Docker-Socket-Zugriff wird von Enterprise blockiert | Hoch | **Hoch** | Podman-rootless, Docker-Socket-Proxy, Kubernetes-Alternative |
| Container-Overhead bei vielen Plugins (RAM) | Hoch | Mittel | Alpine-Images, Go/Rust-Plugins, Ressourcen-Budgets |
| Netzwerk-Latenz bei Batch-Operationen | Mittel | Niedrig | gRPC-Streaming, Batch-Optimierung |
| Plugin-Container startet nicht (Image-Pull-Fehler, Port-Konflikte) | Mittel | Mittel | Health-Checks, automatischer Restart, Image-Prefetch |
| Proto-Definition-Drift zwischen Host und Plugin-SDKs | Niedrig | Hoch | Versioniertes proto-Paket, Contract-Tests |

### 10.5 Fazit

Container-basierte Plugins bieten die **stärkste Isolation und die breiteste Sprachunterstützung** aller drei Varianten. Für Organisationen mit bestehender Kubernetes-Infrastruktur ist dies der natürlichste Ansatz.

Der fundamentale Nachteil ist die **Infrastruktur-Anforderung**: Docker oder Kubernetes muss auf dem Host verfügbar sein. Für OPAA als Self-Hosted-Produkt, das auch von kleineren Teams ohne Container-Infrastruktur betrieben werden soll, ist dies eine erhebliche Einstiegshürde.

**Empfehlung:** Container-basierte Plugins als optionale Premium-Variante für Enterprise-Kunden mit bestehender Container-Infrastruktur. Für das Standard-Deployment-Modell (Docker Compose mit 3 Containern) ist der Overhead von zusätzlichen Plugin-Containern nicht ideal — hier eignet sich Wasm (Variante C) oder PF4J (Variante B) besser als primäre Plugin-Runtime.

---

## 11. Direktvergleich: Alle drei Varianten

| Aspekt | Container (A) | PF4J (B) | Wasm (C) |
|--------|--------------|----------|----------|
| **Isolation** | OS-Ebene (stärkste) | Keine (schwächste) | In-Process-Sandbox (mittel) |
| **Sprachen** | Alle | Nur JVM | AS, Rust, Go, Java (eingeschränkt) |
| **Libraries** | Alle | Alle JVM-Libraries | Eingeschränkt (kein Reflection) |
| **Performance** | Netzwerk-Overhead | Direkte Aufrufe (schnellste) | 2–5x langsamer als nativ |
| **Datentransport** | gRPC-Streaming (effizient) | InputStream direkt (effizienteste) | Reference-based für große Dateien |
| **Ressourcen** | ~150–300 MB pro Plugin | Geteilt mit Host-JVM | ~wenige MB pro Plugin |
| **Startup** | 2–10 Sekunden | Sofort (Hot-Reload) | Millisekunden |
| **Infrastruktur** | Docker/K8s nötig | Keine | Keine |
| **Enterprise** | Bekanntes Modell, Tools existieren | Schwer zu argumentieren | Neues Modell, keine Root-Rechte |
| **Debugging** | Schwierig (Container-Grenzen) | Einfachste (IDE-Breakpoints) | Schwierig (Wasm-Tracing) |
| **Host-Komplexität** | Hoch (Container-Management) | Niedrig (PF4J-API) | Mittel (Wasm-Runtime, Proxy) |
| **Plugin-Komplexität** | Mittel (gRPC + Docker) | Niedrig (Standard-Java) | Mittel (SDK + Wasm-Constraints) |
| **Reife** | Hoch (Docker seit 2013) | Hoch (PF4J seit 2012) | Mittel (Extism/Chicory jung) |
