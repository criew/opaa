# ADR-0063: Bindung und Bewachung geprüfter Namen gegen DNS-Rebinding

## Status

Akzeptiert · zuletzt geändert 2026-10-29

## Kontext

Die Zieladressprüfung (`TargetAddressValidator`) löst den Namen eines Ziels auf und lehnt Adressen in
Loopback-, Link-Local-, privaten und nicht routbaren Bereichen ab. Der Client, der danach verbindet,
löste den Namen ein zweites Mal auf. Wer den Namensdienst des Ziels kontrolliert, konnte die Prüfung
mit einer öffentlichen und die Verbindung mit einer internen Adresse beantworten (DNS-Rebinding).
Der Adress-Cache des JDK schützte nicht: Er läuft nach 30 Sekunden ab, also auch zwischen Prüfung und
Verbindung.

Die Prüfung nutzen Quellen der Wissensbibliotheken, Zugänge (OAuth, MCP-Discovery), Dienstkonto-Tokens,
Originalabruf, Graph, S3 und die Originalablage, die Modell-Endpunkte (Modellverwaltung, Wechsel des
Embedding-Modells, Modellabruf und Prüfung im Installationsassistenten), Identitätsanbieter
(Discovery, JWK-Set, Verzeichnisabgleich) und der Mailserver im Assistenten. Sie verbinden über
mehrere Bibliotheken: JDK-`HttpClient`, OkHttp (Spring AI), Apache HttpClient 5 (AWS SDK),
JavaMail und smbj. Der JDK-`HttpClient` bietet keinen Haken für die Namensauflösung.

## Entscheidung

### 1. Ein Resolver für die ganze JVM

`io.opaa.security.PinnedAddressResolverProvider` ist über `META-INF/services` als
`java.net.spi.InetAddressResolverProvider` registriert und beantwortet damit jede Namensauflösung
der JVM. Ein Name ohne Bindung und ohne Bewachung geht unverändert an den eingebauten Resolver des
JDK; Datenbank, Keycloak, Mailserver und interne Modelle bleiben unberührt. `OpaaApplication.main`
ruft zuerst `PinnedAddressResolverProvider.requireActive()` und bricht den Start ab, wenn das JDK
einen anderen Resolver geladen hat; die Meldung nennt die Ursachen einer früheren Namensauflösung.
Begründung: Alle genannten Bibliotheken lösen über `InetAddress` auf; eine Stelle erfasst jeden
heutigen und künftigen Client ohne eigenen Code, und der Client verbindet weiter über den Namen.

### 2. Jede Prüfung bindet: frische Antwort, Wiederverwendung höchstens 5 Sekunden

`TargetAddressValidator` holt für eine Prüfung eine Antwort des eingebauten Resolvers (alle
Adressfamilien, am Cache des JDK vorbei); eine Antwort für denselben Namen, die jünger als
`ResolutionPins.ANSWER_REUSE` (5 Sekunden) ist, verwendet er wieder. Er prüft jede Adresse und bindet
den Namen für `PIN_LIFETIME` (60 Sekunden) ab dem Abruf der Antwort an genau diese Adressen; eine
wiederverwendete Antwort verlängert die Bindung nicht. Danach prüft er, was `InetAddress` einem Client
jetzt liefert, weil der Cache des JDK noch eine ältere Antwort halten kann. Eine Ablehnung bindet
nichts, eine leere Antwort gilt als unbekannter Host. Fragt ein Client während der Bindung nur eine
Adressfamilie an, für die die Bindung keine Adresse hat, antwortet der Resolver mit
`UnknownHostException`, nie mit einer ungeprüften Antwort. IP-Literale werden geprüft, aber nicht
gebunden; ein Hostname außerhalb von ASCII wird abgelehnt. Begründung: Die frische Antwort lässt einen legitimen Adresswechsel (rotierende
S3-Adressen, DNS-Failover) bei der nächsten Prüfung ankommen; die kurze Wiederverwendung fasst die
Prüfungen eines Crawls oder S3-Laufs zu einer Abfrage je Name und Fenster zusammen, statt je Anfrage
bis zu acht Abfragen (Kubernetes mit `ndots:5`) auszulösen, und übersteht einen DNS-Aussetzer dieser
Länge. Länger als 5 Sekunden verzögerte die Ankunft eines Wechsels, ohne die Last spürbar zu senken.

