# ADR-0041: Verbindungen als eigenes Modul — Zugänge, Token-Speicher und verbundene Konten

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt [ADR-0025](0025-mehrere-oidc-anbieter.md),
[ADR-0036](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) und
[ADR-0038](0038-steckbare-konnektoren.md) · ergänzt durch
[ADR-0048](0048-oauth-kern-und-zugangs-geheimnisse.md) (OAuth-Kern und Geheimnisse des Zugangs),
[ADR-0049](0049-private-bibliotheken-und-kontingente.md) (private Bibliotheken und Kontingente) und
[ADR-0050](0050-ausgangsproxy-und-zertifikatspruefung.md) (Ausgangsproxy, Zertifikatsprüfung)

## Kontext

Die Spezifikation [connector-connections.md](../features/connector-connections.md) (Epic #2147)
führt Bausteine ein, die es nicht gab: Verbindungsprofile („Zugänge“, auch ohne Konnektor als
MCP-Server), Sperre und Profilpflicht je Konnektortyp, Verbindungen mit Besitzer Bibliothek oder
Person (verbundenes Konto), einen verschlüsselten Token-Speicher, den OAuth-Ablauf und ein eigenes
Verbindungsprotokoll. Diese Bausteine brauchen die Freigabe (rights), die Konnektor-Beschreibung
(knowledge) und den Kontolebenszyklus (identity); umgekehrt brauchen Indexierungslauf, Konnektor und
Bibliotheksverwaltung das Ergebnis, also Ziel und Geheimnis. Der Modulgraph (`ModularArchitecture`,
`backend/AGENTS.md`) ist zyklenfrei und soll es bleiben. Ein Konnektor las bis dahin Adresse,
Zugangsdaten und Einstellungen selbst aus der Bibliotheksentität; ob eine Person ihr Konto noch
nutzen darf, prüften drei Stellen mit drei Regeln. Ereignisse allein reichen für den Lebenszyklus
nicht: Für den Ablauf befristeter Konten, deaktivierte Anbieter und Anbieter ohne
Verzeichnis-Konnektor gibt es keine.

## Entscheidung

### 1. Ein logisches Modul `connections`, Paket `io.opaa.connection`

Das Modul besitzt Zugänge (`ConnectionProfile`), die Richtlinie je Konnektortyp
(`ConnectorTypePolicy`: Sperre, Profilpflicht), die Zuordnung einer Bibliothek
(`LibraryConnection`), verbundene Konten, den Token-Speicher, den OAuth-Ablauf, das
Verbindungsprotokoll, die Fristen (Entscheidung 7) und die Verwaltung des Ausgangsproxys
(`OutboundProxyAdministration`, Mechanismus in foundation, ADR-0050). Die Freigabe selbst liegt in
rights (ADR-0036, Entscheidung 5), die Freigabeentscheidung nur hier (`ConnectorReleaseService`).
Kein bestehendes Modul passt: identity und rights liegen unter knowledge, brauchten aber die
Konnektor-Beschreibung; knowledge hielte MCP-Profile und Kontolebenszyklus fachfremd; connectors kennt
niemand; library besitzt keine Tabelle, und ein späterer MCP-Client müsste für ein Token die
Bibliotheksverwaltung kennen.

### 2. Lage im Modulgraphen

```
library ──► connections ──► knowledge ──► rights ──► identity ──► foundation
connectors ──► knowledge        (kein Pfad zu connections)
```

| Kante | Zweck |
|---|---|
| connections → foundation | `CredentialsEncryptor`, `TargetAddressValidator`, HTTP über `sourceaccess`, `outbound` |
| connections → identity | `User`, `AccountUsability`, Kontoereignisse, Revisionsprotokoll, `AuditAccessGate` |
| connections → rights | Freigabe (`CapabilityService` mit Geltungsbereich), `PersonThreshold` |
| connections → knowledge | `SourceConnectorRegistry`, die Ports des Kerns, Bibliotheksbezug |
| library → connections | Anlegen, Verbindungstest und Auflistung prüfen Freigabe, Sperre und Profilpflicht; Löschlauf liest Frist und Beginn |

knowledge deklariert die Ports `SourceConnectionResolver` und `SourceStateLookup`, connections
implementiert sie (`ProfileSourceConnectionResolver`, `ConnectionSourceStateLookup`). Ausgeschlossen:
knowledge → connections, connectors → connections, connections → library, workspace, assistant oder
external; die Kante von assistant bzw. external kommt erst mit dem MCP-Client. Fremdschlüssel zeigen
nur auf identity, rights und knowledge; die Verbindung einer Bibliothek steht in connections, nicht
in `knowledge_libraries`.

### 3. Ein Konnektor erreicht ein Token nur über den Kern

Der Kern fragt `SourceConnectionResolver` vor jedem Lauf, vor jedem Abruf eines Originals
(`RemoteOriginalAccess`) und je Anfrage im Lauf. Die Antwort ist eine Sperre mit Grund
(`SourceBlock.Reason`: `TYPE_LOCKED`, `PROFILE_LOCKED`, `PROFILE_REQUIRED`, `ACCESS_REMOVED`,
`OWNER_DEACTIVATED`, `DORMANT`, `TARGET_OUTSIDE_PROFILE`, `NOT_CONNECTED`, `EXPIRED`; Text nur aus
`SourceBlocks`) oder Ziel und nutzbares Geheimnis. Ohne Zugang gelten die Felder der Bibliothek; die
Sperre des Konnektortyps prüft der Port trotzdem. Vor jeder Herausgabe des Geheimnisses einer Person
prüft er die Nutzbarkeit ihres Kontos (Entscheidung 4). **Ein Konnektor sieht nie Zugang,
Refresh-Token, Client-Secret, Dienstkonto-Schlüssel oder den OAuth-Ablauf** (ArchUnit
`theSecretPortStaysWithTheCore`, `theSecretStoreIsUsedOnlyInConnections`). Erneuerung und Widerruf
laufen im Speicher (ADR-0048, Entscheidung 4). Verbindungstest und Auflistung vor dem Speichern laufen
über library, das connections direkt fragt; der Konnektor bekommt das Geheimnis auch dort nur über
`SourceSettings`.

### 3a. Lauf-SPI: Konnektoren lesen keine Zugangsdaten selbst aus der Bibliothek

Lauf, Originalabruf und die Verwaltungsmethoden von `SourceConnector` bekommen `SourceSettings`
vom Kern: Ziel, Geheimnis mit Art (persönliches Geheimnis oder Zugriffstoken) und
Konnektor-Einstellungen, am Zugang mit dessen Vorgaben zusammengeführt (`EffectiveSourceSettings`,
`ProfileFrame`). Die Entität dient dem Konnektor nur für Identität und Bestand. ArchUnit
`connectorsTakeTheirSourceConfigurationFromTheCore`: Keine Klasse eines Konnektorpakets ruft
`KnowledgeLibrary#getSourceCredentials`, `#getSourceUrl`, `#getSourceSettings` oder
`ConnectorData#storedIn`; Ausnahmen sind `#getSourcePath` (Dateisystem verbietet Zugänge, kein
Geheimnis) und `#getWebhookSecret` (Push-Geheimnis bleibt an der Bibliothek). Proxy und TLS-Schalter
sind keine Felder der Bibliothek oder des Zugangs (ADR-0050).

### 4. Lebenszyklus: eine zentrale Abfrage, Ereignisse beschleunigen nur

`io.opaa.auth.AccountUsability` ist die einzige Regel „Konto jetzt nutzbar?“;
`LocalAdminAvailabilityGuard` und `ExternalAccessTokenAuthenticator` nutzen sie (Fremdzugangstokens
von Personen eines deaktivierten oder gelöschten Anbieters werden deshalb abgelehnt; die
Inaktivitätsschwelle gilt dort nicht).

| Zustand (`AccountUsability.State`) | Anlass |
|---|---|
| `DEACTIVATED` | lokale Sperre durch die Verwaltung (nicht `FAILED_LOGINS`, nicht `INACTIVITY`), befristetes Konto abgelaufen, Verzeichnissperre, Anbieter des Issuers gelöscht (außer Dev-Issuer) |
| `DORMANT_PROVIDER_DISABLED` | Anbieter deaktiviert |
| `DORMANT_LOCAL_ACCOUNTS_DISABLED` | reguläres lokales Konto bei abgeschalteter lokaler Kontenverwaltung |
| `DORMANT_INACTIVE` | keine Anmeldung seit der Inaktivitätsschwelle; lokale Sperre wegen Inaktivität (`LockReason.INACTIVITY`) |
| `LOCKED_OUT`, `INVITED` | vorübergehende Sperre nach Fehlversuchen, offene Einladung — weder nutzbar noch ruhend noch deaktiviert |
| `USABLE` | sonst |

- **Ruhen ist keine Deaktivierung:** Es stoppt Läufe und die Herausgabe von Geheimnissen, löscht
  nichts und startet keine Frist; mit „nutzbar“ geht es ohne Neuverbinden weiter. Ein deaktivierter
  Anbieter zählt als Ruhen, erst sein Löschen deaktiviert; der Löschzeitpunkt je Issuer steht in
  `oidc_provider_removals` (identity) und beginnt die Frist beim letzten Löschen.
- **Abgleich** `ConnectionLifecycleReconciler`: täglich, beim Start und nach dem Commit von
  `LocalAccountAccessEndedEvent` (ein Konto) und `OidcProvidersChangedEvent` (alle), je Person in
  eigener Transaktion (`REQUIRES_NEW`). Deaktiviert: Geheimnisse löschen, beim Anbieter widerrufen,
  Konten über denselben Endweg wie Trennen beenden (`ACCOUNT_DEACTIVATED`); ruhend: ruhen lassen;
  nutzbar: aufheben. Scheitert der Abgleich nach einem Commit, wird das nur als Zahl geloggt, die
  Änderung bleibt erfolgreich, der Tageslauf holt nach. Sperrdialog und Ablaufdatum der
  Benutzerverwaltung nennen die Folge neutral, ohne Zahl und ohne Aussage, ob ein Konto verbunden
  ist. Festgehalten wird nur der Beginn (`connection_person_states`: `deactivated_since`,
  `dormant_since`); der Zustand bleibt abgeleitet. library liest den Beginn über
  `ConnectionLifecycle` für den Löschlauf (ADR-0049, Entscheidung 2). Die Übergabe eines lokalen
  Kontos (ADR-0033, Entscheidung 12) ist keine Deaktivierung.
- **Inaktivitätsschwelle:** `opaa.connection.inactivity-threshold-days` (Vorgabe 90, Grenzen 30–365
  als Konstanten in `ConnectionLifecycleProperties`, ein Wert außerhalb bricht den Start ab).
  `connected_accounts.last_used_at` hält die Herausgabe höchstens einmal je Tag fest, in eigener
  Transaktion mit `SKIP LOCKED`.
- **Anbieterverwaltung**: `GET /api/v1/admin/oidc-providers/{id}/impact` nennt nur, ob
  eine Bestätigung nötig ist und was die Handlung bewirkt (`CONNECTIONS_REST`, `CONNECTIONS_END`),
  nie eine Zahl je Anbieter — Zahlen je Anbieter und je Zugang ließen sich gegeneinander verrechnen.
  Deaktivieren und Löschen verlangen `confirmConnections=true`, sobald irgendein Zugang Personen
  zulässt (`409 PROVIDER_CONNECTIONS_CONFIRMATION_REQUIRED`), auch für den Schalter der Zeile `LOCAL`
  über die Anbieter-API; Port `auth.ProviderConnectionsImpact`.
- **Kontolöschung:** Ein Konto mit Verbindung wird nicht gelöscht (`connected_accounts.user_id`
  `RESTRICT`, `UserRepository#countDeletionBlockers`); Geheimnisse hängen mit `CASCADE` am Konto. Jede
  Beendigung durch die Verwaltung meldet der Person `CONNECTION_ENDED` (ADR-0019), eine
  Deaktivierung nichts.

**Restlücke:** Bei OIDC-Konten ohne Verzeichnis-Konnektor erfährt OPAA eine Deaktivierung beim
Anbieter nicht; die Schwelle stoppt nach spätestens 90 Tagen nur die Nutzung, das Token bleibt bis
zur Sperre in OPAA. Das schließt nur der Verzeichnis-Konnektor (ADR-0036, Entscheidung 3); das
Handbuch empfiehlt ihn für Häuser mit verbundenen Konten.

### 5. MCP-Profil

`connection_profiles.kind` ist `CONNECTOR` (genau dann mit `source_type`, `CHECK`) oder
`MCP_SERVER` (`ProfileKind`), dazu `responsible_group_id` und `issuer`. Verbundene Konten,
Token-Speicher, Protokoll, Notabschaltung, Löschen, Sperre und Widerruf sind dieselben wie bei jedem
Zugang. Konnektor-Pfade sehen keinen MCP-Server (`findConnectorsByName`, ArchUnit
`connectorPathsSeeOnlyConnectorProfiles`; Lookups nach ID weisen mit `400` bzw. `404` ab).
Verwaltung nur über `/api/v1/admin/mcp-servers` (`SYSTEM_ADMIN`), ohne Oberfläche, solange es keinen
MCP-Client gibt; Anmeldeart fest OAuth, Besitzart fest Person, Adresse der Streamable-HTTP-Endpunkt
in kanonischer Form (`McpServerResource`, nur `https`; `http` nur auf Loopback, das die Zielprüfung in
Produktion sperrt). Konten auf MCP-Servern erscheinen nicht auf der Kontoseite. `McpServerDiscovery`
erkennt beim Speichern, nie zur Laufzeit: Protected Resource Metadata (RFC 9728), dann
Autorisierungsserver-Metadaten (RFC 8414, sonst OpenID-Discovery); `resource` muss die
Server-Adresse sein, `issuer` die Adresse der Metadaten, `S256` angeboten, Endpunkte `https` (`http`
nur auf Loopback), jede Adresse durch `TargetAddressValidator`. Ein abweichender Wert ist ein
Adress- bzw. Registrierungswechsel (Entscheidung 12); ein gespeichertes Secret folgt keinem neuen
Server (`400 MCP_SERVER_CLIENT_SECRET_REQUIRED`). Das Token ist an den Ressourcen-Indikator gebunden
und wird nur mit Ziel herausgegeben (`McpServerTokens#accessFor`; ADR-0048, Entscheidung 7). Keine
dynamische Client-Registrierung. Bis das Folge-Epic zu #1747 die Nutzungsfreigabe festlegt,
verbindet nur `SYSTEM_ADMIN` (`requireConnectable`).

### 6. Private Bibliothek und Nur-Besitzerin-Regel

Das Merkmal „nur Besitzerin“ sitzt an der Asset-Schale in rights (`assets.owner_only`, ADR-0036,
Entscheidung 5), damit Grant-Pfad, „Sicht als“, Nachfolge und Space-Zuordnung die Regel erzwingen,
ohne connections zu kennen. Eine private Bibliothek hängt an genau einer Verbindung, dem verbundenen
Konto ihrer Besitzerin (`PrivateLibraryConnections`); sie trägt die Diagnosesperre ab Anlage und
heißt in jedem Protokoll „Private Bibliothek“ (`Asset#auditName`, `#auditPayload`, ArchUnit
`privateAssetsAreAuditedNeutrally`). Anlage, Betrieb, Löschung, Kontingente und Betriebslogs:
ADR-0049.

### 7. Verbindungsprotokoll und Fristen

`connection_log` ist eine nur anfügbare Tabelle nach dem Muster von `audit_log` (ADR-0015):
monatliche Partitionen im Besitz von `opaa_audit_owner`, die Anwendung hat `INSERT` und `SELECT`,
die Löschfunktion `opaa_connection_log_delete_expired_partitions()` ist `SECURITY DEFINER` und
eigens je Tabelle. Ereignisse `ConnectionLogEventType` (`CONNECTED`, `RECONNECTED`, `DISCONNECTED`,
`EXPIRED`, `EMERGENCY_DISCONNECTED`), jedes außer den ersten beiden mit Endanlass
`ConnectionEndCause` (`CHECK`); Besitzer ist Person oder Bibliothek (`ConnectionLogOwner`).
`ConnectionLog#record` tritt der Transaktion des Aufrufers bei. Lesen darf nur `AUDITOR` über
`ConnectionLogQueryService` hinter `AuditAccessGate`, jeder Abruf ist `CONNECTION_LOG_ACCESSED`.
Vergabe, Entzug, Sperre sowie Anlegen, Ändern und Löschen von Zugängen sind Governance-Ereignisse
im Revisionsprotokoll.

| Wert | Grenzen | Vorgabe | Ablage |
|---|---|---|---|
| Löschfrist privater Bibliotheken nach Deaktivierung | 1–90 Tage | 30 Tage | `opaa.connection.private-library-deletion-days`, Grenzen als Konstanten (`PrivateLibraryDeletionPeriod`) |
| Aufbewahrung des Verbindungsprotokolls | 6–24 Monate | 12 Monate | Tabellenzeile mit `CHECK`, `PUT /api/v1/admin/connection-log/retention`, Änderung ist `CONNECTION_LOG_RETENTION_CHANGED` |
| Warnung vor Token- und Secret-Ablauf | — | 14 Tage | fest (`ConnectionExpiryWatch`, einmal je Ende) |
| Inaktivitätsschwelle für Verbindungen | 30–365 Tage | 90 Tage | `opaa.connection.inactivity-threshold-days`, Grenzen als Konstanten |
| Schwellenwarnung abgelaufener Konten je Zugang | — | 10 | `opaa.connection.expired-connections.*` (`ExpiredConnectionWarningProperties`) |

Die Obergrenzen der Fristen sind weder per Konfiguration noch per SQL dehnbar. Begründung: Der
Inhalt einer privaten Bibliothek liegt weiter beim Anbieter, eine kurze Frist kostet nur einen Lauf;
90 Tage sind die Grenze, die Personalrat und Datenschutz verlangen. Das Protokoll belegt die Nutzung
persönlicher Verbindungen, nicht Rechte; zwölf Monate decken einen Prüfzyklus. Personalrat und
Datenschutz können die Vorgaben innerhalb der Grenzen ändern, ohne dass dieser ADR geändert wird.

### 8. Code-Struktur, Freigabe und Spezifikation

`Module.CONNECTIONS` steht in `ModularArchitecture` direkt nach `CONNECTORS`, dort auch Schichtung und
Paketreihenfolge (Regeln: `backend/AGENTS.md`). Modulkanten erst, wenn der Code sie nutzt
(`everyAllowedModuleEdgeIsInUse`). Unterpakete, unten zuerst: `log`, `token`, `profile`, `consent`,
`account`, `oauth`; das Wurzelpaket verdrahtet die Ports, `web` liegt darüber, nach oben nur über
Ports (`LibrariesOnProfile`, `PersonAccounts`, `SecretIssuer`, `SourceConsentRejections`,
`PersonConnections`, `SourceConsentEnds`). Die Regeln je Paket stehen in
`backend/src/main/java/io/opaa/connection/AGENTS.md` (`ModuleAgentsFileTest`). Liquibase:
`db/changelog/connections/`, Tabellen in `ChangelogModuleBoundaryTest`. **Freigabe nur in
connections:** `ConnectorReleaseService` (Bibliothek) und `requireConnectable` (neues Konto)
entscheiden aus `CREATE_CONNECTOR_LIBRARY` mit Geltungsbereich und den Sperren; jedes andere Modul
fragt sie (ArchUnit `theConnectorReleaseIsDecidedInConnections`). Spezifikation zuerst (ADR-0006):
`GET /api/v1/me` führt `CREATE_CONNECTOR_LIBRARY` als Zeichenkette, sobald die Person sie in einem
Geltungsbereich hat; `GET /api/v1/source-types` bleibt vollständig, die Zugänge liefert nur
Entscheidung 11.

### 9. Token-Speicher und verbundene Konten

Geheimnisse liest, speichert und verwirft nur `connection.token.ConnectionSecrets` über
`SecretOwner.of`, das die Besitzart aus Anmeldeart und Bibliothek wählt: `LibraryOwned` (eigene
Adresse, `source_credentials`), `LibraryOnProfile` (persönliches Geheimnis einer Bibliothek am
Zugang im Speicher), `PersonOwned` (verbundenes Konto), `ProfileOwned` (eigene
Anmeldung des Zugangs, keine Zeile), `SourceConsent` (OAuth-Zustimmung einer Bibliothek),
`PendingConsent` (vor der Anlage). `ConnectionSecrets#current` wirft `SecretRefusedException` mit
dem Sperrgrund; „ruhend“ und „deaktiviert“ werden bei jeder Herausgabe aus `AccountUsability`
abgeleitet. Ein Geheimnis geht nur an das Ziel, für das es ausgestellt ist: `issued_for` ist der
undurchsichtige Wert aus `SecretTarget#key` (Ursprung plus `SourceConnector#credentialBinding`), der
Speicher vergleicht ihn und zerlegt ihn nie. Die Kontoadresse liegt im Token-Speicher verschlüsselt
(`account_label_ciphertext`), im Verbindungsprotokoll im Klartext.
**Zahlen über Personen nur maskiert:** Jede Zahl über verbundene Konten erreicht die Verwaltung über
`connection.profile.PersonNumbers` (unter der Mindestgruppengröße N nur „weniger als N“, auch bei
null; eine Teilzahl nur, wenn sie und ihr Rest je mindestens N sind, `PersonThreshold#disclosesPart`);
die exakten Zahlen des Ports `PersonConnections` fragt keine andere Klasse ab
(`personNumbersLeaveOnlyMasked`, `theAdministrationNeverSeesAConnectedPerson`). Die
Schwellenwarnung abgelaufener Konten warnt nach der größten zulässigen Zahl, die die maskierte
Antwort zulässt; ein Zugang nur für Bibliotheken warnt nie. Ein Schwellenwert unter N wird nicht
abgewiesen, aber beim Start als Warnung geloggt: Dann warnt jeder Zugang für Personen schon bei
„weniger als N“. Akzeptierte Restrisiken
(Maintainer, 05.10.2026): Die Systemverwaltung kann mit selbst angelegten Konten die Schwelle
überschreiten (Sybil; Anlegen und Verbinden stehen in den Protokollen), und wer maskierte Zahlen vor
und nach einer eigenen Handlung vergleicht, kann auf eine Person schließen.

### 10. Schreibende Wege je Zugang serialisiert, Sperrreihenfolge

Profiländerung und Notabschaltung sperren die Profilzeile als erste Anweisung mit
`FOR NO KEY UPDATE`, nur das Löschen mit `FOR UPDATE`. Zuordnen, Anlegen über einen Zugang, Lösen
und die Änderung der Quelleinstellungen einer Bibliothek am Zugang halten die Profilzeile mit
`FOR SHARE` und vergleichen die Version mit der, aus der sie den Rahmen gelesen haben
(`LibraryConnectionService#holdUnchanged`; abweichend `409 LIBRARY_CONNECTION_PROFILE_CHANGED`) —
optimistisch, nach der Prüfung durch den Konnektor und vor dem ersten Schreiben, damit kein
Netzzugriff unter der Sperre läuft. Dasselbe gilt für den Abschluss einer Zustimmung
(ADR-0048, Entscheidung 3) und für das Verbinden mit persönlichem Geheimnis
(`lockedVersion`, `409 CONNECTED_ACCOUNT_PROFILE_CHANGED`). Sperrreihenfolge überall: Profilzeile,
dann Token-Zeile, dann Kontozeile; wer das Konto zuerst schreibt (`established`, `expire`), ruft
vorher `ConnectionSecrets#lockHeld`.
Übergänge (Profiländerung, Zuordnen, Lösen) sind je Bibliothek ein `SourceTransitions.Move` durch
`SourceChangeGate`. `CONNECTION_PROFILE_RUN_IN_PROGRESS` lehnt eine Änderung ab, solange eine
geteilte Bibliothek des Zugangs läuft; private halten sie nicht auf. Den Abgleichstand nach einer
Änderung schützt der Fingerabdruck (ADR-0045, Entscheidung 8), nicht ein Vermerk im Speicher.

### 11. Der Assistent zeigt nur Nutzbares, kein Zugangswunsch

Die Systemverwaltung legt fest, was genutzt werden kann (Maintainer, 11.10.2026). Es gibt keinen
Zugangswunsch; `CONNECTION_PROFILE_REQUEST_RESOLVED` bleibt im Enum, damit alte Einträge lesbar
bleiben. `GET /api/v1/connection-profiles` liefert nur Zugänge, die der Person freigegeben und
weder selbst noch über ihre Quellart gesperrt sind (`ConnectorReleaseService#profileOptions`); der
Assistent blendet Quellarten ohne nutzbaren Weg aus und zeigt sonst einen Leerzustand mit Hinweis auf
die Systemverwaltung. Der Zustand einer bestehenden Bibliothek bleibt mit Grund und Aktion sichtbar;
die Verwaltung sieht alles.

### 12. Verwerfende Änderungen eines Zugangs

Adress- und Registrierungswechsel, eine Vorgabe, die die Bindung der Zugangsdaten bestimmt, ein
Wechsel der Besitzart, Notabschaltung und Löschen verwerfen vorher alle Geheimnisse des Zugangs,
widerrufen sie nach dem Commit (ADR-0048, Entscheidung 5) und beenden die Konten mit dem passenden
Endanlass; die Notabschaltung löscht auch Client-Secret bzw. Schlüssel. Jede solche Änderung verlangt
`409 CONNECTION_PROFILE_CONFIRMATION_REQUIRED` mit neutralem Text ohne Zahl; die Vorschau
(`ConnectionProfileService.Discards`) nennt geänderte Endpunkte mit alter und neuer Adresse. Eine
private Bibliothek hat kein Veto: Lehnt ihr Konnektor die Änderung ab oder lässt der Zugang danach
keine Personen zu, wird sie gelöst, ruht mit `ACCESS_REMOVED` und ihre Besitzerin wird benachrichtigt
(`PrivateLibraryRelease`, `PRIVATE_LIBRARY_RELEASED`); `fullSyncLibraries` und die laufende
Indexierung zählen nur geteilte Bibliotheken (`ProfileFullSync`), der Abgleichstand privater wird
ebenso verworfen und ihre Besitzerin benachrichtigt. Trennen, Notabschaltung und
gelöschte Zugänge erreichen die Bibliothek ohne Ereignis: Der Port meldet beim nächsten Lauf.

## Konsequenzen

**Einfacher:** Ein neuer Konnektor meldet Profilangabe, Anmeldearten und OAuth-Endpunkte in seiner
Beschreibung und braucht keinen Code außerhalb seines Pakets; Token und Client-Secret erreichen
keinen Konnektor. Eine Regel „Konto nutzbar“ statt drei.

**Schwieriger:** Ein Modul und ein Changelog-Verzeichnis mehr; der Kern hängt bei jedem Lauf an
einem Port, dessen Antwort Netzzugriffe enthalten kann (Erneuerung).

**Restrisiken:** die Restlücke aus Entscheidung 4 und die akzeptierten Risiken der Personenzahlen
(Entscheidung 9). Der Abgleich läuft nach dem Commit des Auslösers; eine Sperre scheitert nie an ihm,
bis zum Abgleich hält die Herausgabesperre im Port.

## Verworfene Alternativen

- connections unter knowledge — mehr Rückwege (Konnektor-Beschreibung, Vorgaben, Zählen) als ein Port im Kern.
- connections über library — library bräuchte Ports für Freigabe, Sperre und Profilpflicht.
- Freigabe als eigene Tabelle am Profil — doppelte Subjekte, Auswertung, Historie und `/me`;
  stattdessen Geltungsbereich der Fähigkeit.
- Verbindung als Spalte in `knowledge_libraries` — Fremdschlüssel von knowledge nach oben.
- Lebenszyklus über ein Ereignis von connections an library — Kante in Gegenrichtung.
- Lebenszyklus nur über Ereignisse — für befristete Konten, deaktivierte und verzeichnislose
  Anbieter gibt es keine.
- `AccountState` (rights) als Quelle für „gesperrt“ — dort schreibt nur der Verzeichnisabgleich.
- Verbundene Konten nur für Konten mit bekanntem Zustand — schließt jedes Haus mit reinem OIDC aus.
- Deaktivierter Anbieter als Deaktivierung seiner Konten — ein vorübergehendes Abschalten löschte massenhaft.
- Zahlen je Anbieter, auch „mindestens N“ — hebt die Maskierung je Zugang auf.
- Inaktivitätssperre lokaler Konten als Deaktivierung — eine Abwesenheit, kein Entzug (Maintainer, #2260).
- Zweite Tabelle für MCP-Server — dieselben Konten, Token und Protokolle.
- Beginn der Frist bei jedem Abgleich verwerfen, der den Issuer wieder zuordnet — genau dieser
  Abgleich bleibt im Fehlerfall aus.

## Offen

- Der Schalter der lokalen Kontenverwaltung (`updateLocalAuthSettings`) fragt nicht nach; dort ruhen
  die Verbindungen nur.
- Nutzungsfreigabe für MCP-Server und die Modulkante von assistant bzw. external (Folge-Epic zu #1747).
