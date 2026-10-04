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
- **Das Client-Secret ist schreibgeschützt.** `ConnectionProfileService` verschlüsselt es mit
  `CredentialsEncryptor`; Antworten tragen nur `clientSecretSet`, das Audit nur Feldnamen und
  Ja/Nein. Kein Code gibt es heraus, bis ein Konsument es braucht (OAuth, #2168).
- **Ursprungsbindung:** Die Adresse einer zugeordneten Bibliothek liegt unter der Server-Adresse
  ihres Profils (`ServerAddress#covers`), sonst sperrt der Port. Ein Geheimnis folgt nur bei
  `ServerAddress#sameOrigin` (die eine Regel) und `keepsCredentials`. Eine neue Server-Adresse
  verschiebt die Adressen der Bibliotheken und verwirft alle Geheimnisse des Profils.
- **Lesen und Verwerfen des Geheimnisses** in connections nur über `ConnectionSecrets`, library
  über den Port; der Schreibweg der Bibliothek bleibt `updateSourceConfiguration`.
- **Ein gelöschtes Profil** lässt die Zuordnung mit `profile_id NULL` stehen („Zugang entfernt“);
  sie zählt als eigene Adresse, entschieden nur in `LibraryConnection#throughProfile`.
- **Sperrgründe nur in `SourceBlocks`:** Ob und warum eine Bibliothek gesperrt ist und welcher Text
  gilt, entscheidet nur er. Vorrang ist die Deklarationsreihenfolge von `SourceBlock.Reason`, die
  Mengen der Aufrufer leiten sich aus dessen Eigenschaften ab: Ein neuer Grund steht an einer Stelle.
- **Eine Zusammenführung:** `EffectiveSourceSettings` setzt Lauf, Änderung und Entwurf
  (`ofDraft`: Anlegen, Test, Auflistung) in einem `compose` zusammen. Der Rahmen des Zugangs
  (`ProfileFrame`: `TransportRules` der Server-Adresse, `boundKeys`) überschreibt die Bibliothek;
  abweichend ist `400`, gespeichert nur der eigene Teil (`ownPart`, auch beim Zuordnen).
- **Freigabe nur hier:** `ConnectorReleaseService` entscheidet eine Neuanlage aus
  `CREATE_CONNECTOR_LIBRARY` im Geltungsbereich `TYPE:`/`PROFILE:` und den Sperren;
  `ConnectorScopeCatalog` nennt rights die Geltungsbereiche. Kein anderes Modul außer rights nennt
  die Fähigkeit (`theConnectorReleaseIsDecidedInConnections`). Ein Entzug stoppt keinen Lauf.
- **Profilangabe nur über `ProfileRequirements`:** deklariert `OPTIONAL` plus Schalter ergibt
  `REQUIRED`; nur dort liest connections `support` (`theProfileSupportIsReadInOnePlace`).
- **Sperre:** blockiert `resolve` (Laufstart, Original), nicht `currentCredentials`; ein Entwurf auf
  einem anderen Zugang prüft nur dessen Sperren. Registry je Aufruf (Bean-Zyklus).
- **Web-Schicht:** `connection.web` (Profile, Wünsche, Protokoll, Frist); Zuordnung in `library.web`.

## Verweise

- ADRs (`docs/decisions/`): 0025, 0036, 0038, 0041
- Handbuch: `docs/handbuch/indexierung.md`, „Zugänge“; `bibliotheken-und-berechtigungen.md`, 12
- Strukturtests: `ModularArchitectureTest`, `ConnectionLogStructureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.connection.*' --tests 'io.opaa.architecture.*'
```

Schemaänderungen: neue Datei unter `db/changelog/connections/`, Regeln in `backend/AGENTS.md`.
