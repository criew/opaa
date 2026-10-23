# Modul identity

Pakete (`io.opaa.*`): auth, account, audit, branding, mail, notification, selfdisclosure. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Wer jemand ist und was über ihn protokolliert wird. `auth` ist der Identitätskern: Kontoidentität,
Bereitstellung aus dem Token, Registratur der Identitätsanbieter, Modus `dev`, Anmeldefähigkeit.
`account` sind die lokalen Konten (Passwörter, lokaler Issuer, Self-Service, Übergabe, Verwaltung);
die Filterkette und der Resolver des Modus `oidc` liegen ebenfalls dort. Dazu Revisionsprotokoll,
Branding, Mailversand, Benachrichtigungen und Selbstauskunft (Kategorien über `SelfDisclosureExporter`).

## Invarianten und Stolpersteine

- **Ohne Auth-Profil startet das Backend nicht** (`AuthProfileGuard`): Ohne die Filterkette eines
  Modus stünde `/api/**` offen.
- **`dev` ist ein echter Modus:** `DevAuthFilter` legt für jede Anfrage ein synthetisches `Jwt` in
  den Kontext, der Nutzer kommt aus einem Header, ein unbekanntes Subject ergibt `401`. Der Code
  dahinter kennt den aktiven Modus nicht.
- **Die Kontoidentität ist `users(subject, issuer)` in `io.opaa.auth`**, lokale Konten tragen
  `LocalIssuer#URN`. Das Profil `oidc` startet nicht ohne starkes `OPAA_AUTH_JWT_SECRET`
  (`LocalAuthSecretGuard`).
- **`auth` kennt `account` nicht, außer `auth.web`.** Im Kern liegen `LocalCredentials`,
  `LocalAccountAccess`, `AccountUsability` (einzige Regel „Konto nutzbar“) und
  `LocalAdminAvailabilityGuard`. Den Schalter der lokalen Konten erreicht `account` nur über den
  Port `LocalAccountsSwitch`; die Verwaltung der Anbieter liegt in `io.opaa.directory` (rights).
- **Das Revisionsprotokoll ist nur anfügbar.** Geschrieben wird über `AuditLogService#record` (oder
  `AuditEventRecorder`), gelesen nur über `AuditQueryService`, die eigenen Einträge der Person allein
  über `AuditLogSelfDisclosureExporter`. `AuditLogRepository` ist package-private. Das
  Anwendungskonto hat auf `audit_log` nur `INSERT` und `SELECT`; entfernt wird nur eine vollständig
  abgelaufene Monatspartition.
- **Ein Sendeweg:** `MailService` meldet das Ergebnis, statt zu werfen; neue Anlässe bringen Vorlagen, keinen
  zweiten Weg. Ohne Wartenden geht die Mail per `enqueue` in der fachlichen Transaktion in die Outbox (ADR-0053).
- **Branding** ändert nur `BrandingSettingsService`; SVG wird abgelehnt, nicht bereinigt
  (`BrandingImageValidator`).
- **Web-Schicht:** `auth.web`, `account.web`, `audit.web`, `branding.web`, `mail.web`,
  `notification.web`, `selfdisclosure.web`; `audit.web.AuditedAdminCall` protokolliert
  `IndexingAdminController` und `UploadStoreAdminController`. Die Stichtagsauskunft
  `/api/v1/audit/access-as-of` in `revision.web`, `/api/v1/me` in `group.web`, Anbieter in `directory.web`.
- **Lokale Anmeldung testet nur die Familie `oidc`** der Testkontexte (`@OpaaLocalAuth*`), nie `local,dev`.

## Maßgebliche ADRs

- [ADR-0005](../../../../../../../docs/decisions/0005-authentication-strategy.md): Auth-Modi, JWT, Bereitstellung
- [ADR-0015](../../../../../../../docs/decisions/0015-eigentuemertrennung-protokollablage.md): Eigentümertrennung des Revisionsprotokolls
- [ADR-0019](../../../../../../../docs/decisions/0019-minimale-benachrichtigungsinfrastruktur.md): minimale Benachrichtigungen
- [ADR-0025](../../../../../../../docs/decisions/0025-mehrere-oidc-anbieter.md): mehrere OIDC-Anbieter, Identität
- [ADR-0033](../../../../../../../docs/decisions/0033-lokale-benutzerverwaltung.md): lokale Konten, Token, Mail
- [ADR-0041](../../../../../../../docs/decisions/0041-verbindungen-als-eigenes-modul.md) (Entscheidung 4): eine Regel „Konto nutzbar“

## Verweise

- Handbuch (`docs/handbuch/`): `benutzerverwaltung.md`; `authentifizierung.md`, `branding.md`, `e-mail.md`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.auth.*' --tests 'io.opaa.account.*' --tests 'io.opaa.audit.*' \
  --tests 'io.opaa.branding.*' --tests 'io.opaa.mail.*' --tests 'io.opaa.selfdisclosure.*' \
  --tests 'io.opaa.architecture.*'
```
