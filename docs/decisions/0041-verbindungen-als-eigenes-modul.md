# ADR-0041: Verbindungen als eigenes Modul — Verbindungsprofile, Token-Speicher und verbundene Konten

## Status

**Vorgeschlagen (03.10.2026)**, Issue [#2159](https://github.com/criew/opaa/issues/2159), Epic
[#2147](https://github.com/criew/opaa/issues/2147). Grundlage ist die Spezifikation
[connector-connections.md](../features/connector-connections.md). Nachträge mit Verweis hierher
tragen [ADR-0025](0025-mehrere-oidc-anbieter.md), [ADR-0036](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md)
und [ADR-0038](0038-steckbare-konnektoren.md).

## Kontext

Die Spezifikation führt Bausteine ein, die es heute nicht gibt: Verbindungsprofile (auch ohne
Konnektor, Profiltyp „MCP-Server“), Sperre und Profilpflicht je Konnektortyp, Verbindungen mit
Besitzer Bibliothek oder Person (verbundenes Konto), einen verschlüsselten Token-Speicher, den
OAuth-Ablauf und ein eigenes Verbindungsprotokoll. Diese Bausteine brauchen die Freigabe (rights),
die Konnektor-Beschreibung (knowledge) und den Kontolebenszyklus (identity). Umgekehrt brauchen
Indexierungslauf, Konnektor und Bibliotheksverwaltung das Ergebnis, also Ziel und Geheimnis. Der
Modulgraph (`ModularArchitecture`, `backend/AGENTS.md`) ist zyklenfrei und soll es bleiben.

## Entscheidung

### 1. Ein neues logisches Modul `connections`, Paket `io.opaa.connection`

Das Modul besitzt Profile, die Richtlinie je Konnektortyp (Sperre, Profilpflicht), Verbindungen
samt verbundenen Konten, den Token-Speicher, den OAuth-Ablauf, das Verbindungsprotokoll und die
Einstellungen der Fristen (Entscheidung 7). Die Freigabe selbst liegt nicht dort, sondern in rights
(ADR-0036, Nachtrag vom 03.10.2026).

Ein bestehendes Modul passt nicht:

| Modul | Warum nicht |
|---|---|
| identity (`auth`, `account`) | liegt unter rights und knowledge; Profile brauchen Freigabe und Konnektor-Beschreibung, also Kanten nach oben |
| rights | dasselbe: Profile prüfen gegen die `SourceConnectorRegistry` (knowledge) |
| knowledge (`indexing.source`) | MCP-Profile und verbundene Konten sind kein Bestand; OAuth, Kontolebenszyklus und Protokoll wären fachfremd im größten Modul |
| connectors | darf nur foundation, format und knowledge kennen, und niemand kennt einen Konnektor |
| library | besitzt keine Tabelle, und der spätere MCP-Client müsste für ein Token die Bibliotheksverwaltung kennen |

### 2. Lage im Modulgraphen

```
library ──► connections ──► knowledge ──► rights ──► identity ──► foundation
connectors ──► knowledge        (kein Pfad zu connections)
knowledge deklariert den Port, connections implementiert ihn (keine Kante nach oben)
```

| Kante | Zweck |
|---|---|
| connections → foundation | `CredentialsEncryptor`, `TargetAddressValidator`, HTTP über `sourceaccess` |
| connections → identity | `User`, `AccountUsability`, Kontoereignisse aus `account` und `auth`, Revisionsprotokoll und `AuditAccessGate` |
| connections → rights | Freigabe (`CapabilityService` mit Geltungsbereich) |
| connections → knowledge | `SourceConnectorRegistry`, Port des Kerns, Bibliotheksbezug der Verbindung |
| library → connections | Anlegen, Verbindungstest und Auflistung prüfen Freigabe, Sperre und Profilpflicht und holen das Geheimnis; der Löschlauf liest Frist und Beginn |
| assistant/external → connections | erst mit dem MCP-Client (Folge-Epic hinter #1747), eingetragen, wenn der Code sie nutzt |

**Ausgeschlossen:** knowledge → connections, connectors → connections, connections → library,
workspace, assistant oder external. Die Reihenfolge in `Module` schließt die beiden ersten aus,
`ALLOWED_MODULE_EDGES` die übrigen.

### 3. Ein Konnektor erreicht ein Token nur über den Kern

- knowledge deklariert in `io.opaa.indexing.source` einen Port (Arbeitsname
  `SourceConnectionResolver`), connections implementiert ihn. Muster: `FolderDocumentDeleter`.
- Der Kern fragt ihn vor jedem Lauf und vor jedem Abruf eines Originals (`RemoteOriginalAccess`).
  Die Antwort ist entweder eine Sperre mit Kategorie (gesperrt, ruhend, abgelaufen, Zugang
  entfernt, Konto nicht nutzbar) oder Ziel und nutzbares Geheimnis. Ohne Verbindung gelten die
  Felder der Bibliothek wie heute; die Sperre des Konnektortyps prüft der Port trotzdem.
- Vor jeder Herausgabe eines Geheimnisses einer Person prüft der Port die Nutzbarkeit ihres Kontos
  (Entscheidung 4).
- **Ein Konnektor sieht nie Profil, Refresh-Token, Client-Secret oder den OAuth-Ablauf.**
- Die Erneuerung läuft im Port: höchstens eine je Verbindung (Zeilensperre), ein rotierter
  Refresh-Token wird in derselben Transaktion ersetzt.
- Verbindungstest und Auflistung vor dem Speichern laufen über library, das connections direkt
  fragt. Der Konnektor bekommt das Geheimnis auch dort nur über `SourceSettings`.

### 3a. Umbau der Lauf-SPI: Konnektoren lesen nichts mehr selbst aus der Bibliothek

Heute bekommt ein Konnektor im Lauf die Entität (`IndexingRun#library()`) und liest Adresse,
Proxy, TLS-Schalter, Zugangsdaten und Einstellungen selbst, etwa in `ConfluenceLibraryConnection`,
`S3LibraryConnection`, `RssFeedIndexingExecutor` und `UrlIndexingExecutor`. Damit hinge die Zusage
aus Entscheidung 3 nicht am Port. Deshalb:

- Lauf, Originalabruf und die Verwaltungsmethoden von `SourceConnector` bekommen die Einstellungen
  aufgelöst vom Kern: `SourceSettings` mit Ziel, Proxy, TLS-Schalter, Geheimnis mit Art
  (persönliches Geheimnis oder Zugriffstoken) und Konnektor-Einstellungen. Die Konnektor-Einstellungen
  sind bei Profilen mit den Vorgaben des Profils zusammengeführt.
- Die Entität dient dem Konnektor nur noch für Identität und Bestand.
- **ArchUnit-Regel:** Keine Klasse eines Konnektorpakets ruft `KnowledgeLibrary#getSourceCredentials`,
  `#getSourceUrl`, `#getSourceProxy`, `#isSourceInsecureSsl`, `#getSourceSettings`,
  `#getWebhookSecret` oder `ConnectorData#storedIn` auf. Ausgenommen ist `#getSourcePath`: Das
  Dateisystem verbietet Profile, und der Pfad ist kein Geheimnis.
- Der Umbau ist ein eigenes, vorbereitendes Issue vor #2160:
  [#2178](https://github.com/criew/opaa/issues/2178). Bis #2160 löst ein Übergangs-Resolver im
  Kern die Felder der Bibliothek wie heute auf, sodass sich nichts sichtbar ändert.

### 4. Lebenszyklus: eine zentrale Abfrage, Ereignisse beschleunigen nur

Ereignisse allein reichen nicht. `LocalAccountAccessEndedEvent` gibt es nur für die Sperre eines
lokalen Kontos, die Übergabe und die Verzeichnissperre. Für den Ablauf eines befristeten Kontos und
für das Deaktivieren eines OIDC-Anbieters fehlt es, und die Sperre bei einem Anbieter ohne
Verzeichnis-Konnektor erfährt OPAA gar nicht.

- **Zentrale Abfrage in identity** (`io.opaa.auth`, Arbeitsname `AccountUsability`): Ist das Konto
  jetzt nutzbar? Sie führt die heute verstreuten Quellen zusammen, nach dem Muster von
  `ExternalAccessTokenAuthenticator`:

  | Zustand | Ergebnis |
  |---|---|
  | lokal gesperrt (`LocalCredentials`), außer der vorübergehenden Sperre nach Fehlversuchen (`FAILED_LOGINS`) | deaktiviert |
  | befristetes lokales Konto abgelaufen (`LocalAccountState.EXPIRED`) | deaktiviert |
  | Verzeichnissperre (`User#isDirectoryLocked`) | deaktiviert |
  | Anbieter des Issuers gelöscht | deaktiviert |
  | Anbieter des Issuers deaktiviert | ruht |
  | sonst | nutzbar, mit dem Zeitpunkt der letzten Aktivität (`users.last_login_at`) |

  `AccountState` (rights) ist keine Quelle, weil dort nur der Verzeichnisabgleich schreibt.
- **Prüfung vor jeder Herausgabe:** Der Port (Entscheidung 3) gibt das Geheimnis einer Person nur
  heraus, wenn ihr Konto nutzbar ist. So wirkt jede Deaktivierung spätestens beim nächsten Lauf.
- **Täglicher Abgleich in connections:**
  - Deaktivierte Konten: Token und Geheimnisse löschen, beim Anbieter widerrufen.
  - Ruhende Konten: Verbindungen ruhen lassen.
  - Nutzbare Konten: hebt die Ruhe auf.
  - connections hält je Konto fest, seit wann es nicht nutzbar ist; dieser Zeitpunkt beginnt die
    Löschfrist.
- **Ereignisse lösen den Abgleich eines Kontos sofort aus**, in der Transaktion des Auslösers:
  `LocalAccountAccessEndedEvent` für das betroffene Konto, `OidcProvidersChangedEvent` für alle
  Konten (das Ereignis trägt keinen Anbieter). Der Widerruf beim Anbieter läuft nach dem Commit als
  Versuch und hält die Sperre nie auf.
- **Die Übergabe eines lokalen Kontos (ADR-0033, Entscheidung 12) ist keine Deaktivierung.** Weil
  das Ereignis nur die Abfrage auslöst und das übergebene Konto danach nutzbar ist, fällt die
  Übergabe ohne eigenen Anlass heraus. Das gilt für connections und library gleich.
- **Private Bibliotheken:** library hat keinen eigenen Listener, sondern einen Löschlauf. Er löscht,
  wenn ein Konto seit länger als die Löschfrist nicht nutzbar ist. Die Frist und den Beginn liest er
  aus connections. Ist das Konto beim Lauf wieder nutzbar, löscht er nicht. Läufe privater
  Bibliotheken stoppen schon vorher, weil der Port kein Geheimnis herausgibt.
- **Kontolöschung:** Ein Konto mit Verbindungen oder privaten Bibliotheken ist benutzt und wird
  nach ADR-0033 (Entscheidung 11) gesperrt, nicht gelöscht. Die Bibliotheken blockieren als Assets
  die Löschung bereits heute (`countDeletionBlockers`). Token und Geheimnisse einer Person hängen
  mit `ON DELETE CASCADE` an `users`.
- Trennen, Notabschaltung und gelöschte Profile erreichen die Bibliothek ohne Ereignis: Der Port
  meldet beim nächsten Lauf „ruhend“ oder „Zugang entfernt“.
- **Nach dem Einspielen einer Sicherung** läuft der Abgleich beim Start: Token nicht nutzbarer
  Konten löschen, abgelaufene zählen.

**Restlücke: OIDC-Konten ohne Verzeichnis-Konnektor.** Deaktiviert ein Anbieter eine Person, erfährt
OPAA das nicht (`access-control.md`: serverseitig gibt es dafür keine Operation). Ihre Verbindungen
blieben nutzbar, solange die Abfrage sie für nutzbar hält.

**Vorschlag, Entscheidung beim Maintainer:** eine Inaktivitätsschwelle für Verbindungen.
- Ohne Aktivität an OPAA seit N Tagen (`users.last_login_at`) ruhen die Verbindungen der Person:
  Läufe pausieren, das Token bleibt gespeichert, aber der Port gibt es nicht heraus.
- Mit der nächsten Anmeldung geht es ohne Neuverbinden weiter. Gelöscht wird erst bei echter
  Deaktivierung.
- Die Schwelle gilt für alle Kontoarten, wirkt praktisch aber nur bei OIDC-Konten ohne Verzeichnis,
  weil lokale Konten nach `local_auth_settings.inactive_days` ohnehin gesperrt werden.
- Werte: Vorgabe 90 Tage, einstellbar von 30 bis 365 Tagen, als Einstellung der Installation. 90
  Tage entsprechen der Vorgabe der lokalen Inaktivitätssperre.
- Die Schwelle stoppt die Indexierung, aber nicht den Fortbestand des Tokens. Den schließt nur der
  Verzeichnis-Konnektor (ADR-0036, Entscheidung 3), dessen Einsatz das Handbuch für Häuser mit
  verbundenen Konten empfiehlt.

### 5. MCP-Profil

Ein Profil hat eine Art: `CONNECTOR` mit genau einem Konnektortyp oder `MCP_SERVER` ohne Konnektor.
Für ein MCP-Profil gibt es keine Konnektor-Beschreibung. connections prüft selbst: nur Streamable
HTTP, Adressprüfung, Anmeldeart OAuth nach der MCP-Autorisierung mit Ressourcen-Indikator je
Server. Ein verbundenes Konto darauf ist dieselbe Verbindung im selben Token-Speicher. Die Freigabe
ist nicht `CREATE_CONNECTOR_LIBRARY`; ihre Form legt das Folge-Epic fest. Der spätere MCP-Client
hängt von connections ab, nie umgekehrt.

### 6. Private Bibliothek und Nur-Besitzerin-Regel

Das Merkmal „nur Besitzerin“ sitzt an der Asset-Schale in rights, nicht in connections. So
erzwingen Grant-Pfad, „Sicht als“, Nachfolge und Space-Zuordnung die Regel, ohne connections zu
kennen. Einzelheiten stehen im Nachtrag zu ADR-0036. Eine private Bibliothek hängt an genau einer
Verbindung, dem verbundenen Konto ihrer Besitzerin.

### 7. Verbindungsprotokoll und Fristen

- Das Verbindungsprotokoll ist eine eigene, nur anfügbare Tabelle in connections, nach dem Muster
  von `audit_log` (ADR-0015). Lesen darf nur `AUDITOR`, über `AuditAccessGate` mit Anlass,
  begrenztem Zeitraum und eigenem Zugriffseintrag. Die Begründung steht im Nachtrag zu ADR-0036.
- Vergabe, Entzug, Sperre sowie Anlegen, Ändern und Löschen von Profilen sind Governance-Ereignisse
  im Revisionsprotokoll, nicht im Verbindungsprotokoll.

| Wert | Grenzen | Vorgabe | Ablage |
|---|---|---|---|
| Löschfrist privater Bibliotheken nach Deaktivierung | 1–90 Tage | 30 Tage | Einstellung der Installation, Grenzen als `CHECK` |
| Aufbewahrung des Verbindungsprotokolls | 6–24 Monate | 12 Monate | Einstellung der Installation, Grenzen als `CHECK` |
| Warnung vor Token- und Secret-Ablauf | — | 14 Tage | fest (Spezifikation) |
| Inaktivitätsschwelle für Verbindungen (**Vorschlag**, Entscheidung 4) | 30–365 Tage | 90 Tage | Einstellung der Installation, Grenzen als `CHECK` |

Begründung:

- **Löschfrist:** Der Inhalt liegt weiter beim Anbieter und lässt sich nach einer Reaktivierung neu
  indexieren. Eine kurze Frist kostet also nur einen Lauf. 30 Tage decken eine irrtümliche Sperre
  ab. 90 Tage sind die Obergrenze, weil Personalrat und Datenschutz eine Frist verlangen, die keine
  Installation dehnen kann.
- **Aufbewahrung:** Das Protokoll belegt die Nutzung persönlicher Verbindungen, nicht Rechte. Die
  Rechte belegen Rechtehistorie und Revisionsprotokoll mit 12–120 Monaten. Zwölf Monate decken
  einen jährlichen Prüfzyklus ab, 24 Monate sind die Obergrenze.
- Die Abstimmung mit Personalrat und Datenschutz kann die Vorgaben innerhalb der Grenzen ändern,
  ohne dass dieser ADR geändert werden muss.

### 8. Was #2178 und #2160 im Code anlegen

- **#2178, vorher:** Umbau der Lauf-SPI und die ArchUnit-Regel aus Entscheidung 3a, mit
  Negativfall in `ModularArchitectureFixtureTest`.
- **#2160:** Die zentrale Abfrage `AccountUsability` in `io.opaa.auth` (Entscheidung 4) entsteht
  mit dem ersten Konsumenten. `ExternalAccessTokenAuthenticator` stellt auf sie um, damit es nur
  eine Regel gibt.
- **Spezifikation zuerst (ADR-0006),** für den Geltungsbereich der Fähigkeit (ADR-0036, Nachtrag
  vom 03.10.2026, Punkt 1):
  - `GET /api/v1/me` führt `CREATE_CONNECTOR_LIBRARY` weiter als Zeichenkette, wenn die Person die
    Fähigkeit in mindestens einem Geltungsbereich hat. Die heutige Grobsteuerung im Frontend
    (`LibraryCreatePage.tsx`, `assetTypeRegistry.ts`) bleibt dadurch gültig.
  - `GET /api/v1/source-types` und die Liste der Zugänge tragen je Eintrag, ob die Person dort
    anlegen darf, mit Hinweis statt totem Weg. Danach richtet sich die Kachel im Assistenten.
  - Die Prüfungen ohne Geltungsbereich (`SourceConnectionTestService`, `KnowledgeLibraryService`)
    nennen danach Typ oder Profil.
- `ModularArchitecture`: `Module.CONNECTIONS` direkt nach `CONNECTORS` und `entry("connection",
  CONNECTIONS)` in `MODULES`. In `LAYERS` steht `"connection"` zwischen `"indexing"` und
  `"library"`. Kanten in `ALLOWED_MODULE_EDGES` erst eintragen, wenn der Code sie nutzt
  (`everyAllowedModuleEdgeIsInUse`): CONNECTIONS → FOUNDATION, IDENTITY, RIGHTS, KNOWLEDGE;
  LIBRARY → CONNECTIONS.
- Unterpakete, unten zuerst: `connection.log`, `connection.token`, `connection.profile`,
  `connection.oauth`. Das Wurzelpaket verdrahtet und implementiert den Port, `connection.web`
  liegt darüber. Kein Unterpaket nennt das Wurzelpaket, und `subpackagesAreFreeOfCycles` gilt
  ohne neuen Eintrag in `KNOWN_SUBPACKAGE_CYCLE_EDGES`.
- Liquibase: Verzeichnis `db/changelog/connections/` im Master an der Stelle von `CONNECTIONS`. Die
  neuen Tabellen kommen in `ChangelogModuleBoundaryTest.TABLE_MODULES`. Fremdschlüssel zeigen nur
  auf identity, rights und knowledge, nie umgekehrt; die Verbindung einer Bibliothek steht in
  connections, nicht in `knowledge_libraries`.
- Neu: `io/opaa/connection/AGENTS.md` mit `CLAUDE.md`, erfasst von `ModuleAgentsFileTest`.
- Ergänzt werden:
  - `backend/AGENTS.md`: Modultabelle, Kantenliste, Tabelle „Anweisungen je Modul“, Liste der
    Changelog-Verzeichnisse
  - `knowledge/AGENTS.md`: neuer Port
  - `indexing/source/AGENTS.md`: Ziel, Geheimnis und Einstellungen nur über `SourceSettings`
    (schon mit #2178), vier Plätze, Ziel aus dem Profil
  - `permission/AGENTS.md`: Geltungsbereich der Fähigkeit, Merkmal „nur Besitzerin“
  - `library/AGENTS.md`: Kante zu connections, Löschlauf privater Bibliotheken
  - `auth/AGENTS.md`: `AccountUsability` als einzige Regel „Konto nutzbar“

## Verworfene Alternativen

- **connections unter knowledge** (zwischen rights und knowledge): Der Kern könnte das Token direkt
  holen. Dann bräuchte connections aber Ports für Konnektor-Beschreibung, Profilvorgaben und das
  Zählen betroffener Bibliotheken, also mehr Rückwege als ein Port im Kern.
- **connections über library:** library bräuchte Ports für Freigabe, Sperre und Profilpflicht beim
  Anlegen. Den Lebenszyklus privater Bibliotheken trägt library ohnehin selbst.
- **Freigabe als eigene Tabelle am Profil:** Sie würde Subjekte, Auswertung, Historie,
  Governance-Ereignis und `/me` doppeln. Stattdessen bekommt die Fähigkeit einen Geltungsbereich.
- **Verbindung als Spalte in `knowledge_libraries`:** Das wäre ein Fremdschlüssel von knowledge
  nach oben.
- **Lebenszyklus über ein eigenes Ereignis von connections an library:** Das wäre eine Kante in
  Gegenrichtung. library liest stattdessen über die erlaubte Kante den Beginn der Frist.
- **Lebenszyklus nur über Ereignisse:** Für den Ablauf befristeter Konten, deaktivierte Anbieter
  und Anbieter ohne Verzeichnis gibt es keine Ereignisse. Ein vergessener Pfad bliebe unbemerkt.
- **`AccountState` als Quelle für „gesperrt“:** Dort schreibt nur der Verzeichnisabgleich, die
  Sperre lokaler Konten fehlt.
- **Verbundene Konten nur für Konten mit bekanntem Zustand** (lokal oder Verzeichnis): Das ist
  sicher, schließt aber jedes Haus mit reinem OIDC aus. Die Inaktivitätsschwelle ist der mildere
  Vorschlag, die Entscheidung liegt beim Maintainer.

## Konsequenzen

- **Einfacher:** Ein neuer Konnektor meldet Profilangabe, Anmeldearten und OAuth-Endpunkte in seiner
  Beschreibung und braucht keinen Code außerhalb seines Pakets. Token und Client-Secret erreichen
  keinen Konnektor.
- **Schwieriger:** ein Modul und ein Changelog-Verzeichnis mehr. Der Kern hängt bei jedem Lauf an
  einem Port, dessen Antwort auch Netzzugriffe enthalten kann (Erneuerung). Vor #2160 steht der
  Umbau der Lauf-SPI (#2178): etwa 14 Hauptklassen in 4 Konnektoren und im Kern, dazu etwa 30
  Testklassen.
- **Restrisiko:** Bei OIDC-Konten ohne Verzeichnis-Konnektor bleibt ein Token nach einer
  Deaktivierung beim Anbieter gespeichert, bis die Schwelle greift oder das Konto in OPAA gesperrt
  wird (Entscheidung 4).
- **Neutral:** Ereignisse laufen in der Transaktion des Auslösers. Fällt ein Listener, scheitert die
  Sperre; das ist dieselbe Zusage wie bei den Fremdzugangstokens.

## Referenzen

- [connector-connections.md](../features/connector-connections.md)
- [ADR-0015](0015-eigentuemertrennung-protokollablage.md), [ADR-0021](0021-single-instance-betrieb.md),
  [ADR-0033](0033-lokale-benutzerverwaltung.md), [ADR-0035](0035-fremdzugaenge-mcp-server-und-zugangstokens.md)
- `backend/AGENTS.md`, „Logische Module und Schichtung“; `ModularArchitectureTest`
