# Deep Dive: PF4J Plugin-Architektur für OPAA Connectors

## 1. Einleitung

Dieses Dokument vertieft **Variante B (In-JVM / PF4J)** aus dem übergeordneten [Plugin-Architektur-Konzept](discussion-plugin-architecture.md) und beschreibt die konkrete Umsetzung als Plugin-Strategie für OPAA.

Behandelt werden:

- API-Contract zwischen Plugin und Host
- Datentransport inkl. großer Binärdateien
- Plugin-Paketierung und Distribution
- Marketplace-Konzept
- Plugin-SDK für Entwickler (Java + Kotlin)
- Bewertung mit Pros und Cons

**Referenz:** GitHub Issue [#106](https://github.com/criew/opaa/issues/106) (Epic) und Sub-Issues #126–#130.

Dieses Dokument ist das Gegenstück zum [Wasm Deep Dive](discussion-plugin-architecture-wasm-deep-dive.md) und dient dem direkten Vergleich beider Ansätze.

---

## 2. Warum PF4J als Alternative

PF4J (Plugin Framework for Java) ist ein etabliertes, leichtgewichtiges Plugin-Framework für die JVM. Plugins laufen im selben Prozess wie das Spring Boot Backend und kommunizieren über direkte Methodenaufrufe.

- **Volles Java-Ökosystem** — Jackson, Guava, Apache Commons, OkHttp — alles nutzbar ohne Einschränkungen
- **Keine Serialisierung** — direkte Objektübergabe zwischen Host und Plugin
- **Einfachstes Entwickler-Erlebnis** — Standard-Java/Kotlin-Entwicklung mit IDE-Support, Debugging, Profiling
- **Bewährtes Framework** — PF4J existiert seit 2012, wird aktiv gepflegt, stabile API
- **Nur JVM-Sprachen** — Java, Kotlin, Groovy, Scala

**Runtime:** PF4J als Plugin-Framework, eigene ClassLoader pro Plugin für Library-Isolation.

---

## 3. API-Contract: Host ↔ Plugin

### 3.1 Architekturüberblick

```
┌─────────────────────────────────────────────┐
│              Plugin (.jar / .zip)            │
│                                             │
│  IMPLEMENTS (Plugin implementiert):         │
│  ├─ ConnectorPlugin (Interface)             │
│  │   ├─ metadata() → ConnectorMetadata      │
│  │   ├─ configure(config) → void            │
│  │   ├─ validate() → ValidationResult       │
│  │   ├─ fetch(cursor) → FetchResult         │  ← PULL
│  │   └─ onEvent(event) → FetchResult        │  ← PUSH
│  │                                          │
│  OPTIONAL (Host stellt bereit):             │
│  ├─ ManagedHttpClient (mit Monitoring)      │
│  ├─ KeyValueStore (persistenter Speicher)   │
│  └─ PluginLogger (strukturiertes Logging)   │
│                                             │
│  EIGENE LIBRARIES (Plugin bringt mit):      │
│  ├─ Jackson, Gson, OkHttp, ...             │
│  └─ Beliebige JVM-Libraries                │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐
│              Host (Spring Boot)              │
│                                             │
│  ├─ PF4J PluginManager                      │
│  ├─ ClassLoader-Isolation pro Plugin        │
│  ├─ Scheduler (Pull-Polling)                │
│  ├─ Webhook Router (Push-Events)            │
│  ├─ Document Processor (Tika + Embeddings)  │
│  ├─ Registry Client (Marketplace-Index)     │
│  ├─ Plugin Services (HTTP, KV, Logging)     │
│  └─ Lifecycle Management (Load/Unload)      │
└─────────────────────────────────────────────┘
```

### 3.2 Plugin-Interface (was das Plugin implementiert)

Der Contract ist ein Java-Interface — Compile-Time-geprüft, typsicher, mit voller IDE-Unterstützung:

```java
package io.opaa.connector.api;

import org.pf4j.ExtensionPoint;

/**
 * Zentrale Schnittstelle für alle Connector-Plugins.
 * Jedes Plugin implementiert dieses Interface und wird von PF4J
 * automatisch erkannt und geladen.
 */
public interface ConnectorPlugin extends ExtensionPoint {

    /**
     * Liefert statische Metadaten über den Connector.
     * Wird beim Laden einmalig aufgerufen.
     */
    ConnectorMetadata metadata();

    /**
     * Empfängt die Konfiguration vom Host.
     * Werte stammen aus der Admin-UI, das Schema ist im Manifest definiert.
     */
    void configure(PluginConfig config);

    /**
     * Prüft, ob die Konfiguration gültig ist und eine Verbindung
     * zur Datenquelle hergestellt werden kann.
     */
    ValidationResult validate();

    /**
     * Holt Dokumente von der Datenquelle (PULL-Modus).
     * Der cursor ist null beim ersten Aufruf, danach der Wert
     * aus dem vorherigen FetchResult.
     */
    FetchResult fetch(String cursor);

    /**
     * Verarbeitet eingehende Webhook-Events (PUSH-Modus).
     * Nur für Plugins mit mode PUSH oder HYBRID.
     */
    default FetchResult onEvent(WebhookEvent event) {
        throw new UnsupportedOperationException(
            "Plugin does not support push mode");
    }
}
```

#### Datentypen

```java
public record ConnectorMetadata(
    String name,
    String version,
    ConnectorMode mode,
    String description
) {}

public enum ConnectorMode { PULL, PUSH, HYBRID }

public record ValidationResult(boolean ok, String message) {
    public static ValidationResult success(String message) {
        return new ValidationResult(true, message);
    }
    public static ValidationResult failure(String message) {
        return new ValidationResult(false, message);
    }
}

public record FetchResult(
    List<Document> documents,
    List<String> deletions,
    String cursor,
    boolean hasMore
) {
    public static Builder builder() { return new Builder(); }
}
```

### 3.3 Host-Services (optional, vom Host bereitgestellt)

Anders als bei Wasm, wo Host-Functions die **einzige** Möglichkeit für Netzwerk- und Speicherzugriff sind, sind Host-Services bei PF4J **optional**. Plugins können eigene Libraries nutzen. Der Host bietet aber managed Services mit Mehrwert:

```java
/**
 * Vom Host bereitgestellte Services, die Plugins optional nutzen können.
 * Zugriff über PluginContext, das bei configure() übergeben wird.
 */
public interface PluginContext {

    /**
     * Managed HTTP-Client mit Metriken, Rate Limiting und Logging.
     * Plugins können stattdessen eigene HTTP-Clients nutzen.
     */
    ManagedHttpClient httpClient();

    /**
     * Persistenter Key-Value-Store pro Plugin.
     * Überlebt Plugin-Neustarts und Updates.
     */
    KeyValueStore keyValueStore();

    /**
     * Strukturierter Logger mit Plugin-Kontext.
     * Logs erscheinen im Host-Log mit Plugin-Name und -Version.
     */
    PluginLogger logger();
}
```

**Warum optionale Services statt Pflicht?**

- Plugins sollen nicht gezwungen werden, Host-Abstraktionen zu nutzen
- Ein Plugin, das bereits OkHttp verwendet, soll nicht umgeschrieben werden müssen
- Aber: der Managed-HttpClient bietet Monitoring, Metriken und optionales Rate Limiting — ein Anreiz zur Nutzung

### 3.4 Connector-Modi

Wie beim Wasm-Ansatz deklarieren Plugins ihren Betriebsmodus:

| Modus | Beschreibung | Pflicht-Methoden |
|-------|-------------|-----------------|
| `PULL` | Host pollt periodisch | `metadata`, `configure`, `validate`, `fetch` |
| `PUSH` | Externe Events via Webhook | `metadata`, `configure`, `validate`, `onEvent` |
| `HYBRID` | Beides | Alle fünf Methoden |

Der Modus wird über die `ConnectorMetadata` deklariert und zusätzlich im Plugin-Manifest für den Host annotiert.

### 3.5 FetchResult-Datenmodell

```java
public record Document(
    String id,
    String title,
    DocumentContent content,
    String contentType,
    Map<String, Object> metadata,
    String hash
) {
    public static Builder builder() { return new Builder(); }
}
```

Der entscheidende Unterschied zu Wasm: `DocumentContent` ist kein JSON-String, sondern eine typsichere Abstraktion:

```java
public sealed interface DocumentContent {

    /** Textinhalt direkt als String (Markdown, HTML, Plain Text). */
    record Text(String value) implements DocumentContent {}

    /** Binärdaten als Byte-Array (kleine Dateien < 5 MB). */
    record Bytes(byte[] value) implements DocumentContent {}

    /**
     * InputStream für große Dateien (> 5 MB).
     * Der Host liest den Stream und pipt ihn direkt in Tika.
     * Kein doppeltes Buffering im RAM.
     */
    record Stream(InputStream value, long expectedSizeBytes)
        implements DocumentContent {}

    /**
     * Referenz auf eine URL, die der Host selbst herunterladen soll.
     * Nützlich wenn das Plugin die Daten nicht durch seinen eigenen
     * Speicher leiten will/kann.
     */
    record Reference(
        String url,
        String method,
        Map<String, String> headers,
        long expectedSizeBytes
    ) implements DocumentContent {}
}
```

---

## 4. Transport großer Binärdaten

### 4.1 Der Vorteil gegenüber Wasm

Da Plugin und Host im selben JVM-Prozess laufen, entfallen die fundamentalen Limitierungen des Wasm-Ansatzes:

- **Kein JSON-Serialisierungs-Overhead** — Objekte werden direkt als Java-Referenzen übergeben
- **Kein Base64-Encoding** — Binärdaten bleiben als `byte[]` oder `InputStream`
- **Kein linearer Speicher-Engpass** — der JVM-Heap ist die einzige Grenze
- **InputStream-Übergabe** — der Host kann direkt aus dem Plugin-Stream lesen, ohne die gesamte Datei zu buffern

### 4.2 Vier Transportmodi

| Modus | Wann | Wie | RAM-Verbrauch |
|-------|------|-----|---------------|
| **Text** | Markdown, HTML, Plain Text | `DocumentContent.Text("...")` | Nur der String |
| **Bytes** | Kleine Binärdaten (< 5 MB) | `DocumentContent.Bytes(bytes)` | Vollständig im RAM |
| **Stream** | Große Dateien (> 5 MB) | `DocumentContent.Stream(inputStream, size)` | Nur der aktuelle Puffer |
| **Reference** | Optionaler Fallback | `DocumentContent.Reference(url, headers)` | Keiner im Plugin |

### 4.3 Stream-basierter Transport (der Normalfall)

Ein 500 MB PDF? Das Plugin öffnet einen `InputStream`, der Host pipt ihn direkt in Tika:

```java
// Plugin-Seite
Document.builder()
    .id("confluence-att-99")
    .title("Architektur-Diagramm.pdf")
    .contentType("application/pdf")
    .content(new DocumentContent.Stream(
        confluenceClient.downloadAttachment(attachmentId),  // InputStream
        52_428_800L  // 50 MB
    ))
    .build();

// Host-Seite
switch (document.content()) {
    case DocumentContent.Text(var text) ->
        documentProcessor.processText(text, document.contentType());
    case DocumentContent.Bytes(var bytes) ->
        documentProcessor.processBytes(bytes, document.contentType());
    case DocumentContent.Stream(var stream, var size) ->
        documentProcessor.processStream(stream, document.contentType()); // kein Buffering
    case DocumentContent.Reference(var url, var method, var headers, var size) ->
        documentProcessor.processUrl(url, method, headers);
}
```

Kein Byte wird doppelt im RAM gehalten. Das ist ein massiver Vorteil gegenüber dem Wasm-Ansatz, bei dem große Dateien immer über Reference-based Fetching laufen müssen.

### 4.4 Verantwortungsteilung

Identisch zum Wasm-Ansatz — das Plugin ist ein **Daten-Lieferant**, der Host ist der **Daten-Verarbeiter**:

```
Plugin                              Host
──────                              ────
Datenquelle abfragen           →    Rohdaten empfangen
InputStream öffnen             →    Stream direkt in Tika pipen
Content-Type liefern           →    Parsing (PDF, DOCX, etc.)
Source-URL, Hash liefern       →    Embedding generieren
                                    In pgvector speichern
```

---

## 5. Plugin-Paketierung

### 5.1 PF4J Plugin-Format

PF4J unterstützt zwei Plugin-Formate:

**Format A: Plugin-JAR (einfach)**

```
confluence-connector-1.0.0.jar
├── META-INF/
│   ├── MANIFEST.MF
│   └── extensions.idx          # PF4J Extension-Index
├── plugin.properties           # PF4J Plugin-Descriptor
├── com/example/confluence/     # Plugin-Klassen
└── lib/                        # Eingebettete Dependencies (Fat-JAR)
```

**Format B: Plugin-ZIP (mit separaten Dependencies)**

```
confluence-connector-1.0.0.zip
├── plugin.properties           # PF4J Plugin-Descriptor
├── classes/                    # Plugin-Klassen
└── lib/
    ├── jackson-core-2.17.jar
    ├── okhttp-4.12.jar
    └── ...                     # Separate Dependency-JARs
```

### 5.2 Plugin-Descriptor (`plugin.properties`)

PF4J verwendet einen einfachen Descriptor:

```properties
plugin.id=confluence-connector
plugin.class=com.example.confluence.ConfluencePlugin
plugin.version=1.0.0
plugin.provider=OPAA Community
plugin.description=Imports pages from Atlassian Confluence
plugin.requires=>=0.5.0
plugin.license=MIT
plugin.dependencies=
```

### 5.3 OPAA Plugin-Manifest (`plugin.json`)

Zusätzlich zum PF4J-Descriptor definiert OPAA ein eigenes Manifest für Marketplace-Metadaten und Konfiguration:

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
    "engine": "pf4j",
    "minHostVersion": "0.5.0"
  },
  "connector": {
    "mode": "hybrid",
    "webhook": {
      "path": "/webhooks/confluence",
      "events": ["page:created", "page:updated", "page:removed"]
    }
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

Hinweis: Im Gegensatz zum Wasm-Manifest gibt es hier **keine `permissions.network`-Allowlist** — PF4J-Plugins haben vollen Netzwerkzugriff. Dieses Sicherheitsdefizit wird in Abschnitt 10 (Bewertung) adressiert.

### 5.4 Distribution: OCI Artifacts

Identisch zum Wasm-Ansatz — OCI Artifacts als Industriestandard:

```bash
# Plugin veröffentlichen
oras push ghcr.io/opaa-plugins/confluence-connector:1.2.0 \
  plugin.json:application/vnd.opaa.plugin.manifest.v1+json \
  confluence-connector-1.2.0.zip:application/vnd.opaa.plugin.pf4j.v1+zip \
  icon.svg:image/svg+xml

# Plugin installieren (vom Backend automatisiert)
oras pull ghcr.io/opaa-plugins/confluence-connector:1.2.0
```

Alternativ kann PF4J Plugins direkt von einem **Maven-Repository** laden — das ist ein natürlicherer Weg für JVM-Plugins:

```java
// PF4J UpdateManager mit Maven-Repository
UpdateManager updateManager = new UpdateManager(pluginManager);
updateManager.addRepository(new MavenUpdateRepository(
    "opaa-plugins",
    URI.create("https://maven.pkg.github.com/opaa-plugins/registry")
));
updateManager.installPlugin("confluence-connector", "1.2.0");
```

### 5.5 Bundle-Struktur (Entwicklersicht)

```
confluence-connector/
├── src/main/java/
│   └── com/example/confluence/
│       ├── ConfluencePlugin.java         # PF4J Plugin-Klasse
│       ├── ConfluenceConnector.java      # ConnectorPlugin-Implementierung
│       └── ConfluenceConfig.java         # Config-POJO
├── src/main/resources/
│   ├── plugin.properties                 # PF4J-Descriptor
│   └── plugin.json                       # OPAA-Manifest
├── src/test/java/
│   └── com/example/confluence/
│       └── ConfluenceConnectorTest.java
├── icon.svg
├── build.gradle.kts                      # oder pom.xml
└── README.md
```

---

## 6. Marketplace-Konzept

### 6.1 Stufe 1: Plugin Registry (Index)

Identisch zum Wasm-Ansatz — ein Git-Repository als zentraler Index:

```
opaa-plugin-registry/
├── plugins/
│   ├── confluence-connector.json
│   ├── dropbox-connector.json
│   └── github-connector.json
└── index.json
```

Jeder Eintrag verweist auf das Artifact:

```json
{
  "name": "confluence-connector",
  "description": "Import pages from Confluence",
  "author": "OPAA Community",
  "verified": true,
  "registry": "ghcr.io/opaa-plugins/confluence-connector",
  "latestVersion": "1.2.0",
  "categories": ["documentation", "wiki"],
  "downloads": 1523,
  "runtime": "pf4j"
}
```

### 6.2 Stufe 2: Discovery API im Backend

```
GET    /api/v1/plugins/available            # Marketplace durchsuchen
GET    /api/v1/plugins/available/{name}     # Plugin-Details
POST   /api/v1/plugins/install              # Plugin installieren
GET    /api/v1/plugins/installed            # Installierte Plugins
POST   /api/v1/plugins/{id}/configure       # Plugin konfigurieren
DELETE /api/v1/plugins/{id}                 # Plugin deinstallieren
```

PF4J bietet zusätzlich einen eingebauten **UpdateManager**, der direkt auf Maven-Repositories oder Update-URLs prüfen kann — das vereinfacht die Implementierung.

### 6.3 Stufe 3: Marketplace-UI im Frontend

Identisch zum Wasm-Ansatz:

- Suche und Filterung nach Kategorie
- Install/Uninstall mit einem Klick
- Konfigurationsformular (automatisch generiert aus `configSchema`)
- Statusanzeige (aktiv, fehlerhaft, wird aktualisiert)

### 6.4 Enterprise-Features

- **Private Registry:** Maven-Repository oder OCI-Registry (Air-Gapped)
- **Plugin-Allowlist:** Admin legt fest, welche Plugins installierbar sind
- **Signaturprüfung:** JAR-Signing mit Zertifikaten (etablierter Java-Standard)

JAR-Signing ist ein reiferer Standard als Cosign/Sigstore — Java-Ökosystem bietet hier mehr Tooling:

```bash
# Plugin signieren
jarsigner -keystore opaa-plugins.jks \
  confluence-connector-1.2.0.jar opaa-plugins

# Signatur prüfen (Host-Seite)
jarsigner -verify confluence-connector-1.2.0.jar
```

### 6.5 Plugin-Updates

Identisch zum Wasm-Ansatz — konfigurierbares Update-Verhalten pro Plugin:

| Policy | Verhalten |
|--------|-----------|
| `manual` | Admin wird benachrichtigt, installiert selbst |
| `auto` | Jede neue Version wird automatisch installiert |
| `auto-patch` | Nur Patch-Versionen (1.2.x) automatisch, Minor/Major manuell |

Update-Ablauf:

1. Neues Plugin-JAR/ZIP aus der Registry herunterladen
2. JAR-Signatur prüfen
3. Laufende Fetch-Operationen abschließen lassen
4. PF4J: `pluginManager.unloadPlugin(pluginId)` → `pluginManager.loadPlugin(newPath)` → `pluginManager.startPlugin(pluginId)`
5. `validate()` aufrufen — bei Fehler Rollback auf die vorherige Version

PF4J unterstützt Hot-Reloading nativ — Plugins können zur Laufzeit geladen und entladen werden, ohne das Backend neu zu starten.

---

## 7. Plugin-SDK für Entwickler

### 7.1 SDK-Schichten

```
┌──────────────────────────────────────────┐
│  Plugin-Code (Geschäftslogik)            │  ← Entwickler schreibt nur das
│  + beliebige Libraries (Jackson etc.)    │
├──────────────────────────────────────────┤
│  OPAA Connector API                      │  ← Interfaces + Datentypen
│  ├─ ConnectorPlugin Interface            │
│  ├─ Document, FetchResult, etc.          │
│  └─ PluginContext (opt. Host-Services)   │
├──────────────────────────────────────────┤
│  PF4J                                    │  ← Plugin-Lifecycle
│  ├─ Plugin / ExtensionPoint              │
│  ├─ ClassLoader-Isolation                │
│  └─ PluginManager                        │
├──────────────────────────────────────────┤
│  JVM                                     │
└──────────────────────────────────────────┘
```

Im Vergleich zum Wasm-Ansatz gibt es **keine PDK-Schicht** — der Overhead fällt komplett weg.

### 7.2 SDK-Artefakte

| Artefakt | Zweck |
|----------|-------|
| `io.opaa:connector-api` | Interface-Definitionen, Datentypen (`provided` scope) |
| `io.opaa:connector-testing` | Test-Utilities, MockPluginContext (`test` scope) |
| `io.opaa:connector-archetype` | Maven Archetype für Projekt-Scaffolding |

### 7.3 CLI-Tooling: `opaa-plugin`

```bash
# Neues Plugin-Projekt scaffolden
opaa-plugin init my-connector --lang java
opaa-plugin init my-connector --lang kotlin
# → Generiert Projektstruktur via Maven Archetype / Gradle Template

# Plugin bauen
opaa-plugin build
# → Führt Gradle/Maven Build aus, generiert Plugin-ZIP

# Plugin testen (gegen eingebetteten PF4J PluginManager)
opaa-plugin test
# → Führt Tests aus, prüft Contract-Compliance

# Plugin lokal starten (eingebetteter Mini-Host)
opaa-plugin dev
# → Startet minimalen Host mit PF4J, ruft fetch() auf, zeigt Ergebnisse
# → Hot-Reload bei Code-Änderungen (JVM HotSwap)

# Plugin paketieren
opaa-plugin pack
# → Erzeugt Plugin-ZIP + plugin.json für Distribution

# Plugin veröffentlichen
opaa-plugin publish --registry ghcr.io/opaa-plugins
# → Pusht OCI-Artifact oder deployt in Maven-Repository
```

---

## 8. Konkretes Beispiel: Java Confluence Connector

### 8.1 Projektstruktur

```
confluence-connector/
├── src/main/java/
│   └── com/example/confluence/
│       ├── ConfluencePlugin.java         # PF4J Plugin-Klasse (Lifecycle)
│       ├── ConfluenceConnector.java      # ConnectorPlugin-Implementierung
│       ├── ConfluenceConfig.java         # Config-POJO
│       ├── ConfluenceClient.java         # API-Client (Jackson + OkHttp)
│       └── model/
│           ├── ConfluencePage.java        # API-Response-Modell
│           └── ConfluenceAttachment.java
├── src/main/resources/
│   ├── plugin.properties
│   └── plugin.json
├── src/test/java/
│   └── com/example/confluence/
│       ├── ConfluenceConnectorTest.java
│       └── ConfluenceClientTest.java
├── icon.svg
├── build.gradle.kts
└── README.md
```

### 8.2 Build-Konfiguration (`build.gradle.kts`)

```kotlin
plugins {
    java
    id("org.pf4j") version "0.4.0"  // PF4J Gradle Plugin
}

dependencies {
    // OPAA API — vom Host bereitgestellt, nicht ins Plugin-JAR packen
    compileOnly("io.opaa:connector-api:1.0.0")

    // PF4J — ebenfalls vom Host bereitgestellt
    compileOnly("org.pf4j:pf4j:3.12.0")

    // Plugin-eigene Dependencies — werden ins Plugin-ZIP gepackt
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.guava:guava:33.0.0-jre")

    // Test
    testImplementation("io.opaa:connector-testing:1.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.assertj:assertj-core:3.26.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

### 8.3 PF4J Plugin-Klasse (Lifecycle)

```java
package com.example.confluence;

import org.pf4j.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PF4J Plugin-Klasse. Verwaltet den Lifecycle des Plugins
 * (start/stop). Die eigentliche Connector-Logik liegt in
 * ConfluenceConnector.
 */
public class ConfluencePlugin extends Plugin {

    private static final Logger log = LoggerFactory.getLogger(ConfluencePlugin.class);

    @Override
    public void start() {
        log.info("Confluence Connector plugin started");
    }

    @Override
    public void stop() {
        log.info("Confluence Connector plugin stopped");
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

### 8.5 API-Client (nutzt Jackson + OkHttp)

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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Typsicherer Confluence REST API Client.
 * Nutzt Jackson für Deserialisierung und OkHttp für HTTP.
 */
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
        mapper.findAndRegisterModules(); // JSR310 etc.
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

    public InputStream downloadAttachment(String downloadPath) {
        Request request = new Request.Builder()
            .url(baseUrl + "/download" + downloadPath)
            .header("Authorization", "Bearer " + apiToken)
            .build();

        Response response = http.newCall(request).execute();
        return response.body().byteStream();
    }

    public int fetchSpaceCount() {
        HttpUrl url = HttpUrl.parse(baseUrl + "/rest/api/space").newBuilder().build();
        JsonNode root = executeRequest(url);
        return root.get("size").asInt();
    }

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

### 8.6 Connector-Implementierung

```java
package com.example.confluence;

import io.opaa.connector.api.*;
import org.pf4j.Extension;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Extension
public class ConfluenceConnector implements ConnectorPlugin {

    private ConfluenceConfig config;
    private ConfluenceClient client;
    private PluginContext context;

    @Override
    public ConnectorMetadata metadata() {
        return new ConnectorMetadata(
            "confluence-connector",
            "1.0.0",
            ConnectorMode.HYBRID,
            "Imports Confluence pages and attachments"
        );
    }

    @Override
    public void configure(PluginConfig pluginConfig) {
        this.context = pluginConfig.context();
        this.config = pluginConfig.bind(ConfluenceConfig.class); // Jackson-basiert
        this.client = new ConfluenceClient(config.getBaseUrl(), config.getApiToken());
    }

    // ──────────────────────────────────────────────
    // PULL
    // ──────────────────────────────────────────────

    @Override
    public FetchResult fetch(String cursor) {
        int pageSize = 25;
        int start = cursor != null ? Integer.parseInt(cursor) : 0;

        var result = client.fetchPages(config.getSpaceKeys(), start, pageSize);
        List<Document> documents = new ArrayList<>();

        for (var page : result.pages()) {
            documents.add(pageToDocument(page));
            documents.addAll(attachmentsToDocuments(page));
        }

        String nextCursor = result.size() == pageSize
            ? String.valueOf(start + pageSize)
            : null;

        return FetchResult.builder()
            .documents(documents)
            .cursor(nextCursor)
            .hasMore(result.size() == pageSize)
            .build();
    }

    // ──────────────────────────────────────────────
    // PUSH
    // ──────────────────────────────────────────────

    @Override
    public FetchResult onEvent(WebhookEvent event) {
        String pageId = event.payload().get("page.id").toString();

        if ("page:removed".equals(event.type())) {
            return FetchResult.builder()
                .deletions(List.of("confluence-page-" + pageId))
                .build();
        }

        var page = client.fetchPage(pageId);

        return FetchResult.builder()
            .documents(List.of(pageToDocument(page)))
            .build();
    }

    // ──────────────────────────────────────────────
    // VALIDATE
    // ──────────────────────────────────────────────

    @Override
    public ValidationResult validate() {
        try {
            int count = client.fetchSpaceCount();
            return ValidationResult.success("Connected. " + count + " spaces found.");
        } catch (ConnectorException e) {
            return ValidationResult.failure("Connection failed: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────
    // Hilfsmethoden
    // ──────────────────────────────────────────────

    private Document pageToDocument(ConfluencePage page) {
        String body = page.getBodyHtml();

        return Document.builder()
            .id("confluence-page-" + page.getId())
            .title(page.getTitle())
            .content(new DocumentContent.Text(body))
            .contentType("text/html")
            .hash(sha256(body))
            .metadata(Map.of(
                "author", page.getAuthorName(),
                "lastModified", page.getLastModified().toString(),
                "sourceUrl", config.getBaseUrl() + page.getWebUiLink(),
                "labels", page.getLabels()
            ))
            .build();
    }

    private List<Document> attachmentsToDocuments(ConfluencePage page) {
        List<Document> docs = new ArrayList<>();

        for (var att : page.getAttachments()) {
            // Große Dateien: InputStream direkt an den Host übergeben
            InputStream stream = client.downloadAttachment(att.getDownloadPath());

            docs.add(Document.builder()
                .id("confluence-att-" + att.getId())
                .title(att.getTitle())
                .content(new DocumentContent.Stream(stream, att.getFileSize()))
                .contentType(att.getMediaType())
                .hash("sha256:" + att.getHash())
                .metadata(Map.of(
                    "parentDocument", "confluence-page-" + page.getId(),
                    "sourceUrl", config.getBaseUrl() + att.getWebUiLink()
                ))
                .build());
        }

        return docs;
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

### 8.7 Tests

```java
package com.example.confluence;

import io.opaa.connector.api.*;
import io.opaa.connector.testing.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class ConfluenceConnectorTest {

    private MockWebServer server;
    private ConfluenceConnector connector;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        connector = new ConfluenceConnector();
        connector.configure(PluginConfig.of(Map.of(
            "baseUrl", server.url("/").toString().replaceAll("/$", ""),
            "apiToken", "test-token",
            "spaceKeys", List.of("DEV", "OPS")
        )));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void fetchReturnsPages() {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("api-response.json")));

        FetchResult result = connector.fetch(null);

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).title()).isEqualTo("Test Page");
        assertThat(result.documents().get(0).contentType()).isEqualTo("text/html");
        assertThat(result.documents().get(0).content())
            .isInstanceOf(DocumentContent.Text.class);
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void fetchWithPagination() {
        // Erste Seite: 25 Ergebnisse → hasMore
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("page-1.json")));  // size: 25
        // Zweite Seite: 10 Ergebnisse → fertig
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("page-2.json")));  // size: 10

        FetchResult page1 = connector.fetch(null);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.cursor()).isEqualTo("25");

        FetchResult page2 = connector.fetch(page1.cursor());
        assertThat(page2.hasMore()).isFalse();
        assertThat(page2.cursor()).isNull();
    }

    @Test
    void attachmentsUseStreamContent() {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("page-with-attachment.json")));
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/pdf")
            .setBody("fake-pdf-content"));

        FetchResult result = connector.fetch(null);

        Document attachment = result.documents().stream()
            .filter(d -> d.id().startsWith("confluence-att-"))
            .findFirst().orElseThrow();

        assertThat(attachment.content()).isInstanceOf(DocumentContent.Stream.class);
        assertThat(attachment.contentType()).isEqualTo("application/pdf");
    }

    @Test
    void onEventDeleteRemovesDocument() {
        WebhookEvent event = WebhookEvent.of("page:removed",
            Map.of("page.id", "42"));

        FetchResult result = connector.onEvent(event);

        assertThat(result.documents()).isEmpty();
        assertThat(result.deletions()).containsExactly("confluence-page-42");
    }

    @Test
    void onEventUpdateReloadsPage() {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("single-page.json")));

        WebhookEvent event = WebhookEvent.of("page:updated",
            Map.of("page.id", "42"));

        FetchResult result = connector.onEvent(event);

        assertThat(result.documents()).hasSize(1);
        assertThat(result.documents().get(0).id()).isEqualTo("confluence-page-42");
    }

    @Test
    void validateSucceeds() {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("{\"size\": 3, \"results\": []}"));

        ValidationResult result = connector.validate();

        assertThat(result.ok()).isTrue();
        assertThat(result.message()).contains("3 spaces");
    }

    @Test
    void validateFailsWithBadCredentials() {
        server.enqueue(new MockResponse().setResponseCode(401));

        ValidationResult result = connector.validate();

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("Connection failed");
    }
}
```

---

## 9. Konkretes Beispiel: Kotlin Confluence Connector

Kotlin ist das natürliche Äquivalent zu AssemblyScript im Wasm-Ansatz — niedrigere Einstiegshürde, kompakterer Code, gleiche JVM.

### 9.1 Projektstruktur

```
confluence-connector-kotlin/
├── src/main/kotlin/
│   └── com/example/confluence/
│       ├── ConfluencePlugin.kt          # PF4J Plugin-Klasse
│       ├── ConfluenceConnector.kt       # ConnectorPlugin-Implementierung
│       ├── ConfluenceClient.kt          # API-Client
│       └── Model.kt                     # Datenklassen
├── src/main/resources/
│   ├── plugin.properties
│   └── plugin.json
├── src/test/kotlin/
│   └── com/example/confluence/
│       └── ConfluenceConnectorTest.kt
├── icon.svg
├── build.gradle.kts
└── README.md
```

### 9.2 Build-Konfiguration (`build.gradle.kts`)

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    id("org.pf4j") version "0.4.0"
}

dependencies {
    compileOnly("io.opaa:connector-api:1.0.0")
    compileOnly("org.pf4j:pf4j:3.12.0")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("io.opaa:connector-testing:1.0.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("org.assertj:assertj-core:3.26.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

### 9.3 Datenklassen

```kotlin
package com.example.confluence

