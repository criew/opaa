# Konnektor-Freigabe, Verbindungsprofile und verbundene Konten

> **Status: Entwurf.** Grundlage sind die Beschlüsse des Maintainers vom 03.10.2026 in Epic
> [#2147](https://github.com/criew/opaa/issues/2147), einschließlich der Folgebeschlüsse nach der
> Stakeholder-Runde. Die nötigen ADR-Nachträge sind am Ende benannt.

## Motivation

Heute richtet eine einzelne Person eine Quelle vollständig ein: Adresse, Zugangsdaten und
Einstellungen stehen in der Bibliothek. Das reicht für eine Dateiablage mit Dienstkonto, aber nicht
für drei Fälle:

1. **Quellen, die nur OAuth kennen.** Dropbox gibt weder Dienstkonto noch statischen Schlüssel aus.
   Der Zugang entsteht über eine Zustimmung beim Anbieter; danach erneuert OPAA ihn selbst.
2. **Persönliche Konten.** Die eigene Nextcloud-Ablage oder das eigene Postfach sind nur mit den
   Rechten der jeweiligen Person erreichbar. Ein Dienstkonto, das alle Postfächer liest, ist genau
   der Fehler, vor dem [knowledge-sources.md](./knowledge-sources.md) warnt.
3. **Steuerung durch das Haus.** Die Systemverwaltung will festlegen, welche Konnektoren und welche
   Server es in der Installation gibt und wer sie nutzt, statt dass jede Person eine beliebige
   Adresse einträgt.

Heute gibt es dafür nichts: Eine Fähigkeit (`CREATE_CONNECTOR_LIBRARY`) deckt alle Konnektoren ab,
jede Bibliothek trägt ihre Zieladresse frei, und OPAA speichert keine OAuth-Token.

---

## Überblick

1. **Drei Stufen.** Die Systemverwaltung *gibt frei* und *legt den Rahmen fest* (Verbindungsprofil).
   Eine Bibliothek oder eine Person *besitzt* die Verbindung. Die Verbindung wird *genutzt*: zuerst
   für die Indexierung, später live im Chat.
2. **Freigabe je Profil.** Wer ein Verbindungsprofil nutzen darf, steht am Profil (aus, alle,
   Gruppen). Konnektoren ohne Profil werden je Typ freigegeben. Neues ist ab Werk aus.
3. **Freigabe regelt die Neuanlage.** Bestehende Bibliotheken laufen weiter; nur eine **Sperre** von
   Konnektor oder Profil stoppt Läufe.
4. **Profilpflicht** je Konnektor: nur über ein Profil nutzbar, ohne frei eingetragene Adresse.
5. **Drei Anmeldearten:** persönliches Geheimnis (App-Passwort, Token), OAuth (Autorisierungscode
   mit PKCE) und Client-Credentials. Token-Austausch über den Identitätsanbieter ist nur ein Spike.
6. **Verbundene Konten** einer Person speisen eine **private Bibliothek**: genau eine Besitzerin,
   nicht teilbar, keine Nachfolge, für die Verwaltung nur zusammengefasst sichtbar.
7. **Verbindliche Schutzregeln:** „Sicht als“ ist ausgeschlossen, ein Vorfallszugriff braucht vier
   Augen, Benachrichtigung und Personalrat; das Verbinden ist freiwillig; die Löschung ist
   vollständig und nachweisbar.
8. **MCP-Grundlage:** Profiltyp „MCP-Server“ und Token-Speicher nach MCP-Autorisierung. Den
   MCP-Client baut ein Folge-Epic hinter [#1747](https://github.com/criew/opaa/issues/1747).

---

## Begriffe

| Begriff (Spezifikation) | In der Oberfläche | Bedeutung |
|---|---|---|
| **Konnektor** | Quellart | Eine mit OPAA ausgelieferte Quellart, etwa Confluence, Nextcloud, Dropbox, Exchange |
| **Verbindungsprofil** | **Zugang** mit Namen, z. B. „Zugang Nextcloud intern“ | Von der Systemverwaltung vorgegebener Rahmen: Server, Anmeldeart, App-Registrierung, Scopes |
| **Konnektor-Freigabe** | „Wer darf diesen Zugang nutzen?“ | Wer mit einem Profil (oder einem Konnektor ohne Profil) Bibliotheken anlegen und Konten verbinden darf |
| **Sperre** | „Gesperrt“ | Stoppt Neuanlage und Läufe eines Konnektors oder Profils |
| **Profilpflicht** | „Nur über Zugänge“ | Konnektor ist nur mit Profil nutzbar |
| **Quellfreigabe** | **Quelle verbinden** | Einmalige OAuth-Zustimmung für eine Bibliothek mit einem Dienstkonto; der Token gehört der Bibliothek |
| **Verbundenes Konto** | Verbundenes Konto | Eigener Zugang einer Person auf ein Profil; gehört der Person |
| **Private Bibliothek** | Private Bibliothek | Bibliothek, die über ein verbundenes Konto gespeist wird |

Das Wort „Freigabe“ steht in der Oberfläche schon für das Teilen eines Assets. Deshalb heißt die
Quellfreigabe dort „Quelle verbinden“, und die Konnektor-Freigabe wird als Frage formuliert.
„Eigene Wissensinstanz“ gibt es nicht: Persönliche Spaces entfallen
([#2099](https://github.com/criew/opaa/issues/2099)), an ihre Stelle tritt die private Bibliothek.

**Abgrenzung:**

- **Fremdzugang** ([external-access.md](./external-access.md)) ist die Gegenrichtung: OPAA als
  MCP-*Server* für fremde KI-Werkzeuge.
- **Anmeldung an OPAA** ([ADR-0025](../decisions/0025-mehrere-oidc-anbieter.md)): Die Token der
  OIDC-Anbieter dienen nur der Identität und werden hier nicht verwendet, außer im Spike zum
  Token-Austausch.

---

## Die drei Stufen

```
Stufe 1 — Systemverwaltung
  Konnektor Nextcloud ── Zugang „Nextcloud intern“  (App-Passwort; frei für: Alle Konten)
                     └─ Zugang „Nextcloud Partner“  (App-Passwort; frei für: Gruppe Projekt X)

Stufe 2 — Besitz der Verbindung
  Quelle verbinden (Bibliothek)      oder   Verbundenes Konto (Person)
  z. B. Dropbox „Bauamt“                    z. B. Andrea Vogt → „Nextcloud intern“

Stufe 3 — Nutzung
  Indexierung in eine Bibliothek            (dieses Epic)
  Live-Abfrage im Chat / MCP                (später, #1747 und Folge-Epic)
```

Konnektoren ohne Profil (siehe [Profilpflicht](#profilpflicht)) gehen wie heute direkt von der
Freigabe zur Bibliothek.

---

## Konnektor-Freigabe und Sperre

**Woran die Freigabe hängt:**

| Nutzung | Freigabe steht an |
|---|---|
| Bibliothek oder verbundenes Konto über ein Profil | dem **Profil** |
| Bibliothek mit frei eingetragener Adresse (Konnektor ohne Profil oder Profil optional) | dem **Konnektortyp** |

So öffnet die Freigabe eines Profils nie ein zweites Ziel desselben Konnektors, etwa einen
externen statt des internen Nextcloud-Servers.

**Stufen der Freigabe:** aus (niemand) · für alle Konten · für bestimmte Gruppen oder Personen. Für
Konnektoren ist die Freigabe die Fähigkeit `CREATE_CONNECTOR_LIBRARY`, erteilt je Profil bzw. je
Konnektortyp, mit den bestehenden Subjekten und der bestehenden Auswertung. Sie öffnet einen
Anlegepfad, nie einen Inhalt. `SYSTEM_ADMIN` hat sie implizit. Für MCP-Profile siehe
[MCP-Grundlage](#mcp-grundlage).

**Was als Neuanlage zählt:** eine Bibliothek anlegen und ein Konto auf einem Profil erstmals
verbinden. **Keine** Neuanlage sind: eine bestehende Verbindung neu verbinden (nach Ablauf, Widerruf
oder geänderten Scopes), trennen und löschen. Diese Handlungen bleiben auch nach Entzug der Freigabe
möglich.

**Auslieferungszustand:** Die bisherigen Konnektoren (Dateisystem, Webverzeichnis, RSS, Confluence,
S3) sind je Typ für alle Konten frei, wie heute. Jeder neue Konnektor und jedes neue Profil steht auf
„aus“. Ein Update öffnet nie stillschweigend einen neuen Weg nach draußen.

**Wirkung:**

| Handlung | Neuanlage | Laufende Bibliotheken | Bestand |
|---|---|---|---|
| Freigabe einschränken oder einer Person entziehen | nicht mehr möglich | **laufen weiter** | unverändert |
| Profil **sperren** | nicht möglich | Läufe stoppen | durchsuchbar, gekennzeichnet |
| Konnektor **sperren** (gilt für alle seine Profile) | nicht möglich | Läufe stoppen | durchsuchbar, gekennzeichnet |
| Bibliothek mit freier Adresse **sperren** (nur beim Einschalten der [Profilpflicht](#profilpflicht)) | — | Läufe dieser Bibliothek stoppen | durchsuchbar, gekennzeichnet |
| Sperre aufheben | wieder nach Freigabe | laufen ohne Neueinrichtung weiter | — |

Eine gesperrte Bibliothek trägt den Hinweis „Gesperrt – Inhalt wird nicht mehr aktualisiert“. Im
Chat erscheint bei Treffern aus ihr „Stand vom …“.

**Kein toter Weg.** Ein Konnektor oder Zugang, der für die Person nicht freigegeben ist, erscheint im
Anlage-Assistenten mit einem Hinweis, statt zu fehlen oder zu scheitern.

**Regel für alle Hinweise dieses Dokuments:** Jeder Hinweis (gesperrt, ruhend, abgelaufen, nicht
freigegeben, Zugang entfernt, Abbruch) nennt die **zuständige Stelle** und sagt, **was mit dem Inhalt
geschieht** (bleibt durchsuchbar, wird nicht aktualisiert, wird zum Datum gelöscht).

**Verwaltungsübersicht:** je Konnektor und Profil eine Klartextzeile („Zugang Nextcloud intern:
frei für Alle Konten“). Vergabe, Entzug und Sperre sind Governance-Ereignisse.

---

## Verbindungsprofile

Die Systemverwaltung legt Profile an. Wer ein Profil nutzt, wählt es aus und trägt nichts davon
selbst ein.

| Feld | Beispiel | Hinweis |
|---|---|---|
| Name | „Zugang Exchange Rheinfurt“ | erscheint in der Auswahl |
| Konnektor | Exchange | genau einer; beim Profiltyp „MCP-Server“ keiner (siehe [MCP-Grundlage](#mcp-grundlage)) |
| Server-Adresse | Nextcloud-URL, `https://graph.microsoft.com` | einziges Ziel der Zugangsdaten |
| Anmeldeart | persönliches Geheimnis, OAuth, Client-Credentials | nur die Arten, die der Konnektor anbietet |
| App-Registrierung | Client-ID, Client-Secret, Mandant | bei OAuth und Client-Credentials; Secret verschlüsselt, nie in Antworten |
| Ablaufdatum des Secrets | 2027-03-31 | OPAA warnt vorher |
| Scopes | `Files.Read offline_access` | bei OAuth und Client-Credentials |
| Besitzart | Bibliothek, Person oder beides | welche Verbindungen darauf entstehen dürfen |
| Konnektoreigene Vorgaben | etwa die Confluence-Edition | Form bestimmt der Konnektor |
| Freigabe, Sperre | siehe oben | |

**Keine zentrale OPAA-App.** Jede Installation registriert ihre eigene App beim Anbieter; das
Handbuch beschreibt die Registrierung je Konnektor. Mehrere Profile je Konnektor sind erlaubt.

**Ziel der Zugangsdaten.** Bei Profilen leitet sich jedes Ziel aus der Server-Adresse des Profils ab.
Ändert die Systemverwaltung sie, zeigt OPAA vorher, wie viele Verbindungen und Bibliotheken betroffen
sind, und verlangt eine Bestätigung. Danach verwirft es alle Token und Geheimnisse auf dem Profil.

**Was eine neue Zustimmung verlangt:**

| Änderung | Folge |
|---|---|
| Server-Adresse | alle Verbindungen verworfen |
| neue Client-ID, neuer Mandant, geänderte Scopes | neue Zustimmung aller Verbindungen |
| neues Client-Secret zur selben Client-ID | keine; Verbindungen bleiben |

**Notabschaltung „Alle Verbindungen trennen“** je Profil: löscht sofort alle Token und Geheimnisse
darauf, ohne das Profil zu löschen.

**Löschen eines Profils** trennt alle Verbindungen. Bibliotheken bleiben mit Bestand und dem Hinweis
„Zugang entfernt“ stehen, ohne Läufe, bis sie einem anderen Profil desselben Konnektors zugeordnet
oder gelöscht werden.

**Wer eine Bibliothek einem anderen Profil zuordnet:** bei Bibliotheken mit Quellverbindung oder
freier Adresse die Verwaltenden der Bibliothek; bei einer privaten Bibliothek nur die Besitzerin,
indem sie ihr Konto auf dem neuen Profil verbindet. Die Systemverwaltung hängt keine private
Bibliothek um.

### Profilpflicht

Jeder Konnektor meldet, wie er zu Profilen steht:

| Angabe | Bedeutung | Konnektoren |
|---|---|---|
| **verboten** | kein entferntes Ziel | Upload (keine Quelle), Dateisystem (lokale Serverpfade, begrenzt durch die Pfad-Allowlist des Betriebs) |
| **optional** | Profil oder freie Adresse | Webverzeichnis, RSS, Confluence, S3, Nextcloud |
| **Pflicht** | nur mit Profil | Konnektoren mit OAuth oder Client-Credentials, etwa Dropbox, Exchange |

Ein Profil für Webverzeichnis oder RSS trägt nur die Server-Adresse (Ursprung), ohne Anmeldeart.

Bei „optional“ kann die Systemverwaltung die Profilpflicht einschalten:

- **erst, wenn ein passendes Profil existiert;**
- beim Einschalten zeigt eine Liste die Bibliotheken mit freier Adresse, und die Verwaltung wählt
  für den ganzen Bestand, ob er **weiterläuft** oder **gesperrt** wird (Sperre je Bibliothek, siehe
  [Wirkung](#konnektor-freigabe-und-sperre)); eine gesperrte Bibliothek läuft wieder, sobald sie
  einem Profil zugeordnet ist;
- danach bietet der Anlage-Assistent nur noch die Profilauswahl an.

Wer einen fehlenden Server braucht, kann im Assistenten einen **Zugangswunsch** mit Server-Adresse
an die Systemverwaltung stellen (optional).

Mit Profilpflicht für alle Konnektoren der Angabe „optional“ liegen alle entfernten Ziele an einer
Stelle in der Hand der Systemverwaltung.
Die bestehende Zielprüfung gegen private und lokale Adressbereiche bleibt daneben bestehen.

---

## Anmeldearten

| Anmeldeart | Ablauf | Besitz | Typische Konnektoren |
|---|---|---|---|
| **Persönliches Geheimnis** | Person trägt App-Passwort oder persönliches Token ein | Person oder Bibliothek | Nextcloud, Confluence Data Center |
| **OAuth** | Autorisierungscode mit PKCE und `state`; Zustimmung beim Anbieter, Rücksprung in OPAA; Refresh- und Zugriffstoken verschlüsselt | Person oder Bibliothek | Dropbox, Exchange (delegiert) |
| **Client-Credentials** | Anwendung meldet sich mit Client-ID und Secret des Profils an, ohne Person | Bibliothek | Funktionspostfächer, Microsoft 365 ([#2153](https://github.com/criew/opaa/issues/2153)) |

Ein Konnektor meldet, welche Anmeldearten er anbietet; das Profil wählt eine.

**Erneuerung (OAuth):** vor Ablauf; rotiert der Anbieter den Refresh-Token, ersetzt OPAA ihn in
derselben Transaktion. Je Verbindung läuft höchstens eine Erneuerung zugleich.

**Ablauf und Widerruf:** Ein Lauf scheitert mit eigener Kategorie, nie stumm.

- Nennt der Anbieter ein Ablaufdatum, warnt OPAA die Besitzerin bzw. die Verantwortlichen **14 Tage**
  vorher, über die bestehende In-App-Benachrichtigung (Glocke, ADR-0019) und an der Verbindung.
- Abgelaufene Verbindungen zeigen „Verbindung abgelaufen – neu verbinden“.
- Die Verwaltungsübersicht zeigt die Anzahl abgelaufener Verbindungen je Profil mit Warnung ab einem
  einstellbaren Schwellenwert.

**Token-Austausch (nur Spike).** In openDesk melden sich alle Dienste über dasselbe Keycloak an. Ein
Spike prüft, ob OPAA das Anmeldetoken einer Person gegen ein Token für Nextcloud bzw. Open-Xchange
tauschen kann. Bekannte Grenzen: OPAA bräuchte einen vertraulichen Client, und getauschte Token gelten
nur in der laufenden Sitzung. Erst danach wird über eine vierte Anmeldeart entschieden.

---

## Quelle verbinden (Quellfreigabe)

Für Quellen ohne Dienstkonto-Weg; erster Nutzer ist Dropbox
([#2154](https://github.com/criew/opaa/issues/2154)).

```
Anlage-Assistent: Quellart „Dropbox“ → Zugang „Dropbox Bauamt“
  → ☐ „Ich verbinde ein Dienstkonto, kein persönliches Konto“ (Pflicht)
  → [Quelle verbinden] ──► Dropbox: Anmeldung, Zustimmung
  ◄── „Verbunden als bauamt@…“ → Ordner wählen → Bibliothek anlegen
```

- Der Token gehört der **Bibliothek**, nicht der zustimmenden Person.
- Die Kontoadresse beim Anbieter steht **dauerhaft** in den Bibliotheksdetails.
- Je Verbindung ist eine **Person oder Gruppe verantwortlich**; sie erhält Ablaufwarnungen und
  verbindet neu. Neu verbinden ist keine Neuanlage und bleibt auch nach Entzug der Freigabe möglich,
  solange das Profil nicht gesperrt ist.
- Die Systemverwaltung sieht eine **Liste ruhender Quellverbindungen** (abgelaufen, getrennt,
  gesperrt). Private Bibliotheken erscheinen darin nicht.
- Rechte und Teilen folgen dem bestehenden Modell einschließlich der Freigabe-Obergrenze.

---

## Verbundene Konten

Eine Person verbindet ihr eigenes Konto mit einem Profil der Besitzart „Person“, sofern das Profil
für sie freigegeben ist. Erster Nutzer ist **Nextcloud mit App-Passwort**.

```
Verbundene Konten
┌─────────────────────────────────────────────────────────────────┐
│ Zugang Nextcloud intern   avogt          verbunden              │
│   genutzt von: „Meine Ablage“            [Trennen]              │
│ Zugang Exchange           —              [Verbinden]            │
│ Zugang Dropbox            abgelaufen     [Neu verbinden]        │
│ Zugang Nextcloud Partner  verbunden      [Trennen]              │
│   nicht mehr freigegeben – läuft weiter; zuständig: IT-Referat  │
│                                                                 │
│ Warum fehlt mein Zugang?  → wer Zugänge freischaltet            │
└─────────────────────────────────────────────────────────────────┘
```

- **Freiwillig.** Niemand muss ein Konto verbinden; ein Verzicht hat keine Nachteile. Nichts in OPAA
  setzt ein verbundenes Konto voraus oder fordert dazu auf, außer an dieser Seite.
- Je Person und Profil höchstens ein verbundenes Konto.
- **Trennen** löscht Token bzw. Geheimnis sofort und widerruft es, wo der Anbieter das anbietet. Private
  Bibliotheken auf der Verbindung ruhen danach mit Hinweis.
- Die Seite zeigt **alle bestehenden Verbindungen** der Person, auch zu Profilen, die für sie nicht
  mehr freigegeben sind; diese tragen den Hinweis „nicht mehr freigegeben“. Zum **neuen** Verbinden
  bietet sie nur freigegebene Profile an. „Warum fehlt mein Zugang?“ nennt die zuständige Stelle.
- **Trennen und Löschen gehen immer**, auch bei entzogener Freigabe oder gesperrtem Profil. **Neu
  verbinden** einer bestehenden Verbindung geht nach Entzug der Freigabe weiter (keine Neuanlage),
  nicht aber bei gesperrtem Profil. Ein **neues** Konto auf einem nicht freigegebenen Profil ist
  gesperrt.
- Nutzung zunächst nur für private Bibliotheken; die Live-Nutzung im Chat folgt mit #1747.

---

## Private Bibliotheken

Eine private Bibliothek wird über ein verbundenes Konto gespeist. Ihr Inhalt ist, was die Person beim
Anbieter selbst sehen darf. Niemand sonst darf ihn sehen.

**Nur-Besitzerin-Regel.** Eine private Bibliothek hat außer ihrer Besitzerin keine Leserin. Das ist
eine **eigene, harte Regel**, nicht eine Stufe der bestehenden Freigabe-Obergrenze: Jene deckelt nur
den Grant an „Alle Konten“ und ist von der Systemverwaltung je Bibliothek einstellbar. Diese Regel
sitzt am Grant-Pfad und am Pfad der Fremdzugangsfreigabe und gilt für jede private Bibliothek, ohne
Einstellung. **Die Systemverwaltung kann sie nicht lockern.**

| Regel | Durchsetzung |
|---|---|
| Genau eine Besitzerin, eine Person | beim Anlegen festgelegt, nicht übertragbar |
| Keine Grants an Personen, Gruppen oder „Alle Konten“ | Nur-Besitzerin-Regel am Grant-Pfad |
| Keine Fremdzugangsfreigabe | Nur-Besitzerin-Regel am Pfad der Fremdzugangsfreigabe |
| Keine Nachfolge | Eigentum geht an niemanden über |
| **„Sicht als“ ausgeschlossen** | private Bibliotheken liegen nie im fremden Rechtekontext |
| Inhalt folgt der Rechteformel | die Verwaltungsrolle öffnet ihn nicht |

**Verwaltungshandlungen**, abschließend: Die Systemverwaltung kann private Bibliotheken nur über
folgende Handlungen berühren:

1. Konnektor oder Profil sperren (Läufe stoppen),
2. „Alle Verbindungen trennen“ am Profil (Notabschaltung),
3. ein Profil löschen (Verbindungen getrennt, Bibliotheken ruhen),
4. die Server-Adresse eines Profils ändern (Verbindungen verworfen),
5. Client-ID, Mandant oder Scopes eines Profils ändern (neue Zustimmung nötig),
6. das Kontingent hausweit festsetzen,
7. ein Konto deaktivieren (löst den [Lebenszyklus](#lebenszyklus-und-löschung) aus).

Umbenennen, Rechte ändern, einem anderen Profil zuordnen, Läufe auslösen, Inhalte einsehen oder
einzeln löschen kann sie nicht. Der [Vorfallszugriff](#vorfallszugriff) ist keine Handlung der
Systemverwaltung.

**Was die Verwaltung sieht:** je Profil die Anzahl verbundener Konten und privater Bibliotheken,
Laufdaten und Kontingentnutzung nur zusammengefasst, Fehler nur als Kategorie ohne Inhaltsbezug (keine
Dateinamen, Betreffzeilen oder Pfade). Keine Liste, wer welches Konto verbunden hat.

**Space-Zuordnung.** Die Besitzerin darf ihre private Bibliothek einem Space zuordnen. Das öffnet
nichts, weil im Chat „lesbar und zugeordnet“ gilt. Für alle anderen ist die Zuordnung **unsichtbar**:
in Oberfläche und API, in Zählern und beim Löschen eines Space. Ein Test belegt das.

**Im Chat** kennzeichnet die Antwort, wenn private Quellen eingeflossen sind. Ruht die Bibliothek,
steht bei ihren Treffern „Stand vom …“.

**Läufe** nutzen das verbundene Konto der Besitzerin. Bricht die Indexierung ab, etwa am Kontingent,
sieht die Besitzerin das mit Grund an der Bibliothek.

**Kontingent.** Jede private Bibliothek unterliegt dem bestehenden Speicherkontingent je Bibliothek
([#119](https://github.com/criew/opaa/issues/119)). Hinzu kommt ein Kontingent über **alle privaten
Bibliotheken einer Person** mit hausweiter Vorgabe, sonst ließe es sich durch weitere Bibliotheken
umgehen. Verbrauch und Grenze sieht im Einzelnen nur die Person selbst. Dieses Kontingent entsteht in
diesem Epic und muss stehen, bevor Postfächer angebunden werden.

### Vorfallszugriff

Der bestehende Vorfallsbereich ist eine Protokollabfrage mit Personenfilter und öffnet keine Inhalte.
Ein Zugriff auf den **Inhalt** einer privaten Bibliothek ist deshalb eine **neue Befugnis** neben den
Rollen, nach dem Muster der bestehenden Befugnisse: einzeln, benannt, befristet, auf eine Bibliothek
beschränkt. Sie setzt alle vier Bedingungen voraus:

1. **Antrag** durch eine Person mit `AUDITOR`, mit Zweck und Zeitraum.
2. **Bestätigung** durch eine zweite, andere Person in einer eigens benannten Rolle (etwa
   Datenschutz). Vier Augen; die beiden Personen sind verschieden.
3. **Benachrichtigung der Betroffenen** über Zugriff, Zeitpunkt und Grund.
4. **Beteiligung des Personalrats**, im Vorgang dokumentiert.

`SYSTEM_ADMIN` schließt diese Befugnis nicht ein und erreicht den Weg nicht. Die genaue
Ausgestaltung der Rollen legt [#2171](https://github.com/criew/opaa/issues/2171) fest. **Bis dahin
gibt es keinen Vorfallszugriff auf private Bibliotheken.**

---

## Lebenszyklus und Löschung

**Auslöser ist ausschließlich die Deaktivierung oder Löschung eines Kontos**, nicht Abwesenheit,
Versetzung oder Gruppenwechsel. Für lange Abwesenheit oder Versetzung gibt das Handbuch einen Hinweis
(Verbindung selbst trennen oder bestehen lassen).

1. **Sofort:** Alle verbundenen Konten der Person werden gelöscht, Token und Geheimnisse
   eingeschlossen, und wo möglich beim Anbieter widerrufen.
2. **Sofort:** Ihre privaten Bibliotheken stoppen und sind für niemanden lesbar.
3. **Nach der Löschfrist:** Die privaten Bibliotheken werden gelöscht. Die Frist stellt die Installation
   ein; sie hat eine **feste Obergrenze**, die keine Installation überschreiten kann. Innerhalb der
   Frist kann eine reaktivierte Person neu verbinden.

**Sofortlöschung:** Die Besitzerin kann eine private Bibliothek jederzeit selbst sofort löschen.

**Vollständig und nachweisbar.** Die Löschung umfasst Dokumente, Originale, Chunks, Embeddings,
Volltextindex und Caches. Ein Protokolleintrag hält fest, was in welchem Umfang gelöscht wurde, ohne
Inhalt.

Quellverbindungen gehören der Bibliothek und sind vom Lebenszyklus einer Person nicht betroffen.

**Wiederherstellung einer Sicherung:** Nach dem Einspielen gleicht OPAA die Token ab: Token
deaktivierter Konten werden gelöscht, abgelaufene gezählt. Wie mit Sicherungen umzugehen ist, die
innerhalb der Löschfrist entstanden sind, klärt der Datenschutz (siehe offene Fragen).

---

## Verbindungsprotokoll

Verbinden, Trennen, Neuverbinden, Ablauf, Notabschaltung und Löschung einer Verbindung schreibt OPAA
in ein **eigenes Verbindungsprotokoll**:

- Inhalt: wer, welches Profil, welches Ereignis, wann. Keine Token, keine Kontoadresse beim Anbieter
  (außer bei Quellverbindungen, wo sie ohnehin in den Bibliotheksdetails steht).
- **Eigene Leserolle;** die Systemverwaltung liest es nicht automatisch mit.
- **Aufbewahrungsfrist,** danach wird gelöscht.

Token und Geheimnisse erscheinen nie in Logs, Antworten oder Audit. Neben den beiden heutigen Plätzen
(Zugangsdaten und Push-Geheimnis der Bibliothek) entstehen zwei neue: das **Client-Secret am Profil**
und der **Token-Speicher** (Besitzer Bibliothek oder Person). Beide nutzen denselben
Verschlüsselungsweg wie die heutigen Quell-Zugangsdaten; Antworten tragen nur „gesetzt / nicht
gesetzt“ und den Status.

---

## Postfächer

Indexierte Postfächer berühren Aktenführung, Aufbewahrungspflichten und Vertretungsregeln. **Vor dem
ersten Postfach-Konnektor** wird das rechtlich geklärt; bis dahin wird kein Postfach-Konnektor
ausgeliefert. Für Nextcloud ist diese Klärung nicht nötig.

**Funktionspostfächer** brauchen kein verbundenes Konto. Sie werden über Client-Credentials angebunden;
der Exchange-Admin beschränkt die App auf bestimmte Postfächer. Sie bekommen eine eigene Regelung in
der Rechtsklärung, und das Handbuch weist darauf hin, dass ihre Anbindung mitbestimmungspflichtig sein
kann.

---

## MCP-Grundlage

OPAA wird fremde MCP-Server als Werkzeuge anbinden. Dieses Epic legt nur die Grundlage, damit dafür
kein zweiter Verbindungsweg entsteht:

- **Profiltyp „MCP-Server“:** ein Profil ohne Konnektor, mit Server-Adresse (nur Streamable HTTP,
  kein lokaler Prozess), Anmeldeart und verantwortlicher Gruppe. Das ist die Zulassungsliste aus
  [agents-and-tools.md](./agents-and-tools.md#mcp-als-standardisierte-anbindung).
- **Freigabe und Sperre** haben dieselbe Form wie bei jedem Profil (aus, alle, Gruppen; Sperre). Weil
  ein MCP-Profil keine Bibliothek anlegt, ist seine Freigabe nicht `CREATE_CONNECTOR_LIBRARY`,
  sondern eine Nutzungsfreigabe für das Profil; ihre Form legt das Folge-Epic zum MCP-Client fest.
- **Der Profiltyp bleibt in der Oberfläche verborgen**, bis der MCP-Client existiert.
- **Token-Speicher nach MCP-Autorisierung:** OAuth 2.1 mit PKCE, Ressourcen-Indikator je Server,
  Erkennung des Autorisierungsservers über die Metadaten des Servers. Ein Token gilt nur für den
  Server, für den es ausgestellt wurde, und wird nie weitergereicht.
- Ein verbundenes Konto auf einem MCP-Profil ist dasselbe Objekt wie bei einem Konnektor.

Werkzeugaufrufe, Freigabe schreibender Aufrufe und Ausgangstor gehören zu #1747 und einem Folge-Epic.

---

## Reihenfolge

1. **Freigabe und Profile:** Verbindungsprofile, Freigabe je Profil bzw. Typ, Sperre, Profilpflicht.
2. **Verbundene Konten mit persönlichem Geheimnis** und private Bibliotheken mit Lebenszyklus; erster
   Nutzer ist Nextcloud mit App-Passwort (lokal testbar, Dateiablage von openDesk).
3. **OAuth-Baustein** mit Client-Credentials, „Quelle verbinden“ und der MCP-Grundlage.
4. **Dropbox** (Quelle verbinden) und **Exchange** (verbundenes Konto, Funktionspostfächer); Exchange
   erst nach der Rechtsklärung und dem Kontingent.

Unabhängig davon: Spike zum Token-Austausch gegen openDesk. Der MCP-Client folgt als eigenes Epic.

---

## Benötigte ADR-Nachträge

| ADR | Nachtrag |
|---|---|
| [ADR-0025](../decisions/0025-mehrere-oidc-anbieter.md) | „OPAA speichert keine Tokens“ gilt für die Anmeldung an OPAA, nicht für Quellverbindungen und verbundene Konten |
| [ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) | `CREATE_CONNECTOR_LIBRARY` wird je Profil bzw. je Konnektortyp erteilt; neue Konnektoren und Profile ab Werk aus; Entzug wirkt nur auf die Neuanlage (Neuverbinden ist keine), Sperre auf Läufe; **Nur-Besitzerin-Regel** für private Bibliotheken als eigene Sperre am Grant- und Fremdzugangspfad, von der Systemverwaltung nicht lockerbar; „Sicht als“ für private Bibliotheken ausgeschlossen; **Vorfallszugriff auf Inhalte** als neue Befugnis neben den Rollen (Antrag `AUDITOR`, Bestätigung durch eine zweite benannte Rolle, `SYSTEM_ADMIN` nicht eingeschlossen); Leserolle des Verbindungsprotokolls |
| [ADR-0038](../decisions/0038-steckbare-konnektoren.md) | Profilangabe (verboten, optional, Pflicht) und Anmeldearten gehören in die Konnektor-Beschreibung; das Ziel der Zugangsdaten leitet sich bei Profilen aus der Server-Adresse des Profils ab; Entscheidung 3 („genau zwei Plätze für Geheimnisse“) wird um das Client-Secret am Profil und den Token-Speicher erweitert, beide auf demselben Verschlüsselungsweg |

Ob Token-Speicher und Verbindungsmodell einen eigenen ADR brauchen, entscheidet das Konzept-Issue.

---

## Integrationspunkte

| Bezug | Verhältnis |
|---|---|
| [knowledge-sources.md](./knowledge-sources.md) | Konnektoren, Zielprüfung, Zugangsdaten; Profile ersetzen die freie Adresse, wo Profilpflicht gilt |
| [spaces-and-assets.md](./spaces-and-assets.md) | Freigabe-Obergrenze, Nachfolge, Space-Zuordnung; private Bibliotheken als Sonderfall |
| [access-control.md](./access-control.md) | Fähigkeiten, Gruppen, Kontodeaktivierung, Freigabe-Obergrenze (durch die Nur-Besitzerin-Regel ergänzt), „Sicht als“, Vorfallsbereich und die neue Befugnis des Vorfallszugriffs |
| [agents-and-tools.md](./agents-and-tools.md) | MCP-Client, Zulassungsliste, Ausgangstor (Phase 2) |
| [external-access.md](./external-access.md) | Gegenrichtung: OPAA als MCP-Server |
| Epic [#2146](https://github.com/criew/opaa/issues/2146) | Datei-Konnektoren: Nextcloud (#2152), SharePoint/OneDrive (#2153), Dropbox (#2154) |
| [#2099](https://github.com/criew/opaa/issues/2099) | Wegfall persönlicher Spaces |

---

## Offene Fragen / Zukünftige Erweiterungen

- **Sicherungen innerhalb der Löschfrist:** Ob und wie gelöschte private Bibliotheken aus Sicherungen
  entfernt oder deren Wiedereinspielung verhindert wird, klärt der Datenschutz.
- **Werte der Fristen:** Vorgabe und Obergrenze der Löschfrist, Aufbewahrungsfrist des
  Verbindungsprotokolls; Abstimmung mit Personalrat und Datenschutz.
- **Leserolle des Verbindungsprotokolls:** eigene Systemrolle oder Fähigkeit, festzulegen im
  ADR-Nachtrag zu ADR-0036.
- **Live-Abfrage** verbundener Konten im Chat ohne Indexierung: mit #1747.
- **Token-Austausch** als weitere Anmeldeart, abhängig vom Spike.
- **Weitere Kanäle** für Ablaufwarnungen und Abbrüche (E-Mail, Zusammenfassung): Heute gibt es die
  minimale In-App-Benachrichtigung (ADR-0019); der Ausbau gehört zu
  [#1297](https://github.com/criew/opaa/issues/1297).

---

## Erfolgs-Metriken

- Ein neuer Konnektor braucht für Profil, Freigabe, Token-Speicher und Erneuerung keinen Code außerhalb
  seines Pakets.
- Kein Lauf scheitert stumm an einer abgelaufenen Verbindung.
- Nach einer Kontodeaktivierung existiert kein Token dieser Person mehr.
- Anteil der Personen mit Freigabe für einen Zugang, die nach 30 Tagen ein Konto verbunden haben
  (nur als Gesamtzahl je Profil).
- Das Handbuch nennt je Konnektor Umfang und Aktualität (Laufrhythmus, Verzögerung bis zur
  Auffindbarkeit).
