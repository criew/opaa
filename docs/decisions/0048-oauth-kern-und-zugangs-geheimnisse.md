# ADR-0048: OAuth-Kern und Zugangs-Geheimnisse

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ergänzt [ADR-0041](0041-verbindungen-als-eigenes-modul.md)
(Entscheidungen 3, 5, 9 und 12) und [ADR-0025](0025-mehrere-oidc-anbieter.md)

## Kontext

Ein Zugang (ADR-0041) meldet sich auf drei Arten an einer Quelle an: mit einer eigenen Anmeldung
(Client-Credentials oder Dienstkonto-Schlüssel), mit der OAuth-Zustimmung einer Person
(verbundenes Konto) oder mit der OAuth-Zustimmung einer Bibliothek („Quelle verbinden“). Alle drei
tragen Geheimnisse, die nur an die Endpunkte gehen dürfen, für die sie eingetragen wurden:
Client-Secret, Schlüssel, Refresh- und Zugriffstoken. OPAA hat keine HTTP-Sitzung und kein
`oauth2Login` (ADR-0025); der Rücksprung des Anbieters landet in der SPA. Alle Zugänge teilen eine
Redirect-URI, was einen Mix-up zwischen zwei Autorisierungsservern (RFC 9207) möglich macht. Widerrufe
beim Anbieter dürfen weder eine Transaktion noch den Request-Thread halten, und ein Zugang, den die
Verwaltung ändert, darf sein Secret nicht an einen Endpunkt lenken, den sie nicht lesen darf.

## Entscheidung

### 1. Eigene Anmeldung des Zugangs (`ProfileOwned`)

Client-Credentials und Dienstkonto-Schlüssel gehören dem Zugang; es gibt keine Zeile im
Token-Speicher, `ConnectionSecrets#current` fragt den Port `SecretIssuer#mint`. Die Registrierung
verlässt `connection.profile` nur über `ProfileRegistrations#registrationOf` (`ClientRegistration`:
Client-ID, entschlüsseltes Secret bzw. Schlüssel, Mandant, Scopes, deklarierte Anmeldung, imitiertes
Konto, Ressourcen-Indikator), und nur `connection.oauth` darf den Wert halten (ArchUnit
`theProfileRegistrationLeavesOnlyToTheSignIn`). `ProfileSignIn` holt Client-Credentials über
`OAuthClient` (`ClientCredentialsGrant`), den Dienstkonto-Schlüssel signiert `ServiceAccountTokens`
im Kern (ADR-0040, Entscheidung 2); das Token bleibt im Prozess bis kurz vor Ablauf (ADR-0021).
Weist der Token-Endpunkt die Registrierung ab (`invalid_client`, beim Schlüssel auch
`invalid_grant`), setzt `ProfileRegistrations` `sign_in_rejected_at` in eigener Transaktion; alle
Bibliotheken des Zugangs tragen `EXPIRED`, bis ein neues Secret, eine neue Registrierung oder
`POST …/{id}/test-sign-in` die Markierung aufhebt.

### 2. Zustimmung ohne Server-Sitzung

`POST /api/v1/connections/authorizations` legt in `connection_authorizations` nur den SHA-256-Hash
des `state` (32 Byte Zufall), den verschlüsselten PKCE-Verifier (S256), Person, Zugang mit
Zeilenversion, Zweck (`ACCOUNT`, `LIBRARY_NEW`, `LIBRARY_RECONNECT`), erwarteten Issuer und die
Rücksprungadresse `{OPAA_PUBLIC_BASE_URL}/connections/callback` ab, gültig
`ConnectionAuthorizationService.LIFETIME` (10 Minuten); ohne öffentliche Adresse `409
PUBLIC_BASE_URL_MISSING`, nie eine Adresse aus dem Host-Header. Die SPA reicht `state`, `code`
(oder `error`) und `iss` mit ihrem Bearer-Token an `POST …/authorizations/complete`. Der `state`
wird mit einem bedingten `UPDATE` in eigener Transaktion verbraucht, **bevor** der Anbieter gefragt
wird; fremd, verbraucht oder abgelaufen ist `404`, ein geänderter Zugang `409
CONNECTION_AUTHORIZATION_PROFILE_CHANGED`. Höchstens `MAX_STARTS` Zustimmungen je Person in 10
Minuten (`429`); abgelaufene Zeilen räumt der Start ab. Es gibt keinen freigegebenen
Callback-Endpunkt in der Security-Konfiguration. Deklaration: `OAuthAuth` (Autorisierungs-,
Token- und Widerrufs-Endpunkt, Vorgabe-Scopes, zusätzliche Parameter, Client-Authentisierung,
Issuer, `issuerParameterSupported`) ist Pflicht für `SignIn` mit `OAUTH`; `Revocation` ist `None`,
`Rfc7009` oder `BearerPost`. `Endpoint.FromProfile` überlässt einen Endpunkt dem Zugang (drei
Spalten, beim Speichern festgeschrieben, nie zur Laufzeit erkannt); ein geänderter Endpunkt ist eine
Registrierungsänderung.

