# ADR-0060: Schlanke Konfiguration und Einrichtungs-Assistent mit Installationsmodus

## Status

Akzeptiert · zuletzt geändert 2026-10-25 · ersetzt Entscheidung 3 von ADR-0025 teilweise (Übernahme des ersten Anbieters aus der Umgebung) · ersetzt Entscheidung 5 von ADR-0033 teilweise (Anlage des Erstadministrators aus der Umgebung) · ergänzt ADR-0042 und ADR-0053

## Kontext

Viele Werte kommen heute aus der Konfiguration (Umgebungsvariablen, `application.yml`), obwohl sie
fachliche Einstellungen sind. Drei Werte wirken nur einmal: Beim ersten Start übernehmen
`LlmModelSeeder`, `OidcProviderSeeder` und `LocalAdminSeeder` sie in die Datenbank, danach liest sie
niemand mehr. Andere Werte sind Vorgaben, die ein Datenbankwert zur Laufzeit überschreibt, etwa das
Speicherkontingent privater Bibliotheken (`PersonalStorageQuota`) oder der Vollabgleich-Rhythmus von
Confluence, Google Drive, SharePoint/OneDrive und Dropbox. Für Betrieb und Verwaltung ist so schwer
zu erkennen, welcher Wert gilt; `OPAA_OIDC_BOOTSTRAP=force` existiert nur als Notbehelf dafür.

Die Vektordimension steht als Flyway-Platzhalter (`VectorStoreMigrationPlaceholders`) in der
Migration, die `vector_store` anlegt. Das Schema entsteht damit, bevor jemand ein Embedding-Modell
gewählt hat. Die Erstinstallation verlangt eine `.env` mit Startwerten, deren Wirkung nach dem
ersten Start endet.

Bestandsaufnahme der 371 Variablen und Ablauf des Assistenten:
[Spezifikation](../features/installation-and-settings.md). Issue #3160.

## Entscheidung

### 1. Drei Quellen, je Wert genau eine

Ein Wert kommt aus genau einer Quelle: aus der **Konfiguration** (Start und Infrastruktur, Schlüssel,
Grenzen des Betriebs, Lastgrenzen und Technik, Experten-Feinabstimmung), aus der **Datenbank**
(fachliche Einstellungen) oder als **Werkseinstellung im Code**, solange die Datenbank für eine
fachliche Einstellung nichts enthält. Eine Konfigurationsvariable ist nie die Vorgabe einer
fachlichen Einstellung und nie ein Startwert. Fachliche Einstellungen ändert nur die Systemverwaltung
über Oberfläche oder abgesicherte REST-API, ohne Neustart. Begründung: Wer einen Wert sucht, findet
ihn an einer Stelle, und eine geänderte Konfiguration wirkt immer oder nie, nie „nur beim ersten
Start“.

### 2. Grenzen des Betriebs bleiben in der Konfiguration und begrenzen die Datenbank

Allowlists der Adressprüfung und des Dateisystem-Konnektors, die Funktionsschalter `OPAA_FEATURES_*`,
Netzbeschränkungen der Verwaltung und Obergrenzen (etwa `OPAA_EXTERNAL_ACCESS_MAX_RELEASE_DAYS`,
`OPAA_LIBRARY_QUOTA_BYTES`, die Mindestgruppengröße) bleiben in der Konfiguration. Eine fachliche
Einstellung, die eine solche Grenze berührt, wird gegen sie geprüft; die Verwaltung entscheidet nur
innerhalb der Grenze. Begründung: Wer nur Rechte in der Anwendung hat, darf die Schutzgrenzen des
Betriebs nicht aufweichen, etwa um interne Adressen abrufen oder Pfade außerhalb der Freigabe lesen
zu lassen.

### 3. Experten-Feinabstimmung von Suche und Index bleibt vorerst in der Konfiguration

`OPAA_QUERY_*`, `OPAA_SEARCH_*`, `OPAA_MCP_*` und die Zerlegung in Chunks bleiben
Konfigurationswerte. Ihre Vorgaben sind gegen die Evaluierungsdomänen gemessen, und eine Änderung
wirkt auf die Antwortqualität aller Spaces oder verlangt eine Neuindexierung; eine Oberfläche dafür
bräuchte Leitplanken und Messung, die es nicht gibt.

### 4. Installationszustand in der Datenbank, Installationsmodus beim ersten Start