### 3. Zwei Arten von Prüfung

Jeder Aufrufer nennt den Geltungsbereich (`TargetAddressValidator.Scope`; es gibt keinen Default,
`null` wird abgelehnt):

| Geltungsbereich | Aufrufer | Wirkung |
|---|---|---|
| `PER_CONNECTION` | `RedirectFollowingFetcher` (jeder Hop der HTTP-Konnektoren, Zugänge, OAuth, MCP, Graph, Originalabruf), `S3RequestGuard`, `S3ClientFactory`, `UploadS3TargetPolicy`, `SmbShareClient`, `SmbSourceConnector`, `NextcloudSourceConnector` | nur die Bindung aus Entscheidung 2, in einer eigenen Tabelle von höchstens `MAX_CONNECTION_PINS` (50 000) Einträgen; nur abgelaufene Bindungen weichen, ist die Tabelle voll gültiger Bindungen, wird die Prüfung eines neuen Namens abgelehnt |
| `STORED_TARGET` | `ModelAddressPolicy` (alle Modellrollen), `OidcAddressPolicy` (Discovery, JWK-Set, Verzeichnisabgleich), `InstallationMailTargets` | Bindung und Wächter aus Entscheidung 4 |

`PER_CONNECTION` braucht keinen Wächter: Jeder dieser Clients prüft unmittelbar vor jedem neuen
Verbindungsaufbau, ein späterer Adresswechsel trifft also auf eine neue Prüfung, und das Fenster
zwischen Prüfung und Verbindung schließt die Bindung. `RedirectFollowingFetcher` prüft vor jedem
`send`, auch vor jeder Wiederholung nach `429`; alle JDK-Clients folgen keiner Weiterleitung selbst
(`Redirect.NEVER`), ein Pool baut eine neue Verbindung also nur innerhalb eines `send` auf.
`S3RequestGuard` prüft vor jedem Übertragungsversuch des AWS SDK, bevor der Pool von Apache
HttpClient 5 verbindet; `SmbShareClient` prüft jeden Socket in seiner `SocketFactory`. Eine
wiederverwendete Verbindung bleibt an der Adresse, an die sie geprüft aufgebaut wurde. Eine Bindung,
die noch gilt, wird nie verdrängt, sonst fiele ein Name zwischen Prüfung und Verbindung auf eine
ungeprüfte Antwort zurück. Auch gespeicherte Basisadressen von Quellen, Zugängen und MCP-Servern laufen als
`PER_CONNECTION`: Ihre Clients prüfen jede Verbindung, und jedes Konto mit Anlagerecht legt sie an;
als `STORED_TARGET` könnten sie den Wächter füllen. Begründung: Die Wächter-Tabelle wächst nur mit der
Konfiguration der Systemverwaltung und der Installation, nicht mit Weiterleitungen, Feed-Links oder
Crawls.

### 4. Wächter bis zum Neustart für gespeicherte Ziele

Eine `STORED_TARGET`-Prüfung bewacht den Namen bis zum Neustart: Nach der Bindung löst der Resolver
bei jeder Anfrage frisch auf und entfernt jede Adresse in einem gesperrten Bereich; bleibt keine,
folgt `UnknownHostException` und eine Warnung im Log, höchstens eine je Minute. Die Regel gilt je
Richtlinie (`ResolutionPins.policyOf`, eine je Allowlist): Ein bewachter Name steht nicht in der
Allowlist des Validators, der ihn bewacht, und eine Allowlist lässt nach Namen zu, nie nach
aufgelöster Adresse; für ihn gilt also genau „keine Adresse in einem gesperrten Bereich“. Der Filter
wirkt für die ganze JVM: Lässt eine andere Allowlist denselben Namen zu, wirkt das erst nach dem
Neustart. Nach `ApplicationReadyEvent` prüft `StoredTargetWarmup` die gespeicherten Modell-Endpunkte
aller Rollen und den gespeicherten Mailserver einmal als `STORED_TARGET`, auf einem eigenen Thread;
eine Ablehnung ergibt nur eine Warnung, beim Mailserver eine Info, weil ein interner Server der
Regelfall ist. Identitätsanbieter prüft schon der Aufbau der Registry. Jede Instanz macht das für sich
([ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md), Entscheidung 16). Begründung: Modellaufrufe zur
Laufzeit ([ADR-0060](0060-schlanke-konfiguration-und-einrichtungsassistent.md), Entscheidung 10), der
JWK-Abruf von Nimbus und der Mailversand verbinden ohne eigene Prüfung.