### 3. Mix-up-Schutz und Versionsbindung

`ResponseIssuer`: Ist ein Issuer bekannt (aus der Deklaration des Konnektors oder aus der
Erkennung eines MCP-Servers, festgehalten beim Start in `expected_issuer`/`issuer_required`), muss
ein geliefertes `iss` ihm Zeichen für Zeichen entsprechen; mit Ankündigung ist `iss` Pflicht. Geprüft
wird direkt nach dem Verbrauch des `state`, auch vor einer Fehlerantwort des Anbieters; bei
Abweichung erreicht kein Code einen Token-Endpunkt (`400 CONNECTION_AUTHORIZATION_ISSUER_MISMATCH`),
das Log nennt nur den Zugang. Ein Konnektor mit `Endpoint.FromProfile` kennt keinen Issuer; dort
bleibt das Restrisiko Mix-up bestehen, solange der Zugang keinen Issuer trägt. Der Abschluss
vergleicht die Zugangsversion der Autorisierung mit der gelesenen Registrierung und legt den Grant in
einer Transaktion ab, die die Profilzeile mit `FOR SHARE` hält (ADR-0041, Entscheidung 10): Kommt
eine Profiländerung zuerst, antwortet der Abschluss `409` und widerruft den frischen Grant mit der
Registrierung, die ihn getauscht hat.

### 4. Ein Weg zum Anbieter, Erneuerung unter Zeilensperre

Nur `connection.oauth.OAuthClient` stellt Autorisierungsanfrage, Code-Tausch, Erneuerung und
Widerruf, auch für Client-Credentials, über `SourceFormPost` (Zielprüfung, keine Weiterleitung,
Größengrenze 64 KiB, Zeitlimit 15 s für den ganzen Austausch; ArchUnit
`theAuthorizationServerIsReachedOnlyThroughTheOAuthClient`). `SecretIssuer#renew` liefert `Issued`
(Zugriffstoken mit Ablauf, rotierter Refresh-Token oder `null`), implementiert von `ProviderTokens`.
`ConnectionSecrets#current` gibt ein Zugriffstoken ohne Sperre heraus, solange es länger als die
Marge gilt (`RENEWAL_MARGIN` fünf Minuten, höchstens die halbe Lebensdauer); sonst folgt eine eigene
Transaktion mit `FOR UPDATE` auf der Token-Zeile, erneute Prüfung, `renew` und das Ersetzen des
rotierten Refresh-Tokens in derselben Transaktion — zwei gleichzeitige Anfragen erneuern einmal.
`invalid_grant` beendet die Verbindung in derselben Transaktion über `GrantRejections`
(`PROVIDER_REJECTED`, Protokoll `EXPIRED`, Benachrichtigung `CONNECTION_EXPIRED`); ein unerreichbarer
Anbieter oder `invalid_client` ändert keinen Zustand. Nach einem `401` der Quelle erzwingt
`afterRejection` eine Erneuerung. Refresh-Tokens verlassen `connection.token` und `connection.oauth`
nie (`refreshTokensStayInTheTokenStore`); kein Wert zeigt ein Token in `toString`, Antworten, Logs
und Protokolle enthalten weder Token noch Code, Verifier, `state` oder Secret.

