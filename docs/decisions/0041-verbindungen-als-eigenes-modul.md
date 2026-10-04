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
  `#getSourceUrl`, `#getSourceProxy`, `#isSourceInsecureSsl`, `#getSourceSettings` oder
  `ConnectorData#storedIn` auf. Zwei Ausnahmen:
  - `#getSourcePath`, weil das Dateisystem Profile verbietet und der Pfad kein Geheimnis ist.
  - `#getWebhookSecret`, weil das Push-Geheimnis an der Bibliothek bleibt und nicht am Profil
    hängt. Die Push-Adapter (`ConfluenceWebhookService`, `S3EventService`) lesen es weiter selbst.
- Der Umbau ist ein eigenes, vorbereitendes Issue vor #2160:
  [#2178](https://github.com/criew/opaa/issues/2178). Bis #2160 löst ein Übergangs-Resolver im
  Kern die Felder der Bibliothek wie heute auf, sodass sich nichts sichtbar ändert.

### 4. Lebenszyklus: eine zentrale Abfrage, Ereignisse beschleunigen nur

Ereignisse allein reichen nicht. `LocalAccountAccessEndedEvent` gibt es nur für die Sperre eines
lokalen Kontos, die Übergabe und die Verzeichnissperre. Für den Ablauf eines befristeten Kontos und
für das Deaktivieren eines OIDC-Anbieters fehlt es, und die Sperre bei einem Anbieter ohne
Verzeichnis-Konnektor erfährt OPAA gar nicht.

- **Zentrale Abfrage in identity** (`io.opaa.auth`, Arbeitsname `AccountUsability`): Ist das Konto
  jetzt nutzbar? Vorbild ist `LocalAdminAvailabilityGuard#isLoginCapable`, die heute vollständigste
  Regel: Verzeichnissperre vor allem anderen, lokaler Issuer über `LocalAccountAccess`, Dev-Issuer
  und Anbieter über `OidcIssuerUris.normalize`, nur `ProviderType.OIDC`; vom Anbieter `LOCAL`
  zählt nur sein Schalter.

  | Zustand | Ergebnis |
  |---|---|
  | lokal gesperrt (`LocalCredentials`), außer der vorübergehenden Sperre nach Fehlversuchen (`FAILED_LOGINS`) | deaktiviert |
  | befristetes lokales Konto abgelaufen (`LocalAccountState.EXPIRED`) | deaktiviert |
  | Verzeichnissperre (`User#isDirectoryLocked`) | deaktiviert |
  | kein Anbieter mehr zum normalisierten Issuer (gelöscht), außer dem Dev-Issuer | deaktiviert |
  | Anbieter des Issuers deaktiviert | ruht |
  | reguläres lokales Konto (nicht `SYSTEM_ADMIN`) bei abgeschalteter lokaler Kontenverwaltung | ruht |
  | ohne Aktivität seit der Inaktivitätsschwelle (`users.last_login_at`) | ruht |
  | sonst | nutzbar |

  `AccountState` (rights) ist keine Quelle, weil dort nur der Verzeichnisabgleich schreibt.
- **Eine Regel statt drei:** `LocalAdminAvailabilityGuard` und `ExternalAccessTokenAuthenticator`
  stellen auf die Abfrage um. Für den Authenticator ändert sich dabei Verhalten: Fremdzugangstokens
  von Personen eines deaktivierten oder gelöschten Anbieters werden künftig abgelehnt, wie heute
  schon deren Anmeldung. Die Inaktivitätsschwelle gilt dort nicht, weil sie eine Regel für
  Verbindungen ist. Die Abfrage meldet „ruht wegen Inaktivität“ deshalb gesondert.
- **Ruhen ist keine Deaktivierung** (Beschluss 15). „Ruht“ stoppt Läufe und die Herausgabe von
  Geheimnissen, löscht aber nichts und startet keine Löschfrist. Mit dem nächsten Zustand „nutzbar“
  geht es ohne Neuverbinden weiter.
- **Deaktivierter Anbieter zählt als Ruhen.** Ein vorübergehendes Abschalten, etwa für eine
  Migration, soll nicht massenhaft löschen. Erst das Löschen des Anbieters deaktiviert dessen
  Konten.
  - Vor dem Deaktivieren nennt die Anbieterverwaltung der Systemverwaltung die Zahl der ruhenden
    Verbindungen und privaten Bibliotheken.
  - Vor dem Löschen nennt sie zusätzlich, dass für deren private Bibliotheken die Löschfrist
    beginnt.
- **Prüfung vor jeder Herausgabe:** Der Port (Entscheidung 3) gibt das Geheimnis einer Person nur
  heraus, wenn ihr Konto nutzbar ist. So wirkt jede Deaktivierung und jedes Ruhen spätestens beim
  nächsten Lauf.
- **Täglicher Abgleich in connections:**
  - Deaktivierte Konten: Token und Geheimnisse löschen, beim Anbieter widerrufen. connections hält
    fest, seit wann das Konto deaktiviert ist; nur dieser Zeitpunkt beginnt die Löschfrist.
  - Ruhende Konten: Verbindungen ruhen lassen, ohne Frist.
  - Nutzbare Konten: Ruhe und Fristbeginn aufheben.
- **Ereignisse lösen den Abgleich sofort aus, nach dem Commit** des Auslösers
  (`@TransactionalEventListener(AFTER_COMMIT)`):
  - `LocalAccountAccessEndedEvent` für das betroffene Konto.
  - `OidcProvidersChangedEvent` für alle Konten. Das Ereignis trägt keinen Anbieter und kommt auch
    beim Umbenennen und beim Start (`LocalAdminSeeder`).
  - Eine Anbieteränderung hängt so nie am Abgleich aller Verbindungen. Die Herausgabesperre im Port
    wirkt ohnehin ab dem Commit.
  - Fällt der Abgleich aus, holt ihn der tägliche Lauf nach. Der Widerruf beim Anbieter ist ein
    Versuch.
- **Die Übergabe eines lokalen Kontos (ADR-0033, Entscheidung 12) ist keine Deaktivierung.** Weil
  das Ereignis nur die Abfrage auslöst und das übergebene Konto danach nutzbar ist, fällt die
  Übergabe ohne eigenen Anlass heraus. Das gilt für connections und library gleich.
- **Private Bibliotheken:** library hat keinen eigenen Listener, sondern einen Löschlauf. Er löscht,
  wenn ein Konto seit länger als die Löschfrist **deaktiviert** ist; ein ruhendes Konto zählt nicht.
  Frist und Beginn liest er aus connections. Ist das Konto beim Lauf nicht mehr deaktiviert, löscht
  er nicht. Läufe privater Bibliotheken stoppen schon vorher, weil der Port kein Geheimnis
  herausgibt.
- **Kontolöschung:** Ein Konto mit Verbindungen oder privaten Bibliotheken ist benutzt und wird
  nach ADR-0033 (Entscheidung 11) gesperrt, nicht gelöscht. Die Bibliotheken blockieren als Assets
  die Löschung bereits heute (`countDeletionBlockers`). Token und Geheimnisse einer Person hängen
  mit `ON DELETE CASCADE` an `users`.
- Trennen, Notabschaltung und gelöschte Profile erreichen die Bibliothek ohne Ereignis: Der Port
  meldet beim nächsten Lauf „ruhend“ oder „Zugang entfernt“.
- **Nach dem Einspielen einer Sicherung** läuft der Abgleich beim Start: Token deaktivierter Konten
  löschen, abgelaufene zählen.

**Inaktivitätsschwelle** (Beschluss 15): Ohne Anmeldung seit 90 Tagen ruhen die Verbindungen einer
Person (einstellbar 30–365, Einstellung der Installation).
- Sie gilt für alle Kontoarten. Praktisch wirkt sie bei OIDC-Konten ohne Verzeichnis, weil lokale
  Konten nach `local_auth_settings.inactive_days` ohnehin gesperrt werden.
- 90 Tage entsprechen der Vorgabe der lokalen Inaktivitätssperre.

**Restlücke: OIDC-Konten ohne Verzeichnis-Konnektor.** Deaktiviert ein Anbieter eine Person, erfährt
OPAA das nicht (`access-control.md`: serverseitig gibt es dafür keine Operation).
- Die Schwelle stoppt die Indexierung nach spätestens 90 Tagen, löscht das Token aber nicht.
- Das schließt nur der Verzeichnis-Konnektor (ADR-0036, Entscheidung 3). Das Handbuch empfiehlt ihn
  für Häuser mit verbundenen Konten.

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
| Inaktivitätsschwelle für Verbindungen (Entscheidung 4) | 30–365 Tage | 90 Tage | Einstellung der Installation, Grenzen als `CHECK` |

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
  mit dem ersten Konsumenten. `LocalAdminAvailabilityGuard` und `ExternalAccessTokenAuthenticator`
  stellen auf sie um, damit es nur eine Regel gibt. Die Verhaltensänderung des Authenticators steht
  in Entscheidung 4.
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
  sicher, schließt aber jedes Haus mit reinem OIDC aus. Entschieden ist der mildere Weg, die
  Inaktivitätsschwelle (Beschluss 15).
- **Deaktivierter Anbieter als Deaktivierung seiner Konten:** Ein vorübergehendes Abschalten würde
  nach der Löschfrist massenhaft private Bibliotheken löschen.

## Konsequenzen

- **Einfacher:** Ein neuer Konnektor meldet Profilangabe, Anmeldearten und OAuth-Endpunkte in seiner
  Beschreibung und braucht keinen Code außerhalb seines Pakets. Token und Client-Secret erreichen
  keinen Konnektor.
- **Schwieriger:** ein Modul und ein Changelog-Verzeichnis mehr. Der Kern hängt bei jedem Lauf an
  einem Port, dessen Antwort auch Netzzugriffe enthalten kann (Erneuerung). Vor #2160 steht der
  Umbau der Lauf-SPI (#2178): etwa 14 Hauptklassen in 4 Konnektoren und im Kern, dazu etwa 30
  Testklassen.
- **Restrisiko:** Bei OIDC-Konten ohne Verzeichnis-Konnektor bleibt ein Token nach einer
  Deaktivierung beim Anbieter gespeichert, bis das Konto in OPAA gesperrt wird. Die Schwelle stoppt
  nach 90 Tagen nur die Nutzung (Entscheidung 4).
- **Verhaltensänderung:** Fremdzugangstokens von Personen eines deaktivierten oder gelöschten
  Anbieters werden abgelehnt (Entscheidung 4).
- **Neutral:** Der Abgleich läuft nach dem Commit des Auslösers. Eine Sperre scheitert nie an ihm,
  und bis zum Abgleich hält die Herausgabesperre im Port.

## Nachtrag vom 04.10.2026: Verbindungsprotokoll angelegt (#2163)

- `connection.log` ist das unterste Unterpaket: `CONNECTION_PACKAGES` beginnt mit
  `connection.log`, dann `connection.profile`. Es nennt kein anderes Unterpaket, damit Profil,
  Token-Speicher und verbundene Konten hineinschreiben können, ohne einen Zyklus zu bilden.
- Die Wertebereiche liegen noch tiefer, in `io.opaa.api.types`: `ConnectionLogEventType` und
  `ConnectionEndCause`. Der Endanlass ist derselbe Wert, den das verbundene Konto später als Grund
  seines Endes speichert.
- Tabelle `connection_log` und Löschung folgen dem Muster von `audit_log` (ADR-0015): monatliche
  Partitionen im Besitz von `opaa_audit_owner`, die Anwendung hat nur `INSERT` und `SELECT`, die
  Löschfunktion `opaa_connection_log_delete_expired_partitions()` ist `SECURITY DEFINER`. Sie ist
  eine eigene Funktion je Tabelle: Eine gemeinsame Funktion mit Tabellenparameter könnte das
  Anwendungskonto mit einem fremden Protokoll aufrufen. Der Lauf ist täglich.
- Jedes Ereignis außer `CONNECTED` und `RECONNECTED` trägt einen Endanlass, die beiden nie
  (`CHECK`). Die Anlässe sind vollständig: `SELF, EMERGENCY, ADDRESS_CHANGED, REGISTRATION_CHANGED,
  ACCOUNT_DEACTIVATED, PROFILE_DELETED, PROVIDER_REJECTED, SECRET_EXPIRED`.
- Lesen: `ConnectionLogQueryService` hinter `AuditAccessGate`, Rolle `AUDITOR`, jeder Abruf als
  `CONNECTION_LOG_ACCESSED` im Revisionsprotokoll. Die Frist (Vorgabe 12, Grenzen 6–24 Monate als
  `CHECK`) ändert die Systemverwaltung wie die Fristen von Rechtehistorie und Suchdiagnose über
  `/api/v1/admin/connection-log/retention`; jede Änderung ist `CONNECTION_LOG_RETENTION_CHANGED`.
- `ConnectionLog#record` tritt der Transaktion des Aufrufers bei. Ein Aufrufer nach dem Commit
  (`AFTER_COMMIT`-Listener des Lebenszyklus) braucht eine eigene Transaktion.

## Nachtrag vom 04.10.2026: Token-Speicher und verbundene Konten (#2163)

- **Paketreihenfolge, unten zuerst:** `connection.log`, `connection.token`, `connection.profile`,
  `connection.account`, später `connection.oauth`. Der Speicher liegt unter den Profilen, damit
  Adress- und Registrierungswechsel, Notabschaltung und Löschung des Profils verwerfen können; die
  verbundenen Konten liegen darüber, weil sie Profil, Sperre und Freigabe brauchen. `oauth` liegt
  zuoberst, weil der Abschluss einer Zustimmung ein Konto anlegt.
- **Ports statt Kanten nach oben:** `connection.token` deklariert `SecretIssuer` (Erneuerung und
  Widerruf eines OAuth-Tokens, implementiert von `connection.oauth`), `LibrariesOnProfile` und
  `PersonAccounts`; `connection.profile` deklariert `PersonConnections`, über den die
  Profilverwaltung die Konten eines Zugangs zählt und beendet. Ohne `SecretIssuer` kann kein
  OAuth-Token herausgegeben werden.
- **Der Speicher meldet Gründe, keine Texte:** `ConnectionSecrets#current` wirft
  `SecretRefusedException` mit dem Sperrgrund; den Hinweis formuliert weiter nur `SourceBlocks`,
  das über dem Speicher liegt.
- **Neue Sperrgründe** in `SourceBlock.Reason`: `OWNER_DEACTIVATED` und `DORMANT` nach
  `ACCESS_REMOVED`, `EXPIRED` nach `NOT_CONNECTED`; alle drei beenden einen laufenden Lauf und
  erscheinen in der Antwort, keiner ist eine Sperre der Verwaltung. „Ruhend“ und „deaktiviert“ werden
  bei jeder Herausgabe aus `AccountUsability` abgeleitet, mit der Inaktivitätsschwelle als Konstante
  (90 Tage), bis der Lebenszyklus sie einstellbar macht.
- **Zielbindung:** `issued_for` ist ein undurchsichtiger Wert, den nur `ConnectionProfile#secretTarget`
  liefert (heute die Server-Adresse); der Speicher vergleicht ihn, er zerlegt ihn nie.
- **Zahlen über Personen nur maskiert:** Jede Zahl über verbundene Konten erreicht die Verwaltung
  über `connection.profile.PersonNumbers` (Gesamtzahl unter der Mindestgruppengröße nur „weniger
  als N“, auch bei null, wie `GroupService`; eine Teilzahl nur, wenn weder sie noch ihr Komplement
  darunter liegt); die exakten Zahlen des Ports fragt keine andere Klasse ab (ArchUnit
  `personNumbersLeaveOnlyMasked`). Eine verwerfende Änderung eines Zugangs für Personen verlangt
  immer eine Bestätigung mit neutralem Text. Grenzen: Die Beobachtung über die Zeit zeigt, wann die
  Zahl N überschreitet, und eine Verwaltungsperson mit eigener Freigabe kann durch eigenes
  Verbinden darauf schließen, dass höchstens N−1 weitere verbunden sind.
- **Ein Konto mit Verbindung wird nicht gelöscht:** `connected_accounts.user_id` ist `RESTRICT`
  und steht in `UserRepository#countDeletionBlockers`; das Geheimnis hängt mit `CASCADE` am Konto.

## Nachtrag vom 04.10.2026: Lebenszyklus der verbundenen Konten (#2163)

- **Fristen als Start-Einstellungen** (offene Entscheidung 1 des Phase-2-Plans, nach Empfehlung):
  Die Inaktivitätsschwelle ist die Property `opaa.connection.inactivity-threshold-days`
  (`OPAA_CONNECTION_INACTIVITY_THRESHOLD_DAYS`, Vorgabe 90). Die Grenzen 30–365 stehen als Konstanten
  in `ConnectionLifecycleProperties`; ein Wert außerhalb bricht den Start ab. Damit ist die
  Obergrenze weder per Konfiguration noch per SQL dehnbar. Die Tabelle in Entscheidung 7 („Grenzen
  als `CHECK`“) gilt für die Inaktivitätsschwelle und die Löschfrist privater Bibliotheken nicht
  mehr; die Löschfrist folgt mit dem Löschlauf nach demselben Muster. Die Aufbewahrung des
  Verbindungsprotokolls bleibt eine Tabellenzeile mit `CHECK`.
- **Abgleich:** `connection.account.ConnectionLifecycleReconciler`, täglich, nach dem Commit von
  `LocalAccountAccessEndedEvent` (ein Konto) und `OidcProvidersChangedEvent` (alle) und beim Start.
  Er sieht nur die Personen, die der Lebenszyklus betrifft: mit verbundenem Konto, mit privater
  Bibliothek oder mit festgehaltenem Zustand. Je Person läuft er in einer eigenen Transaktion
  (`REQUIRES_NEW`), weil ein Aufruf nach dem Commit sonst in die abgeschlossene Transaktion schriebe
  und Löschung und Protokolleintrag verlöre. Scheitert der Abgleich nach einem Commit, wird das nur
  als Zahl geloggt; die gespeicherte Änderung bleibt erfolgreich, der Tageslauf holt nach.
- **Festgehalten wird nur der Beginn,** in `connection_person_states` (`deactivated_since`,
  `dormant_since`, je Person höchstens einer). Der Zustand selbst bleibt abgeleitet. library liest
  den Beginn über `ConnectionLifecycle#deactivatedSince` und `#deactivatedBefore`.
- **Ein Endweg für die Deaktivierung:** `ConnectedAccountService#endAllOf` beendet über denselben
  privaten Endweg wie Trennen und Notabschaltung, mit `ACCOUNT_DEACTIVATED` und dem Systemprozess als
  Handelndem.
- **Bindungswechsel:** Ändert die Verwaltung an einem Zugang für Personen eine Vorgabe, die die
  Bindung der Zugangsdaten bestimmt, enden die verbundenen Konten mit `PROFILE_CHANGED`. Die
  `409`-Bestätigung nennt das wie jede verwerfende Änderung ohne Zahl.
- **Benachrichtigung:** Jede Beendigung, die die Verwaltung auslöst (Adresse, Registrierung,
  Bindung, Besitzart, Notabschaltung, Löschung), meldet der Person `CONNECTION_ENDED` (ADR-0019).
  Eine Deaktivierung meldet nichts, weil die Person sich nicht mehr anmeldet.
- **Letzte Nutzung:** `connected_accounts.last_used_at` schreibt die Herausgabe eines Geheimnisses
  fort, höchstens einmal je Tag und Konto, in eigener Transaktion mit `FOR UPDATE SKIP LOCKED`. So
  belastet die Herausgabe keinen Aufruf mit einem Schreibzugriff, auch nicht aus einer lesenden
  Transaktion, und wartet nie auf eine gesperrte Zeile.
- **Zahlen vor dem Abschalten eines Anbieters:** identity deklariert den Port
  `auth.ProviderConnectionsImpact`, connections beantwortet ihn über `PersonNumbers`
  (Verbindungen und private Bibliotheken der nicht deaktivierten Konten des Anbieters, je maskiert).
  Die Anzeige in der Anbieterverwaltung (Spezifikation, API, Oberfläche) folgt.

## Nachtrag vom 04.10.2026: Private Bibliotheken anlegen und betreiben (#2164)

- **Anlegen nur über `library.PrivateLibraryCreation`:** ausdrückliche Wahl „privat“, auch auf einem
  Zugang mit beiden Besitzarten, danach unveränderlich (Entscheidung 8 des Phase-2-Plans). Verlangt
  sind ein verbundenes Konto der Person auf dem Zugang, die Freigabe des Zugangs und das Ziel des
  Kontos: Ursprung und Bindung der Bibliothek müssen `SecretTarget#key` des Zugangs ergeben, sonst
  `400`. Ein eigenes Geheimnis nimmt eine private Bibliothek nie an.
- **Ein eigener Sperrgrund für ein abweichendes Ziel:** Passt das Ziel einer privaten Bibliothek
  nicht zu dem, für das das Konto ausgestellt ist, verweigert der Speicher mit
  `TARGET_OUTSIDE_PROFILE` statt `NOT_CONNECTED`; der Hinweis nennt die Besitzerin.
- **Kein Veto, sondern ein Sperrgrund:** Lehnt der Konnektor eine Profiländerung für eine private
  Bibliothek ab oder lässt das Profil danach keine Personen mehr zu, wird sie vom Profil gelöst und
  ruht mit `ACCESS_REMOVED`, bis die Besitzerin sie einem anderen Profil zuordnet. Die Vorschau
  zählt solche Ablehnungen, ohne Bibliothek und Grund. Eine Constraint-Trigger-Funktion auf
  `connection_profiles` hält die Bedingung des Triggers auf `library_connections` auch nach einer
  Änderung der Besitzart (geprüft zum Commit).
- **Verwaltungssichten ohne private Bibliotheken:** Zahlen am Profil, Indexstatus (eine
  Summenzeile, unter der Mindestgruppengröße nur „weniger als N“), chunk-arme Dokumente, Zahl der
  diagnosegesperrten Bibliotheken. Die ungefilterten Finder ruft nur ein gelisteter Systemprozess
  (`ModularArchitecture#privateLibrariesAreNotEnumeratedOutsideListedClasses`). Protokolle nennen
  eine private Bibliothek „Private Bibliothek“ (`Asset#auditName`).
- **Diagnosesperre:** Eine private Bibliothek trägt sie ab Anlage und behält sie; lösen lässt sie
  sich nicht. „Sicht als“ erreicht sie unabhängig davon nie; die Sperre ist die zweite Schranke.
- **Laufkategorie:** `indexing_jobs.failure_category` hält, warum ein Lauf scheiterte (Sperrgrund,
  abgelehnte Anmeldung), ohne Inhaltsbezug.

## Referenzen

- [connector-connections.md](../features/connector-connections.md)
- [ADR-0015](0015-eigentuemertrennung-protokollablage.md), [ADR-0021](0021-single-instance-betrieb.md),
  [ADR-0033](0033-lokale-benutzerverwaltung.md), [ADR-0035](0035-fremdzugaenge-mcp-server-und-zugangstokens.md)
- `backend/AGENTS.md`, „Logische Module und Schichtung“; `ModularArchitectureTest`
