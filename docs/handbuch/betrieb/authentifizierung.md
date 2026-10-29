# Authentifizierung

Dieses Kapitel beschreibt, wie Menschen sich bei OPAA anmelden: über einen oder mehrere
Identitätsanbieter mit OpenID Connect, über Konten, die OPAA selbst führt, und über das eine
Notanker-Konto der Systemverwaltung. Es beschreibt außerdem, wie Gruppen und Kontosperren aus dem
Verzeichnis kommen und was zu tun ist, wenn niemand mehr hereinkommt. Wie die Verwaltung lokale
Konten im Alltag pflegt, steht im Kapitel [Benutzerverwaltung](../konten-und-rechte/benutzerverwaltung.md); was Gruppen
für Rechte bedeuten, im Kapitel [Berechtigungen](../konten-und-rechte/berechtigungen.md).

> **Beispiel.** Das Haus meldet seine Beschäftigten über das eigene Keycloak an, das die Gruppen
> aus dem Active Directory durchreicht. Ein Partneramt bekommt einen zweiten Anbieter. Lokale Konten
> gibt es nur für die Systemverwaltung, und das Notanker-Konto liegt versiegelt im Tresor der IT —
> für den Tag, an dem die Anbindung an Keycloak bricht.

Der Zugang fremder KI-Werkzeuge (persönliche Zugangstokens, MCP-Server) ist ein eigener Schalter,
unabhängig vom Anmeldeverfahren, und im Auslieferungszustand aus ([Externe KI-Werkzeuge](../spaces/fremdzugaenge.md)).

## Betriebsmodi

Der Modus wird über das Spring-Profil gewählt; ist weder `oidc` noch `dev` gesetzt, **bricht das
Backend den Start mit einer Meldung ab**.

| Modus | Profil | Zweck |
|-------|--------|-------|
| `oidc` | `oidc` | **Betriebsmodus** und der einzige für den Betrieb zulässige: Identitätsanbieter und lokale Konten. Der Name ist geblieben, obwohl der Modus auch die Konten enthält, die OPAA selbst führt |
| `dev` | `dev` | Lokale Entwicklung und automatisierte Tests — **keinerlei Prüfung von Anmeldedaten** |

Einen weiteren Modus ohne Anmeldung gibt es nicht. Für eine erreichbare Instanz ist ausschließlich
`SPRING_PROFILES_ACTIVE=...,oidc` zulässig.

## Entwicklungsmodus (`dev`)

```env
SPRING_PROFILES_ACTIVE=docker,dev
```

Es gibt keinen Anmeldevorgang und kein Token: Jede Anfrage gilt als einer der konfigurierten
Entwicklungsnutzer, vorkonfiguriert `dev-admin` (`admin@opaa.local`, Systemverwalter) und `dev-user`
(regulärer Nutzer). Gewechselt wird im Browser mit `?devUser=dev-user` (für die Dauer der
Browser-Sitzung gemerkt), bei API-Aufrufen mit dem Header `X-OPAA-Dev-User`; ein unbekannter Name
führt zu `401`. Lokale Konten gibt es in diesem Modus nicht; einen Verzeichnisabgleich nur für eine
eigens angelegte Anbieterzeile.