### 5. Widerruf nach dem Commit über einen begrenzten Pool

Verwurfspfade (`discard`, `discardAllUnder`, Ersatz beim Neuverbinden) lesen die Token-Zeilen mit
`PESSIMISTIC_WRITE`, nehmen in der Transaktion einen Schnappschuss (Registrierung, wie sie gerade
steht) und übergeben die Widerrufe in `afterCommit` als **einen** Eintrag an
`connection.token.GrantRevocations`; ein Rollback übergibt nichts. Deshalb verwirft
`ConnectionProfileService` bei Adress- oder Registrierungswechsel und Notabschaltung vor der Änderung
und vor dem Löschen des Secrets (`lockForChange`). Der Pool: `THREADS` (2) und `QUEUE_CAPACITY`
(1000 Einträge je Verwurf, nicht je Grant); ein Eintrag widerruft seine Grants nacheinander mit je
eigenem Zeitlimit, ein Fehler bricht die übrigen nicht ab; ist die Warteschlange voll, wird der
Eintrag mit einer WARN-Zeile verworfen, der Aufrufer blockiert nie. Beim Herunterfahren
`SHUTDOWN_GRACE` (2 s), dann werden Reste verworfen (prozesslokal, ADR-0021). `BearerPost`
erneuert ein abgelaufenes oder bald ablaufendes Zugriffstoken vorher mit dem Refresh-Token und
schickt das frische. Nicht über den Pool läuft nur der Widerruf eines frischen Grants, den der
Abschluss nicht ablegen kann (`ConnectionAuthorizationService#complete`, direkt nach dem Rollback);
ArchUnit `grantsAreRevokedOnlyAfterTheCommit`.

### 6. Quelle verbinden (`connection.consent`)

Die OAuth-Zustimmung einer Bibliothek ist `SecretOwner.SourceConsent(profileId, libraryId)`, eine
Zeile in `connection_tokens` mit `library_id`; vor der Anlage gibt eine Person sie als
`PendingConsent` (`PENDING_LIFETIME` 60 Minuten, `PendingConsentSweep` alle 15 Minuten, Widerruf
nach dem Commit), nutzbar nur von ihr, nur für Test und Ordnerauswahl im Entwurf desselben Zugangs
und beim Anlegen für dasselbe Ziel (`409 PENDING_CONNECTION_UNUSABLE`). Die Zwecke `LIBRARY_NEW`
und `LIBRARY_RECONNECT` verlangen einen Zugang mit OAuth und Besitzart Bibliothek sowie
`serviceAccountConfirmed` (`400 SERVICE_ACCOUNT_CONFIRMATION_REQUIRED`); eine private Bibliothek nimmt
nie eine Zustimmung mit Dienstkonto. Für `SourceConsent` prüft `ConnectionSecrets` keine Person;
`library_connections.connected_by` ist `ON DELETE SET NULL`. Nach dem Code-Tausch, außerhalb jeder
Transaktion, liefert `SourceConnector#connectedAccount` die Kontoadresse; weicht sie beim
Neuverbinden ab, ist das ohne `confirmAccountChange` ein `409 ACCOUNT_CHANGED` und der frische Grant
wird widerrufen, mit Bestätigung verwirft `SourceChangeGate#accountChanged` den Laufzustand. An der
Verbindung stehen `account_label`, `connected_by`, `connected_at`, Verantwortliche
(`responsible_type`/`responsible_id`, Person oder Gruppe mit `MANAGER`) und Endgrund;
Benachrichtigungen (`SOURCE_CONNECTION_EXPIRING` 14 Tage vorher, `SOURCE_CONNECTION_EXPIRED`,
`SOURCE_CONNECTION_ENDED`) gehen an die Verantwortlichen, sonst an alle Verwaltenden. Ein Endweg:
Trennen (`DELETE /api/v1/libraries/{id}/source-connection`), Zuordnen zu einem anderen Zugang,
Löschen der Bibliothek, Adress- und Registrierungswechsel, Notabschaltung und Löschen des Zugangs
verwerfen und widerrufen; Lösen auf eine eigene Adresse gibt es nicht. `LibraryResponse.sourceConnection`
sehen nur `MANAGER`; ruhende Quellverbindungen nennt `GET /api/v1/admin/source-connections/dormant`.
Dropbox ist der erste mitgelieferte Konnektor mit OAuth und Besitzart Bibliothek
(`Revocation.BearerPost`); die Fälle belegt der Testkonnektor `CONSENT_PROBE`. `LIBRARY_NEW` prüft
Freigabe und Sperren wie das Anlegen, `LIBRARY_RECONNECT` nur `MANAGER`, die Zugehörigkeit zum Zugang
und die Sperre; das Rücksprungziel folgt aus dem Zweck.

