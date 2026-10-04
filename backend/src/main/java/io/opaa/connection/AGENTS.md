# Modul connections

Pakete (`io.opaa.*`): connection. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Verbindungsprofile („Zugänge“), die Zuordnung einer Bibliothek zu einem Profil, die
Konnektor-Freigabe und die Sperre von Quellart und Zugang (ADR-0041, Spezifikation
`docs/features/connector-connections.md`). Später kommen Token-Speicher, verbundene
Konten, OAuth und das Verbindungsprotokoll dazu. connections hängt nur von foundation, identity,
rights und knowledge ab; nur library hängt von connections ab. Kein Konnektor und nichts in
knowledge kennt connections: Der Kern erreicht es über seine Ports `SourceConnectionResolver`
(`ProfileSourceConnectionResolver`) und `SourceStateLookup` (`ConnectionSourceStateLookup`).

## Invarianten und Stolpersteine

- **Unterpakete, unten zuerst:** `connection.profile` (Profile, Zuordnung, `ServerAddress`,
  Sperren, Profilpflicht, `ConnectorScope`). Das Wurzelpaket verdrahtet und implementiert den Port,
  `connection.web` liegt darüber. Kein Unterpaket nennt das Wurzelpaket
  (`ModularArchitecture.CONNECTION_PACKAGES`).
- **Das Client-Secret ist schreibgeschützt.** `ConnectionProfileService` verschlüsselt es mit
  `CredentialsEncryptor`; Antworten tragen nur `clientSecretSet`, das Audit nur Feldnamen und
  Ja/Nein. Kein Code gibt es heraus, bis ein Konsument es braucht (OAuth, #2168).
- **Ursprungsbindung:** Die Adresse einer zugeordneten Bibliothek liegt unter der Server-Adresse
  ihres Profils (`ServerAddress#covers`), sonst sperrt der Port. Eine neue Server-Adresse verschiebt
  die Adressen der Bibliotheken und verwirft alle Geheimnisse des Profils.
- **Geheimnis verwerfen** heißt `KnowledgeLibraryRepository#eraseSourceCredentials` auf der Spalte,
  nicht nur `null` an der Entität: Ohne Schlüssel liest die Entität ohnehin `null`.
- **Ein gelöschtes Profil** lässt die Zuordnung mit `profile_id NULL` stehen („Zugang entfernt“);
  der Port sperrt dann mit eigenem Grund und deutscher Meldung.
- **Sperrgründe nur in `SourceBlocks`:** Ob und warum eine Bibliothek gesperrt ist und welcher Text
  gilt, entscheidet nur er. Vorrang ist die Deklarationsreihenfolge von `SourceBlock.Reason`, die
  Mengen der Aufrufer leiten sich aus dessen Eigenschaften ab: Ein neuer Grund steht an einer Stelle.
- **Konnektor-Vorgaben** des Profils überschreiben die Einstellungen der Bibliothek je Schlüssel.
  Verwaltungspfade arbeiten mit den gespeicherten, Lauf und Push-Eingang mit den zusammengeführten
  (`SourceConnectionResolver#effectiveSettings`).
- **Freigabe nur hier:** `ConnectorReleaseService` entscheidet eine Neuanlage aus
  `CREATE_CONNECTOR_LIBRARY` im Geltungsbereich `TYPE:`/`PROFILE:` und den Sperren;
  `ConnectorScopeCatalog` nennt rights die Geltungsbereiche. Kein anderes Modul außer rights nennt
  die Fähigkeit (`theConnectorReleaseIsDecidedInConnections`). Ein Entzug stoppt keinen Lauf.
- **Profilangabe nur über `ProfileRequirements`:** deklariert `OPTIONAL` plus Schalter ergibt
  `REQUIRED`; kein anderer Code liest die Deklaration oder vergleicht sie mit `REQUIRED`.
- **Sperre:** blockiert `resolve` (Laufstart, Original), nicht `currentCredentials`. Die Registry
  wird je Aufruf geholt, weil der Port im Kern hängt und die Konnektoren am Kern.
- **Web-Schicht:** `connection.web` (Verwaltung unter `/api/v1/admin/connection-profiles`, Auswahl
  unter `/api/v1/connection-profiles`). Die Zuordnung einer Bibliothek liegt in `library.web`.

## Verweise

- ADRs (`docs/decisions/`): 0025, 0036, 0038, 0041
- Handbuch: `docs/handbuch/indexierung.md`, Abschnitt „Zugänge“
- Strukturtests: `ModularArchitectureTest` (`theConnectionPackagesDependOnlyDownward`)

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.connection.*' --tests 'io.opaa.architecture.*'
```

Schemaänderungen: neue Datei unter `db/changelog/connections/`, Regeln in `backend/AGENTS.md`.
