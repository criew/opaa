# Authentifizierung

> **Fremdzugänge sind davon unberührt und stehen auf „aus".** Der Zugang für fremde KI-Werkzeuge
> (persönliche Zugangstokens, MCP-Server) ist ein eigener Schalter unter *Administration →
> Anbindungen → Externe KI-Werkzeuge*, unabhängig vom hier eingestellten Anmeldeverfahren. Eine frische Installation
> liefert ihn ausgeschaltet aus, zusammen mit einer Netzbeschränkung auf das Hausnetz. Schalter,
> Freigaben, Tokens und die Einrichtung im Client stehen im Kapitel
> [Fremdzugänge](fremdzugaenge.md).

OPAA kennt genau zwei Auth-Modi. Der Modus
wird über das aktive Spring-Profil gewählt; ist weder `oidc` noch `dev` gesetzt, **bricht das
Backend den Start mit einer Fehlermeldung ab**.

| Modus | Profil | Zweck |
|-------|--------|-------|
| `oidc` | `oidc` | **Betriebsmodus: OIDC-Anbieter und lokale Konten** — der einzige für den Betrieb zulässige Modus. Der Name ist historisch; seit der lokalen Benutzerverwaltung enthält dieser Modus zusätzlich einen anwendungseigenen Aussteller für Konten, die OPAA selbst führt (siehe unten). Umbenannt wurde er nicht, weil das jede bestehende `SPRING_PROFILES_ACTIVE` im Feld gebrochen hätte |
| `dev` | `dev` | Lokale Entwicklung und automatisierte Tests — **keinerlei Prüfung von Anmeldedaten**, kein Aussteller, keine lokalen Konten |

## Entwicklungsmodus (`dev`)

```env
SPRING_PROFILES_ACTIVE=docker,dev
```

Es gibt keinen Anmeldevorgang und kein Token: Jede Anfrage wird als einer der konfigurierten
Entwicklungsnutzer authentifiziert. Vorkonfiguriert sind `dev-admin` (E-Mail `admin@opaa.local`,
wird durch `OPAA_INITIAL_ADMIN_EMAIL` zum `SYSTEM_ADMIN`) und `dev-user` (regulärer Nutzer).

Den Nutzer wechselt man im Browser über den Query-Parameter `?devUser=dev-user`, der für die Dauer
der Browser-Session gemerkt wird; direkte API-Aufrufe setzen stattdessen den Header
`X-OPAA-Dev-User`. Ein unbekannter Nutzername führt zu `401`.

> **Warnung:** Dieser Modus deaktiviert die Authentifizierung vollständig. Er gehört ausschließlich
> auf Arbeitsplätze und in Testumgebungen, nie auf eine erreichbare Instanz. Das Backend
> protokolliert beim Start eine entsprechende Warnung.

