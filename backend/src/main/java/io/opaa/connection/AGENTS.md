# Modul connections

Pakete (`io.opaa.*`): connection. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Verbindungsprofile („Zugänge“), Zuordnung einer Bibliothek, Konnektor-Freigabe, Sperren,
Zugangswünsche und das Verbindungsprotokoll (ADR-0041, `docs/features/connector-connections.md`).
connections hängt nur von foundation, identity, rights und knowledge ab; nur library hängt von
connections ab. Kein Konnektor und nichts in knowledge kennt connections: Der Kern erreicht es über
seine Ports `SourceConnectionResolver` (`ProfileSourceConnectionResolver`) und `SourceStateLookup`.

## Invarianten und Stolpersteine

- **Unterpakete, unten zuerst:** `connection.log`, `connection.profile`, `connection.request`; das
  Wurzelpaket verdrahtet den Port, `connection.web` darüber. Kein Unterpaket nennt das Wurzelpaket.
- **Zugangswunsch:** Grenzen je Person aus der Tabelle; die Begründung nie in Log oder Audit.
- **Verbindungsprotokoll** wie `audit_log`: schreibt nur `ConnectionLog` (in der Transaktion des
  Aufrufers), liest nur `ConnectionLogQueryService` (`AUDITOR`), löscht nur die Datenbankfunktion.
- **Das Client-Secret ist schreibgeschützt:** `ConnectionProfileService` verschlüsselt es;
  Antworten und Audit sagen nur Ja/Nein. Kein Code gibt es heraus, bis OAuth es braucht (#2168).
- **Ursprungsbindung:** Die Adresse einer zugeordneten Bibliothek liegt unter der Server-Adresse
  ihres Profils (`ServerAddress#covers`), sonst sperrt der Port. Ein Geheimnis folgt nur zum selben
  `SecretTarget` (`sameOrigin` plus `credentialBinding` des Konnektors), dessen `key()` das Ziel
  für `ConnectionSecrets#current` ist. Eine neue Server-Adresse verwirft alle Geheimnisse.
- **Übergänge** (Profiländerung, Zuordnen, Lösen) sind je Bibliothek ein `SourceTransitions.Move`
  durch `SourceChangeGate`: erst alle prüfen (`Answers`, je Konfiguration einmal, bei der
  Profiländerung vor der Schreibtransaktion), dann schreiben. Ablehnung: `ChangeRejection`.
- **Geheimnis** lesen und verwerfen nur über `ConnectionSecrets`, library über den Port.
- **Ein gelöschtes Profil** lässt die Zuordnung mit `profile_id NULL` stehen („Zugang entfernt“);
  sie zählt als eigene Adresse, entschieden nur in `LibraryConnection#throughProfile`.
- **Sperrgründe nur in `SourceBlocks`** (ob, warum, welcher Text). Vorrang ist die Reihenfolge von
  `SourceBlock.Reason`, die Mengen der Aufrufer folgen aus dessen Eigenschaften.
- **Eine Zusammenführung:** `EffectiveSourceSettings` setzt Lauf, Änderung und Entwurf
  (`ofDraft`: Anlegen, Test, Auflistung) in einem `compose` zusammen. Der Rahmen des Zugangs
  (`ProfileFrame`: `TransportRules` der Server-Adresse, `boundKeys`) überschreibt die Bibliothek;
  abweichend ist `400`, gespeichert nur der eigene Teil; Lösen schreibt den Rahmen ein.
- **Freigabe nur hier:** `ConnectorReleaseService` entscheidet eine Neuanlage aus
  `CREATE_CONNECTOR_LIBRARY` (`TYPE:`/`PROFILE:`, `ConnectorScopeCatalog`) und den Sperren; nur
  rights nennt die Fähigkeit sonst (`theConnectorReleaseIsDecidedInConnections`). Ein Entzug
  stoppt keinen Lauf.
- **Profilangabe nur über `ProfileRequirements`:** deklariert `OPTIONAL` plus Schalter ergibt
  `REQUIRED`; nur dort liest connections `support` (`theProfileSupportIsReadInOnePlace`).
- **Sperre:** blockiert `resolve`, nicht `currentCredentials`; ein Entwurf auf einem anderen Zugang
  prüft nur dessen Sperren. Registry je Aufruf (Bean-Zyklus).
- **Web-Schicht:** `connection.web`, auch Wünsche; Zuordnung, Optionen mit `libraryId`: `library.web`.

## Verweise

- ADRs (`docs/decisions/`): 0025, 0036, 0038, 0041
- Handbuch: `docs/handbuch/indexierung.md`, „Zugänge“; `bibliotheken-und-berechtigungen.md`, 12
- Strukturtests: `ModularArchitectureTest`, `ConnectionLogStructureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.connection.*' --tests 'io.opaa.architecture.*'
```

Schemaänderungen: neue Datei unter `db/changelog/connections/`, Regeln in `backend/AGENTS.md`.
