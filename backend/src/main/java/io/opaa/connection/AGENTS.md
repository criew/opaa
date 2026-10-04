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
- **Geheimnisse liest, speichert und verwirft nur `ConnectionSecrets`** (token), adressiert über
  `SecretOwner.of` (die eine Wahl); library nur über den Port. Entschlüsselt wird nur dort, Antworten
  tragen nur Zustände. Das Geheimnis einer Person geht nur heraus, solange ihr Konto nutzbar ist
  (`AccountUsability` mit Schwelle) und nur an `ConnectionProfile#secretTarget`.
- **„Ruhend“ und „deaktiviert“ werden abgeleitet, nie gespeichert;** gespeichert sind nur
  `CONNECTED`, `EXPIRED`, `DISCONNECTED`. Ein verbundenes Konto endet nur über
  `ConnectedAccountService#end`; die Zeile bleibt `DISCONNECTED`, solange eine private Bibliothek
  daran hängt (Trigger: private Bibliothek nur auf Personen-Zugang mit verbundenem Konto).
- **Die Verwaltung sieht verbundene Konten nur als Zahlen** (`ConnectedAccountCounts`, „weniger
  als N“ unter der Mindestgruppengröße); Kontoname verschlüsselt, nur für die Person.
- **Das Client-Secret ist schreibgeschützt;** Antworten tragen nur `clientSecretSet`.
- **Ursprungsbindung:** Die Adresse einer zugeordneten Bibliothek liegt unter der Server-Adresse
  ihres Profils (`ServerAddress#covers`), sonst sperrt der Port. Ein Geheimnis folgt nur bei
  `ServerAddress#sameOrigin` und `keepsCredentials`. Neue Server-Adresse oder Registrierung
  verwirft alle Geheimnisse des Profils (`discardAllUnder`) und beendet die verbundenen Konten. Ein
  gelöschtes Profil lässt die Zuordnung mit `profile_id NULL` stehen („Zugang entfernt“).
- **Sperrgründe nur in `SourceBlocks`:** Vorrang ist die Deklarationsreihenfolge von
  `SourceBlock.Reason`, die Mengen der Aufrufer leiten sich aus dessen Eigenschaften ab. Die Gründe
  des Speichers (`SecretRefusedException`) formuliert `SourceBlocks#secretBlock`.
- **Eine Zusammenführung:** `EffectiveSourceSettings` setzt Lauf, Änderung und Entwurf (`ofDraft`,
  auch `DraftOwner.PERSON` vor dem Verbinden) in einem `compose` zusammen; abweichend ist `400`.
- **Freigabe nur hier:** `ConnectorReleaseService` (Bibliothek) und `requireConnectable` (neues
  Konto) entscheiden aus `CREATE_CONNECTOR_LIBRARY` und Sperren; ein Entzug stoppt nichts.
- **Profilangabe nur über `ProfileRequirements`.** Eine Sperre blockiert `resolve`, nicht
  `currentCredentials`. Registry je Aufruf (Bean-Zyklus). Web-Schicht: `connection.web`.

## Verweise

- ADRs (`docs/decisions/`): 0025, 0036, 0038, 0041
- Handbuch: `docs/handbuch/indexierung.md`, „Zugänge“; `bibliotheken-und-berechtigungen.md`, 12
- Strukturtests: `ModularArchitectureTest`, `ConnectionLogStructureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.connection.*' --tests 'io.opaa.architecture.*'
```

Schemaänderungen: neue Datei unter `db/changelog/connections/`, Regeln in `backend/AGENTS.md`.