**Einen Mock-Auth-Modus gibt es nicht** — er steht deshalb auch nicht in der Liste oben. Der einzige
Modus ohne Prüfung von Anmeldedaten ist das Spring-Profil `dev`, und **es gehört nie auf eine
erreichbare Instanz** (siehe die Warnung unter
[„Entwicklungsmodus (dev)"](#entwicklungsmodus-dev) unten). Für den erreichbaren Betrieb ist
ausschließlich `SPRING_PROFILES_ACTIVE=...,oidc` zulässig.

## OIDC (Keycloak)

Um OIDC-Authentifizierung mit dem gebündelten Keycloak zu aktivieren:

```bash
docker compose --profile oidc up --build
```

Erforderliche Variablen in `.env.docker` (siehe [„Bestandsübernahme"](#bestandsübernahme-aus-opaa_oidc_) unten für ihre Rolle):

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_OIDC_ISSUER_URI=http://localhost:8180/realms/opaa
OPAA_OIDC_CLIENT_ID=opaa-frontend
OPAA_OIDC_JWK_SET_URI=http://keycloak:8180/realms/opaa/protocol/openid-connect/certs
OPAA_CSP_CONNECT_SRC_EXTRA=http://localhost:8180
```

> **Wichtig:** `OPAA_OIDC_JWK_SET_URI` muss den Docker-internen Hostnamen `keycloak` (nicht `localhost`) verwenden, weil der Backend-Container JWT-Tokens verifiziert, indem er Schlüssel von Keycloak abruft. `OPAA_OIDC_ISSUER_URI` bleibt `http://localhost:8180/...`, da der Browser diese Adresse verwendet — sie ist zugleich die Authority des Anmeldeflusses. `OPAA_OIDC_AUTHORITY` ist ohne Wirkung und kann entfallen. Ohne `OPAA_CSP_CONNECT_SRC_EXTRA` blockiert die Content-Security-Policy des Frontend-nginx den Aufruf gegen Keycloak stillschweigend, siehe [„Sicherheits-Header und `Strict-Transport-Security`"](#sicherheits-header-und-strict-transport-security) oben; mehrere Origins werden durch Leerzeichen getrennt, und eine Änderung wirkt erst nach einem Neustart des Frontend-Containers.

Vorkonfiguriert sind zwei Realms (`keycloak/realm-export.json`, `keycloak/realm-partner-export.json`): Der Realm `opaa` mit dem Client `opaa-frontend` ist der beim Start übernommene Standardanbieter; der Realm `partner` mit dem Client `opaa-partner` ist ein zweiter Anbieter auf demselben Origin, den die Systemverwaltung über die Anbieterverwaltung anlegt (so, wie ein Betreiber den Anbieter eines Partners anbindet) — er dient dem Demo-Smoke-Lauf und lokalen Versuchen mit mehreren Anbietern. Ein Testbenutzer im Realm `opaa`: `testuser`/`testpass`. Die Keycloak-Admin-Konsole ist unter http://localhost:8180 verfügbar (admin/admin).

### Content-Security-Policy und ein Anbieter auf fremdem Origin

**`connect-src` und ein OIDC-Anbieter auf fremdem Origin.** `oidc-client-ts` holt die OIDC-Discovery-Metadaten von der Authority und tauscht später den Auth-Code am `token_endpoint` des Anbieters gegen Tokens, jeweils per `fetch` direkt aus dem Browser — liegt einer dieser Endpunkte nicht auf demselben Origin wie das Frontend, blockiert eine reine `connect-src 'self'`-Richtlinie den Aufruf; die Oberfläche nennt dann nur eine technische Ursache, die blockierte Adresse steht in der Browser-Konsole. Welche Origins genau gehören, steht im nächsten Absatz. `frontend/nginx.conf` ist deshalb kein fest gebackenes Ergebnis mehr, sondern ein `envsubst`-Template (`/etc/nginx/templates/default.conf.template`, siehe `frontend/Dockerfile`): Die Umgebungsvariable `OPAA_CSP_CONNECT_SRC_EXTRA` wird beim Containerstart in `connect-src 'self' ${OPAA_CSP_CONNECT_SRC_EXTRA}` eingesetzt (leer per Voreinstellung im Image; `.env.docker.example` führt den Origin des mitgelieferten Keycloak als auskommentiertes Beispiel, siehe [„OIDC (Keycloak)"](#oidc-keycloak) unten für den Fall, in dem er tatsächlich gebraucht wird). `NGINX_ENVSUBST_FILTER=^OPAA_(CSP|DEMO|BACKEND)_` im Dockerfile begrenzt die Ersetzung auf `OPAA_CSP_*`-, `OPAA_DEMO_*`- und `OPAA_BACKEND_*`-Variablen (für den Demo-Hinweis siehe `OPAA_DEMO_MODE` oben, für das Weiterleitungsziel `OPAA_BACKEND_UPSTREAM`), damit `envsubst` nicht versehentlich echte nginx-Variablen (`$scheme`, `$host`, `$remote_addr`, …) in derselben Datei anfasst. Details zum Betrieb mit einem eigenen Behörden-Identitätsanbieter: Abschnitt „OIDC (Keycloak)" unten.

**Welche Origins eines Identitätsanbieters nach `connect-src` gehören.** Der Browser spricht den Anbieter auf zwei Arten an. Per `fetch`, also unter `connect-src`: das Discovery-Dokument `<Issuer>/.well-known/openid-configuration` und den `token_endpoint` aus diesem Dokument — beim Einlösen des Codes nach der Anmeldung und bei jeder Erneuerung des Tokens. Als Seitenwechsel, für den `connect-src` nicht gilt: den `authorization_endpoint` (Anmeldung) und den `end_session_endpoint` (Abmeldung). `userinfo_endpoint`, `jwks_uri` und `revocation_endpoint` ruft der Browser nicht auf; die Signaturschlüssel liest das Backend. In `OPAA_CSP_CONNECT_SRC_EXTRA` gehören deshalb der Origin des Issuers und, wenn er abweicht, der Origin des `token_endpoint`. Beispiele aus den Discovery-Dokumenten der Anbieter, abgerufen im Oktober 2026:

| Anbieter | Issuer | `token_endpoint` | Einzutragen | Weitere Origins im Dokument, nicht nötig |
|---|---|---|---|---|
| Keycloak mit Hostname `login.example.org` | `https://login.example.org/realms/<Realm>` | `https://login.example.org/realms/<Realm>/protocol/openid-connect/token` | `https://login.example.org` | keine |
| Microsoft Entra ID, Endpunkt v2.0 eines Mandanten | `https://login.microsoftonline.com/<Mandant>/v2.0` | `https://login.microsoftonline.com/<Mandant>/oauth2/v2.0/token` | `https://login.microsoftonline.com` | `https://graph.microsoft.com` (`userinfo_endpoint`) |
| Google | `https://accounts.google.com` | `https://oauth2.googleapis.com/token` | `https://accounts.google.com https://oauth2.googleapis.com` | `https://openidconnect.googleapis.com` (`userinfo_endpoint`), `https://www.googleapis.com` (`jwks_uri`) |

Für einen anderen Anbieter zeigt `curl -s <Issuer>/.well-known/openid-configuration` den `token_endpoint`. Fehlt der Origin des Issuers, meldet die Anmeldeseite nach dem Klick „Die Anmeldung bei … konnte nicht gestartet werden“ mit einer technischen Ursache, und die automatische Anmeldung ohne Klick unterbleibt still. Fehlt nur der Origin des `token_endpoint`, meldet sich die Person beim Anbieter an und landet danach auf der Rückmeldeseite (`/auth/callback`) mit einer technischen Fehlermeldung (in Chromium `Failed to fetch`). In beiden Fällen nennt die Browser-Konsole die blockierte Adresse und die verletzte Direktive `connect-src`. Ein Eintrag folgt der Syntax von Content Security Policy Level 3: ein Origin wie `https://login.example.org`, ein Host ohne Schema (`login.example.org`, dann gilt das Schema der OPAA-Seite) oder ein Platzhalter für Subdomains (`https://*.example.org` erlaubt `login.example.org`, nicht aber `example.org` selbst). Ohne Port gilt der Standardport des Schemas. Ein Pfad im Eintrag schränkt die Quelle ein: `https://login.example.org/realms/opaa` erlaubt weder das Discovery-Dokument noch den `token_endpoint`, `https://login.example.org/realms/opaa/` nur das Discovery-Dokument, denn der `token_endpoint` liegt meist unter einem anderen Pfad. Deshalb nur Origins eintragen. Ein Anbieter, der nur per `http` erreichbar ist, scheitert an einer per `https` ausgelieferten OPAA-Seite unabhängig von der Policy: Der Browser sperrt den Aufruf als Mixed Content. Ausgenommen sind Adressen des eigenen Rechners: `localhost` und seine Subdomains, `127.0.0.0/8` und `[::1]`.

### Anbieterverwaltung

OPAA akzeptiert Anmeldungen von **mehreren OIDC-Anbietern**. Die Anbieter liegen in der Datenbank und werden unter **Administration → Identitätsanbieter** (nur `SYSTEM_ADMIN`; API `/api/v1/admin/oidc-providers`) gepflegt; jede Änderung wirkt ohne Neustart. Je Anbieter:

| Feld | Bedeutung |
|------|-----------|
| Anzeigename | Beschriftung der Schaltfläche auf der Anmeldeseite („Anmelden bei …") |
| Issuer-URI | Der `iss`-Claim seiner Tokens, byteweise wie der Anbieter ihn prägt (bei Keycloak die Realm-Adresse, bei Auth0 mit abschließendem Schrägstrich); zugleich die Adresse, an der der Browser die Anmeldung startet. Eindeutig je Anbieter |
| Client-ID | Der beim Anbieter angelegte **öffentliche** Client (Authorization Code Flow mit PKCE, **kein Secret** — die SPA kann keines geheim halten) |
| JWK-Set-Adresse | Optional: die Backend-seitige Adresse des JWK-Sets, wenn das Backend den Anbieter unter einer anderen Adresse erreicht als der Browser (Compose: `keycloak` statt `localhost`). Ohne Angabe liest das Backend die Adresse aus dem Discovery-Dokument des Issuers. Sie ist der **Vertrauensanker** jedes Kontos dieses Anbieters |
| Claim-Zuordnung | Aus welchen Token-Claims E-Mail und Anzeigename gelesen werden (Vorgaben `email`/`name`, Rückfall `preferred_username`) und optional Rollen- und Gruppen-Claim, siehe unten |
| Reihenfolge, Standard, aktiviert | Reihenfolge der Anmeldeseite; genau ein **Standardanbieter**; ein deaktivierter Anbieter wird ab dem nächsten Token abgewiesen |
| Extern | Ob der Anbieter einem anderen Haus gehört. Vorgabe: jeder Anbieter außer dem Standardanbieter ist extern, bis die Systemverwaltung es ändert; die Zeile „Lokale Konten" ist es nie. Nur durch die Systemverwaltung änderbar (`POST /api/v1/admin/oidc-providers/{id}/external`), als Änderung der Anbieterzeile protokolliert. Das Kennzeichen entscheidet, wie die Gruppen dieses Anbieters in Auswahlfeldern dargestellt werden |

**Beim Anbieter einzutragen** (die Verwaltungsoberfläche zeigt die Werte mit dem eigenen Origin an): Weiterleitungs-URI `<Origin>/auth/callback` (für alle Anbieter dieselbe), erlaubter Web-Origin und Abmelde-Weiterleitung `<Origin>`. Zusätzlich im Betrieb: den Origin des Anbieters in `OPAA_CSP_CONNECT_SRC_EXTRA` aufnehmen — dazu den Origin seines `token_endpoint`, wenn er abweicht (bei Google `https://oauth2.googleapis.com`, siehe [„Sicherheits-Header und `Strict-Transport-Security`“](#sicherheits-header-und-strict-transport-security)) — und den Frontend-Container neu starten, und — wenn der Anbieter in einem privaten Netz liegt — seinen Host in `OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST` (siehe unten). Der **Verbindungstest** im Formular prüft Discovery-Dokument und JWK-Set vor dem Speichern; ist die Issuer-URI vom Backend aus nicht erreichbar (Compose-Aufteilung), prüft er das JWK-Set über die Backend-seitige Adresse und sagt das — ein Discovery-Dokument, das erreichbar ist und nicht passt (anderer Issuer, Fehlerstatus, gesperrte Adresse), bleibt ein Fehler.

**Standardanbieter.** Der erste Anbieter ist automatisch der Standard; er kann weder deaktiviert noch gelöscht werden, solange ein weiterer Anbieter existiert, der stattdessen Standard werden kann. Der Verzeichnisabgleich hängt **nicht** mehr an ihm, sondern ist je Anbieter einschaltbar (siehe [Verzeichnisabgleich je Anbieter](#verzeichnisabgleich-je-anbieter)). Ein Zustand ohne Anbieter ist zulässig: Ein aktivierter Anbieter darf nur deaktiviert oder gelöscht werden, wenn danach noch ein anmeldefähiger Systemverwalter übrig bleibt (sonst Antwort 409 mit dem Hinweis, zuerst ein lokales Systemverwalterkonto mit Passwort einzurichten); der **letzte aktivierte** Anbieter zusätzlich nur mit ausdrücklicher Bestätigung (`acknowledgeLastProvider=true`; danach können sich nur noch lokale Konten anmelden). Der Erstadministrator ist seit ADR-0033 das lokale Notanker-Konto (`OPAA_INITIAL_ADMIN_EMAIL`, siehe Variablentabelle); Konten eines Anbieters erhalten `SYSTEM_ADMIN` nur durch Rollenvergabe. **Seine Gruppen gehen mit dem Anbieter**, solange keine von ihnen noch verwendet wird: Hat eine seiner Gruppen noch Rechte an einer Bibliothek, ist sie Eigentümerin einer Bibliothek, Mitglied in einem Space oder Trägerin eines Anlegerechts, wird das Löschen mit 409 (`PROVIDER_GROUPS_IN_EFFECT`) abgelehnt, und die Meldung nennt die Zahlen („3 Gruppen werden noch verwendet: Rechte an 7 Bibliotheken, Mitglied in 2 Spaces …“). Die Ablehnung führt in die **Arbeitsliste der Gruppen** dieses Anbieters (Zeilenmenü der Anbieterliste): Sie nennt je Gruppe, wofür sie noch verwendet wird (etwa „Rechte an 7 Bibliotheken · Mitglied in 2 Spaces“), und bietet an, ihre Rechte an eine andere Gruppe zu übertragen; alternativ werden die Rechte dort entfernt, wo sie vergeben sind — an der Bibliothek oder im Space. Nicht verwendete Gruppen gehen mit dem Anbieter. **Deaktivieren bleibt jederzeit möglich** und lässt Gruppen, Mitgliedschaften und Berechtigungen unverändert stehen. Die Gruppen eines deaktivierten Anbieters sind allerdings kein neues Ziel einer Berechtigung mehr — bestehende bleiben unangetastet. Die Zeile „Lokale Konten" steht in derselben Tabelle, ist aber keine Anbieterzeile im engeren Sinn und **erscheint in der Anbieterliste nicht**: nicht löschbar, nie Standard, nur der Anzeigename ist änderbar, und ihr Aktivieren/Deaktivieren ist der Schalter der lokalen Benutzerverwaltung — bedient wird er unter **Administration → Benutzer → Einstellungen** in der Karte „Lokale Anmeldung" (das Abschalten beendet die Sitzungen aller regulären lokalen Konten; lokale Systemverwalter bleiben angemeldet).

**Anmeldung ohne Klick bei laufender Anbieter-Sitzung.** Wer die Anmeldeseite betritt und beim vorgeschlagenen Anbieter — zuletzt verwendet, sonst Standard, sonst der erste — noch eine laufende Sitzung hat, erreicht die Anwendung ohne Klick: OPAA startet einmal je Browser-Tab einen Anmeldeversuch, der dem Anbieter untersagt, dafür eine eigene Seite zu zeigen (`prompt=none`). Lehnt der Anbieter ab — keine Sitzung, Einwilligung oder Kontoauswahl nötig —, steht die Anmeldeseite unverändert da, mit allen Anbietern und, falls eingeschaltet, der Maske der lokalen Konten; eine Fehlermeldung erscheint nicht, weil niemand den Versuch ausgelöst hat. Der Versuch unterbleibt, wenn kein Anbieter aktiviert ist, wenn bereits eine Sitzung besteht, wenn dieser Browser-Tab seinen Versuch schon unternommen hat, nach einem Abmelden, nach einem Sitzungsende mit Grund (damit der Hinweis lesbar bleibt) und nach einer bewusst gestarteten Anmeldung per Klick auf einen Anbieter. Am Anbieter ist dafür nichts einzurichten, und ein Schalter, der das Verhalten abstellt, ist nicht vorgesehen. Wer mit einem **anderen Konto** desselben Anbieters hereinkommen will, meldet sich in OPAA ab: Das beendet die Sitzung beim Anbieter mit, und der nächste Klick auf den Anbieter fragt wieder nach Anmeldedaten. Wurde die Anmeldeseite von einem Direktlink aus erreicht, führt sie nach dem Versuch weiterhin auf das verlinkte Ziel — ob der Versuch gelingt oder abgelehnt wird.

**Wenn der Versuch aus einem anderen Grund abgelehnt wird.** Als Absage gilt jede Antwort des Anbieters mit einem OAuth-Fehlercode, nicht nur „keine Sitzung": Auch eine falsch eingetragene Client-ID oder eine beim Anbieter fehlende Weiterleitungs-URI führt dazu, dass die Anmeldeseite wortlos stehen bleibt — für die Person ist es derselbe Vorgang, den sie nicht ausgelöst hat, und eine rohe OAuth-Meldung hülfe ihr nicht. Zur Eingrenzung schreibt OPAA den Fehlercode in die **Browser-Konsole** (`Silent sign-in refused by the provider`). Wer den Verdacht hat, dass eine Anmeldung gar nicht erst zustande kommt, prüft dort zuerst. Bei einer Anmeldung per Klick auf einen Anbieter wird derselbe Fehler sichtbar: Der Anmeldeversuch endet dann auf der Rückmeldeseite (`/auth/callback`), die ihn im Klartext anzeigt und eine Schaltfläche „Zur Anmeldung" anbietet.

Bei eingeschalteter lokaler Benutzerverwaltung heißt das zugleich: Gelingt der Versuch, ist die Anmeldeseite samt der Maske der lokalen Konten fort, bevor jemand sie benutzen kann. Wer sich trotz laufender Anbieter-Sitzung mit einem Konto dieser Installation anmelden will, ruft **`/login/system`** auf — diese Seite schaltet den Versuch für den Browser-Tab ab und führt dann zur regulären Anmeldeseite, die damit stehen bleibt. Ein Abmelden ist dafür nicht nötig; es würde die Anbieter-Sitzung mit beenden.

**Konten.** Die Identität eines Kontos ist das Paar (Issuer, Subject). Dieselbe Person bei zwei Anbietern hat **zwei Konten** — eine Zusammenführung über die E-Mail findet nie statt. Wird ein Anbieter gelöscht, bleiben seine Konten und werden wieder nutzbar, sobald ein Anbieter mit derselben Issuer-URI existiert; ihre Fremdzugangstokens enden dagegen mit dem Löschen endgültig; die Issuer-URI eines Anbieters, über den bereits Konten angelegt wurden, ist deshalb nicht änderbar (409). Konten eines deaktivierten Anbieters können sich nicht mehr anmelden; laufende Sitzungen enden mit der nächsten Anfrage (`unknown_issuer`).

**Rollen und Gruppen aus dem Token** (optional, je Anbieter). Ist ein **Rollen-Claim** gesetzt (Pfad in Punktnotation, z. B. `realm_access.roles`, mit den Rollenwerten für `SYSTEM_ADMIN` und `AUDITOR`), ist der Anbieter für diese Systemrollen führend: Die Rolle wird bei jeder Anfrage aus dem Token übernommen, die manuelle Rollenvergabe ist für Konten dieses Anbieters gesperrt, und der letzte `SYSTEM_ADMIN` der Installation wird nie per Token entzogen (der abgelehnte Entzug wird protokolliert und auditiert). Das Setzen des Rollen-Claims verlangt in der Oberfläche eine Bestätigung. Ist ein **Gruppen-Claim** gesetzt, werden die Gruppennamen des Tokens bei jeder Anmeldung zu Mitgliedschaften in Gruppen der Art „Gruppe aus dem Identitätsanbieter"; jede solche Gruppe trägt ihren Anbieter als Verweis, gleichnamige Gruppen zweier Anbieter sind zwei Gruppen. Diese Gruppen sind in der Gruppenverwaltung schreibgeschützt. Gruppen-Claim und Verzeichnisabgleich schließen einander je Anbieter aus (siehe [Verzeichnisabgleich je Anbieter](#verzeichnisabgleich-je-anbieter)).

**Was ein Token über Rollen sagt — und was nicht.** Ein Rollen-Claim ist nur dann führend, wenn das Token ihn auch trägt. OPAA unterscheidet drei Fälle:

| Was das Token trägt | Was bei der Anmeldung geschieht |
|---|---|
| Rollen-Claim mit einem der konfigurierten Rollenwerte | Die Rolle wird übernommen (`SYSTEM_ADMIN` vor `AUDITOR`), bei Abweichung geschrieben und auditiert. |
| Rollen-Claim vorhanden, aber ohne einen der konfigurierten Werte (auch der leere Claim) | Herabstufung auf `USER`, auditiert — der Anbieter ist die führende Quelle. Ausgenommen bleibt nur der letzte anmeldefähige `SYSTEM_ADMIN`. |
| Kein Rollen-Claim, falsch geformter Claim (anderer Typ, nur unbrauchbare Werte), oder Overage-Hinweis | Es ändert sich **nichts**: Die gespeicherte Rolle bleibt, es wird kein Rollenereignis und kein Audit-Eintrag geschrieben. Das Backend meldet den Vorfall im Anwendungsprotokoll, je Anbieter und Ursache höchstens alle fünf Minuten einmal. |

Der dritte Fall ist der praktisch wichtige: Wird am Anbieter der Rollen-Mapper entfernt, umbenannt oder bei einer Umstellung vorübergehend falsch gesetzt, verlöre sonst jeder Revisor seine `AUDITOR`-Rolle und jeder Systemverwalter bis auf einen seine `SYSTEM_ADMIN`-Rolle — je Anmeldung eine Herabstufung, jede davon als reguläres „Rolle geändert"-Ereignis, das den Vorfall wie eine beabsichtigte Änderung aussehen lässt. Wer die Meldung sieht, prüft die Claim-Zuordnung des Anbieters und die Mapper-Konfiguration dort; bis dahin arbeiten die Konten mit ihrer zuletzt bekannten Rolle weiter.

**Was ein Token über Gruppen sagt — und was nicht.** Ein Gruppen-Claim ist nur dann führend, wenn das Token ihn auch trägt. OPAA unterscheidet drei Fälle:

| Was das Token trägt | Was bei der Anmeldung geschieht |
|---|---|
| Gruppen-Claim mit Werten | Die Mitgliedschaften dieses Anbieters werden auf genau diese Gruppen gebracht (Zugang und Entzug, je Änderung historisiert und auditiert). |
| Gruppen-Claim vorhanden und leer | Alle Mitgliedschaften dieses Anbieters werden entzogen, historisiert und auditiert — der Anbieter ist die führende Quelle. |
| Kein Gruppen-Claim, falsch geformter Claim, nur unbrauchbare Werte, oder Overage-Hinweis | Es ändert sich **nichts**: Mitgliedschaften, Rechtehistorie und Nachweisprotokoll bleiben unberührt. Das Backend meldet den Vorfall im Anwendungsprotokoll, je Anbieter und Ursache höchstens alle fünf Minuten einmal (ein Wechsel der Ursache wird also gemeldet, eine Wiederholung nicht). |

Der dritte Fall ist der praktisch wichtige: Wird am Anbieter der Gruppen-Mapper entfernt oder umbenannt, verlöre sonst jedes Konto bei seiner nächsten Anmeldung alle Gruppenrechte — einzeln und unauffällig. Der **Overage-Hinweis** ist der Sonderfall, in dem der Anbieter den Claim wegen seiner Größe durch einen Verweis ersetzt (Entra ID ab 200 Gruppen, `_claim_names` nach OpenID Connect 5.6.2); OPAA lädt die Gruppen nicht nach, meldet den Vorfall aber eigens benannt. Wer die Meldung sieht, prüft die Claim-Zuordnung des Anbieters und die Mapper-Konfiguration dort — bis dahin arbeiten die Konten mit ihrem letzten bekannten Gruppenstand weiter.

### Verzeichnisabgleich je Anbieter

**Ein Anbieter hat genau einen Gruppenmechanismus**: entweder den **Gruppen-Claim** seiner Tokens (Vorgabe, funktioniert ohne Konfiguration) oder den **Verzeichnisabgleich** (empfohlen für Häuser mit gepflegtem Verzeichnis). Beides zugleich lehnt OPAA mit 409 (`DIRECTORY_SYNC_MECHANISM_CONFLICT`) ab — sonst entstünde dieselbe Verzeichnisgruppe zweimal, mit zwei Wahrheiten über die Mitgliedschaft und zwei Entzugszeitpunkten. Das Einschalten des Abgleichs verlangt also einen leeren Gruppen-Claim, und das Setzen eines Gruppen-Claims verlangt einen ausgeschalteten Abgleich.

Bedient wird er unter **Administration → Verzeichnisabgleich**. Die Seite zeigt alle Identitätsanbieter in einer Übersicht, eine Zeile je Anbieter:

| Spalte | Inhalt |
|---|---|
| Anbieter | Name; „Anbieter deaktiviert“, wenn die Anbieterzeile abgeschaltet ist |
| Abgleich | **Eingeschaltet** mit Intervall, **Pausiert** (eingeschaltet, aber der Anbieter ist deaktiviert), **Gruppen aus dem Token** (der Anbieter hat einen Gruppen-Claim) oder **Aus** |
| Letzter Lauf | Ergebnis des letzten Laufs und wie lange er zurückliegt |
| Nächster Lauf | Wann der Zeitplan den Anbieter wieder fällig findet: ein Intervall nach dem letzten Lauf; „jetzt fällig“, wenn das Intervall schon verstrichen ist oder der Anbieter noch nie gelaufen ist. Gilt nur, solange der Takt eingeschaltet ist (`OPAA_DIRECTORY_SYNC_SCHEDULE_ENABLED`, siehe „Zeitplan“ unten) |
| Handlungsbedarf | **Entscheidung nötig** (ein Plan wartet, mit Alter und Wirkung), **Letzter Lauf gescheitert** (Verzeichnis nicht erreichbar oder leeres Ergebnis) oder **Verzeichniszugang fehlt** (Abgleich eingeschaltet, aber kein Zugang hinterlegt) |

Zeilen mit Handlungsbedarf sind farbig markiert, und eine Zeile über der Übersicht fasst zusammen, wie viele Anbieter eine Entscheidung brauchen oder gestört sind. Ab sechs Anbietern lässt sich die Übersicht auf **Handlungsbedarf** oder **Eingeschaltet** eingrenzen und nach dem Namen durchsuchen.

**Details** (bei einem wartenden Plan **Entscheiden**) öffnet den Anbieter in einem Seitenpanel. Dort steht, in dieser Reihenfolge:

- **Ausstehender Plan** — nur, wenn einer wartet: Alter, Bericht, Pflichtfeld für den Grund, „Plan bestätigen“ und „Plan verwerfen“. Bestätigen ist ein Lauf und geht deshalb nur bei eingeschaltetem Abgleich und aktivem Anbieter; Verwerfen geht immer.
- **Letzter Lauf** — Statuszeile mit Meldung, Zeitpunkt und Änderungsanteil, dazu „Trockenlauf“ und „Jetzt abgleichen“. Beide gehen nur bei eingeschaltetem Abgleich und aktivem Anbieter; das Ergebnis erscheint darunter.
- **Zeitplan** — Schalter „Abgleich eingeschaltet“, Intervall in Minuten mit „Intervall speichern“ und der nächste Lauf. Bei einem Anbieter mit Gruppen-Claim ist der Schalter gesperrt, und das Panel nennt den Grund; ebenso lässt er sich nicht einschalten, solange das eingetragene Intervall außerhalb der zulässigen Grenzen liegt.
- **Verzeichniszugang** — der hinterlegte Zugang (Verzeichnis, Realm, Dienstkonto, Adresse der Admin-API) und das Formular zum Speichern, Testen und Entfernen.
- Ein Verweis auf die **Arbeitsliste der Gruppen** des Anbieters.

Dieselben Einstellungen über die Schnittstelle je Anbieterzeile (`PUT /api/v1/admin/oidc-providers/{id}/directory-sync`, nur `SYSTEM_ADMIN`):

| Einstellung | Bedeutung |
|---|---|
| Abgleich an/aus | Vorgabe **aus**. Ein Update schaltet nirgends einen Abgleich ein, der vorher nicht lief |
| Intervall | Wie oft ein Lauf fällig ist, in Minuten. Vorgabe **360** (6 Stunden), Grenzen **5** bis **10080** (eine Woche) |

**Woran der Abgleich hängt.** Er läuft für die Konten des **jeweiligen** Anbieters: Mitglieder werden nur unter dessen Issuer aufgelöst, und die entstehenden Organisationseinheiten tragen ihn als Herkunft. Gruppen eines anderen Anbieters sind für einen Lauf unsichtbar — er kann sie weder auflösen noch umbenennen. **Ist die Anbieterzeile deaktiviert, pausiert ihr Abgleich**; ein deaktivierter Anbieter ist kein Vertrauensanker, und ein Lauf von Hand wird mit 409 (`DIRECTORY_SYNC_PROVIDER_DISABLED`) abgewiesen.

**Zeitplan.** Ein Lauf startet ohne Zutun, sobald der letzte Lauf des Anbieters mindestens ein Intervall zurückliegt; ein Anbieter, der noch nie gelaufen ist, ist sofort fällig. Ein verpasster Zeitpunkt wird nicht nachgeholt — der nächste Takt findet den Anbieter wieder fällig. Der Takt selbst lässt sich mit `OPAA_DIRECTORY_SYNC_SCHEDULE_ENABLED=false` ganz abschalten; das Intervall bleibt dann eine Einstellung ohne Wirkung, und Läufe gehen nur noch von Hand.

**Die vier Schutzmechaniken.** Der Trockenlauf (`…/directory-sync/dry-run`) rechnet den Unterschied aus, ohne etwas zu schreiben. Ein **leeres Abgleichergebnis** bei vorhandenen Verzeichnisgruppen bricht hart ab (`ABORTED_EMPTY_RESULT`) — „die Quelle hat nicht geantwortet, wie sie soll" darf niemand wegklicken. Ein **unerreichbares Verzeichnis** lässt den letzten bekannten Stand in Kraft (`UNREACHABLE`). Und ein Lauf, der mehr als die **Plausibilitätsschwelle** (`OPAA_DIRECTORY_SYNC_CHANGE_THRESHOLD_FRACTION`, Vorgabewert in der [Variablentabelle](#alle-umgebungsvariablen)) an Mitgliedschaften entziehen oder Gruppen auflösen würde, schreibt nichts und legt seinen Plan zur Bestätigung vor.

**Der Bestätigungsweg.** Ein solcher Lauf endet mit dem Ergebnis `PENDING_CONFIRMATION` und hinterlässt einen **ausstehenden Plan**:

- Der Plan steht mit seinem **Alter** in der Statuszeile des Anbieters (`GET /api/v1/admin/directory-sync/status`), nicht erst auf einer Unterseite. Angesehen wird er unter `…/directory-sync/pending-plan`.
- **Ein neuer Lauf ersetzt ihn.** Es staut sich also nie ein Vorrat an Plänen, von dem irgendwann der älteste bestätigt würde.
- **Bestätigt wird gegen einen frischen Schnappschuss** (`…/pending-plan/{planId}/confirm`, mit Pflichtangabe eines Anlasses): OPAA liest das Verzeichnis erneut und wendet nur an, was dem vorgelegten Plan noch entspricht. Weicht das Ergebnis ab, wird nichts angewendet, der neu gerechnete Plan tritt an die Stelle des alten, und die Antwort ist 409 (`DIRECTORY_SYNC_PLAN_CHANGED`). Ein Plan, den ein späterer Lauf bereits ersetzt hat, antwortet 404.
- **Verworfen** wird er unter `…/pending-plan/{planId}/discard`, ebenfalls mit Anlass. Das entscheidet über diesen Plan, es unterdrückt den Befund nicht: Der nächste Lauf rechnet ihn neu und legt ihn gegebenenfalls erneut vor.
- Bestätigung und Verwerfen sind **Protokollereignisse** mit der handelnden Person und ihrem Anlass (`DIRECTORY_SYNC_RUN_COMPLETED` bzw. `DIRECTORY_SYNC_PLAN_DISCARDED`).

**Der Bericht nennt Namen, und das steht im Protokoll.** Trockenlauf, Lauf und ausstehender Plan zeigen, wen ein Lauf in welche Gruppe aufnimmt oder aus ihr entfernt und welche Konten er sperrt oder entsperrt — für eine neu angelegte Gruppe die vollständige Mitgliederliste. Jeder Abruf des ausstehenden Plans, jeder Trockenlauf, jeder Lauf von Hand und jede Bestätigung, deren Bericht mindestens eine Person nennt, hinterlässt deshalb einen Protokolleintrag `DIRECTORY_SYNC_REPORT_READ`: mit der handelnden Person, dem Anbieter, dem Weg und der Zahl der genannten Gruppen und Personen, ohne die Namen. Ein Bericht ohne Namen und der zeitgesteuerte Lauf erzeugen keinen Eintrag. Die Übersicht der Seite Verzeichnisabgleich ruft den Plan nicht ab, sie nennt nur sein Alter und seine Zahlen. Erst das **Öffnen eines Anbieters mit offenem Plan** erzeugt einen Eintrag; ein erneutes Öffnen oder ein neu geladener Abgleichstand mit demselben Plan erzeugt keinen weiteren, solange die Seite geöffnet bleibt. Lässt sich der Eintrag nicht schreiben, liefert OPAA den Bericht nicht aus und antwortet mit einem Fehler — bei einem Lauf von Hand und bei einer Bestätigung ist die Änderung dann trotzdem schon angewendet. Was der Lauf bewirkt hat, zeigen in diesem Fall die Statuszeile und das Protokoll.

**Kontostatus aus dem Verzeichnis.** Derselbe Lauf übernimmt neben den Gruppen den **Kontostatus** der Konten dieses Anbieters. Ein Konto, das das Verzeichnis als gesperrt meldet **oder gar nicht mehr meldet**, verliert beim nächsten Lauf seinen Zugang — nicht erst, wenn sein Token abläuft:

- **Gesperrt, nicht gelöscht.** Mitgliedschaften, Spaces, Rollen und Eigentum bleiben unverändert stehen. Die Sperre ist rückholbar: Meldet das Verzeichnis das Konto wieder als freigeschaltet, hebt der nächste Lauf sie auf. **Eine Entsperrung von Hand gibt es nicht** — OPAA schreibt nie ins Verzeichnis, und die Sperre käme beim nächsten Lauf zurück. Der Weg führt über das Verzeichnis.
- **Sofort wirksam.** Jede weitere Anfrage des Kontos wird mit 401 abgewiesen, auch mit einem noch gültigen Token, und seine persönlichen Zugangstokens treten außer Kraft. Die Person sieht beim nächsten Aufruf den Grund und die Ansprechstelle, keine wortlose Abweisung.
- **Dieselbe Schwelle, derselbe Bestätigungsweg.** Sperren zählen in die Plausibilitätsschwelle wie Mitgliedschaftsentzüge: Ein Lauf, der einen auffällig großen Teil der Konten eines Anbieters sperren würde, legt seinen Plan vor, statt ihn anzuwenden. Der Plan nennt die Zahl der Sperren neben den entzogenen Mitgliedschaften. Eine **leere Kontenliste** sperrt niemanden (`ABORTED_EMPTY_RESULT`) — wie eine leere Gruppenliste ein harter Abbruch ohne bestätigbaren Plan.
- **Der letzte anmeldefähige Systemverwalter wird nie gesperrt.** Seine Sperre wird zurückgehalten und im Bericht des Laufs namentlich ausgewiesen, statt stillschweigend zu entfallen.
- **Protokoll und Historie.** Jede Sperre und jede Entsperrung ist ein Protokollereignis (`DIRECTORY_ACCOUNT_LOCKED`, `DIRECTORY_ACCOUNT_UNLOCKED`), verbunden mit dem Kopfeintrag des Laufs. Zusätzlich hält die Rechtehistorie den Kontozustand mit Beginn und Ende fest und unterliegt der Aufbewahrungshöchstdauer der Rechtehistorie (12 bis 120 Monate, ausgeliefert 36). Einen „Verlauf" am Konto in der Benutzerverwaltung gibt es bewusst nicht.
- **Ein Konto mit Beständen lässt sich sofort sperren.** Offene Eigentums- oder Zuständigkeitsfragen halten eine Sperre nie auf.

**Wechsel des Mechanismus.** Token-Gruppen und Verzeichnisgruppen sind verschiedene Objekte. Schaltet ein Haus einen Anbieter vom Gruppen-Claim auf den Abgleich um, weist der Differenzbericht seine Token-Gruppen als **„werden nicht mehr gepflegt"** aus; sie bleiben mit eingefrorener Mitgliedschaft stehen. **Nichts wird stillschweigend entzogen** — die Übertragung ihrer Berechtigungen auf die neuen Verzeichnisgruppen ist eine eigene Handlung; sie steht in der Gruppenverwaltung und in der Arbeitsliste des Anbieters (siehe „Rechte einer Gruppe auf eine andere übertragen" im Kapitel [Berechtigungen](berechtigungen.md#132-rechte-einer-gruppe-auf-eine-andere-übertragen)).

**Im Betriebsmodus `dev` findet kein Verzeichnisabgleich statt.** Dort gibt es keine Anbieterzeile, und OPAA legt auch keine künstliche an: Jede Zeile der Anbietertabelle ist ein Vertrauensanker der Anmeldung, aus dem das Backend einen Token-Prüfer baut — eine Zeile, die niemanden anmeldet und nur zwei Einstellungsspalten hält, wäre der falsche Preis. Wer den Abgleich lokal ausprobieren will, legt im `dev`-Modus eine gewöhnliche Anbieterzeile an und schaltet den Abgleich dort ein; der Lauf liest Issuer und Herkunft aus der Zeile und hängt an keiner Stelle am Betriebsmodus. Verzeichnisgruppen, die vor dieser Umstellung im `dev`-Modus ohne Anbieterbezug entstanden sind, werden beim Update zu **internen Gruppen** (mit Herkunftsvermerk in der Beschreibung und einem Protokolleintrag je Gruppe); Berechtigungen und Mitgliedschaften bleiben unangetastet.

**Ohne hinterlegten Verzeichniszugang meldet jeder Lauf das Verzeichnis als unerreichbar** — die sichere Richtung: Es wird nichts entzogen. Der Zugang wird je Anbieter hinterlegt; der erste (und bisher einzige) Konnektor ist die Keycloak Admin REST API.

### Keycloak als Verzeichnis

**Was OPAA liest.** Der Lauf meldet sich mit einem Dienstkonto an der Admin API an und liest den kompletten Gruppenbaum des Realms samt seiner Mitgliedschaften (`GET /admin/realms/{realm}/groups`, `…/groups/{id}/children`, `…/groups/{id}/members`, jeweils seitenweise) sowie die Konten des Realms mit ihrem Kennzeichen `enabled` (`…/users`, ebenfalls seitenweise und in der Kurzform ohne Attribute und Anmeldedaten). Jede Keycloak-Gruppe wird zu einer Organisationseinheit in OPAA:

| Keycloak | OPAA |
|---|---|
| Gruppen-ID (UUID) | stabile Kennung der Gruppe — **danach wird abgeglichen** |
| Gruppenname | Anzeigename; eine Umbenennung im Verzeichnis ändert keine Berechtigung |
| Gruppenpfad (`/Haus/Referat 50`) | Herkunftspfad; nur zur Anzeige, unterscheidet gleichnamige Untergruppen |
| übergeordnete Gruppe | übergeordnete Einheit (nur Anzeige und Gliederung — **Mitgliedschaft vererbt nicht**) |
| Benutzer-ID eines Mitglieds | `sub` des Kontos in OPAA, aufgelöst unter dem Issuer **dieses** Anbieters |
| `enabled` eines Benutzers | Kontostatus in OPAA — ein Konto ohne dieses Kennzeichen, oder eines, das der Realm gar nicht mehr meldet, wird gesperrt (siehe [Kontostatus aus dem Verzeichnis](#verzeichnisabgleich-je-anbieter)) |

**Warum Keycloak zuerst.** Die Keycloak-Benutzer-ID *ist* der `sub` der Tokens, die dieselbe Instanz ausstellt. Die Identität eines Mitglieds fällt damit ohne Abbildungsregel mit dem Konto in OPAA zusammen. Der Realm wird aus der **Issuer-URI der Anbieterzeile abgeleitet** (`https://kc.example/realms/haus` → Realm `haus`) und ist bewusst nicht getrennt einstellbar: So kann ein Lauf strukturell nur den Realm lesen, aus dem die Tokens dieses Anbieters stammen. Ist die Issuer-URI keine Keycloak-Realm-Adresse, lehnt OPAA das Hinterlegen mit 400 ab.

**Nur direkte Mitglieder.** `…/groups/{id}/members` liefert die Personen, die unmittelbar in dieser Gruppe stehen — nicht die ihrer Untergruppen. Eine **Abteilung, die nur Untergruppen hat, ist in OPAA eine leere Gruppe**: Eine Freigabe an sie erreicht niemanden. Das ist Absicht (Mitgliedschaft vererbt in OPAA nirgends), und der Differenzbericht nennt deshalb **je Gruppe die Mitgliederzahl**, damit das vor dem Anwenden sichtbar ist. Wer eine ganze Abteilung erreichen will, hinterlegt ihre Mitglieder auch in der Abteilungsgruppe selbst oder vergibt die Freigabe an die Referatsgruppen.

**Das Dienstkonto einrichten.** In Keycloak, im **selben Realm**, den OPAA liest:

1. **Clients → Create client**: Client-ID z. B. `opaa-directory`, *Client authentication* **an** (vertraulicher Client), *Standard flow* und *Direct access grants* **aus**, **Service accounts roles** **an**.
2. **Credentials**: das Client-Geheimnis notieren (es wird gleich in OPAA hinterlegt und danach nie wieder angezeigt).
3. **Service accounts roles → Assign role → Filter by clients → `realm-management`**: genau die beiden Rollen **`view-users`** und **`query-groups`** zuweisen. Mehr braucht der Abgleich nicht — und mehr sollte das Konto auch nicht haben; es liest nur.

In OPAA hinterlegen (nur `SYSTEM_ADMIN`):

```bash
# Zugang speichern (Realm kommt aus der Issuer-URI des Anbieters)
PUT /api/v1/admin/oidc-providers/{providerId}/directory-connector
{ "type": "KEYCLOAK", "clientId": "opaa-directory", "clientSecret": "…" }

# Verbindung prüfen, ohne etwas zu speichern; ohne clientSecret wird das hinterlegte benutzt
POST /api/v1/admin/oidc-providers/{providerId}/directory-connector/test
{ "type": "KEYCLOAK", "clientId": "opaa-directory" }

# Zugang samt Geheimnis wieder entfernen
DELETE /api/v1/admin/oidc-providers/{providerId}/directory-connector
```

Ein erfolgreicher Test antwortet z. B. „Verzeichnis erreichbar: Realm „haus" mit 14 Gruppen."; ein abgewiesenes Dienstkonto nennt die beiden fehlenden Rollen.

**`baseUrl` (optional).** Erreicht das Backend Keycloak unter einer anderen Adresse als der Browser — im Compose-Stapel `http://keycloak:8180` gegenüber `http://localhost:8180` —, gehört die backendseitige Adresse in `baseUrl`. Ohne Angabe leitet OPAA sie aus der Issuer-URI ab. Die Adresse durchläuft **dieselbe Adressprüfung** wie Issuer- und JWK-Set-Adresse (siehe [Adressprüfung](#bestandsübernahme-aus-opaa_oidc_)); eine hausinterne Adresse gehört in `OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST`. Weiterleitungen folgt das Backend nicht.

**Das Geheimnis liegt verschlüsselt.** Das Client-Geheimnis wird mit `OPAA_CREDENTIALS_ENCRYPTION_KEY` verschlüsselt gespeichert (AES-256-GCM, Präfix `enc:v1:`, siehe [Zugangsdaten-Verschlüsselung](#zugangsdaten-verschlüsselung)) — **ohne gesetzten Schlüssel lässt sich kein Verzeichniszugang speichern**. Keine API-Antwort gibt es zurück; angezeigt werden nur Typ, Adresse, Realm und Client-ID.

> **Vorbehalt zur Schlüsselrotation.** Das **Passwort des Dienstkontos** lässt sich jederzeit wechseln: In Keycloak neu erzeugen, in OPAA erneut hinterlegen — der Wert wird dabei mit dem aktuellen Schlüssel neu verschlüsselt. **Nicht vorgesehen ist dagegen der Wechsel von `OPAA_CREDENTIALS_ENCRYPTION_KEY` selbst im Bestand**: Es gibt heute keinen Weg, bestehende Chiffretexte umzuschlüsseln (ein Wechsel des Verfahrens soll ein Format `enc:v2:` bringen). Wer den Schlüssel dennoch wechselt, muss jeden betroffenen Wert danach neu eingeben — für den Verzeichniszugang heißt das: Zugang erneut hinterlegen. **Die Anbieterverwaltung bleibt dabei bedienbar:** Ein nicht mehr entschlüsselbares Geheimnis wird beim Lesen als „nicht vorhanden" behandelt (mit Warnung im Anwendungslog), die Anbieterliste antwortet weiter, und beide Auswege — erneut hinterlegen und entfernen — funktionieren. Bis dahin meldet jeder Lauf dieses Anbieters das Verzeichnis als **nicht erreichbar** und entzieht nichts; der Verbindungstest verlangt das Geheimnis ausdrücklich, statt mit einem leeren Wert anzuklopfen. Ob aus den Vorgaben eines Hauses eine harte Anforderung an eine regelmäßige Schlüsselrotation folgt (BSI IT-Grundschutz ORP.4 ist der naheliegende Baustein, **zu prüfen**), entscheidet der Informationssicherheitsbeauftragte des Hauses; dieses Handbuch hält nur die Lage fest.

**Grenzen des Konnektors.**

- **Ein Realm je Anbieter** — der aus der Issuer-URI. Zwei Realms sind zwei Anbieterzeilen.
- **Nur Gruppen und Mitgliedschaften.** Kontosperren aus dem Verzeichnis sind ein eigener Schritt.
- **Obergrenzen.** Mehr als `OPAA_DIRECTORY_SYNC_KEYCLOAK_MAX_GROUPS` Gruppen oder `…_MAX_MEMBERS_PER_GROUP` direkte Mitglieder je Gruppe lassen den Lauf abbrechen (`UNREACHABLE`), statt eine abgeschnittene Liste anzuwenden. Die Werte sind für Verwaltungsrealms reichlich bemessen; wer sie erreicht, hebt sie bewusst an.
- **Keine anderen Verzeichnistypen.** LDAP und Microsoft Graph folgen als eigene Konnektoren. In vielen Häusern ist Keycloak ohnehin die Vermittlungsschicht vor Active Directory: Seine LDAP-Mapper importieren die AD-Gruppen, und OPAA liest sie dann aus Keycloak.

### Bestandsübernahme aus `OPAA_OIDC_*`

Die Variablen `OPAA_OIDC_ISSUER_URI`, `OPAA_OIDC_CLIENT_ID` und `OPAA_OIDC_JWK_SET_URI` sind **Bootstrap-Werte**: Beim ersten Start im `oidc`-Modus übernimmt OPAA sie einmalig als ersten, aktivierten Standardanbieter „Verzeichnisdienst" — vor dem Start des Webservers, sodass keine Anmeldung der Übernahme zuvorkommt. Danach führt die Datenbank; eine Änderung der Variablen wirkt nicht mehr. Eine Bestandsinstallation, deren Konten unter diesem Issuer angelegt wurden, behält damit jedes Konto (kein Eingriff an `users`). Fehlt beim Start eine der beiden Pflichtvariablen `OPAA_OIDC_ISSUER_URI` und `OPAA_OIDC_CLIENT_ID` (`OPAA_OIDC_JWK_SET_URI` ist optional) oder ist die Issuer-URI keine http(s)-Adresse, wird nichts übernommen, das Backend protokolliert, was zu setzen ist, und holt die Übernahme beim nächsten Start nach. Mit einem anmeldefähigen lokalen Systemverwalterkonto (etwa dem Notanker-Konto) ist eine fehlende Issuer-URI eine `INFO`-Meldung (Betrieb nur mit lokalen Konten). Eine unvollständige oder fehlerhafte Angabe ist ein Fehler (`ERROR`), der die weiterhin mögliche Anmeldung mit lokalen Konten nennt. Ohne ein solches Konto ist die Meldung immer ein Fehler und sagt, dass keine Anmeldung möglich ist. Sobald ein Anbieter in der Anbieterverwaltung angelegt ist, entfällt die Meldung.

**Die Variablen bleiben gesetzt.** Sie sind der Notanker: `OPAA_OIDC_BOOTSTRAP=force` stellt den Anbieter aus der Umgebung einmalig wieder her (Zeile mit diesem Issuer überschrieben bzw. angelegt, aktiviert, Standard) — der dokumentierte Weg zurück aus einer vertippten Issuer-URI des einzigen Anbieters. Die Variable danach wieder entfernen; jeder weitere Start würde die Anbieterverwaltung erneut überschreiben. Außerdem sind die **Adressen** der beiden Variablen (Schema, Host und Port von Issuer-URI und JWK-Set-Adresse) für die Adressprüfung immer erlaubt.

**Adressprüfung.** Jede von der Systemverwaltung eingegebene Issuer- und JWK-Set-Adresse durchläuft dieselbe Prüfung gegen private Netzbereiche wie die Datenquellen (SSRF-Schutz), mit eigener Konfiguration `OPAA_OIDC_TARGET_VALIDATION_ENABLED` (Vorgabe `true`) und `OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST` (Hosts, kommagetrennt). Ein hausinterner Anbieter mit privater Adresse gehört in die Allowlist; die Bootstrap-Adressen brauchen keinen Eintrag. Discovery-Dokument und JWK-Set werden vom Backend selbst abgerufen, ohne Weiterleitungen zu folgen.

**Tokenprüfung (`azp`/`aud`).** Ein Token wird nur angenommen, wenn sein `azp`-Claim (authorized party) die `client_id` der Anbieterzeile nennt, der Claim ganz fehlt, oder `aud` die `client_id` enthält — sonst weist das Backend die Anfrage mit HTTP 401 ab und nennt den Grund im `WWW-Authenticate`-Header. Bei einem öffentlichen Client ist das die einzige Kontrolle dagegen, dass ein Token, das derselbe Anbieter für eine **andere** Anwendung ausgestellt hat, hier angenommen wird. Praktische Folge für eigene Dienst-Clients: Keycloak setzt `azp` immer auf den anfragenden Client, ein zweiter Client braucht deshalb einen Audience-Mapper auf die `client_id` dieser Installation. Der mitgelieferte Seed-Client `opaa-seed` ist genau dieser Fall und trägt den Mapper im Realm-Export (siehe [„Härtung für erreichbare Deployments"](#härtung-für-erreichbare-deployments), Punkt 6 und der Absatz zu Änderungen am Realm).

### Grenzen

- **Keine Kontenzusammenführung:** Zwei Anbieter, dieselbe E-Mail — zwei Konten mit getrennten Rechten. Wer eine Person von einem Anbieter zum anderen umzieht, vergibt ihre Rechte neu.
- **Kein SAML, kein reines OAuth2:** Ausschließlich OpenID Connect mit Discovery-Dokument und Authorization Code Flow (PKCE, öffentlicher Client).
- **Nur Keycloak als Verzeichnis:** Der Abgleich ist je Anbieter einschaltbar, zeitgesteuert und mit Bestätigungsweg gebaut; angebunden ist bisher ausschließlich die Keycloak Admin REST API (siehe [Keycloak als Verzeichnis](#keycloak-als-verzeichnis)). Ohne hinterlegten Zugang meldet jeder Lauf „nicht erreichbar“ und entzieht nichts. Im Betriebsmodus `dev` findet gar kein Abgleich statt — dort gibt es keine Anbieterzeile, an die er gebunden wäre.
- **Keine Übertragung von Berechtigungen zwischen Gruppen:** Ein Anbieter, dessen Gruppen noch Berechtigungen tragen, ist nicht löschbar; die Berechtigungen müssen einzeln entfernt werden. Deaktivieren bleibt jederzeit möglich.
- **`AUDITOR` ist nicht geschützt:** Ein durch einen benannten Rollen-Claim entzogener letzter Prüfer ist nur im Anbieter wiederherstellbar (ein fehlender oder unbrauchbarer Claim entzieht die Rolle nicht).
- **Eine Organisation:** Alle Anbieter provisionieren in dieselbe Organisation.

## Härtung des mitgelieferten Keycloak

| # | Fundstelle | Vorgabewert | Risiko bei erreichbarem Betrieb | Gegenmaßnahme |
|---|---|---|---|---|
| 1 | `keycloak/realm-export.json` | Realm-Benutzer `testuser`/`testpass` sowie die fünf Demo-Nutzer (`demo-admin`, `maria.weber`, `selin.kaya`, `thomas.klein`, `andrea.vogt`) mit dem gemeinsamen Passwort `RheinfurtDemo!2026`, alle `"temporary": false` | Bekannte, öffentlich im Repository stehende Zugangsdaten zu echten Konten — jeder, der das Repository kennt, kennt diese Logins | **Zwingend.** Diese Benutzer aus dem Realm-Export entfernen oder durch eigene, beim Import per Skript gesetzte Zufallspasswörter ersetzen, bevor der Realm importiert wird. Wer den mitgelieferten Realm unverändert importiert, importiert diese Konten mit. Für einen Rollout der Demo-Instanz selbst gilt grundsätzlich dieselbe Pflicht, mit einer bewussten Ausnahme: Die vier Fachkonten behalten dort das dokumentierte Demo-Passwort als akzeptiertes Restrisiko (sie sind `USER`-Konten ohne Adminrechte, begrenzt durch Rate Limiting und ein Ausgabenlimit beim Chat-Anbieter); `testuser` ist deaktiviert, `demo-admin` trägt ein rotiertes Passwort |
| 2 | `keycloak/realm-export.json:6` | `"sslRequired": "none"` | Keycloak nimmt Anmeldungen und Admin-Zugriffe auch unverschlüsselt an — in Kombination mit einer erreichbaren Instanz eine offene Tür für das Mitlesen von Zugangsdaten | **Zwingend**, sobald die Instanz nicht ausschließlich über eine als vertrauenswürdig geltende Loopback-/interne Verbindung erreicht wird: auf `external` (TLS für externe Zugriffe verpflichtend, intern optional) oder `all` (TLS immer verpflichtend) setzen. TLS selbst terminiert dabei **nicht** Keycloak, sondern der vorgelagerte Reverse-Proxy — siehe [„Netzwerkzugang"](#netzwerkzugang) unten (nginx terminiert TLS, Keycloak selbst bleibt intern auf HTTP). **Zur selben Maßnahme gehört zwingend `KC_PROXY_HEADERS=xforwarded`** (Keycloak-Umgebungsvariable, analog zum in [„Netzwerkzugang"](#netzwerkzugang) beschriebenen `server.forward-headers-strategy` des Backends) — ohne sie hält Keycloak eine über den Proxy per TLS ankommende Anfrage für unverschlüsselt und antwortet mit „HTTPS required", was einen Betreiber typischerweise dazu verleitet, `sslRequired` fälschlich wieder auf `none` zurückzustellen, statt die eigentliche Ursache zu beheben. Bei Einhängung unter einem Unterpfad (z. B. `/idp`) zusätzlich `KC_HTTP_RELATIVE_PATH` setzen. Wie beim Backend muss der vorgelagerte Proxy `X-Forwarded-Proto` dabei **autoritativ** setzen und den Wert nicht vom Client durchlassen |
| 3 | `docker-compose.yml:73-74` | Keycloak-Bootstrap-Admin `KC_BOOTSTRAP_ADMIN_USERNAME`/`_PASSWORD` fest auf `admin`/`admin` | Voller administrativer Zugriff auf den Identitätsanbieter mit einem der bekanntesten Vorgabepasswörter überhaupt | **Zwingend.** Über eigene Umgebungsvariablen setzen und **nach der Ersteinrichtung ersetzen** — der Bootstrap-Admin legt beim allerersten Start einen administrativen Benutzer an; sein Passwort danach unverändert zu lassen, macht die einmalige Bootstrap-Vereinfachung zu einer dauerhaften Schwachstelle. **Nicht** über `.env.docker`: Der `keycloak`-Service hat kein `env_file` und liest `${...}`-Platzhalter in `docker-compose.yml` stattdessen aus der Shell-Umgebung bzw. einer `.env`-Datei (siehe die Erklärung unter [„Konfiguration"](#konfiguration) unten) — die beiden Werte gehören also in `.env` oder werden per `docker compose --env-file <datei>` übergeben, mit derselben Vorsicht wie dort beschrieben, damit sie keine lokalen Entwicklungseinstellungen überschreiben |
| 4 | `docker-compose.yml:71` | `command: start-dev --import-realm` — kein persistentes Datenverzeichnis, nur der Realm-Export ist als Datei gemountet | Keycloaks eingebaute Entwicklungsdatenbank ist an den Container gebunden und geht bei jedem Neuerstellen verloren — ein rotiertes Bootstrap-Passwort (Punkt 3) und ein bereinigter Realm (Punkt 1) verschwinden damit beim nächsten `docker compose up -d`/Image-Update und `admin`/`admin` sowie `testuser` sind wieder da, ohne dass das auffällt | **Zwingend**, sonst wirken die Gegenmaßnahmen zu Punkt 1 und 3 nicht dauerhaft, sondern nur bis zum nächsten Container-Neustart. Für erreichbaren Betrieb `start` statt `start-dev` verwenden (das erzwingt ohnehin die übrigen Härtungspunkte dieser Zeile — Keycloak startet mit `start` ohne konfiguriertes TLS/Proxy-Setup gar nicht erst) und eine externe, persistente Datenbank anbinden (`KC_DB`, `KC_DB_URL` u. a.) statt der eingebauten Entwicklungsdatenbank. `--import-realm` importiert einen Realm dabei ohnehin nur, wenn er noch nicht existiert — nach der ersten, persistenten Einrichtung wirken spätere Realm-Änderungen über die Admin-Konsole oder einen erneuten, gezielten Import |
| 6 | `keycloak/realm-export.json` (Client `opaa-seed`) | Öffentlicher Client mit `directAccessGrantsEnabled: true` (Resource-Owner-Password-Grant) und ohne Client-Secret, ausschließlich für das Seed-Skript der Demo (`demo/seed/seed.py`) gedacht, das sich damit als Demo-Nutzer anmeldet und Bibliotheken, Rechte und Chats über die reguläre API anlegt | Erlaubt einen passwortbasierten Tokenweg **ohne Secret** gegen jedes Realm-Konto — auf einer erreichbaren Instanz ein zusätzlicher, von der eigentlichen Anmeldung (`opaa-frontend`, `directAccessGrantsEnabled: false`, Authorization-Code + PKCE) unabhängiger Angriffsweg, unabhängig davon, wessen Passwort betroffen ist | **Zwingend.** Client `opaa-seed` aus dem Realm-Export entfernen oder auf `enabled: false` setzen, bevor der Realm auf einer erreichbaren Instanz importiert wird. Wer die Demo dort dennoch erneut seeden will, aktiviert den Client nur für die Dauer des Laufs wieder (per `kcadm` oder Admin-Konsole) oder legt die Rechte direkt über die Keycloak-Admin-Konsole/API an, statt den Client dauerhaft scharf zu lassen |
| 8 | `.env.docker.example` und `e2e/local-auth.env` (`OPAA_AUTH_LOCAL_COOKIE_SECURE`) | Die Anwendungsvorgabe ist `true`; die Beispiel- und Testdateien zeigen, wie sie für rein lokales HTTP auf `false` gesetzt wird | Auf `false` gesetzt reist das Refresh-Cookie auch über eine unverschlüsselte Verbindung — wer mitliest, hält eine Sitzung, die sich selbst erneuert. Der Wert ist leicht aus einer Entwicklungskonfiguration mitgeschleppt | **Zwingend.** Auf einer erreichbaren Instanz bleibt `OPAA_AUTH_LOCAL_COOKIE_SECURE` ungesetzt (damit `true`), und die Anwendung liegt hinter TLS. Der `oidc`-Betriebsmodus schreibt bei `false` zu jedem Start eine Warnung ins Log — diese Zeile ist der Prüfpunkt nach jedem Übernehmen einer Beispielkonfiguration |
| 9 | `keycloak/realm-export.json` (Client `opaa-directory`) | Vertraulicher Dienstkonto-Client für den Verzeichnisabgleich mit dem Geheimnis `RheinfurtVerzeichnis!2026` und den Rollen `view-users` und `query-groups` aus `realm-management` | Wer das Geheimnis kennt, liest über die Admin-API den ganzen Gruppenbaum des Realms, alle Mitgliedschaften und alle Konten samt ihrem Kontostatus — ein Verzeichnisabzug ohne Anmeldung einer Person | **Zwingend**, zumal die Admin-API von Keycloak auf einer erreichbaren Instanz meist mit erreichbar ist. Ein eigenes Geheimnis zufällig erzeugen (etwa `openssl rand -base64 32`), außerhalb des Repositorys ablegen und vor dem Import in den Realm-Export setzen — oder den Client nach dem Import in Keycloak unter **Credentials** neu erzeugen. Denselben Wert in OPAA unter **Administration → Verzeichnisabgleich** hinterlegen (siehe [„Keycloak als Verzeichnis"](#keycloak-als-verzeichnis)); ein Werkzeug, das Realm oder Verzeichniszugang automatisiert pflegt, muss diesen Wert übergeben bekommen und darf nie auf den Vorgabewert zurückfallen. Wer den Verzeichnisabgleich nicht nutzt, entfernt den Client |

**Änderungen am Realm auf einer bereits laufenden Instanz:** Wie bei Punkt 4 oben importiert
`--import-realm` einen Realm nur, wenn er noch nicht existiert — auf einer Instanz mit bereits
importiertem Realm (Keycloak mit eigenem Volume oder eigener Datenbank) wirkt eine spätere
Änderung an `keycloak/realm-export.json` also **nicht von selbst**. Ein erneuter, vollständiger
Import würde außerdem die dokumentierte Härtung der Konten aus Punkt 1 zurückdrehen. Zwei
Änderungen, die deshalb gezielt über `kcadm` (oder die Admin-Konsole) nachzuziehen sind:

*Lebensdauern* — `keycloak/realm-export.json` setzt `accessTokenLifespan`, `ssoSessionIdleTimeout`
und `ssoSessionMaxLifespan` explizit:

```bash
kcadm.sh config credentials --server http://localhost:8180 --realm master --user admin
kcadm.sh update realms/opaa -s accessTokenLifespan=900 -s ssoSessionIdleTimeout=3600 -s ssoSessionMaxLifespan=36000
```

*Audience-Mapper des Seed-Clients* — der Client `opaa-seed` aus Punkt 6 trägt im Export einen
`oidc-audience-mapper` auf `opaa-frontend`. Ohne ihn nennt sein Token die `client_id` der
Anbieterzeile weder in `azp` noch in `aud`, und das Backend weist jeden Aufruf eines Seed-Laufs mit
HTTP 401 ab (siehe [„OIDC (Keycloak)"](#oidc-keycloak)). Maßgeblich ist dabei genau diese
`client_id` aus der Anbieterverwaltung — `OPAA_OIDC_CLIENT_ID` ist nur ihr Bootstrap-Wert und wirkt
auf einer laufenden Installation nicht mehr (siehe
[„Bestandsübernahme aus `OPAA_OIDC_*`"](#bestandsübernahme-aus-opaa_oidc_)). Lautet sie nicht
`opaa-frontend`, gehört ihr Wert in `included.client.audience`:

```bash
kcadm.sh create clients/<id-des-clients-opaa-seed>/protocol-mappers/models -r opaa \
  -s name=opaa-frontend-audience -s protocol=openid-connect \
  -s protocolMapper=oidc-audience-mapper \
  -s 'config."included.client.audience"=opaa-frontend' \
  -s 'config."access.token.claim"=true'
```

## Erststart und Systemverwalter-Konto

**Beim allerersten Start im Betriebsmodus `oidc` legt OPAA sich seinen ersten Systemverwalter selbst
an** — als **lokales** Konto, unabhängig davon, ob ein Identitätsanbieter konfiguriert ist. Das ist
der Weg hinein, bevor irgendetwas eingerichtet ist, und zugleich der Weg zurück, wenn ein Anbieter
falsch konfiguriert wurde.

Zwei Variablen steuern das:

```env
# Pflicht: die Adresse des ersten Systemverwalters - ein zustellbares Postfach,
# am besten ein Funktionspostfach der IT.
OPAA_INITIAL_ADMIN_EMAIL=it-postfach@beispielstadt.de

# Optional: ein festes Anfangspasswort statt eines erzeugten. Für CI und
# automatisierte Bereitstellung gedacht; dann wird kein Wechsel erzwungen.
#OPAA_INITIAL_ADMIN_PASSWORD=
```

**Wo das Einmalpasswort steht.** Ist `OPAA_INITIAL_ADMIN_PASSWORD` nicht gesetzt, erzeugt OPAA ein
Passwort und schreibt es **einmalig** als deutlich markierten Block in das Anwendungslog:

```text
=====================================================================
  NOTANKER-KONTO DER SYSTEMVERWALTUNG - EINMALIGE AUSGABE
  Anmeldung:  mit der Adresse aus OPAA_INITIAL_ADMIN_EMAIL
  Passwort:   <hier steht das erzeugte Passwort>
  Der Wechsel des Passworts wird bei der ersten Anmeldung erzwungen.
  Diese Ausgabe erscheint einmalig und wird nicht wiederholt.
=====================================================================
```

Zu lesen mit `docker compose logs backend | grep -A5 NOTANKER` direkt nach dem ersten Start. Danach
nennt das Log nur noch, **dass** gesät wurde, nie wieder den Wert. Die Kehrseite ist bekannt und
bewusst in Kauf genommen: **Eine Log-Weiterleitung sieht diesen Block.** Drei Dinge halten den
Schaden klein — das Passwort ist nur bis zur ersten Anmeldung gültig (der Wechsel wird erzwungen),
das Konto ist ohne Anmeldung wertlos, und wer das Log nicht belasten will, setzt
`OPAA_INITIAL_ADMIN_PASSWORD` und übernimmt die Übergabe selbst.

**Die Adresse muss zustellbar sein, und der ausgelieferte Vorgabewert wird abgelehnt.** Steht in
`OPAA_INITIAL_ADMIN_EMAIL` nichts, etwas ohne `@` oder der frühere Vorgabewert `admin@opaa.local`,
legt OPAA **kein** Konto an, schreibt einen Fehler ins Log und nennt die Variable. Es merkt sich
diesen Versuch nicht: Ein Start mit korrigierter Adresse holt die Anlage nach. Der Grund ist
einfach — die Adresse wird zum Anmeldenamen eines privilegierten Kontos an einem erreichbaren
Formular, und unter `admin@opaa.local` erreicht niemand ein Postfach, also auch keinen Rücksetzlink.

**Erststart und Bestandsinstallation sind zwei Fälle.** Findet OPAA beim Säen bereits Konten eines
bestehenden Anbieters vor, legt es das Konto **ohne Passwort** an („eingeladen") und schreibt kein
Passwort ins Log. Es ist dann vorhanden, aber nicht scharf; ein Haus, das lokale Konten
organisatorisch ausschließt, bekommt damit kein gültiges Passwort in sein Log. Wer es später braucht,
aktiviert es bewusst über die Notfallprozedur unten.

**Das Notanker-Konto ist ein Notfallzugangsmittel, kein Arbeitskonto.** Für den Betrieb gilt:

1. **Einmal anmelden, Passwort wechseln** (wird erzwungen, wenn es erzeugt wurde).
2. **Persönliche Verwalterkonten anlegen** — je Person eines, über
   [Benutzerverwaltung](benutzerverwaltung.md). Die tägliche Arbeit läuft über diese Konten.
3. **Das Passwort des Notanker-Kontos versiegelt hinterlegen** — dort, wo das Haus andere
   Notfallzugänge hinterlegt. Es ist der Weg zurück, wenn die Anbieteranbindung bricht.

Das Konto bleibt anmeldefähig. **Gegenüber Verwaltungsakten ist es unverletzlich**: Löschen,
Sperren, Befristen, Herabsetzen der Rolle, die Übergabe an einen Identitätsanbieter sowie ein von
der Verwaltung erzeugtes Passwort oder einen Link zum Zurücksetzen lehnt OPAA immer ab (409
`BOOTSTRAP_ACCOUNT`), auch wenn weitere Systemverwalter anmeldefähig sind — ein
Ablaufdatum in der Zukunft würde den Notweg sonst still und zeitversetzt schließen, ein von einer
anderen Systemverwaltung ersetztes Passwort das versiegelte wertlos machen, ohne dass die
Verwahrenden davon erfahren. Sein Passwort ändert das Konto nach der Anmeldung selbst; ohne
Anmeldung setzt es nur die Notfallprozedur unten neu. Von der Sperre
nach Inaktivität ist es ausgenommen (sein Zweck ist, unbenutzt zu bleiben). Nur die
Fehlversuch-Sperre (15 Minuten nach fünf Fehlversuchen) gilt auch für dieses Konto. **Jede erfolgreiche Anmeldung
mit ihm steht im Nachweisprotokoll** (`LOCAL_BOOTSTRAP_ACCOUNT_LOGIN`) und löst eine Nachricht an
alle übrigen Systemverwalter aus — „wurde der Notfallzugang benutzt?" ist die Prüferfrage zu jedem
Notfallkonto, und sie soll beantwortbar sein. Erkannt wird es an einer internen Markierung, nicht an
seiner Adresse: Adresse und Anzeigename dürfen sich ändern, ohne dass der Notweg verloren geht.

> **Ein Konto eines Identitätsanbieters wird nicht mehr durch `OPAA_INITIAL_ADMIN_EMAIL` zum
> Systemverwalter.** Die Variable bezeichnet ausschließlich das lokale Notanker-Konto. IdP-Konten
> erhalten die Rolle entweder über die Benutzer-Rollenvergabe oder über den Rollen-Claim ihres
> Anbieters. Im `dev`-Modus ist `dev-admin` unverändert Systemverwalter.

**Lokaler Erstadministrator statt OIDC-Erstadmin-Regel (seit 11.09.2026, ADR-0033).** Beim ersten
Start nach dem Update legt OPAA im Profil `oidc` das lokale Notanker-Konto der Systemverwaltung an —
auf einer Bestandsinstallation ohne Passwort (`INVITED`), es bleibt bis zu einem bewussten
`OPAA_LOCAL_ADMIN_RESET=force` unbenutzbar. Dafür braucht `OPAA_INITIAL_ADMIN_EMAIL` einen echten
Wert: Der alte Vorgabewert `admin@opaa.local` wird abgelehnt und der Start protokolliert so lange
einen Fehler (ohne abzubrechen), bis ein zustellbares Postfach eingetragen ist. Bestehende
Systemverwalter behalten ihre Rolle; ein Konto eines Identitätsanbieters mit der Adresse aus
`OPAA_INITIAL_ADMIN_EMAIL` wird künftig als regulärer Nutzer angelegt.

## Lokale Benutzerverwaltung

Die Verwaltung lokaler Konten ist **ein Schalter unter Administration → Benutzer → Einstellungen** und im
Auslieferungszustand **aus**. Was sie im Alltag leistet — anlegen, einladen, sperren, zurücksetzen,
befristen, Selbstregistrierung — steht im Kapitel [Benutzerverwaltung](benutzerverwaltung.md). Für
den Betrieb sind vier Dinge wichtig:

- **Abgeschaltet heißt: nur Systemverwalter.** Ist der Schalter aus, können sich reguläre lokale
  Konten nicht anmelden, und laufende Sitzungen enden beim nächsten Aufruf — die Person liest dabei
  „Die Anmeldung mit Konten dieser Installation wurde abgeschaltet" und wird an die
  Systemverwaltung verwiesen. Lokale Systemverwalterkonten passieren weiter. Ihre eigene
  Anmeldeseite ist `/login/system`; die reguläre Anmeldeseite verlinkt sie unauffällig, damit die
  Verwaltung hereinkommt, ohne dass die Maske allen angeboten wird.
- **Die Empfehlung lautet: im Regelbetrieb aus.** Wer einen Identitätsanbieter betreibt, lässt die
  Verwaltung regulärer lokaler Konten ausgeschaltet und befristet die Systemverwalterkonten. Lokale
  Konten laufen am Verzeichnis und damit am Austrittsprozess vorbei; die Gegenmittel (Pflicht-Anlass,
  Ablaufdatum, Sperre nach Inaktivität, Erinnerungen, vierteljährliche Wiedervorlage) machen das
  beherrschbar, ersetzen den Prozess aber nicht.
- **Einladungen und Rücksetzlinks brauchen Mail und eine öffentliche Adresse.** Ohne beides zeigt
  OPAA den Link zur Übergabe an, und die Selbstbedienungsflüsse „Passwort vergessen" und
  Selbstregistrierung lassen sich gar nicht einschalten. Siehe
  [„E-Mail-Versand (SMTP)"](#e-mail-versand-smtp) unten.
- **Es gibt keinen zweiten Faktor.** Eine Anmeldung mit einem zweiten Faktor (TOTP) ist für lokale
  Systemverwalterkonten die naheliegende nächste Härtung, aber **nicht gebaut**. Bis dahin
  kompensieren: die Netzbeschränkung `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS` (lässt die Anmeldung lokaler
  Systemverwalter nur aus den genannten Netzen zu; leer = keine Beschränkung), kurze Sitzungsfristen
  für diese Konten (eigene, kürzere Vorgaben, siehe Variablentabelle), die Auditierung des
  Notanker-Kontos und die Empfehlung oben.

- Die lokale Anmeldung ist je Client-Adresse, je Konto und insgesamt begrenzt
  (`OPAA_RATE_LIMIT_LOCAL_AUTH_*`, Werte in der Variablentabelle): Eine überschrittene Grenze
  antwortet `429` mit `Retry-After`. Die globale Grenze je Fenster für Anmeldung, Registrierung und
  „Passwort vergessen" ist das Frühwarnsignal des Betriebs gegen verteiltes Ausprobieren gestohlener
  Zugangsdaten — ihr Überschreiten steht als Warnung im Log und als Metrik
  (`opaa.rate_limit.rejected`, Dimensionen `limit` und `scope`). Nach
  `OPAA_AUTH_LOCAL_LOCKOUT_MAX_ATTEMPTS` falschen Passwörtern ist ein lokales Konto für
  `OPAA_AUTH_LOCAL_LOCKOUT_DURATION` gesperrt; die Anmeldung antwortet währenddessen genau wie bei
  einem falschen Passwort, die Sperre steht als `LOCAL_ACCOUNT_LOCKED_AFTER_FAILED_LOGINS` im
  Nachweisprotokoll und als Metrik `opaa.auth.local_account_lockout`, der einzelne Fehlversuch nur
  im Anwendungslog (Konto-Kennung, nie die Adresse). Die Zähler je Adresse sind je Grenze auf eine
  hohe Anzahl gleichzeitig geführter Schlüssel begrenzt; darüber werden die am wenigsten genutzten
  vergessen und beginnen bei der nächsten Anfrage ein frisches Fenster (die Grenze fällt dann für
  diese Adressen offen aus, nicht geschlossen) — die globale Grenze fängt genau diesen Fall auf.
- **Die Erneuerung einer lokalen Sitzung im Browser ist nur über einen sicheren Ursprung
  abgestimmt.** Die Oberfläche serialisiert das Erneuern über die Sperren-Schnittstelle des Browsers,
  und die steht ausschließlich in einem *Secure Context* zur Verfügung — also unter `https://` oder
  auf `localhost`. Wird die Anwendung über `http://` unter einem Hostnamen oder einer IP-Adresse
  ausgeliefert, fällt die Abstimmung zwischen mehreren Tabs still weg: Zwei Tabs können dasselbe
  Refresh-Token gleichzeitig vorlegen, das zweite gilt als Wiederverwendung, und **alle** Sitzungen
  des Kontos werden widerrufen. Das Fehlerbild ist „die Sitzung bricht beim Arbeiten in mehreren Tabs
  immer wieder ab". Abhilfe ist dieselbe wie bei Punkt 8 der Tabelle: TLS davor.

**Was organisatorisch zu klären ist, bevor die lokale Benutzerverwaltung eingeschaltet wird:** Drei
Punkte gehören nach `docs/features/security-and-compliance.md` in eine Dienstvereinbarung oder eine
vergleichbare Regelung, und zwar **vor** dem Einschalten, nicht danach:

- **Der Schalter selbst** und der Schalter der Selbstregistrierung — beide erzeugen Konten außerhalb
  des Verzeichnisdienstes, also außerhalb des Ein- und Austrittsprozesses des Hauses.
- **Der Auszug für die Personalvertretung** mit der geschlossenen Liste der Nachweisereignisse
  — sie ist dokumentiert, bevor das erste lokale Konto entsteht, nicht erst auf Nachfrage.
- **Eine jährliche Vorlage** dieses Auszugs, zusätzlich zur vierteljährlichen Wiedervorlage der
  Kontenprüfung, die OPAA selbst an die Systemverwaltung schickt
  ([Benutzerverwaltung](benutzerverwaltung.md), Abschnitt 6).

## Notfallprozedur: wieder hereinkommen

Für den Fall, dass sich **niemand** mehr anmelden kann — der letzte Anbieter ist falsch
konfiguriert, das Notanker-Konto wurde gelöscht oder sein Passwort ist verloren:

```env
OPAA_LOCAL_ADMIN_RESET=force
```

Setzen, Backend neu starten, **danach wieder entfernen**. Der Start stellt einmalig einen
anmeldefähigen lokalen Systemverwalter her: Das Notanker-Konto wird entsperrt, ein Ablaufdatum
entfernt, die Systemverwalter-Rolle wiederhergestellt, ein neues Einmalpasswort gesetzt (im Log oder
aus `OPAA_INITIAL_ADMIN_PASSWORD`) und alle seine Sitzungen beendet. Wurde das Konto gelöscht, legt
der Start es mit der konfigurierten Adresse neu an. Der Vorgang steht laut im Log und im
Nachweisprotokoll (`LOCAL_ADMIN_RESET`).

**Die Variable bleibt nicht stehen.** Jeder weitere Start mit gesetztem Wert setzt das Konto erneut
zurück; das Log sagt das beim Durchlauf.

Anschließend läuft die Reparatur über die Oberfläche: als lokaler Systemverwalter anmelden, unter
Administration → Benutzer → Anbieter den falschen Anbieter korrigieren oder einen neuen anlegen. **Ein
Datenbankeingriff ist dafür nicht nötig**, und die frühere Variable `OPAA_OIDC_BOOTSTRAP=force` ist
dafür nicht mehr der Weg — sie wird mit dem Datum in
[„Migrationen aus älteren Ständen"](#migrationen-aus-älteren-ständen) entfernt.

## Nacharbeit nach einer Rücksicherung der Datenbank

Eine zurückgesicherte Datenbank bringt Zustände mit, die bis zur lokalen Benutzerverwaltung außerhalb
von OPAA lagen: widerrufene Sitzungen gelten wieder, verbrauchte Einladungs- und Rücksetzlinks sind
wieder einlösbar, und ein nach einem Verdacht geänderter Passworthash ist auf den alten Stand
zurückgefallen. Deshalb gehören nach **jeder** Rücksicherung zwei Schritte dazu:

1. **`OPAA_AUTH_JWT_SECRET` rotieren.** Ein neuer Wert beendet alle lokalen Sitzungen und entwertet
   alle offenen Einladungs-, Rücksetz- und Übergabelinks in einem Schritt — alle Prüfwerte hängen an
   diesem Geheimnis. Das ist der eine Handgriff, der einen Aufräumlauf ersetzt.
2. **Sperren und Rücksetzungen seit dem Sicherungszeitpunkt erneut vornehmen.** Wer zwischen
   Sicherung und Rücksicherung gesperrt oder zurückgesetzt wurde, ist es danach nicht mehr. Das
   Nachweisprotokoll der Zwischenzeit ist die Liste, die dabei abzuarbeiten ist — sofern es selbst
   nicht Teil der Rücksicherung war.

Sind Fremdzugänge in Betrieb, kommt eine dritte Prüfung dazu: Schalter, Freigaben und Zugangstokens
rollen mit zurück, einschließlich eines Notaus und gesperrter Tokens. Die Prüfliste dafür steht im
Kapitel [Fremdzugänge](fremdzugaenge.md).

## Testkonten im Überblick

Im Repository existieren mehrere Testkonto-Muster nebeneinander. Sie sind **bewusst nicht
vereinheitlicht** — jedes gehört zu einem anderen Auth-Modus bzw. einer anderen Konfigurationsebene
und folgt dessen jeweils eigenem Mechanismus:

| Muster | Geltungsbereich | Nutzer |
|--------|------------------|--------|
| **Notanker-Konto der Systemverwaltung** (`OPAA_INITIAL_ADMIN_EMAIL`, siehe [„Erststart und Systemverwalter-Konto"](#erststart-und-systemverwalter-konto) oben) | Jede Installation im Betriebsmodus `oidc` — **kein Testkonto, sondern ein echtes Konto dieser Installation**; es steht hier, weil es in CI-, E2E- und Demo-Stacks mit einem festen Passwort bestückt wird | Die Adresse aus `OPAA_INITIAL_ADMIN_EMAIL`, Anzeigename „Systemverwaltung", Rolle `SYSTEM_ADMIN`. Passwort: einmalig im Log oder aus `OPAA_INITIAL_ADMIN_PASSWORD`. Die im Repository stehenden Werte sind `RheinfurtNotanker!2026` (Demo-Smoke, `e2e/demo-smoke.env`) und `E2eNotankerKonto!2026` (Ziel `local-auth`, `e2e/local-auth.env`) — dokumentierte Testwerte zweier Stacks, die ein Befehl erzeugt und wieder wegwirft, niemals Vorgaben für eine Installation |
| Entwicklungsnutzer des `dev`-Profils (`opaa.auth.dev.users`) | Lokale Entwicklung, `dev`-Auth-Modus (siehe [„Entwicklungsmodus (dev)"](#entwicklungsmodus-dev) oben) | `dev-admin` (`admin@opaa.local`, `SYSTEM_ADMIN`), `dev-user` (regulärer Nutzer) |
| Keycloak-Realm-Nutzer (`keycloak/realm-export.json`) | `oidc`-Auth-Modus mit dem gebündelten Keycloak (siehe [„OIDC (Keycloak)"](#oidc-keycloak) oben) | `testuser`/`testpass` (E-Mail `test@opaa.local`) — ein regulärer Nutzer. Die Rolle `SYSTEM_ADMIN` erhält er **nicht** mehr über `OPAA_INITIAL_ADMIN_EMAIL` (diese Variable bezeichnet seit der lokalen Benutzerverwaltung nur noch das Notanker-Konto), sondern durch Rollenvergabe — angemeldet als lokaler Systemverwalter — oder über den Rollen-Claim des Anbieters |
| Demo-Realm-Nutzer (`keycloak/realm-export.json`) | Demo-Instanz „Stadt Rheinfurt" (Compose-Profil `demo`, befüllt durch das Seed-Skript `demo/seed/seed.py`) | `demo-admin` (`admin@stadt-rheinfurt.example`; die Rolle `SYSTEM_ADMIN` vergibt der Seed-Lauf über das Notanker-Konto, nicht mehr `OPAA_INITIAL_ADMIN_EMAIL`), `maria.weber`, `selin.kaya`, `thomas.klein`, `andrea.vogt` — alle mit dem offenen Demo-Passwort `RheinfurtDemo!2026`. Zusätzlich der Client `opaa-seed` (Resource Owner Password Grant, `directAccessGrantsEnabled: true`) — ausschließlich für das Seed-Skript, nie für eine reguläre Anmeldung |
| Realm des Kubernetes-Erprobungsaufbaus (`examples/kubernetes-trial/keycloak/realm-opaa.json`) | Keycloak im Erprobungsaufbau für Kubernetes (siehe [Kubernetes](kubernetes.md#erprobung-mit-dem-beispiel-setup)) — Client `opaa`, Weiterleitungs-URIs aus der Adresse des Aufbaus | `testuser`/`testpass` wie oben und `maria.weber` mit dem Demo-Passwort `RheinfurtDemo!2026`, beide reguläre Nutzer. Das Notanker-Konto bekommt dort ein beim Start erzeugtes Passwort, das im Secret des Aufbaus liegt |
| Partner-Realm-Nutzer (`keycloak/realm-partner-export.json`) | Zweiter Identitätsanbieter (Realm `partner`, Client `opaa-partner`) auf demselben Keycloak — nicht beim Start übernommen, sondern über die Anbieterverwaltung anzulegen (der Demo-Smoke-Lauf tut genau das) | `maria.weber` mit **derselben E-Mail** wie im Realm `opaa` und dem Demo-Passwort `RheinfurtDemo!2026` — ein eigenes, zweites Konto ohne die Rechte der Demo-Maria: der Nachweis, dass Konten zweier Anbieter nie zusammengeführt werden |
| E2E-Suite (`e2e/e2e.env`, `e2e/docker-compose.e2e.yml`) | Playwright-Suite, Ziel `e2e` | Wiederverwendet `dev-admin` und `dev-user` aus dem `dev`-Profil, ergänzt um `dev-outsider` und `dev-format-pipelines` (nur für diese Suite, über `OPAA_AUTH_DEV_USERS_*` hinzugefügt) |
| E2E-Ziel `local-auth` (`e2e/local-auth.env`, `e2e/docker-compose.local-auth.yml`) | Playwright-Lauf gegen den Betriebsmodus `oidc` **ohne** Keycloak — der einzige, der einen echten lokalen Anmeldevorgang übt | Nur das Notanker-Konto der ersten Zeile, mit festem Passwort. Alle weiteren Konten legen die Szenarien selbst über die Verwaltung an und werfen sie mit dem Stack wieder weg |
| Quellenzugangsdaten (`sourceCredentials`, siehe [„Zugangsdaten-Verschlüsselung"](#zugangsdaten-verschlüsselung) oben) | Kein Testkonto für OPAA selbst — Basic-Auth-Zugangsdaten (`user:password`), mit denen eine `HTTP_DIRECTORY`- oder `RSS_FEED`-Bibliothek eine *externe* Dokumentenquelle abruft | Kein fester Beispielwert; frei je Bibliothek |

Warum keine Vereinheitlichung:

- Der `dev`-Modus prüft grundsätzlich **keine** Anmeldedaten — er authentifiziert jede Anfrage
  ungeprüft als einen der konfigurierten Nutzer (siehe oben). Ein Passwort dafür zu vergeben wäre
  irreführend, weil keines abgefragt wird.
- Der Keycloak-`testuser` ist dagegen ein echtes, wenn auch bewusst schwaches Credential innerhalb
  eines vollständigen OIDC-Identitätsanbieters — ein anderer Mechanismus mit eigenem Lebenszyklus
  (Realm-Import), der sich nicht auf `dev`-Nutzer abbilden lässt.
- `sourceCredentials` ist kein Konto zum Anmelden bei OPAA, sondern die Zugangsdaten, mit denen das
  Backend selbst eine externe Quelle kontaktiert — fachlich und im Lebenszyklus unabhängig von den
  beiden Auth-Modi oben.
- Die E2E-Suite legt bewusst **kein** eigenes Kontoschema an, sondern läuft im `dev`-Auth-Modus und
  nutzt dessen Nutzer weiter: Die Filterkette ist dort identisch zur OIDC-Konfiguration, die Suite
  übt also dieselben Autorisierungsregeln aus, ohne Keycloak, Realm-Import und Anmeldeablauf im
  Prüfpfad zu haben. Die Anmeldevorgänge selbst prüfen zwei eigene Läufe: den Keycloak-Login der
  Demo-Smoke-Lauf, die lokale Anmeldung das Ziel `local-auth`.
- Das Notanker-Konto ist das eine Muster, das **nicht** vereinheitlicht werden kann: Es ist ein echtes
  Konto der jeweiligen Installation. Dass es in zwei Stacks mit einem festen, im Repository stehenden
  Passwort bestückt ist, ist der dafür vorgesehene Weg für automatisierte Bereitstellung — und genau
  der Grund, warum `OPAA_INITIAL_ADMIN_PASSWORD` auf einer erreichbaren Instanz ungesetzt bleibt.

## Reihenfolge der Anmeldewege

Die Anmeldeseite zeigt oben die Maske für Konten dieser Installation — sofern die lokale
Benutzerverwaltung eingeschaltet ist —, darunter die Verzeichnisdienste, einer je Zeile. Der Verweis
„Anmeldung für die Systemverwaltung" steht weiterhin am Fuß der Seite, solange die lokale
Benutzerverwaltung aus ist; danach führt `/login/system` ohnehin auf die reguläre Anmeldung.

## Fehlerbehebung

### OIDC: "Completing sign in..." hängt

- Sicherstellen, dass Keycloak läuft (`docker compose --profile oidc ps`)
- Wenn Keycloak neu gestartet wurde, ist das Access-Token möglicherweise abgelaufen — Seite neu laden und erneut anmelden
- Prüfen, dass `OPAA_OIDC_JWK_SET_URI` `keycloak:8180` (nicht `localhost:8180`) verwendet

### Das Backend startet nicht und nennt `OPAA_AUTH_JWT_SECRET`

Im Betriebsmodus `oidc` ist das Geheimnis Pflicht, mindestens 32 Zeichen lang und darf kein erkannter
Platzhalter sein. Mit `openssl rand -base64 48` erzeugen und in `.env.docker` setzen; Einzelheiten
unter [„Vorbereitungsschritte für Bestandsinstallationen"](#vorbereitungsschritte-für-bestandsinstallationen).
Dieselbe Prüfung gilt für die Sitzungsfristen: Eine Obergrenze oder eine verletzte Reihenfolge
(Zugangstoken ≤ Leerlauffrist ≤ Höchstdauer) nennt die Meldung mit Variablennamen.

### Kein Einmalpasswort im Log zu finden

Drei Ursachen, in dieser Reihenfolge zu prüfen:

- **`OPAA_INITIAL_ADMIN_PASSWORD` ist gesetzt.** Dann erzeugt OPAA keines und schreibt keinen Block.
- **Die Adresse wurde abgelehnt.** Eine Fehlerzeile nennt `OPAA_INITIAL_ADMIN_EMAIL` und den Grund
  (leer, keine Adresse, oder der abgelehnte Vorgabewert `admin@opaa.local`). Korrigieren und neu
  starten — die Anlage wird nachgeholt.
- **Es ist eine Bestandsinstallation.** Fand der Start bereits Konten vor, entsteht das Konto ohne
  Passwort und ohne Log-Ausgabe. Aktivierung über
  [„Notfallprozedur: wieder hereinkommen"](#notfallprozedur-wieder-hereinkommen).

Ein bereits gesäter Stand wiederholt die Ausgabe nicht. Wer das Passwort verloren hat, nutzt die
Notfallprozedur.

### Die Anmeldung antwortet `429` mit `Retry-After`

Die Rate-Grenze der Anmeldung ist erreicht. Steht ein Reverse-Proxy davor und ist
`OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS` nicht gesetzt, **teilen alle Clients einen Zähler** — dann trifft
die Grenze das ganze Haus. Die aufgelöste Client-Adresse zeigt der Diagnose-Endpunkt unter
[„Diagnose ohne Shell"](#diagnose-ohne-shell); stimmt sie nicht mit der Adresse des Browsers überein,
ist die Proxy-Liste die Ursache.

### Eine Anmeldung wird abgelehnt, ohne einen Grund zu nennen

Das ist beabsichtigt: Unbekannte Adresse, falsches Passwort, gesperrtes, abgelaufenes und noch nicht
bestätigtes Konto beantwortet OPAA **identisch**. Den Zustand eines Kontos zeigt die Kontenliste unter
Administration → Benutzer ([Benutzerverwaltung](benutzerverwaltung.md), Abschnitt 6). Kommt eine Person
dagegen in die Anwendung und wird sofort auf „Passwort ändern" geführt, ist ein Wechsel erzwungen — die
Seite nennt den Anlass.

### Eine Sitzung bricht beim Arbeiten in mehreren Tabs wiederholt ab

Die Abstimmung der Sitzungserneuerung zwischen Tabs braucht einen sicheren Ursprung. Wird die
Anwendung über `http://` unter einem Hostnamen oder einer IP ausgeliefert, fällt sie still weg; zwei
Tabs legen dann dasselbe Token vor, und das gilt als Wiederverwendung. Abhilfe ist TLS — siehe den
Punkt zur Sitzungserneuerung unter
[„Härtung für erreichbare Deployments"](#härtung-für-erreichbare-deployments).
