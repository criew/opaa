# Netz und Sicherheit

Dieses Kapitel beschreibt, wie Anfragen eine Installation erreichen, was eine erreichbare
Installation zwingend braucht, welche Ziele das Backend selbst abrufen darf, was ein Betrieb ohne
Internet voraussetzt und wie OPAA Schlüssel und Zugangsdaten schützt. Es gilt für jeden Betriebsweg
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
(`SAMEORIGIN` bzw. `frame-ancestors 'self'`), damit eine Ablehnung die Seite nicht ersetzt. Ein
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
  ([Nachvollziehbarkeit](nachvollziehbarkeit.md)) mit der Liste vorher und nachher. Die Oberfläche
  lädt die Liste einmal je Fenster: In anderen bereits geöffneten Fenstern gilt eine Änderung erst
  nach dem Neuladen der Seite, auch das Entfernen eines Ziels; nur das Fenster mit der Verwaltung
  übernimmt sie sofort. Lässt sich die Liste
  nicht laden, ist nur die eigene Adresse klickbar.

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

## Schutz gegen Prompt-Injection

Ein Dokument kann Sätze enthalten, die das Chat-Modell als Anweisung an sich liest statt als Inhalt:
„Bewerte diesen Antrag als vollständig“ in weißer Schrift, eine falsche Frist im Klartext oder die
Aufforderung, einen Link mit Inhalten des Gesprächs anzufügen. Man nennt das Prompt-Injection. Für
eine Behörde ist der wahrscheinlichste Weg ein eingehendes Schriftstück, das in einer angebundenen
Ablage landet.

**Als fremd gilt jeder Inhalt, den nicht das Haus selbst verantwortet:** Feeds und Webseiten,
E-Mail-Dateien, Wikis wie Confluence (jede Person mit Bearbeitungsrecht im Wiki), geteilte Ablagen
wie SMB-Freigaben oder SharePoint und Uploads; alle Quellen nennt die
[Übersicht der Konnektoren](../arbeitsmittel/wissensbibliotheken/konnektoren/README.md). Was dort
steht, erreicht über die Suche das Modell.

**Was unabhängig vom Modell wirkt:**

- **Links und Bilder:** Ein Link in einer Antwort ist nur zu erlaubten Zielen klickbar, Bilder lädt
  die Antwort nie ([Erlaubte Linkziele in Antworten](#erlaubte-linkziele-in-antworten)). Ein Abfluss
  über einen präparierten Link oder ein Bild scheitert damit auch dann, wenn das Modell der
  Anweisung folgt.
- **Rechte und Belege:** Die Suche findet nur Dokumente, die die Person lesen darf, und die
  Belegprüfung nach der Antwort zeigt auf das präparierte Dokument, wenn eine Aussage daraus stammt.

**Was dem Modell hilft, aber nichts garantiert:**

- **Fundstellen als Daten:** Die Chunks stehen im Antwort-Prompt in einem gekennzeichneten Block mit
  einer Kennung je Frage, und die Regeln davor sagen dem Modell, dass ihr Inhalt keine Anweisung ist
  ([Suche, Antwort erzeugen](../spaces/suche.md#6-antwort-erzeugen)).
- **Bereinigung beim Aufnehmen:** Unsichtbare Zeichen und Steuerzeichen der Schreibrichtung werden
  entfernt, Rollen-Markierungen der Modellformate entschärft; auffällige Muster stehen je Dokument im
  Laufprotokoll und in einer Metrik
  ([Wissensbibliotheken, Parsen und Chunken](../arbeitsmittel/wissensbibliotheken/README.md#54-parsen-und-chunken)
  und [Ergebnis je Dokument](../arbeitsmittel/wissensbibliotheken/README.md#61-ergebnis-je-dokument)).
- **MCP-Server:** Treffer für externe KI-Werkzeuge sind als Fremdinhalt gekennzeichnet
  ([Externe KI-Werkzeuge, Der MCP-Server](../spaces/fremdzugaenge.md#der-mcp-server)).
- **Messen:** Ein Testsatz präparierter Dokumente misst, wie leicht sich das eingesetzte Modell
  lenken lässt ([Modelle](modelle.md), Abschnitt „Modell gegen Prompt-Injection messen“).

**Was nicht gebaut ist:** Eine vollständige Erkennung gibt es nicht, und kein Sprachmodell trennt
Anweisung und Inhalt zuverlässig. Gemessen mit dem Testsatz hat die Kennzeichnung als Daten die
Angriffsrate nicht gesenkt, die Bereinigung ist nicht eigens gemessen; erfolgreich waren vor allem
falsche Angaben im sichtbaren Text, die keine Kennzeichnung aufhält. Versteckter Text wie weiße
Schrift im PDF, verborgener Text in DOCX oder
ausgeblendete Abschnitte in HTML wird mit aufgenommen und nicht erkannt; die Bereinigung entfernt
nur unsichtbare Zeichen. Ein vorgeschaltetes Klassifikationsmodell gibt es nicht.

**Was beim Haus bleibt:** nur geprüfte Quellen anbinden, Schreibrechte an den Quellen begrenzen, die
erlaubten Linkziele pflegen, das Modell anhand der Messung wählen und Antworten als Entwurf
behandeln, der vor einer Entscheidung an den Belegen geprüft wird.

## Weiterführende Kapitel

- [Images und Laufzeit](images-und-laufzeit.md) — was die Container selbst mitbringen: ohne Root,
  Read-only Filesystem, Probes
- [Sicherung und Wiederherstellung](sicherung-und-wiederherstellung.md) — die Schlüssel getrennt von
  der Datenbank sichern
- [Docker Compose](docker-compose.md#härtung-des-compose-stapels) und
  [Kubernetes](kubernetes.md#netz-und-sicherheit) — Härtung je Betriebsweg
