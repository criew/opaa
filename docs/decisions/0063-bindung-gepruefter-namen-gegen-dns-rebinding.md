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
der JVM. Ein Name, den die Prüfung nicht bewacht, geht unverändert an den eingebauten Resolver
des JDK; Datenbank, Keycloak, Mailserver und interne Modelle bleiben unberührt.
`OpaaApplication.main` ruft zuerst `PinnedAddressResolverProvider.requireActive()` und bricht den
Start ab, wenn das JDK einen anderen Resolver geladen hat; die Meldung nennt die üblichen Ursachen
einer früheren Namensauflösung. Begründung: Alle genannten Bibliotheken
lösen über `InetAddress` auf; eine Stelle erfasst jeden heutigen und künftigen Client ohne eigenen
Code, und der Client verbindet weiter über den Namen.

### 2. Prüfung bewacht den Namen

`TargetAddressValidator` löst einen Namen bei jeder Prüfung frisch über `ResolutionPins` auf (alle
Adressfamilien, am Cache des JDK vorbei), prüft jede Adresse und bewacht den Namen danach mit genau
diesen Adressen. Danach prüft er, was `InetAddress` einem Client jetzt liefert, weil der Cache des
JDK noch eine Antwort von vor der Prüfung halten kann. Eine Ablehnung bewacht nichts, damit ein
versehentlich geprüfter interner Name (etwa der Datenbank-Host) für die Infrastruktur auflösbar
bleibt. IP-Literale werden geprüft, aber nicht bewacht; ein Hostname außerhalb von ASCII wird
abgelehnt, internationalisierte Namen kommen als Punycode. Begründung: Nur die frische Auflösung
lässt einen legitimen Adresswechsel (rotierende S3-Adressen, DNS-Failover) bei der nächsten Prüfung
ankommen, auch wenn Prüfungen dichter als die Bindungsfrist folgen.

### 3. Zwei Stufen: exakte Bindung, dann Wächter bis zum Neustart

60 Sekunden nach seiner letzten Prüfung (`ResolutionPins.PIN_LIFETIME`) bekommt ein Client für den
Namen genau die geprüften Adressen, gefiltert und geordnet nach seiner Anfrage. Danach löst der
Resolver bei jeder Anfrage frisch auf und entfernt jede Adresse in einem gesperrten Bereich; bleibt
keine, folgt `UnknownHostException` und eine Warnung im Log, höchstens eine je Minute. Der Wächter
läuft nicht ab, er endet mit dem Neustart. Er gilt je Instanz
([ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md), Entscheidung 16), denn jede Instanz löst
selbst auf. Der Filter entspricht der Regel des Validators: Ein bewachter Name steht nicht in dessen
Allowlist, und die Allowlist lässt nach Namen zu, nie nach aufgelöster Adresse; für ihn gilt also
genau „keine Adresse in einem gesperrten Bereich“. Begründung: Clients, die nur einmal prüfen, bleiben
dauerhaft geschützt, etwa der JWK-Abruf von Nimbus, Modellaufrufe zur Laufzeit, die
[ADR-0060](0060-schlanke-konfiguration-und-einrichtungsassistent.md), Entscheidung 10, nicht prüft,
und der Mailversand nach der Installation.

### 4. TLS prüft weiter den Namen

Gebundene Adressen tragen den angefragten Namen, wie die Antworten des eingebauten Resolvers. Der
Client sendet ihn als SNI und prüft das Zertifikat gegen ihn, nicht gegen die Adresse. JavaMail
prüft die Identität des Mailservers bei SSL und STARTTLS ausdrücklich
(`mail.smtp.ssl.checkserveridentity`, `MailSenderProvider`). Begründung: Der JDK-`HttpClient` nimmt
den Namen für SNI und Zertifikatsprüfung aus der Adresse, an die er verbindet.

### 5. Ausgangsproxy, Allowlist und Freigabe

Geht eine Verbindung über den Ausgangsproxy, löst der Proxy den Namen auf; der Wächter erreicht ihn
nicht. Die Prüfung bleibt beim Ziel ([ADR-0050](0050-ausgangsproxy-und-zertifikatspruefung.md),
Entscheidung 3); interne Ziele zu sperren ist dort Pflicht des Proxys, und ein lokal nicht
auflösbarer Name bleibt über ihn erlaubt. Ein Name, den die Allowlist zulässt, wird nicht bewacht.
Jede Allowlist ist eine Richtlinie (`ResolutionPins.policyOf`): Ein Eintrag ohne Port hebt nur die
Bewachung durch dieselbe Richtlinie auf, die einer anderen bleibt. Ein Eintrag mit Port hebt nichts
auf, weil eine Auflösung keinen Port kennt; das ist die strengste gemeinsame Regel. Allowlists liest
OPAA nur beim Start: Zieht ein bewachter Name ins eigene Netz, braucht er einen Eintrag ohne Port
und einen Neustart.

### 6. Kein Weg am Wächter vorbei

