# Modul identity

Pakete (`io.opaa.*`): auth, account, audit, branding, mail, notification. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Wer jemand ist und was über ihn protokolliert wird. `auth` ist der Identitätskern: Kontoidentität,
Bereitstellung aus dem Token, Registratur der Identitätsanbieter, Modus `dev`, Anmeldefähigkeit.
`account` sind die lokalen Konten (Passwörter, lokaler Issuer, Self-Service, Übergabe, Verwaltung);
die Filterkette und der Resolver des Modus `oidc` liegen ebenfalls dort. Dazu Revisionsprotokoll, Branding, Mailversand und Benachrichtigungen. identity hängt nur
von foundation ab.

## Invarianten und Stolpersteine

- **Ohne Auth-Profil startet das Backend nicht** (`AuthProfileGuard`): Ohne die Filterkette eines
  Modus stünde `/api/**` offen.
- **`dev` ist ein echter Modus:** `DevAuthFilter` legt für jede Anfrage ein synthetisches `Jwt` in
  den Kontext, der Nutzer kommt aus einem Header, ein unbekanntes Subject ergibt `401`. Der Code
  dahinter kennt den aktiven Modus nicht.
- **Die Kontoidentität ist `users(subject, issuer)` in `io.opaa.auth`**, lokale Konten tragen
  `LocalIssuer#URN`. Das Profil `oidc` startet nicht ohne starkes `OPAA_AUTH_JWT_SECRET`
  (`LocalAuthSecretGuard`).
- **`auth` kennt `account` nicht, außer `auth.web`.** Was Tokenverarbeitung und Rollenpflege vom lokalen Konto
  brauchen, liegt im Kern: `LocalCredentials`, die Regel `LocalAccountAccess` und
  `LocalAdminAvailabilityGuard` („nie ohne anmeldefähigen Systemverwalter“). Den Schalter der
  lokalen Konten erreicht `account` nur über den Port `LocalAccountsSwitch`; die Verwaltung der
  Anbieter liegt in `io.opaa.directory` (rights).
- **Das Revisionsprotokoll ist nur anfügbar.** Geschrieben wird über `AuditLogService#record` (oder
  `AuditEventRecorder`), gelesen nur über `AuditQueryService`. `AuditLogRepository` ist
  package-private. Das Anwendungskonto hat auf `audit_log` nur `INSERT` und `SELECT`; entfernt wird
  nur eine vollständig abgelaufene Monatspartition.
- **Ein Sendeweg:** `MailService` ist der einzige Einstieg für alles, was Mail verschickt, und
  meldet das Ergebnis, statt zu werfen. Neue Anlässe bringen Vorlagen und Variablen mit, keinen
  zweiten Sendeweg.
- **Branding** ändert nur `BrandingSettingsService`; SVG wird abgelehnt, nicht bereinigt
  (`BrandingImageValidator`).
- **Web-Schicht:** `auth.web`, `account.web`, `audit.web`, `branding.web`, `mail.web`,
  `notification.web`; `audit.web.AuditedAdminCall` protokolliert `IndexingAdminController` und
  `UploadStoreAdminController`. `AuditController` liegt in `revision.web` (workspace), `/api/v1/me`
  in `group.web` und die Anbieterverwaltung in `directory.web` (beide rights).
- **Lokale Anmeldung testet nur die Familie `oidc`** der Testkontexte (`@OpaaLocalAuth*`); unter
  `local,dev` ist keine lokale Sitzung fahrbar.

## Verweise

- ADRs (`docs/decisions/`): 0005, 0015, 0019, 0025, 0033
- Handbuch: `docs/handbuch/benutzerverwaltung.md`; `docs/handbuch/deployment.md`,
  „Authentifizierung", „Branding", „E-Mail-Versand (SMTP)"
- Strukturtests: `AuditFunnelStructureTest`, `AuthProfileGuardTest`, `LocalAuthSecretGuardTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.auth.*' --tests 'io.opaa.account.*' --tests 'io.opaa.audit.*' \
  --tests 'io.opaa.branding.*' --tests 'io.opaa.mail.*' --tests 'io.opaa.architecture.*'
```

Bei Schemaänderungen: neue Datei unter `db/changelog/identity/` mit eigenem Delta-Test
(`MasterChangelog.filesExcept(...)`), Regeln in `backend/AGENTS.md`, „Liquibase: Changelog je
Modul“; dazu `ChangelogLayoutTest`, `ChangelogModuleBoundaryTest`, `ChangelogOrderTest`.
