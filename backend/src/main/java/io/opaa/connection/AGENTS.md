# Modul connections

Pakete (`io.opaa.*`): connection. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Verbindungsprofile („Zugänge“), Zuordnung einer Bibliothek, Konnektor-Freigabe, Sperren, Verbindungsprotokoll,
Token-Speicher, verbundene Konten und die Anmeldung des Zugangs (`docs/features/connector-connections.md`). Der
Kern erreicht es über die Ports `SourceConnectionResolver` (`ProfileSourceConnectionResolver`) und `SourceStateLookup`.

## Invarianten und Stolpersteine

- **Verbindungsprotokoll** wie `audit_log`: schreibt nur `ConnectionLog` (Transaktion des Aufrufers), liest nur
  `ConnectionLogQueryService` (`AUDITOR`) und die Selbstauskunft (eigene Einträge), löscht nur die DB-Funktion.
  Die Verbindung einer privaten Bibliothek ist immer die der Person (`ConnectionLogOwner.Person`).
- **Geheimnisse liest, speichert und verwirft nur `ConnectionSecrets`** (token) über `SecretOwner.of`,
  library nur über den Port. Das Geheimnis einer Person geht nur heraus, solange ihr Konto nutzbar ist
  (`AccountUsability` mit Schwelle), und nur an sein Ziel: `SecretTarget#key` (`sameOrigin` plus
  `credentialBinding`) ist die eine Zielfunktion; das eigene Geheimnis einer Bibliothek liegt nach ADR-0048,
  Entscheidung 9. Verwerfen je Bibliothek trifft nur deren eigenes Geheimnis (`ownedByOneLibrary`), nie das
  geteilte einer Person, das nur mit dem Konto endet. Die OAuth-Zustimmung einer Bibliothek (`SourceConsent`)
  prüft keine Person; vor Anlage wartet sie als `PendingConsent` ihrer Person.
- **Sperrreihenfolge und Übergänge** nach ADR-0041, Entscheidung 10: Profil-, Token-, Kontozeile; ein Übergang
  ist je Bibliothek ein `SourceTransitions.Move` durch `SourceChangeGate` (erst Bestätigung, alle prüfen, schreiben).
- **„Ruhend“/„deaktiviert“ werden abgeleitet;** `ConnectionLifecycleReconciler` beendet nur bei
  `DEACTIVATED`, je Person in eigener Transaktion; die getrennte Zeile bleibt für private Bibliotheken.
- **Zahlen über Personen** nur über `PersonNumbers` und `PersonThreshold` (ADR-0041, Entscheidung 9;
  ADR-0049, Entscheidung 1); Kontoname verschlüsselt. Eine private Bibliothek blockiert keine
  Zugangsänderung, sie wird gelöst (`PrivateLibraryRelease`, ADR-0049, Entscheidung 3).
- **Zum Anbieter nur `OAuthClient`** in `connection.oauth`, Registrierung nur über `registrationOf`
  (Secret/Schlüssel nur Ja/Nein); Erneuerung und Widerruf nach ADR-0048, Entscheidungen 4 und 5
  (`GrantRevocations`, ArchUnit). Refresh-Tokens verlassen token/oauth nie.
- **Ursprungsbindung:** Bibliotheksadresse unter der Server-Adresse des Profils, sonst sperrt der Port. Endpunkte
  nur `https://`. Neue Adresse/Registrierung verwirft vorher alle Geheimnisse, beendet die Konten; gelöscht: `NULL`.
- **Eine Zusammenführung:** `EffectiveSourceSettings` setzt Lauf, Änderung und Entwurf (`ofDraft`, auch
  `DraftOwner.PERSON`) zusammen; der Rahmen des Zugangs (`ProfileFrame`) überschreibt die Bibliothek, abweichend
  ist `400`; Lösen schreibt den Rahmen ein, eine entfallende Vorgabe geht in den eigenen Teil (`Move#keptDefaults`).
- **Freigabe nur hier:** `ConnectorReleaseService` (Bibliothek) und `requireConnectable` (neues
  Konto) aus `CREATE_CONNECTOR_LIBRARY` und Sperren; ein Entzug stoppt nichts.
- **MCP-Server** (`ProfileKind.MCP_SERVER`, ohne Quellart): Konnektor-Pfade listen nur `findConnectorsByName`
  (ArchUnit); Token nur über `McpServerTokens`, gebunden an `issued_for`; der Rücksprung prüft `iss` (RFC 9207).
- **Schwellen und Fristen** nur über `ConnectionLifecycleSettings`, je Aufruf aus der Datenbank, nie gemerkt;
  ändern nur `ConnectionLifecycleAdministration` (Revisionsereignis, Abschnitt `connectionLifecycle`).
- **Sperrgründe nur in `SourceBlocks`** (Vorrang: Enum-Reihenfolge), Profilangabe nur über
  `ProfileRequirements`. Eine Sperre blockiert `resolve`, nicht `currentCredentials`.

## Maßgebliche ADRs

- [ADR-0015](../../../../../../../docs/decisions/0015-eigentuemertrennung-protokollablage.md) (Entscheidungen 1, 2): Eigentümertrennung des Verbindungsprotokolls
- [ADR-0036](../../../../../../../docs/decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) (Entscheidung 5): Fähigkeit mit Geltungsbereich
- [ADR-0038](../../../../../../../docs/decisions/0038-steckbare-konnektoren.md) (Entscheidungen 3, 7): Geheimnisse, Profilangabe
- [ADR-0041](../../../../../../../docs/decisions/0041-verbindungen-als-eigenes-modul.md): Modul, Zugänge, Token-Speicher
- [ADR-0048](../../../../../../../docs/decisions/0048-oauth-kern-und-zugangs-geheimnisse.md): OAuth-Kern, Geheimnisse am Zugang
- [ADR-0049](../../../../../../../docs/decisions/0049-private-bibliotheken-und-kontingente.md) (Entscheidungen 1, 3): private Bibliotheken am Zugang

## Verweise

- Handbuch: `docs/handbuch/arbeitsmittel/zugaenge.md`; `berechtigungen.md`, 12

## Tests bei Änderungen

`./gradlew test --tests 'io.opaa.connection.*' --tests 'io.opaa.architecture.*'`
