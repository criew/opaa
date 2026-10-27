# ADR-0060: Schlanke Konfiguration und Einrichtungs-Assistent mit Installationsmodus

## Status

Akzeptiert · zuletzt geändert 2026-10-27 · ersetzt Entscheidung 3 von ADR-0025 teilweise (Übernahme des ersten Anbieters aus der Umgebung) · ersetzt Entscheidung 5 von ADR-0033 teilweise (Anlage des Erstadministrators aus der Umgebung) · ergänzt ADR-0042 und ADR-0053 · ergänzt durch [ADR-0061](0061-wechsel-des-embedding-modells.md)

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
Schritte sind optional und später in der Verwaltung erreichbar, heute der E-Mail-Versand. Ein
optionaler Schritt wird übersprungen, indem die API seinen Zwischenstand verwirft; das ist ein
Revisionsereignis. Ein übersprungener optionaler Schritt hält den Abschluss nicht auf, ein
gespeicherter erst, wenn seine Prüfung gelungen ist: Der Abschluss übernimmt nie ungeprüfte Werte.
Der Abschluss ist eine Transaktion unter einem Advisory-Lock und gelingt nur, wenn jeder
Pflichtschritt und jeder gespeicherte optionale Schritt geprüft ist. Ein geprüfter E-Mail-Versand geht
mit den Regeln der Mail-Verwaltung in deren Einstellungen über und schaltet den Versand ein; ohne ihn
bleibt der Versand aus. Danach antwortet
jede Operation der Installations-API `404`, auf jeder Instanz. Begründung für `404`: Der Endpunkt
verrät nach dem Abschluss nicht, dass es ihn gibt, wie der MCP-Endpunkt bei geschlossenem Kanal.

### 7. Antwortdatei: Installation ohne Browser

Eine Antwortdatei (JSON) enthält dieselben Angaben wie die Schritte des Assistenten. Sie wird auf
zwei Wegen angenommen, mit derselben Prüfung: über die Installations-API mit dem Token, oder beim
Start aus der Datei unter `OPAA_INSTALLATION_ANSWER_FILE`, wenn die Installation noch nicht
abgeschlossen ist. In der Datei stehen Geheimnisse (Passwort, Modellschlüssel) nie im Klartext,
sondern als Verweis auf eine Datei, damit sie aus Secrets kommen; über die API kommen sie im Rumpf
der Anfrage wie in den einzelnen Schritten. Das erste Konto aus der Antwortdatei muss sein Passwort
bei der ersten Anmeldung wechseln, solange die Datei nicht `requirePasswordChange: false` sagt; so
gilt das Passwort, das in einem Secret des Clusters liegen bleibt, nach der ersten Anmeldung nicht
mehr. Helm-Chart, Compose, e2e-Stack und Erprobungsaufbauten installieren über die Antwortdatei. Das
Profil `dev` richtet sich selbst ein, mit Chat- und Embedding-Modell aus festen Vorgaben der
Entwicklung; es hat keine lokale Anmeldung, und Ausnahmen für es im Prüfcode der Installation wären
eine Hintertür. Begründung: GitOps und CI klicken keinen
Assistenten; ein einziger Prüfweg verhindert, dass Browser- und Dateiinstallation auseinanderlaufen.
Ein Dateiverweis über die API ließe jeden Inhaber des Tokens Dateien des Backends lesen und an eine
Modelladresse seiner Wahl senden.

### 8. Erstes Systemverwaltungskonto aus dem Assistenten

Das im Assistenten angelegte Konto ist das lokale Notanker-Konto aus ADR-0033, Entscheidung 5
(`is_bootstrap`, lokaler Anbieter), mit der dort eingegebenen Adresse und dem dort gesetzten Passwort.
`OPAA_INITIAL_ADMIN_EMAIL` und `OPAA_INITIAL_ADMIN_PASSWORD` entfallen. `OPAA_LOCAL_ADMIN_RESET=force`
bleibt der Wiederanlauf des Betriebs: Er setzt das Notanker-Konto zurück, und eine eingerichtete
Installation ohne ein solches Konto bekommt es damit angelegt, unter der Adresse aus
`OPAA_LOCAL_ADMIN_RESET_EMAIL` (dieselben Regeln wie im Assistenten), mit einem einmalig ins Log
geschriebenen Passwort und erzwungenem Wechsel. Ein zweites Notanker-Konto entsteht nie; der Start
einer eingerichteten Installation ohne eines warnt und nennt diesen Weg. Begründung: Eine Installation,
deren Notanker-Konto gelöscht wurde, hätte sonst keinen Weg zurück, wenn ihr Identitätsanbieter
ausfällt.

### 9. Anmeldeverfahren aus dem Assistenten