data class ConfluenceConfig(
    val baseUrl: String,
    val apiToken: String,
    val spaceKeys: List<String>
)
```

### 9.4 API-Client

```kotlin
package com.example.confluence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream

class ConfluenceClient(
    private val baseUrl: String,
    private val apiToken: String
) {
    private val http = OkHttpClient()
    private val mapper = jacksonObjectMapper().apply { findAndRegisterModules() }

    fun fetchPages(spaceKeys: List<String>, start: Int, limit: Int): PageResult {
        val url = "$baseUrl/rest/api/content".toHttpUrl().newBuilder()
            .addQueryParameter("spaceKey", spaceKeys.joinToString(","))
            .addQueryParameter("start", start.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("expand",
                "body.storage,version,metadata.labels,children.attachment")
            .build()

        val root = executeRequest(url.toString())
        val pages = root["results"].map { mapper.treeToValue(it, ConfluencePage::class.java) }
        return PageResult(pages, root["size"].asInt())
    }

    fun fetchPage(pageId: String): ConfluencePage {
        val url = "$baseUrl/rest/api/content/$pageId".toHttpUrl().newBuilder()
            .addQueryParameter("expand", "body.storage,version,metadata.labels")
            .build()

        return mapper.treeToValue(executeRequest(url.toString()), ConfluencePage::class.java)
    }

    fun downloadAttachment(downloadPath: String): InputStream {
        val request = Request.Builder()
            .url("$baseUrl/download$downloadPath")
            .header("Authorization", "Bearer $apiToken")
            .build()

        return http.newCall(request).execute().body!!.byteStream()
    }

    fun fetchSpaceCount(): Int {
        val root = executeRequest("$baseUrl/rest/api/space")
        return root["size"].asInt()
    }

    private fun executeRequest(url: String): JsonNode {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiToken")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ConnectorException("HTTP ${response.code}: ${response.message}")
            }
            return mapper.readTree(response.body!!.string())
        }
    }

    data class PageResult(val pages: List<ConfluencePage>, val size: Int)
}
```

### 9.5 Connector-Implementierung

```kotlin
package com.example.confluence

