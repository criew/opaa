# Netz und Sicherheit

Dieses Kapitel beschreibt, wie Anfragen eine Installation erreichen, was eine erreichbare
Installation zwingend braucht, welche Ziele das Backend selbst abrufen darf, was ein Betrieb ohne
Internet voraussetzt, wie aufgenommene Dateien auf Schadprogramme geprüft werden und wie OPAA
Schlüssel und Zugangsdaten schützt. Es gilt für jeden Betriebsweg
und gehört zum Teil Betrieb des [Handbuchs](../README.md); die Bausteine einer Installation
beschreibt die [Betriebsübersicht](README.md).

## Netzwerkzugang

Das Backend wird nie direkt angesprochen, sondern über das Frontend. Davor steht in jedem
erreichbaren Betrieb ein **TLS-terminierender Reverse-Proxy** oder Ingress. Für ihn gilt:

- **`X-Forwarded-Proto` selbst setzen**, nie vom Client übernehmen; ein gefälschtes `https` umginge
  die CORS-Prüfung.
- **`X-Forwarded-For` setzen oder anhängen, `Forwarded` verwerfen**
  ([Vertraute Proxys](#vertraute-proxys-und-client-adresse)).
- **`/`, `/api/` und `/mcp` an das Frontend reichen**; Antworten auf `/mcp` nicht puffern.
- **Den Download der Selbstauskunft nicht puffern** (`/api/v1/me/self-disclosure/download`): Das
  Backend zählt eine Auskunft erst, wenn ihr letzter Block abgeflossen ist. Der Frontend-Container
  leitet ohne Puffer weiter; bei ingress-nginx gehört `proxy-buffering: "off"` dazu.
- **Die Frame-Header des Downloads nicht ersetzen:** Für `/api/v1/me/self-disclosure/download`
  setzt der Proxy kein eigenes `X-Frame-Options` und keine eigene `Content-Security-Policy` mit
  `frame-ancestors` und überschreibt die des Frontends nicht. Die Seite lädt den Download in einen
  eigenen verborgenen Frame; verbietet ein Header das, bricht etwa Firefox jeden Download ab, nachdem
  OPAA ihn schon begonnen hat. Jeder Versuch verbraucht dann einen Start und löst Benachrichtigung
  und Mail aus, bis die Starts des Tages erschöpft sind.
- **Timeout** für `/api/` und `/mcp` mindestens `OPAA_BACKEND_READ_TIMEOUT`, sonst `504` bei langen
  Antworten.
- **Callback-Seiten** `/auth/callback` und `/connections/callback` tragen einmal gültige Codes:
  weder protokollieren noch verändern.
- OPAA läuft nur im Wurzelpfad. Eine öffentliche Adresse mit Nicht-Standardport gehört in
  `OPAA_CORS_ALLOWED_ORIGINS`.
- **`Strict-Transport-Security` setzt der äußere Proxy**, wo TLS endet.

### Sicherheits-Header und `Strict-Transport-Security`

Der Frontend-Container setzt auf jede Antwort `Content-Security-Policy`, `X-Content-Type-Options`,
`X-Frame-Options`, `Referrer-Policy` und `Permissions-Policy` (Mikrofon nur für die eigene Herkunft,
Kamera und Standort gesperrt). Die CSP lässt keine fremden Skripte oder CDNs zu und Verbindungen nur
zur eigenen Herkunft. Einbetten in einen Frame verbieten beide Header überall, mit einer Ausnahme: Den
Download der Selbstauskunft darf die Anwendung selbst in einen verborgenen Frame laden
(`SAMEORIGIN` bzw. `frame-ancestors 'self'`), damit eine Ablehnung die Seite nicht ersetzt. Das
Backend sendet dieselbe Regel selbst: `SAMEORIGIN` und `frame-ancestors 'self'` für den Download,
`X-Frame-Options: DENY` für alles andere. Der Container ersetzt die Frame-Header des Backends durch
seine eigenen, damit jeder Header genau einmal ankommt. Ein
Identitätsanbieter auf fremdem Origin kommt über
`OPAA_CSP_CONNECT_SRC_EXTRA` dazu
([Authentifizierung](authentifizierung.md#content-security-policy-und-ein-anbieter-auf-fremdem-origin)).
HSTS setzt der Container nicht; das gehört an den äußeren Proxy.

### Erlaubte Linkziele in Antworten

Ein präpariertes Dokument kann das Chat-Modell dazu bringen, einen Link auszugeben, in dessen
Adresse Inhalte aus Dokumenten oder dem Gesprächsverlauf stecken. Ein Klick darauf genügt, und die
Inhalte liegen beim fremden Server. Die **erlaubten Linkziele** begrenzen das: Ein Link in einer
Antwort oder in der Dokumentvorschau ist nur zur eigenen Adresse der Anwendung und zu den
eingetragenen Zielen klickbar, so bleiben Links ins Intranet oder auf ein Fachportal nutzbar.

- **Was die Person sieht:** Ein Link zu einem anderen Ziel erscheint als Text, hinter dem Linktext
  steht die vollständige Adresse in Klammern, damit kein Linktext ein Ziel verbirgt. Dasselbe gilt
  für `mailto:` und jedes andere Schema. Verglichen wird der Ursprung genau: Eine Subdomain oder
  eine längere Domain mit demselben Anfang ist ein anderes Ziel, ebenso `http` statt `https`.
- **Bilder** lädt die Oberfläche in Antworten und Vorschau nie, auch nicht von der eigenen Adresse;
  an ihrer Stelle steht „[Bild: …]“ mit der Adresse. Unabhängig davon blockiert die
  `Content-Security-Policy` fremde Bilder.
- **Bedienung:** Verwaltung › Anbindungen › Einstellungen, Abschnitt „Erlaubte Linkziele“. Jedes
  Hinzufügen und Entfernen speichert die ganze Liste sofort; eine abgewiesene Eingabe zeigt die
  Meldung und bleibt im Feld stehen.

- **Vorgabe:** leer. Dann ist nur die eigene Adresse der Anwendung klickbar.
- **Format:** je Eintrag ein Ursprung aus Schema, Host und optional Port, etwa
  `https://intranet.stadt.example` oder `https://portal.stadt.example:8443`. Erlaubt sind nur `https`
  und `http`; Pfad, Abfrage, Anker, Platzhalter wie `*` und Anmeldedaten in der Adresse werden mit
  einer Meldung abgewiesen, ebenso IPv6-Adressen und Ports außerhalb 1 bis 65535. Ein Host mit
  Umlauten wird in seiner `xn--`-Form eingetragen, so wie der Browser ihn meldet; Eingaben mit
  anderen als ASCII-Zeichen werden abgewiesen. Der Host wird kleingeschrieben und der Standardport
  weggelassen, doppelte Einträge fallen zusammen. Die Zahl der Einträge ist begrenzt (höchstens 50).
- **Wer:** Lesen darf jede angemeldete Person, weil die Oberfläche die Liste zum Darstellen braucht;
  ändern nur die Systemverwaltung.
- Eine Änderung wirkt ohne Neustart auf allen Instanzen und steht im Revisionsprotokoll
  ([Nachvollziehbarkeit](nachvollziehbarkeit.md)) mit der Liste vorher und nachher.

## Härtung für erreichbare Deployments

Ist die Installation aus einem größeren Netz oder dem Internet erreichbar, gilt:

| Maßnahme | Zwingend | Wo beschrieben |
|---|---|---|
| TLS am äußeren Proxy; `X-Forwarded-Proto` autoritativ | ja | [Netzwerkzugang](#netzwerkzugang) |
| Betriebsmodus `oidc`, nie `dev` | ja | [Authentifizierung](authentifizierung.md#entwicklungsmodus-dev) |
| eigene Geheimnisse statt Vorgaben, `OPAA_AUTH_LOCAL_COOKIE_SECURE` nicht auf `false` | ja | [Schlüssel und Geheimnisse](#schlüssel-und-geheimnisse) |
| vertraute Proxys eintragen | ja, sobald ein Proxy vor dem Backend steht | [Vertraute Proxys](#vertraute-proxys-und-client-adresse) |
| mitgelieferten Keycloak-Realm härten | ja, wenn er genutzt wird | [Authentifizierung](authentifizierung.md#härtung-des-mitgelieferten-keycloak) |
| Ports nur an `127.0.0.1`, Zugangsdaten des mitgelieferten Objektspeichers ersetzen | ja für Keycloak und den Objektspeicher, sonst empfohlen | [Docker Compose](docker-compose.md#härtung-des-compose-stapels) |
| Netztrennung, Pod Security | empfohlen | [Kubernetes](kubernetes.md#netz-und-sicherheit) |
| Ziele des Servers begrenzen | Vorgabe ist eingeschaltet | [Zielprüfung](#zielprüfung-abrufe-des-servers) |

### Vertraute Proxys und Client-Adresse

Rate-Limits und die Netzbeschränkung lokaler Systemverwalter (`OPAA_LOCAL_ADMIN_ALLOWED_CIDRS`)
richten sich nach der Client-Adresse. Hinter einem Proxy — der Frontend-nginx ist schon einer — ist
die Verbindungsadresse die des Proxys. `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` nennt die Netze, deren
`X-Forwarded-For` zählt; **Pflicht, sobald ein Proxy vor dem Backend steht**.

- **Ohne Wert** teilen sich alle Clients einen Zähler, und die Netzbeschränkung sieht nur den Proxy.
  Der Start warnt im Log.
- **So eng wie möglich:** unter Compose die feste Adresse des Frontend-Containers als `/32`, nie das
  ganze Compose-Netz; jeder weitere Proxy mit seiner eigenen Adresse. Bereiche weiter als `/8` lehnt
  der Start ab.
- **Prüfen** mit `GET /api/v1/admin/diagnostics/client-address`
  ([Diagnose ohne Shell](ueberwachung-und-fehlerbehebung.md#diagnose-ohne-shell)).

### Zielprüfung: Abrufe des Servers

Wer eine Bibliothek mit einer Netzquelle anlegt, bestimmt Adressen, die der Server abruft — auch
solche, die nur aus dem Netz des Servers erreichbar sind (SSRF). Zwei Schranken begrenzen das:

- **Pfad-Allowlist** (`OPAA_INDEXING_FILESYSTEM_ALLOWLIST`) für Dateisystem-Bibliotheken.
- **Zielprüfung** für jede Netzquelle, standardmäßig an: Loopback, Link-Local samt
  `169.254.169.254`, private und reservierte Bereiche werden abgelehnt, geprüft nach der
  DNS-Auflösung und nach jedem Redirect. Interne Quellen kommen in
  `OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST`, statt die Prüfung abzuschalten. Identitätsanbieter
  und Originalablage haben eigene Schalter derselben Prüfung.
- Nur `http` und `https`; Redirects nur auf demselben Origin, nie auf `http` herab. Zertifikate
  werden immer geprüft.
- **Grenze:** DNS-Rebinding wird nicht erkannt. Wer das ausschließen muss, beschränkt den Egress des
  Backends zusätzlich auf Netzebene.

## Ausgangsproxy

Verlangt das Haus einen Proxy, trägt die Systemverwaltung ihn unter **Administration → Anbindungen →
Einstellungen**, Abschnitt **Ausgangsproxy** ein. Es gibt genau einen; `HTTP_PROXY`/`HTTPS_PROXY`
und `http.proxyHost` wertet OPAA nicht aus.

- **Über den Proxy:** jede HTTP(S)-Verbindung des Backends — Quellen, Zugänge, Modelldienste,
  Identitätsanbieter, Verzeichnisabgleich.
- **Nie über den Proxy:** Loopback, Datenbank, Mailserver, SMB-Freigaben, Objektspeicher der
  Originalablage.
- **Format:** `http://host:port` oder `host:port`. Proxy über HTTPS, SOCKS und Proxy-Anmeldung
  werden nicht unterstützt.
- **Ausnahmeliste:** je Zeile ein Hostname (gilt mit Subdomänen), eine IP-Adresse oder ein
  CIDR-Bereich. Interne Ziele wie ein Modellserver im Haus gehören hierher.
- **Ausnahmeliste ist keine Freigabe:** Ob ein internes Ziel erreicht werden darf, entscheidet die
  [Zielprüfung](#zielprüfung-abrufe-des-servers). Hinter dem Proxy löst der Proxy die Adresse auf;
  interne Ziele sperrt dann der Proxy.
- Änderungen wirken ohne Neustart und stehen im Revisionsprotokoll.
- **Notanker:** Ein falscher Proxy kann die Anmeldung über Identitätsanbieter lahmlegen; das lokale
  Systemverwaltungskonto meldet sich ohne Anbieter an und korrigiert den Eintrag.

## Betrieb ohne Internet

OPAA ist dafür gebaut, in einem Netz ohne Internetzugang zu laufen, wenn alles, was es braucht, im
Haus steht: Anmeldung, Aufnahme von Dokumenten, Fragen mit Antwort und Fundstelle. Die Oberfläche
lädt Schriften, Skripte und Bilder von der eigenen Installation. Die Nutzungsstatistik der
eingebauten Bibliothek für Datenbankmigrationen (Liquibase) ist abgeschaltet. Was davon belegt ist,
nennen Nachweis und Grenzen unten.

**Voraussetzungen:**

- **Images vorab bereitstellen:** Backend, Frontend und Datenbank (unter Kubernetes dazu die Images
  des Charts) in eine interne Registry übernehmen oder auf den Hosts laden. Ein Start ohne
  Internet darf kein Image mehr nachladen.
- **Modelle im Haus:** Chat- und Embedding-Modell, bei Bedarf Reranking und Transkription, laufen
  auf einem Modellserver im Netz der Installation ([Modelle](modelle.md)); seine Modellgewichte
  werden vorab dort abgelegt.
- **Anmeldung im Haus:** ein Identitätsanbieter im eigenen Netz oder die lokale Anmeldung
  ([Authentifizierung](authentifizierung.md)).
- **Quellen im Haus:** Bibliotheken lesen nur Quellen, die im Netz erreichbar sind; der Upload
  braucht keine.

**Nachweis:** Ein nächtlicher Lauf in der CI des Projekts (Workflow „Betrieb ohne Internet“) startet
die Compose-Installation in einem Netz ohne Weg nach außen. Alle Images liegen vor dem Start bereit;
das Modell ist ein OpenAI-kompatibler Dienst im selben Netz. Der Lauf meldet sich lokal an, legt eine
Bibliothek an, nimmt ein Dokument auf und stellt eine Frage, deren Antwort das Dokument als
Fundstelle nennt. Jede Namensauflösung außerhalb der Installation und jeder Abruf des Browsers von
einer fremden Adresse lässt ihn scheitern; ein absichtlicher Abruf einer fremden Adresse belegt,
dass diese Erkennung greift. Das Ergebnis steht mit Version und Datum in der Zusammenfassung des
Laufs.

**Grenzen des Nachweises:**

- **Umfang:** die Compose-Installation mit lokaler Anmeldung und dem Upload eines Textdokuments, in
  einem Lauf von rund einer Minute. Andere Formate, Reranking, Transkription und zeitgesteuerte
  Aufgaben sind nicht Teil des Laufs, ebenso Konnektoren zu Quellen, Identitätsanbieter,
  Mailversand und der Betrieb unter Kubernetes.
- **Modelle:** Der Modelldienst ist ein Platzhalter mit festen Antworten, kein Modellserver; ob ein
  Modellserver selbst ohne Internet startet, gehört zu dessen Betrieb.
- **Erkennung:** Verbindungen an feste IP-Adressen ohne Namensauflösung scheitern im Netz ebenfalls.
  Der Lauf erkennt sie aber nur, wenn das Log sie als nicht erreichbares Netz meldet; ein Fehler,
  der nicht oder nur auf niedriger Stufe protokolliert wird, bleibt unbemerkt.

## Virenprüfung der aufgenommenen Dateien

OPAA kann jede Datei, die eine Bibliothek aufnehmen soll, vorher an einen Virenscanner geben: einen
Upload, die Datei einer Quelle und jeden Anhang. Erst nach dem Befund „sauber“ wird sie gelesen,
geschnitten und, beim Upload, in der Originalablage abgelegt.

> **Beispiel.** Das Landratsamt betreibt für seine Fachverfahren einen ClamAV-Dienst im eigenen Netz.
> Der Betrieb trägt dessen Adresse ein und schaltet die Prüfung ein. Lädt eine Sachbearbeiterin eine
> befallene Datei hoch, lehnt OPAA den Upload mit dem Namen der Signatur ab; die Datei landet weder in
> der Originalablage noch im Index.

- **Scanner:** ein clamd-Dienst (ClamAV), angesprochen über TCP mit dem Befehl `INSTREAM`. OPAA
  bringt keinen Scanner mit; der Dienst der Behörde oder ein eigener Container neben dem Backend wird
  eingetragen. Die Verbindung geht direkt, nie über den Ausgangsproxy, und trägt nur den Inhalt der
  Datei, weder Namen noch Bibliothek noch Person.
- **Ab Werk aus.** Eingeschaltet ohne Adresse bricht der Start ab.
- **Befund:** Ein Upload wird mit Meldung und Signatur abgewiesen. Bei einer Quelle steht das
  Dokument mit dem Grund in der Bibliothek, ohne Inhalt im Index, und das Laufprotokoll führt es als
  abgewiesen. Das Original ist gesperrt: „Original öffnen“ liefert es nicht aus, auch keinen Anhang
  daraus, ebenso wenig bei einem Upload, den eine spätere Neuindizierung mit neuen Signaturen als
  befallen erkennt. Dieselbe unveränderte Datei wird weder erneut geprüft noch erneut protokolliert;
  erst eine geänderte Datei wird wieder geprüft. Das Dokument zählt weiter zum Speicherkontingent
  der Bibliothek, bis es aus der Quelle verschwindet oder gelöscht wird.
- **Revisionsprotokoll:** Jeder Befund steht dort als Systemvorgang mit Bibliothek und Signatur,
  ohne Dateinamen und ohne die Person, die die Datei hochgeladen hat. Bei einer privaten Bibliothek
  nennt der Eintrag statt der Bibliothek nur die Virenprüfung und die Art der Quelle, weil die
  Bibliothek auf ihre Eigentümerin schließen ließe.
- **Scanner nicht erreichbar:** Ein Fehler des Scanners, eine Zeitüberschreitung oder eine Datei über
  seiner Grenze für Datenströme gilt als nicht geprüft. Mit `OPAA_MALWARE_SCAN_ON_UNAVAILABLE` auf
  `REJECT` wird sie nicht aufgenommen: Der Upload antwortet `503`, ein Dokument einer Quelle wird beim
  nächsten Lauf erneut versucht. Mit `ACCEPT` wird sie ungeprüft aufgenommen; das Laufprotokoll
  nennt sie unter „Ohne Virenprüfung aufgenommen“, und beim Start warnt das Log vor dieser
  Einstellung.
- **Frist:** `OPAA_MALWARE_SCAN_TIMEOUT` begrenzt einen Scan insgesamt, vom Verbindungsaufbau bis zur
  Antwort; danach gilt die Datei als nicht geprüft.
- **Grenzen des Scanners:** `StreamMaxLength` von clamd muss die größte Datei fassen, die OPAA
  annimmt; sonst gilt jede größere Datei als nicht geprüft, und das Log nennt diese Grenze als
  Grund. Über `MaxFileSize` und `MaxScanSize` hinaus prüft clamd eine Datei nur teilweise und meldet
  sie dennoch als sauber, solange `AlertExceedsMax` aus ist; verschlüsselte Archive meldet er nur
  mit `AlertEncrypted`. Beides stellt der Betrieb des Scanners ein.
- **Metrik:** `opaa_malware_scans_total` nach Ergebnis (`clean`, `infected`, `failed`).

Nicht gebaut sind eine Prüfung über ICAP, eine erneute Prüfung des Bestands mit neuen Signaturen
und die Prüfung von Seiteninhalten, die eine Quelle als Text liefert (Confluence-Seiten, Einträge
eines Feeds).

| Variable | Standard | Bedeutung |
|---|---|---|
| `OPAA_MALWARE_SCAN_ENABLED` | `false` | Virenprüfung ein |
| `OPAA_MALWARE_SCAN_HOST` | — | Adresse des clamd-Dienstes, ohne Schema |
| `OPAA_MALWARE_SCAN_PORT` | `3310` | Port des clamd-Dienstes |
| `OPAA_MALWARE_SCAN_TIMEOUT` | `30s` | Frist eines Scans insgesamt |
| `OPAA_MALWARE_SCAN_ON_UNAVAILABLE` | `REJECT` | Umgang mit einer nicht geprüften Datei: `REJECT` oder `ACCEPT` |

Unter Kubernetes stehen dieselben Werte unter `malwareScan` im Helm-Chart; eine NetworkPolicy muss
dem Backend den Weg zum Scanner öffnen.

## Schlüssel und Geheimnisse

| Geheimnis | Wofür | Ohne eigenen Wert | Bei Verlust oder Wechsel |
|---|---|---|---|
| `OPAA_AUTH_JWT_SECRET` | lokal ausgestellte Tokens und Links (Refresh, Einladung, Rücksetzen) | im Modus `oidc` bricht der Start ab | alle lokalen Sitzungen, offenen Links und Zugangstokens [externer KI-Werkzeuge](../spaces/fremdzugaenge.md) werden ungültig — gewollt nach einer [Rücksicherung](authentifizierung.md#nacharbeit-nach-einer-rücksicherung-der-datenbank) |
| `OPAA_CREDENTIALS_ENCRYPTION_KEY` | Zugangsdaten von Quellen, Zugängen und verbundenen Konten, Webhook-Geheimnisse | Speichern solcher Zugangsdaten scheitert | gespeicherte Werte unlesbar, neu einzutragen |
| `OPAA_SETTINGS_ENCRYPTION_KEY` | Schlüssel der Chat-Modelle, SMTP-Passwort, wartende Mails | Speichern solcher Werte scheitert | Werte neu einzutragen ([Modelle](modelle.md#verschlüsselung-der-zugangsschlüssel-verwalteter-chat-modelle)); wartende Mails verworfen |
| `OPAA_DB_USERNAME`, `OPAA_DB_PASSWORD` | Datenbankkonto | Vorgabe `opaa`/`opaa`, nur Entwicklung | vor dem ersten Start setzen |

- Zufällig erzeugen: `openssl rand -base64 48` (Signaturgeheimnis), `openssl rand -base64 32`
  (Schlüssel).
- Außerhalb des Repositorys ablegen (Tresor, Secret-Manager) und **getrennt von der Datenbank
  sichern**: Ohne die Schlüssel enthält eine Datenbanksicherung unlesbare Zugangsdaten.
- Die Schlüssel des Profils `dev` sind nicht für den Betrieb.

### Zugangsdaten-Verschlüsselung

Zugangsdaten liegen mit AES-256-GCM verschlüsselt in der Datenbank. Keine API-Antwort und kein Log
enthält sie.

- Ohne Schlüssel scheitert nur das Speichern von Zugangsdaten (`503`).
- Ist ein Wert nicht entschlüsselbar, zeigt die Oberfläche „nicht gesetzt“, das Log eine Warnung;
  andere Änderungen an der Bibliothek lassen den Geheimtext stehen.
- Ändert sich die Adresse der Quelle ohne neue Zugangsdaten, verwirft OPAA die gespeicherten
  Zugangsdaten; sie werden für die neue Adresse neu eingetragen.
- **Bei Schlüsselverlust** sind die Werte verloren, nicht die Bibliothek: Zugangsdaten neu eintragen.
- **Schlüsselwechsel** im Bestand ist nicht vorgesehen; wer wechselt, trägt alle Werte neu ein.

## Weiterführende Kapitel

- [Images und Laufzeit](images-und-laufzeit.md) — was die Container selbst mitbringen: ohne Root,
  Read-only Filesystem, Probes
- [Sicherung und Wiederherstellung](sicherung-und-wiederherstellung.md) — die Schlüssel getrennt von
  der Datenbank sichern
- [Docker Compose](docker-compose.md#härtung-des-compose-stapels) und
  [Kubernetes](kubernetes.md#netz-und-sicherheit) — Härtung je Betriebsweg
