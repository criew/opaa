# Modul connections

Pakete (`io.opaa.*`): connection. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Verbindungsprofile („Zugänge“), Zuordnung einer Bibliothek, Konnektor-Freigabe, Sperren,
Verbindungsprotokoll, Token-Speicher, verbundene Konten, Zugangswünsche und die Anmeldung des
Zugangs (ADR-0041, `docs/features/connector-connections.md`). connections hängt nur von foundation,
identity, rights und knowledge ab; nur library hängt von connections ab. Der Kern erreicht es über
seine Ports `SourceConnectionResolver` (`ProfileSourceConnectionResolver`) und `SourceStateLookup`.

## Invarianten und Stolpersteine

- **Unterpakete, unten zuerst:** `connection.log`, `connection.token`, `connection.profile`,
  `connection.request` (Zugangswünsche), `connection.consent` (Quelle verbinden),
  `connection.account`, `connection.oauth`; das Wurzelpaket verdrahtet den Port, `connection.web`
  darüber; keines nennt es. Nach oben nur über Ports: `LibrariesOnProfile`, `PersonAccounts`,
  `SecretIssuer`, `SourceConsentRejections` (token), `PersonConnections`, `SourceConsentEnds`.
- **Verbindungsprotokoll** wie `audit_log`: schreibt nur `ConnectionLog` (in der Transaktion des
  Aufrufers), liest nur `ConnectionLogQueryService` (`AUDITOR`), löscht nur die Datenbankfunktion.
  Die Verbindung einer privaten Bibliothek ist immer die der Person (`ConnectionLogOwner.Person`).
- **Geheimnisse liest, speichert und verwirft nur `ConnectionSecrets`** (token) über
  `SecretOwner.of`; library nur über den Port. Das Geheimnis einer Person geht nur heraus, solange
  ihr Konto nutzbar ist (`AccountUsability` mit Schwelle), und nur an das Ziel, für das es ausgestellt
  ist: `SecretTarget#key` (`sameOrigin` plus `credentialBinding`) ist die eine Zielfunktion.
  Verwerfen je Bibliothek trifft nur deren eigenes Geheimnis (`ownedByOneLibrary`), nie das geteilte
  einer Person; das endet nur mit dem Konto. Die OAuth-Zustimmung einer Bibliothek
  (`SourceConsent`) prüft keine Person; vor Anlage wartet sie als `PendingConsent` ihrer Person.
- **„Ruhend“/„deaktiviert“ werden abgeleitet;** `ConnectionLifecycleReconciler` beendet nur bei
  `DEACTIVATED`, je Person in eigener Transaktion; die getrennte Zeile bleibt für private Bibliotheken.
- **Die Verwaltung sieht verbundene Konten nur über `PersonNumbers`** („weniger als N“, auch bei 0,
  für Teilzahlen und Schwellenwarnung; je Anbieter keine Zahl), private Bibliotheken nach
  Besitzerinnen (`PersonThreshold`); Kontoname verschlüsselt. Eine private vetiert, zählt, blockiert
  keine Zugangsänderung; gelöst, Besitzerin benachrichtigt (`PrivateLibraryRelease`).
- **Registrierung des Zugangs** (Secret/Schlüssel nur Ja/Nein) gibt nur `registrationOf` heraus, nur
  an `connection.oauth`; dort erreicht nur `OAuthClient` den Anbieter. Refresh-Tokens verlassen
  token/oauth nie; Erneuerung unter Zeilensperre, Widerruf erst nach Commit (alles ArchUnit/ADR-0041).
- **Ursprungsbindung:** Bibliotheksadresse unter der Server-Adresse des Profils, sonst sperrt der Port.
  Neue Adresse/Registrierung verwirft vorher alle Geheimnisse, beendet die Konten; gelöscht: `NULL`.
- **Übergänge** (Profiländerung, Zuordnen, Lösen) sind je Bibliothek ein `SourceTransitions.Move` durch `SourceChangeGate`: erst Bestätigung, dann alle prüfen (`Answers`), dann schreiben. Je Zugang serialisiert über die Profilzeile, immer vor jeder anderen Zeile (ADR-0041, Nachtrag #2246).
- **Eine Zusammenführung:** `EffectiveSourceSettings` setzt Lauf, Änderung und Entwurf (`ofDraft`,
  auch `DraftOwner.PERSON`) zusammen; der Rahmen des Zugangs (`ProfileFrame`) überschreibt die
  Bibliothek, abweichend ist `400`; Lösen schreibt den Rahmen ein, eine entfallende Vorgabe geht in den eigenen Teil (`Move#keptDefaults`).
- **Freigabe nur hier:** `ConnectorReleaseService` (Bibliothek) und `requireConnectable` (neues Konto) aus `CREATE_CONNECTOR_LIBRARY` und Sperren; ein Entzug stoppt nichts.
- **MCP-Server** (`ProfileKind.MCP_SERVER`, ohne Quellart): Konnektor-Pfade listen nur `findConnectorsByName` (ArchUnit); Token nur über `McpServerTokens`, gebunden an den Ressourcen-Indikator (`issued_for`).
- **Sperrgründe nur in `SourceBlocks`** (Vorrang: Enum-Reihenfolge), Profilangabe nur über `ProfileRequirements`. Eine Sperre blockiert `resolve`, nicht `currentCredentials`.

## Verweise

- ADRs (`docs/decisions/`): 0025, 0036, 0038, 0041; Handbuch: `docs/handbuch/indexierung.md`, „Zugänge“; `bibliotheken-und-berechtigungen.md`, 12
- Strukturtests: `ModularArchitectureTest`, `ConnectionLogStructureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.connection.*' --tests 'io.opaa.architecture.*'
```

Schemaänderungen: neue Datei unter `db/changelog/connections/`, Regeln in `backend/AGENTS.md`.