`NameResolutionBypassTest` verbietet jeder Hauptklasse außerhalb von `io.opaa.security` eine eigene
Namensauflösung (OkHttp `Dns`, Apache `DnsResolver`, Netty, dnsjava, JNDI-DNS, ein eigener
`InetAddressResolver`). `PinnedAddressResolverProviderTest` prüft Registrierung und Wirkung. Die
Rebinding-Tests (`*RebindingTest`) laufen in der eigenen Gradle-Task `dnsRebindingTest`, Teil von
`check`, in einer JVM ohne Namens-Cache (`sun.net.inetaddr.ttl=0`), damit `ScriptedResolution` als
Namensdienst eines Angreifers jede Auflösung beantwortet; die übrigen Tests behalten den Cache.

### 7. Volle Tabelle lehnt ab, statt zu verdrängen

Höchstens `ResolutionPins.MAX_GUARDED_NAMES` (100 000) Namen werden bewacht. Der Schlüssel ist
kompakt (128 Bit aus SHA-256 des normalisierten Namens), ein Eintrag belegt außerhalb der Bindungsfrist
rund 120 Byte, die volle Tabelle also etwa 12 MB. Ist sie voll, lehnt der Validator die Prüfung eines
neuen Namens ab (`GuardCapacityExceededException`, eigene Meldung), warnt im Log höchstens einmal je
Minute und zählt `opaa_target_validation_refused_checks_total`; `opaa_target_validation_guarded_names`
zeigt den Füllstand. Eine Bewachung fällt nie weg, und die volle Tabelle sucht nichts. Begründung:
Verdrängen machte gerade den Namen wieder angreifbar, den ein Angreifer zuvor prüfen ließ.

## Konsequenzen

**Einfacher:** Eine Stelle für alle Clients, auch künftige; kein Client braucht eigenen Code, TLS
bleibt unverändert, legitime Adresswechsel kommen sofort an.

**Schwieriger:** Der Resolver ist Zustand der ganzen JVM. Ein geprüfter Name, der danach ins eigene
Netz wechselt, ist bis zum Neustart nicht erreichbar, auch mit Allowlist-Eintrag erst nach dem
Neustart. Löst vor `main` etwas einen Namen auf (`-javaagent`, JMX-Remote, `-Djdk.net.hosts.file`),
lädt das JDK den Provider nicht, und der Start bricht mit dieser Ursache in der Meldung ab.

**Restrisiken:**

- Verbindungen über den Ausgangsproxy und Namen der Allowlist bewacht OPAA nicht.
- Eine Instanz bewacht nur, was sie selbst seit ihrem Start geprüft hat. Ein Client, der nach einem
  Neustart oder auf einer anderen Instanz ohne eigene Prüfung verbindet, etwa ein Modellaufruf, ist
  bis zur nächsten Prüfung des Namens ungeschützt.
- Füllt jemand die Tabelle, lehnt die Instanz bis zum Neustart jede Prüfung eines neuen Namens ab:
  ein Denial-of-Service gegen neue Quellen und Modelle. Bestehende Quellen bleiben bewacht und
  erreichbar. Dazu braucht es ein angemeldetes Konto mit Anlagerecht und 100 000 Prüfungen, je mit
  eigenem, öffentlich auflösbarem Namen; Log und Metrik zeigen es. Eine Grenze je Konto ist nicht
  gebaut: Der Validator kennt das Konto nicht, sie bräuchte eine Naht durch alle Aufrufer.
- Eine Bibliothek mit eigener Namensauflösung, die der Architekturtest nicht kennt, umginge den
  Wächter.

## Verworfene Alternativen

- Eigene `SocketFactory` je Client — der JDK-`HttpClient` hat keine, jede Bibliothek bräuchte eigenen Code.
- IP-Adresse in die URL, Name im `Host`-Header — bricht SNI und Zertifikatsprüfung, je Client umzubauen.
- Wechsel auf einen HTTP-Client mit DNS-Haken (OkHttp `Dns`, Apache `DnsResolver`) — deckt JavaMail, smbj und den JDK-Client nicht.
- Resolver prüft jede Auflösung gegen die Bereiche — sperrt Datenbank, Keycloak und interne Modelle; er kennt keinen Client.
- Kontext je Thread — der JDK-`HttpClient` löst auf eigenen Threads auf.
- Längerer Cache des JDK — keine Zusage, der Eintrag kann zwischen Prüfung und Verbindung ablaufen.
- Bindung mit Ablauf (etwa 24 Stunden) — danach löst ein Client, der nur einmal prüft, wieder ungeprüft auf.
- Gebundene Adressen bei Prüfungen innerhalb der Frist weiterverwenden — friert die Adresse ein, solange Prüfungen dicht folgen.
- Älteste Bindung verdrängen, wenn die Tabelle voll ist — macht einen bewachten Namen wieder angreifbar.
- Nur Egress-Regeln — bleiben empfohlen, liegen aber außerhalb von OPAA.

## Offen

Nichts.
