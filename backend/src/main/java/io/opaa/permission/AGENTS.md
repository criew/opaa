# Modul rights

Pakete (`io.opaa.*`): permission, asset, group, directory, succession. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Das Berechtigungsmodell: Rechtesubjekt, Grant einer Rolle auf ein Asset, Rechteformel, Herleitung, Rechtehistorie
(`permission`); die Asset-Schale, die jeder Asset-Typ teilt (`asset`); Gruppen (`group`); Verwaltung der
Identitätsanbieter, Verzeichnis-Synchronisation und -Konnektoren (`directory`); „Nachfolge offen" (`succession`).

## Invarianten und Stolpersteine

- **`permission` kennt kein Fachpaket und nicht die Asset-Schale.** Was es von oben braucht, deklariert es als
  Port, den das obere Paket implementiert (`GroupMembershipSource`, `GroupSubjectDirectory`, `AssetOwnershipDirectory`,
  `SuccessionFindingSource`, `GroupSpaceMembershipDirectory`, `SpaceAssetDirectory`, `CapabilityScopeCatalog`).
- **Geltungsbereich einer Fähigkeit** hat nur `CREATE_CONNECTOR_LIBRARY` (`CapabilityService#isScoped`): rights
  speichert `scope` und deutet ihn nie; jede Prüfung nennt ihn, entschieden wird in `ConnectorReleaseService`.
- **Grants sind typunabhängig:** Asset über `AssetType` plus ID, Typen nie aufgezählt; `asset_grants`
  verweist mit der Organisation im Schlüssel per `ON DELETE CASCADE` auf `assets`.
- **Die Formel ist hier vollständig:** direkter Grant, Gruppengrant und Grant an „Alle Konten“. Einziger Boden
  außerhalb: In der Asset-Verwaltung gilt die Systemverwaltung als Eigentümer, nie für Suche und Nur-Besitzerin-Assets.
- **Nur-Besitzerin-Merkmal** (`assets.owner_only`): beim Anlegen gesetzt, samt Eigentümer
  unveränderlich, nur der Grant der Besitzerin (Trigger); Regeln in `asset.OwnerOnlyRule`, ins
  Protokoll nur über `Asset#auditName`/`auditPayload`, Zahlen nach Personen über `PersonThreshold`.
- **Jede Mechanik der Asset-Schale gibt es genau einmal.** Ein neuer Typ bringt Tabelle, Entity
  (erweitert `Asset`) und `AssetTypeDefinition` mit, keine eigene Grant-Logik, Historie, Fundquelle.
- **Inaktiver Typ** (Flag aus): Lesewege fragen `AssetTypes#active`/`isActive`/`requireActive`;
  Eigentum, Historie, Protokoll und Nachfolgewächter bleiben bei `registered`/`require`.
- **Historientabellen tragen keinen Fremdschlüssel auf das Asset:** Die Historie überlebt es. Die
  Schale schließt offene Intervalle vor dem Löschen (`AssetShellService#registerDeleted`).
- **Der Nachfolgezustand wird nie gespeichert,** nur Beginn und Ende (`succession_cases`).
- **Rohes SQL gegen die Grant-Tabellen** nennt das Objekt über `asset_type` und `asset_id`.
- **`directory` liegt über `group`,** denn die Synchronisation schreibt Gruppen; `group` fragt das Verzeichnis
  über den Port `DirectorySyncRuns`. Die Anbieter-Registratur liegt in `io.opaa.auth`.
- **Web-Schicht:** `asset.web` (Katalog, Grants, Herleitung, Eigentum), `group.web` (auch `/api/v1/me`),
  `directory.web` (Anbieter, Verzeichnis-Konnektor, Synchronisation); Raumzuordnungen eines Assets in `space.web`.
- **Gruppenrechte enden mit Mitgliedschaft oder Deaktivierung** (`GroupMembershipResolver`, Cache nach Commit).

## Maßgebliche ADRs

- [ADR-0016](../../../../../../../docs/decisions/0016-loeschschicksal-rechtehistorie.md): Löschschicksal der Rechtehistorie
- [ADR-0025](../../../../../../../docs/decisions/0025-mehrere-oidc-anbieter.md) (Entscheidungen 3, 4): Anbieterverwaltung, Claim-Zuordnung
- [ADR-0032](../../../../../../../docs/decisions/0032-zeitquelle-rechtehistorie.md): Zeitquelle der Rechtehistorie
- [ADR-0036](../../../../../../../docs/decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md): Berechtigungsmodell, Gruppen, Fähigkeiten
- [ADR-0037](../../../../../../../docs/decisions/0037-reichweite-als-freigabe-an-alle.md): Freigabe an „Alle Konten“
- [ADR-0039](../../../../../../../docs/decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md) (Entscheidungen 1–3): Katalog und Sichtbarkeit
- [ADR-0044](../../../../../../../docs/decisions/0044-installationsweite-feature-flags.md) (Entscheidung 3): inaktive Asset-Typen

## Verweise

- Handbuch: `docs/handbuch/konten-und-rechte/berechtigungen.md`, Abschnitte 3–13

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.permission.*' --tests 'io.opaa.asset.*' --tests 'io.opaa.group.*' \
  --tests 'io.opaa.directory.*' --tests 'io.opaa.succession.*' --tests 'io.opaa.architecture.*'
```

Bei Änderungen an `directory.sync` zusätzlich `./gradlew keycloakIntegrationTest` (braucht Docker).
