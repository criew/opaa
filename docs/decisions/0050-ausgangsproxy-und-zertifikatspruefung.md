# ADR-0050: Ausgangsproxy und Zertifikatsprüfung

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ersetzt die Proxy- und TLS-Felder aus
[ADR-0041](0041-verbindungen-als-eigenes-modul.md) (Entscheidung 3a), [ADR-0023](0023-confluence-konnektor.md)
und [ADR-0027](0027-s3-konnektor.md)

## Kontext

Bis #2496 trug jede Bibliothek und jeder Zugang einen eigenen Proxy (`source_proxy`), bis #2537
einen Schalter, der die Zertifikatsprüfung aussetzte (`source_insecure_ssl`). Beides musste jeder
HTTP-Client des Backends selbst umsetzen, galt nur für Quellzugriffe und nicht für OAuth,
Modell-Anbieter, OIDC-Discovery oder den Verzeichnisabgleich, und ein geänderter Proxy konnte ein
Client-Secret sehen. Der Maintainer entschied am 11.10.2026 (Epic #2492), den Proxy einmal hausweit
in der Oberfläche zu pflegen, und am 12.10.2026, die Zertifikatsprüfung für keine Quellart,
Bibliothek oder Zugang aussetzbar zu machen.

## Entscheidung

### 1. Ein hausweiter Ausgangsproxy als JVM-weiter `ProxySelector`

`io.opaa.outbound` (foundation, unter `security`): `OutboundProxyInstaller` setzt beim Start vor
jeder Bean `OutboundProxySelector` als `ProxySelector.setDefault`. Der JDK-`HttpClient` ohne
`.proxy()`, `HttpURLConnection`, RestClient/RestTemplate und OkHttp fragen ihn je Verbindung; kein
Client braucht eigenen Code, Änderungen wirken ohne Neustart. Nur das AWS SDK fragt keinen
`ProxySelector`; der S3-Konnektor übergibt ihm `proxyFor(endpoint)` beim Bau des Clients. Über den
Proxy gehen nur `http` und `https`; jedes andere Schema (`socket://` aus `new Socket()`: SMB, SMTP,
JDBC), Loopback und die Treffer der Ausnahmeliste gehen direkt (`OutboundProxySelectorTest`). Ziele
sind alle HTTP(S)-Ziele des Backends: Konnektoren, OAuth, Dienstkonto-Tokens, Originalabruf,
MCP-Discovery, Modell-Anbieter, OIDC-Discovery/JWKS, Verzeichnisabgleich. Die S3-Originalablage ist
Infrastruktur: `S3SdkClient` setzt immer eine eigene `ProxyConfiguration` ohne System-Properties und
geht direkt. JVM-Proxy-Properties und `HTTP(S)_PROXY` werden nicht gelesen; ohne Eintrag geht alles
direkt.

### 2. Speicher, Verwaltung, Ausnahmeliste

Singleton `outbound_proxy_settings` (foundation, `OutboundProxySetting`); der Selector liest den
Stand beim Start und nach jedem Commit einer Änderung neu, synchronisiert, ein Rollback leitet nie
um (prozesslokaler Zustand, ADR-0021). `GET/PUT /api/v1/admin/outbound-proxy` nur für die
Systemverwaltung (`OutboundProxyAdministration` in connections), jede Änderung ist
`OUTBOUND_PROXY_CHANGED` mit Vorher und Nachher. Keine Proxy-Authentifizierung: Der JDK sperrt
Basic für `CONNECT`, und jede Client-Familie bräuchte einen eigenen Authenticator. Ausnahmeliste
(`ProxyExclusions`) im `NO_PROXY`-Stil: Hostname trifft sich und alle Subdomänen, IP-Literal sich
selbst, CIDR nur IP-Literale und nie über DNS; abgewiesen werden `*`, Ports und Leerzeichen.
`NumericAddress` liegt in `io.opaa.common`, weil `outbound` unter `security` liegt.

### 3. Adressprüfung bleibt beim Ziel

Geprüft wird das Ziel, nie der Proxy. Ein lokal nicht auflösbarer Name ist nur dann kein
Ablehnungsgrund, wenn die Verbindung tatsächlich über den Proxy geht, denn der Proxy löst ihn auf:
in `TargetAddressValidator#validate(URI)`, wenn `proxyFor(uri)` den Proxy liefert (auch für
`OidcAddressPolicy`), und in `validateHost(host, true)`, wenn der Aufrufer die Route seines Clients
kennt (`S3RequestGuard`). `validateHost(host)` bleibt streng für SMB, Bucket-Hosts und die
Originalablage (`UploadS3TargetPolicy`), die nie über den Ausgangsproxy gehen. Ausnahmeliste und
Allowlist (`OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST`) bleiben getrennt: Die eine entscheidet über
den Weg, die andere über die Erreichbarkeit. Akzeptierte Grenze: Welche Adresse der Proxy für einen
Namen wählt, sieht OPAA nicht.

### 4. Kein Proxy je Bibliothek und Zugang

`knowledge_libraries.source_proxy` und `connection_profiles.source_proxy` sind entfernt
(`DropSourceProxyMigrationTest`; der Constraint `chk_knowledge_libraries_upload_without_source` ist
ohne die Spalte neu angelegt). `sourceProxy` entfällt in allen Schemas von Bibliothek,
Verbindungstest, Auflistung und Zugang; `SourceSettings`, `ClientRegistration`,
`ConnectionProfileValues` und die Verbindungs-Records der Konnektoren tragen keinen Proxy
(`BasicCredentials`), `SourceHttpClientFactory` baut jeden Client ohne eigenen Proxy. Ein geänderter
Ausgangsproxy verlangt kein neues Client-Secret, weil die Endpunkte eines Zugangs `https://` sind
(ADR-0048, Entscheidung 8). Fingerabdruck des Abgleichstands (ADR-0045, Entscheidung 8) und Bindung
der Geheimnisse nennen den Proxy nicht.

### 5. Zertifikatsprüfung nicht aussetzbar

`knowledge_libraries.source_insecure_ssl` und `connection_profiles.source_insecure_ssl` sind
entfernt (`DropSourceInsecureSslMigrationTest`); `sourceInsecureSsl` entfällt in allen Schemas und
Formularen. `SourceHttpClientFactory.buildHttpClient()` baut jeden Client mit dem Truststore der JVM
ohne eigenen Trust-Manager, `S3SdkClient` ohne `TRUST_ALL_CERTIFICATES`; `SourceSettings`,
`S3ClientSettings`, `UrlIndexingRequest`, `RssFeedRunContext` und die Verbindungs-Records tragen
keinen Schalter. Der Rahmen eines Zugangs besteht nur noch aus
Server-Adresse, Anmeldung und Vorgaben. Ein Betrieb mit eigener Zertifizierungsstelle ergänzt sie im
Truststore der JVM (Handbuch `betrieb.md`).

### 6. Fehlerbild

`ProxyRefusal` erkennt einen abgelehnten Tunnel (JDK, OkHttp, Apache) und eine Antwort `407`, die
der JDK-Client als Antwort statt Ausnahme liefert; alle Statuspfade benennen sie deutsch
(`ProxyRefusal.statusMessage`, `.statusFailure`, `.describe`): Verbindungstests, OAuth,
Dienstkonto-Tokens, MCP, OIDC, Verzeichnisabgleich, die Lauf-Clients, der Abbruch jedes Laufs
(`IndexingRunTemplate`) sowie Einzelfehler und Detailseiten eines Laufs. Ausnahme S3: Apache
HttpClient 5 reicht die Antwort des Proxys auf `CONNECT` als Antwort des Ziels durch; erkennbar ist
dort nur `407`. `UntrustedCertificate` erkennt ein abgelehntes Zertifikat an der Ursachenkette;
Verbindungstest, Lauf, Confluence, Nextcloud und S3 melden dann, dass der Betrieb die eigene
Zertifizierungsstelle ergänzen muss.

## Konsequenzen

**Einfacher:** Ein Proxy für alles, ohne Neustart; keine zweite Konfigurationsquelle; kein Weg, die
Zertifikatsprüfung zu umgehen.

**Schwieriger:** Ein Betrieb mit mehreren Proxys je Ziel braucht die Ausnahmeliste statt einer
Zuordnung je Quelle; eine Quelle mit privater Zertifizierungsstelle verlangt die Pflege des
Truststores.

**Restrisiken:** Welche Adresse der Proxy für einen Namen wählt, bleibt unsichtbar; ein
abgelehnter Tunnel bei S3 erscheint als Antwort des Objektspeichers.

## Verworfene Alternativen

- JVM-Proxy-Properties oder `HTTP(S)_PROXY` lesen — zweite Quelle neben der Oberfläche.
- Proxy je Client konfigurieren — jeder Client bräuchte eigenen Code, Spring AI baut OkHttp mit `proxy(null)`.
- Proxy-Authentifizierung — der JDK sperrt Basic für `CONNECT`, je Client-Familie ein Authenticator.
- Proxy-Host prüfen — geprüft wird das Ziel; der Proxy ist Infrastruktur des Hauses.
- Eigener TLS-Kontext je Client — nur mit einer Naht im Produktionscode, `SourceHttpClientFactory`
  baut ihn selbst.

## Offen

Nichts.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-10-11 | #2496 (PR 1) | Globaler Ausgangsproxy als Nachtrag zu ADR-0041 | 1–3, 6 |
| 2026-10-12 | #2496 (PR 2, 3) | Proxy je Bibliothek und Zugang entfernt | 4, 6 |
| 2026-10-12 | #2537 | Zertifikatsprüfung nicht aussetzbar | 5, 6 |
| 2026-10-14 | #2702 | Aus ADR-0041 herausgelöst und auf den Endstand neu gefasst | alle |
