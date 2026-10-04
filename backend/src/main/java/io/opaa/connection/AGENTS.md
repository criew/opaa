# Modul connections

Pakete (`io.opaa.*`): connection. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Verbindungsprofile („Zugänge“), Zuordnung einer Bibliothek, Konnektor-Freigabe, Sperren,
Verbindungsprotokoll, Token-Speicher und verbundene Konten (ADR-0041,
`docs/features/connector-connections.md`); OAuth folgt. connections hängt nur von foundation,
identity, rights und knowledge ab; nur library hängt von connections ab. Der Kern erreicht es über
seine Ports `SourceConnectionResolver` (`ProfileSourceConnectionResolver`) und `SourceStateLookup`.

## Invarianten und Stolpersteine

- **Unterpakete, unten zuerst:** `connection.log`, `connection.token`, `connection.profile`,
  `connection.account`; das Wurzelpaket verdrahtet und implementiert den Port, `connection.web`
  darüber. Kein Unterpaket nennt das Wurzelpaket. Nach oben nur über Ports: `LibrariesOnProfile`,
  `PersonAccounts`, `SecretIssuer` (token), `PersonConnections` (profile).
- **Verbindungsprotokoll** wie `audit_log`: schreibt nur `ConnectionLog` (in der Transaktion des
  Aufrufers), liest nur `ConnectionLogQueryService` (`AUDITOR`), löscht nur die Datenbankfunktion.
  Die Verbindung einer privaten Bibliothek ist immer die der Person (`ConnectionLogOwner.Person`).
- **Geheimnisse liest, speichert und verwirft nur `ConnectionSecrets`** (token) über
  `SecretOwner.of`; library nur über den Port. Das Geheimnis einer Person geht nur heraus, solange
  ihr Konto nutzbar ist (`AccountUsability` mit Schwelle), und nur an das Ziel, für das es ausgestellt
  ist: `SecretTarget#key` (`sameOrigin` plus `credentialBinding`) ist die eine Zielfunktion.
  Verwerfen je Bibliothek trifft nur deren eigenes Geheimnis (`LibraryOwned`), nie das geteilte
  einer Person; das endet nur mit dem Konto (`ConnectedAccountService#end`).
- **„Ruhend“ und „deaktiviert“ werden abgeleitet, nie gespeichert** (gespeichert: `CONNECTED`,
  `EXPIRED`, `DISCONNECTED`). Eine getrennte Zeile bleibt, solange eine private Bibliothek daran
  hängt (Trigger: private Bibliothek nur auf Personen-Zugang mit Konto, auch nach Besitzartwechsel).
- **Die Verwaltung sieht Konten nur über `PersonNumbers`** („weniger als N“), Bibliothekszahlen ohne
  private, Kontoname und Client-Secret nie. Eine private vetiert keine Profiländerung, sie wird
  gelöst (`PrivateLibraryRelease`); Konto, Ziel, Entwurf prüft `PrivateLibraryConnections`.
- **Ursprungsbindung:** Die Adresse einer zugeordneten Bibliothek liegt unter der Server-Adresse
  ihres Profils, sonst sperrt der Port. Eine neue Server-Adresse oder Registrierung verwirft alle
  Geheimnisse und beendet die verbundenen Konten; ein gelöschtes Profil lässt `profile_id NULL`.
- **Übergänge** (Profiländerung, Zuordnen, Lösen) sind je Bibliothek ein `SourceTransitions.Move`
  durch `SourceChangeGate`: erst Bestätigung, dann alle prüfen (`Answers`), dann schreiben.
- **Sperrgründe nur in `SourceBlocks`** (ob, warum, Text); Vorrang ist die Enum-Reihenfolge.
- **Eine Zusammenführung:** `EffectiveSourceSettings` setzt Lauf, Änderung und Entwurf (`ofDraft`,
  auch `DraftOwner.PERSON`) zusammen; der Rahmen des Zugangs (`ProfileFrame`) überschreibt die
  Bibliothek, abweichend ist `400`; Lösen schreibt den Rahmen ein.
- **Freigabe nur hier:** `ConnectorReleaseService` (Bibliothek) und `requireConnectable` (neues
  Konto) aus `CREATE_CONNECTOR_LIBRARY` und Sperren; ein Entzug stoppt nichts.
- **Profilangabe nur über `ProfileRequirements`;** Sperre blockiert `resolve`, nicht
  `currentCredentials`; Registry je Aufruf (Bean-Zyklus); Web-Schicht `connection.web`.

## Verweise

- ADRs (`docs/decisions/`): 0025, 0036, 0038, 0041
- Handbuch: `docs/handbuch/indexierung.md`, „Zugänge“; `bibliotheken-und-berechtigungen.md`, 12
- Strukturtests: `ModularArchitectureTest`, `ConnectionLogStructureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.connection.*' --tests 'io.opaa.architecture.*'
```

Schemaänderungen: neue Datei unter `db/changelog/connections/`, Regeln in `backend/AGENTS.md`.