### 5. TLS prüft weiter den Namen

Gebundene Adressen tragen den angefragten Namen, wie die Antworten des eingebauten Resolvers. Der
Client sendet ihn als SNI und prüft das Zertifikat gegen ihn, nicht gegen die Adresse. JavaMail
prüft die Identität des Mailservers bei SSL und STARTTLS ausdrücklich
(`mail.smtp.ssl.checkserveridentity`, `MailSenderProvider`); ein eingetragener Server, den das
Zertifikat nicht nennt, scheitert deshalb, und die Prüfung im Assistenten meldet das als
TLS-Fehler. Begründung: Der JDK-`HttpClient` nimmt den Namen für SNI und Zertifikatsprüfung aus der
Adresse, an die er verbindet.

### 6. Ausgangsproxy, Allowlist und Freigabe

Geht eine Verbindung über den Ausgangsproxy, löst der Proxy den Namen auf; der Validator prüft die
lokale Antwort, bindet und bewacht aber nichts. Die Prüfung bleibt beim Ziel
([ADR-0050](0050-ausgangsproxy-und-zertifikatspruefung.md), Entscheidung 3); interne Ziele zu sperren
ist dort Pflicht des Proxys, und ein lokal nicht auflösbarer Name bleibt über ihn erlaubt. Ein Name,
den die Allowlist zulässt, wird nicht gebunden. Ein Eintrag ohne Port hebt nur die Bewachung durch
dieselbe Richtlinie auf; ein Eintrag mit Port hebt nichts auf, weil eine Auflösung keinen Port kennt.
Allowlists liest OPAA nur beim Start: Zieht ein bewachter Name ins eigene Netz, braucht er einen
Eintrag ohne Port und einen Neustart.

### 7. Kein Weg am Resolver vorbei

`NameResolutionBypassTest` verbietet jeder Hauptklasse außerhalb von `io.opaa.security` eine eigene
Namensauflösung (OkHttp `Dns`, Apache `DnsResolver`, Netty, dnsjava, JNDI-DNS, ein eigener
`InetAddressResolver`). `PinnedAddressResolverProviderTest` prüft Registrierung und Wirkung. Die
Rebinding-Tests (`*RebindingTest`) laufen in der eigenen Gradle-Task `dnsRebindingTest`, Teil von
`check`, in einer JVM ohne Namens-Cache (`sun.net.inetaddr.ttl=0`), damit `ScriptedResolution` als
Namensdienst eines Angreifers jede Auflösung beantwortet; die übrigen Tests behalten den Cache.

### 8. Volle Wächter-Tabelle lehnt ab, statt zu verdrängen

Höchstens `ResolutionPins.MAX_GUARDED_NAMES` (100 000) Namen werden bewacht. Der Schlüssel ist
kompakt (128 Bit aus SHA-256 des normalisierten Namens), ein Eintrag belegt außerhalb der
Bindungsfrist rund 120 Byte. Ist die Tabelle voll, lehnt der Validator die `STORED_TARGET`-Prüfung
eines neuen Namens ab (`GuardCapacityExceededException`, eigene Meldung), warnt im Log höchstens
einmal je Minute und zählt `opaa_target_validation_refused_checks_total`;
`opaa_target_validation_guarded_names` zeigt den Füllstand. Eine Bewachung fällt nie weg, und die
volle Tabelle sucht nichts. Begründung: Verdrängen machte gerade den Namen wieder angreifbar, den ein
Angreifer zuvor prüfen ließ; die Zahl gespeicherter Ziele begrenzt die Konfiguration.