> **Warnung:** Dieser Modus schaltet die Authentifizierung vollständig ab. Er gehört nur auf
> Arbeitsplätze und in Testumgebungen, nie auf eine erreichbare Instanz; das Backend warnt beim
> Start. Es startet in diesem Modus nicht, wenn das Frontend des Compose-Stapels oder, außerhalb von
> Docker, `OPAA_SERVER_ADDRESS` über Loopback hinausreicht, außer `OPAA_AUTH_DEV_ALLOW_REMOTE=true`
> bestätigt es ([Härtung des Compose-Stapels](docker-compose.md#härtung-des-compose-stapels)).

## Anmeldewege im Betriebsmodus `oidc`

Die Anmeldeseite zeigt oben die Maske für Konten dieser Installation — sofern die lokale
Benutzerverwaltung eingeschaltet ist —, darunter die Identitätsanbieter, einer je Zeile, in der
Reihenfolge der Anbieterverwaltung. Ist die lokale Benutzerverwaltung aus, steht am Fuß der Seite der
Verweis „Anmeldung für die Systemverwaltung" auf `/login/system`. Wie Produktname und Bilder der
Anmeldeseite festgelegt werden, steht im Kapitel [Branding](branding.md).

**Anmeldung ohne Klick.** Hat jemand beim vorgeschlagenen Anbieter — zuletzt verwendet, sonst
Standard, sonst der erste — noch eine laufende Sitzung, erreicht er die Anwendung ohne Klick: OPAA
startet je Browser-Tab einmal einen Anmeldeversuch, bei dem der Anbieter keine eigene Seite zeigen
darf (`prompt=none`). Lehnt der Anbieter ab, steht die Anmeldeseite unverändert da, ohne
Fehlermeldung. Der Versuch unterbleibt, wenn kein Anbieter aktiviert ist, eine Sitzung besteht, der
Tab es schon versucht hat, nach einem Abmelden, nach einem Sitzungsende mit Grund und nach einem
bewussten Klick auf einen Anbieter. Ein Schalter dafür ist nicht vorgesehen.

- **Ein anderes Konto desselben Anbieters:** in OPAA abmelden. Das beendet die Sitzung beim Anbieter
  mit, und der nächste Klick fragt wieder nach Anmeldedaten.
- **Ein lokales Konto trotz laufender Anbieter-Sitzung:** `/login/system` aufrufen. Die Seite schaltet
  den Versuch für den Tab ab und führt zur regulären Anmeldeseite.
- **Direktlinks** führen nach dem Versuch auf das verlinkte Ziel, ob er gelingt oder nicht.
- **Fehlersuche:** Als Absage gilt jede Antwort mit einem OAuth-Fehlercode, auch eine falsch
  eingetragene Client-ID oder eine fehlende Weiterleitungs-URI. OPAA schreibt den Fehlercode in die
  **Browser-Konsole** (`Silent sign-in refused by the provider`). Bei einer Anmeldung per Klick endet
  derselbe Fehler sichtbar auf der Rückmeldeseite `/auth/callback`.

## OIDC (Keycloak)

OPAA nimmt Anmeldungen von **einem oder mehreren OIDC-Anbietern** an. Angebunden wird jeder Anbieter
mit OpenID Connect, Discovery-Dokument und Authorization Code Flow mit PKCE über einen öffentlichen
Client. Der Compose-Stapel bringt einen Keycloak mit:

```bash
docker compose --profile oidc up --build
```

```env
SPRING_PROFILES_ACTIVE=docker,oidc
OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST=localhost:8180,keycloak:8180
OPAA_CSP_CONNECT_SRC_EXTRA=http://localhost:8180
```

Der erste Anbieter ist ein Schritt der [Installation](installation.md), das Anmeldeverfahren
„lokale Konten und OIDC“, im Assistenten oder in der Antwortdatei
([Installation im Compose-Stapel](docker-compose.md#installation-im-compose-stapel)). Weitere Anbieter
legt die Anbieterverwaltung an.

- **Zwei Adressen für denselben Anbieter.** Der Issuer (`http://localhost:8180/realms/opaa`) ist die
  Adresse, die der Browser verwendet, und zugleich die Authority des Anmeldeflusses; die
  JWK-Set-Adresse (`http://keycloak:8180/realms/opaa/protocol/openid-connect/certs`) nennt den Weg des
  Backends zu den Signaturschlüsseln, im Compose-Netz über den Namen `keycloak` statt `localhost`.
- **Beide Hosts sind intern** und brauchen deshalb die Allowlist der Adressprüfung (siehe
  [Adressprüfung](#adressprüfung-und-tokenprüfung)), mit Port: Ein Eintrag `localhost` allein gäbe
  jeden Port frei, auch den der Verwaltungsschnittstelle des Backends.
- **Ohne `OPAA_CSP_CONNECT_SRC_EXTRA`** blockiert der Browser den Aufruf gegen Keycloak (nächster
  Abschnitt). Mehrere Origins werden durch Leerzeichen getrennt; eine Änderung wirkt nach einem
  Neustart des Frontend-Containers.

Der mitgelieferte Keycloak hat zwei Realms: `opaa` mit dem Client `opaa-frontend`, der beim Start
übernommene Standardanbieter, und `partner` mit dem Client `opaa-partner`, ein zweiter Anbieter auf
demselben Origin, den die Systemverwaltung selbst anlegt — für Versuche mit mehreren Anbietern. Die
Admin-Konsole liegt unter `http://localhost:8180`. Testkonten stehen unter
[Testkonten im Überblick](#testkonten-im-überblick); was vor einem erreichbaren Betrieb an diesem
Keycloak zu ändern ist, unter [Härtung des mitgelieferten Keycloak](#härtung-des-mitgelieferten-keycloak).

### Content-Security-Policy und ein Anbieter auf fremdem Origin

Die Oberfläche holt das Discovery-Dokument und tauscht den Code nach der Anmeldung gegen Tokens per
`fetch` direkt aus dem Browser. Liegt einer dieser Endpunkte nicht auf dem Origin von OPAA, blockiert
die Content-Security-Policy des Frontends den Aufruf
([Sicherheits-Header](netz-und-sicherheit.md#sicherheits-header-und-strict-transport-security)), bis der Origin in
`OPAA_CSP_CONNECT_SRC_EXTRA` steht.

**Welche Origins hineingehören:** der Origin des Issuers (für `<Issuer>/.well-known/openid-configuration`)
und, wenn er abweicht, der Origin des `token_endpoint` aus diesem Dokument — gebraucht beim Einlösen
des Codes und bei jeder Erneuerung. `authorization_endpoint` und `end_session_endpoint` sind
Seitenwechsel, für die `connect-src` nicht gilt; `userinfo_endpoint`, `jwks_uri` und
`revocation_endpoint` ruft der Browser nicht auf. Beispiele aus den Discovery-Dokumenten der Anbieter,
abgerufen im Oktober 2026:

| Anbieter | Issuer | `token_endpoint` | Einzutragen | Weitere Origins im Dokument, nicht nötig |
|---|---|---|---|---|
| Keycloak mit Hostname `login.example.org` | `https://login.example.org/realms/<Realm>` | `https://login.example.org/realms/<Realm>/protocol/openid-connect/token` | `https://login.example.org` | keine |
| Microsoft Entra ID, Endpunkt v2.0 eines Mandanten | `https://login.microsoftonline.com/<Mandant>/v2.0` | `https://login.microsoftonline.com/<Mandant>/oauth2/v2.0/token` | `https://login.microsoftonline.com` | `https://graph.microsoft.com` (`userinfo_endpoint`) |
| Google | `https://accounts.google.com` | `https://oauth2.googleapis.com/token` | `https://accounts.google.com https://oauth2.googleapis.com` | `https://openidconnect.googleapis.com` (`userinfo_endpoint`), `https://www.googleapis.com` (`jwks_uri`) |

Für einen anderen Anbieter zeigt `curl -s <Issuer>/.well-known/openid-configuration` den
`token_endpoint`.

- **Fehlt der Origin des Issuers**, meldet die Anmeldeseite nach dem Klick „Die Anmeldung bei …
  konnte nicht gestartet werden", und die Anmeldung ohne Klick unterbleibt still. **Fehlt nur der
  Origin des `token_endpoint`**, landet die Person nach der Anmeldung beim Anbieter auf
  `/auth/callback` mit einer technischen Fehlermeldung (in Chromium `Failed to fetch`). In beiden
  Fällen nennt die Browser-Konsole die blockierte Adresse und die Direktive `connect-src`.
- **Nur Origins eintragen.** Ein Eintrag folgt Content Security Policy Level 3: ein Origin
  (`https://login.example.org`), ein Host ohne Schema (dann gilt das Schema der OPAA-Seite) oder ein
  Platzhalter für Subdomains (`https://*.example.org` erlaubt `login.example.org`, nicht
  `example.org`). Ohne Port gilt der Standardport. Ein Pfad schränkt die Quelle ein:
  `https://login.example.org/realms/opaa` erlaubt weder Discovery-Dokument noch `token_endpoint`.
- **Ein Anbieter nur über `http`** scheitert an einer per `https` ausgelieferten OPAA-Seite
  unabhängig von der Policy als Mixed Content; ausgenommen sind `localhost` und seine Subdomains,
  `127.0.0.0/8` und `[::1]`.

### Anbieterverwaltung

Die Anbieter liegen in der Datenbank und werden unter **Administration → Identitätsanbieter**
gepflegt (nur Systemverwaltung); jede Änderung wirkt ohne Neustart. Je Anbieter:

| Feld | Bedeutung |
|------|-----------|
| Anzeigename | Beschriftung der Schaltfläche auf der Anmeldeseite („Anmelden bei …") |
| Issuer-URI | Der `iss`-Claim seiner Tokens, Zeichen für Zeichen wie der Anbieter ihn prägt (bei Keycloak die Realm-Adresse, bei Auth0 mit abschließendem Schrägstrich); zugleich die Adresse, an der der Browser die Anmeldung startet. Eindeutig je Anbieter |
| Client-ID | Der beim Anbieter angelegte **öffentliche** Client (Authorization Code Flow mit PKCE, **kein Secret** — die Oberfläche könnte keines geheim halten) |
| JWK-Set-Adresse | Optional: die Adresse der Signaturschlüssel, wenn das Backend den Anbieter anders erreicht als der Browser (Compose: `keycloak` statt `localhost`). Ohne Angabe liest das Backend sie aus dem Discovery-Dokument. Sie ist der **Vertrauensanker** jedes Kontos dieses Anbieters |
| Claim-Zuordnung | Aus welchen Claims E-Mail und Anzeigename kommen (Vorgaben `email`/`name`, Rückfall `preferred_username`), optional Rollen- und Gruppen-Claim |
| Reihenfolge, Standard, aktiviert | Reihenfolge der Anmeldeseite; genau ein **Standardanbieter**; ein deaktivierter Anbieter wird ab dem nächsten Token abgewiesen |
| Extern | Ob der Anbieter einem anderen Haus gehört; Vorgabe ist jeder Anbieter außer dem Standardanbieter. Gruppen eines externen Anbieters sind in jeder Auswahl abgehoben ([Berechtigungen](../konten-und-rechte/berechtigungen.md#6-woher-gruppen-kommen)) |

**Beim Anbieter einzutragen** (die Oberfläche zeigt die Werte mit dem eigenen Origin): die
Weiterleitungs-URI `<Origin>/auth/callback` (für alle Anbieter dieselbe), der erlaubte Web-Origin und
die Abmelde-Weiterleitung `<Origin>`. In OPAA kommen der Origin des Anbieters nach
`OPAA_CSP_CONNECT_SRC_EXTRA` und, wenn der Anbieter in einem privaten Netz liegt, sein Host nach
`OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST`. Der **Verbindungstest** prüft Discovery-Dokument und JWK-Set
vor dem Speichern; ist die Issuer-URI vom Backend aus nicht erreichbar, prüft er das JWK-Set über die
Backend-Adresse und sagt das. Ein erreichbares Discovery-Dokument, das nicht passt (anderer Issuer,
Fehlerstatus, gesperrte Adresse), bleibt ein Fehler.

**Deaktivieren und Löschen.**

- **Deaktivieren** geht jederzeit und lässt Gruppen, Mitgliedschaften und Rechte stehen. Konten des
  Anbieters melden sich nicht mehr an, laufende Sitzungen enden mit der nächsten Anfrage; seine
  Gruppen sind kein neues Ziel einer Berechtigung mehr.
- **Der Standardanbieter** kann weder deaktiviert noch gelöscht werden, solange ein anderer Anbieter
  an seine Stelle treten kann.
- **Mindestens ein anmeldefähiger Systemverwalter bleibt.** Einen Anbieter zu deaktivieren oder zu
  löschen, lehnt OPAA ab, wenn danach keiner übrig bliebe (Hinweis: zuerst ein lokales
  Systemverwalterkonto mit Passwort einrichten). Der **letzte aktivierte** Anbieter verlangt eine
  ausdrückliche Bestätigung; danach melden sich nur noch lokale Konten an. Ein Zustand ohne Anbieter
  ist zulässig.
- **Löschen geht nur, solange keine seiner Gruppen verwendet wird** — als Rechteinhaberin an einer
  Bibliothek, Eigentümerin, Mitglied eines Space oder Trägerin eines Anlegerechts. Die Ablehnung nennt
  die Zahlen („3 Gruppen werden noch verwendet: Rechte an 7 Bibliotheken, Mitglied in 2 Spaces …")
  und führt in die **Arbeitsliste der Gruppen** des Anbieters: Sie nennt je Gruppe, wofür sie
  verwendet wird, und überträgt ihre Rechte auf eine andere Gruppe
  ([Berechtigungen](../konten-und-rechte/berechtigungen.md#132-rechte-einer-gruppe-auf-eine-andere-übertragen)), oder die
  Rechte werden dort entfernt, wo sie vergeben sind. Nicht verwendete Gruppen gehen mit dem Anbieter.
  Abgelaufene Berechtigungen zählen in der Arbeitsliste mit und halten das Löschen auf; die Vorschau
  einer Übertragung lässt sie aus und kann deshalb kleinere Zahlen nennen.
- **Die Zeile „Lokale Konten"** steht in derselben Verwaltung, erscheint aber nicht in der
  Anbieterliste: nicht löschbar, nie Standard, nur der Anzeigename änderbar. Ihr Ein- und Ausschalten
  ist der Schalter der lokalen Benutzerverwaltung (Karte „Lokale Anmeldung" unter Administration →
  Benutzer → Einstellungen).

**Konten.** Die Identität eines Kontos ist das Paar aus Issuer und Subject. Dieselbe Person bei zwei
Anbietern hat **zwei Konten**; eine Zusammenführung über die E-Mail gibt es nicht. Wird ein Anbieter
gelöscht, bleiben seine Konten und sind wieder nutzbar, sobald ein Anbieter mit derselben Issuer-URI
existiert; ihre Zugangstokens für externe KI-Werkzeuge enden dagegen endgültig. Die Issuer-URI eines Anbieters mit Konten
ist deshalb nicht änderbar. Konten eines Anbieters werden Systemverwalter nur durch Rollenvergabe oder
den Rollen-Claim.

**Rollen und Gruppen aus dem Token** (optional, je Anbieter).

- Ein **Rollen-Claim** (Pfad in Punktnotation, etwa `realm_access.roles`, mit den Werten für
  `SYSTEM_ADMIN` und `AUDITOR`) macht den Anbieter für diese Systemrollen führend: Die Rolle kommt bei
  jeder Anfrage aus dem Token, die manuelle Rollenvergabe ist für seine Konten gesperrt, und der letzte
  `SYSTEM_ADMIN` der Installation wird nie per Token entzogen. Das Setzen verlangt eine Bestätigung.
- Ein **Gruppen-Claim** macht die Gruppennamen des Tokens bei jeder Anmeldung zu Mitgliedschaften in
  Gruppen dieses Anbieters; gleichnamige Gruppen zweier Anbieter sind zwei Gruppen. Sie sind in der
  Gruppenverwaltung schreibgeschützt. Gruppen-Claim und Verzeichnisabgleich schließen einander je
  Anbieter aus.

**Ein Claim ist nur führend, wenn das Token ihn trägt.**

| Was das Token trägt | Rollen | Gruppen |
|---|---|---|
| Claim mit passenden Werten | Die Rolle wird übernommen (`SYSTEM_ADMIN` vor `AUDITOR`), bei Abweichung geschrieben und auditiert | Die Mitgliedschaften dieses Anbieters werden auf genau diese Gruppen gebracht, historisiert und auditiert |
| Claim vorhanden, aber leer oder ohne passenden Wert | Herabstufung auf `USER`, auditiert; ausgenommen der letzte anmeldefähige `SYSTEM_ADMIN` | Alle Mitgliedschaften dieses Anbieters werden entzogen, historisiert und auditiert |
| Kein Claim, ein falsch geformter Claim oder ein Overage-Hinweis | Es ändert sich **nichts**; das Backend meldet den Vorfall im Protokoll, je Anbieter und Ursache höchstens alle fünf Minuten | Es ändert sich **nichts**; Meldung wie bei den Rollen |

Die dritte Zeile ist die praktisch wichtige: Wird am Anbieter ein Mapper entfernt, umbenannt oder bei
einer Umstellung vorübergehend falsch gesetzt, verlöre sonst jedes Konto bei seiner nächsten Anmeldung
Rollen und Gruppenrechte, jede Änderung als reguläres Ereignis, das wie eine beabsichtigte aussieht.
Der **Overage-Hinweis** ist der Fall, in dem der Anbieter den Claim wegen seiner Größe durch einen
Verweis ersetzt (Entra ID ab 200 Gruppen); OPAA lädt die Gruppen nicht nach, meldet den Vorfall aber
eigens. Wer die Meldung sieht, prüft die Claim-Zuordnung und die Mapper des Anbieters; bis dahin
arbeiten die Konten mit ihrem letzten bekannten Stand. Ein durch einen benannten Rollen-Claim
entzogener letzter `AUDITOR` ist nur im Anbieter wiederherstellbar.

### Verzeichnisabgleich je Anbieter

Ein Anbieter hat **genau einen Gruppenmechanismus**: den **Gruppen-Claim** seiner Tokens (Vorgabe,
ohne Konfiguration) oder den **Verzeichnisabgleich** (empfohlen für Häuser mit gepflegtem
Verzeichnis). Beides zugleich lehnt OPAA ab, sonst entstünde dieselbe Gruppe zweimal mit zwei
Wahrheiten über die Mitgliedschaft. Was das für Rechte und Rechtehistorie bedeutet, steht im Kapitel
[Berechtigungen](../konten-und-rechte/berechtigungen.md#6-woher-gruppen-kommen).

Der Abgleich läuft für die Konten **dieses** Anbieters: Mitglieder werden nur unter seinem Issuer
aufgelöst, Gruppen eines anderen Anbieters sind für ihn unsichtbar. Ist die Anbieterzeile
deaktiviert, pausiert er. Im Betriebsmodus `dev` gibt es keinen Abgleich; wer ihn dort ausprobieren
will, legt eine gewöhnliche Anbieterzeile an und schaltet ihn dort ein.

**Die Seite.** Unter **Administration → Verzeichnisabgleich** steht je Anbieter eine Zeile:

| Spalte | Inhalt |
|---|---|
| Anbieter | Name; „Anbieter deaktiviert", wenn die Zeile abgeschaltet ist |
| Abgleich | **Eingeschaltet** mit Intervall, **Pausiert** (Anbieter deaktiviert), **Gruppen aus dem Token** oder **Aus** |
| Letzter Lauf | Ergebnis und wie lange er zurückliegt |
| Nächster Lauf | ein Intervall nach dem letzten Lauf, oder „jetzt fällig" |
| Handlungsbedarf | **Entscheidung nötig** (ein Plan wartet), **Letzter Lauf gescheitert** oder **Verzeichniszugang fehlt** |

Zeilen mit Handlungsbedarf sind farbig markiert; ab sechs Anbietern lässt sich die Übersicht
eingrenzen und durchsuchen. **Details** bzw. **Entscheiden** öffnet ein Seitenpanel mit dem
ausstehenden Plan (falls einer wartet), dem letzten Lauf samt „Trockenlauf" und „Jetzt abgleichen",
dem Zeitplan (Schalter und Intervall, voreingestellt **aus**; Vorgabe und Grenzen des Intervalls
in der [Konfiguration](konfiguration.md#grenzwerte-im-überblick)), dem
Verzeichniszugang und einem Verweis auf die Arbeitsliste der Gruppen. Ein Update schaltet nirgends
einen Abgleich ein, der vorher nicht lief.

**Zeitplan.** Ein Lauf startet, sobald der letzte mindestens ein Intervall zurückliegt; ein Anbieter,
der nie gelaufen ist, ist sofort fällig. Auch ein gescheiterter Lauf zählt als Lauf: Der nächste
wartet ebenfalls ein Intervall ab. Verpasste Zeitpunkte werden nicht nachgeholt.
`OPAA_DIRECTORY_SYNC_SCHEDULE_ENABLED=false` schaltet den Takt ganz ab; dann laufen Abgleiche nur von
Hand.

**Gescheiterter Lauf.** Bricht ein Lauf beim Lesen des Verzeichnisses oder danach mit einem Fehler
ab, der nicht „Verzeichnis nicht erreichbar" ist, etwa weil die Datenbank einen gelieferten Wert
ablehnt, schreibt er nichts; der letzte bekannte Stand
bleibt in Kraft. Das Seitenpanel zeigt **Abgleich fehlgeschlagen** mit Zeitpunkt und Meldung, die
Übersicht **Letzter Lauf gescheitert**. Die Ursache steht nur im Log des Backends;
das Nachweisprotokoll erhält wie bei einem unerreichbaren Verzeichnis einen Kopfeintrag mit dem
Ergebnis „fehlgeschlagen", ohne Ursache.

**Die vier Schutzmechaniken.**

- **Trockenlauf:** rechnet den Unterschied aus, ohne etwas zu schreiben.
- **Leeres Ergebnis** bei vorhandenen Verzeichnisgruppen oder Konten: harter Abbruch ohne
  bestätigbaren Plan — „die Quelle hat nicht geantwortet, wie sie soll" darf niemand wegklicken.
- **Unerreichbares Verzeichnis:** Der letzte bekannte Stand bleibt in Kraft. Ohne hinterlegten
  Verzeichniszugang gilt jedes Verzeichnis als unerreichbar — die sichere Richtung.
- **Plausibilitätsschwelle**: Ein Lauf, der mehr Mitgliedschaften entziehen, Gruppen auflösen oder
  Konten sperren würde, schreibt nichts und legt seinen Plan zur Bestätigung vor. Die Schwelle in
  Prozent gilt für alle Anbieter; die Systemverwaltung setzt sie unten auf der Seite
  Verzeichnisabgleich, ohne eigenen Wert gilt die Werkseinstellung. Wer sie anhebt, lässt größere
  Entzüge ohne Bestätigung durch; deshalb fragt die Seite dabei mit Warnung nach, und jede Änderung
  steht im Nachweisprotokoll. Wer vor dem Update `OPAA_DIRECTORY_SYNC_CHANGE_THRESHOLD_FRACTION`
  strenger als `0.3` gesetzt hatte, startet die neue Version zuerst mit
  `OPAA_DIRECTORY_SYNC_SCHEDULE_ENABLED=false`, trägt die Schwelle ein und schaltet den Zeittakt danach
  wieder ein: Bis dahin gälte 30 %, und eine Sperre beendet die verbundenen Konten der Person, was ein
  Entsperren nicht zurückholt.

**Der Bestätigungsweg.** Ein wartender Plan steht mit seinem **Alter** in der Übersicht. Ein neuer
Lauf ersetzt ihn, es staut sich also kein Vorrat an Plänen. **Bestätigt wird gegen einen frischen
Stand**, mit Pflichtangabe eines Anlasses: OPAA liest das Verzeichnis erneut und wendet nur an, was
dem vorgelegten Plan noch entspricht; weicht es ab, wird nichts angewendet, und der neue Plan tritt an
die Stelle des alten. **Verwerfen** entscheidet über diesen Plan, nicht über den Befund — der nächste
Lauf legt ihn gegebenenfalls erneut vor. Bestätigen und Verwerfen stehen mit Person und Anlass im
Nachweisprotokoll.

**Der Bericht nennt Namen, und das steht im Protokoll.** Trockenlauf, Lauf und Plan zeigen, wen ein
Lauf in welche Gruppe aufnimmt oder aus ihr entfernt und wen er sperrt oder entsperrt. Jeder Abruf
eines solchen Berichts mit mindestens einer Person hinterlässt einen Eintrag im Nachweisprotokoll —
mit handelnder Person, Anbieter, Weg und Zahl der genannten Gruppen und Personen, ohne die Namen. Die
Übersicht selbst ruft keinen Plan ab; erst das Öffnen eines Anbieters mit offenem Plan erzeugt einen
Eintrag, ein erneutes Öffnen bei geöffneter Seite keinen weiteren. Lässt sich der Eintrag nicht
schreiben, liefert OPAA den Bericht nicht aus — bei einem Lauf von Hand und einer Bestätigung ist die
Änderung dann trotzdem angewendet; was sie bewirkt hat, zeigen Statuszeile und Protokoll.

**Kontostatus aus dem Verzeichnis.** Derselbe Lauf übernimmt den **Kontostatus**: Ein Konto, das das
Verzeichnis als gesperrt meldet **oder gar nicht mehr meldet**, verliert beim nächsten Lauf seinen
Zugang, nicht erst mit dem Ablauf seines Tokens.

- **Gesperrt, nicht gelöscht.** Mitgliedschaften, Spaces, Rollen und Eigentum bleiben stehen. Meldet
  das Verzeichnis das Konto wieder als freigeschaltet, hebt der nächste Lauf die Sperre auf; eine
  Entsperrung von Hand gibt es nicht, weil OPAA nie ins Verzeichnis schreibt.
- **Sofort wirksam.** Jede weitere Anfrage des Kontos wird abgewiesen, auch mit gültigem Token, und
  seine Zugangstokens treten außer Kraft. Die Person sieht beim nächsten Aufruf Grund und
  Ansprechstelle.
- **Dieselbe Schwelle, derselbe Bestätigungsweg** wie bei Mitgliedschaften; eine **leere Kontenliste**
  sperrt niemanden.
- **Der letzte anmeldefähige Systemverwalter wird nie gesperrt**; der Bericht weist die
  zurückgehaltene Sperre namentlich aus.
- **Offene Eigentums- oder Zuständigkeitsfragen halten eine Sperre nie auf**
  ([Berechtigungen](../konten-und-rechte/berechtigungen.md#131-kontosperre-aus-dem-verzeichnis)).
- Jede Sperre und Entsperrung steht im Nachweisprotokoll, der Kontozustand mit Beginn und Ende in der
  Rechtehistorie.

**Wechsel des Mechanismus.** Token-Gruppen und Verzeichnisgruppen sind verschiedene Objekte. Wechselt
ein Anbieter vom Gruppen-Claim auf den Abgleich, weist der Bericht die Token-Gruppen als „werden nicht
mehr gepflegt" aus; sie bleiben mit eingefrorener Mitgliedschaft stehen. **Nichts wird still
entzogen** — ihre Rechte gehen mit einer eigenen Übertragung auf die neuen Gruppen über.

### Keycloak als Verzeichnis

Angebunden ist bisher **Keycloak** über seine Admin-API. Viele Häuser haben Keycloak ohnehin vor dem
Active Directory; seine LDAP-Mapper importieren die AD-Gruppen, und OPAA liest sie aus Keycloak.

**Was OPAA liest.** Mit einem Dienstkonto den Gruppenbaum des Realms samt direkter Mitglieder und die
Konten mit ihrem Kennzeichen `enabled`, jeweils seitenweise und ohne Attribute und Anmeldedaten:

| Keycloak | OPAA |
|---|---|
| Gruppen-ID | stabile Kennung der Gruppe — **danach wird abgeglichen** |
| Gruppenname | Anzeigename; eine Umbenennung ändert keine Berechtigung |
| Gruppenpfad (`/Haus/Referat 50`) | Herkunftspfad, nur zur Anzeige |
| übergeordnete Gruppe | übergeordnete Einheit, nur zur Gliederung — **Mitgliedschaft vererbt nicht** |
| Benutzer-ID eines Mitglieds | das Subject des Kontos unter dem Issuer **dieses** Anbieters |
| `enabled` eines Benutzers | Kontostatus; ohne das Kennzeichen oder nicht mehr gemeldet: gesperrt |

Die Keycloak-Benutzer-ID *ist* das Subject der Tokens desselben Realms; die Zuordnung braucht keine
Regel. Der Realm kommt aus der **Issuer-URI der Anbieterzeile** (`https://kc.example/realms/haus` →
Realm `haus`) und ist bewusst nicht einstellbar: So liest ein Lauf nur den Realm, aus dem die Tokens
dieses Anbieters stammen. Ist die Issuer-URI keine Keycloak-Realm-Adresse, lehnt OPAA den Zugang ab.

**Nur direkte Mitglieder.** Eine Abteilung, die nur Untergruppen hat, ist in OPAA eine **leere
Gruppe**; eine Freigabe an sie erreicht niemanden. Der Bericht nennt deshalb je Gruppe die
Mitgliederzahl. Wer eine ganze Abteilung erreichen will, nimmt ihre Mitglieder auch in die
Abteilungsgruppe auf oder gibt an die Referatsgruppen frei.

**Das Dienstkonto einrichten**, im selben Realm:

1. **Clients → Create client**: Client-ID etwa `opaa-directory`, *Client authentication* **an**,
   *Standard flow* und *Direct access grants* **aus**, **Service accounts roles** **an**.
2. **Credentials**: das Client-Geheimnis notieren.
3. **Service accounts roles → Assign role → Filter by clients → `realm-management`**: genau
   **`view-users`** und **`query-groups`**. Mehr braucht und sollte das Konto nicht haben.

In OPAA wird der Zugang im Seitenpanel des Anbieters unter **Verzeichniszugang** hinterlegt, getestet
und entfernt. Ein erfolgreicher Test antwortet etwa „Verzeichnis erreichbar: Realm „haus" mit 14
Gruppen."; ein abgewiesenes Dienstkonto nennt die beiden fehlenden Rollen. Erreicht das Backend
Keycloak unter einer anderen Adresse als der Browser — im Compose-Stapel `http://keycloak:8180` statt
`http://localhost:8180` —, gehört die Backend-Adresse in das Feld der Admin-API; ohne Angabe leitet
OPAA sie aus der Issuer-URI ab. Sie durchläuft dieselbe [Adressprüfung](#adressprüfung-und-tokenprüfung)
wie die Issuer-URI; Redirects folgt das Backend nicht.

**Das Geheimnis liegt verschlüsselt** mit `OPAA_CREDENTIALS_ENCRYPTION_KEY`; ohne diesen Schlüssel
lässt sich kein Zugang speichern ([Schlüssel und Geheimnisse](netz-und-sicherheit.md#schlüssel-und-geheimnisse)).
Angezeigt werden nur Typ, Adresse, Realm und Client-ID. Das Geheimnis des Dienstkontos lässt sich
jederzeit wechseln: in Keycloak neu erzeugen, in OPAA neu hinterlegen. Ein Test ohne eingetragenes
Geheimnis nutzt das gespeicherte nur für die gespeicherte Admin-API-Adresse; für eine geänderte
Adresse ist das Geheimnis einzutragen, sonst lehnt OPAA den Test ab, statt es dorthin zu senden.
Ist das gespeicherte Geheimnis nicht mehr entschlüsselbar, gilt es als nicht vorhanden; jeder Lauf
meldet das Verzeichnis als unerreichbar und entzieht nichts, bis der Zugang neu hinterlegt ist.

**Obergrenzen.** Mehr als `OPAA_DIRECTORY_SYNC_KEYCLOAK_MAX_GROUPS` Gruppen oder
`OPAA_DIRECTORY_SYNC_KEYCLOAK_MAX_MEMBERS_PER_GROUP` direkte Mitglieder je Gruppe lassen den Lauf als
unerreichbar abbrechen, statt eine abgeschnittene Liste anzuwenden. Die Werte sind für
Verwaltungsrealms reichlich bemessen; wer sie erreicht, hebt sie bewusst an.

### Adressprüfung und Tokenprüfung

**Adressprüfung.** Jede eingetragene Issuer- und JWK-Set-Adresse durchläuft dieselbe Prüfung gegen
private Netzbereiche wie die Quellen ([Zielprüfung](netz-und-sicherheit.md#zielprüfung-abrufe-des-servers)), mit
eigenen Schaltern `OPAA_OIDC_TARGET_VALIDATION_ENABLED` und `OPAA_OIDC_TARGET_VALIDATION_ALLOWLIST`.
Ein hausinterner Anbieter gehört in die Allowlist, auch der aus der Installation; keine Adresse ist
ohne Eintrag erlaubt. Ein Eintrag `host:port` (IPv6 als `[adresse]:port`) gibt nur diesen Port frei,
ohne Port gilt er für jeden Port des Hosts; für interne Anbieter immer den Port nennen. Das Helm-Chart
trägt den Anbieter der Antwortdatei mit Host und Port selbst ein.
Discovery-Dokument und JWK-Set holt das Backend selbst, ohne Redirects zu folgen.

**Tokenprüfung.** Ein Token wird nur angenommen, wenn sein `azp`-Claim die Client-ID der Anbieterzeile
nennt oder `aud` sie enthält; sonst antwortet das Backend mit HTTP 401 und nennt den Grund
im Header `WWW-Authenticate`. Bei einem öffentlichen Client ist das die Kontrolle dagegen, dass ein
Token für eine **andere** Anwendung desselben Anbieters hier gilt. Der Claim `azp` ist optional, und nicht jeder
Anbieter setzt ihn. Trägt ein Token kein `azp`, muss die Client-ID der Anbieterzeile in `aud`
stehen, sonst wird es abgewiesen. Für eigene Dienst-Clients heißt
das: Keycloak setzt `azp` auf den anfragenden Client, ein zweiter Client braucht einen
Audience-Mapper auf die Client-ID dieser Installation — so der Client `opaa-seed` des mitgelieferten
Realms.

### Grenzen

- **Keine Kontenzusammenführung:** Zwei Anbieter, dieselbe E-Mail — zwei Konten mit getrennten Rechten.
  Wer eine Person zu einem anderen Anbieter umzieht, vergibt ihre Rechte neu oder übergibt ein
  lokales Konto ([Benutzerverwaltung](../konten-und-rechte/benutzerverwaltung.md#9-ein-konto-an-einen-identitätsanbieter-übergeben)).
- **Kein SAML, kein reines OAuth2.**
- **Nur Keycloak als Verzeichnis.** LDAP und Microsoft Graph sind nicht angebunden.
- **Eine Organisation:** Alle Anbieter legen Konten in dieselbe Organisation an.

## Härtung des mitgelieferten Keycloak

Der Realm-Export `keycloak/realm-export.json` und der Keycloak-Dienst des Compose-Stapels sind für die
Entwicklung gebaut. Wer sie für eine erreichbare Instanz nutzt, schließt diese Punkte **vor** dem
ersten Import:

| Vorgabe | Risiko | Gegenmaßnahme |
|---|---|---|
| Realm-Benutzer `testuser`/`testpass` und fünf Demo-Nutzer mit dem gemeinsamen Passwort `RheinfurtDemo!2026`, alle nicht temporär | öffentlich bekannte Zugangsdaten zu echten Konten | **Zwingend:** aus dem Export entfernen oder beim Import per Skript mit Zufallspasswörtern versehen. Die öffentliche Demo behält für die vier Fachkonten (ohne Adminrechte) bewusst das dokumentierte Passwort; `testuser` ist dort deaktiviert, `demo-admin` hat ein rotiertes Passwort |
| `"sslRequired": "none"` | Anmeldungen und Admin-Zugriffe auch unverschlüsselt | **Zwingend:** `external` oder `all`. TLS terminiert der vorgelagerte Proxy, nicht Keycloak; dazu gehört **`KC_PROXY_HEADERS=xforwarded`**, sonst hält Keycloak die Anfrage für unverschlüsselt und antwortet „HTTPS required" — die Ursache ist dann nie `sslRequired`. Unter einem Unterpfad (etwa `/idp`) zusätzlich `KC_HTTP_RELATIVE_PATH`. Der Proxy setzt `X-Forwarded-Proto` autoritativ |
| Bootstrap-Administrator `admin`/`admin` | voller Zugriff auf den Identitätsanbieter | **Zwingend:** `KC_BOOTSTRAP_ADMIN_USERNAME`/`_PASSWORD` in der Compose-Datei durch eigene Werte ersetzen, etwa als Platzhalter aus `.env` oder der Shell-Umgebung (der Dienst liest `.env.docker` nicht), und das Passwort nach der Ersteinrichtung ändern |
| `start-dev --import-realm` ohne persistentes Datenverzeichnis | Die eingebaute Entwicklungsdatenbank geht mit jedem neuen Container verloren — und mit ihr jede Härtung: `admin`/`admin` und `testuser` sind nach dem nächsten Update wieder da | **Zwingend:** `start` statt `start-dev` (startet ohne TLS- und Proxy-Einrichtung gar nicht erst) und eine externe, persistente Datenbank (`KC_DB`, `KC_DB_URL` u. a.) |
| Client `opaa-seed` mit Passwort-Grant und ohne Secret, nur für das Befüllen der Demo | ein zweiter, passwortbasierter Tokenweg gegen jedes Konto des Realms | **Zwingend:** entfernen oder auf `enabled: false`; wer erneut befüllen will, aktiviert ihn nur für die Dauer des Laufs |
| Client `opaa-directory` mit dem Geheimnis `RheinfurtVerzeichnis!2026` und den Rollen `view-users` und `query-groups` | Wer das Geheimnis kennt, liest den ganzen Gruppenbaum, alle Mitgliedschaften und alle Konten | **Zwingend:** ein eigenes Geheimnis erzeugen (`openssl rand -base64 32`), vor dem Import setzen oder in Keycloak unter **Credentials** neu erzeugen, und denselben Wert im [Verzeichniszugang](#keycloak-als-verzeichnis) hinterlegen. Ohne Verzeichnisabgleich den Client entfernen |

Dazu bleibt die Port-Bindung des Dienstes `keycloak` an `127.0.0.1` (Vorgabe), sonst ist die
Admin-Konsole am Proxy vorbei erreichbar ([Docker Compose](docker-compose.md#härtung-des-compose-stapels)).

**Änderungen am Realm einer laufenden Instanz.** `--import-realm` importiert einen Realm nur, wenn er
noch nicht existiert; eine spätere Änderung am Export wirkt auf einer Instanz mit persistentem
Keycloak also nicht von selbst, und ein erneuter Import drehte die Härtung der Konten zurück.
Änderungen werden gezielt mit `kcadm` oder der Admin-Konsole nachgezogen, etwa die Lebensdauern, die
der Export setzt:

```bash
kcadm.sh config credentials --server http://localhost:8180 --realm master --user admin
kcadm.sh update realms/opaa -s accessTokenLifespan=900 -s ssoSessionIdleTimeout=3600 -s ssoSessionMaxLifespan=36000
```

oder der Audience-Mapper des Clients `opaa-seed` auf die Client-ID der Anbieterzeile (ohne ihn weist
das Backend jeden Aufruf eines Befüllungslaufs mit 401 ab, siehe [Tokenprüfung](#adressprüfung-und-tokenprüfung);
lautet die Client-ID nicht `opaa-frontend`, gehört ihr Wert in `included.client.audience`):

```bash
kcadm.sh create clients/<id-des-clients-opaa-seed>/protocol-mappers/models -r opaa \
  -s name=opaa-frontend-audience -s protocol=openid-connect \
  -s protocolMapper=oidc-audience-mapper \
  -s 'config."included.client.audience"=opaa-frontend' \
  -s 'config."access.token.claim"=true'
```

## Erneute Anmeldung vor sensiblen Vorgängen

OPAA kann vor einem Vorgang verlangen, dass die Person ihre Identität gerade eben bestätigt hat, damit
ein nicht gesperrter Arbeitsplatz dafür nicht genügt. Die Bestätigung gilt für kurze Zeit
(`OPAA_AUTH_RECENT_LOGIN_MAX_AGE` in der [Variablenliste](konfiguration.md)), danach wird wieder
gefragt. Verlangt wird sie vor dem Download der eigenen Daten
([Selbstauskunft](../konten-und-rechte/benutzerverwaltung.md#meine-daten-die-selbstauskunft)) und vor
dem Erzeugen eines Zugangstokens für [externe KI-Werkzeuge](../spaces/fremdzugaenge.md).

- **Lokales Konto:** Die Bestätigung erfolgt mit dem Passwort. Ein falsches Passwort zählt wie bei
  der Anmeldung als Fehlversuch und führt nach zu vielen Versuchen zur Sperre.
- **Konto eines Identitätsanbieters:** Die Bestätigung ist eine Anmeldung beim Anmeldedienst, die
  OPAA auch bei bestehender Sitzung verlangt. Der Anbieter muss dafür die Anfrageparameter
  `prompt=login` und `max_age=0` beachten und den Anmeldezeitpunkt (`auth_time`) ins Zugangstoken
  schreiben; Keycloak tut beides mit dem mitgelieferten Client-Scope `basic`. Sonst lässt OPAA den
  Vorgang nicht zu.
- **Entwicklungsmodus:** Es gibt keine Anmeldung; jede Anfrage gilt als bestätigt.

Die bloße Verlängerung einer Sitzung im Hintergrund zählt nicht als erneute Anmeldung.

## Erststart und Systemverwalter-Konto

**Der erste Systemverwalter ist ein lokales Konto**, das Notanker-Konto, unabhängig davon, ob ein
Identitätsanbieter konfiguriert ist. Das ist der Weg hinein, bevor etwas eingerichtet ist, und der
Weg zurück, wenn ein Anbieter falsch konfiguriert wurde. Es ist ein Schritt der
[Installation](installation.md), im Assistenten oder in der Antwortdatei, mit dem dort gewählten
Passwort. Aus dem Assistenten gilt es ohne erzwungenen Wechsel. Eine Antwortdatei nennt das Passwort
nur als Pfad einer Datei, und die erste Anmeldung verlangt ein neues, solange die Datei nicht
`requirePasswordChange: false` sagt; so gilt das Passwort, das in einem Secret oder einer Datei
liegen bleibt, danach nicht mehr.
Die Adresse muss ein zustellbares Postfach sein, am besten ein Funktionspostfach der IT;
`admin@opaa.local` lehnt die Installation ab, weil unter ihr kein Rücksetzlink ein Postfach erreicht.

**Das Notanker-Konto ist ein Notfallzugangsmittel, kein Arbeitskonto.**

1. **Einmal anmelden**, auf der regulären Anmeldeseite, und prüfen, dass das Konto funktioniert.
2. **Persönliche Verwalterkonten anlegen**, je Person eines
   ([Benutzerverwaltung](../konten-und-rechte/benutzerverwaltung.md)); die tägliche Arbeit läuft über diese Konten.
3. **Das Passwort versiegelt hinterlegen**, dort, wo das Haus andere Notfallzugänge verwahrt.

Das Konto bleibt anmeldefähig und ist **gegenüber Verwaltungsakten unverletzlich**: Löschen, Sperren,
Befristen, Herabsetzen der Rolle, die Übergabe an einen Identitätsanbieter sowie ein von der
Verwaltung gesetztes Passwort oder ein Rücksetzlink werden immer abgelehnt — ein Ablaufdatum würde den
Notweg sonst still schließen, ein ersetztes Passwort das versiegelte wertlos machen. Sein Passwort
ändert das Konto nach der Anmeldung selbst; ohne Anmeldung setzen es die Notfallprozedur oder,
solange „Passwort vergessen" verfügbar ist, ein Rücksetzlink an seine eigene Adresse neu. Von
der Sperre nach Inaktivität ist es ausgenommen, die Sperre nach Fehlversuchen gilt auch für es.
**Jede Anmeldung mit ihm steht im Nachweisprotokoll** und löst eine Nachricht an alle übrigen
Systemverwalter aus. Erkannt wird es an einer internen Markierung, nicht an der Adresse; Adresse und
Anzeigename dürfen sich ändern.

Das erste Konto der Installation macht **kein Konto eines Identitätsanbieters** zum
Systemverwalter, auch wenn die Adresse übereinstimmt; es bezeichnet nur das Notanker-Konto. Im Modus
`dev` ist `dev-admin` Systemverwalter.

## Lokale Benutzerverwaltung

Lokale Konten sind Konten, die OPAA selbst führt, mit E-Mail-Adresse und Passwort. Ihre Verwaltung ist
**ein Schalter unter Administration → Benutzer → Einstellungen**. Der Abschluss der
[Installation](installation.md) schaltet ihn **ein**, mit „nur lokale Konten" wie mit einem
Identitätsanbieter; das Revisionsprotokoll hält es unter der Installation fest.
Was sie im Alltag leistet — anlegen, einladen, sperren, zurücksetzen, befristen, Selbstregistrierung —,
steht im Kapitel [Benutzerverwaltung](../konten-und-rechte/benutzerverwaltung.md). Für den Betrieb zählt:

- **Abgeschaltet heißt: nur Systemverwalter.** Reguläre lokale Konten melden sich nicht an, laufende
  Sitzungen enden beim nächsten Aufruf mit „Die Anmeldung mit Konten dieser Installation wurde
  abgeschaltet". Lokale Systemverwalter melden sich weiter über `/login/system` an.
- **Empfehlung: im Regelbetrieb aus.** Wer einen Identitätsanbieter betreibt, schaltet reguläre
  lokale Konten nach der Einrichtung ab und befristet die Systemverwalterkonten. Lokale Konten laufen am Verzeichnis und damit am
  Austrittsprozess vorbei; Pflicht-Anlass, Ablaufdatum, Sperre nach Inaktivität, Erinnerungen und die
  vierteljährliche Wiedervorlage machen das beherrschbar, ersetzen den Prozess aber nicht.
- **Vorher organisatorisch klären:** Drei Punkte gehören in eine Dienstvereinbarung oder vergleichbare
  Regelung, **bevor** der Schalter umgelegt wird — der Schalter selbst und der der
  Selbstregistrierung (beide erzeugen Konten außerhalb des Verzeichnisses), der Auszug für die
  Personalvertretung mit der geschlossenen Liste der Nachweisereignisse und eine jährliche Vorlage
  dieses Auszugs zusätzlich zur vierteljährlichen Kontenprüfung
  ([Benutzerverwaltung](../konten-und-rechte/benutzerverwaltung.md#6-die-liste-und-die-auflagenprüfung)).
- **Einladungen und Rücksetzlinks brauchen Mail und eine öffentliche Adresse**
  (`OPAA_PUBLIC_BASE_URL`, [E-Mail-Versand](e-mail.md)). Ohne beides zeigt OPAA den Link zur Übergabe
  an, und „Passwort vergessen" und Selbstregistrierung lassen sich nicht einschalten.
- **Es gibt keinen zweiten Faktor.** Bis dahin gleichen aus: die Netzbeschränkung
  `OPAA_LOCAL_ADMIN_ALLOWED_CIDRS` (lokale Systemverwalter nur aus den genannten Netzen; leer heißt
  ohne Beschränkung), kürzere Sitzungsfristen für Systemverwalterkonten, die Protokollierung des
  Notanker-Kontos und die Empfehlung oben.

**Anmeldegrenzen.** Die lokale Anmeldung ist je Client-Adresse, je Konto und insgesamt begrenzt
(`OPAA_RATE_LIMIT_LOCAL_AUTH_*`); eine überschrittene Grenze antwortet `429` mit `Retry-After`. Die
Gesamtgrenze für Anmeldung, Registrierung und „Passwort vergessen" ist das Frühwarnsignal gegen
verteiltes Ausprobieren gestohlener Zugangsdaten. Sie zählt auch Fehlversuche und gilt für alle Adressen
zugleich: Ist sie erreicht, antwortet der Endpunkt jeder Adresse mit `429`, und das Überschreiten steht
als Warnung im Protokoll und in der Metrik `opaa_rate_limit_rejected_total` ([Logs und Metriken](ueberwachung-und-fehlerbehebung.md#logs-und-metriken)).
Nach `OPAA_AUTH_LOCAL_LOCKOUT_MAX_ATTEMPTS` falschen Passwörtern ist ein Konto für
`OPAA_AUTH_LOCAL_LOCKOUT_DURATION` gesperrt; die Anmeldung antwortet dann wie bei einem falschen
Passwort. Gleichzeitige Versuche gegen dasselbe Konto entscheidet OPAA nacheinander, auch über
mehrere Instanzen: Viele parallele Anmeldungen zählen nicht über die Grenze hinaus, und ein
richtiges Passwort, dessen Prüfung erst nach Eintreten der Sperre endet, wird wie ein falsches
beantwortet. Die Sperre steht im Nachweisprotokoll und in einer eigenen Metrik, der einzelne
Fehlversuch nur im Protokoll (mit Konto-Kennung, nie mit Adresse). Wie lange der Log-Sammler dieses Protokoll
aufbewahrt, legt der Betrieb fest; eine kurze Frist, etwa 90 Tage, genügt der Fehlersuche und hält
das Protokoll der Fehlversuche aus der Leistungskontrolle heraus. Die Zähler je Adresse sind auf eine hohe Zahl
gleichzeitig geführter Adressen begrenzt; darüber werden die am wenigsten genutzten vergessen und
beginnen neu — diesen Fall fängt die Gesamtgrenze auf. Damit die Grenzen je Person wirken, muss die
Client-Adresse stimmen ([Vertraute Proxys](netz-und-sicherheit.md#vertraute-proxys-und-client-adresse)).

**Wiederverwendung eines Refresh-Tokens.** Jede Erneuerung einer Sitzung ersetzt das Refresh-Token.
Taucht ein bereits ersetztes Token wieder auf, gilt das als Hinweis auf ein abgegriffenes Cookie:
**Alle** Sitzungen des Kontos enden, das Nachweisprotokoll hält es fest, und das Log warnt mit der
Konto-Kennung. Ausgenommen ist das Neuladen einer Seite mitten in der Erneuerung, bei dem der Browser
das neue Cookie verwirft: Legt die nächste Seite das alte Token innerhalb von
`OPAA_AUTH_LOCAL_REFRESH_GRACE_PERIOD` nach der Erneuerung vor und wurde dessen Nachfolger noch nie
benutzt, wird die Sitzung einfach noch einmal erneuert. Wer ein Token in diesen Sekunden abgreift,
kann die Sitzung übernehmen; spätestens die nächste Erneuerung durch die Person selbst gilt dann als
Wiederverwendung und beendet alle Sitzungen.

**Nur über TLS.**

- **Das Refresh-Cookie** trägt das Attribut `Secure`, solange `OPAA_AUTH_LOCAL_COOKIE_SECURE` nicht auf
  `false` steht. `false` ist nur für lokales HTTP gedacht (die Beispiel- und Testdateien zeigen es);
  auf einer erreichbaren Instanz reiste das Cookie sonst unverschlüsselt, und wer mitliest, hielte
  eine Sitzung, die sich selbst erneuert. Der Modus `oidc` warnt bei `false` bei jedem Start — die
  Zeile ist der Prüfpunkt nach dem Übernehmen einer Beispielkonfiguration.
- **Die Erneuerung einer Sitzung zwischen mehreren Tabs** stimmt die Oberfläche über eine
  Browser-Schnittstelle ab, die es nur in einem sicheren Kontext gibt, also unter `https://` oder auf
  `localhost`. Unter `http://` mit Hostname oder IP fällt die Abstimmung still weg; zwei Tabs legen
  dasselbe Token vor, das zweite gilt als Wiederverwendung, und **alle** Sitzungen des Kontos werden
  widerrufen.

## Notfallprozedur: wieder hereinkommen

Für den Fall, dass sich **niemand** mehr anmelden kann — der letzte Anbieter ist falsch konfiguriert,
das Notanker-Konto gesperrt, abgelaufen, sein Passwort verloren oder das Konto nicht vorhanden:

```env
OPAA_LOCAL_ADMIN_RESET=force
```

Setzen, Backend neu starten, **danach wieder entfernen**. Der Wert wirkt einmal je Setzen: Genau ein
Start setzt zurück, auch wenn mehrere Instanzen gleichzeitig oder nacheinander mit ihm starten; jeder
weitere Start mit gesetztem Wert lässt das Konto unverändert und vermerkt das im Protokoll. Erst ein
Start ohne den Wert gibt einen neuen Reset frei. Der Start stellt einen anmeldefähigen lokalen
Systemverwalter her:
Das Notanker-Konto wird entsperrt, ein Ablaufdatum entfernt, die Rolle wiederhergestellt, ein neues
Einmalpasswort gesetzt und alle seine Sitzungen beendet. Das Einmalpasswort steht **einmalig** als
markierter Block im Protokoll, der Wechsel wird bei der ersten Anmeldung erzwungen:

```text
=====================================================================
  NOTANKER-KONTO DER SYSTEMVERWALTUNG - EINMALIGE AUSGABE
  Anmeldung:  mit der Adresse des Notanker-Kontos
  Passwort:   <hier steht das erzeugte Passwort>
  Der Wechsel des Passworts wird bei der ersten Anmeldung erzwungen.
  Diese Ausgabe erscheint einmalig und wird nicht wiederholt.
=====================================================================
```

Zu lesen mit `docker compose logs backend | grep -A5 NOTANKER` bzw. `kubectl logs`. **Eine
Log-Weiterleitung sieht diesen Block** — bewusst in Kauf genommen: Das Passwort gilt nur bis zur
ersten Anmeldung. Der Vorgang steht laut im Protokoll und im Nachweisprotokoll.

**Kein Notanker-Konto vorhanden**, etwa nach einem Eingriff in die Datenbank: Dann legt derselbe
Start es an, sobald zusätzlich die Adresse gesetzt ist:

```env
OPAA_LOCAL_ADMIN_RESET=force
OPAA_LOCAL_ADMIN_RESET_EMAIL=it-betrieb@stadt.example
```

Die Adresse folgt denselben Regeln wie im Assistenten (zustellbares Postfach, nicht
`admin@opaa.local`) und darf noch keinem lokalen Konto gehören; das Passwort steht wie oben
einmalig im Protokoll. Fehlt die Adresse oder ist sie nicht verwendbar, legt der Start nichts an und
nennt den Grund. Ein zweites Notanker-Konto entsteht nie. Beide Variablen danach wieder entfernen.
Eine eingerichtete Installation ohne Notanker-Konto meldet das bei jedem Start als Warnung.

Danach läuft die Reparatur über die Oberfläche: als lokaler Systemverwalter anmelden und in der
Anbieterverwaltung den falschen Anbieter korrigieren oder einen neuen anlegen. Ein Eingriff in die
Datenbank ist nicht nötig.

## Nacharbeit nach einer Rücksicherung der Datenbank

Eine zurückgesicherte Datenbank bringt Zustände zurück: Widerrufene Sitzungen gelten wieder,
verbrauchte Einladungs- und Rücksetzlinks sind wieder einlösbar, ein nach einem Verdacht geänderter
Passworthash ist auf dem alten Stand. Nach **jeder** Rücksicherung gehören deshalb zwei Schritte dazu:

1. **`OPAA_AUTH_JWT_SECRET` ersetzen**, bevor das Backend startet. Ein neuer Wert beendet alle lokalen
   Sitzungen und entwertet alle offenen Einladungs-, Rücksetz- und Übergabelinks und alle
   Zugangstokens für externe KI-Werkzeuge in einem Schritt.
2. **Sperren und Rücksetzungen seit dem Sicherungszeitpunkt erneut vornehmen.** Das Nachweisprotokoll
   der Zwischenzeit ist die Liste, sofern es nicht selbst mit zurückgesichert wurde.

Die beiden Verschlüsselungsschlüssel bleiben dieselben, sonst sind die gespeicherten Geheimnisse
unlesbar. Ist der Zugriff externer KI-Werkzeuge eingeschaltet, rollen Schalter, Freigaben und Zugangstokens mit zurück,
einschließlich eines Notaus; die Prüfliste steht im Kapitel [Externe KI-Werkzeuge](../spaces/fremdzugaenge.md).

## Testkonten im Überblick

Im Repository stehen mehrere Arten von Testkonten nebeneinander. Sie sind **bewusst nicht
vereinheitlicht**: Jede gehört zu einem anderen Modus oder Werkzeug und folgt dessen Mechanismus.

| Muster | Geltungsbereich | Nutzer |
|--------|------------------|--------|
| **Notanker-Konto** (erstes Konto der Installation) | jede Installation im Modus `oidc` — **kein Testkonto, sondern ein echtes Konto**; es steht hier, weil Test- und Demo-Stapel es mit festem Passwort bestücken | Anzeigename „Systemverwaltung", Rolle `SYSTEM_ADMIN`. Die im Repository stehenden Passwörter unter `e2e/installation/`, etwa `RheinfurtNotanker!2026` (Demo-Smoke) und `E2eNotankerKonto!2026` (Ziel `local-auth`), gehören zu Stapeln, die ein Befehl erzeugt und wieder wegwirft — nie Vorgaben für eine Installation |
| Entwicklungsnutzer (`opaa.auth.dev.users`) | Modus `dev` | `dev-admin` (`admin@opaa.local`, `SYSTEM_ADMIN`), `dev-user` (regulärer Nutzer); kein Passwort, weil keines geprüft wird |
| Keycloak-Realm-Nutzer (`keycloak/realm-export.json`) | Modus `oidc` mit dem mitgelieferten Keycloak | `testuser`/`testpass` (`test@opaa.local`), ein regulärer Nutzer; Systemverwalter wird er nur durch Rollenvergabe oder den Rollen-Claim |
| Demo-Realm-Nutzer (`keycloak/realm-export.json`) | Demo-Instanz „Stadt Rheinfurt" (Profil `demo`, befüllt durch `demo/seed/seed.py`) | `demo-admin` (`admin@stadt-rheinfurt.example`; die Rolle vergibt der Befüllungslauf), `maria.weber`, `selin.kaya`, `thomas.klein`, `andrea.vogt`, alle mit `RheinfurtDemo!2026`; dazu der Client `opaa-seed` nur für das Befüllen |
| Realm des Kubernetes-Erprobungsaufbaus (`examples/kubernetes-trial/keycloak/realm-opaa.json`) | Keycloak im [Erprobungsaufbau](kubernetes.md#erprobung-mit-dem-beispiel-setup), Client `opaa` | `testuser`/`testpass` und `maria.weber` mit `RheinfurtDemo!2026`, beide regulär; das Notanker-Konto bekommt ein von `trial.sh` erzeugtes Passwort im Secret des Aufbaus |
| Partner-Realm-Nutzer (`keycloak/realm-partner-export.json`) | zweiter Anbieter (Realm `partner`, Client `opaa-partner`), über die Anbieterverwaltung anzulegen | `maria.weber` mit **derselben E-Mail** wie im Realm `opaa` und `RheinfurtDemo!2026` — ein zweites Konto ohne die Rechte der Demo-Maria, der Nachweis, dass Konten zweier Anbieter nie zusammengeführt werden |
| E2E-Suite (`e2e/e2e.env`, `e2e/docker-compose.e2e.yml`) | Playwright-Suite, Ziel `e2e`, im Modus `dev` | `dev-admin` und `dev-user`, ergänzt um `dev-outsider` und `dev-format-pipelines` (über `OPAA_AUTH_DEV_USERS_*`) |
| E2E-Ziel `local-auth` (`e2e/local-auth.env`, `e2e/installation/local-auth.json`) | Modus `oidc` **ohne** Keycloak — der Lauf, der eine echte lokale Anmeldung übt | nur das Notanker-Konto der Antwortdatei mit festem Passwort; weitere Konten legen die Szenarien selbst an |
| E2E-Ziel `installation` (`e2e/installation.env`) | Modus `oidc` im Installationsmodus — der Lauf des Einrichtungs-Assistenten | kein Konto vor dem Lauf; das Szenario legt das erste Konto im Assistenten an |

Die E2E-Suite läuft im Modus `dev`, weil dort dieselben Autorisierungsregeln gelten wie mit
Anmeldung, ohne Keycloak im Prüfpfad; die Anmeldevorgänge selbst prüfen der Demo-Smoke-Lauf (Keycloak)
und das Ziel `local-auth` (lokale Anmeldung). Dass das Notanker-Konto in zwei Stapeln ein festes,
öffentlich bekanntes Passwort trägt, ist genau der Grund, warum keines dieser Passwörter auf einer
erreichbaren Instanz verwendet wird. Zugangsdaten, mit denen eine Bibliothek ihre Quelle abruft,
sind kein Konto bei OPAA ([Schlüssel und Geheimnisse](netz-und-sicherheit.md#zugangsdaten-verschlüsselung)).

## Fehlerbehebung

### OIDC: „Completing sign in..." hängt

- Läuft Keycloak (`docker compose --profile oidc ps`)?
- Nach einem Neustart von Keycloak ist das Token womöglich abgelaufen — Seite neu laden und erneut
  anmelden.
- Verwendet die JWK-Set-Adresse im Compose-Stapel `keycloak:8180`, nicht `localhost:8180`?

### Die Anmeldeseite bleibt stehen, obwohl eine Anbieter-Sitzung besteht

Die Anmeldung ohne Klick wurde abgelehnt; den Fehlercode nennt die Browser-Konsole
([Anmeldewege](#anmeldewege-im-betriebsmodus-oidc)). Meldet die Anmeldeseite nach dem Klick, dass die
Anmeldung nicht gestartet werden konnte, fehlt meist ein Origin in `OPAA_CSP_CONNECT_SRC_EXTRA`
([Content-Security-Policy](#content-security-policy-und-ein-anbieter-auf-fremdem-origin)).

### Das Backend startet nicht und nennt `OPAA_AUTH_JWT_SECRET`

Im Modus `oidc` ist das Geheimnis Pflicht, mindestens 32 Zeichen lang und kein erkannter Platzhalter;
auch die Testwerte des Repositorys (`ci-only-…`, Kennzeichen `NICHT-FUER-DEN-PRODUKTIVBETRIEB`) lehnt
der Start ab. Mit `openssl rand -base64 48` erzeugen und setzen. Dieselbe Prüfung gilt für die Sitzungsfristen: Eine
überschrittene Obergrenze oder eine verletzte Reihenfolge (Zugangstoken ≤ Leerlauffrist ≤ Höchstdauer)
nennt die Meldung mit Variablennamen.

### Kein Einmalpasswort im Log zu finden

In dieser Reihenfolge prüfen:

- **Die Installation lief über Assistent oder Antwortdatei.** Dann gibt es kein Einmalpasswort;
  das Passwort ist das dort gewählte. Ein Einmalpasswort erzeugt nur die
  [Notfallprozedur](#notfallprozedur-wieder-hereinkommen).
- **Die Notfallprozedur fand kein Notanker-Konto und keine Adresse in
  `OPAA_LOCAL_ADMIN_RESET_EMAIL`**, oder sie hatte seit dem Setzen von `OPAA_LOCAL_ADMIN_RESET` schon
  zurückgesetzt; beides steht im Protokoll.
- **Unter Kubernetes** ist der Pod seither mehrmals neu gestartet und das Protokoll weg.

### Die Anmeldung antwortet `429` mit `Retry-After`

Die Grenze der Anmeldung ist erreicht. Steht ein Proxy davor und fehlt
`OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS`, **teilen alle Clients einen Zähler**, und die Grenze trifft das
ganze Haus. Die aufgelöste Client-Adresse zeigt der Diagnose-Endpunkt
([Diagnose ohne Shell](ueberwachung-und-fehlerbehebung.md#diagnose-ohne-shell)).

### Eine Anmeldung wird abgelehnt, ohne einen Grund zu nennen

Beabsichtigt: Unbekannte Adresse, falsches Passwort, gesperrtes, abgelaufenes und noch nicht
bestätigtes Konto beantwortet OPAA **gleich**. Den Zustand eines Kontos zeigt die Kontenliste
([Benutzerverwaltung](../konten-und-rechte/benutzerverwaltung.md#6-die-liste-und-die-auflagenprüfung)). Wird eine Person
nach der Anmeldung sofort auf „Passwort ändern" geführt, ist ein Wechsel erzwungen; die Seite nennt den
Anlass.

### Eine Sitzung bricht beim Arbeiten in mehreren Tabs wiederholt ab

Die Anwendung wird über `http://` ausgeliefert; die Abstimmung zwischen Tabs braucht TLS
([Nur über TLS](#lokale-benutzerverwaltung)).

### Der Verzeichnisabgleich meldet „Abgleich fehlgeschlagen"

Der Lauf ist mit einem Fehler abgebrochen und hat nichts geändert. Die Ursache nennt das
Log des Backends zum Zeitpunkt der Statuszeile, häufig ein Wert aus dem Verzeichnis,
der zu lang ist, etwa ein sehr tiefer Gruppenpfad. Nach der Korrektur im Verzeichnis **Jetzt
abgleichen** wählen; der Zeitplan versucht es sonst erst nach einem Intervall erneut
([Gescheiterter Lauf](#verzeichnisabgleich-je-anbieter)). Auch ein **Trockenlauf** zählt als Lauf:
Er verschiebt den nächsten geplanten Lauf um ein Intervall, ohne etwas anzuwenden.

### Jeder Aufruf eines eigenen Dienst-Clients endet mit 401

Das Token nennt die Client-ID der Anbieterzeile weder in `azp` noch in `aud`; der Client braucht einen
Audience-Mapper ([Tokenprüfung](#adressprüfung-und-tokenprüfung)).

### Jede Anmeldung bei einem Anbieter endet mit 401

Der Anbieter setzt kein `azp` und führt die Client-ID der Anbieterzeile nicht in `aud`; das Backend
weist deshalb jedes seiner Token ab ([Tokenprüfung](#adressprüfung-und-tokenprüfung)). Abhilfe: beim
Anbieter einen Audience-Mapper (oder die gleichwertige Einstellung) am Client einrichten, der die
Client-ID der Anbieterzeile in `aud` einträgt. Ein zusätzlicher Scope hilft nur, wenn der Anbieter
ihn dem Client als Standard-Scope zuordnet: Die Anmeldung fordert fest `openid profile email` an.

## Weiterführende Kapitel

- [Modelle](modelle.md) und [Branding](branding.md) — die Nachbarkapitel im Betriebsteil