### 7. MCP-Server: Bindung an die Ressource

`ClientRegistration#resource` ist für einen MCP-Server seine Adresse (RFC 8707); `OAuthClient`
setzt `resource` in Autorisierungsanfrage, Code-Tausch und Erneuerung, `OAuthAuth.RESERVED_PARAMS`
schützt den Parameter vor Konnektoren. `issued_for` ist der Ressourcen-Indikator samt Pfad; ein
zweiter Server unter demselben Host ist ein anderes Ziel, `ProviderTokens#renew` verweigert einen
Grant, dessen `issued_for` nicht mehr der Adresse des Zugangs entspricht. `McpServerTokens#accessFor`
liefert das Token nur zusammen mit der einzigen Adresse, an die es darf. Client-Authentisierung
`client_secret_basic`, ohne Secret ein öffentlicher Client.

### 8. Client-Secret folgt keinem Endpunkt des Zugangs, Endpunkte nur `https://`

Nennt der Zugang Autorisierungs-, Token- oder Widerrufs-Endpunkt selbst, nimmt
`ConnectionProfileService` nur `https://` an (`400`, keine Ausnahme für Loopback): Token- und
Widerrufs-Endpunkt bekommen Refresh-Tokens und Secret (`OAuthClient#authenticate`), am
Autorisierungs-Endpunkt meldet sich die Person an. Ändert `update` bei gespeichertem Secret den
Token- oder Widerrufs-Endpunkt, verlangt die Änderung ein neues Secret im Request (`400
CONNECTION_PROFILE_CLIENT_SECRET_REQUIRED`; ein leeres macht den Zugang zum öffentlichen Client),
geprüft in `check` und `update` vor Rückfrage und Verwurf, auch mit `confirmDiscard`. Nicht
betroffen: der Autorisierungs-Endpunkt, ein Zugang ohne Secret, ein Wechsel der Anmeldeart, ein
fester Endpunkt des Konnektors und ein Wechsel nur des Mandanten (`Endpoint.WithTenant`). Die
Rückfrage und die Vorschau nennen bei jedem geänderten Endpunkt alte und neue Adresse; das Formular
macht das Secret dann zum Pflichtfeld oder verlangt „Ohne Client-Secret speichern“. Tests gegen einen
Zugang mit eigenem Endpunkt laufen über TLS (`FakeAuthorizationServer.overTls()`, `LoopbackTls`).

### 9. Eigenes Geheimnis einer Bibliothek am Zugang im Token-Speicher

Jedes Geheimnis an einem Zugang hat genau einen Ort: `SecretOwner.LibraryOnProfile` für eine
geteilte Bibliothek mit `PERSONAL_SECRET` oder `NONE` (Zeile in `connection_tokens`, `kind =
'PERSONAL_SECRET'`), `LibraryOwned` für die eigene Adresse (`source_credentials`). Die Zeile
gehört der Bibliothek, gebunden an `issued_for`, läuft nicht ab und wird nicht widerrufen. Anlegen
und Ändern schreiben über `LibraryConnectionService#replaceOwnSecret`; Zuordnen verschiebt das
Geheimnis in den Speicher (`EffectiveSourceSettings#adoptFrame`, `ConnectionSecrets#carryOver`),
ein Wechsel des Zugangs nimmt die Zeile mit, Lösen schreibt es zurück in die Spalte, ein nicht
entschlüsselbarer Wert wandert als Geheimtext; `discardOwnSecret` trifft Spalte und Zeile.
Die Spalte liest nur `ConnectionSecrets` (`theLibrarySecretIsReadInOnePlace`). Bestand wurde nicht
umgezogen, weil es keine Bestandsinstallationen gab.