import io.opaa.connector.api.*
import org.pf4j.Extension
import java.security.MessageDigest

@Extension
class ConfluenceConnector : ConnectorPlugin {

    private lateinit var config: ConfluenceConfig
    private lateinit var client: ConfluenceClient
    private lateinit var context: PluginContext

    override fun metadata() = ConnectorMetadata(
        name = "confluence-connector",
        version = "1.0.0",
        mode = ConnectorMode.HYBRID,
        description = "Imports Confluence pages and attachments"
    )

    override fun configure(pluginConfig: PluginConfig) {
        context = pluginConfig.context()
        config = pluginConfig.bind(ConfluenceConfig::class.java)
        client = ConfluenceClient(config.baseUrl, config.apiToken)
    }

    // ──────────────────────────────────────────────
    // PULL
    // ──────────────────────────────────────────────

    override fun fetch(cursor: String?): FetchResult {
        val pageSize = 25
        val start = cursor?.toInt() ?: 0

        val result = client.fetchPages(config.spaceKeys, start, pageSize)
        val documents = result.pages.flatMap { page ->
            listOf(pageToDocument(page)) + attachmentsToDocuments(page)
        }

        val nextCursor = if (result.size == pageSize) (start + pageSize).toString() else null

        return FetchResult.builder()
            .documents(documents)
            .cursor(nextCursor)
            .hasMore(result.size == pageSize)
            .build()
    }

