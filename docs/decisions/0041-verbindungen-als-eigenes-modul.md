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
| connections → identity | `User`, Kontoereignisse aus `account`, Revisionsprotokoll und `AuditAccessGate` |
| connections → rights | Freigabe (`CapabilityService` mit Geltungsbereich), `AccountState` |
| connections → knowledge | `SourceConnectorRegistry`, Port des Kerns, Bibliotheksbezug der Verbindung |
| library → connections | Anlegen, Verbindungstest und Auflistung prüfen Freigabe, Sperre und Profilpflicht und holen das Geheimnis |
| assistant/external → connections | erst mit dem MCP-Client (Folge-Epic hinter #1747), eingetragen, wenn der Code sie nutzt |

**Ausgeschlossen:** knowledge → connections, connectors → connections, connections → library,
workspace, assistant oder external. Die Reihenfolge in `Module` schließt die beiden ersten aus,
`ALLOWED_MODULE_EDGES` die übrigen.

### 3. Ein Konnektor erreicht ein Token nur über den Kern

- knowledge deklariert in `io.opaa.indexing.source` einen Port (Arbeitsname
  `SourceConnectionResolver`), connections implementiert ihn. Muster: `FolderDocumentDeleter`.
- Der Kern fragt ihn vor jedem Lauf und vor jedem Abruf eines Originals (`RemoteOriginalAccess`).
  Die Antwort ist entweder eine Sperre mit Kategorie (gesperrt, ruhend, abgelaufen, Zugang
  entfernt) oder Ziel und nutzbares Geheimnis. Ohne Verbindung gelten die Felder der Bibliothek wie
  heute; die Sperre des Konnektortyps prüft der Port trotzdem.
- Der Kern reicht Ziel und Geheimnis in `SourceSettings` an den Konnektor, mit Art (persönliches
  Geheimnis oder Zugriffstoken). **Ein Konnektor sieht nie Profil, Refresh-Token, Client-Secret oder
  den OAuth-Ablauf.**
- Die Erneuerung läuft im Port: höchstens eine je Verbindung (Zeilensperre), ein rotierter
  Refresh-Token wird in derselben Transaktion ersetzt.
- Verbindungstest und Auflistung vor dem Speichern laufen über library, das connections direkt
  fragt. Der Konnektor bekommt das Geheimnis auch dort nur über `SourceSettings`.

### 4. Lebenszyklus über bestehende Kontoereignisse

- connections hört auf `LocalAccountAccessEndedEvent` und `LocalAccountDeletionEvent`
  (`io.opaa.account`) in der Transaktion des Auslösers. Es löscht Token und Geheimnisse aller
  Verbindungen der Person. Der Widerruf beim Anbieter läuft nach dem Commit als Versuch und hält
  die Sperre nie auf. Muster: `ExternalAccessTokenAccountLifecycleListener`.
- **Die Übergabe eines lokalen Kontos (ADR-0033, Entscheidung 12) ist keine Deaktivierung.** Das
  Ereignis bekommt einen Anlass, und connections überspringt die Übergabe.
- Private Bibliotheken behandelt library mit eigenen Listenern auf denselben Ereignissen und mit
  einem eigenen Löschlauf: Löschen, wenn die Besitzerin nach Ablauf der Frist noch gesperrt ist
  (`AccountState`, rights). Die Frist liest library aus connections. Eine Reaktivierung braucht kein
  Ereignis, weil der Lauf den Zustand erst beim Löschen prüft.
- Trennen, Notabschaltung und gelöschte Profile erreichen die Bibliothek ohne Ereignis: Der Port
  meldet beim nächsten Lauf „ruhend“ oder „Zugang entfernt“.
- Nach dem Einspielen einer Sicherung gleicht connections beim Start die Token ab: Token gesperrter
  Konten löschen, abgelaufene zählen.

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

### 8. Was #2160 im Code anlegt

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
  - `indexing/source/AGENTS.md`: Geheimnis nur über `SourceSettings`, vier Plätze, Ziel aus dem
    Profil
  - `permission/AGENTS.md`: Geltungsbereich der Fähigkeit, Merkmal „nur Besitzerin“
  - `library/AGENTS.md`: Kante zu connections, Lebenszyklus privater Bibliotheken
  - `auth/AGENTS.md`: Anlass im Kontoereignis

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
- **Lebenszyklus über ein eigenes Ereignis von connections an library:** Beide reagieren
  unabhängig auf das Kontoereignis. Eine Kante zwischen ihnen in Gegenrichtung entfällt so.

## Konsequenzen

- **Einfacher:** Ein neuer Konnektor meldet Profilangabe, Anmeldearten und OAuth-Endpunkte in seiner
  Beschreibung und braucht keinen Code außerhalb seines Pakets. Token und Client-Secret erreichen
  keinen Konnektor.
- **Schwieriger:** ein Modul und ein Changelog-Verzeichnis mehr. Der Kern hängt bei jedem Lauf an
  einem Port, dessen Antwort auch Netzzugriffe enthalten kann (Erneuerung).
- **Neutral:** Ereignisse laufen in der Transaktion des Auslösers. Fällt ein Listener, scheitert die
  Sperre; das ist dieselbe Zusage wie bei den Fremdzugangstokens.

## Referenzen

- [connector-connections.md](../features/connector-connections.md)
- [ADR-0015](0015-eigentuemertrennung-protokollablage.md), [ADR-0021](0021-single-instance-betrieb.md),
  [ADR-0033](0033-lokale-benutzerverwaltung.md), [ADR-0035](0035-fremdzugaenge-mcp-server-und-zugangstokens.md)
- `backend/AGENTS.md`, „Logische Module und Schichtung“; `ModularArchitectureTest`