Der Assistent legt das Anmeldeverfahren fest: nur lokale Konten, oder zusätzlich ein
OIDC-Identitätsanbieter mit Verbindungstest als Standardanbieter. Der Abschluss schaltet in beiden
Fällen die lokalen Konten ein (Schalter aus ADR-0033, Entscheidung 4), über
`OidcProviderService#enableLocalAccountsForInstallation`, auditiert als `LOCAL_ACCOUNTS_ENABLED`
unter dem Systemprozess der Installation. Damit gilt die Voreinstellung „Passwort vergessen“ an; der
Rücksetzweg ist verfügbar, sobald `OPAA_PUBLIC_BASE_URL` und der Mailversand eingerichtet sind, auch
für das Notanker-Konto über dessen Postfach — deshalb ein Funktionspostfach, und den Rücksetzweg
bei Bedarf in der Verwaltung abschalten. `OPAA_OIDC_ISSUER_URI`,
`OPAA_OIDC_CLIENT_ID`, `OPAA_OIDC_JWK_SET_URI`, `OPAA_OIDC_AUTHORITY` und `OPAA_OIDC_BOOTSTRAP`
entfallen, ebenso die Erlaubnis ihrer Hosts in der Adressprüfung; deren Allowlist ist allein
`OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST`. Ein Eintrag `host:port` gibt nur diesen Port frei, ein
Eintrag ohne Port jeden; das Helm-Chart trägt Host und Port des Anbieters aus der Antwortdatei ein,
damit ein interner Host wie `localhost` nicht mit allen Ports, etwa dem der Verwaltungsschnittstelle,
erreichbar wird.

### 10. Modelle in der Datenbank, Vektortabelle nach der Wahl des Embedding-Modells

Chat-, Embedding-, Rerank- und Transkriptionsmodell sind Einträge der Modellverwaltung; ihre Adressen,
Schlüssel und Modellnamen kommen nicht mehr aus der Konfiguration, ihre Timeouts und Lastgrenzen
schon. `vector_store` entsteht nicht in der Migration, sondern beim Abschluss der Installation,
unter dem Advisory-Lock aus Entscheidung 6 und mit der Dimension, die die Probeeinbettung des
gewählten Modells liefert. `OPAA_PGVECTOR_DIMENSIONS` entfällt. Die knowledge-Baseline legt
`vector_store` weiterhin an, weil ein ausgeführtes Skript unverändert bleibt; eine spätere Migration
entfernt die leere Tabelle auf einer Datenbank, auf der noch keine Instanz gestartet ist. Eine
Übernahme des Embedding-Modells aus der Umgebung gibt es nicht: Das Update von `v0.1.0-rc.4` setzt die
Datenbank ohnehin neu auf (ADR-0056), jede Installation danach wählt ihr Modell in der Installation.
Nur das Profil `dev` richtet sein Embedding-Modell selbst ein (Entscheidung 7). Nach dem Abschluss
wechselt die Systemverwaltung das Embedding-Modell in der Oberfläche, mit Probeeinbettung, Vorschau
und Neueinbettung; einen Endpunkt desselben Modells ändert sie ohne Neueinbettung
([ADR-0061](0061-wechsel-des-embedding-modells.md)).

Jede Modelladresse, die Installation oder Modellverwaltung speichern, testen oder nach Modellen
abfragen, prüft eine eigene Adressprüfung der Modelle mit eigenem Schalter und eigener Allowlist
(`OPAA_MODEL_TARGET_VALIDATION_ENABLED`, `OPAA_MODEL_TARGET_VALIDATION_ALLOWLIST`, Einträge `host`
oder `host:port`), eine Grenze des Betriebs nach Entscheidung 2, für alle vier Rollen gleich, das
Chat-Modell eingeschlossen. Sie ist von der Zielprüfung der Quellen getrennt: Ein Modell-Host wird
kein erlaubtes Ziel der Konnektoren, ein Quell-Host kein erlaubtes Modell. In der Modellverwaltung
bleibt eine gespeicherte Adresse unverändert speicher- und testbar; die Installation prüft jede.
Aufrufe zur Laufzeit prüft sie nicht, damit eine enger gezogene Liste kein laufendes Modell
abschaltet; nennt nur die Liste der Quellen den Host eines gespeicherten Modells, warnt der Start.
Begründung: Wer Bibliotheken anbinden darf, soll den internen Modellserver nicht als Quelle lesen
können, nur weil der Betrieb ihn für die Modelle freigegeben hat.