    // ──────────────────────────────────────────────
    // PUSH
    // ──────────────────────────────────────────────

    override fun onEvent(event: WebhookEvent): FetchResult {
        val pageId = event.payload()["page.id"].toString()

        if (event.type() == "page:removed") {
            return FetchResult.builder()
                .deletions(listOf("confluence-page-$pageId"))
                .build()
        }

        val page = client.fetchPage(pageId)
        return FetchResult.builder()
            .documents(listOf(pageToDocument(page)))
            .build()
    }

    // ──────────────────────────────────────────────
    // VALIDATE
    // ──────────────────────────────────────────────

    override fun validate(): ValidationResult = try {
        val count = client.fetchSpaceCount()
        ValidationResult.success("Connected. $count spaces found.")
    } catch (e: ConnectorException) {
        ValidationResult.failure("Connection failed: ${e.message}")
    }

    // ──────────────────────────────────────────────
    // Hilfsmethoden
    // ──────────────────────────────────────────────

    private fun pageToDocument(page: ConfluencePage): Document {
        val body = page.bodyHtml
        return Document.builder()
            .id("confluence-page-${page.id}")
            .title(page.title)
            .content(DocumentContent.Text(body))
            .contentType("text/html")
            .hash(sha256(body))
            .metadata(mapOf(
                "author" to page.authorName,
                "lastModified" to page.lastModified.toString(),
                "sourceUrl" to "${config.baseUrl}${page.webUiLink}",
                "labels" to page.labels
            ))
            .build()
    }