## Konsequenzen

**Einfacher:** Ein Weg zum Anbieter, ein Speicher, eine Besitzartweiche; Konnektoren deklarieren
Endpunkte und sehen nie ein Token.

**Schwieriger:** Jede Änderung an Verwurf oder Erneuerung muss die Sperrreihenfolge und das
Widerrufsfenster beachten; Tests brauchen Fake-Autorisierungsserver, teils über TLS.

**Restrisiken:** Ein Widerruf geht verloren, wenn die Warteschlange voll ist, der Prozess vorher
endet oder der Anbieter nicht erreichbar ist; das einzige Signal ist die WARN-Zeile, der Grant
bleibt beim Anbieter gültig (Refresh-Tokens etwa von Dropbox laufen nicht ab) — Gegenmaßnahme ist
ein neues Client-Secret beim Anbieter. Die Warteschlange ist eine für alle Zugänge: Ein toter
Anbieter mit 1000 Grants hält einen Thread rund 1000 × 15 s; Trennen und Neuverbinden einer Person,
bevor ihr alter Widerruf lief, kann bei Anbietern, deren Widerruf die ganze Autorisierung beendet,
die neue Verbindung mitreißen. Mix-up bleibt bei `Endpoint.FromProfile` ohne Issuer möglich. Kein
mitgelieferter Konnektor bietet OAuth für Personen an; Dropbox bietet es nur für Bibliotheken.

## Verworfene Alternativen

- Widerruf im Request-Thread — eine Notabschaltung mit 100 Konten bei totem Anbieter dauerte 25
  Minuten und hielt die Datenbankverbindung.
- Widerruf per Refresh-Token bei `BearerPost` — der Endpunkt nimmt nur das Zugriffstoken; ein
  Refresh-Token ginge nur über RFC 7009.
- Verteilung der Warteschlange je Zugang — nicht gebaut, Head-of-Line-Blocking akzeptiert.
- Proxy-Regel für `http://`-Endpunkte (#2297) — seit der `https://`-Pflicht erreicht keine Änderung den Zweig.
- Dynamische Client-Registrierung für MCP-Server — Entscheidung 7 des Phase-3-Plans.
- Erkennung über `WWW-Authenticate` einer `401` — OPAA müsste den MCP-Endpunkt selbst ansprechen.
- Bestätigung der Dienstkonto-Art schon beim Zweck `ACCOUNT` — ohne Bibliothekszweck entsteht keine URL.

## Offen

- Ob Dropbox den `BearerPost`-Widerruf mit leerem Formular-Body annimmt.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-10-04 | #2220 | Eigene Anmeldung des Zugangs (`ProfileOwned`, `ProfileSignIn`, Ablehnung) als Nachtrag zu ADR-0041 | 1 |
| 2026-10-05 | #2168 | OAuth-Kern: Zustimmung ohne Sitzung, `OAuthClient`, Erneuerung, Widerruf nach dem Commit | 2–5 |
| 2026-10-05 | #2169 | Quelle verbinden, `connection.consent` | 6 |
| 2026-10-05 | #2173 | Ressourcen-Indikator und Erkennung für MCP-Server | 7 |
| 2026-10-09 | #2265 | Widerruf über einen begrenzten Pool, `BearerPost` erneuert vorher | 5 |
| 2026-10-09 | #2266 | Mix-up-Schutz im Rücksprung (`ResponseIssuer`) | 3 |
| 2026-10-10 | #2297 | Client-Secret folgt keinem Endpunkt des Zugangs | 8 |
| 2026-10-10 | #2279 | Endpunkte des Zugangs nur `https://`, Proxy-Regel entfällt | 8 |
| 2026-10-11 | #2273 | Eigenes Geheimnis einer Bibliothek im Token-Speicher (T1) | 9 |
| 2026-10-12 | #2496, #2537 | Kein Proxy und kein TLS-Schalter je Zugang (ADR-0050) | 1, 4 |
| 2026-10-14 | #2702 | Aus ADR-0041 herausgelöst und auf den Endstand neu gefasst | alle |
