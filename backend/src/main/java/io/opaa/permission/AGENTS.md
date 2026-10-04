# Modul rights

Pakete (`io.opaa.*`): permission, asset, group, directory, succession. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Das Berechtigungsmodell: Rechtesubjekt, Grant einer Rolle auf ein Asset, Rechteformel und
Herleitung, Rechtehistorie (`permission`); die Asset-Schale, die jeder Asset-Typ teilt (`asset`);
Gruppen (`group`); die Verwaltung der Identitätsanbieter, die Verzeichnis-Synchronisation und die
Verzeichnis-Konnektoren (`directory`); der Lebenszyklus „Nachfolge offen" (`succession`). rights
hängt nur von foundation und identity ab.

## Invarianten und Stolpersteine

- **`permission` kennt kein Fachpaket und nicht die Asset-Schale.** Was es von oben braucht,
  deklariert es als Port, den das obere Paket implementiert (`GroupMembershipSource`,
  `GroupSubjectDirectory`, `AssetOwnershipDirectory`, `SuccessionFindingSource`,
  `GroupSpaceMembershipDirectory`, `SpaceAssetDirectory`, `CapabilityScopeCatalog`).
- **Geltungsbereich einer Fähigkeit** hat nur `CREATE_CONNECTOR_LIBRARY` (`CapabilityService#isScoped`):
  rights speichert `scope` und deutet ihn nie; jede Prüfung dieser Fähigkeit nennt ihn. Entschieden
  wird die Freigabe in connections (`ConnectorReleaseService`, ADR-0041).
- **Grants sind typunabhängig:** Ein Grant nennt sein Asset über `AssetType` plus ID; `permission`
  zählt die Typen nie auf. `asset_grants` verweist mit der Organisation im Schlüssel und
  `ON DELETE CASCADE` auf `assets` — kein Grant überlebt sein Asset.
- **Die Formel ist hier vollständig:** direkter Grant, Gruppengrant und Grant an „Alle Konten“.
  Einziger Boden außerhalb: In der Asset-Verwaltung zählt die Systemverwaltung als Eigentümer — nie
  für die Suche und nie bei einem Nur-Besitzerin-Asset.
- **Nur-Besitzerin-Merkmal** (`assets.owner_only`, private Bibliothek): beim Anlegen gesetzt, danach
  samt Eigentümer unveränderlich; zulässig ist nur der Grant der Besitzerin (Trigger). Meldung
  `403 OWNER_ONLY_ASSET` aus `asset.OwnerOnlyRule`; Sammelübertragung und Nachfolge lassen es aus,
  ein fremder Rechtekontext liest `readableLibraryIdsInForeignContext` (ArchUnit).
- **Jede Mechanik der Asset-Schale gibt es genau einmal.** Ein neuer Asset-Typ bringt eine Tabelle,
  eine Entity, die `Asset` erweitert, und eine `AssetTypeDefinition` mit — keine eigene
  Grant-Logik, keine eigene Historie, keine eigene Fundquelle.
- **Historientabellen tragen keinen Fremdschlüssel auf das Asset:** Die Historie überlebt es. Die
  Schale schließt offene Intervalle vor dem Löschen (`AssetShellService#registerDeleted`).
- **Der Nachfolgezustand wird nie gespeichert;** `succession_cases` hält nur Beginn und Ende.
- **Rohes SQL gegen die Grant-Tabellen** nennt das Objekt über `asset_type` und `asset_id`.
- **`directory` liegt über `group`,** denn die Synchronisation schreibt Gruppen; `group` fragt das
  Verzeichnis über den Port `DirectorySyncRuns`. Die Anbieter-Registratur liegt in `io.opaa.auth`.
- **Web-Schicht:** `permission.web`, `asset.web` (Katalog, Grants, Herleitung, Eigentum), `group.web`
  (auch `/api/v1/me`), `directory.web` (Anbieter, Verzeichnis-Konnektor, Synchronisation),
  `succession.web`. Die Raumzuordnungen eines Assets bedient `space.web` (workspace).
- **Gruppenrechte enden mit der Mitgliedschaft** (`GroupMembershipResolver`, Cache nach Commit).

## Verweise

- ADRs (`docs/decisions/`): 0016, 0032, 0036, 0037
- Handbuch: `docs/handbuch/bibliotheken-und-berechtigungen.md`, Abschnitte 3–13
- Strukturtests: `PermissionPackageBoundaryTest` (Kanten zwischen Fachpaketen, die die Schichtung
  zuließe), `AssetTypeFormatParityTest`, `GrantTableRawSqlGuardTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.permission.*' --tests 'io.opaa.asset.*' --tests 'io.opaa.group.*' \
  --tests 'io.opaa.directory.*' --tests 'io.opaa.succession.*' --tests 'io.opaa.architecture.*'
```

Bei `directory.sync` zusätzlich `./gradlew keycloakIntegrationTest`. Schema: `db/changelog/rights/`.
