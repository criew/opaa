# Modul connections

Pakete (`io.opaa.*`): connection. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Verbindungsprofile („Zugänge“) und die Zuordnung einer Bibliothek zu einem Profil (ADR-0041,
Spezifikation `docs/features/connector-connections.md`). Später kommen Token-Speicher, verbundene
Konten, OAuth und das Verbindungsprotokoll dazu. connections hängt nur von foundation, identity,
rights und knowledge ab; nur library hängt von connections ab. Kein Konnektor und nichts in
knowledge kennt connections: Der Kern erreicht es über seinen Port `SourceConnectionResolver`, den
`ProfileSourceConnectionResolver` implementiert.

## Invarianten und Stolpersteine

- **Unterpakete, unten zuerst:** `connection.profile` (Profile, Zuordnung, `ServerAddress`).
  Das Wurzelpaket verdrahtet und implementiert den Port, `connection.web` liegt darüber. Kein
  Unterpaket nennt das Wurzelpaket (`ModularArchitecture.CONNECTION_PACKAGES`).
- **Das Client-Secret ist schreibgeschützt.** `ConnectionProfileService` verschlüsselt es mit
  `CredentialsEncryptor`; Antworten tragen nur `clientSecretSet`, das Audit nur Feldnamen und
  Ja/Nein. Kein Code gibt es heraus, bis ein Konsument es braucht (OAuth, #2168).
- **Ursprungsbindung:** Die Adresse einer zugeordneten Bibliothek liegt unter der Server-Adresse
  ihres Profils (`ServerAddress#covers`), sonst sperrt der Port. Eine neue Server-Adresse verschiebt
  die Adressen der Bibliotheken und verwirft alle Geheimnisse des Profils.
- **Geheimnis verwerfen** heißt `KnowledgeLibraryRepository#eraseSourceCredentials` auf der Spalte,
  nicht nur `null` an der Entität: Ohne Schlüssel liest die Entität ohnehin `null`.
- **Ein gelöschtes Profil** lässt die Zuordnung mit `profile_id NULL` stehen („Zugang entfernt“);
  der Port sperrt dann mit eigener Kategorie und deutscher Meldung.
- **Konnektor-Vorgaben** des Profils überschreiben die Einstellungen der Bibliothek je Schlüssel.
  Verwaltungspfade arbeiten mit den gespeicherten, Lauf und Push-Eingang mit den zusammengeführten
  (`SourceConnectionResolver#effectiveSettings`).
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

Bei Schemaänderungen: neue Datei unter `db/changelog/connections/`. Regeln und Tests in
`backend/AGENTS.md`, „Liquibase: Changelog je Modul“.