Den Mailserver des E-Mail-Versands prüft die Installation beim Speichern und vor jeder Prüfung gegen
eine eigene Allowlist (`OPAA_INSTALLATION_MAIL_TARGET_ALLOWLIST`, Einträge `host` oder `host:port`),
eine Grenze des Betriebs nach Entscheidung 2. Die Prüfung baut eine Verbindung auf und meldet sich
an, sendet aber nichts; eine Testnachricht geht nur an eine Adresse, die im Schritt angegeben wird,
und ist je Instanz begrenzt. Die Mail-Verwaltung prüft Mailserver-Adressen weiterhin nicht.
Begründung: In der Verwaltung trägt nur die Systemverwaltung den Mailserver ein, und ein interner
Server ist der Regelfall. In der Installation ist das Token die einzige Hürde; ohne Prüfung reichte
der Verbindungsaufbau weiter ins interne Netz als der Modellabruf, und eine Testnachricht an eine
frei wählbare Adresse machte ein internes Relais von außen nutzbar. Ein interner Mailserver braucht
deshalb dieselbe Freigabe des Betriebs wie ein internes Modell, oder er wird nach der Installation in
der Verwaltung eingetragen.

### 11. Jede Änderung einer Einstellung ist nachweisbar

Jede Änderung einer fachlichen Einstellung über Oberfläche, REST-API oder Installation erzeugt einen
Eintrag im Revisionsprotokoll mit vorherigem und neuem Wert; eine Änderung auf denselben Wert
erzeugt keinen. Geheimnisse erscheinen nur als „gesetzt“, „geändert“ oder „entfernt“. Freitexte der
Verwaltung — Betreff und Text der Mail-Vorlagen, der Einleitungstext für externe KI-Werkzeuge —
erscheinen nur als SHA-256 und Länge vorher und nachher, nie im Wortlaut: Belegt ist, ob und wann
sich ein Text änderte und ob er zu einer früheren Fassung zurückkehrte, ohne dass das Protokoll
Freitexte für die Dauer seiner Frist aufbewahrt. Der Abschluss der Installation ist ein eigenes
Ereignis.

### 12. Export und Import der Einstellungen

Die Systemverwaltung exportiert alle fachlichen Einstellungen als Datei ohne Geheimnisse und
importiert sie in eine andere Installation; der Import prüft jeden Wert wie die Oberfläche und
gegen die Grenzen des Betriebs (Entscheidung 2) und ist ein Revisionsereignis. Begründung: Liegen
die Einstellungen in der Datenbank, lassen sich Test und Produktion nicht mehr über die
Konfiguration vergleichen.

Übertragen werden auch je Quellart die Sperre und „Nur über Zugänge“, abgeglichen über den
Schlüssel der Quellart. Beides enthält kein Geheimnis und geht durch die
Schreibwege der Verwaltung. Weil Zugänge nicht übertragen werden, lehnt der Import „Nur über
Zugänge“ ab, wenn in der Zielinstallation kein Zugang die Quellart übernehmen könnte.

Nicht übertragen werden Bestände, die die Installation mit ihren eigenen Diensten verbinden oder
festlegen, wer sich mit welcher Rolle anmeldet:

- **Chat-, Rerank- und Transkriptionsmodell**: Ihre Adresse empfängt Chats und Spracheingaben, ein
  Import lenkte diese Daten an ein neues Ziel. Der Schlüssel folgt keiner neuen Adresse, ein
  übertragenes Modell stünde ohne Schlüssel da. Chat-Modelle haben keinen eindeutigen Namen, über
  den sich abgleichen ließe. Das Embedding-Modell bleibt nach Entscheidung 10 außen vor.
- **Identitätsanbieter und der Schalter der lokalen Konten**: Sie bilden das Anmeldeverfahren. Ein
  Anbieter ordnet Werte seiner Claims den Systemrollen zu; ein Import könnte damit den Kreis der
  Systemverwaltung erweitern. Ein ausgeschalteter Anbieter, ein anderer Standardanbieter oder
  abgeschaltete lokale Konten könnten die Systemverwaltung aussperren. Ein Geheimnis haben
  Identitätsanbieter nicht; der Grund ist die Anmeldung.
- **Verzeichniszugänge**: Sie brauchen das Geheimnis eines Dienstkontos und hängen an einem
  Identitätsanbieter, der selbst nicht übertragen wird.
- **Zugänge**: App-Registrierung und Geheimnis gehören zur Installation, deren Adresse in die
  Rücksprungadresse eingeht. Eine geänderte Adresse, ein anderer Mandant oder andere Scopes
  verwerfen alle Geheimnisse und beenden die verbundenen Konten aller Personen.
- **Konnektor-Freigabe** (die Anlegerechte je Quellart und Zugang): Sie verweist auf Gruppen und
  Zugänge dieser Installation, die es in einer anderen so nicht gibt.
- **Bilder des Brandings**: Sie dürfen größer sein als eine Einstellungsdatei und werden in der
  Verwaltung hochgeladen.

Ein Zugang protokolliert Client-ID, Mandant und Scopes mit ihren Werten vorher und nachher; sie
sind keine Geheimnisse (Entscheidung 11). Von den Konnektor-Vorgaben nennt das Protokoll nur die
gesetzten und die geänderten Schlüssel, nie ihre Werte: Eine Vorgabe kann eine Person nennen, etwa
das Konto, in dessen Namen Google Drive gelesen wird.

