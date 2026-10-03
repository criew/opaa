# ADR-0039: Ein Katalog und ausdrückliche Space-Zuordnung — `listed` entfällt, die Zuordnung ist eine harte Grenze

## Status

**Akzeptiert (02.10.2026)** — Issue [#2091](https://github.com/criew/opaa/issues/2091), Epic
[#2070](https://github.com/criew/opaa/issues/2070), Phase 1. Hält die Beschlüsse des Maintainers vom
02.10.2026 fest, die nach der Abstimmung vom 30.09.2026 und der Bewertung durch sechs
Stakeholder-Rollen getroffen wurden (Kommentare im Epic). Umgesetzt wird mit #2092 bis #2098 und dem
Demo-Seed #2103.

**Ändert:**

- [ADR-0037](0037-reichweite-als-freigabe-an-alle.md): Entscheidung 4 vollständig, der `listed`-Teil
  der Entscheidungen 5, 6, 7 und 9 sowie die Konsequenz „Katalog: lesbar ∪ `listed`".
- [ADR-0036](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md), Entscheidung 9: Die Begründung
  „Gegenstück zu `listed`" für die Freigabe interner Gruppen zur Verwendung entfällt; die Regel selbst
  bleibt.
- [ADR-0035](0035-fremdzugaenge-mcp-server-und-zugangstokens.md): die Verweise auf `listed` als
  Reichweitenfeld und die Zusage „Was die Web-Oberfläche nicht findet, findet auch der Fremdzugang
  nicht — und umgekehrt" (Entscheidung 5, „Folgen").

**Nimmt eine Zusage gegenüber der Personalvertretung zurück** — siehe Entscheidung 6. Alle drei
geänderten ADRs tragen einen Nachtrag mit Verweis hierher; ADR-0014, 0016, 0018 und 0019 einen Hinweis.

## Kontext

Für Assets gab es bis hierher vier Einstiege mit sich überlappender Bedeutung:

| Einstieg | zeigt |
|---|---|
| „Wissen" (`/libraries`) | Wissensbibliotheken, die man lesen darf |
| „Prompts" (`/prompts`) | Prompt-Bibliotheken, die man lesen darf |
| „Katalog" (`/catalog`) | beide Typen: lesbar **vereinigt mit** gelisteten (`listed`), auch ohne Leserecht — das Schaufenster aus ADR-0037, Entscheidung 4 |
| Space-Einstellungen | was dem Space zugeordnet ist |

Dazu kam eine Regel, die in keinem ADR stand, sondern nur als „dauerhafte Regel" in
`spaces-and-assets.md`, Abschnitt „Suchbereich je Chatart": **Ein Space ohne Zuordnung durchsucht
alles, was die Person lesen darf; erst eine gesetzte Zuordnung verengt.** Sie trug eine Zusage an die
Personalvertretung: Ein Raum, in dem jemand allein arbeitet, steht fachlich nie schlechter da als ein
gemeinsamer. Für Prompts galt die Zuordnung gar nicht als Grenze; sie ordnete das `/`-Angebot nur
(`PromptService#available`). Und für den Fall, dass ein Raum eine technische Zusicherung braucht, war
ein **Strikt-Modus** je Space spezifiziert, aber nie gebaut.

In der Abstimmung vom 30.09.2026 war das Ergebnis eindeutig: Die Stufung „ohne Zuordnung alles, mit
Zuordnung eingeengt" ist aus Nutzersicht nicht verständlich, selbst wenn man die Regel kennt. Das
Schaufenster ist ohne Anfrageweg eine Sackgasse, und es ist zugleich die einzige Stelle, an der die
Existenz nicht lesbarer Assets sichtbar wird. Alle sechs Rollen der Stakeholder-Bewertung (Sachbearbeitung,
KI-Champion, Referatsleitung, Personalrat, Betrieb, Skeptiker) urteilten „tragfähig mit Auflagen"; die
Auflagen sind unten eingearbeitet.

## Entscheidung

### 1. Ein Einstieg „Katalog", der nur Lesbares zeigt

Die Hauptnavigation hat für Assets **genau einen** Einstieg: den **Katalog**. Er zeigt alle Asset-Typen
(heute Wissen und Prompts, später Skills und Agenten) — **alles, was die Person lesen darf, und nichts
sonst**. Es gibt keine Typ-Einstiege mehr; ein neuer Asset-Typ braucht einen Eintrag in einer
Typ-Registry, keinen Menüpunkt.

- **Nur Kacheln**, keine Tabellenansicht. Die Kachel zeigt Typ-Badge mit Icon, Name, Beschreibung,
  Sichtbarkeit, Eigentümer, die eigene Rolle, Stand oder Status und die Zahl der Spaces, in denen das
  Asset zugeordnet ist. Wer verwaltet, findet die Übersicht über Sortierung (Name, Änderungsdatum) und
  Filter, nicht über eine Tabelle.
- **Filter:** Typ, Sichtbarkeit, „aus meinen Gruppen", Favoriten (Entscheidung 7); dazu eine Suche über Name und
  Beschreibung.
- **„Neu"** ist generisch: Schritt 1 ist die Typwahl als Kacheln mit Icon, angeboten werden nur Typen,
  für die die Person das Anlegerecht hat; danach folgt der typeigene Assistent.
- **Kachelauswahl mit Icon ist das durchgängige UI-Muster** für jede Auswahl aus einer überschaubaren
  Menge gleichartiger Dinge: Typwahl, Quellart, Zuordnung zu einem Space, Bibliotheksauswahl eines
  Zugangstokens. Festgeschrieben in [`docs/design/guidelines.md`](../design/guidelines.md).

### 2. Sichtbarkeit: öffentlich oder geschlossen, abgeleitet aus den Freigaben

Ein Asset ist entweder

- **öffentlich** — es trägt eine Freigabe an „Alle Konten" (ADR-0037), oder
- **geschlossen** — es ist nur über Freigaben an Personen oder Gruppen erreichbar.

Die Sichtbarkeit ist **kein neues Feld**, sondern wird aus den Freigaben abgeleitet, wie schon die
Reichweiten-Badge aus ADR-0037, Entscheidung 9. Einen Anfrage- oder Genehmigungsweg gibt es nicht.

**Die Existenz eines nicht lesbaren Assets wird nicht preisgegeben.** Weder Katalog noch Auswahl, Space-Einstellungen
oder API-Antwort nennen einen Namen, eine Beschreibung, eine Kennung oder eine **Anzahl** nicht lesbarer Assets. Die
einzige zulässige Auskunft steht innerhalb eines Space, dem die Person angehört: ein Hinweis **ohne Anzahl und ohne
Namen**, dass dort nicht alle zugeordneten Inhalte für sie lesbar sind („Nicht alle zugeordneten Inhalte sind für Sie
lesbar.") — in den Space-Einstellungen und, wenn deshalb nichts durchsucht werden kann, als Signal im Chat
(Entscheidung 4). Er erklärt, warum zwei Mitglieder unterschiedliche Antworten erhalten, ohne ein einzelnes Asset
erkennbar zu machen. Die Beschriftung in der Oberfläche
legt #2094 fest; „öffentlich" klingt in Behörden nach Internet, der Vorschlag ist „Für alle" /
„Eingeschränkt". „Öffentlich" und „geschlossen" sind die Begriffe der Spezifikation.

### 3. `listed` entfällt vollständig — auch in Historie und Audit

Das Schaufenster (`assets.listed`, die Obergrenze `listed_cap`, `requireListedWithinLimits`, der
Teilindex `idx_assets_organization_listed`, das Antwortfeld `accessible`, der Schalter „Im Katalog
auffindbar, auch ohne Berechtigung") entfällt ersatzlos. **Sichtbar ist gleich lesbar**, mit der
einen zahlen- und namenlosen Ausnahme aus Entscheidung 2.

Es entfällt **auch rückwirkend**: Die Spalte `listed` verschwindet aus `asset_visibility_history`, und
das Audit-Ereignis `ASSET_VISIBILITY_CHANGED` entfällt, soweit es nur `listed` trägt. Die
Begründung ist eine Tatsache, kein Abwägen: **Alle bestehenden Installationen lassen sich neu
aufbauen** (Demo und Testinstallationen, kein Produktivbetrieb). Ein Nachweis „war dieses Asset am
3. März gelistet?" wird damit für die Vergangenheit nicht mehr beantwortbar — er hat nie Zugriff
belegt, nur Sichtbarkeit eines Eintrags, und die Prüferfrage „wer konnte lesen?" beantwortet die
Grant-Historie unverändert.

`asset_visibility_history` bleibt als Tabelle bestehen, weil sie weiter die Fremdzugangsfreigabe einer
Wissensbibliothek trägt (ADR-0035). Die Freigabe-Obergrenze konnektor-gespeister Bibliotheken (#797)
besteht danach nur noch aus `all_accounts_grant_allowed`.

### 4. Die Space-Zuordnung ist für alle Asset-Typen eine harte Grenze

**Ein Space enthält genau, was ihm zugeordnet ist.** Im Chat eines Space ist nur nutzbar, was dem
Space zugeordnet **und** für die Person lesbar ist — für jeden Asset-Typ, serverseitig durchgesetzt:

| Typ | Was die Zuordnung begrenzt |
|---|---|
| Wissensbibliothek | den Suchbereich des Chats, den Chip „@Space-Wissen" und die konkreten `@`-Chips — ein Chip auf eine nicht zugeordnete Bibliothek ist nicht möglich |
| Prompt-Bibliothek | das `/`-Angebot (`/prompts/available` verlangt den Space) und das Einsetzen (`usedPromptId` wird gegen die Zuordnung des Chat-Space geprüft) |
| Agent (Zielbild) | das **Angebot** im Chat. Die eigene Wissensbindung eines Agenten bleibt unberührt — der Space verengt den Agenten weiterhin nicht (`agents-and-tools.md`, „Der Agent führt sein Wissen selbst mit") |

Daraus folgt:

- **Die Übergangsregel entfällt**, auch für persönliche Spaces. Ein Space ohne zugeordnetes Wissen
  durchsucht kein Wissen. Persönliche Spaces starten leer.
- **Kein stiller Leerlauf.** Ein Space ohne zugeordnetes Wissen zeigt im Chat und auf der Space-Seite
  einen Hinweis mit **Direktlink zur Zuordnung** (für `CURATOR`/`ADMIN`; andere Mitglieder erfahren,
  wer zuordnen kann). Es gibt **zwei getrennte Signale ohne Zahlen**: „diesem Space ist kein Wissen
  zugeordnet" und „zugeordnet, aber nichts davon für Sie lesbar". Eine Antwort ohne Quellen bleibt
  möglich, ist aber deutlich gekennzeichnet und nie scheinbar quellenbasiert.
- **Der Strikt-Modus entfällt**, weil jeder Space im Sinne des Suchbereichs strikt ist. Mit ihm
  entfallen die spezifizierten, nie gebauten Bestandteile, die an ihm hingen: die Kennzeichnung
  „strikt-only" einer Bibliothek, die Aufrufverweigerung für Agenten im Strikt-Space, die Ablehnung
  einer Mitgliederaufnahme und der Zustand „Voraussetzung verletzt". Eine technische Zusicherung „alle
  Mitglieder dürfen alles Zugeordnete lesen" gibt es damit nicht mehr; wer einen Raum so führen will,
  ordnet nur solche Bestände zu. Gegen das Ableitungsleck bleiben die vier Mittel aus
  `spaces-and-assets.md`, „Was bleibt" (Herkunftsverfolgung, Hinweis im Teilen-Dialog,
  rechtegeprüfte Sprungmarken, Benachrichtigung des Eigentümers).
- **Der Chip heißt „@Space-Wissen"** statt „@Alles-Wissen": Er durchsucht das Wissen des Space, nicht
  alles.
- **Die Zuordnung gewährt weiterhin keinen Zugriff.** Zuordnen darf, wer im Space `CURATOR` ist und das
  Asset selbst lesen kann; Mitglieder ohne Leserecht finden darin nichts. Die Space-Einstellungen zeigen
  jedem Mitglied nur die für es lesbaren Zuordnungen und, falls zutreffend, den Hinweis ohne Anzahl
  „Nicht alle zugeordneten Inhalte sind für Sie lesbar." (Entscheidung 2, #2097).
- **Die Zuordnung wird nicht historisiert.** Sie gewährt keinen Zugriff; die Stichtagsfrage „wer konnte
  lesen?" beantwortet die Grant-Historie. Was ein einzelner Chat durchsucht hat, wird wie bisher
  nicht protokolliert (Mitbestimmung).

**Ausgleich für die Kuratierungslast** (Einwand Skeptiker, KI-Champion, Sachbearbeitung: „für alle
freigegeben" wirkt erst nach Zuordnung): Der Space-Assistent ordnet aus derselben Kachelliste zu, mit
den Filtern „nur Favoriten", „aus meinen Gruppen", „alle" und Suche; der Schritt ist überspringbar.
Am Katalog gibt es „In Space verwenden", höchstens zwei Klicks bis zur Zuordnung. Die Benachrichtigung
der Eigentümer bei Zuordnung zu einem gemischten Space (ADR-0019) gilt für alle Typen und wird beim
Anlegen mit vielen Zuordnungen gebündelt (#2097).

### 5. Fremdzugang und Suche ohne Space bleiben bei ihrer eigenen, ausdrücklichen Auswahl

Der Fremdzugang (MCP-Server, Zugangstoken) bleibt **bewusst ohne Space**: Er durchsucht lesbar ∩
Fremdzugangsfreigabe der Bibliothek ∩ Bibliotheksauswahl des Tokens (ADR-0035, Entscheidung 5). Die
Token-Auswahl ist im Kleinen schon eine ausdrückliche Zuordnung und erhält dieselbe Kachelauswahl wie
der Space-Assistent (#2098). Tokens an Spaces zu binden wurde am 02.10.2026 verworfen.

Dasselbe gilt für den Endpunkt `POST /api/v1/search` (Treffer ohne Antwort), den der Fremdzugang mit
der angemeldeten Person teilt: Er hat keinen Space-Bezug, durchsucht für eine Person ihre lesbaren
Bibliotheken, enger gefasst über die mitgegebene Auswahl, und bleibt unverändert.

Die Zusage aus ADR-0035 „Was die Web-Oberfläche nicht findet, findet auch der Fremdzugang nicht — und
umgekehrt" gilt damit nicht mehr wörtlich. **Sie lautet jetzt:** Beide Kanäle laufen über denselben
Suchweg mit derselben Rechteprüfung; was sie durchsuchen, bestimmt im Chat die Zuordnung des Space,
im Fremdzugang die Auswahl des Tokens. Eine Bibliothek, die eine Person nicht lesen darf, findet sie
in keinem der beiden Kanäle.

### 6. Geänderte Zusage gegenüber der Personalvertretung

> **Zurückgenommen wird die Zusage: „Ein Raum, in dem jemand allein arbeitet, steht fachlich nicht
> schlechter da als ein gemeinsamer — der Suchbereich umfasst dort alles, was die Person lesen darf."**
> (`spaces-and-assets.md`, „Private Inhalte sind unbeobachtet" und „Suchbereich je Chatart".)

Sie war die Begründung der Übergangsregel und fällt mit ihr. Ein Raum, in dem jemand allein arbeitet,
durchsucht künftig genau das, was ihm zugeordnet ist — wie jeder andere. Wer ausweicht, muss sich
das benötigte Wissen dort zuordnen; dafür sind Hinweis mit Direktlink, Space-Assistent und
„In Space verwenden" da.

**Unverändert bleibt die Zusage „Private Chats sind unbeobachtet"**: Private Inhalte sind für
Systemverwaltung, Revision und Dienststellenleitung nicht lesbar, in jedem Space. Ebenso bleibt: Ein
privater Chat verfügt über dasselbe Wissen und denselben Suchbereich wie ein geteilter **im selben
Space** — der Privatstatus kostet keinen Zugang zu Wissen. Was sich ändert, ist allein der
Vergleich zwischen verschiedenen Räumen.

Die Änderung ist der Personalvertretung vor dem Rollout ausdrücklich vorzulegen, als **geänderte
Zusage**, nicht als Detail der Suche.

### 7. Favoriten sind privat

Jede Person kann jedes für sie sichtbare Asset als **Favorit** markieren. Favoriten stehen im Katalog
oben und wirken als Filter in Katalog, Space-Assistent und Token-Auswahl (#2095). Weil eine Liste von
Favoriten ein Interessenprofil ist (Einwand Personalrat, Betrieb), gilt:

- Abrufbar **nur für die Person selbst** — auch nicht für Systemverwaltung, Space-Verwaltung oder
  Asset-Verantwortliche.
- **Keine Zählung** und keine Anzeige von Favoriten je Asset, auch nicht aggregiert.
- **Kein Protokolleintrag, keine Historie.** Nicht Teil von Berichten und Exporten an Dritte; enthalten
  nur in der Selbstauskunft der Person (Muster der persönlichen Ordnungsmerkmale in `chat-list.md`).
- **Löschung mit dem Konto**; Favoriten sind kein Löschblocker. Ein favorisiertes Asset, das nicht mehr
  lesbar ist, erscheint nicht mehr.

### 8. Kein A/B-Parallelbetrieb

Altes und neues Modell laufen nicht nebeneinander. Eine Umschaltung je Person wäre bei geteilten
Spaces sinnlos, eine je Space eine zweite Semantik, die nach der Einführung niemand zurückbaut. Die
Demo-Instanz wird parallel so bestückt, dass jedem Space inhaltlich passendes Wissen zugeordnet ist,
und nach der Umsetzung neu aufgesetzt (#2096, Seed #2103).

## Konsequenzen

**Einfacher:**

- **Eine Regel für alle Typen:** Was zugeordnet und lesbar ist, ist im Space nutzbar. Keine Stufe, die
  vom Zuordnungsstand abhängt, kein Unterschied zwischen Wissen und Prompts, kein Schalter je Space.
- **„Sichtbar = lesbar":** Name, Kennung und Anzahl nicht lesbarer Assets werden nirgends mehr
  preisgegeben — weder im Katalog noch in einer Auswahl, den Space-Einstellungen oder einer
  API-Antwort. Es bleibt allein der zahlen- und namenlose Hinweis aus Entscheidung 2.
- **Ein Einstieg statt vier**, ein Kachelbaustein statt dreier fast gleicher Übersichten; ein neuer
  Typ braucht einen Registry-Eintrag.
- **Weniger Datenmodell:** `listed`, `listed_cap`, `accessible` und ein Audit-Ereignis entfallen, der
  nie gebaute Strikt-Modus samt Folgezuständen wird nicht mehr gebaut.

**Schwieriger oder teurer:**

- **Kuratierende werden zum Nadelöhr.** Eine Freigabe an „Alle Konten" wirkt im Chat erst, wenn
  jemand das Asset dem Space zuordnet. Bewusst angenommen; der Ausgleich steht in Entscheidung 4.
- **Neue Konten und neue Spaces starten leer.** Bewusst angenommen; der Hinweis führt direkt in die
  Zuordnung.
- **Zwei Mitglieder desselben Space erhalten unterschiedliche Antworten**, wenn ihre Leserechte am
  Zugeordneten verschieden sind. Das galt schon vorher; neu ist der Hinweis ohne Anzahl „Nicht alle
  zugeordneten Inhalte sind für Sie lesbar." Wie viel fehlt, erfährt das Mitglied bewusst nicht.
- **Die Zusage an die Personalvertretung ist schwächer** (Entscheidung 6).
- **API-Bruch** in `assets.yaml`, `libraries.yaml`, `prompts.yaml`, `search.yaml`, `chats.yaml`,
  `spaces.yaml` und `audit.yaml`; tragbar, weil alle Installationen neu aufgebaut werden.
- **Die Historie verliert die `listed`-Intervalle** (Entscheidung 3).

**Außerhalb dieses ADR:**

- **Persönliche Spaces abschaffen** — Folge-Issue #2099. Bis dahin bleiben sie, wie
  `spaces-and-assets.md` sie beschreibt; nur ihr Suchbereich folgt dieser Entscheidung.
- Ein Marketplace über Organisationsgrenzen, Freigabe- und Prüfworkflow, Versionierung, neue
  Asset-Typen.
- Ein Vorschlag „weiteres Wissen zuordnen", wenn die Suche im Zugeordneten nichts findet — bei Bedarf
  ein Folge-Issue.

## Verworfene Alternativen

### Modus je Space („alles Lesbare" / „nur Zugeordnetes")

Die Ist-Analyse im Epic schlug ihn als Weg für einen Parallelbetrieb und als Dauerlösung für
persönliche Spaces vor. Verworfen: Er erhält genau die Stufung, die nicht verständlich ist, nur als
Schalter statt als Zustand — und jeder Chat müsste wieder erklären, in welchem Modus sein Space steht.

### `listed` behalten, aber nur für Lesbare

Ein Merkmal „im Katalog hervorgehoben" für ohnehin Lesbares wäre Kuratierung ohne Rechtewirkung. Das
leisten Favoriten (persönlich) und die Zuordnung (je Space) bereits; ein drittes, organisationsweites
Hervorheben hat keinen Anwendungsfall, den jemand benannt hat.

### Die Übergangsregel nur für persönliche Spaces behalten

Sie hätte die Zusage aus Entscheidung 6 gerettet. Verworfen, weil dann persönliche und gemeinsame
Spaces wieder unterschiedlich suchen — die Sonderrolle, die `spaces-and-assets.md` mit dem Wegfall der
Space-Arten gerade abgeschafft hat, und eine Regel, die beim ersten Hinzuziehen einer Kollegin
unbemerkt kippt.

### Fremdzugangs-Tokens an einen Space binden

Hätte die Gleichheitszusage aus ADR-0035 wörtlich erhalten. Verworfen am 02.10.2026: Die
Token-Auswahl ist bereits eine ausdrückliche, unveränderliche Auswahl, und ein Space als Umweg dorthin
bände den Kanal an einen Arbeitsraum, dessen Zuordnung sich ändert, ohne dass das Token davon weiß.

## Nachtrag (10/2026, #2129): kompaktere Kachel, eine Filterzeile, feste Reihenfolge

Nach der Umsetzung hat der Maintainer am 03.10.2026 den Katalog verschlankt. Es ändern sich:

- **Entscheidung 1, Kachel:** Die Sichtbarkeit steht nicht mehr als Textetikett, sondern als
  Welt-Symbol nur bei öffentlichen Assets; ein geschlossenes trägt kein Symbol. Die eigene Rolle
  steht nur noch auf der Detailseite. Die Sortierauswahl entfällt; die Reihenfolge ist fest:
  Favoriten zuerst, dann nach Name.
- **Entscheidung 1, Filter:** Die Filter „Sichtbarkeit" und „aus meinen Gruppen" entfallen. Es
  bleibt eine Filterzeile aus Suche, Typ und Favoriten, die Katalog, Space-Assistent,
  Space-Einstellungen und Token-Auswahl gleich führen. Mit dem Gruppenfilter entfallen der
  API-Parameter `fromMyGroups` des Katalogs und das gleichnamige Kennzeichen in
  `eligible-libraries`; mit der Sortierauswahl der Parameter `sort`.
- **Entscheidung 4, Ausgleich der Kuratierungslast:** Die Kachelauswahl filtert nach Suche, Typ und
  Favoriten. „In Space verwenden" liegt im Menü „⋯" der Kachel; vom Katalog bis zur Zuordnung sind
  es damit drei Klicks statt höchstens zwei. Auf der Detailseite bleibt es ein eigener Knopf.

Begründung: Gruppenherkunft und Sichtbarkeit beantworten selten gesuchte Fragen und machten die
Kachel lang; die Favoriten leisten die persönliche Eingrenzung, und die Aktion „In Space verwenden"
braucht keine eigene Knopfzeile auf jeder Kachel.

## Verwandte Dokumente

- [Epic #2070](https://github.com/criew/opaa/issues/2070) — Ist-Analyse, Stakeholder-Bewertung,
  Konsistenzinventar
- [ADR-0037: Organisationsweite Reichweite als Freigabe an „Alle Konten"](0037-reichweite-als-freigabe-an-alle.md)
- [ADR-0036: Berechtigungsmodell](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md)
- [ADR-0035: Fremdzugänge](0035-fremdzugaenge-mcp-server-und-zugangstokens.md)
- [Spaces, Assets & Zugangskontrolle](../features/spaces-and-assets.md)
- [Gestaltungsleitlinien](../design/guidelines.md)
