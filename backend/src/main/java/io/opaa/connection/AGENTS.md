# Modul connections

Pakete (`io.opaa.*`): connection. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Verbindungsprofile („Zugänge“), die Zuordnung einer Bibliothek zu einem Profil, die
Konnektor-Freigabe, die Sperre von Quellart und Zugang und das Verbindungsprotokoll (ADR-0041,
`docs/features/connector-connections.md`); Token-Speicher, verbundene Konten und OAuth folgen.
connections hängt nur von foundation, identity, rights und knowledge ab; nur library hängt von
connections ab. Kein Konnektor und nichts in knowledge kennt connections: Der Kern erreicht es über
seine Ports `SourceConnectionResolver` (`ProfileSourceConnectionResolver`) und `SourceStateLookup`.

## Invarianten und Stolpersteine

- **Unterpakete, unten zuerst:** `connection.log`, `connection.profile` (Profile, Zuordnung,
  `ServerAddress`, Sperren, `ConnectorScope`). Das Wurzelpaket verdrahtet und implementiert den
  Port, `connection.web` liegt darüber. Kein Unterpaket nennt das Wurzelpaket
  (`ModularArchitecture.CONNECTION_PACKAGES`).
- **Verbindungsprotokoll** wie `audit_log`: schreibt nur `ConnectionLog` (in der Transaktion des
  Aufrufers), liest nur `ConnectionLogQueryService` (`AUDITOR`), löscht nur die Datenbankfunktion.
- **Das Client-Secret ist schreibgeschützt.** `ConnectionProfileService` verschlüsselt es mit
  `CredentialsEncryptor`; Antworten tragen nur `clientSecretSet`, das Audit nur Feldnamen und
  Ja/Nein. Kein Code gibt es heraus, bis ein Konsument es braucht (OAuth, #2168).
- **Ursprungsbindung:** Die Adresse einer zugeordneten Bibliothek liegt unter der Server-Adresse
  ihres Profils (`ServerAddress#covers`), sonst sperrt der Port. Eine neue Server-Adresse verschiebt
  die Adressen der Bibliotheken und verwirft alle Geheimnisse des Profils.
- **Lesen und Verwerfen des Geheimnisses** in connections nur über `ConnectionSecrets`, library
  über den Port; der Schreibweg der Bibliothek bleibt `updateSourceConfiguration`.
- **Ein gelöschtes Profil** lässt die Zuordnung mit `profile_id NULL` stehen („Zugang entfernt“);
  der Port sperrt dann mit eigenem Grund und deutscher Meldung.
- **Sperrgründe nur in `SourceBlocks`:** Ob und warum eine Bibliothek gesperrt ist und welcher Text
  gilt, entscheidet nur er. Vorrang ist die Deklarationsreihenfolge von `SourceBlock.Reason`, die
  Mengen der Aufrufer leiten sich aus dessen Eigenschaften ab: Ein neuer Grund steht an einer Stelle.
- **Eine Zusammenführung:** `EffectiveSourceSettings` setzt Lauf, Änderung und Einstellungen
  zusammen; Konnektor-Vorgaben (nur deklarierte, `ProfileDefaults#read`) überschreiben die der
  Bibliothek je Schlüssel. Nur `applyChange` bekommt den eigenen Teil (`SourceChangeGate`).
- **Freigabe nur hier:** `ConnectorReleaseService` entscheidet eine Neuanlage aus
  `CREATE_CONNECTOR_LIBRARY` im Geltungsbereich `TYPE:`/`PROFILE:` und den Sperren;
  `ConnectorScopeCatalog` nennt rights die Geltungsbereiche. Kein anderes Modul außer rights nennt
  die Fähigkeit (`theConnectorReleaseIsDecidedInConnections`). Ein Entzug stoppt keinen Lauf.
- **Sperre:** Der Port blockiert `resolve` (Laufstart, Original), nicht `currentCredentials`; ein
  laufender Lauf endet regulär. `ConnectorLockService` holt die Registry je Aufruf, weil der Port
  im Kern hängt und die Konnektoren am Kern.
- **Web-Schicht:** `connection.web` (Profile, Auswahl, Verbindungsprotokoll und seine Frist). Die
  Zuordnung einer Bibliothek liegt in `library.web`.

## Verweise

- ADRs (`docs/decisions/`): 0025, 0036, 0038, 0041
- Handbuch: `docs/handbuch/indexierung.md`, „Zugänge“; `bibliotheken-und-berechtigungen.md`, 12
- Strukturtests: `ModularArchitectureTest`, `ConnectionLogStructureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.connection.*' --tests 'io.opaa.architecture.*'
```

Schemaänderungen: neue Datei unter `db/changelog/connections/` (`backend/AGENTS.md`, „Liquibase“).