Der Zustand „nicht installiert“ oder „installiert“ ist eine Zeile der Datenbank, die alle Instanzen
lesen (ADR-0053). Solange die Installation nicht abgeschlossen ist, läuft jede Instanz im
**Installationsmodus**: Erreichbar sind nur die Installations-API, die öffentliche Konfiguration der
Oberfläche und die Health-Endpunkte; jeder andere Endpunkt antwortet `503`. Die Readiness bleibt
`UP`, damit der Assistent erreichbar ist; der Modus erscheint als eigene Health-Komponente und
Metrik. Es gibt keinen Rückweg aus der Oberfläche in den Installationsmodus.

### 5. Installations-Token

Die Installations-API verlangt ein Installations-Token. Setzt der Betrieb `OPAA_INSTALLATION_TOKEN`
(etwa aus einem Kubernetes-Secret), gilt dieses; sonst erzeugt der erste Start ein Token und
schreibt es einmalig als markierten Block ins Anwendungslog, wie das Einmalpasswort aus ADR-0033,
Entscheidung 5. Gespeichert wird nur ein Hash; mit dem Abschluss verfällt es. Zusätzlich
beschränkt `OPAA_INSTALLATION_ALLOWED_CIDRS` die API auf Netzbereiche (Grenze des Betriebs).
Begründung: Ohne Token übernähme die Installation, wer die Adresse zuerst aufruft.

### 6. Installations-API: Schritte, Pflicht und optional, Abschluss unter Sperre

Die Installations-API speichert jeden Schritt einzeln; ein Abbruch setzt an derselben Stelle fort.
Pflichtschritte sind erstes Systemverwaltungskonto, Anmeldeverfahren, Chat-Modell und
Embedding-Modell, jeweils mit erfolgreicher Prüfung (Verbindungstest, Probeeinbettung). Alle übrigen
Schritte sind optional und später in der Verwaltung erreichbar. Der Abschluss ist eine Transaktion
unter einem Advisory-Lock und gelingt nur, wenn jeder Pflichtschritt geprüft ist. Danach antwortet
jede Operation der Installations-API `404`, auf jeder Instanz. Begründung für `404`: Der Endpunkt
verrät nach dem Abschluss nicht, dass es ihn gibt, wie der MCP-Endpunkt bei geschlossenem Kanal.

### 7. Antwortdatei: Installation ohne Browser

Eine Antwortdatei (JSON) enthält dieselben Angaben wie die Schritte des Assistenten. Sie wird auf
zwei Wegen angenommen, mit derselben Prüfung: über die Installations-API mit dem Token, oder beim
Start aus der Datei unter `OPAA_INSTALLATION_ANSWER_FILE`, wenn die Installation noch nicht
abgeschlossen ist. In der Datei stehen Geheimnisse (Passwort, Modellschlüssel) nie im Klartext,
sondern als Verweis auf eine Datei, damit sie aus Secrets kommen; über die API kommen sie im Rumpf
der Anfrage wie in den einzelnen Schritten. Helm-Chart, Compose, e2e-Stack, Erprobungsaufbauten und
das Profil `dev` installieren über die Antwortdatei. Begründung: GitOps und CI klicken keinen
Assistenten; ein einziger Prüfweg verhindert, dass Browser- und Dateiinstallation auseinanderlaufen.
Ein Dateiverweis über die API ließe jeden Inhaber des Tokens Dateien des Backends lesen und an eine
Modelladresse seiner Wahl senden.

### 8. Erstes Systemverwaltungskonto aus dem Assistenten

Das im Assistenten angelegte Konto ist das lokale Notanker-Konto aus ADR-0033, Entscheidung 5
(`is_bootstrap`, lokaler Anbieter), mit der dort eingegebenen Adresse und dem dort gesetzten Passwort.
`OPAA_INITIAL_ADMIN_EMAIL` und `OPAA_INITIAL_ADMIN_PASSWORD` entfallen, ebenso das erzeugte
Einmalpasswort. `OPAA_LOCAL_ADMIN_RESET=force` bleibt der Wiederanlauf des Betriebs.

### 9. Anmeldeverfahren aus dem Assistenten

