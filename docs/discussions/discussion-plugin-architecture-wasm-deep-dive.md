# Deep Dive: WebAssembly Plugin-Architektur für OPAA Connectors

## 1. Einleitung

Dieses Dokument vertieft **Variante C (WebAssembly)** aus dem übergeordneten [Plugin-Architektur-Konzept](discussion-plugin-architecture.md) und beschreibt die konkrete Umsetzung als bevorzugte Plugin-Strategie für OPAA.

Behandelt werden:

- API-Contract zwischen Plugin und Host
- Datentransport inkl. großer Binärdateien
- Plugin-Paketierung und Distribution
- Marketplace-Konzept
- Plugin-SDK für Entwickler (AssemblyScript + Java)
- Bewertung mit Pros und Cons

**Referenz:** GitHub Issue [#106](https://github.com/criew/opaa/issues/106) (Epic) und Sub-Issues #126–#130.

---

## 2. Warum WebAssembly für OPAA

OPAA ist ein Self-Hosted-Produkt mit Fokus auf digitale Souveränität und Enterprise-Tauglichkeit. Wasm bietet hier entscheidende Vorteile gegenüber Container- und In-JVM-Ansätzen:

- **Kein Docker-Socket nötig** — oft in Enterprise-Umgebungen aus Sicherheitsgründen blockiert
- **In-Process-Sandboxing** — ein fehlerhaftes Plugin kann das Backend nicht crashen
- **Sprachunabhängig** — Plugin-Entwickler nutzen AssemblyScript, Rust, Go oder Java (via TeaVM)
- **Millisekunden-Startup** — kein Container-Boot, sofortige Instanziierung
- **Keine Root-Rechte** — läuft vollständig im User-Space der JVM

**Runtime-Empfehlung:** Extism (Plugin-Framework) + Chicory (pure Java Wasm Runtime). Kein nativer Code, keine JNI-Abhängigkeiten.

---

## 3. API-Contract: Host ↔ Plugin

### 3.1 Architekturüberblick

```
┌─────────────────────────────────────────────┐
│              Plugin (.wasm)                  │
│                                             │
│  EXPORTS (Plugin implementiert):            │
│  ├─ connector_metadata() → JSON             │
│  ├─ connector_configure(config) → Result    │
│  ├─ connector_validate() → Result           │
│  ├─ connector_fetch(cursor) → FetchResult   │  ← PULL
│  └─ connector_on_event(event) → FetchResult │  ← PUSH
│                                             │
│  IMPORTS (Host stellt bereit):              │
│  ├─ host_http_request(req) → Response       │
│  ├─ host_log(level, msg)                    │
│  ├─ host_kv_get(key) → bytes                │
│  └─ host_kv_set(key, value)                 │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│              Host (Spring Boot)              │
│                                             │
│  ├─ Wasm Runtime (Extism/Chicory)           │
│  ├─ Plugin Manager (Load/Unload/Update)     │
│  ├─ Scheduler (Pull-Polling)                │
│  ├─ Webhook Router (Push-Events)            │
│  ├─ Document Processor (Tika + Embeddings)  │
│  ├─ Registry Client (Marketplace-Index)     │
│  └─ Security (Allowlist, Signatur, Limits)  │
└─────────────────────────────────────────────┘
```

### 3.2 Plugin-Exports (was das Plugin implementiert)

#### `connector_metadata() → JSON`

Liefert statische Informationen über das Plugin. Wird beim Laden einmalig aufgerufen.

#### `connector_configure(config: JSON) → Result`

Empfängt die Konfiguration (API-Keys, URLs etc.) vom Host. Die Konfigurationswerte werden vom Admin über die UI eingegeben — das Schema dafür ist im Plugin-Manifest definiert.

#### `connector_validate() → Result`

Prüft, ob die aktuelle Konfiguration gültig ist und eine Verbindung zur Datenquelle hergestellt werden kann. Gibt `{ ok: true/false, message: "..." }` zurück.

#### `connector_fetch(cursor: JSON) → FetchResult` (PULL)

Holt Dokumente von der Datenquelle. Der `cursor` ist ein opakes Paginierungs-Token — `null` beim ersten Aufruf, danach der Wert aus dem vorherigen `FetchResult`. Der Host steuert das Polling via `@Scheduled`.

#### `connector_on_event(event: JSON) → FetchResult` (PUSH)

Verarbeitet eingehende Webhook-Events von externen Diensten. Der Host registriert einen Webhook-Endpoint pro Plugin und routet Events hierher. Nur für Plugins mit `mode: "push"` oder `mode: "hybrid"`.

### 3.3 Host-Functions (was der Host dem Plugin bereitstellt)

#### `host_http_request(request: JSON) → Response`

Alle Netzwerkzugriffe des Plugins laufen über diese Funktion. Das Plugin macht **niemals direkte HTTP-Calls**. Der Host erzwingt eine Domain-Allowlist pro Plugin (definiert im Manifest unter `permissions.network`).

#### `host_log(level: string, message: string)`

Logging-Kanal vom Plugin zum Host. Level: `debug`, `info`, `warn`, `error`. Logs erscheinen im Host-Log mit Plugin-Kontext.

#### `host_kv_get(key: string) → bytes` / `host_kv_set(key: string, value: bytes)`

Persistenter Key-Value-Store pro Plugin. Nutzung: Cursor-Speicherung, OAuth-Token-Caching, Zustandsverwaltung zwischen Aufrufen.

### 3.4 Connector-Modi

Plugins deklarieren ihren Betriebsmodus im Manifest:

| Modus | Beschreibung | Pflicht-Exports |
|-------|-------------|-----------------|
| `pull` | Host pollt periodisch | `metadata`, `configure`, `validate`, `fetch` |
| `push` | Externe Events via Webhook | `metadata`, `configure`, `validate`, `on_event` |
| `hybrid` | Beides | Alle fünf Exports |

### 3.5 FetchResult-Datenmodell

```json
{
  "documents": [
    {
      "id": "unique-source-id",
      "title": "Dokumenttitel",
      "content": "Klartext, Markdown oder Base64",
      "contentType": "text/html",
      "encoding": null,
      "fetchInstruction": null,
      "metadata": {
        "author": "Max Mustermann",
        "lastModified": "2026-03-01T10:00:00Z",
        "sourceUrl": "https://wiki.example.com/page/42",
        "sourceSystem": "confluence",
        "labels": ["projekt", "planung"]
      },
      "hash": "sha256:abc123..."
    }
  ],
  "deletions": ["id-of-deleted-document"],
  "cursor": "opaque-pagination-token-or-null",
  "hasMore": true
}
```

#### Verantwortungsteilung: Plugin liefert, Host verarbeitet

```
Plugin                              Host
──────                              ────
Datenquelle abfragen           →    Rohdaten empfangen
Metadata extrahieren           →    Content-Type auswerten
Rohdaten + Content-Type        →    Tika-Parsing (PDF, DOCX, etc.)
Source-URL, Hash liefern       →    Embedding generieren
                                    In pgvector speichern
```

Das Plugin ist ein **Daten-Lieferant**. Es muss keine Dokumente parsen oder transformieren — das übernimmt der Host mit Apache Tika und der Embedding-Pipeline.

---

## 4. Transport großer Binärdaten

### 4.1 Das Problem

Base64 in JSON hat zwei fundamentale Probleme:

- **+33% Overhead** — aus 100 MB werden ~133 MB im JSON
- **Speicherverbrauch** — das gesamte JSON muss in den linearen Wasm-Speicher passen UND im Host vollständig geparst werden

Bei Dokumenten mit Hunderten MB (große PDFs, Präsentationen, Videos) ist Inline-Transport nicht tragbar.

### 4.2 Lösung: Reference-based Fetching

Das Plugin transportiert **große Binärdaten niemals durch seinen eigenen Speicher**. Stattdessen liefert es eine Fetch-Anweisung, und der Host lädt die Datei direkt herunter — am Wasm-Modul vorbei.

```json
{
  "id": "confluence-att-99",
  "title": "Architektur-Diagramm.pdf",
  "contentType": "application/pdf",
  "content": null,
  "fetchInstruction": {
    "url": "https://wiki.example.com/download/attachments/99/diagram.pdf",
    "method": "GET",
    "headers": {
      "Authorization": "Bearer eyJ..."
    },
    "expectedSizeBytes": 52428800
  },
  "metadata": { "..." : "..." },
  "hash": "sha256:abc123..."
}
```

Der Host erkennt `content: null` + vorhandene `fetchInstruction` und führt den Download selbst durch — per Java `HttpClient` mit Streaming, ohne die gesamte Datei in den RAM zu laden:

```java
// Pseudocode — Host-seitige Verarbeitung
try (var response = httpClient.send(request, BodyHandlers.ofInputStream())) {
    documentProcessor.process(
        response.body(),          // InputStream — kein RAM-Buffering
        document.contentType(),
        document.metadata()
    );
}
```

### 4.3 Drei Transportmodi

| Modus | Wann | Wie |
|-------|------|-----|
| **Inline Text** | Text, Markdown, kleine Dateien (< 1 MB) | `content` enthält den Text direkt, `encoding: null` |
| **Inline Base64** | Kleine Binärdaten (1–5 MB) | `content` enthält Base64-Daten, `encoding: "base64"` |
| **Reference** | Alles > 5 MB | `content: null`, `fetchInstruction` mit URL + Auth-Headers |

Der Schwellenwert (5 MB) ist konfigurierbar pro Plugin.

### 4.4 Sicherheit bei Reference-based Fetching

Die URL in `fetchInstruction` muss zur **Network-Allowlist** im Plugin-Manifest passen. Der Host prüft dies vor jedem Download:

```
Manifest: "permissions.network": ["*.atlassian.net"]

fetchInstruction.url: https://wiki.atlassian.net/download/...   → ✅ erlaubt
fetchInstruction.url: https://evil.com/exfiltrate               → ❌ blockiert
```

---

## 5. Plugin-Paketierung

### 5.1 Bundle-Struktur

```
my-confluence-connector/
├── plugin.json          # Manifest (Metadata, Permissions, Config-Schema)
├── connector.wasm       # Kompiliertes Wasm-Modul
├── icon.svg             # Icon für Marketplace/UI
└── README.md            # Dokumentation
```

### 5.2 Plugin-Manifest (`plugin.json`)

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
    "engine": "wasm",
    "minHostVersion": "0.5.0"
  },
  "connector": {
    "mode": "hybrid",
    "webhook": {
      "path": "/webhooks/confluence",
      "events": ["page:created", "page:updated", "page:removed"]
    }
  },
  "permissions": {
    "network": ["*.atlassian.net", "*.confluence.com"],
    "kvStorage": true,
    "maxMemoryMB": 64
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

### 5.3 Distribution: OCI Artifacts

Statt ein eigenes Paketformat zu erfinden, nutzen wir **OCI Artifacts** — den Standard hinter Docker-Images:

- Plugins werden in jeder **OCI-kompatiblen Registry** gespeichert (GitHub Container Registry, Docker Hub, private Registry)
- Push/Pull via `oras` (OCI Registry As Storage)
- Enterprise-Kunden können eine **private Registry** betreiben (Air-Gapped)
- Kryptographische Signierung via **Cosign/Sigstore**

```bash
# Plugin veröffentlichen
oras push ghcr.io/opaa-plugins/confluence-connector:1.2.0 \
  plugin.json:application/vnd.opaa.plugin.manifest.v1+json \
  connector.wasm:application/vnd.opaa.plugin.wasm.v1 \
  icon.svg:image/svg+xml

# Plugin installieren (vom Backend automatisiert)
oras pull ghcr.io/opaa-plugins/confluence-connector:1.2.0
```

---

## 6. Marketplace-Konzept

### 6.1 Stufe 1: Plugin Registry (Index)

Ein **Git-Repository** als zentraler Index (vergleichbar mit Homebrew Taps oder Helm Chart Repositories):

```
opaa-plugin-registry/
├── plugins/
│   ├── confluence-connector.json
│   ├── dropbox-connector.json
│   └── github-connector.json
└── index.json   # Aggregierter Index aller Plugins
```

Jeder Eintrag verweist auf das OCI-Artifact:

```json
{
  "name": "confluence-connector",
  "description": "Import pages from Confluence",
  "author": "OPAA Community",
  "verified": true,
  "registry": "ghcr.io/opaa-plugins/confluence-connector",
  "latestVersion": "1.2.0",
  "categories": ["documentation", "wiki"],
  "downloads": 1523
}
```

### 6.2 Stufe 2: Discovery API im Backend

Das OPAA-Backend synchronisiert den Registry-Index periodisch und bietet eine REST-API:

```
GET    /api/v1/plugins/available            # Marketplace durchsuchen
GET    /api/v1/plugins/available/{name}     # Plugin-Details
POST   /api/v1/plugins/install              # Plugin installieren
GET    /api/v1/plugins/installed            # Installierte Plugins
POST   /api/v1/plugins/{id}/configure       # Plugin konfigurieren
DELETE /api/v1/plugins/{id}                 # Plugin deinstallieren
```

### 6.3 Stufe 3: Marketplace-UI im Frontend

Eine Marketplace-Seite in der OPAA-UI mit:

- Suche und Filterung nach Kategorie
- Install/Uninstall mit einem Klick
- Konfigurationsformular (automatisch generiert aus `configSchema`)
- Statusanzeige (aktiv, fehlerhaft, wird aktualisiert)

### 6.4 Enterprise-Features

- **Private Registry:** Firmen konfigurieren ihre eigene OCI-Registry (Air-Gapped)
- **Plugin-Allowlist:** Admin legt fest, welche Plugins installierbar sind
- **Signaturprüfung:** Nur signierte Plugins von vertrauenswürdigen Publishern

### 6.5 Plugin-Updates

Das Update-Verhalten ist pro Plugin konfigurierbar:

| Policy | Verhalten |
|--------|-----------|
| `manual` | Admin wird benachrichtigt, installiert selbst |
| `auto` | Jede neue Version wird automatisch installiert |
| `auto-patch` | Nur Patch-Versionen (1.2.x) automatisch, Minor/Major manuell |

Update-Ablauf bei `auto` / `auto-patch`:

1. Neue `.wasm`-Datei aus der Registry herunterladen
2. Cosign-Signatur prüfen
3. Laufende Fetch-Operationen abschließen lassen, alte Instanz stoppen
4. Neue Instanz laden
5. `connector_validate()` aufrufen — bei Fehler automatischer Rollback auf die vorherige Version

---

## 7. Plugin-SDK für Entwickler

### 7.1 SDK-Schichten

```
┌──────────────────────────────────────────┐
│  Plugin-Code (Geschäftslogik)            │  ← Entwickler schreibt nur das
├──────────────────────────────────────────┤
│  OPAA Plugin SDK                         │  ← Abstrahiert Wasm-Details
│  ├─ @connector() Decorator/Annotation    │
│  ├─ HttpClient (→ host_http_request)     │
│  ├─ KeyValueStore (→ host_kv_*)          │
│  ├─ Logger (→ host_log)                  │
│  └─ Types (Document, FetchResult, etc.)  │
├──────────────────────────────────────────┤
│  Extism PDK (Sprach-spezifisch)          │  ← Wasm-Kommunikationsschicht
├──────────────────────────────────────────┤
│  WebAssembly                             │
└──────────────────────────────────────────┘
```

### 7.2 SDK-Varianten

| Sprache | SDK-Paket | Build-Tool | Wasm-Größe |
|---------|-----------|-----------|------------|
| AssemblyScript | `@opaa/plugin-sdk` (npm) | `asc` (AssemblyScript Compiler) | ~100–500 KB |
| Rust | `opaa-plugin-sdk` (crates.io) | `cargo build --target wasm32-wasi` | ~200 KB–1 MB |
| Go | `github.com/opaa/plugin-sdk-go` | `tinygo build -target wasi` | ~500 KB–2 MB |
| Java | `io.opaa:plugin-sdk` (Maven) | TeaVM Maven Plugin | ~1–5 MB |

Jede Variante bietet dieselben Abstraktionen in idiomatischer Syntax.

### 7.3 CLI-Tooling: `opaa-plugin`

```bash
# Neues Plugin-Projekt scaffolden
opaa-plugin init my-connector --lang assemblyscript
# → Generiert Projektstruktur, plugin.json, Build-Config

# Lokaler Entwicklungsserver mit Mock-Host
opaa-plugin dev
# → Startet Mock-Host, ruft fetch() auf, zeigt FetchResults an
# → Hot-Reload bei Code-Änderungen

# Plugin bauen
opaa-plugin build
# → Kompiliert zu .wasm, validiert Manifest, prüft Exports

# Plugin testen
opaa-plugin test
# → Führt Tests aus, prüft Contract-Compliance

# Plugin paketieren
opaa-plugin pack
# → Erzeugt OCI-Bundle (plugin.json + connector.wasm + icon.svg)

# Plugin veröffentlichen
opaa-plugin publish --registry ghcr.io/opaa-plugins
# → Pusht OCI-Artifact, optional mit Cosign-Signatur
```

---

## 8. Konkretes Beispiel: AssemblyScript Confluence Connector

### 8.1 Projektstruktur

```
confluence-connector/
├── src/
│   ├── index.ts              # Plugin-Entry (fetch, onEvent, validate)
│   └── types.ts              # Eigene Typen
├── test/
│   ├── fetch.test.ts         # Unit-Tests
│   └── fixtures/             # Mock-API-Responses
├── plugin.json               # Manifest
├── icon.svg
├── package.json              # Dependencies: @opaa/plugin-sdk + @extism/as-pdk
├── asconfig.json             # AssemblyScript-Compiler-Config
└── README.md
```

### 8.2 Plugin-Code

```typescript
import {
  ConnectorPlugin,
  FetchResult,
  Document,
  HttpClient,
  KeyValueStore,
  PluginConfig,
  WebhookEvent,
  Result
} from "@opaa/plugin-sdk"

// Metadata — wird automatisch als connector_metadata() exportiert
export const metadata = ConnectorPlugin.define({
  name: "confluence-connector",
  version: "1.0.0",
  mode: "hybrid",
  description: "Imports Confluence pages and attachments",
})

interface ConfluenceConfig {
  baseUrl: string
  apiToken: string
  spaceKeys: string[]
}

const http = new HttpClient()
const kv = new KeyValueStore()

// ──────────────────────────────────────────────
// PULL: Host ruft das periodisch auf
// ──────────────────────────────────────────────

export function fetch(cursor: string | null): FetchResult {
  const config = PluginConfig.get<ConfluenceConfig>()
  const pageSize = 25
  const start = cursor ? parseInt(cursor) : 0

  const response = http.get(`${config.baseUrl}/rest/api/content`, {
    params: {
      spaceKey: config.spaceKeys.join(","),
      start: start.toString(),
      limit: pageSize.toString(),
      expand: "body.storage,version,metadata.labels,children.attachment"
    },
    headers: {
      "Authorization": `Bearer ${config.apiToken}`
    }
  })

  const data = response.json()
  const documents: Document[] = []

  for (const page of data.results) {
    // Textinhalt → inline
    documents.push({
      id: `confluence-page-${page.id}`,
      title: page.title,
      content: page.body.storage.value,
      contentType: "text/html",
      metadata: {
        author: page.version.by.displayName,
        lastModified: page.version.when,
        sourceUrl: `${config.baseUrl}${page._links.webui}`,
        labels: page.metadata.labels.results.map(l => l.name)
      },
      hash: sha256(page.body.storage.value)
    })

    // Attachments → Reference (große Dateien)
    for (const att of page.children?.attachment?.results ?? []) {
      documents.push({
        id: `confluence-att-${att.id}`,
        title: att.title,
        content: null,
        contentType: att.mediaType,
        fetchInstruction: {
          url: `${config.baseUrl}/download${att._links.download}`,
          method: "GET",
          headers: { "Authorization": `Bearer ${config.apiToken}` },
          expectedSizeBytes: att.extensions.fileSize
        },
        metadata: {
          parentDocument: `confluence-page-${page.id}`,
          sourceUrl: `${config.baseUrl}${att._links.webui}`
        },
        hash: `sha256:${att.extensions.hash}`
      })
    }
  }

  return {
    documents,
    cursor: data.size === pageSize ? (start + pageSize).toString() : null,
    hasMore: data.size === pageSize
  }
}

// ──────────────────────────────────────────────
// PUSH: Webhook-Event von Confluence
// ──────────────────────────────────────────────

export function onEvent(event: WebhookEvent): FetchResult {
  const config = PluginConfig.get<ConfluenceConfig>()

  if (event.type === "page:removed") {
    return {
      documents: [],
      deletions: [`confluence-page-${event.page.id}`],
      cursor: null,
      hasMore: false
    }
  }

  // page:created oder page:updated → Seite neu laden
  const page = http.get(
    `${config.baseUrl}/rest/api/content/${event.page.id}`,
    {
      params: { expand: "body.storage,version,metadata.labels" },
      headers: { "Authorization": `Bearer ${config.apiToken}` }
    }
  ).json()

  return {
    documents: [{
      id: `confluence-page-${page.id}`,
      title: page.title,
      content: page.body.storage.value,
      contentType: "text/html",
      metadata: {
        author: page.version.by.displayName,
        lastModified: page.version.when,
        sourceUrl: `${config.baseUrl}${page._links.webui}`
      },
      hash: sha256(page.body.storage.value)
    }],
    cursor: null,
    hasMore: false
  }
}

// ──────────────────────────────────────────────
// VALIDATE
// ──────────────────────────────────────────────

export function validate(): Result {
  const config = PluginConfig.get<ConfluenceConfig>()
  try {
    const resp = http.get(`${config.baseUrl}/rest/api/space`, {
      headers: { "Authorization": `Bearer ${config.apiToken}` }
    })
    return { ok: true, message: `Connected. ${resp.json().size} spaces found.` }
  } catch (e) {
    return { ok: false, message: `Connection failed: ${e.message}` }
  }
}
```

### 8.3 Tests

```typescript
import { MockHost, createTestPlugin } from "@opaa/plugin-sdk/testing"

describe("confluence-connector", () => {
  const host = new MockHost()

  host.mockHttp("GET", "https://wiki.example.com/rest/api/content", {
    status: 200,
    body: { results: [{ id: "1", title: "Test Page", body: { storage: { value: "<p>Hello</p>" } }, version: { by: { displayName: "Admin" }, when: "2026-01-01T00:00:00Z" }, _links: { webui: "/pages/1" }, metadata: { labels: { results: [] } } }], size: 1 }
  })

  host.mockHttp("GET", "https://wiki.example.com/rest/api/space", {
    status: 200,
    body: { size: 3, results: [] }
  })

  it("fetches pages from Confluence", () => {
    const plugin = createTestPlugin("./build/connector.wasm", {
      host,
      config: { baseUrl: "https://wiki.example.com", apiToken: "test", spaceKeys: ["DEV"] }
    })

    const result = plugin.call("fetch", null)

    expect(result.documents).toHaveLength(1)
    expect(result.documents[0].title).toBe("Test Page")
    expect(result.documents[0].contentType).toBe("text/html")
    expect(result.hasMore).toBe(false)
  })

  it("handles page deletion events", () => {
    const plugin = createTestPlugin("./build/connector.wasm", {
      host,
      config: { baseUrl: "https://wiki.example.com", apiToken: "test", spaceKeys: ["DEV"] }
    })

    const result = plugin.call("onEvent", { type: "page:removed", page: { id: "42" } })

    expect(result.documents).toHaveLength(0)
    expect(result.deletions).toContain("confluence-page-42")
  })

  it("validates connection successfully", () => {
    const plugin = createTestPlugin("./build/connector.wasm", {
      host,
      config: { baseUrl: "https://wiki.example.com", apiToken: "test", spaceKeys: ["DEV"] }
    })

    const result = plugin.call("validate")

    expect(result.ok).toBe(true)
    expect(result.message).toContain("3 spaces")
  })
})
```

---

## 9. Konkretes Beispiel: Java Confluence Connector

### 9.1 Projektstruktur

```
confluence-connector-java/
├── src/main/java/
│   └── com/example/confluence/
│       ├── ConfluenceConnector.java     # Plugin-Entry
│       ├── ConfluenceConfig.java        # Config-POJO
│       └── ConfluencePageParser.java    # Hilfsklasse
├── src/test/java/
│   └── com/example/confluence/
│       ├── ConfluenceConnectorTest.java
│       └── fixtures/
│           └── api-response.json
├── plugin.json
├── icon.svg
├── pom.xml
└── README.md
```

### 9.2 Build-Konfiguration (`pom.xml`, Auszug)

```xml
<dependencies>
    <dependency>
        <groupId>io.opaa</groupId>
        <artifactId>plugin-sdk</artifactId>
        <version>1.0.0</version>
    </dependency>
    <dependency>
        <groupId>io.opaa</groupId>
        <artifactId>plugin-sdk-testing</artifactId>
        <version>1.0.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.teavm</groupId>
            <artifactId>teavm-maven-plugin</artifactId>
            <version>0.10.0</version>
            <configuration>
                <targetType>WEBASSEMBLY</targetType>
                <mainClass>com.example.confluence.ConfluenceConnector</mainClass>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### 9.3 Config-POJO

```java
package com.example.confluence;

import io.opaa.plugin.sdk.ConfigProperty;
import io.opaa.plugin.sdk.ConfigSchema;

import java.util.List;

@ConfigSchema
public class ConfluenceConfig {

    @ConfigProperty(title = "Confluence URL", required = true)
    private String baseUrl;

    @ConfigProperty(title = "API Token", required = true, secret = true)
    private String apiToken;

    @ConfigProperty(title = "Space Keys", required = true)
    private List<String> spaceKeys;

    public String getBaseUrl() { return baseUrl; }
    public String getApiToken() { return apiToken; }
    public List<String> getSpaceKeys() { return spaceKeys; }
}
```

### 9.4 Plugin-Code

```java
package com.example.confluence;

import io.opaa.plugin.sdk.*;
import io.opaa.plugin.sdk.http.HttpClient;
import io.opaa.plugin.sdk.http.HttpResponse;
import io.opaa.plugin.sdk.json.Json;
import io.opaa.plugin.sdk.store.KeyValueStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ConnectorPlugin(
    name = "confluence-connector",
    version = "1.0.0",
    mode = ConnectorMode.HYBRID,
    description = "Imports Confluence pages and attachments",
    webhook = @Webhook(
        path = "/webhooks/confluence",
        events = {"page:created", "page:updated", "page:removed"}
    )
)
public class ConfluenceConnector implements Connector<ConfluenceConfig> {

    private final HttpClient http = HttpClient.create();
    private final KeyValueStore kv = KeyValueStore.create();
    private final Logger log = Logger.create(ConfluenceConnector.class);

    // ──────────────────────────────────────────────
    // PULL
    // ──────────────────────────────────────────────

    @Override
    public FetchResult fetch(ConfluenceConfig config, String cursor) {
        int pageSize = 25;
        int start = cursor != null ? Integer.parseInt(cursor) : 0;

        HttpResponse response = http.get(config.getBaseUrl() + "/rest/api/content")
            .param("spaceKey", String.join(",", config.getSpaceKeys()))
            .param("start", String.valueOf(start))
            .param("limit", String.valueOf(pageSize))
            .param("expand", "body.storage,version,metadata.labels,children.attachment")
            .header("Authorization", "Bearer " + config.getApiToken())
            .execute();

        Map<String, Object> data = response.json();
        List<Map<String, Object>> results = Json.getList(data, "results");
        List<Document> documents = new ArrayList<>();

        for (Map<String, Object> page : results) {
            documents.add(pageToDocument(config, page));
            documents.addAll(attachmentsToDocuments(config, page));
        }

        int size = Json.getInt(data, "size");
        String nextCursor = size == pageSize
            ? String.valueOf(start + pageSize)
            : null;

        return FetchResult.builder()
            .documents(documents)
            .cursor(nextCursor)
            .hasMore(size == pageSize)
            .build();
    }

    // ──────────────────────────────────────────────
    // PUSH
    // ──────────────────────────────────────────────

    @Override
    public FetchResult onEvent(ConfluenceConfig config, WebhookEvent event) {
        String pageId = Json.getString(event.payload(), "page.id");

        if ("page:removed".equals(event.type())) {
            return FetchResult.builder()
                .deletions(List.of("confluence-page-" + pageId))
                .build();
        }

        HttpResponse response = http.get(
                config.getBaseUrl() + "/rest/api/content/" + pageId)
            .param("expand", "body.storage,version,metadata.labels")
            .header("Authorization", "Bearer " + config.getApiToken())
            .execute();

        Map<String, Object> page = response.json();

        return FetchResult.builder()
            .documents(List.of(pageToDocument(config, page)))
            .build();
    }

    // ──────────────────────────────────────────────
    // VALIDATE
    // ──────────────────────────────────────────────

    @Override
    public Result validate(ConfluenceConfig config) {
        try {
            HttpResponse response = http.get(config.getBaseUrl() + "/rest/api/space")
                .header("Authorization", "Bearer " + config.getApiToken())
                .execute();

            int spaceCount = Json.getInt(response.json(), "size");
            return Result.ok("Connected. " + spaceCount + " spaces found.");
        } catch (HttpException e) {
            return Result.error("Connection failed: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // Hilfsmethoden
    // ──────────────────────────────────────────────

    private Document pageToDocument(ConfluenceConfig config, Map<String, Object> page) {
        String pageId = Json.getString(page, "id");
        String body = Json.getString(page, "body.storage.value");

        return Document.builder()
            .id("confluence-page-" + pageId)
            .title(Json.getString(page, "title"))
            .content(body)
            .contentType("text/html")
            .hash(Hash.sha256(body))
            .metadata(Metadata.builder()
                .author(Json.getString(page, "version.by.displayName"))
                .lastModified(Json.getString(page, "version.when"))
                .sourceUrl(config.getBaseUrl() + Json.getString(page, "_links.webui"))
                .labels(Json.getStringList(page, "metadata.labels.results[*].name"))
                .build())
            .build();
    }

    private List<Document> attachmentsToDocuments(
            ConfluenceConfig config, Map<String, Object> page) {

        List<Document> docs = new ArrayList<>();
        String pageId = Json.getString(page, "id");
        List<Map<String, Object>> attachments =
            Json.getList(page, "children.attachment.results");

        for (Map<String, Object> att : attachments) {
            String attId = Json.getString(att, "id");
            long fileSize = Json.getLong(att, "extensions.fileSize");

            docs.add(Document.builder()
                .id("confluence-att-" + attId)
                .title(Json.getString(att, "title"))
                .contentType(Json.getString(att, "mediaType"))
                .fetchInstruction(FetchInstruction.builder()
                    .url(config.getBaseUrl() + "/download"
                        + Json.getString(att, "_links.download"))
                    .method("GET")
                    .header("Authorization", "Bearer " + config.getApiToken())
                    .expectedSizeBytes(fileSize)
                    .build())
                .hash("sha256:" + Json.getString(att, "extensions.hash"))
                .metadata(Metadata.builder()
                    .put("parentDocument", "confluence-page-" + pageId)
                    .sourceUrl(config.getBaseUrl()
                        + Json.getString(att, "_links.webui"))
                    .build())
                .build());
        }

        return docs;
    }
}
```

### 9.5 Tests

```java
package com.example.confluence;

import io.opaa.plugin.sdk.*;
import io.opaa.plugin.sdk.testing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ConfluenceConnectorTest {

    private MockHost host;
    private PluginTestHarness<ConfluenceConfig> harness;

    @BeforeEach
    void setUp() {
        host = new MockHost();

        host.mockHttp("GET", "https://wiki.example.com/rest/api/content")
            .respondWith(200, Fixtures.load("api-response.json"));

        host.mockHttp("GET", "https://wiki.example.com/rest/api/space")
            .respondWith(200, """
                {"size": 3, "results": []}
            """);

        ConfluenceConfig config = new ConfluenceConfig();
        config.baseUrl = "https://wiki.example.com";
        config.apiToken = "test-token";
        config.spaceKeys = List.of("DEV", "OPS");

        harness = PluginTestHarness.create(ConfluenceConnector.class, host)
            .config(config);
    }

    @Test
    void fetchReturnsPages() {
        FetchResult result = harness.fetch(null);

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).title()).isEqualTo("Test Page");
        assertThat(result.documents().get(0).contentType()).isEqualTo("text/html");
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void fetchWithPagination() {
        host.mockHttp("GET", "https://wiki.example.com/rest/api/content")
            .withParam("start", "0")
            .respondWith(200, Fixtures.load("page-1.json"));
        host.mockHttp("GET", "https://wiki.example.com/rest/api/content")
            .withParam("start", "25")
            .respondWith(200, Fixtures.load("page-2.json"));

        FetchResult page1 = harness.fetch(null);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.cursor()).isEqualTo("25");

        FetchResult page2 = harness.fetch(page1.cursor());
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.cursor()).isNull();
    }

    @Test
    void largeAttachmentsUseFetchInstruction() {
        FetchResult result = harness.fetch(null);

        Document attachment = result.documents().stream()
            .filter(d -> d.id().startsWith("confluence-att-"))
            .findFirst().orElseThrow();

        assertThat(attachment.content()).isNull();
        assertThat(attachment.fetchInstruction()).isNotNull();
        assertThat(attachment.fetchInstruction().url())
            .startsWith("https://wiki.example.com/download");
    }

    @Test
    void onEventDeleteRemovesDocument() {
        WebhookEvent event = WebhookEvent.of("page:removed",
            Map.of("page", Map.of("id", "42")));

        FetchResult result = harness.onEvent(event);

        assertThat(result.documents()).isEmpty();
        assertThat(result.deletions()).containsExactly("confluence-page-42");
    }

    @Test
    void validateSucceeds() {
        Result result = harness.validate();

        assertThat(result.isOk()).isTrue();
        assertThat(result.message()).contains("3 spaces");
    }

    @Test
    void validateFailsWithBadCredentials() {
        host.mockHttp("GET", "https://wiki.example.com/rest/api/space")
            .respondWith(401, "Unauthorized");

        Result result = harness.validate();

        assertThat(result.isOk()).isFalse();
        assertThat(result.message()).contains("Connection failed");
    }
}
```

### 9.6 Einschränkungen bei Java-Plugins (TeaVM)

TeaVM kompiliert Java-Bytecode nach Wasm, reimplementiert aber die Java-Standardbibliothek nur teilweise. Folgende Einschränkungen gelten:

| Funktioniert | Funktioniert nicht |
|---|---|
| Pure Logik, Datenstrukturen | Reflection (`Class.forName`, `Field.get`) |
| String-Manipulation | Threads, Concurrency |
| Collections (`java.util.*`) | File I/O, Networking |
| Eigene POJOs, Enums | Annotation Processing zur Laufzeit |
| Einfache Mathematik | `java.nio`, `java.net` |

**Konsequenzen für Bibliotheken:**

- **Jackson, Gson** → nicht nutzbar (Reflection-basiert)
- **Guava** → nur pure Teile (`Strings`, `ImmutableList`, `Splitter`), nicht `Cache`, `Files`
- **Lombok** → nicht nutzbar (Annotation Processing)

**Empfehlung:** SDK-eigene Utilities nutzen (`Json`, `HttpClient`, `Hash`). Für komplexere JSON-Mappings bietet das SDK einen Compile-Time-Code-Generator:

```java
@JsonMapping
public interface ConfluencePage {
    String id();
    String title();
    @JsonPath("body.storage.value") String bodyHtml();
    @JsonPath("version.by.displayName") String author();
}

// Generiert zur Compile-Time einen Parser (kein Reflection):
ConfluencePage page = ConfluencePageParser.fromJson(response.json());
```

### 9.7 Vergleich AssemblyScript vs. Java

| Aspekt | AssemblyScript | Java |
|--------|---------------|------|
| **Einstiegshürde** | Niedrig (TypeScript-Syntax) | Mittel (kein Spring, kein Lombok) |
| **Typsicherheit** | Gut (TypeScript-Typen) | Sehr gut (Compiler + Annotationen) |
| **Config** | Plain Objects | `@ConfigSchema` POJOs |
| **Plugin-Deklaration** | `export const metadata = ...` | `@ConnectorPlugin` Annotation |
| **Contract-Bindung** | Named Exports | `implements Connector<T>` Interface |
| **Einschränkungen** | Kein voller JS-Support | Kein Reflection, kein Lombok, kein Spring |
| **Wasm-Größe** | ~100–500 KB | ~1–5 MB (TeaVM Runtime) |
| **Build** | `opaa-plugin build` (asc) | `mvn package` (TeaVM Maven Plugin) |
| **Zielgruppe** | Frontend-/Fullstack-Devs | Backend-/Java-Devs |

Der Java-Ansatz hat den Vorteil, dass `implements Connector<T>` zur Compile-Time sicherstellt, dass alle Pflicht-Methoden implementiert sind. Vergisst man `onEvent` bei einem Plugin mit `mode = HYBRID`, entsteht sofort ein Compiler-Fehler.

---

## 10. Bewertung: Pros und Cons des Wasm-Ansatzes

### 10.1 Vorteile

**Sicherheit & Isolation**

- Höchste Sandbox-Garantie aller drei Varianten: kein Zugriff auf Host-Speicher, Dateisystem oder Netzwerk ohne explizite Erlaubnis
- Domain-Allowlist für alle Netzwerkzugriffe — der Host kontrolliert, wohin das Plugin kommuniziert
- Kein Docker-Socket, keine Root-Rechte — ideal für Enterprise-Umgebungen mit strengen Sicherheitsrichtlinien
- Ein fehlerhaftes Plugin kann das Backend nicht crashen (isolierter linearer Speicher)

**Betrieb & Deployment**

- Millisekunden-Startup statt Sekunden (Container) — wichtig für On-Demand-Instanziierung
- Kein Docker/Kubernetes auf dem Host erforderlich — senkt die Einstiegshürde für Self-Hosted-Installationen drastisch
- Minimaler Ressourcenverbrauch: ein Wasm-Modul benötigt wenige MB RAM statt eines vollständigen Container-OS
- OCI-basierte Distribution passt in bestehende Enterprise-Infrastruktur (Registry, Signierung)

**Entwickler-Erfahrung**

- Sprachunabhängig: AssemblyScript, Rust, Go, Java — breite Zielgruppe
- Minimaler API-Contract (5 Funktionen) — niedrige Einstiegshürde für Plugin-Entwickler
- Lokales Testen mit Mock-Host ohne laufendes Backend
- Compile-Time-Validierung (besonders stark bei Java mit `implements Connector<T>`)

### 10.2 Nachteile

**Eingeschränktes Ökosystem**

- Keine Nutzung beliebiger Libraries: Jackson, Gson, Lombok, Spring-Komponenten — alles Reflection-basierte fällt weg
- Plugin-Entwickler müssen SDK-eigene Utilities für JSON, HTTP, etc. lernen
- Debugging von Wasm-Modulen ist deutlich schwieriger als bei nativem Java-Code

**Technische Einschränkungen**

- Kein Daemon-Verhalten: Plugins können keine Hintergrund-Threads starten — der Host muss Polling und Scheduling übernehmen
- Datenübergabe zwischen Host und Plugin erfordert Serialisierung/Deserialisierung (JSON über lineare Speichergrenzen)
- Große Binärdaten benötigen den Reference-based-Fetching-Umweg
- TeaVM (Java → Wasm) ist weniger ausgereift als native Wasm-Targets von Rust/Go

**Komplexität auf Host-Seite**

- Der Host übernimmt mehr Verantwortung: Scheduling, Webhook-Routing, HTTP-Proxying, KV-Store, Update-Management
- Extism/Chicory sind relativ junge Projekte — geringere Community und weniger Battle-Testing als Docker oder PF4J
- Host-Function-Schnittstelle muss stabil gehalten werden (Versionierung des Plugin-API-Contracts)

**Performance**

- Wasm-Ausführung ist langsamer als nativer JVM-Code (Faktor 2–5x je nach Workload)
- Für Connector-Plugins ist das unkritisch (I/O-bound), könnte aber bei rechenintensiven Plugins relevant werden

### 10.3 Risikobewertung

| Risiko | Wahrscheinlichkeit | Auswirkung | Mitigation |
|--------|-------------------|------------|------------|
| Chicory/Extism API-Änderungen | Mittel | Mittel | Abstraktionsschicht im Host, Pinning auf stabile Versionen |
| TeaVM-Bugs bei komplexem Java-Code | Mittel | Niedrig | AssemblyScript/Rust als primäre Sprachen empfehlen |
| Plugin-Entwickler-Akzeptanz | Mittel | Hoch | Gutes SDK, Templates, Dokumentation, `opaa-plugin` CLI |
| Performance bei vielen gleichzeitigen Plugins | Niedrig | Mittel | Plugin-Instanz-Pooling, Ressourcenlimits pro Plugin |

### 10.4 Fazit

Für OPAA als Self-Hosted-Enterprise-Produkt überwiegen die Vorteile des Wasm-Ansatzes deutlich. Die Sicherheitsgarantien und der Wegfall der Docker-Abhängigkeit sind entscheidende Differenzierungsmerkmale. Die Einschränkungen beim Library-Ökosystem sind für Connector-Plugins akzeptabel, da deren Aufgabe primär in API-Calls und Daten-Transformation besteht — nicht in komplexer Geschäftslogik.

**Empfehlung:** Wasm (Variante C) als primäre Plugin-Architektur implementieren. Langfristig kann eine zusätzliche Container-basierte Variante (Variante A) für Plugins mit speziellen Anforderungen (native Libraries, komplexe Dependencies) angeboten werden.
