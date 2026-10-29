# ADR-0063: Bindung geprüfter Namen gegen DNS-Rebinding

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
der JVM. Ein Name, den die Prüfung nicht gebunden hat, geht unverändert an den eingebauten Resolver
des JDK; Datenbank, Keycloak, Mailserver und interne Modelle bleiben unberührt.
`OpaaApplication.main` ruft zuerst `PinnedAddressResolverProvider.requireActive()` und bricht den
Start ab, wenn das JDK einen anderen Resolver geladen hat. Begründung: Alle genannten Bibliotheken
lösen über `InetAddress` auf; eine Stelle erfasst jeden heutigen und künftigen Client ohne eigenen
Code, und der Client verbindet weiter über den Namen.

### 2. Bindung nach bestandener Prüfung

`TargetAddressValidator` löst einen Namen über `ResolutionPins` frisch auf (alle Adressfamilien, am
Cache des JDK vorbei), prüft jede Adresse und bindet den Namen an genau diese Adressen. Danach prüft
er, was `InetAddress` einem Client jetzt liefert, weil der Cache des JDK noch eine Antwort von vor
der Bindung halten kann. Eine Ablehnung bindet nichts, damit ein versehentlich geprüfter interner
Name (etwa der Datenbank-Host) für die Infrastruktur auflösbar bleibt. IP-Literale werden geprüft,
aber nicht gebunden.

### 3. Lebensdauer der Bindung

60 Sekunden nach der Prüfung (`ResolutionPins.PIN_LIFETIME`) liefert der Resolver für den Namen genau
die geprüften Adressen, gefiltert und geordnet nach der Anfrage des Clients. Danach löst er frisch
auf, prüft erneut und lehnt eine Antwort mit gesperrter Adresse für jeden Client mit
`UnknownHostException` ab. Die Bindung endet 24 Stunden nach der letzten Prüfung des Namens
(`BINDING_LIFETIME`) oder sobald ein Eintrag der Allowlist den Namen zulässt; mehr als
`MAX_BINDINGS` Namen verdrängen die Bindung mit der ältesten Prüfung. Die Bindungen gelten je
Instanz ([ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md), Entscheidung 16), denn jede Instanz
löst selbst auf. Begründung: Die kurze Frist gibt dem Client genau die geprüfte Adresse, die lange
deckt Clients, die nach einer Prüfung länger verbinden, auch Modellaufrufe zur Laufzeit, die
[ADR-0060](0060-schlanke-konfiguration-und-einrichtungsassistent.md), Entscheidung 10, nicht prüft.

### 4. TLS prüft weiter den Namen

Gebundene Adressen tragen den angefragten Namen, wie die Antworten des eingebauten Resolvers. Der
Client sendet ihn als SNI und prüft das Zertifikat gegen ihn, nicht gegen die Adresse.
Begründung: Der JDK-`HttpClient` nimmt den Namen für SNI und Zertifikatsprüfung aus der Adresse, an
die er verbindet.

### 5. Ausgangsproxy und Allowlist

Geht eine Verbindung über den Ausgangsproxy, löst der Proxy den Namen auf; die Bindung erreicht ihn
nicht. Die Prüfung bleibt beim Ziel ([ADR-0050](0050-ausgangsproxy-und-zertifikatspruefung.md),
Entscheidung 3), interne Ziele sperrt dort der Proxy. Ein Name, den die Allowlist zulässt, wird nicht
gebunden: Der Betrieb hat ihm die interne Adresse ausdrücklich erlaubt.

### 6. Kein Weg an der Bindung vorbei

`NameResolutionBypassTest` verbietet jeder Hauptklasse außerhalb von `io.opaa.security` eine eigene
Namensauflösung (OkHttp `Dns`, Apache `DnsResolver`, Netty, dnsjava, JNDI-DNS, ein eigener
`InetAddressResolver`). `PinnedAddressResolverProviderTest` prüft Registrierung und Wirkung. Die
Test-JVMs cachen keine Auflösung (`sun.net.inetaddr.ttl=0`), damit `ScriptedResolution` als
Namensdienst eines Angreifers jede Auflösung beantwortet.

## Konsequenzen

**Einfacher:** Eine Stelle für alle Clients, auch künftige; kein Client braucht eigenen Code, TLS
bleibt unverändert.

**Schwieriger:** Der Resolver ist Zustand der ganzen JVM. Ein geprüfter Name, der danach auf eine
interne Adresse wechselt, ist bis 24 Stunden nach seiner letzten Prüfung nicht erreichbar; ein
Eintrag in der Allowlist hebt die Bindung bei der nächsten Prüfung auf, ein Neustart sofort.

**Restrisiken:** Verbindungen über den Ausgangsproxy und Namen der Allowlist bindet OPAA nicht. Eine
Bibliothek mit eigener Namensauflösung, die der Architekturtest nicht kennt, umginge die Bindung.

## Verworfene Alternativen

- Eigene `SocketFactory` je Client — der JDK-`HttpClient` hat keine, jede Bibliothek bräuchte eigenen Code.
- IP-Adresse in die URL, Name im `Host`-Header — bricht SNI und Zertifikatsprüfung, je Client umzubauen.
- Wechsel auf einen HTTP-Client mit DNS-Haken (OkHttp `Dns`, Apache `DnsResolver`) — deckt JavaMail, smbj und den JDK-Client nicht.
- Resolver prüft jede Auflösung gegen die Bereiche — sperrt Datenbank, Keycloak und interne Modelle; er kennt keinen Client.
- Kontext je Thread — der JDK-`HttpClient` löst auf eigenen Threads auf.
- Längerer Cache des JDK — keine Zusage, der Eintrag kann zwischen Prüfung und Verbindung ablaufen.
- Nur Egress-Regeln — bleiben empfohlen, liegen aber außerhalb von OPAA.

## Offen

Nichts.
