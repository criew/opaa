# Modul foundation

Pakete (`io.opaa.*`): common, compliance, observability, instance, organization, outbound, security,
ratelimit, sourceaccess, s3, msgraph. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Die unterste Schicht: gemeinsame Ausnahmen und Hilfen, die Organisation als Mandantengrenze, Verschlüsselung,
Ratenbegrenzung, Metriken, Quellzugriff (HTTP), Ausgangsproxy, S3, Graph-Client, Compliance-Manifest, Instanzregister.

## Invarianten und Stolpersteine

- **Die Organisation ist die harte Mandantengrenze.** Ein Objekt einer fremden Organisation gilt als „nicht gefunden",
  nie als „verboten", auch für die Systemverwaltung (`OrganizationScopedLoader`).
- **Jeder Abruf einer quellkonfigurierten URL geht über `io.opaa.sourceaccess`:** Zieladressprüfung vor der ersten
  Anfrage und bei jedem Redirect, Byte-Deckel, Höflichkeitspause, `SourceRequestPolicy`; ohne `library` und `api`.
- **`io.opaa.s3` umgeht `sourceaccess`**, weil das SDK einen eigenen HTTP-Client mitbringt. Zieladressprüfung, Timeouts,
  Proxy und Wiederholungen sind dort bewusst nachgebaut, je mit Test; Fehler: deutsche `S3AccessException`s ohne
  Zugangsdaten. Seine Nutzer (S3-Konnektor, Originalablage) kennt es nicht.
- **`io.opaa.msgraph` folgt keiner Adresse von Graph:** Folgeseiten nur als Token von Graphs eigenem Ursprung; Downloads
  auf den vorab signierten Host nur über https ohne `Authorization`, die Adresse (Kurzzeit-Token) in keinem Log.
- **Zwei Schlüssel für Geheimnisse:** `CredentialsEncryptor` (Quell-Zugangsdaten, `OPAA_CREDENTIALS_ENCRYPTION_KEY`),
  `SettingsEncryptor` (verwaltete Einstellungen, `OPAA_SETTINGS_ENCRYPTION_KEY`); geprüft erst beim ersten Gebrauch.
- **`LocalAuthKeyService` leitet alle Schlüssel des lokalen Ausstellers per HKDF aus `OPAA_AUTH_JWT_SECRET` ab**, je
  Zweck ein eigenes Label. Eine Rotation beendet alle lokalen Sitzungen, offenen Links und Zugangstokens zugleich.
- **Ratenbegrenzung:** Fenster je Instanz (`RateLimitService`), Deckel in der DB, Kontogrenze hinter der Kette;
  Wildcard-Proxy-CIDR bricht den Start ab. Marken zurück: Unangemeldete (außer Anmelden), Tokens je Adresse.
- **`TargetAddressValidator` bindet geprüfte Namen an ihre Adressen** (JVM-Resolver, ADR-0063); kein Client löst
  selbst auf. **Feature-Flags sind ohne Angabe an;** `@ConditionalOnFeature` an Blatt-Beans und bedingten Teilgraphen.
- **Zwei Uhren:** Was eine andere Instanz vergleicht, aus `DatabaseClock` oder `clock_timestamp()`, sonst `Clock`. Ein
  `opaa.*`-Schlüssel ohne `@DataImpact` trägt `@InstallationWide` oder `@PerInstance` (`FingerprintKeysTest`).
- **`OutboundProxySelector` ist der Proxy-Standard der JVM:** nur `http`/`https` über den Proxy, `socket://` (SMB, SMTP,
  JDBC) und Loopback nie; das AWS SDK bekommt ihn über `proxyFor`.

## Maßgebliche ADRs

- [ADR-0014](../../../../../../../docs/decisions/0014-produktausrichtung-oeffentliche-verwaltung.md) (Entscheidung 15): Organisationsgrenze in drei Schichten
- [ADR-0018](../../../../../../../docs/decisions/0018-quellkonfiguration-in-der-bibliothek.md) (Entscheidungen 4, 6): verschlüsselte Zugangsdaten, Zieladressprüfung
- [ADR-0027](../../../../../../../docs/decisions/0027-s3-konnektor.md) (Entscheidungen 8, 9): S3-Clientschicht über das SDK
- [ADR-0030](../../../../../../../docs/decisions/0030-originalablage-der-uploads.md) (Entscheidung 8): geteilter Bau des S3-Clients
- [ADR-0033](../../../../../../../docs/decisions/0033-lokale-benutzerverwaltung.md) (Entscheidungen 6, 9): Schlüsselableitung, Sicherheitsmindestmaß
- [ADR-0044](../../../../../../../docs/decisions/0044-installationsweite-feature-flags.md): installationsweite Feature-Flags
- [ADR-0047](../../../../../../../docs/decisions/0047-sharepoint-konnektor.md) (Entscheidung 1): Graph-Client in `io.opaa.msgraph`
- [ADR-0050](../../../../../../../docs/decisions/0050-ausgangsproxy-und-zertifikatspruefung.md): Ausgangsproxy, Zertifikatsprüfung
- [ADR-0052](../../../../../../../docs/decisions/0052-compliance-manifest.md): Compliance-Manifest; Fundstellen nur als Namen, kein Import nach oben
- [ADR-0053](../../../../../../../docs/decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md) (Entscheidungen 3, 4, 5, 9): Instanzregister, Abdruck, Invalidierungsbus (`InvalidationSubscriber`), Datenbankuhr

## Verweise

- Handbuch: `betrieb/netz-und-sicherheit.md`, „Härtung für erreichbare Deployments"; `betrieb/konfiguration.md`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.common.*' --tests 'io.opaa.security.*' --tests 'io.opaa.outbound.*' \
  --tests 'io.opaa.sourceaccess.*' --tests 'io.opaa.ratelimit.*' --tests 'io.opaa.s3.*' --tests 'io.opaa.compliance.*' \
  --tests 'io.opaa.msgraph.*' --tests 'io.opaa.observability.*' --tests 'io.opaa.instance.*' \
  --tests 'io.opaa.architecture.*'
```
Die S3-Tests brauchen Docker und werden ohne Docker übersprungen.