    private fun attachmentsToDocuments(page: ConfluencePage): List<Document> =
        page.attachments.map { att ->
            Document.builder()
                .id("confluence-att-${att.id}")
                .title(att.title)
                .content(DocumentContent.Stream(
                    client.downloadAttachment(att.downloadPath),
                    att.fileSize
                ))
                .contentType(att.mediaType)
                .hash("sha256:${att.hash}")
                .metadata(mapOf(
                    "parentDocument" to "confluence-page-${page.id}",
                    "sourceUrl" to "${config.baseUrl}${att.webUiLink}"
                ))
                .build()
        }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return "sha256:${hash.joinToString("") { "%02x".format(it) }}"
    }
}
```

### 9.6 Tests

```kotlin
package com.example.confluence

import io.opaa.connector.api.*
import io.opaa.connector.testing.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConfluenceConnectorTest {

    private lateinit var server: MockWebServer
    private lateinit var connector: ConfluenceConnector

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()

        connector = ConfluenceConnector()
        connector.configure(PluginConfig.of(mapOf(
            "baseUrl" to server.url("/").toString().trimEnd('/'),
            "apiToken" to "test-token",
            "spaceKeys" to listOf("DEV", "OPS")
        )))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `fetch returns pages`() {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("api-response.json")))

        val result = connector.fetch(null)

        assertThat(result.documents()).hasSize(1)
        assertThat(result.documents()[0].title()).isEqualTo("Test Page")
        assertThat(result.documents()[0].contentType()).isEqualTo("text/html")
        assertThat(result.documents()[0].content())
            .isInstanceOf(DocumentContent.Text::class.java)
        assertThat(result.hasMore()).isFalse()
    }

    @Test
    fun `fetch with pagination`() {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("page-1.json")))
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("page-2.json")))

        val page1 = connector.fetch(null)
        assertThat(page1.hasMore()).isTrue()
        assertThat(page1.cursor()).isEqualTo("25")

        val page2 = connector.fetch(page1.cursor())
        assertThat(page2.hasMore()).isFalse()
        assertThat(page2.cursor()).isNull()
    }

    @Test
    fun `attachments use stream content`() {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("page-with-attachment.json")))
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/pdf")
            .setBody("fake-pdf-content"))

        val result = connector.fetch(null)

        val attachment = result.documents()
            .first { it.id().startsWith("confluence-att-") }

        assertThat(attachment.content()).isInstanceOf(DocumentContent.Stream::class.java)
        assertThat(attachment.contentType()).isEqualTo("application/pdf")
    }

    @Test
    fun `onEvent delete removes document`() {
        val event = WebhookEvent.of("page:removed", mapOf("page.id" to "42"))

        val result = connector.onEvent(event)

        assertThat(result.documents()).isEmpty()
        assertThat(result.deletions()).containsExactly("confluence-page-42")
    }

    @Test
    fun `onEvent update reloads page`() {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(Fixtures.load("single-page.json")))

        val event = WebhookEvent.of("page:updated", mapOf("page.id" to "42"))

        val result = connector.onEvent(event)

        assertThat(result.documents()).hasSize(1)
        assertThat(result.documents()[0].id()).isEqualTo("confluence-page-42")
    }

    @Test
    fun `validate succeeds`() {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"size": 3, "results": []}"""))

        val result = connector.validate()

        assertThat(result.ok()).isTrue()
        assertThat(result.message()).contains("3 spaces")
    }

    @Test
    fun `validate fails with bad credentials`() {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = connector.validate()

        assertThat(result.ok()).isFalse()
        assertThat(result.message()).contains("Connection failed")
    }
}
```

### 9.7 Vergleich Java vs. Kotlin

| Aspekt | Java | Kotlin |
|--------|------|--------|
| **Einstiegshürde** | Niedrig (Standard-Java) | Niedrig (kompakter, idiomatischer) |
| **Boilerplate** | Mehr (Getter, Builder) | Weniger (data classes, `lateinit`, Extension Functions) |
| **Null-Safety** | Nur via Annotationen | Eingebaut (`String?` vs `String`) |
| **Config-Bindung** | Jackson + Getter | Jackson + `data class` (keine Getter nötig) |
| **Test-Lesbarkeit** | Gut | Sehr gut (Backtick-Methodennamen) |
| **IDE-Support** | Exzellent | Exzellent (IntelliJ) |
| **Build-Größe** | Plugin-Klassen | Plugin-Klassen + Kotlin-Stdlib (~1.5 MB) |
| **Zielgruppe** | Java-Backend-Devs | Kotlin/Android-Devs, moderne JVM-Devs |

---

## 10. Bewertung: Pros und Cons des PF4J-Ansatzes

### 10.1 Vorteile

**Volles Java-Ökosystem**

- Jede JVM-Library nutzbar: Jackson, Guava, OkHttp, Apache HttpClient, Jsoup, etc.
- Keine Einschränkungen bei Reflection, Threads, I/O, Annotation Processing
- Plugin-Entwickler arbeiten in ihrer gewohnten Umgebung — keine neue Toolchain zu lernen
- Voller IDE-Support: Debugging, Profiling, Step-Through in Plugin-Code

**Einfachster Datentransport**

- Direkte Objektübergabe — kein JSON-Serialisierungs-Overhead
- `InputStream`-Übergabe für große Dateien — kein Base64, kein Reference-based Fetching
- Kein Speicher-Engpass durch Wasm-linearen-Speicher
- Sealed Interface `DocumentContent` bietet typsichere Transportmodi

**Entwickler-Erfahrung**

- Standard Java/Kotlin-Entwicklung — kein Wasm-Tooling nötig
- Debugging mit Breakpoints direkt im Plugin-Code
- Profiling mit JVM-Standard-Tools (JFR, VisualVM)
- Schneller Build-Cycle: Compile → Test → Fertig (kein Wasm-Compile-Schritt)
- Hot-Reload über PF4J + JVM HotSwap

**Bewährte Technologie**

- PF4J existiert seit 2012, stabile API, aktive Community
- JAR-Signing ist ein etablierter Java-Standard
- ClassLoader-Isolation ist ein bekanntes, verstandenes Konzept
- Maven/Gradle-basierte Distribution passt ins JVM-Ökosystem

**Betrieb**

- Kein zusätzlicher Runtime-Overhead — Plugins laufen im bestehenden JVM-Prozess
- PF4J unterstützt Hot-Reloading nativ
- Direkte Methodenaufrufe — beste Performance aller drei Varianten
- UpdateManager für Plugin-Updates eingebaut

### 10.2 Nachteile

**Sicherheit — das fundamentale Problem**

- **Kein Sandboxing:** Ein Plugin hat vollen Zugriff auf den JVM-Heap — kann API-Keys anderer Plugins, Datenbank-Credentials und User-Daten auslesen
- **Keine Netzwerk-Isolation:** Plugins können beliebige Netzwerk-Connections öffnen — keine Domain-Allowlist möglich
- **Prozess-Instabilität:** `System.exit()`, `OutOfMemoryError` oder Endlosschleifen im Plugin crashen das gesamte Backend
- **Keine Ressourcen-Limits:** Ein Plugin kann beliebig viel RAM und CPU verbrauchen
- Java SecurityManager ist deprecated (Java 17) und entfernt (Java 24) — keine Nachfolge-Lösung
- ClassLoader-Isolation schützt vor Library-Konflikten, **nicht vor bösartigem Code**

**Nur JVM-Sprachen**

- Plugins müssen in Java, Kotlin, Groovy oder Scala geschrieben sein
- Web-Entwickler (TypeScript/JavaScript) und Rust/Go-Entwickler sind ausgeschlossen
- Kleinerer potenzieller Entwickler-Pool als bei Wasm

**Dependency-Konflikte**

- Trotz ClassLoader-Isolation können transitive Dependencies kollidieren
- Plugin A nutzt Jackson 2.15, Plugin B nutzt Jackson 2.17 — ClassLoader-Hell
- PF4J's ClassLoader-Strategie (Parent-First vs. Plugin-First) erfordert sorgfältige Konfiguration
- Shared Dependencies (connector-api) müssen exakt gleiche Versionen haben

**Enterprise-Compliance**

- Kein Sandbox-Nachweis für Sicherheits-Audits — schwer gegenüber Compliance-Teams zu argumentieren
- Plugins können auf das Dateisystem zugreifen — sensible Daten auf dem Host sind exponiert
- Kein nachvollziehbares Permissions-Modell (im Gegensatz zu Wasm Network-Allowlist)

### 10.3 Sicherheits-Mitigationen (und ihre Grenzen)

| Mitigation | Schutz | Grenzen |
|------------|--------|---------|
| **Code-Review** (manuelle Prüfung vor Marketplace-Aufnahme) | Verhindert offensichtlich bösartige Plugins | Skaliert nicht, subtile Backdoors schwer erkennbar |
| **JAR-Signing** (kryptographische Signatur) | Garantiert Herkunft und Integrität | Schützt nicht vor legitimem aber fehlerhaftem Code |
| **ClassLoader-Isolation** (PF4J Standard) | Verhindert Library-Konflikte | Kein Sicherheits-Sandboxing |
| **Custom SecurityManager** (vor Java 24) | Einschränkbare Permissions | Deprecated, entfernt in Java 24, umgehbar |
| **JPMS Module-Boundaries** | Begrenzte Sichtbarkeit | Plugin muss kooperieren, nicht erzwingbar |
| **Watchdog-Thread** (Timeouts, Ressourcen-Monitoring) | Erkennt Endlosschleifen, Memory-Leaks | Reaktiv, nicht präventiv — Schaden kann schon passiert sein |
| **Plugin-Allowlist** (nur verifizierte Plugins) | Reduziert Risiko auf vertrauenswürdige Quellen | Eingeschränktes Ökosystem, hoher Review-Aufwand |

**Fazit Sicherheit:** Keine der Mitigationen bietet echtes Sandboxing. PF4J erfordert **vollständiges Vertrauen** in Plugin-Code. Das ist für First-Party-Plugins und einen kuratierten Marketplace akzeptabel, aber problematisch für ein offenes Ökosystem mit Drittanbieter-Plugins.

### 10.4 Risikobewertung

| Risiko | Wahrscheinlichkeit | Auswirkung | Mitigation |
|--------|-------------------|------------|------------|
| Bösartiges Plugin liest Host-Speicher aus | Niedrig (bei kuratiertem Marketplace) | **Kritisch** | Code-Review, Signierung, Allowlist |
| Plugin-Bug crasht das Backend | Mittel | **Hoch** | Watchdog, Isolierte Thread-Pools, Auto-Restart |
| ClassLoader-Konflikte | Mittel | Mittel | Plugin-First-ClassLoader, Dependency-Shading |
| Enterprise-Compliance-Ablehnung | Hoch (in regulierten Branchen) | **Hoch** | Zusätzlicher Container-Modus als Alternative |
| Fehlende Entwickler-Diversität (nur JVM) | Mittel | Mittel | Kotlin als niedrigschwellige Alternative anbieten |

### 10.5 Fazit

PF4J bietet das **einfachste und produktivste Entwickler-Erlebnis** aller drei Varianten. Für ein Projekt mit einem kleinen, kuratierten Ökosystem von First-Party-Plugins und vertrauenswürdigen Community-Plugins ist es eine pragmatische Wahl.

Der fundamentale Nachteil ist das fehlende Sandboxing. In einem offenen Marketplace-Modell, in dem beliebige Drittanbieter Plugins veröffentlichen können, stellt dies ein erhebliches Sicherheitsrisiko dar — insbesondere für Enterprise-Kunden in regulierten Branchen.

**Empfehlung:** PF4J eignet sich als Einstieg für ein PoC und für First-Party-Plugins. Für ein offenes Plugin-Ökosystem mit Drittanbieter-Plugins sollte langfristig Wasm (Variante C) oder eine hybride Strategie (PF4J für vertrauenswürdige Plugins + Wasm für Drittanbieter) in Betracht gezogen werden.

---

## 11. Direktvergleich: PF4J vs. Wasm

| Aspekt | PF4J (In-JVM) | Wasm (Extism/Chicory) |
|--------|---------------|----------------------|
| **Sicherheit** | Kein Sandboxing, volles Vertrauen nötig | Echtes Sandboxing, Least-Privilege |
| **Performance** | Direkte Methodenaufrufe (schnellste) | 2–5x langsamer (für Connector I/O irrelevant) |
| **Ökosystem** | Volles Java-Ökosystem (Jackson etc.) | Eingeschränkt (kein Reflection, eigene Utils) |
| **Sprachen** | Nur JVM (Java, Kotlin) | Multi-Language (AS, Rust, Go, Java) |
| **Datentransport** | InputStream direkt (am effizientesten) | Reference-based für große Dateien |
| **Debugging** | IDE-Breakpoints, JFR, VisualVM | Schwieriger (Wasm-Tracing) |
| **Enterprise** | Schwer zu argumentieren (kein Sandbox) | Ideal (kein Docker, keine Root-Rechte) |
| **Dependency-Konflikte** | Möglich (ClassLoader-Hell) | Unmöglich (isolierter Speicher) |
| **Komplexität (Host)** | Niedriger (PF4J übernimmt viel) | Höher (Scheduling, HTTP-Proxy, KV-Store) |
| **Komplexität (Plugin)** | Niedrig (Standard-Java) | Mittel (SDK-Utilities, Wasm-Constraints) |
| **Reife** | Hoch (PF4J seit 2012) | Mittel (Extism/Chicory relativ jung) |
