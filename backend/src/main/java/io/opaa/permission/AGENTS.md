# Modul rights

Pakete (`io.opaa.*`): permission, asset, group, succession. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Das Berechtigungsmodell: Rechtesubjekt, Grant einer Rolle auf ein Asset, Rechteformel und
Herleitung, Rechtehistorie (`permission`); die Asset-Schale, die jeder Asset-Typ teilt (`asset`);
Gruppen samt Verzeichnis-Synchronisation (`group`); der Lebenszyklus „Nachfolge offen"
(`succession`). rights hängt nur von foundation und identity ab.

## Invarianten und Stolpersteine

- **`permission` kennt kein Fachpaket und nicht die Asset-Schale.** Was es von oben braucht,
  deklariert es als Port, den das obere Paket implementiert (`GroupMembershipSource`,
  `GroupSubjectDirectory`, `AssetOwnershipDirectory`, `SuccessionFindingSource`,
  `GroupSpaceMembershipDirectory`, `SpaceAssetDirectory`).
- **Grants sind typunabhängig:** Ein Grant nennt sein Asset über `AssetType` plus ID; `permission`
  zählt die Typen nie auf. `asset_grants` verweist mit der Organisation im Schlüssel und
  `ON DELETE CASCADE` auf `assets` — kein Grant überlebt sein Asset.
- **Die Formel ist hier vollständig:** direkter Grant, Gruppengrant und organisationsweite Freigabe
  (`assets.visibility`). Der einzige Boden außerhalb ist die Asset-Verwaltung, in der die
  Systemverwaltung als Eigentümer zählt — nie für die Suche.
- **Jede Mechanik der Asset-Schale gibt es genau einmal.** Ein neuer Asset-Typ bringt eine Tabelle,
  eine Entity, die `Asset` erweitert, und eine `AssetTypeDefinition` mit — keine eigene
  Grant-Logik, keine eigene Historie, keine eigene Fundquelle.
- **Historientabellen tragen keinen Fremdschlüssel auf das Asset:** Die Historie überlebt es. Die
  Schale schließt offene Intervalle vor dem Löschen (`AssetShellService#registerDeleted`).
- **Der Nachfolgezustand wird nie gespeichert.** `succession_cases` hält nur Beginn und Ende; alles
  andere wird bei jedem Lesen bei den Quellen erfragt.
- **Rohes SQL gegen die Grant-Tabellen** nennt das Objekt über `asset_type` und `asset_id`.
- **Web-Schicht:** `permission.web`, `asset.web` (Katalog), `group.web` (auch `/api/v1/me` und die
  Identitätsanbieter samt Verzeichnis-Konnektor), `succession.web`. Die Grants und Raumzuordnungen
  eines Assets bedient `AssetController` in `space.web` (workspace).
- **Gruppenrechte enden mit der Mitgliedschaft:** Aufgelöst wird über `GroupMembershipResolver`,
  dessen Cache nach dem Commit der schreibenden Transaktion invalidiert wird.

## Verweise

- ADRs (`docs/decisions/`): 0016, 0032, 0036, 0037
- Handbuch: `docs/handbuch/bibliotheken-und-berechtigungen.md`, Abschnitte 3–13
- Strukturtests: `PermissionPackageBoundaryTest` (Kanten zwischen Fachpaketen, die die Schichtung
  zuließe), `AssetTypeFormatParityTest`, `GrantTableRawSqlGuardTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.permission.*' --tests 'io.opaa.asset.*' \
  --tests 'io.opaa.group.*' --tests 'io.opaa.succession.*' --tests 'io.opaa.architecture.*'
```

Bei Änderungen am Verzeichnis-Konnektor (`group.sync`) zusätzlich `./gradlew keycloakIntegrationTest`
(braucht Docker).

Bei Schemaänderungen: neue Datei unter `db/changelog/rights/` mit eigenem Delta-Test
(`MasterChangelog.filesExcept(...)`), Regeln in `backend/AGENTS.md`, „Liquibase: Changelog je
Modul“; dazu `ChangelogLayoutTest`, `ChangelogModuleBoundaryTest`, `ChangelogOrderTest`.
