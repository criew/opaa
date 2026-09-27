# Modul identity

Pakete (`io.opaa.*`): auth, audit, branding, mail. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Wer jemand ist und was über ihn protokolliert wird: Konten und Authentifizierung (`dev`, `oidc` mit
lokalen Konten und mehreren Identitätsanbietern), das Revisionsprotokoll, das Branding und der
Mailversand. identity hängt nur von foundation ab.

## Invarianten und Stolpersteine

- **Ohne Auth-Profil startet das Backend nicht** (`AuthProfileGuard`): Ohne die Filterkette eines
  Modus stünde `/api/**` offen.
- **`dev` ist ein echter Modus:** `DevAuthFilter` legt für jede Anfrage ein synthetisches `Jwt` in
  den Kontext, der Nutzer kommt aus einem Header, ein unbekanntes Subject ergibt `401`. Der Code
  dahinter kennt den aktiven Modus nicht.
- **Die Kontoidentität ist `users(subject, issuer)` in `io.opaa.auth`**, lokale Konten tragen
  `LocalIssuer#URN`. Das Profil `oidc` startet nicht ohne starkes `OPAA_AUTH_JWT_SECRET`
  (`LocalAuthSecretGuard`).
- **Das Revisionsprotokoll ist nur anfügbar.** Geschrieben wird über `AuditLogService#record` (oder
  `AuditEventRecorder`), gelesen nur über `AuditQueryService`. `AuditLogRepository` ist
  package-private. Das Anwendungskonto hat auf `audit_log` nur `INSERT` und `SELECT`; entfernt wird
  nur eine vollständig abgelaufene Monatspartition.
- **Ein Sendeweg:** `MailService` ist der einzige Einstieg für alles, was Mail verschickt, und
  meldet das Ergebnis, statt zu werfen. Neue Anlässe bringen Vorlagen und Variablen mit, keinen
  zweiten Sendeweg.
- **Branding** ändert nur `BrandingSettingsService`; SVG wird abgelehnt, nicht bereinigt
  (`BrandingImageValidator`).

- **Lokale Anmeldung testet nur die Familie `oidc`** der Testkontexte (`@OpaaLocalAuth*`, siehe
  `backend/AGENTS.md`, „Spring-Testkontexte"); unter `local,dev` ist keine lokale Sitzung fahrbar.

## Verweise

- ADRs (`docs/decisions/`): 0005, 0015, 0025, 0033
- Handbuch: `docs/handbuch/benutzerverwaltung.md`; `docs/handbuch/deployment.md`,
  „Authentifizierung", „Branding", „E-Mail-Versand (SMTP)"
- Strukturtests: `AuditFunnelStructureTest`, `AuthProfileGuardTest`, `LocalAuthSecretGuardTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.auth.*' --tests 'io.opaa.audit.*' --tests 'io.opaa.branding.*' \
  --tests 'io.opaa.mail.*' --tests 'io.opaa.architecture.*'
```

Bei Schemaänderungen: Changeset mit eigenem Delta-Test nach `backend/AGENTS.md`, Abschnitt
„Liquibase“; die Baseline-Tests prüfen nur die Baseline, nicht die Änderung.