## Konsequenzen

**Einfacher:** Eine Stelle für alle Clients, auch künftige; kein Client braucht eigenen Code, TLS
bleibt unverändert, legitime Adresswechsel kommen nach höchstens 5 Sekunden Wiederverwendung bzw.
dem Cache der JVM an.

**Schwieriger:** Der Resolver ist Zustand der ganzen JVM. Ein gespeichertes Ziel, das danach ins
eigene Netz wechselt, ist bis zum Neustart nicht erreichbar, auch mit Allowlist-Eintrag erst nach dem
Neustart. Löst vor `main` eine Option einen Namen auf (JMX-Remote, `-Djdk.net.hosts.file`, ein
Javaagent, der Namen auflöst), lädt das JDK den Provider nicht, und der Start bricht mit dieser
Ursache in der Meldung ab. Ein Mailserver, den das Zertifikat unter dem eingetragenen Namen nicht
nennt, scheitert.

**Restrisiken:**

- Verbindungen über den Ausgangsproxy und Namen der Allowlist bindet OPAA nicht.
- Prüft jemand in kurzer Zeit mehr als 50 000 verschiedene Namen vor einer Verbindung, lehnt die
  Instanz weitere neue Namen ab, bis die ältesten Bindungen nach 60 Sekunden ablaufen; das zählt
  `opaa_target_validation_refused_connection_checks_total`.
- Ein gespeichertes Ziel, das beim Vorwärmen abgelehnt wird oder dessen Prüfung scheitert, bleibt
  unbewacht, bis es erneut erfolgreich geprüft wird.
- Die Wächter-Tabelle füllt nur, wer gespeicherte Ziele anlegen darf: Systemverwaltung (Modelle,
  Identitätsanbieter) und Installation (Token). 100 000 Einträge liegen weit über jeder Konfiguration;
  ist sie dennoch voll, lassen sich bis zum Neustart keine neuen gespeicherten Ziele prüfen, die
  bestehenden bleiben bewacht. Eine Grenze je Konto ist nicht gebaut, weil diese Prüfungen nur
  Verwaltung und Installation auslösen.
- Eine Bibliothek mit eigener Namensauflösung, die der Architekturtest nicht kennt, umginge den
  Resolver.

## Verworfene Alternativen

- Eigene `SocketFactory` je Client — der JDK-`HttpClient` hat keine, jede Bibliothek bräuchte eigenen Code.
- IP-Adresse in die URL, Name im `Host`-Header — bricht SNI und Zertifikatsprüfung, je Client umzubauen.
- Wechsel auf einen HTTP-Client mit DNS-Haken (OkHttp `Dns`, Apache `DnsResolver`) — deckt JavaMail, smbj und den JDK-Client nicht.
- Resolver prüft jede Auflösung gegen die Bereiche — sperrt Datenbank, Keycloak und interne Modelle; er kennt keinen Client.
- Kontext je Thread — der JDK-`HttpClient` löst auf eigenen Threads auf.
- Längerer Cache des JDK — keine Zusage, der Eintrag kann zwischen Prüfung und Verbindung ablaufen.
- Bindung mit Ablauf (etwa 24 Stunden) — danach löst ein Client, der nur einmal prüft, wieder ungeprüft auf.
- Gebundene Adressen bei Prüfungen innerhalb der Frist weiterverwenden — friert die Adresse ein, solange Prüfungen dicht folgen.
- Älteste Bewachung verdrängen, wenn die Tabelle voll ist — macht einen bewachten Namen wieder angreifbar.
- Jede Prüfung bewachen — Weiterleitungen auf Zufallsnamen füllen die Tabelle und sperren neue Ziele für alle.
- Basisadressen von Quellen als gespeicherte Ziele bewachen — jedes Konto mit Anlagerecht könnte die Tabelle füllen, und ihre Clients prüfen ohnehin jede Verbindung.
- Nur Egress-Regeln — bleiben empfohlen, liegen aber außerhalb von OPAA.

## Offen

Nichts.