Der Assistent legt das Anmeldeverfahren fest: nur lokale Konten, oder zusätzlich ein
OIDC-Identitätsanbieter mit Verbindungstest als Standardanbieter. `OPAA_OIDC_ISSUER_URI`,
`OPAA_OIDC_CLIENT_ID`, `OPAA_OIDC_JWK_SET_URI`, `OPAA_OIDC_AUTHORITY` und `OPAA_OIDC_BOOTSTRAP`
entfallen, ebenso die Erlaubnis ihrer Hosts in der Adressprüfung; deren Allowlist ist allein
`OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST`.

### 10. Modelle in der Datenbank, Vektortabelle nach der Wahl des Embedding-Modells

Chat-, Embedding-, Rerank- und Transkriptionsmodell sind Einträge der Modellverwaltung; ihre Adressen,
Schlüssel und Modellnamen kommen nicht mehr aus der Konfiguration, ihre Timeouts und Lastgrenzen
schon. `vector_store` entsteht nicht in der Migration, sondern beim Abschluss der Installation,
unter dem Advisory-Lock aus Entscheidung 6 und mit der Dimension, die die Probeeinbettung des
gewählten Modells liefert. `OPAA_PGVECTOR_DIMENSIONS` entfällt. Die knowledge-Baseline legt
`vector_store` weiterhin an, weil ein ausgeführtes Skript unverändert bleibt; eine spätere Migration
entfernt die leere Tabelle auf einer Datenbank, auf der noch keine Instanz gestartet ist. Eine
Installation von davor behält ihre Tabelle und übernimmt beim ersten Start einmalig das Modell, mit
dem sie lief, samt Dimension ihrer bisherigen Konfiguration. Nach dem Abschluss lässt sich das
Embedding-Modell nicht über die Oberfläche wechseln; ein Wechsel verlangt eine Neuindexierung und
ist ein eigenes Thema (Offen).

### 11. Jede Änderung einer Einstellung ist nachweisbar

Jede Änderung einer fachlichen Einstellung über Oberfläche, REST-API oder Installation erzeugt einen
Eintrag im Revisionsprotokoll mit vorherigem und neuem Wert; Geheimnisse erscheinen nur als
„geändert“. Der Abschluss der Installation ist ein eigenes Ereignis.

### 12. Export und Import der Einstellungen

Die Systemverwaltung exportiert alle fachlichen Einstellungen als Datei ohne Geheimnisse und
importiert sie in eine andere Installation; der Import prüft jeden Wert wie die Oberfläche und
gegen die Grenzen des Betriebs (Entscheidung 2) und ist ein Revisionsereignis. Begründung: Liegen
die Einstellungen in der Datenbank, lassen sich Test und Produktion nicht mehr über die
Konfiguration vergleichen.

## Konsequenzen

- Die Konfiguration schrumpft auf Start, Infrastruktur, Schlüssel, Grenzen, Lastgrenzen und
  Experten-Feinabstimmung; 48 Variablen entfallen oder wandern in die Datenbank.
- Eine Rücksicherung der Datenbank rollt Einstellungen mit zurück; das Handbuch-Kapitel zu Sicherung
  und Wiederherstellung nennt die Prüfschritte.
- Eine Instanz mit leerer Datenbank ist erst nach der Installation nutzbar; automatisierte Aufbauten
  brauchen die Antwortdatei.
- Wer das Installations-Token aus dem Log liest, kann die Installation abschließen; Log-Zugriff ist
  damit so schutzwürdig wie heute beim Einmalpasswort.
- Rerank- und Transkriptionsmodell brauchen eine Verwaltungsoberfläche, die es bisher nicht gibt.

## Verworfene Alternativen

- Vorgaben aus der Konfiguration, die die Datenbank überschreibt — zwei Orte für einen Wert, genau
  das Problem dieses ADR.
- Installations-API ohne Token, abgesichert nur über Netzbereiche — eine Fehlkonfiguration des Netzes
  gäbe die Installation frei.
- Antwortdatei nur über die API — ein Helm-Hook oder Job müsste das Token kennen und den Zeitpunkt
  abpassen; die Datei beim Start ist deklarativ.
- Grenzen des Betriebs in die Oberfläche holen — hebt die Trennung zwischen Betrieb und
  Systemverwaltung auf (Entscheidung 2).
- `410 Gone` nach dem Abschluss — bestätigt die Existenz des Endpunkts ohne Nutzen für den Betrieb.

## Offen

- Wechsel des Embedding-Modells nach der Installation mit geführter Neuindexierung.
- Ob die Experten-Feinabstimmung (Entscheidung 3) später mit Messung und Leitplanken in die
  Verwaltung wandert.
