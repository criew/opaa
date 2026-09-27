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

## Testkontexte der Familie `oidc`

Unter `local,dev` authentifiziert `DevAuthFilter` jede Anfrage, bevor ein Bearer-Token gelesen
wird; eine lokale Sitzung ist dort nicht fahrbar. Deshalb gibt es fünf Meta-Annotationen mit
Profil `oidc`, jede Variante über die Basis meta-annotiert:

- `@OpaaLocalAuthMockMvcTest` — Basis: MockMvc, starkes Test-Secret (sonst verweigert
  `LocalAuthSecretGuard` den Start), angehobene `opaa.rate-limit.local-auth.*`-Grenzen.
- `@OpaaLocalAuthLinkTest` — plus `opaa.public-base-url`; ohne sie sind die Link-Flüsse aus.
- `@OpaaLocalAuthSeedTest` — plus zustellbare Erstadministrator-Adresse und Netzbeschränkung.
- `@OpaaLocalAuthRateLimitTest` — mit den echten Grenzen, die hier Prüfgegenstand sind.
- `@OpaaLocalAuthProviderTest` — plus `OidcProviderTokenTestConfiguration`, ein echter
  `NimbusJwtDecoder` über einen lokalen Schlüssel statt eines JWK-Sets aus dem Netz: ein prüfbares
  Anbieter-Token für die Übergabe eines lokalen Kontos. Nicht in die Basis ziehen.

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

Bei Schemaänderungen zusätzlich `IdentityBaselineTest` und `AuditPrivilegeModelTest`.