### 13. Eine entfallene Lösch- oder Aufbewahrungsvariable bremst nur

Wandert eine Lösch- oder Aufbewahrungsfrist aus der Konfiguration in die Datenbank, wird ihre
entfallene Variable nie als Wert übernommen (Entscheidung 1). Solange die Systemverwaltung noch keinen
eigenen Wert gespeichert hat und eine laufende Instanz die Variable, unter Umgebungs- oder
Property-Namen, auf eine längere Frist setzt, die Löschung abschaltet oder einen Wert setzt, den die
frühere Bindung nicht lesen würde, hält der Löschschritt an, den die Frist steuert; Umkehrbares wie
das Archivieren läuft weiter. Gelesen wird der Altwert mit derselben Umwandlung wie früher. Jede
Instanz meldet ihre gesetzten Altwerte beim Start ins Instanzregister
(`backend_instances.retired_settings`), und jeder Lauf fragt alle lebenden Instanzen: Eine einzige
Instanz mit der Variable hält damit die Läufe aller Instanzen an, gleich wo die Sperre des Laufs
liegt. Ein gespeicherter Wert, auch die Werkseinstellung, oder die auf allen lebenden Instanzen
entfernte Variable beendet die Pause; der Lauf meldet jede Pause als Fehler im Log, die Verwaltung
zeigt sie an. Begründung: Ein Update darf nicht unwiderruflich früher löschen, als der Betrieb
eingestellt hatte, und doch bleibt die Datenbank die einzige Quelle des Werts.

## Konsequenzen

- Die Konfiguration schrumpft auf Start, Infrastruktur, Schlüssel, Grenzen, Lastgrenzen und
  Experten-Feinabstimmung; 48 Variablen entfallen oder wandern in die Datenbank.
- Eine Rücksicherung der Datenbank rollt Einstellungen mit zurück; das Handbuch-Kapitel zu Sicherung
  und Wiederherstellung nennt die Prüfschritte.
- Eine Instanz mit leerer Datenbank ist erst nach der Installation nutzbar; automatisierte Aufbauten
  brauchen die Antwortdatei.
- Wer das Installations-Token aus dem Log liest, kann die Installation abschließen; Log-Zugriff ist
  damit so schutzwürdig wie heute beim Einmalpasswort.
- Rerank- und Transkriptionsmodell pflegt die Systemverwaltung in eigenen Reitern der Modellverwaltung.
  Ihre früheren Variablen übernimmt eine Installation von davor nicht (Entscheidung 1); der Start und
  der Reiter der Rolle nennen sie, solange sie gesetzt sind.

## Verworfene Alternativen

- Vorgaben aus der Konfiguration, die die Datenbank überschreibt — zwei Orte für einen Wert, genau
  das Problem dieses ADR.
- Eine entfallene Fristvariable beim ersten Start in die Datenbank übernehmen — wäre ein Startwert
  aus der Konfiguration (Entscheidung 1); stattdessen bremst sie nur (Entscheidung 13).
- Installations-API ohne Token, abgesichert nur über Netzbereiche — eine Fehlkonfiguration des Netzes
  gäbe die Installation frei.
- Antwortdatei nur über die API — ein Helm-Hook oder Job müsste das Token kennen und den Zeitpunkt
  abpassen; die Datei beim Start ist deklarativ.
- Grenzen des Betriebs in die Oberfläche holen — hebt die Trennung zwischen Betrieb und
  Systemverwaltung auf (Entscheidung 2).
- Mailserver im Assistenten nur über ein Budget begrenzen, ohne Adressprüfung — jede Adresse und jeder
  Port im internen Netz bliebe mit dem Token erreichbar, nur langsamer (Entscheidung 10).
- Mailserver gegen die Allowlist der Modelle prüfen — machte jeden freigegebenen Modellserver zum
  erlaubten Mailserver und umgekehrt (Entscheidung 10).
- Modelladressen gegen die Allowlist der Quellen prüfen — macht jeden freigegebenen Modellserver zum
  erlaubten Ziel der Konnektoren (Entscheidung 10).
- Übergang, in dem die Allowlist der Quellen Modelle eine Zeit lang weiter freigibt — keine
  veröffentlichte Version prüfte Modelle gegen sie, und eine gespeicherte Adresse bleibt ohnehin
  nutzbar; Startwarnung und Upgrade-Notiz genügen.
- `410 Gone` nach dem Abschluss — bestätigt die Existenz des Endpunkts ohne Nutzen für den Betrieb.

## Offen

- Ob die Experten-Feinabstimmung (Entscheidung 3) später mit Messung und Leitplanken in die
  Verwaltung wandert.
