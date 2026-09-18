# ADR-0035: Fremdzugänge — ein MCP-Server über Streamable HTTP, persönliche Zugangstokens und die Freigabe je Wissensbibliothek

## Status

**Vorgeschlagen (18.09.2026)** — Issue #1716, Epic #1715. Setzt den Maintainer-Beschluss vom
18.09.2026 („Fundament zuerst") und die dort getroffenen dreizehn Entscheidungen nach den fünf
Stakeholder-Bewertungen um. Nachtrag zu [ADR-0005](0005-authentication-strategy.md) (ein zusätzlicher
Prüfweg, kein zusätzlicher Betriebsmodus), zu [ADR-0033](0033-lokale-benutzerverwaltung.md)
(dieselbe Prüfkette und derselbe Sofortwiderruf, eigene Ablage) und zu
[ADR-0021](0021-single-instance-betrieb.md) (ein weiterer Eintrag prozesslokalen Zustands: das
Zählfenster des Abflussalarms).

Das **Verhalten** dieses Kanals steht in
[`docs/features/external-access.md`](../features/external-access.md) — Schalter, Freigabe,
Tokeneigenschaften, effektive Sicht, Werkzeuge, Kontingent, Abflussalarm, Protokollierung. Dieser ADR
wiederholt es nicht; er hält die sechs Festlegungen fest, die das Produkt langfristig binden und deren
Umkehrung teuer wäre, samt der betrachteten Alternativen.

## Kontext

Beschäftigte arbeiten in KI-Werkzeugen, die OPAA nicht kennt, und holen sich das Wissen des Hauses
heute über die Zwischenablage dorthin. Der Kanal, der das ablösen soll, ist **lesend**, liegt hinter
drei Freigaben und liefert Fundstellen statt erzeugter Antworten.

Vier Randbedingungen binden die Entscheidungen unten:

- **Die Betriebsform.** OPAA wird für diesen Kanal **nicht** aus dem offenen Netz erreichbar gemacht.
  Zielclients laufen auf dem Arbeitsplatz oder im Hausnetz; der ganze Kanal liegt zusätzlich hinter
  einer installationsweiten Netzbeschränkung mit der Vorgabe Hausnetz.
- **Die Zusage, dass die einzelne Abfrage nicht protokolliert wird**
  (`security-and-compliance.md`, „Was ausdrücklich nicht protokolliert wird"). Sie trägt die
  Mitbestimmungsfähigkeit des Produkts und gilt in diesem Kanal unverändert. Jede Entscheidung unten
  darf keinen personenbezogenen Auswertungspfad erzeugen.
- **Das Rechtemodell hängt an der Wissensbibliothek**, nicht am Arbeitsraum, und die Rechtehistorie
  historisiert neben Grants und Gruppenmitgliedschaften auch die **Reichweitenfelder** am Asset
  (`visibility`, `listed`) als Zeitintervalle.
- **Die Protokolllage von MCP, Stand 18.09.2026** (geprüft an der Spezifikation und an den
  Artefakten in Maven Central):

  | Sache | Stand |
  |---|---|
  | Aktuelle MCP-Spezifikation | `2026-07-28`. Sie handelt die Fassung **je Anfrage** aus (`MCP-Protocol-Version`-Header bzw. `_meta`), verlangt vom Server die RPC `server/discover` und antwortet bei einer nicht unterstützten Fassung mit `UnsupportedProtocolVersionError` (JSON-RPC-Code `-32022`) samt Liste der unterstützten Fassungen. Die Spezifikation nennt Revisionen bis `2025-11-25` „legacy" (Aushandlung über den `initialize`-Handshake) |
  | Abgekündigt | Der **HTTP+SSE-Transport** (seit `2025-03-26`, Migrationspfad Streamable HTTP) und — seit `2026-07-28` — die **dynamische Client-Registrierung** (Migrationspfad „Client ID Metadata Documents"), außerdem Roots, Sampling und Logging. Nach der Lebenszyklus-Richtlinie (SEP-2596) bleibt ein abgekündigtes Merkmal mindestens zwölf Monate in der Spezifikation, im beschleunigten Fall mindestens neunzig Tage |
  | Unsere Bibliothek | Spring AI **2.0.1** (`backend/gradle/libs.versions.toml`). Der Starter `org.springframework.ai:spring-ai-starter-mcp-server-webmvc:2.0.1` zieht `spring-boot-starter-web:4.1.1` — genau unsere Spring-Boot-Fassung — sowie `mcp-spring-webmvc:2.0.1` und darüber `io.modelcontextprotocol.sdk:mcp-core:2.0.x` |
  | Fassungen, die diese Bibliothek kennt | `2024-11-05`, `2025-03-26`, `2025-06-18`, `2025-11-25` (`ProtocolVersions` in `mcp-core`) — **ausschließlich legacy**. Die Aushandlung je Anfrage, `server/discover` und `UnsupportedProtocolVersionError` aus `2026-07-28` sind dort noch nicht umgesetzt |
  | Werkzeugliste | `tools/list` wird in **beiden** Betriebsarten (`McpStatelessAsyncServer` wie `McpAsyncServer`, `mcp-core` 2.0.1) aus der serverweit registrierten Liste beantwortet; der Anfragekontext geht nicht ein. Eine Liste je Token ist ein eigener Handler, keine Konfiguration |
  | Was der Starter **nicht** liefert | Authentifizierung. Die Spring-AI-Dokumentation sagt es ausdrücklich: Der HTTP-Transport legt einen **unauthentifizierten** JSON-RPC-Endpunkt an; jeder, der ihn erreicht, kann ohne Merkmal alle registrierten Werkzeuge auflisten und aufrufen. Die Absicherung ist Sache einer eigenen Schicht davor |

Die letzte Zeile ist die wichtigste dieses ADR: Der Transport bringt den Prüfweg **nicht** mit. Er
entsteht in unserer Filterkette, und er ist der einzige Grund, warum dieser Kanal überhaupt
verantwortbar ist.

## Entscheidung

### 1. Ein MCP-Server im Backend, Transport Streamable HTTP in der zustandsfreien Betriebsart, Pfad `/mcp`

OPAA baut **keine Erweiterung je Werkzeug**, sondern genau einen MCP-Server, den alle einschlägigen
Clients gleichermaßen ansprechen. Der Transport ist **Streamable HTTP, und zwar in der zustandsfreien
Betriebsart** (`STATELESS`); der Server läuft im Backendprozess unter dem Pfad `/mcp` und wird mit dem
Spring-AI-Starter gebaut:

```
implementation(libs.spring.ai.starter.mcp.server.webmvc)   // Eintrag in libs.versions.toml

spring.ai.mcp.server.protocol                 = STATELESS
spring.ai.mcp.server.type                     = SYNC
spring.ai.mcp.server.stateless.mcp-endpoint   = /mcp        (Vorgabe der Bibliothek)
spring.ai.mcp.server.instructions             = aus der Systemkonfiguration
spring.ai.mcp.server.capabilities.resource    = false
spring.ai.mcp.server.capabilities.prompt      = false
spring.ai.mcp.server.capabilities.completion  = false
```

WebMVC, nicht WebFlux — das Backend ist eine MVC-Anwendung. `SYNC`, weil die Werkzeuge synchrone
Domain-Dienste aufrufen. Nur die Fähigkeit **Tools** wird angeboten: Ressourcen, Prompts und
Completions sind eigene Ausspielflächen mit eigener Rechtefrage, und der Grundsatz der
Spring-AI-Dokumentation gilt — *die Registrierung eines Werkzeugs ist die Entscheidung, es
offenzulegen*.

**Warum zustandsfrei.** Jede Anfrage trägt ihr Bearer-Merkmal; eine Sitzung fügt diesem Kanal nichts
hinzu, was er braucht, aber vier Dinge, die er nicht will: prozesslokalen Zustand
([ADR-0021](0021-single-instance-betrieb.md)), eine Stelle, an der eine Prüfung „je Verbindung"
plausibel aussieht und damit die Prüfung je Aufruf (Entscheidung 5) unterlaufen kann, eine
Sitzungstabelle, die einen Neustart oder einen Notaus überdauern will, und einen Lebenszyklus, den
niemand beobachten kann, weil dieser Kanal bewusst keine Nutzungsdaten führt. Ohne Sitzungen ist
„eine offene Sitzung überdauert den Notaus nicht" keine Zusage, die eingehalten werden muss, sondern
ein Zustand, den es nicht gibt. Die Spezifikation `2026-07-28` geht mit ihrer Aushandlung je Anfrage
in dieselbe Richtung.

**Nachzug in der Spezifikation.** `external-access.md` formuliert an zwei Stellen sitzungsnah — die
Werkzeugbeschreibungen entstünden „zur Verbindungszeit", und „bestehende Sitzungen enden beim
Ausschalten". Beides ist mit dieser Entscheidung stärker erfüllt, als es dort steht: Es gibt keine
Sitzung, die enden müsste, und die Beschreibung entsteht je Anfrage. Die Formulierungen sind
entsprechend nachzuziehen (#1721); eine Verhaltensänderung ist damit nicht verbunden.

Die Bibliotheksversion ist **nicht Teil dieser Entscheidung**. Festgelegt sind Transport, Betriebsart
und Ort (ein Server, im Backend, unter `/mcp`); wechselt der Starter, gilt die Festlegung unverändert.

**Betrachtete Alternativen.** Eine *eigene Erweiterung je Werkzeug* (Claude Code, Cursor, OpenCode, VS
Code) — vier Artefakte mit vier Veröffentlichungswegen und vier Lebenszyklen für denselben Zuschnitt;
der Standard, den alle vier bereits sprechen, macht sie überflüssig. Ein *stdio-Server je Arbeitsplatz*
— müsste auf jedem Gerät installiert, aktualisiert und mit einem eigenen Zugangsmerkmal versehen
werden, und ein Sicherheitsfehler wäre auf tausend Geräten zu beheben statt an einer Stelle. Der
*SSE-Transport* — in Spring AI seit 2.0.0 als überholt geführt und in der MCP-Spezifikation seit
`2025-03-26` abgekündigt; ein neuer Kanal beginnt nicht auf einem Migrationspfad, der schon läuft. Die
*sitzungsbehaftete Betriebsart* `STREAMABLE` — sie kann Benachrichtigungen an den Client (etwa über
geänderte Werkzeuglisten) und Rückfragen an ihn; beides braucht dieser Kanal nicht, und der Preis
wären die vier Punkte oben. Der scheinbare Vorteil, Werkzeugbeschreibungen je Verbindung zu bilden,
ist keiner: Die Bibliothek beantwortet `tools/list` in **beiden** Betriebsarten aus derselben
serverweiten Liste (siehe Umsetzungsrisiko unten).

**Umsetzungsrisiko für #1721 — die Werkzeugliste je Token.** Geprüft an `mcp-core` 2.0.1: Sowohl
`McpStatelessAsyncServer` als auch `McpAsyncServer` beantworten `tools/list` aus der beim Aufbau
registrierten, **serverweiten** Liste; der Anfragekontext (`McpTransportContext`, über einen
`McpTransportContextExtractor` mit dem `Authorization`-Kopf befüllbar) wird dabei nicht ausgewertet.
Eine Beschreibung je Token ist also in keiner Betriebsart eine Voreinstellung, sondern verlangt einen
eigenen Handler für `tools/list` — das ist der Umsetzungspunkt, nicht die Wahl der Betriebsart. Kommt
#1721 damit nicht durch, ist der **Ausweg** festgelegt: eine feste Werkzeugliste, deren Beschreibungen
die Bestände nicht aufzählen, dazu die Aufzählung der effektiven Sicht **im Antworttext** jedes
Aufrufs und in `list_libraries`. Das fremde Modell erfährt den Umfang dann beim ersten Aufruf statt
bei der Auflistung; die Rechteprüfung hängt davon in keinem Fall ab. Was dabei **nicht** zulässig ist:
die Bestände aller Bibliotheken der Installation in eine allen Tokens gemeinsame Beschreibung zu
schreiben — das wäre eine Auskunft über Bestände, die das Token nicht sehen darf.

**Folgen.** Ein Betriebsartefakt, ein Update, eine Adresse im Handbuch. Zugleich **ein einziger
Ausfallpunkt für alle Fremdzugänge des Hauses** — davon handelt Entscheidung 6. Prozesslokaler Zustand
entsteht durch den Server selbst **nicht**; der einzige Eintrag, den dieser Kanal in die Liste aus
[ADR-0021](0021-single-instance-betrieb.md) trägt, ist das Zählfenster des Abflussalarms, das
ausdrücklich im Arbeitsspeicher lebt. Die zustandsfreie Betriebsart kennt keine Rückfragen an den
Client (Elicitation, Sampling, Ping) und keine Änderungsbenachrichtigungen — beides ist hier
verzichtbar und in den Grenzen des Kanals ohnehin nicht vorgesehen. Und: Weil der Starter den
Endpunkt unauthentifiziert anlegt, ist eine fehlende Filterkettenregel kein Konfigurationsfehler,
sondern eine offene Tür. Die Wegeliste des Sicherheits-Wächters muss `/mcp` deshalb **ausdrücklich**
führen, und ein Test muss belegen, dass ein Aufruf ohne Merkmal nicht durchkommt.

### 2. Authentifizierung ist ein persönliches Zugangstoken: undurchsichtig, mit Präfix, nur gehasht gespeichert

Der Fremdzugang authentifiziert sich mit einem **persönlichen Zugangstoken** als Bearer-Merkmal:

```
Authorization: Bearer opaa_pat_<Zufall>
```

- **Undurchsichtiges Zufallsmerkmal**, mindestens 256 Bit aus einem kryptografisch sicheren
  Zufallsgenerator, Base64url kodiert, mit dem festen Präfix `opaa_pat_`.
- **Serverseitig nur als Hash.** Ein schneller kryptografischer Hash (SHA-256) genügt und ist hier
  richtig: Das Merkmal ist zufällig und hochentropisch, ein Wörterbuchangriff darauf existiert nicht,
  und ein Passwort-KDF mit Arbeitsfaktor läge auf dem heißen Pfad jedes einzelnen Werkzeugaufrufs.
  Das ist die bewusste Abweichung von der Passwortablage aus ADR-0033, Entscheidung 9 — und der
  Grund, warum sie zulässig ist. Der Hash ist deterministisch und damit indizierbar; der Vergleich
  läuft in konstanter Zeit.
- **Der Klartext ist genau einmal sichtbar**, unmittelbar nach dem Erzeugen. Danach existiert er
  nirgends mehr, auch nicht für die Systemverwaltung.
- **Das Präfix dient der Wiedererkennung** — in der Oberfläche, im Client und für Geheimnis-Scanner,
  die es in einer versehentlich eingecheckten Konfigurationsdatei finden sollen. Es erscheint
  **nicht** in Anwendungs- oder Proxy-Logs: über Monate stabil und einer Person zuordenbar, ergäbe es
  zusammen mit Zeitstempel und Netzadresse genau die minutengenaue Abfragehistorie, die das
  Nachweisprotokoll zu Recht ausschließt — nur ohne dessen Schutzregeln.
- **Widerruf über die eigene Zeile.** Ausstellung, Ablauf und Sofortwiderruf folgen derselben
  Prüfkette wie die lokalen Tokens aus [ADR-0033](0033-lokale-benutzerverwaltung.md) (Entscheidungen
  6–8) — aber in einer **eigenen Ablage**, nicht in `local_refresh_tokens`/`local_revoked_tokens`.
  Ein Zugangstoken ist kein Sitzungsmerkmal; es hat andere Fristen, andere Felder (Bibliotheksauswahl,
  Name) und einen anderen Lebenszyklus.
- **Die Netzbeschränkung gehört dem Kanal, nicht dem Token.** Eine CIDR je Token ist für einen
  Arbeitsplatzclient hinter wechselnden Adressen unbrauchbar und wäre zugleich ein
  **Anwesenheitsmerkmal**: Gesetzt, scheitert jeder Aufruf aus der Heimarbeit, und die Abweisung
  entstünde als Ereignis irgendwo im Betrieb — die Unterscheidbarkeit von Dienststelle und Heimarbeit
  ist in `security-and-compliance.md` ausdrücklich als personenbezogenes Merkmal eingestuft. Mit den
  Service-Accounts kommt sie je Identität wieder; dort ist die Adresse fest und die Identität keine
  Person.

**Betrachtete Alternativen.** Ein *selbsttragendes JWT* mit den Rechten im Merkmal: Es ist ohne
Datenbankzugriff prüfbar — aber der Widerruf braucht dann eine `jti`-Sperrliste, also einen
Datenbankzugriff je Aufruf, und die eingesparte Abfrage ist wieder da. Zugleich trüge das Merkmal eine
**Kopie** der Rechtelage, während die Zusage dieses Kanals lautet, dass die effektive Sicht zur
Anfragezeit entsteht. Das Zeitfenster zwischen Rechteentzug und Tokenablauf, das ein JWT unvermeidlich
öffnet, ist genau das, was hier nicht sein darf. *Clientzertifikate (mTLS)*: verteilungsaufwendig je
Arbeitsplatz, und die Erneuerung liegt beim Betrieb statt bei der Person. *Das OIDC-Zugangstoken der
Web-Sitzung weiterreichen*: kurzlebig, nicht für ein fremdes Werkzeug ausgestellt, und es trüge den
vollen Rechteumfang der Person statt eines Ausschnitts.

**Folgen.** Jeder Werkzeugaufruf kostet eine Tokenabfrage — akzeptabel, weil Kontingent und effektive
Sicht ohnehin je Aufruf ausgewertet werden. Ein Widerruf wirkt in derselben Sekunde; das ist die
Zusage, die dieser Kanal gibt, und sie ist nur mit einer nachgeschlagenen Zeile haltbar. Ein
verlorener Tokenwert ist unwiederbringlich — es gibt nur „neu ausstellen".

### 3. OAuth 2.1 mit Autorisierungsserver und dynamischer Client-Registrierung wird zurückgestellt

Die MCP-Spezifikation sieht für fremd betriebene Verbraucherdienste OAuth 2.1 vor: einen
Autorisierungsserver, Metadaten der geschützten Ressource, Resource Indicators und eine dynamische
Client-Registrierung. **Nichts davon wird in dieser Stufe gebaut.**

Die Begründung ist die Betriebsform, nicht der Aufwand: OAuth 2.1 in dieser Ausprägung löst das
Problem „ein Dienst, den die Behörde nicht betreibt, soll im Namen einer Person zugreifen, ohne dass
ein Geheimnis von Hand verteilt wird". Genau dieses Problem hat der Kanal nicht. Er bedient Werkzeuge,
die auf dem Arbeitsplatz oder im Hausnetz laufen, und OPAA wird dafür nicht öffentlich erreichbar
gemacht. Ein Autorisierungsserver ohne öffentliche Erreichbarkeit ist Apparat ohne Gegenwert — und
Apparat, der selbst angegriffen werden kann.

Hinzu kommt ein Befund aus der Spezifikation selbst: Die **dynamische Client-Registrierung ist mit
`2026-07-28` abgekündigt** (Migrationspfad „Client ID Metadata Documents", frühestens entfernbar mit
der ersten Revision ab dem 28.07.2027). Wer sie heute bauen wollte, baute auf einem Verfahren, das
bereits auf dem Weg hinaus ist.

**Die Entscheidung ist zurückgestellt, nicht verworfen. Sie ist neu zu treffen, sobald eine der
beiden Bedingungen eintritt:**

1. Ein **fremd betriebener Dienst** soll angeschlossen werden (ein Cloud-Connector, ein
   Verbraucherdienst außerhalb des Hauses) — dann gibt es kein Merkmal mehr, das eine Person auf
   ihrem Gerät verwahrt.
2. Die Installation wird **bewusst aus dem offenen Netz erreichbar** gemacht — dann fällt die
   Netzbeschränkung als tragende Sicherung weg.

Beides ist zuerst eine Entscheidung über die **öffentliche Erreichbarkeit der Installation**, und die
gehört nicht in diesen ADR. Dieser ADR benennt sie nur als Voraussetzung.

**Folgen.** Werkzeuge, die ausschließlich OAuth mit dynamischer Registrierung sprechen und kein
statisches Bearer-Merkmal zulassen, sind an diese Installation nicht anschließbar. Das ist bekannt und
hingenommen; die vier Zielclients des Handbuchs können ein Bearer-Merkmal. Der Weg dorthin bleibt
offen: Der Prüfweg liegt in unserer Filterkette (Entscheidung 1), er lässt sich um ein zweites
Verfahren ergänzen, ohne dass Werkzeuge, Freigabemodell oder Datenmodell sich ändern.

### 4. Freigabe-Einheit ist die Wissensbibliothek, und die Freigabe ist ein historisiertes, befristetes Reichweitenfeld

Freigegeben wird die **Wissensbibliothek** — nicht der Arbeitsraum, nicht das Dokument, nicht die
Person. Der Arbeitsraum ist eine **Sicht** auf Bibliotheken; Rechte hängen ohnehin an der Bibliothek,
und eine Freigabe, die an einer Sicht hinge, ließe sich durch eine zweite Sicht auf denselben Bestand
umgehen. Eine Freigabe je Dokument wäre bei fünfstelligen Beständen nicht pflegbar und bei
Chunk-Treffern nicht durchsetzbar. Eine **personenbezogene** Freigabe („Frau X darf das über ihr
Werkzeug, der Praktikant nicht") wäre eine zweite Rechtewahrheit neben den Leserechten; wer die
Auswahl enger ziehen will, zieht die Leserechte enger.

Das Merkmal ist fachlich dasselbe wie `visibility` und `listed`: eine Stufe der **Reichweite** an der
Bibliothek. Daraus folgt unmittelbar und ohne neue Mechanik:

- **Es wird historisiert, nicht nur protokolliert** — dieselbe Intervall-Historisierung wie die
  übrigen Reichweitenfelder, mit demselben Schreibpfadschutz. Damit gelten für es auch
  [ADR-0016](0016-loeschschicksal-rechtehistorie.md) (die Historie überlebt die Löschung ihres
  Fachobjekts) und [ADR-0032](0032-zeitquelle-rechtehistorie.md) (streng monotone Zeitquelle, keine
  leeren Intervalle) unverändert. Der Grund ist eine Prüfsituation: Das Nachweisprotokoll wird nach
  Frist monatsweise vollständig gelöscht — die Frage „war dieser Bestand 2026 aus dem Haus
  erreichbar?" wäre 2030 sonst nicht mehr zu beantworten, bei einem Feld, das über Hausgrenzen
  entscheidet.
- **Es steht unter der Freigabe-Obergrenze** konnektor-gespeister Bibliotheken — sonst wäre der
  Fremdzugang der Weg an der einzigen technischen Sicherung zwischen „Fachverfahrensdaten
  eingespeist" und „breit lesbar" vorbei.
- **Es ist pflichtbefristet auf höchstens ein Jahr.** Eine unbefristete Freigabe ist eine Ratsche:
  Jede Anfrage erzeugt eine, und nichts erzeugt je eine Rücknahme. Die Befristung ist die einzige
  Maßnahme, die das Ziel „der Anteil freigegebener Bibliotheken bleibt klein" durchsetzt, statt es zu
  erhoffen.
- **Es ist gesperrt bei „Nachfolge offen".** `spaces-and-assets.md` friert die Reichweite verwaister
  Assets ein; dieses Merkmal ist eine Erhöhung der Reichweite. Ein Bestand ohne fachlich
  Verantwortlichen verlässt das Haus nicht.

Das **Freigaberecht** bleibt bei der Verwalter-Rolle der Bibliothek plus Systemverwaltung. Eine
zusätzliche Genehmigungsstufe (etwa die Referatsleitung) verschöbe die Verantwortung, statt sie zu
klären; Befristung, Historisierung und die angezeigte **Anzahl** der Tokens, die die Bibliothek
enthalten, sind die Antwort auf das dahinterliegende Anliegen.

**Folgen.** Kein neues Historisierungsverfahren, kein neues Rechtekonzept — aber ein weiteres Feld im
Schreibpfadschutz der Rechtehistorie und eine wiederkehrende Frist, die jemand bedienen muss; die
Wiedervorlage läuft über den Mailweg aus ADR-0033. Eine zurückgenommene oder erloschene Freigabe
entzieht die Bibliothek **allen** Tokens sofort, ohne dass ein Token angefasst wird. Sie lebt in
bestehenden Tokens **nicht wieder auf**: Ein Token, dessen Umfang ohne Zutun der Person wieder wächst,
ist aus derselben Richtung falsch wie eine Auswahl „alle, auch künftige".

### 5. Genau ein Zugriffsweg auf die Daten — und die Prüfung geschieht je Werkzeugaufruf

Die MCP-Werkzeuge `search`, `fetch` und `list_libraries` sowie der REST-Endpunkt `POST /api/v1/search`
rufen **dieselben Domain-Dienste** wie `POST /api/v1/query`. Es gibt keinen eigenen Zugriff auf
`vector_store`, keine zweite Rechtelogik, keinen zweiten Rankingpfad. Die MCP-Schicht ist eine
Übersetzungsschicht: Sie nimmt einen Werkzeugaufruf entgegen, ermittelt die effektive Sicht und ruft
den Dienst auf.

Die **effektive Sicht** ist die Schnittmenge aus Rechten der Person, gültiger Bibliotheksfreigabe,
Auswahl im Token und Installationsschalter — ausgewertet **zur Anfragezeit, bei jedem einzelnen
Werkzeugaufruf**, nie eingefroren. Die zustandsfreie Betriebsart aus Entscheidung 1 macht das zur
einzigen möglichen Bauweise statt zu einer Regel, an die man sich halten muss: Es gibt keine
Verbindung, bei deren Aufbau man prüfen könnte, und damit auch keine offene Sitzung, die einen Notaus
überdauern und die Meldung „Kanal ist zu" zu einer Unwahrheit machen könnte. Der Rechtefilter sitzt
dabei **in der Suche**, nicht davor — eine zweite Filterstelle wäre eine zweite Wahrheit.

Abgesichert wird die Invariante nicht durch Disziplin, sondern durch zwei Prüfungen: ein Strukturtest,
der die Abhängigkeiten der Werkzeug- und Endpunktklassen auf die Domain-Dienste begrenzt (kein
`VectorStore`, kein Repository des Index), und die Tests des Epics, die jeden der vier Faktoren
einzeln entziehen und die Bibliothek aus den Treffern verschwinden sehen.

**Betrachtete Alternative.** Ein eigener, für Werkzeuge optimierter Lesepfad — schneller, weil ohne
Teilfragen, Fusion und Reranking. Verworfen: Er wäre eine zweite Qualitätswahrheit, die niemand
pflegt, und vor allem eine zweite Stelle, an der die Rechteprüfung richtig sein muss. Von zwei
Rechteprüfungen ist nach zwei Jahren eine falsch.

**Folgen.** Was die Web-Oberfläche nicht findet, findet auch der Fremdzugang nicht — und umgekehrt;
jede Verbesserung des Suchwegs wirkt in beiden Kanälen. Der Kanal erbt aber auch die Last des vollen
Suchwegs je Aufruf; deshalb das Kontingent je Token. Und: Die Antwortzeit eines Werkzeugaufrufs ist
die des Suchwegs — der `request-timeout` des MCP-Servers (Vorgabe 20 Sekunden) ist gegen sie zu
stellen.

### 6. Der Fassungswechsel der MCP-Spezifikation ist geplant, nicht abgewartet

MCP versioniert nach Datum, und der Transport der Vorgängerfassung ist bereits abgekündigt. Die
Fassung, die dieser Kanal spricht, wird dasselbe Schicksal haben. Weil es genau **einen** Server gibt,
fallen bei einem Bruch **alle Fremdzugänge des Hauses zugleich** aus. Vier Festlegungen:

**a) Was der Server aushandelt und ankündigt.** OPAA handelt genau die Fassungen aus, die die
eingesetzte Bibliothek trägt — heute (`mcp-core` 2.0.x) `2024-11-05`, `2025-03-26`, `2025-06-18` und
`2025-11-25`, also ausschließlich handshake-basierte Revisionen. Die Bezugsfassung `2026-07-28` mit
ihrer Aushandlung je Anfrage ist dort noch nicht umgesetzt; sie ist der Stand, gegen den entschieden
wird, nicht der Stand, der ausgeliefert wird. **Die unterstützten Fassungen werden an einer Stelle
angekündigt, die nicht die Bibliothek ist**: im Handbuchkapitel und in der Systemkonfiguration des
Kanals, damit „welche Fassung spricht diese Installation?" ohne Blick in eine Abhängigkeitsliste zu
beantworten ist.

**b) Verhalten bei einer Fassungsabweichung: klare Ablehnung mit benanntem Grund.** Ein Client mit
einer nicht unterstützten Fassung — älter oder neuer — bekommt eine Ablehnung, die die unterstützten
Fassungen **aufzählt**; nie ein stilles Weiterlaufen unter einer anderen Fassung und nie eine
Ablehnung ohne Grund. Solange die Bibliothek legacy spricht, ist das die Fehlerantwort auf
`initialize` mit der genannten Fassungsliste — genau das, was die Spezifikation `2026-07-28` einem
Server empfiehlt, der legacy Clients begegnet: Diese Meldung ist oft die einzige Diagnose, die der
Client anzeigen kann. Sobald die Bibliothek die moderne Aushandlung trägt, ist es
`UnsupportedProtocolVersionError` mit derselben Liste. **Die Zusage ist die Aufzählung, nicht ihr
Transportformat.**

**c) Abkündigungsfrist: mindestens zwölf Monate.** OPAA bedient eine von ihm abgekündigte
Protokollfassung noch mindestens **zwölf Monate**, gerechnet ab dem Tag, an dem eine OPAA-Fassung die
Nachfolgefassung erstmals ausliefert — und in keinem Fall kürzer als die „earliest removal" der
MCP-Lebenszyklus-Richtlinie. Die Frist lehnt sich an SEP-2596 an, ist aber **eigenständig**: Eine
Installation steuert den Client-Bestand ihrer Beschäftigten nicht, und die Frist der Spezifikation
läuft gegen Bibliotheksautoren, nicht gegen Behörden. Unterschritten wird sie nur bei einer
Sicherheitslücke in der abgekündigten Fassung — dann mit ausdrücklicher Ankündigung im Handbuch und in
den Versionshinweisen, nicht stillschweigend.

**d) Zuständigkeit: eine benannte Fassungswache, vierteljährlich.** Die Nachführung ist einer Rolle
zugewiesen (Maintainer oder von ihm benannt) und prüft vierteljährlich drei Dinge:

1. den Revisionsstand und die Abkündigungsliste der **MCP-Spezifikation**,
2. die Fassungsliste der eingesetzten **Bibliothek** (`ProtocolVersions` in `mcp-core`),
3. das **Konfigurationsformat der vier Client-Anleitungen** im Handbuch (Claude Code, Cursor,
   OpenCode, VS Code) — das sich ändert, ohne dass irgendeine Version in diesem Repository steigt.

Das Ergebnis ist festgehalten, auch wenn nichts zu tun ist. **Renovate leistet das nicht**: Es
überwacht Bibliotheksversionen, keine Protokollfassungen und keine fremden Konfigurationsformate. Ein
Versionssprung des MCP-Starters ist deshalb **kein gewöhnliches Abhängigkeits-Update** — er gehört in
`renovate.json5` in eine Regel ohne Auto-Merge, wie die übrigen Abhängigkeiten mit Verhaltensrisiko
(#951, `docs/renovate.md`).

**Folgen.** Ein eigenes Störungsbild, das das Handbuch (#1722) führen muss: **alle** Fremdzugänge
fallen nach einem Client-Update gleichzeitig aus, und die Meldung sieht die Person in ihrem Werkzeug
(„MCP server failed") — eine Meldung, die OPAA weder formuliert hat noch beeinflussen kann. Ohne
dieses Bild in der Störungssuche wird das Ticket beim Betrieb als Einzelfall untersucht. Dazu eine
wiederkehrende Pflegelast, die bewusst in Kauf genommen wird: Sie ist der Preis dafür, einen fremden,
schnell laufenden Standard zu sprechen, statt eine eigene Schnittstelle zu definieren, die niemand
spricht.

## Konsequenzen

### Einfacher

- **Ein Kanal für alle Werkzeuge.** Ein neuer MCP-fähiger Client kostet eine Handbuchseite, keinen
  Code.
- **Eine Rechtewahrheit.** Weil alles über die vorhandenen Domain-Dienste läuft, erbt der Fremdzugang
  jede bestehende und jede künftige Sicherung des Suchwegs, ohne dass sie ein zweites Mal geschrieben
  wird.
- **Nachweisbarkeit ohne Verhaltensdaten.** Die Frage „wer konnte wann worauf zugreifen?" beantworten
  Rechtehistorie und Ereignisliste. Dafür ist **keine** Abfrageprotokollierung nötig — die Zusage aus
  `security-and-compliance.md` bleibt unangetastet, und der Kanal wird dadurch mitbestimmungsfähig.
- **Ein Notaus, der wirkt.** Weil je Aufruf geprüft wird und der Server keine Sitzungen führt, wirkt
  das Ausschalten mit der nächsten Anfrage — es gibt nichts, was ihn überdauern könnte, und es wird
  dabei kein Token vernichtet.
- **Zwei Endpunkte, die ohnehin fehlten.** Such- und Abrufweg schließen die Lücken, die
  `user-frontends.md` heute ausdrücklich als fehlend führt, und stehen auch angemeldeten Personen
  offen.

### Schwieriger

- **Ein einziger Ausfallpunkt für alle Fremdzugänge**, mit einem Störungsbild, das im fremden Client
  entsteht (Entscheidung 6).
- **Eine wiederkehrende, nicht automatisierbare Pflegelast** — Protokollfassungen und vier fremde
  Konfigurationsformate.
- **Ein unauthentifizierter Endpunkt in der Voreinstellung der Bibliothek.** Die Absicherung liegt
  vollständig bei uns; eine Lücke in der Filterkette ist hier keine Unbequemlichkeit, sondern eine
  offene Tür in den Bestand.
- **Ein weiterer Eintrag in der Single-Instance-Liste aus ADR-0021** — das Zählfenster des
  Abflussalarms, das bewusst im Arbeitsspeicher lebt. Der Server selbst trägt nichts bei; das ist der
  Gewinn der zustandsfreien Betriebsart.
- **Werkzeugbeschreibungen je Token sind keine Voreinstellung der Bibliothek.** `tools/list` kommt aus
  einer serverweiten Liste; die Beschreibung aus der effektiven Sicht verlangt einen eigenen Handler.
  Umsetzungsrisiko und Ausweg stehen in Entscheidung 1 und sind in #1721 zu entscheiden.
- **Eine wiederkehrende organisatorische Pflicht**: Jede Bibliotheksfreigabe läuft nach spätestens
  einem Jahr aus und will erneuert werden. Das ist beabsichtigt und trotzdem Arbeit.
- **Eine Freigabeentscheidung, die faktisch unumkehrbar ist.** Was einmal in ein fremdes Werkzeug
  geholt wurde, bleibt dort — auch wenn die Oberfläche später „aus" zeigt. Der Kanal macht die
  Entscheidung zurechenbar und wiederkehrend, nicht rückholbar.

### Neutral

- **Die Bibliotheksversion ist austauschbar.** Keine der sechs Entscheidungen hängt an Spring AI;
  Transport, Pfad, Authentifizierungsverfahren und Freigabemodell überleben einen Wechsel des
  Starters.
- **Kein zusätzlicher Betriebsmodus der Authentifizierung.** ADR-0005 bleibt unverändert: Der
  Fremdzugang ist ein weiterer Prüfweg in derselben Filterkette, kein dritter Modus neben `oidc` und
  `dev`.
- **Die Werkzeugnamen `search` und `fetch`** sind bewusst die Signatur, die gängige Assistenzwerkzeuge
  als Wissensquelle erkennen. Daraus folgt **keine** Zusage, dass OPAA an einen fremd betriebenen
  Dienst angeschlossen wird.

## Verworfene Alternativen

Die je Entscheidung betrachteten Alternativen stehen dort. Zusätzlich verworfen, weil sie den
Zuschnitt als Ganzes betreffen:

- **Ein OpenAI-kompatibler `/v1/chat/completions`.** Er wäre der bequemste Anschluss für viele
  Werkzeuge — und würde die Belege auf ein Textfeld reduzieren und die Modellvorgaben der
  Systemverwaltung umgehen. Der Kanal liefert Fundstellen; die Generierung findet im fremden Werkzeug
  mit dessen Modell statt.
- **Ein Werkzeug `ask`, das eine Antwort erzeugt.** Es hinge den Modellverbrauch der Installation an
  ein fremdes Werkzeug. Erst sinnvoll, wenn der Bedarf belegt ist.
- **Ein Nachweis der tatsächlich abgerufenen Fundstellen.** Der härteste Gegenfall ist echt (ein
  Bescheid beruht auf drei fehlerhaft zusammengefassten Fundstellen und landet vor Gericht), und die
  Entscheidung fällt trotzdem gegen den Nachweis: Ein Protokoll der abgerufenen Dokumente je Person
  ist das Tätigkeitsprofil, dessen Nichtexistenz die Mitbestimmungsfähigkeit dieses Produkts trägt.
  Die zweite Verarbeitungsstufe ist das fremde Werkzeug — **es führt sein eigenes Protokoll**, und wer
  den Weg eines Bescheids rekonstruieren muss, führt ihn dort.
- **Service-Accounts als eigene Identität ohne Person.** Bleiben Zielbild
  (`access-control.md`). Diese Stufe kennt nur persönliche Tokens; das Handbuch rät deshalb
  ausdrücklich davon ab, ein Fachverfahren an eines zu hängen.
- **Schreibende Fremdzugänge** — indizieren, hochladen, Rechte vergeben. Betrieblich etwas anderes als
  lesen, mit einer eigenen Missbrauchsfläche und einer eigenen Freigabefrage.

## Referenzen

- [`docs/features/external-access.md`](../features/external-access.md) — die Verhaltensbeschreibung
  dieses Kanals; dieser ADR hält nur die Architekturentscheidungen fest
- [ADR-0005](0005-authentication-strategy.md) — Betriebsmodi der Authentifizierung
- [ADR-0033](0033-lokale-benutzerverwaltung.md) — Ausstellung, Widerruf, Prüfkette, Mailwege
- [ADR-0021](0021-single-instance-betrieb.md) — prozesslokaler Zustand und die Single-Instance-Annahme
- [ADR-0016](0016-loeschschicksal-rechtehistorie.md) · [ADR-0032](0032-zeitquelle-rechtehistorie.md) —
  Löschschicksal und Zeitquelle der Rechtehistorie, in die das Freigabemerkmal einzieht
- [`docs/features/access-control.md`](../features/access-control.md) ·
  [`docs/features/security-and-compliance.md`](../features/security-and-compliance.md) ·
  [`docs/features/spaces-and-assets.md`](../features/spaces-and-assets.md) ·
  [`docs/features/user-frontends.md`](../features/user-frontends.md)
- Epic #1715 mit den dreizehn Entscheidungen vom 18.09.2026; dieses ADR ist Issue #1716
- MCP-Spezifikation `2026-07-28`: „Versioning and Compatibility", „Deprecated Features" (Registry),
  Feature-Lifecycle-Richtlinie SEP-2596
- Spring AI 2.0.1: „MCP Server Boot Starter", „Streamable-HTTP MCP Servers" und „Stateless
  Streamable-HTTP MCP Servers";
  `io.modelcontextprotocol.sdk:mcp-core` 2.0.x, `ProtocolVersions`
