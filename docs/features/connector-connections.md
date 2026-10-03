# Konnektor-Freigabe, Verbindungsprofile und verbundene Konten

> **Status: Entwurf zur Stakeholder-Bewertung.** Grundlage sind die Beschlüsse des Maintainers vom
> 03.10.2026 in Epic [#2147](https://github.com/criew/opaa/issues/2147). Die nötigen ADR-Nachträge
> sind am Ende benannt, aber noch nicht geschrieben.

## Motivation

Bisher richtet eine einzelne Person eine Quelle ganz ein: Sie trägt Adresse, Zugangsdaten und
Einstellungen in die Bibliothek ein. Das reicht für eine Dateiablage mit Dienstkonto. Für drei
Fälle reicht es nicht:

1. **Quellen, die nur OAuth kennen.** Dropbox gibt kein Dienstkonto und keinen statischen
   Schlüssel aus. Der Zugang entsteht über eine Weiterleitung zum Anbieter, bei der eine Person
   zustimmt; danach braucht OPAA einen Refresh-Token, den es selbst erneuert.
2. **Persönliche Konten.** Das eigene Outlook-Postfach oder die eigene Nextcloud-Ablage sind nur mit
   den Rechten der jeweiligen Person erreichbar. Ein Dienstkonto, das alle Postfächer liest, ist
   genau der Fehler, vor dem [knowledge-sources.md](./knowledge-sources.md) warnt.
3. **Steuerung durch das Haus.** Die Systemverwaltung will entscheiden, welche Konnektoren es in
   ihrer Installation überhaupt gibt, welche Server angesprochen werden und mit welcher Anmeldeart,
   statt dass jede Person eine beliebige Adresse einträgt.

Heute gibt es dafür nichts: Eine einzige Fähigkeit (`CREATE_CONNECTOR_LIBRARY`) deckt alle
Konnektoren ab, jede Bibliothek trägt ihre Zieladresse frei, und OPAA speichert keine OAuth-Token.

Diese Spezifikation legt ein gemeinsames Modell fest. Es trägt die Quellfreigabe für Dropbox, die
verbundenen Konten von Personen und später die Anbindung fremder MCP-Server.

---

## Überblick

1. **Drei Stufen.** Die Systemverwaltung *erlaubt* einen Konnektor und *legt den Rahmen fest*
   (Verbindungsprofil). Eine Bibliothek oder eine Person *besitzt* die Verbindung. Die Verbindung
   wird *genutzt*: zuerst für die Indexierung in eine Bibliothek, später live im Chat.
2. **Konnektor-Freigabe je Typ:** aus, für alle Konten oder für bestimmte Gruppen. Neue Konnektoren
   sind ab Werk aus, die bisherigen an.
3. **Verbindungsprofile** halten Server, Anmeldeart, App-Registrierung und Scopes. Die
   Systemverwaltung kann je Konnektor erzwingen, dass er nur über ein Profil nutzbar ist.
4. **Zwei Anmeldearten:** OAuth (Autorisierungscode mit PKCE) und persönliches Geheimnis
   (App-Passwort oder Token). Der Token-Austausch über den Identitätsanbieter ist nur ein Spike.
5. **Quellfreigabe:** Eine verwaltende Person verbindet eine Bibliothek einmal per OAuth; die Läufe
   erneuern den Zugang selbst. Erster Nutzer ist Dropbox.
6. **Verbundene Konten:** Eine Person hinterlegt auf ein Profil ihren eigenen Zugang. Daraus speist
   sie eine **private Bibliothek**, die nur ihr gehört und nicht geteilt werden kann.
7. **Datensparsamkeit:** Token gehen bei Deaktivierung sofort verloren, private Konto-Bibliotheken
   haben keine Nachfolge, und die Systemverwaltung sieht nur Anzahlen.
8. **MCP-Grundlage:** Das Modell kennt den Profiltyp „MCP-Server“ und einen Token-Speicher nach der
   MCP-Autorisierung. Den MCP-Client selbst baut ein Folge-Epic hinter
   [#1747](https://github.com/criew/opaa/issues/1747).

---

## Begriffe

| Begriff | Bedeutung | Wer legt fest |
|---|---|---|
| **Konnektor** | Eine Quellart im Produkt, etwa Confluence, S3, Dropbox oder Exchange. Wird mit OPAA ausgeliefert | Produkt |
| **Konnektor-Freigabe** | Ob und für wen ein Konnektor in dieser Installation nutzbar ist | Systemverwaltung |
| **Verbindungsprofil** | Vorgegebener Rahmen für einen Konnektor: Server, Anmeldeart, App-Registrierung (Client-ID, Secret), Scopes. Je Konnektor beliebig viele | Systemverwaltung |
| **Profilpflicht** | Einstellung je Konnektor: nur über ein Verbindungsprofil nutzbar, ohne frei eingetragene Adresse | Systemverwaltung |
| **Quellfreigabe** | Einmalige OAuth-Zustimmung für eine Bibliothek, mit einem Dienst- oder Admin-Konto beim Anbieter. Der Token gehört der Bibliothek | Verwaltende der Bibliothek |
| **Verbundenes Konto** | Der eigene Zugang einer Person auf ein Verbindungsprofil: OAuth-Token oder App-Passwort. Gehört der Person | die Person |
| **Private Bibliothek** | Eine Bibliothek, die über ein verbundenes Konto gespeist wird. Sie hat nur ihre Besitzerin und ist nicht teilbar | die Person |

Den Ausdruck „eigene Wissensinstanz“ verwendet OPAA nicht. Es gibt dafür die private Bibliothek, weil
persönliche Spaces entfallen ([#2099](https://github.com/criew/opaa/issues/2099)).

Nicht zu verwechseln:

- **Fremdzugang** ([external-access.md](./external-access.md)): OPAA als MCP-*Server*, den fremde
  KI-Werkzeuge mit einem persönlichen Zugangstoken von OPAA abfragen. Dieses Dokument betrifft die
  Gegenrichtung, also Zugänge von OPAA *zu* fremden Systemen.
- **Anmeldung an OPAA** ([ADR-0025](../decisions/0025-mehrere-oidc-anbieter.md)): Die OIDC-Anbieter
  dienen nur der Identität. Ihre Token werden hier nicht verwendet, mit Ausnahme des Spikes zum
  Token-Austausch.

---

## Die drei Stufen

```
Stufe 1 — Systemverwaltung
  Konnektor-Freigabe ──── Exchange: für Gruppe „Pilot Postfach“
  Verbindungsprofil  ──── „Exchange Stadt Rheinfurt“: Graph, OAuth, Client-ID, Mail.Read

Stufe 2 — Besitz der Verbindung
  Quellfreigabe (Bibliothek)       oder   Verbundenes Konto (Person)
  z. B. Dropbox „Bauamt“                  z. B. Andrea Vogt → „Exchange Stadt Rheinfurt“

Stufe 3 — Nutzung
  Indexierung in eine Bibliothek          (gebaut in diesem Epic)
  Live-Abfrage im Chat / MCP              (später, #1747 und Folge-Epic)
```

Eine Stufe setzt die jeweils vorige voraus: Ohne Freigabe kein Profil, ohne Profil kein verbundenes
Konto. Konnektoren ohne Profil (siehe [Profilpflicht](#profilpflicht)) kommen wie heute direkt von
Stufe 1 zur Bibliothek.

---

## Konnektor-Freigabe

Die Systemverwaltung stellt je Konnektor eine von drei Stufen ein:

| Stufe | Wirkung |
|---|---|
| **aus** | Niemand legt eine Bibliothek dieses Typs an, und die Kachel erscheint im Anlage-Assistenten nicht |
| **für alle Konten** | Jede Person kann Bibliotheken dieses Typs anlegen |
| **für bestimmte Gruppen** | Nur Mitglieder der genannten Gruppen oder genannte Personen können anlegen |

Die Freigabe ist die bisherige Fähigkeit `CREATE_CONNECTOR_LIBRARY`, jetzt **je Konnektortyp**
erteilt, mit derselben Auswertung und denselben Subjekten (Person, Gruppe, „Alle Konten“). Sie öffnet
wie jede Fähigkeit einen Anlegepfad und nie einen Inhalt. `SYSTEM_ADMIN` hat sie implizit.

**Auslieferungszustand.** Die bisherigen Konnektoren (Dateisystem, Webverzeichnis, RSS, Confluence,
S3) sind „für alle Konten“ freigegeben, wie heute. Jeder neu hinzukommende Konnektor ist ab Werk
„aus“. Ein Update schaltet also nie stillschweigend einen neuen Weg nach draußen frei.

**Sperren eines Konnektors mit Bestand.** Wird ein Konnektor auf „aus“ gesetzt oder einer Gruppe
entzogen:

- Neue Bibliotheken dieses Typs lassen sich nicht mehr anlegen.
- Geplante und manuelle Läufe bestehender Bibliotheken starten nicht mehr. Ein gerade laufender
  Lauf endet regulär.
- Der Bestand bleibt durchsuchbar und behält seine Rechte. Die Bibliothek trägt den Hinweis
  „Konnektor gesperrt – Inhalt wird nicht mehr aktualisiert“.
- Wird der Konnektor wieder freigegeben, laufen die Bibliotheken ohne Neueinrichtung weiter.

Bei einer Gruppenfreigabe zählt für bestehende Bibliotheken die Freigabe an deren Eigentümer. Fällt
die Eigentümerin aus der Gruppe, verhält sich ihre Bibliothek wie bei einem gesperrten Konnektor.

**Sichtbarkeit.** Die Verwaltungsübersicht zeigt je Konnektor eine Klartextzeile („Dropbox: aus“,
„Exchange: für Gruppe Pilot Postfach“). Vergabe und Entzug sind Governance-Ereignisse wie jede
Fähigkeitsänderung.

---

## Verbindungsprofile

Ein Verbindungsprofil hält fest, *wohin* und *wie* ein Konnektor sich verbindet. Die Systemverwaltung
legt es an. Wer den Konnektor nutzen darf, wählt ein Profil aus und trägt nichts davon selbst ein.

**Inhalt eines Profils:**

| Feld | Beispiel | Hinweis |
|---|---|---|
| Name | „Exchange Stadt Rheinfurt“ | erscheint in der Auswahl |
| Konnektor | Exchange | genau einer |
| Server-Adresse | `https://graph.microsoft.com` bzw. Nextcloud-URL | Ziel aller Zugangsdaten, siehe unten |
| Anmeldeart | OAuth oder persönliches Geheimnis | nur die Arten, die der Konnektor anbietet |
| App-Registrierung | Client-ID, Client-Secret, Mandant | nur bei OAuth; Secret verschlüsselt, nie in Antworten |
| Scopes | `Mail.Read offline_access` | nur bei OAuth |
| Besitzart | Quellfreigabe, verbundenes Konto oder beides | welche Verbindungen auf dem Profil entstehen dürfen |
| Konnektoreigene Vorgaben | etwa die Confluence-Edition | Form bestimmt der Konnektor |

**Keine zentrale OPAA-App.** Jede Installation registriert ihre eigene App beim Anbieter. Eine
zentrale App würde den Betrieb an das Projekt binden und für Google zudem eine jährliche
Sicherheitsprüfung verlangen. Das Handbuch beschreibt die Registrierung je Konnektor.

**Mehrere Profile je Konnektor** sind erlaubt, etwa zwei Exchange-Mandanten oder eine interne und
eine externe Nextcloud.

**Ziel der Zugangsdaten.** Heute gilt: Jedes Ziel, an das Zugangsdaten gehen, leitet sich aus der
Adresse der Bibliothek ab, und eine Adressänderung verwirft die Zugangsdaten. Mit Profilen leitet
es sich aus der **Server-Adresse des Profils** ab. Ändert die Systemverwaltung diese Adresse, verwirft
OPAA alle Token und Geheimnisse auf dem Profil; die Betroffenen sehen „Verbindung getrennt – neu
verbinden“. Eine Änderung an App-Registrierung oder Scopes verlangt ebenfalls eine neue Zustimmung.

**Löschen eines Profils** trennt alle Verbindungen darauf. Bibliotheken bleiben mit Bestand und dem
Hinweis „Profil entfernt“ stehen, ohne Läufe, bis jemand sie einem anderen Profil zuordnet oder
löscht.

### Profilpflicht

Jeder Konnektor meldet, wie er zu Profilen steht:

| Angabe des Konnektors | Bedeutung |
|---|---|
| **verboten** | Profile ergeben keinen Sinn, etwa beim Upload oder beim RSS-Feed |
| **optional** | Profil möglich, freie Adresse ebenfalls, etwa bei Confluence oder S3 |
| **Pflicht** | Ohne Profil nicht nutzbar, etwa bei OAuth-Konnektoren wie Dropbox und Exchange |

Bei „optional“ kann die Systemverwaltung die **Profilpflicht einschalten**. Danach bietet der
Anlage-Assistent für diesen Konnektor nur noch die Profilauswahl an, kein Adressfeld. Bestehende
Bibliotheken mit freier Adresse laufen weiter und tragen den Hinweis „ohne Profil eingerichtet“; die
Verwaltende kann sie einem Profil zuordnen.

Die Profilpflicht ist die wirksamste Sicherung gegen Abfluss über frei eingetragene Ziele: Welche
Server überhaupt angesprochen werden, steht dann an einer Stelle und in der Hand der
Systemverwaltung. Die bestehende Zielprüfung gegen private und lokale Adressbereiche bleibt daneben
bestehen.

---

## Anmeldearten

| Anmeldeart | Ablauf | Typische Konnektoren |
|---|---|---|
| **OAuth** | Autorisierungscode mit PKCE und `state`. Weiterleitung zum Anbieter, Zustimmung, Rücksprung in OPAA. OPAA speichert Refresh- und Zugriffstoken verschlüsselt und erneuert selbst | Dropbox, Exchange/Outlook (Graph), OneDrive |
| **Persönliches Geheimnis** | Die Person trägt ein App-Passwort oder ein persönliches Token ein. Kein Ablauf beim Anbieter, solange es nicht widerrufen wird | Nextcloud, Confluence Data Center |

Ein Konnektor meldet, welche Anmeldearten er anbietet. Das Profil wählt eine davon.

**Erneuerung.** OPAA erneuert ein Zugriffstoken vor Ablauf. Rotiert der Anbieter dabei den
Refresh-Token, ersetzt OPAA ihn in derselben Transaktion. Je Verbindung läuft immer nur eine
Erneuerung zugleich, sonst entwerten sich gleichzeitige Läufe gegenseitig den Token.

**Ablauf und Widerruf.** Token verfallen beim Anbieter: bei Microsoft nach rund 90 Tagen ohne Nutzung,
bei Google im Testmodus nach 7 Tagen, und Personen wie Admins können eine Zustimmung widerrufen. Ein
Lauf scheitert dann mit eigener Kategorie und nie stumm. Die Bibliothek bzw. die Seite „Verbundene
Konten“ zeigt „Verbindung abgelaufen – neu verbinden“, und ein Klick startet die Weiterleitung
erneut.

**Token-Austausch über den Identitätsanbieter (nur Spike).** In openDesk melden sich alle Dienste
über dasselbe Keycloak an. OPAA könnte das Anmeldetoken einer Person gegen ein Token für Nextcloud
tauschen, ohne eigene Zustimmung je Dienst. Zwei Einschränkungen sind bekannt: OPAA bräuchte einen
vertraulichen Client beim Identitätsanbieter, und die getauschten Token gelten nur, solange die
Sitzung lebt, was für Hintergrundläufe nicht reicht. Ein Spike prüft das gegen openDesk (Nextcloud,
Open-Xchange). Erst danach wird entschieden, ob daraus eine dritte Anmeldeart wird.

---

## Quellfreigabe

Die Quellfreigabe ist der Weg für Quellen ohne Dienstkonto. Erster Nutzer ist Dropbox
([#2154](https://github.com/criew/opaa/issues/2154)).

**Ablauf:**

```
Anlage-Assistent: Konnektor „Dropbox“ → Profil „Dropbox Bauamt“ wählen
  → [Mit Dropbox verbinden]  ──► Dropbox: Anmeldung mit Dienst- oder Admin-Konto, Zustimmung
  ◄── Rücksprung in den Assistenten: „Verbunden als bauamt@…“
  → Ordner wählen → Bibliothek anlegen → erster Lauf
```

- Der Token gehört der **Bibliothek**, nicht der Person, die zugestimmt hat. Er bleibt, wenn diese
  Person die Bibliothek abgibt oder ausscheidet.
- Neu verbinden darf, wer die Bibliothek verwaltet. OPAA zeigt an, mit welchem Konto beim Anbieter die
  Verbindung besteht, damit ein versehentlich persönliches Konto auffällt.
- Rechte und Teilen der Bibliothek folgen dem bestehenden Modell, einschließlich der
  Freigabe-Obergrenze.
- Google Drive und Microsoft 365 brauchen keine Quellfreigabe: Sie nutzen ein Dienstkonto bzw.
  Client-Credentials ([#2146](https://github.com/criew/opaa/issues/2146)).

---

## Verbundene Konten

Eine Person verbindet ihr eigenes Konto mit einem Verbindungsprofil, das die Besitzart „verbundenes
Konto“ erlaubt. Voraussetzung ist die Konnektor-Freigabe für diese Person.

**Seite „Verbundene Konten“** im eigenen Profil:

```
Verbundene Konten
┌───────────────────────────────────────────────────────────────┐
│ Exchange Stadt Rheinfurt   andrea.vogt@…   verbunden          │
│   genutzt von: „Mein Postfach“             [Trennen]          │
│ Nextcloud intern           —               [Verbinden]        │
│ Dropbox privat             abgelaufen      [Neu verbinden]    │
└───────────────────────────────────────────────────────────────┘
```

- Je Person und Profil gibt es höchstens ein verbundenes Konto.
- **Trennen** löscht Token bzw. Geheimnis sofort und, wo der Anbieter es anbietet, widerruft es auch
  dort. Private Bibliotheken auf dieser Verbindung laufen nicht mehr und zeigen „Verbindung getrennt“.
- Die Seite zeigt nur Profile, für deren Konnektor die Person eine Freigabe hat.
- Nutzung zuerst nur für **private Bibliotheken**. Die Live-Nutzung im Chat folgt mit #1747.

---

## Private Bibliotheken

Eine private Bibliothek wird über ein verbundenes Konto gespeist. Ihr Inhalt ist das, was die Person
selbst beim Anbieter sehen darf, etwa ihr Postfach. Deshalb darf ihn niemand sonst sehen.

**Regeln:**

| Regel | Durchsetzung |
|---|---|
| Genau eine Besitzerin, eine Person (keine Gruppe) | beim Anlegen festgelegt, nicht übertragbar |
| Keine Grants an Personen, Gruppen oder „Alle Konten“ | über die Freigabe-Obergrenze technisch erzwungen, nicht als Hinweis |
| Keine Nachfolge | Eigentum geht beim Ausscheiden an niemanden über |
| Keine Fremdzugangsfreigabe | siehe [Offene Fragen](#offene-fragen--zukünftige-erweiterungen) |
| Systemverwaltung verwaltet, sieht aber keinen Inhalt | Inhalt folgt wie bei jedem Asset der Rechteformel; die Verwaltungsrolle öffnet ihn nicht |

**Zuordnung zu einem Space.** Die Besitzerin darf ihre private Bibliothek einem Space zuordnen. Das
öffnet nichts: Im Chat zählt „lesbar und zugeordnet“, für alle anderen Mitglieder ist die Bibliothek
nicht lesbar und trägt nichts bei. Die Oberfläche zeigt sie anderen Mitgliedern in der
Zuordnungsliste nicht, damit weder Name noch Existenz durchsickern.

**Läufe** nutzen das Token der Besitzerin. Ist es abgelaufen oder getrennt, ruht die Bibliothek mit
Hinweis; der Bestand bleibt für die Besitzerin durchsuchbar.

**Umfang** und Kontingent folgen den allgemeinen Regeln für Bibliotheken. Ein Kontingent je Person
ist in [knowledge-sources.md](./knowledge-sources.md) vorgesehen (#119) und wird hier umso
wichtiger, weil ein Postfach schnell groß ist.

---

## Lebenszyklus und Datensparsamkeit

**Deaktivierung, Ausscheiden, Kontolöschung:**

1. **Sofort:** Alle verbundenen Konten der Person werden gelöscht, Token und Geheimnisse
   eingeschlossen. Wo möglich, widerruft OPAA sie beim Anbieter.
2. **Sofort:** Ihre privaten Bibliotheken stoppen und sind für niemanden lesbar.
3. **Nach einer Frist:** Die privaten Bibliotheken werden mit Inhalt gelöscht. Die Frist ist eine
   Einstellung der Installation (Vorschlag: 30 Tage). In dieser Zeit kann eine reaktivierte Person
   neu verbinden und ihren Bestand weiter nutzen.

Quellfreigaben gehören der Bibliothek und sind davon nicht betroffen.

**Was die Systemverwaltung sieht:**

- je Profil die **Anzahl** verbundener Konten und privater Bibliotheken,
- je Profil die Anzahl abgelaufener Verbindungen,
- **keine** Liste, wer welches Konto verbunden hat, und keine Postfachadressen.

**Protokoll:** Verbinden, Trennen und Löschen einer Verbindung sind Ereignisse im Protokoll (wer, welches
Profil, wann), ohne Token-Inhalte und ohne Kontoadresse beim Anbieter. Token erscheinen nie in Logs,
Antworten oder Audit.

**Speicher:** Token und Geheimnisse liegen verschlüsselt, auf demselben Weg wie die heutigen
Quell-Zugangsdaten. Antworten tragen nur „gesetzt / nicht gesetzt“ und den Status.

---

## MCP-Grundlage

OPAA wird fremde MCP-Server als Werkzeuge anbinden (MCP-Client). Dieses Epic baut nur die Grundlage,
damit diese Anbindung keinen zweiten Verbindungsweg braucht:

- **Profiltyp „MCP-Server“:** ein Verbindungsprofil mit Server-Adresse (nur Streamable HTTP, kein
  lokaler Prozess), Anmeldeart und verantwortlicher Gruppe. Das ist die Zulassungsliste aus
  [agents-and-tools.md](./agents-and-tools.md#mcp-als-standardisierte-anbindung).
- **Konnektor-Freigabe** gilt für MCP-Server genauso: Die Systemverwaltung erlaubt sie für alle, für
  Gruppen oder gar nicht.
- **Token-Speicher nach MCP-Autorisierung:** OAuth 2.1 mit PKCE, Ressourcen-Indikator je Server,
  Erkennung des Autorisierungsservers über die Metadaten des Servers. Ein Token gilt nur für den
  Server, für den es ausgestellt wurde, und wird nie an einen anderen weitergereicht.
- **Verbundenes Konto** auf einem MCP-Profil ist dasselbe Objekt wie bei einem Konnektor.

Nicht in diesem Epic: Werkzeugaufrufe im Chat, Ausführung, Freigabe vor schreibenden Aufrufen und das
Ausgangstor. Sie gehören zu #1747 und einem Folge-Epic.

---

## Reihenfolge

1. **Konzept:** diese Spezifikation, Stakeholder-Bewertung, ADR-Nachträge.
2. **Konnektor-Freigabe je Typ** und **Verbindungsprofile mit Profilpflicht**, parallel.
3. **OAuth-Baustein mit Quellfreigabe.** Danach ist Dropbox (#2154) möglich.
4. **Verbundene Konten** und **private Bibliotheken.**
5. **Erster Konnektor mit verbundenem Konto** (Exchange/Outlook oder Nextcloud).
6. **Spike Token-Austausch** gegen openDesk, unabhängig von 2–5.
7. **MCP-Client** als Folge-Epic hinter #1747.

---

## Benötigte ADR-Nachträge

| ADR | Nachtrag |
|---|---|
| [ADR-0025](../decisions/0025-mehrere-oidc-anbieter.md) | „OPAA speichert keine Tokens“ gilt für die Anmeldung an OPAA, nicht für Quellfreigaben und verbundene Konten |
| [ADR-0036](../decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) | `CREATE_CONNECTOR_LIBRARY` wird je Konnektortyp erteilt; Auslieferungszustand neuer Konnektoren „aus“; Wirkung des Entzugs auf Bestand |
| [ADR-0038](../decisions/0038-steckbare-konnektoren.md) | Profilangabe (verboten, optional, Pflicht) und Anmeldearten gehören in die Konnektor-Beschreibung; das Ziel der Zugangsdaten leitet sich bei Profilen aus der Server-Adresse des Profils ab |

Ob Token-Speicher und Verbindungsmodell zusätzlich einen eigenen ADR brauchen, entscheidet die
Umsetzung des Konzept-Issues.

---

## Integrationspunkte

| Bezug | Verhältnis |
|---|---|
| [knowledge-sources.md](./knowledge-sources.md) | Konnektoren, Zielprüfung, Zugangsdaten der Bibliothek; Profile ersetzen dort die freie Adresse, wo die Profilpflicht gilt |
| [spaces-and-assets.md](./spaces-and-assets.md) | Freigabe-Obergrenze, Nachfolge, Space-Zuordnung; private Bibliotheken sind ein Sonderfall der Obergrenze |
| [access-control.md](./access-control.md) | Fähigkeiten, Gruppen, Deaktivierung von Konten |
| [agents-and-tools.md](./agents-and-tools.md) | MCP-Client, Zulassungsliste, Ausgangstor (Phase 2) |
| [external-access.md](./external-access.md) | Gegenrichtung: OPAA als MCP-Server; nicht Gegenstand dieses Dokuments |
| Epic [#2146](https://github.com/criew/opaa/issues/2146) | Datei-Konnektoren; Dropbox braucht die Quellfreigabe |
| [#2099](https://github.com/criew/opaa/issues/2099) | Wegfall persönlicher Spaces; private Bibliotheken ersetzen die „eigene Wissensinstanz“ |

---

## Offene Fragen / Zukünftige Erweiterungen

- **Fremdzugang auf private Bibliotheken.** Die Besitzerin könnte ihr eigenes Postfach über den
  Fremdzugang in einem externen KI-Werkzeug durchsuchen. Leserkreis bliebe sie allein, aber der
  Inhalt verließe OPAA. Vorschlag: zunächst ausgeschlossen.
- **„Sicht als“ und Vorfallsbereich.** Gelten diese Vollmachten auch für private Bibliotheken? Vorschlag:
  nein, außer im protokollierten Vorfallsbereich mit Vier-Augen-Prinzip.
- **Frist bis zur Löschung** privater Bibliotheken nach Deaktivierung: Vorschlag 30 Tage, Abstimmung mit
  Personalrat und Datenschutz.
- **Freigabe je Profil statt nur je Konnektor**, etwa „Exchange-Profil A nur für Gruppe X“. Vorerst nur
  je Konnektor; Profile erben dessen Freigabe.
- **Live-Abfrage** verbundener Konten im Chat, ohne Indexierung: kommt mit #1747.
- **Funktionspostfächer** brauchen kein verbundenes Konto. Sie gehen über eine App-Registrierung, die der
  Exchange-Admin auf bestimmte Postfächer beschränkt, also als normale Quelle mit Profil.
- **Benachrichtigung** bei abgelaufener Verbindung über das Postfach aus
  [#1297](https://github.com/criew/opaa/issues/1297), sobald es existiert; bis dahin nur der Hinweis an
  Bibliothek und Kontoseite.
- **Token-Austausch** als dritte Anmeldeart, abhängig vom Spike.

---

## Erfolgs-Metriken

- Ein neuer OAuth-Konnektor braucht keinen Code außerhalb seines Pakets für Weiterleitung,
  Token-Speicher und Erneuerung.
- Kein Lauf scheitert stumm an einer abgelaufenen Verbindung: Jede abgelaufene Verbindung ist an
  Bibliothek oder Kontoseite sichtbar.
- Nach einer Kontodeaktivierung existiert kein Token dieser Person mehr.
