# Architektur-Konzept: Plugin-System für Java/Spring Boot Backends

## 1. Einleitung

Ziel ist die Entwicklung eines erweiterbaren Backends, das Daten aus verschiedenen Quellen (Confluence, Dropbox, etc.) über Konnektoren konsumiert. Diese Konnektoren sollen als Plugins von Drittanbietern über einen Marketplace installierbar sein.

---

## 2. Variante A: Container-basierte Plugins (Out-of-Process)

In dieser Variante wird jedes Plugin als eigenständiger Container (Docker/Podman) ausgeführt und kommuniziert per API mit dem Spring Boot Backend.

### Technische Umsetzung

* **Kommunikation:** gRPC (empfohlen für Performance) oder REST/Webhooks.
* **Orchestrierung:** Das Backend steuert den Docker-Daemon über den Docker-Socket oder die Kubernetes-API (via Fabric8 oder offizieller Java-Client).
* **Isolation:** Vollständige Trennung auf Betriebssystemebene.

### Vor- und Nachteile

| Vorteile | Nachteile |
| --- | --- |
| **Maximale Isolation:** Plugin-Abstürze oder Leaks beeinflussen das Backend nicht. | **Sicherheitsrisiko:** Docker-Socket-Zugriff benötigt oft Root-Rechte (Enterprise-Problem). |
| **Sprachunabhängig:** Plugins können in jeder Sprache (Python, Go, Node) vorliegen. | **Ressourcenintensiv:** Jeder Container benötigt ein eigenes OS-Environment. |
| **Dependency-Management:** Keine Konflikte zwischen Libraries (Classpath). | **Komplexität:** Erfordert Docker/K8s Infrastruktur auf dem Host. |

---

## 3. Variante B: In-JVM Plugins (Klassisch)

Alle Plugins laufen innerhalb desselben Java-Prozesses wie das Spring Boot Backend.

### Technische Umsetzung

* **Framework:** **PF4J** (Plugin Framework for Java).
* **Mechanismus:** Eigene ClassLoader für jedes Plugin, um Library-Konflikte zu minimieren.
* **Deployment:** Plugins werden als `.jar`-Dateien in einen spezifischen Ordner geladen.

### Vor- und Nachteile

| Vorteile | Nachteile |
| --- | --- |
| **Einfachheit:** Keine zusätzliche Infrastruktur (Docker/K8s) nötig. | **Sicherheit:** Ein bösartiges Plugin kann den gesamten Speicher auslesen. |
| **Performance:** Direkte Methodenaufrufe ohne Netzwerk-Overhead. | **Instabilität:** Ein `OutOfMemoryError` im Plugin reißt das gesamte Backend mit. |
| **Entwicklung:** Einfaches Debugging innerhalb der IDE. | **Lock-in:** Plugins *müssen* in einer JVM-Sprache geschrieben sein. |

---

## 4. Variante C: WebAssembly (Wasm) – Die moderne Sandbox

Plugins werden in eine `.wasm`-Datei kompiliert und innerhalb der JVM in einer hochsicheren Sandbox ausgeführt.

### Technische Umsetzung

* **Runtime:** **Extism** (Framework) oder **Chicory** (pure Java Runtime).
* **Sprachen:** Plugins in **AssemblyScript** (TypeScript-Syntax), Rust, Go oder Java (via **TeaVM**).
* **Isolation:** "In-Process Sandboxing" – isolierter linearer Speicher für jedes Modul.

### Vor- und Nachteile

| Vorteile | Nachteile |
| --- | --- |
| **Höchste Sicherheit:** Kein Zugriff auf Host-System ohne explizite Erlaubnis. | **Komplexität:** Datenübergabe (Strings/Objekte) erfordert Frameworks wie Extism. |
| **Keine Root-Rechte:** Läuft vollständig im User-Space der JVM. | **Kein nativer Daemon:** Plugins laufen nur, wenn sie vom Host aufgerufen werden. |
| **Startzeit:** Millisekunden statt Sekunden (im Vergleich zu Containern). | **Eingeschränkte Libraries:** Keine Reflection oder direkten OS-Aufrufe möglich. |

---

## 5. Vergleich der Deployment-Strategien

### "One-Click" Installation

* **Container:** Backend triggert `docker pull` und `docker run` via API.
* **In-JVM:** Backend lädt `.jar` herunter und führt `pluginManager.loadPlugin()` aus.
* **Wasm:** Backend lädt `.wasm` (Byte-Array) und instanziiert die Runtime.

### Sicherheit & Enterprise-Compliance

* **Docker-Socket:** Oft in Firmen untersagt wegen Root-Eskalation.
* **Podman/Socket-Proxy:** Sicherere Alternativen für Container-Setups.
* **Wasm:** Ideal für Enterprise, da keine System-Änderungen am Host nötig sind.

---

## 6. Zusammenfassung der Plugin-Entwicklung (Sprachen)

1. **TypeScript (AssemblyScript):** Beste Wahl für Web-Entwickler. Nutzt `@extism/as-pdk` zur Kompilierung.
2. **Java (TeaVM):** Ermöglicht Java-Plugins ohne JVM-Abhängigkeit. Erfordert Verzicht auf Reflection.
3. **Rust/Go:** Maximale Performance und exzellente Wasm-Unterstützung.

---

## 7. Besonderheit: Daemon-Verhalten bei Wasm

Da Wasm-Module keine eigenständigen Hintergrund-Prozesse sind, muss das Backend das "Daemon-Verhalten" steuern:

* **Polling:** Spring Boot ruft das Plugin via `@Scheduled` regelmäßig auf.
* **Stateful:** Die Plugin-Instanz wird im Speicher gehalten, um den Zustand zwischen Aufrufen zu bewahren.
