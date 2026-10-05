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
  | lokal gesperrt (`LocalCredentials`), außer der vorübergehenden Sperre nach Fehlversuchen (`FAILED_LOGINS`) und der Sperre wegen Inaktivität (`INACTIVITY`) | deaktiviert |
  | befristetes lokales Konto abgelaufen (`LocalAccountState.EXPIRED`) | deaktiviert |
  | Verzeichnissperre (`User#isDirectoryLocked`) | deaktiviert |
  | kein Anbieter mehr zum normalisierten Issuer (gelöscht), außer dem Dev-Issuer | deaktiviert |
  | Anbieter des Issuers deaktiviert | ruht |
  | reguläres lokales Konto (nicht `SYSTEM_ADMIN`) bei abgeschalteter lokaler Kontenverwaltung | ruht |
  | ohne Aktivität seit der Inaktivitätsschwelle (`users.last_login_at`) | ruht |
  | lokal gesperrt wegen Inaktivität (`INACTIVITY`, Nachtrag #2260) | ruht |
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
  als N“, auch bei null, wie `GroupService`; eine Teilzahl nur, wenn sie und ihr Rest je mindestens
  N sind, ein leerer Rest gilt als wenige, `PersonThreshold#disclosesPart`; unter einer Summe von
  2N−1 gar nicht); die exakten Zahlen des Ports fragt keine andere Klasse ab (ArchUnit
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

## Nachtrag vom 04.10.2026: Folgen in der Anbieterverwaltung (#2251)

- **Wirkung statt Zahlen:** `GET /api/v1/admin/oidc-providers/{id}/impact` nennt vor dem
  Deaktivieren oder Löschen nur, **ob** eine Bestätigung nötig ist, und **was** die Handlung
  bewirkt (`CONNECTIONS_REST` bzw. `CONNECTIONS_END`). Eine Zahl je Anbieter nennt sie nicht.
  Zahlen je Anbieter und je Zugang zählen dieselben Verbindungen und lassen sich deshalb
  gegeneinander verrechnen. Selbst ein bloßes „mindestens N“ je Anbieter hebt die 0-Rundung der
  Zugänge auf: Ein Anbieter mit „mindestens 5“ und zwei Zugänge mit je „weniger als 5“ zeigen, dass
  jeder Zugang mindestens eine Verbindung hat. Damit weicht der Nachtrag von Entscheidung 4 („nennt
  die Zahl der ruhenden Verbindungen“) bewusst ab. Der Port `auth.ProviderConnectionsImpact` sagt nur
  noch, ob ein Zugang Personen zulässt.
- **Bestätigung:** Deaktivieren und Löschen verlangen `confirmConnections=true`, sobald irgendein
  Zugang Personen zulässt, unabhängig davon, ob jemand verbunden ist. Sonst antworten sie mit
  `409 PROVIDER_CONNECTIONS_CONFIRMATION_REQUIRED` und einer neutralen deutschen Meldung über
  „etwaige verbundene Konten“. Deaktivieren lässt ruhen und löscht nichts. Löschen löscht die
  Geheimnisse sofort und unumkehrbar und startet die Löschfrist. Das gilt auch für den Schalter der
  Zeile `LOCAL` über die Anbieter-API.
- **Belegt** in `ProviderShutdownIntegrationTest` mit zwei Anbietern und zwei Zugängen:
  Zugangsliste, Folgenabschätzung beider Zugänge und beider Anbieter lauten vor jeder Verbindung,
  nach einer und nach zwei gleich.
- **Offen:** Der Schalter der lokalen Kontenverwaltung unter Administration → Benutzer
  (`updateLocalAuthSettings`) fragt nicht nach; dort ruhen die Verbindungen nur.

## Nachtrag vom 04.10.2026: Anmeldung des Zugangs (#2220, Teil von #2168)

- **Paketreihenfolge, unten zuerst:** `connection.log`, `connection.token`, `connection.profile`,
  `connection.request`, `connection.account`, `connection.oauth`. `oauth` liegt zuoberst; ein Fall
  der Fixture `connectionorder` belegt, dass ein Verweis von `connection.profile` auf `oauth` nach
  oben zeigt.
- **Dritte Besitzart `ProfileOwned`:** Client-Credentials und Dienstkonto-Schlüssel gehören dem
  Zugang. Es gibt keine Zeile im Token-Speicher; `SecretOwner.of` wählt die Besitzart nach der
  Anmeldeart, `ConnectionSecrets#current` fragt den Port `SecretIssuer#mint`. Jede Weiche über
  `SecretOwner` entscheidet ausdrücklich: Der Zugang hält für die Bibliothek nichts (`stored`
  `null`, `holds` falsch), `store` ist ein Programmfehler, Verwerfen und eine Ablehnung an der
  Quelle vergessen das Token im Prozess.
- **Die Registrierung verlässt `connection.profile` nur einmal:** `ProfileRegistrations#registrationOf`
  liefert `ClientRegistration` (Client-ID, entschlüsseltes Secret bzw. Schlüssel, Mandant, Scopes,
  deklarierte Anmeldung, imitiertes Konto, Proxy). Aufrufen und den Wert halten darf nur
  `connection.oauth` (ArchUnit `theProfileRegistrationLeavesOnlyToTheSignIn` mit Fixture). Den
  TLS-Schalter trägt der Wert nicht: Er gilt für die Server-Adresse, nie für den Token-Endpunkt.
- **`ProfileSignIn`** implementiert den Port. Client-Credentials holt es über `SourceFormPost`
  (Zielprüfung, keine Weiterleitung, Größengrenze, `client_secret_basic` oder `_post` nach
  Deklaration), den Dienstkonto-Schlüssel signiert weiter nur `ServiceAccountTokens` im Kern. Das
  Token bleibt im Prozess, bis kurz vor seinem Ablauf (ADR-0021).
- **Ablehnung:** Weist der Token-Endpunkt die Registrierung ab (`invalid_client`, beim Schlüssel
  auch `invalid_grant` ohne Kontobezug), setzt `ProfileRegistrations` `sign_in_rejected_at` in
  eigener Transaktion: Die Ablehnung ist eine Tatsache des Anbieters und gilt auch, wenn die Arbeit
  des Aufrufers zurückgerollt wird; eine nur lesende Transaktion des Aufrufers könnte sie nicht
  schreiben. Alle Bibliotheken des Zugangs tragen dann `EXPIRED` mit der Systemverwaltung als
  zuständig, ohne dass ein Lauf den Anbieter erneut fragt. Ein neues Secret, eine neue Registrierung
  oder ein erfolgreicher Anmeldetest (`POST …/{id}/test-sign-in`) hebt die Markierung auf.
- **Notabschaltung** löscht bei einem Zugang mit eigener Anmeldung auch das Client-Secret bzw. den
  Schlüssel: Es ist das einzige Geheimnis, mit dem seine Bibliotheken die Quelle erreichen.

## Nachtrag vom 04.10.2026: OAuth-Kern (#2168, Schnitt O1)

- **Zustimmung ohne Server-Sitzung,** wie im Nachtrag zu [ADR-0025](0025-mehrere-oidc-anbieter.md)
  entschieden: `POST /api/v1/connections/authorizations` legt eine Zeile in
  `connection_authorizations` an – nur der SHA-256-Hash des `state` (32 Byte Zufall), der
  PKCE-Verifier verschlüsselt (S256), die Person, der Zugang mit seiner Zeilenversion, der Zweck, die
  Rücksprungadresse `{OPAA_PUBLIC_BASE_URL}/connections/callback`, gültig 10 Minuten. Ohne
  öffentliche Adresse gibt es `409 PUBLIC_BASE_URL_MISSING`, nie eine Adresse aus dem Host-Header.
  Die SPA reicht `state` und `code` (oder `error`) mit ihrem Bearer-Token an
  `POST …/authorizations/complete`. Das Backend verbraucht den `state` mit einem bedingten `UPDATE`
  in eigener Transaktion, **bevor** es den Anbieter fragt; ein fremder, verbrauchter, abgelaufener
  oder unbekannter `state` ist `404`, ein inzwischen geänderter Zugang `409
  CONNECTION_AUTHORIZATION_PROFILE_CHANGED`. Das Rücksprungziel leitet der Server aus dem Zweck ab.
  Eine Person beginnt höchstens 10 Zustimmungen in 10 Minuten (`429`, Advisory-Lock-Namensraum 207);
  abgelaufene Zeilen räumt der nächste Start ab. Es gibt keinen freigegebenen Callback-Endpunkt in
  der Security-Konfiguration; Issue #2168 nannte ihn, ADR-0025 gilt.
- **Nur der Zweck `ACCOUNT` ist gebaut.** `LIBRARY_NEW` und `LIBRARY_RECONNECT` stehen im Schema und
  in der API, werden aber mit `400 CONNECTION_AUTHORIZATION_PURPOSE_UNAVAILABLE` abgewiesen, bis
  „Quelle verbinden“ (#2169) Bibliotheks-Token im Speicher ablegen kann. Damit entfällt die
  Bestätigung der Dienstkonto-Art vorerst: Ohne Bibliothekszweck entsteht keine URL.
- **Deklaration:** `OAuthAuth` (Autorisierungs-, Token- und Widerrufs-Endpunkt, Vorgabe-Scopes,
  zusätzliche Parameter der Autorisierungsanfrage, Client-Authentisierung) ist Pflicht für
  `SignIn` mit `OAUTH`. `Revocation` ist `None`, `Rfc7009` oder `BearerPost`. `Endpoint.FromProfile`
  überlässt einen Endpunkt dem Zugang: drei Spalten `authorization_endpoint`, `token_endpoint`,
  `revocation_endpoint`, Pflicht genau dort, wo die Deklaration sie offenlässt, beim Speichern
  festgeschrieben, nie zur Laufzeit erkannt. Ein geänderter Endpunkt ist eine Registrierungsänderung.
- **Ein Weg zum Anbieter:** `connection.oauth.OAuthClient` stellt Autorisierungsanfrage,
  Code-Tausch, Erneuerung und Widerruf; auch die Client-Credentials laufen darüber. Nur er ruft in
  connections `SourceFormPost` (Zielprüfung, keine Weiterleitung, Größengrenze), immer mit
  Zertifikatsprüfung und dem Proxy des Zugangs (ArchUnit
  `theAuthorizationServerIsReachedOnlyThroughTheOAuthClient` mit Fixture).
- **Port `SecretIssuer` neu geschnitten:** `renew` liefert `Issued` (Zugriffstoken mit Ablauf,
  rotierter Refresh-Token mit Ablauf oder `null`), `revocation` bekommt einen Schnappschuss beider
  Token. Implementiert ihn `connection.oauth.ProviderTokens`; `ProfileSignIn` bleibt die eigene
  Anmeldung des Zugangs und ist nur noch dessen Delegat.
- **Erneuerung in `ConnectionSecrets.current`:** Ein Zugriffstoken geht ohne Sperre heraus, solange
  es länger als die Marge gilt – fünf Minuten, höchstens die halbe Lebensdauer. Sonst folgt eine
  eigene Transaktion mit `SELECT … FOR UPDATE` auf der Token-Zeile, erneute Prüfung, `renew` und das
  Ersetzen des rotierten Refresh-Tokens in derselben Transaktion; zwei gleichzeitige Anfragen
  erneuern also einmal. Die Zeilensperre genügt unter [ADR-0021](0021-single-instance-betrieb.md) und
  bliebe bei mehreren Instanzen korrekt; sie hält eine Datenbankverbindung höchstens für das
  Zeitlimit des Token-Aufrufs (15 s). Das Zeitlimit von `SourceFormPost` umfasst den ganzen
  Austausch einschließlich des Lesens der Antwort (höchstens 64 KiB); ein Endpunkt, der seine
  Antwort tröpfeln lässt, wird abgebrochen. `invalid_grant` beendet die Verbindung in derselben gesperrten
  Transaktion über den neuen Port `connection.token.GrantRejections` (implementiert von
  `ConnectedAccountService`): abgelaufen mit `PROVIDER_REJECTED`, Protokolleintrag `EXPIRED`,
  Benachrichtigung `CONNECTION_EXPIRED`. Ein unerreichbarer Anbieter oder eine abgewiesene
  App-Registrierung (`invalid_client`) ändert keinen Zustand: Das alte Zugriffstoken bleibt bis zu
  seinem echten Ablauf nutzbar, danach scheitert der Lauf mit `SourceCredentialsException`. Nach
  einem `401` der Quelle erzwingt `afterRejection` eine Erneuerung, wenn das gespeicherte Token das
  abgelehnte ist.
- **Widerruf nach dem Commit:** `discard`, `discardAllUnder` und das Ersetzen eines Grants beim
  Neuverbinden nehmen vor dem Löschen einen Schnappschuss und widerrufen erst nach dem Commit, einmal,
  im aufrufenden Thread; ein zurückgerollter Verwurf widerruft nichts, ein gescheiterter Widerruf
  wird nur geloggt. Der Schnappschuss liest die Registrierung, wie sie gerade steht. Deshalb
  verwirft `ConnectionProfileService` bei Adress- oder Registrierungswechsel und bei der
  Notabschaltung **vor** der Änderung bzw. vor dem Löschen des Client-Secrets, nachdem es die
  Profilzeile gesperrt hat (`lockForChange`).
- **Bekannte Grenze des Widerrufs:** Die Widerrufe eines Commits laufen nacheinander im
  Request-Thread, je bis zu 15 s, und halten dabei die Datenbankverbindung. Eine Notabschaltung mit
  100 Konten bei unerreichbarem Anbieter dauert so rund 25 Minuten. Begrenzter Executor oder
  Gesamtbudget je Commit sind Maintainer-Entscheidung in #2265. Ein `BearerPost`-Widerruf sendet das
  gespeicherte, oft schon abgelaufene Zugriffstoken; vor dem Widerruf zu erneuern löst D1 (#2154).
- **Versionsbindung bis zum Ablegen:** Der Abschluss vergleicht die Zugangsversion der
  Autorisierung mit der Version, mit der die Registrierung gelesen wurde, und legt den Grant in
  einer Transaktion ab, die die Profilzeile mit `FOR SHARE` hält und die Version erneut vergleicht.
  Eine Änderung des Zugangs sperrt die Zeile mit `FOR UPDATE`, bevor sie verwirft: Entweder sie
  kommt zuerst, dann antwortet der Abschluss `409` und widerruft den frischen Grant mit der
  Registrierung, die ihn getauscht hat; oder sie kommt danach und verwirft ihn mit der alten.
- **Refresh-Tokens bleiben im Speicher:** Ihre Träger (`NewSecret.OAuthGrant`, `SecretIssuer.Issued`,
  `SecretIssuer.StoredTokens`, `OAuthClient.Grant`) liest nur `connection.token` und
  `connection.oauth` aus (ArchUnit `refreshTokensStayInTheTokenStore` mit Fixture). Kein Wert zeigt
  ein Token in `toString`; Antworten, Logs, Revisions- und Verbindungsprotokoll enthalten weder
  Token noch Code, Verifier, `state` oder Client-Secret (Leak-Test).
- **Belegt** in `ConnectionAuthorizationIntegrationTest` (Fake-Autorisierungsserver, Testkonnektor
  `OAUTH_PROBE` nur in `src/test`) und `KeycloakOAuthConsentTest` (Code mit PKCE, `offline_access`,
  Rotation, Widerruf gegen ein echtes Keycloak, nur in `keycloakIntegrationTest`).
- **Offen für O2 und Folgearbeit:** Callback-Seite und „Verbinden“ auf der Kontoseite, Anzeige der
  Endpunkte im Zugangsformular samt Meldung in `SourceTypeSignIn`, Ablaufwarnung 14 Tage vorher,
  Zahl abgelaufener Verbindungen mit Schwellenwert, Handbuch (`indexierung.md`, `deployment.md`).
  Die Kontoadresse nach der Zustimmung (`SourceConnector#connectedAccount`) gehört zu „Quelle
  verbinden“ (#2169).
- **Restrisiko Mix-up (RFC 9207):** Der Rücksprung prüft `iss` nicht. Ein bösartiger
  Autorisierungsserver an Zugang A kann die Person zum ehrlichen Server B schicken; der Code von B
  kommt mit dem `state` von A zurück und wird samt Verifier bei A eingelöst. Bei einem Zugang ohne
  Client-Secret (öffentlicher Client) genügt das A, um den Code bei B einzulösen. Vertretbar, weil
  nur die Systemverwaltung Zugänge anlegt und ihre Endpunkte setzt; alle Zugänge teilen aber eine
  Redirect-URI. Die Prüfung folgt in #2266.

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
- **Verwaltungssichten ohne private Bibliotheken:** Zahlen am Profil, Indexstatus und
  Pipeline-Stand (je eine Summenzeile), chunk-arme Dokumente, Zahl der diagnosegesperrten
  Bibliotheken, Speicherbereiche der Bereinigung. Der Bestandslauf der Pipeline bezieht private
  Bibliotheken ein (als Systemprozess), weist sie aber in keiner Zahl seiner Antwort aus; `done`
  folgt den geteilten. Ihre Dokumente liegen beim Anbieter; jeder Aufruf merkt alle veralteten
  mit einer Anweisung samt Elternkette für den nächsten Lauf vor, der das Geheimnis der Besitzerin
  braucht, unabhängig von `batchSize`. Die ungefilterten Finder ruft nur ein gelisteter Systemprozess
  (`ModularArchitecture#privateLibrariesAreNotEnumeratedOutsideListedClasses`).
- **Eine Zählbasis:** Jede Zahl über private Bibliotheken ruht auf ihren Besitzerinnen, nicht auf
  den Bibliotheken: unter der Mindestgruppengröße N an Besitzerinnen, null eingeschlossen, nur
  „weniger als N“ (`permission.PersonThreshold`, dieselbe Schwelle wie `PersonNumbers`). Das gilt
  für die Summenzeilen und die abgelehnten privaten Bibliotheken der Änderungsvorschau
  (`rejectedPrivateLibraries`, nie in `rejectedLibraries` oder `rejections`). Diese Teilzahl ist nur
  exakt, wenn ihre Besitzerinnen und die verschiedenen Besitzerinnen aller übrigen privaten
  Bibliotheken der Organisation je mindestens N sind (`PersonThreshold#disclosesPart`), und zwar in
  jeder Organisation, deren Bibliotheken sie zählt, denn ein Zugang hat keine eigene; ein leerer
  Rest gilt als wenige, sonst ist sie ab N nicht genannt. So ruht weder sie noch ihre Differenz zur
  Summenzeile auf weniger als N Personen. Private Ablagebereiche zählen nie in
  `knownLibraryCount` und `scannedAreas`, denn sie wären eine weitere Teilzahl.
- **Vollabgleich ohne private Bibliotheken:** Ändert sich eine Vorgabe nur des Zugangs, zählen
  `fullSyncLibraries` und die Bestätigung nur geteilte Bibliotheken, und nur deren laufende
  Indexierung lehnt die Änderung ab. Der Abgleichstand privater wird ebenso verworfen und ihre
  Besitzerin benachrichtigt. Läuft eine gerade, verwirft ihr Konnektor ihn nach dem Ende des Laufs
  noch einmal (`RunStateResets` über `SourceConnectionResolver#runEnded`), denn der Lauf schreibt
  ihn bis dahin weiter. Der Vermerk entsteht schon in der Transaktion der Änderung (ein Rollback
  nimmt ihn zurück) und liegt im Speicher (ADR-0021). **Restrisiko:** Startet der Prozess während
  eines solchen Laufs neu, oder startet ein Lauf zwischen der Prüfung und dem Commit der Änderung,
  bleibt der Abgleichstand der alten Vorgabe stehen; der nächste Lauf arbeitet inkrementell darauf
  weiter, und der Index bleibt bis zum nächsten Vollabgleich auf dem alten Stand. Dasselbe Fenster
  besteht für geteilte Bibliotheken. Heute in Produktion nicht erreichbar, weil nur Google Drive
  eine Vorgabe allein des Zugangs deklariert und nur Bibliotheken zulässt; die neustartfeste Lösung
  (Abgleichstand mit Fingerabdruck seiner Einstellungen) ist #2268, spätestens mit #2167 nötig.
- **Protokolle neutral:** Eine private Bibliothek heißt dort „Private Bibliothek“
  (`Asset#auditName`), ihre Nutzlasten behalten nur neutrale Schlüssel ohne Namen, Pfade und Werte
  (`Asset#auditPayload`); `ModularArchitecture#privateAssetsAreAuditedNeutrally` hält das fest.
- **Lösen benachrichtigt:** Wird eine private Bibliothek vom Zugang gelöst, erhält ihre
  Besitzerin eine Benachrichtigung nach dem Muster von ADR-0019 (warum, was mit dem Inhalt
  geschieht, was sie tun kann). Verweigert der Speicher ihr Geheimnis, etwa bei deaktiviertem
  Konto, fragt die Änderung ihren Konnektor nicht; die Bibliothek wird nicht allein deshalb gelöst.
- **Diagnosesperre:** Eine private Bibliothek trägt sie ab Anlage und behält sie; lösen lässt sie
  sich nicht. „Sicht als“ erreicht sie unabhängig davon nie; die Sperre ist die zweite Schranke.
- **Laufkategorie:** `indexing_jobs.failure_category` hält, warum ein Lauf scheiterte (Sperrgrund,
  abgelehnte Anmeldung), ohne Inhaltsbezug.

## Nachtrag vom 05.10.2026: Löschung privater Bibliotheken (#2165)

- **Löschfrist als Start-Einstellung:** `opaa.connection.private-library-deletion-days`
  (`OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS`, Vorgabe 30) in
  `connection.token.PrivateLibraryDeletionPeriod`; Grenzen 1–90 als Konstanten, `MAX_DAYS = 90`.
  Ein Wert außerhalb bricht den Start ab. Die Zeile „Grenzen als `CHECK`“ der Tabelle in
  Entscheidung 7 gilt damit für keine der beiden Fristen der Personen mehr.
- **Eine Löschung für beide Wege:** `library.PrivateLibraryErasure#erase` dient der Sofortlöschung
  durch die Besitzerin (`DELETE /api/v1/libraries/{id}`, für alle anderen `404`) und dem Löschlauf.
  Zuerst wird der Marker `knowledge_libraries.erasure_requested_at` mit Anlass in eigener
  Transaktion festgeschrieben. Danach startet kein Lauf mehr (manuell `409`, nach Zeitplan und per
  Ereignis verworfen). Ein laufender Lauf endet an seiner nächsten Frage nach dem Geheimnis
  (`LibraryErasureRequestedException`, ein `EndsRun`, über `IndexingRunTemplate` und
  `RunCredentials`); einen eigenen Abbruchmechanismus in den Konnektoren braucht es nicht, weil
  jeder entfernte Konnektor vor jeder Anfrage fragt (`RunSecretContract`). Solange ein Lauf
  `RUNNING` ist, antwortet die Löschung `PENDING` (`202`); was der Lauf bis zu seinem Ende noch
  schreibt, löscht die Fortsetzung mit. Danach folgt in **einer** Transaktion unter Zeilensperre:
  Abschnitte in beiden Speichern (der Schreibweg der Abschnitte sperrt die Bibliothekszeile mit
  `FOR KEY SHARE` und verweigert eine vorgemerkte oder fehlende Bibliothek, sodass auch ein
  Nachzügler-Thread nach der Löschung nichts zurückschreibt), Verweise außerhalb des Bestands über den Port
  `knowledge.ErasedLibraryReferences` (Chat-Quellen werden zu „Quelle entfernt“, Raumzuordnungen
  entfallen), Dokumente, Läufe samt Ereignissen, Benachrichtigungen, die Schale über
  `registerDeleted`, der Nachweis, die Zeile; Ordner, Metadaten, Abgleichstand, Präsenz und
  Geheimnisse folgen über `ON DELETE CASCADE`. Die gespeicherten Originale werden vorher und
  außerhalb der Transaktion entfernt; scheitert die Transaktion, bleibt die Bibliothek vorgemerkt,
  ihre Dokumente lassen sich nicht mehr öffnen, und die Fortsetzung vollendet die Löschung. Am Ende zählt sie jede Ablage nach. Bleibt etwas übrig, rollt
  sie zurück; der Marker bleibt, und die nächste Fortsetzung beginnt von vorn.
- **Nachweis** `PRIVATE_LIBRARY_ERASED` im Revisionsprotokoll mit Zeitpunkt der Vormerkung, Anlass
  (`OWNER_REQUEST`, `DELETION_PERIOD_EXPIRED`) und Zählern, nur über `Asset#auditPayload`. Die
  Historientabellen der Rechte, das Verbindungs- und das Diagnoseprotokoll behalten die Kennung der
  Bibliothek, nie einen Namen; sie haben ihre eigenen Fristen.
- **Löschlauf** `library.PrivateLibraryDeletionRun`: täglich um 04:45 nach dem Abgleich der
  Verbindungen. Die Frist hängt an der **aktuellen ausdrücklichen** Deaktivierung
  (`ConnectionLifecycle#deletionPeriodStartedBefore` über `AccountUsability.Snapshot#deactivationsOf`):
  Verzeichnissperre, Ablauf, lokale Sperre außer wegen Inaktivität oder gelöschter Anbieter. Sie
  beginnt beim späteren Zeitpunkt aus festgehaltenem Beginn und aktueller Sperre; ein erneutes
  Sperren nach einer vom Abgleich nicht gesehenen Reaktivierung startet sie neu. Die Sperre eines
  lokalen Kontos wegen Inaktivität (`LockReason.INACTIVITY`) startet keine Frist, bis #2260
  entscheidet, ob sie ruht oder deaktiviert; dasselbe gilt für Löschtag und `scheduledErasureCount`.
  Ruhend löscht nie. Alle fünf Minuten setzt er die vorgemerkten Löschungen fort.
- **Verbundenes Konto:** Eine getrennte Verbindung, an der danach keine private Bibliothek mehr
  hängt, wird mit der Löschung entfernt, protokolliert als `DELETED` mit `LIBRARY_DELETED`.
- **Sperrgrund mit Datum:** `OWNER_DEACTIVATED` trägt `contentDeletedOn`, den Beginn der
  festgehaltenen Deaktivierung plus Frist (Port `connection.profile.DeactivationStarts`).
- **Verwaltungssicht:** Die Indexübersicht nennt `scheduledErasureCount` als Teilzahl der privaten
  Bibliotheken, maskiert nach `PersonThreshold#disclosesPart`.
- **Restrisiko:** Die Antworttexte in Chats der Besitzerin bleiben stehen; nur Dateiname und Link
  ihrer Quellen entfallen. Sicherungen enthalten eine vor der Löschung gesicherte Bibliothek weiter
  (offen nach Spezifikation).

## Nachtrag vom 05.10.2026: Speicherkontingent je Person (#2166)

- **Ort knowledge, an der einen Aufnahmestelle:** `LibraryStorageQuotaService#verdictFor` liefert
  `QuotaVerdict` (`WITHIN`, `LIBRARY_EXHAUSTED`, `PERSON_EXHAUSTED`). Das Kontingent je
  Bibliothek (#119) prüft zuerst und bleibt unverändert; für eine Nur-Besitzerin-Bibliothek zählt
  danach die Summe über alle privaten Bibliotheken derselben Besitzerin
  (`PersonalStorageQuota#usageOf`).
- **Grenze:** ein hausweiter Wert in `private_storage_quota_settings` (eine Zeile, `0` =
  unbegrenzt); ohne Zeile gilt die Start-Property `opaa.library.private-storage-quota-bytes`.
  Setzen darf nur die Systemverwaltung; jede Änderung schreibt `PRIVATE_STORAGE_QUOTA_CHANGED`.
- **Lauf:** `PERSON_EXHAUSTED` wirft in `DocumentIngestService` die `PersonalQuotaExhaustedException`
  (`EndsRun`). `IndexingRunTemplate` beendet den Lauf wie bei einem erschöpften Anfragebudget
  geordnet als unvollständig, ohne Abgleich, mit der Meldung der Besitzerin im Protokoll und der
  Kategorie `QUOTA_EXHAUSTED` an einem abgeschlossenen Lauf.
- **Wettlauf:** Prüfung und Speichern der Dokumentzeile laufen unter
  `LibraryStorageQuotaService#holdIntake`, einer Sperre je Besitzerin in diesem Prozess
  (ADR-0021). Gezählt wird, was gespeichert ist; gleichzeitige Läufe mehrerer privater
  Bibliotheken einer Person überschreiten die Grenze deshalb nicht. Mit einer zweiten Instanz
  entfiele diese Garantie (Restrisiko wie bei jeder Annahme aus ADR-0021).
- **Kein Verwaltungspfad zum Verbrauch einer Person:** `PersonalStorageQuota#usageOf` rufen nur
  `LibraryStorageQuotaService` und der Selbstauskunfts-Controller, die zugrunde liegende Summe nur
  `PersonalStorageQuota` (`ModularArchitecture#personalUsageIsReadOnlyByItsOwner`). Die Übersicht
  der Verwaltung rechnet je Zugang in SQL über Personen und maskiert nach
  `PersonThreshold#disclosesPart`; mehrere exakte Teilsummen erscheinen nur, solange ihr
  gemeinsamer Rest auf mindestens N Personen ruht; ein leerer Rest gilt als wenige.

## Nachtrag vom 05.10.2026: Schreibende Wege je Zugang serialisiert (#2246)

- **Wettlauf:** Eine Profiländerung plant aus den Bibliotheken, die gerade am Zugang hängen. Ohne
  Sperre konnte eine Bibliothek, die währenddessen zugeordnet, über den Zugang angelegt, gelöst oder
  in ihren Quelleinstellungen geändert wurde, die Prüfung durch den Konnektor verpassen oder mit dem
  alten Rahmen des Zugangs weiterlaufen.
- **Profiländerung und Notabschaltung** sperren die Profilzeile als erste Anweisung mit
  `FOR NO KEY UPDATE`, nicht mehr mit `FOR UPDATE`: Sie schließen einander und jedes `FOR SHARE`
  aus, lassen aber Zeilen mit Fremdschlüssel auf das Profil durch, etwa den Start einer
  Zustimmung (deren Abschluss vergleicht die Version weiterhin unter `FOR SHARE`). Nur das
  **Löschen** sperrt mit `FOR UPDATE`, weil es die Zeile entfernt.
- **Zuordnen, Anlegen über einen Zugang, Lösen und die Änderung der Quelleinstellungen** einer
  Bibliothek am Zugang halten die Profilzeile mit `FOR SHARE` und vergleichen die Version mit der,
  aus der sie den Rahmen gelesen haben (`LibraryConnectionService#holdUnchanged`); beim
  Zugangswechsel beide Zugänge, in der Reihenfolge ihrer IDs. Weicht sie ab oder fehlt die Zeile,
  ändert sich nichts: `409 LIBRARY_CONNECTION_PROFILE_CHANGED`, ein erneuter Versuch liest den
  neuen Rahmen. Kam der Schreibweg zuerst, wartet die Profiländerung, sieht danach die Bibliothek
  und lässt sie vom Konnektor prüfen. Diese Wege warten nicht aufeinander.
- **Optimistisch statt von Beginn an gesperrt:** Die Sperre fällt nach der Prüfung durch den
  Konnektor, die Netzzugriffe enthalten kann (Edition einer Confluence-Instanz, Zieladressen), und
  vor dem ersten Schreiben. So läuft auf diesen Wegen kein Netzzugriff unter der Sperre, und ein
  Schreibweg hält beim Warten auf die Profilzeile noch keine andere Zeile. Ausnahme, korrekt und
  selten: Wartet eine Profiländerung auf ein Zuordnen oder Anlegen, findet sie danach die neue
  Bibliothek und fragt deren Konnektor innerhalb ihrer gesperrten Transaktion.
- **Sperrreihenfolge:** Profilzeile vor allen Zeilen, die der Weg schreibt (Token, Konten,
  Bibliotheken, Zuordnungen). Die Aufnahme von Chunks hält `FOR KEY SHARE` auf der
  Bibliothekszeile und kollidiert mit keiner dieser Sperren. Verbinden eines Kontos mit
  persönlichem Geheimnis nimmt die Profilzeile bisher nicht und ist nicht Teil dieses Nachtrags.

## Nachtrag vom 05.10.2026: Quelle verbinden (#2169, Schnitt Q1)

- **Paketreihenfolge, unten zuerst:** `connection.log`, `connection.token`, `connection.profile`,
  `connection.request`, `connection.consent`, `connection.account`, `connection.oauth`. Das neue
  Paket `connection.consent` trägt die eigene OAuth-Zustimmung einer Bibliothek
  (`SourceConsentService`), die Verantwortlichen (`ConsentResponsibles`), die Liste ruhender
  Quellverbindungen und das Abräumen nicht übernommener Zustimmungen. Nach unten erreichen es zwei
  neue Ports: `connection.token.SourceConsentRejections` (Erneuerung mit `invalid_grant`) und
  `connection.profile.SourceConsentEnds` (Adress-, Registrierungswechsel, Notabschaltung, Löschen
  des Zugangs).
- **Zwei Besitzarten mehr, ohne T1:** `SecretOwner.of` wählt für `OAUTH` an einer geteilten
  Bibliothek `SourceConsent(profileId, libraryId)`, eine Zeile in `connection_tokens` mit
  `library_id`. Persönliche Geheimnisse einer Bibliothek (`LibraryOwned`, `source_credentials`)
  bleiben, wo sie sind; ihr Umzug in den Speicher ist T1 (#2273) und nicht Teil dieses Nachtrags.
  `PendingConsent(tokenId, userId)` ist die Zustimmung, die eine Person im Assistenten vor dem
  Anlegen gibt: `pending_user_id` und `pending_expires_at` (60 Minuten), Besitzer-`CHECK` jetzt über
  drei Spalten. Nur diese Person nutzt sie, für Test und Ordnerauswahl (`SourceDraft#pending`) und
  beim Anlegen (`LibraryRequest.pendingConnectionId`); übernommen wird sie nur auf demselben Zugang
  und für dasselbe Ziel, sonst `409 PENDING_CONNECTION_UNUSABLE`. `PendingConsentSweep` löscht
  abgelaufene alle 15 Minuten und widerruft sie nach dem Commit (Eintrag in ADR-0021).
- **Keine Person entscheidet über die Herausgabe:** Anders als bei `PersonOwned` prüft
  `ConnectionSecrets` für `SourceConsent` keine Kontonutzbarkeit. Die Deaktivierung der
  zustimmenden Person lässt die Verbindung bestehen; `library_connections.connected_by` ist
  `ON DELETE SET NULL`.
- **Zustimmung:** Die Zwecke `LIBRARY_NEW` und `LIBRARY_RECONNECT` sind gebaut. Beide verlangen
  einen Zugang mit OAuth, der Bibliotheken zulässt, dessen Konnektor die Besitzart Bibliothek für
  OAuth deklariert, und `serviceAccountConfirmed`; ohne Bestätigung entsteht keine URL
  (`400 SERVICE_ACCOUNT_CONFIRMATION_REQUIRED`), der Zeitpunkt steht an der Autorisierung.
  `LIBRARY_NEW` prüft die Freigabe des Zugangs und Sperren wie das Anlegen, `LIBRARY_RECONNECT`
  `MANAGER` an der Bibliothek, dass sie auf dem Zugang liegt, und die Sperre, keine Freigabe. Beides
  prüft der Abschluss erneut. Eine private Bibliothek nimmt nie eine Zustimmung mit Dienstkonto
  (`400`, für alle anderen unsichtbar `404`). Der `state` bleibt an Person, Zugangsversion und
  Bibliothek gebunden; das Rücksprungziel folgt aus dem Zweck (`/libraries/new` bzw.
  `/libraries/{id}`).
- **Kontoadresse:** `SourceConnector#connectedAccount(SourceSettings)` mit dem frischen
  Zugriffstoken, Vorgabe leer, nach dem Code-Tausch und außerhalb jeder Transaktion. Scheitert der
  Abruf anders als mit `SourceCredentialsException`, wird der frische Grant widerrufen und der
  Abschluss mit `400` beendet; jeder Schritt nach dem Code-Tausch liegt im Widerrufsblock. Auch
  verbundene Konten von Personen tragen sie jetzt. Weicht sie beim Neuverbinden von der gespeicherten ab, ist
  das ohne `confirmAccountChange` beim Start ein `409 ACCOUNT_CHANGED`; der frische Grant wird mit
  der Registrierung widerrufen, die ihn getauscht hat. Mit Bestätigung verwirft
  `SourceChangeGate#accountChanged` den Laufzustand.
- **An der Verbindung** (`library_connections`, additiv): `account_label`, `connected_by`,
  `connected_at`, `responsible_type`/`responsible_id` (Person oder Gruppe mit `MANAGER`, sonst
  `400`), `ended_cause`/`ended_at`. Verantwortlich ist ohne Angabe die verbindende Person.
  Benachrichtigungen gehen an die Verantwortlichen, solange sie `MANAGER` halten, sonst an alle
  Verwaltenden: `SOURCE_CONNECTION_EXPIRING` (14 Tage vorher, einmal, über `ConnectionExpiryWatch`),
  `SOURCE_CONNECTION_EXPIRED` (`invalid_grant`), `SOURCE_CONNECTION_ENDED` (Änderung durch die
  Systemverwaltung).
- **Ein Endweg:** Trennen (`DELETE /api/v1/libraries/{id}/source-connection`, `SELF`), Zuordnen zu
  einem anderen Zugang (`SELF`), Löschen der Bibliothek (`LIBRARY_DELETED`), Adress- und
  Registrierungswechsel, Notabschaltung und Löschen des Zugangs verwerfen den Grant und widerrufen
  ihn nach dem Commit; das Verbindungsprotokoll nennt die Bibliothek mit Kontoadresse
  (`ConnectionLogOwner.Library`). Ein beendeter Grant (`invalid_grant`) bleibt als abgelaufen
  stehen, bis neu verbunden wird. Lösen auf eine eigene Adresse gibt es für eine solche Bibliothek
  nicht: Ein Konnektor mit OAuth verlangt Zugänge, das Lösen wird abgewiesen und die Zustimmung
  bleibt. Je Endweg belegt `SourceConsentIntegrationTest`, dass mit der alten Registrierung
  widerrufen wird.
- **Ausstehende Zustimmung nur unter ihrem Zugang:** Test und Ordnerauswahl nutzen sie nur im
  Entwurf desselben Zugangs, auch wenn ein anderer Zugang dieselbe Server-Adresse hat. Eine
  Zugangsversion trägt die Zeile nicht: Jede Adress- oder Registrierungsänderung verwirft sie
  ohnehin (`findConsentsUnder`), andere Änderungen berühren den Grant nicht.
- **Sichtbarkeit:** `LibraryResponse.sourceConnection` (Kontoadresse, Verantwortliche, Endgrund)
  erhalten nur Personen mit `MANAGER` an der Bibliothek; Lesende erfahren über den Sperrhinweis
  nur, dass die Quelle nicht erreicht wird. Vom Koordinator konservativ entschieden, die
  Entscheidung liegt beim Maintainer (PR #2286).
- **Sperrgrund und Anzeige:** Für `SourceConsent` antwortet der Speicher mit `NOT_CONNECTED`,
  `EXPIRED` oder `TARGET_OUTSIDE_PROFILE`; der Hinweis nennt die Verwaltenden, die Aktion ist
  `CONNECT_SOURCE`. Eine geteilte Bibliothek zeigt außer den Sperren auch diese Gründe, weil ihre
  Verwaltenden darauf handeln. Der frühere Hinweis „wird für Bibliotheken noch nicht unterstützt“
  entfällt.
- **Ruhende Quellverbindungen:** `GET /api/v1/admin/source-connections/dormant`
  (`SYSTEM_ADMIN`) nennt jede geteilte Bibliothek mit eigener Zustimmung, deren Quelle nicht
  erreicht wird; der Finder schließt private Bibliotheken in der Abfrage aus.
- **Sperrreihenfolge** wie im Nachtrag #2246: Der Abschluss hält die Profilzeile `FOR SHARE` mit
  Versionsvergleich, schreibt dann Token, dann `library_connections`; Trennen und Löschen
  schreiben Token vor Bibliothek. Kein Netzzugriff unter einer Sperre: Code-Tausch und
  Kontoadresse laufen vor, Widerrufe nach der Transaktion.
- **Vorschau:** Eine Bibliothek mit eigener Zustimmung zählt bei Adress- und
  Registrierungswechsel unter den Verbindungen, die neu angemeldet werden müssen.
- **Produktion:** Kein mitgelieferter Konnektor deklariert OAuth mit Besitzart Bibliothek; erster
  Nutzer ist Dropbox (#2154). Belegt in `SourceConsentIntegrationTest` mit dem Testkonnektor
  `CONSENT_PROBE` nur in `src/test`.
- **Abweichung vom Phase-3-Plan:** Die verantwortliche Person oder Gruppe einer neuen Bibliothek
  kommt mit dem Anlegen (`LibraryRequest.sourceConnectionResponsible`), nicht mit dem Start der
  Zustimmung, weil sie `MANAGER` an der erst entstehenden Bibliothek halten muss. Beim Neuverbinden
  kommt sie mit dem Start.

## Nachtrag vom 05.10.2026: Inaktivitätssperre lokaler Konten ruht (#2260)

- **Entscheidung (Maintainer):** Die Sperre eines lokalen Kontos wegen Inaktivität
  (`LockReason.INACTIVITY`) ist eine Abwesenheit, keine Deaktivierung. `LocalAccountAccess#usability`
  meldet für sie `DORMANT_INACTIVE`, auch ohne angefragte Schwelle. Der Abgleich hält nur den Beginn
  des Ruhens fest, löscht keine Geheimnisse und beendet keine verbundenen Konten. Private
  Bibliotheken ruhen mit `DORMANT`, ohne Löschtag. Nach dem Entsperren geht es mit der nächsten
  Anmeldung ohne Neuverbinden weiter.
- **Eine Stelle:** Die Ausnahme in `AccountUsability.Snapshot#deactivationsOf` aus #2165 entfällt.
  Löschfrist, Löschtag und `scheduledErasureCount` folgen allein `State#DEACTIVATED`.
- **Deaktivierung bleibt** die Sperre durch die Verwaltung, der Ablauf eines befristeten Kontos,
  die Verzeichnissperre und der gelöschte Anbieter. Sperrdialog und Ablaufdatum der
  Benutzerverwaltung nennen die Folge neutral, ohne Zahl und ohne Aussage, ob die Person ein Konto
  verbunden hat.
- **Offen (Nachtrag H6 aus #2275, #2289):** Für einen gelöschten Anbieter trägt das Konto keinen
  Zeitstempel; die Frist läuft ab dem festgehaltenen Beginn. Die Neuanlage des Anbieters löst nach
  dem Commit einen Abgleich aus, der diesen Beginn verwirft. Nur wenn dieser Abgleich scheitert und
  der Anbieter vor dem täglichen Abgleich erneut gelöscht wird, zählt der alte Beginn.

### Akzeptierte Restrisiken der Personenzahlen

Beide nach Maintainer-Entscheidung vom 05.10.2026 ohne Gegenmaßnahme:

- **Sybil-Risiko der Mindestgruppengröße:** Die Schwelle N (`PersonThreshold`, `PersonNumbers`)
  schützt nur, solange die gezählten Personen echt sind. Die Systemverwaltung kann selbst Konten
  anlegen, mit ihnen Konten verbinden oder private Bibliotheken anlegen, damit die Schwelle
  überschreiten und die eigenen Beiträge aus der dann genannten Zahl herausrechnen. Das Anlegen von
  Konten steht im Revisionsprotokoll, das Verbinden im Verbindungsprotokoll; beides liest die
  Revision.
- **Differenz-Orakel über die Zeit:** Die Laufabbrüche je Kategorie der Kontingentübersicht und
  `scheduledErasureCount` sind je Abfrage maskiert, nicht über Abfragen hinweg. Wer die Zahlen vor
  und nach einer eigenen Handlung vergleicht, etwa der Sperre einer Person, kann aus einer
  Änderung um eins auf diese Person schließen, sobald beide Zahlen genannt werden.

## Referenzen

- [connector-connections.md](../features/connector-connections.md)
- [ADR-0015](0015-eigentuemertrennung-protokollablage.md), [ADR-0021](0021-single-instance-betrieb.md),
  [ADR-0033](0033-lokale-benutzerverwaltung.md), [ADR-0035](0035-fremdzugaenge-mcp-server-und-zugangstokens.md)
- `backend/AGENTS.md`, „Logische Module und Schichtung“; `ModularArchitectureTest`
