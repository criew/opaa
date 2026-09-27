# Modul foundation

Pakete (`io.opaa.*`): common, observability, organization, security, ratelimit, sourceaccess, s3.
Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Die unterste Schicht: gemeinsame Ausnahmen und Hilfen, die Organisation als Mandantengrenze,
Verschlüsselung und Schlüsselableitung, Ratenbegrenzung, Metriken, der Zugriff auf konfigurierte
Quellen (HTTP) und die S3-Clientschicht. foundation hängt von keinem anderen Modul ab.

## Invarianten und Stolpersteine

- **Die Organisation ist die harte Mandantengrenze.** Ein Objekt einer fremden Organisation gilt als
  „nicht gefunden", nie als „verboten", auch für die Systemverwaltung (`OrganizationScopedLoader`).
- **Jeder Abruf einer quellkonfigurierten URL geht über `io.opaa.sourceaccess`:** Zieladressprüfung
  vor der ersten Anfrage und bei jedem Redirect, Byte-Deckel, Höflichkeitspause,
  `SourceRequestPolicy`. Das Paket kennt weder `library` noch `api`.
- **`io.opaa.s3` umgeht `sourceaccess`**, weil das SDK einen eigenen HTTP-Client mitbringt.
  Zieladressprüfung, Timeouts, Proxy, gelockertes TLS und Wiederholungen sind dort bewusst
  nachgebaut, mit einem Test je Stück. Fehler werden zu deutschen `S3AccessException`s ohne
  Zugangsdaten. Seine Nutzer (S3-Konnektor, Originalablage) kennt das Paket nicht.
- **Zwei getrennte Schlüssel für Geheimnisse:** `CredentialsEncryptor` für Quell-Zugangsdaten
  (`OPAA_CREDENTIALS_ENCRYPTION_KEY`), `SettingsEncryptor` für Geheimnisse verwalteter Einstellungen
  (`OPAA_SETTINGS_ENCRYPTION_KEY`). Beide prüfen den Schlüssel erst beim ersten Gebrauch.
- **`LocalAuthKeyService` leitet alle Schlüssel des lokalen Ausstellers per HKDF aus
  `OPAA_AUTH_JWT_SECRET` ab**, je Zweck ein eigenes Label. Eine Rotation beendet alle lokalen
  Sitzungen, offenen Links und Zugangstokens zugleich.
- **Ratenbegrenzung:** `RateLimitService` ist ein Gleitfenster je Geltungsbereich mit gedeckeltem
  Schlüsselraum; jenseits des Deckels öffnet er. Ein Wildcard-Bereich in
  `OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` wird in jedem Profil abgewiesen.
- **`TargetAddressValidator` kann DNS-Rebinding nicht ausschließen** — eine akzeptierte Grenze.

## Verweise

- ADRs (`docs/decisions/`): 0018 (Entscheidung 4), 0021, 0027 (Entscheidung 8, 9), 0030
  (Entscheidung 8), 0033 (Entscheidung 6, 9)
- Handbuch: `docs/handbuch/deployment.md`, „Härtung für erreichbare Deployments" und „Konfiguration"
- Strukturtests: `SourceAccessDependencyStructureTest`, `TrustedProxyStartupGuardTest`,
  `S3RequestGuardTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.common.*' --tests 'io.opaa.security.*' \
  --tests 'io.opaa.sourceaccess.*' --tests 'io.opaa.ratelimit.*' --tests 'io.opaa.s3.*' \
  --tests 'io.opaa.observability.*' --tests 'io.opaa.architecture.*'
```

Die S3-Tests brauchen Docker und werden ohne Docker übersprungen. Bei Schemaänderungen: neue Datei
unter `db/changelog/foundation/` mit eigenem Delta-Test (`MasterChangelog.filesExcept(...)`),
Regeln in `backend/AGENTS.md`, „Liquibase: Changelog je Modul“; dazu `ChangelogLayoutTest`,
`ChangelogModuleBoundaryTest`, `ChangelogOrderTest`.
