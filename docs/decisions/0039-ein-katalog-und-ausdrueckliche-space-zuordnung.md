# ADR-0039: Ein Katalog und ausdrückliche Space-Zuordnung — `listed` entfällt, die Zuordnung ist eine harte Grenze

## Status

Akzeptiert · zuletzt geändert 2026-10-14 · ändert [ADR-0037](0037-reichweite-als-freigabe-an-alle.md)
(Entscheidung 4 und die `listed`-Teile von 5, 6, 7 und 9),
[ADR-0036](0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) (Entscheidung 9, Begründung) und
[ADR-0035](0035-fremdzugaenge-mcp-server-und-zugangstokens.md) (Entscheidung 5, Gleichheitszusage)

## Kontext

Assets hatten vier Einstiege mit überlappender Bedeutung: „Wissen", „Prompts", ein „Katalog", der
Lesbares **vereinigt mit** gelisteten Assets zeigte (das Schaufenster aus ADR-0037, Entscheidung 4,
ohne Anfrageweg), und die Space-Einstellungen. Dazu galt eine Regel, die in keinem ADR stand: Ein Space
ohne Zuordnung durchsuchte alles, was die Person lesen darf, erst eine gesetzte Zuordnung verengte. Für
Prompts war die Zuordnung keine Grenze. Ein Strikt-Modus je Space war spezifiziert, aber nie gebaut.

In der Abstimmung vom 30.09.2026 war das Ergebnis eindeutig: Die Stufung „ohne Zuordnung alles, mit
Zuordnung eingeengt" ist aus Nutzersicht nicht verständlich, und das Schaufenster war die einzige Stelle,
an der die Existenz nicht lesbarer Assets sichtbar wurde. Sechs Stakeholder-Rollen urteilten „tragfähig
mit Auflagen" (Epic #2070); die Auflagen sind eingearbeitet.

## Entscheidung

### 1. Ein Einstieg „Katalog", der nur Lesbares zeigt

Die Hauptnavigation hat für Assets genau einen Einstieg, den **Katalog** (`GET /api/v1/catalog`,
`AssetCatalogService`): alle Asset-Typen, alles, was die Person lesen darf, und nichts sonst. Ein neuer
Typ braucht einen Eintrag in der Typ-Registry (`AssetTypeDefinition`), keinen Menüpunkt.

- Nur Kacheln: Typ-Badge mit Icon, Welt-Symbol bei öffentlichen Assets, Name, Beschreibung,
  Eigentümer, Stand oder Status, Zahl der Spaces mit Zuordnung. Die eigene Rolle steht auf der
  Detailseite. Feste Reihenfolge: Favoriten zuerst, dann nach Name.
- Eine Filterzeile aus Suche, Typ und Favoriten, gleich in Katalog, Space-Assistent,
  Space-Einstellungen und Token-Auswahl.
- „Neu" ist generisch: Typwahl als Kacheln, angeboten nur für Typen mit Anlegerecht, dann der typeigene
  Assistent. Kachelauswahl mit Icon ist das durchgängige Muster für jede Auswahl aus einer überschaubaren
  Menge (`docs/design/guidelines.md`).

### 2. Sichtbarkeit: öffentlich oder geschlossen, abgeleitet aus den Freigaben

Ein Asset ist **öffentlich** (Freigabe an „Alle Konten", ADR-0037) oder **geschlossen** (nur über
Freigaben an Personen oder Gruppen erreichbar); `CatalogVisibility` ist kein Feld, sondern abgeleitet.
Keinen Anfrage- oder Genehmigungsweg. **Die Existenz eines nicht lesbaren Assets wird nicht
preisgegeben:** weder Name, Beschreibung, Kennung noch **Anzahl**, in keiner Auswahl, Space-Einstellung
oder API-Antwort. Einzige Auskunft, innerhalb eines Space, dem die Person angehört: der Hinweis ohne
Anzahl und Namen „Nicht alle zugeordneten Inhalte sind für Sie lesbar." in den Space-Einstellungen und,
wenn deshalb nichts durchsucht werden kann, im Chat. In der Oberfläche heißt es „Für alle" /
„Eingeschränkt", weil „öffentlich" in Behörden nach Internet klingt.

### 3. `listed` entfällt vollständig — auch in Historie und Audit

`assets.listed`, `listed_cap`, `requireListedWithinLimits`, das Antwortfeld `accessible` und der
Schalter „Im Katalog auffindbar" sind ersatzlos entfallen. **Sichtbar ist gleich lesbar.** Die Spalte
`listed` ist auch aus `asset_visibility_history` entfernt, `ASSET_VISIBILITY_CHANGED` hat keinen
Schreiber mehr (der Enum-Wert bleibt für alte Protokollzeilen); bestehende Intervalle wurden nicht überführt, weil alle Installationen neu aufgebaut wurden. Der
Nachweis „war das Asset gelistet?" hat nie Zugriff belegt; „wer konnte lesen?" beantwortet die
Grant-Historie unverändert. `asset_visibility_history` bleibt für die Fremdzugangsfreigabe (ADR-0035);
die Freigabe-Obergrenze (#797) besteht nur noch aus `all_accounts_grant_allowed`.

### 4. Die Space-Zuordnung ist für alle Asset-Typen eine harte Grenze

**Ein Space enthält genau, was ihm zugeordnet ist.** Nutzbar ist, was zugeordnet **und** für die Person
lesbar ist, serverseitig durchgesetzt (`SpaceAssetAssociationService`):

| Typ | Was die Zuordnung begrenzt |
|---|---|
| Wissensbibliothek | Suchbereich des Chats, Chip „@Space-Wissen", konkrete `@`-Chips — ein Chip auf eine nicht zugeordnete Bibliothek ist nicht möglich |
| Prompt-Bibliothek | `/`-Angebot (`GET /api/v1/prompts/available` verlangt den Space) und Einsetzen (`usedPromptId` gegen die Zuordnung geprüft) |
| Agent (Zielbild) | das Angebot im Chat; die eigene Wissensbindung eines Agenten bleibt unberührt |

- Die Übergangsregel entfällt, auch für den persönlichen Standard-Space: Ein Space ohne Wissen
  durchsucht kein Wissen und startet leer. **Kein stiller Leerlauf:** Chat und Space-Seite zeigen einen
  Hinweis mit Direktlink zur Zuordnung (für `CURATOR`/`ADMIN`; andere erfahren, wer zuordnen kann); zwei
  getrennte Signale ohne Zahlen — „kein Wissen zugeordnet" und „zugeordnet, aber nichts davon lesbar".
  Eine Antwort ohne Quellen bleibt möglich, deutlich gekennzeichnet.
- Der Strikt-Modus entfällt samt der nie gebauten Bestandteile (strikt-only, Aufrufverweigerung,
  Ablehnung einer Mitgliederaufnahme, „Voraussetzung verletzt"); gegen das Ableitungsleck bleiben
  Herkunftsverfolgung, Hinweis im Teilen-Dialog, rechtegeprüfte Sprungmarken und Benachrichtigung des
  Eigentümers (`spaces-and-assets.md`).
- Die Zuordnung gewährt keinen Zugriff: Zuordnen darf, wer `CURATOR` ist und das Asset lesen kann;
  jedes Mitglied sieht nur die für es lesbaren Zuordnungen. Sie wird nicht historisiert; was ein Chat
  durchsucht hat, wird nicht protokolliert.
- Ausgleich für die Kuratierungslast: Der Space-Assistent ordnet aus derselben Kachelliste zu (Schritt
  überspringbar); „In Space verwenden" liegt im Menü der Kachel und auf der Detailseite; die
  Benachrichtigung der Eigentümer bei Zuordnung zu einem gemischten Space (ADR-0019) gilt für alle Typen
  und wird gebündelt.

### 5. Fremdzugang und Suche ohne Space bleiben bei ihrer eigenen Auswahl

Der Fremdzugang (MCP-Server, Zugangstoken) bleibt ohne Space: lesbar ∩ Fremdzugangsfreigabe ∩
Bibliotheksauswahl des Tokens (ADR-0035, Entscheidung 5), mit derselben Kachelauswahl wie der
Space-Assistent. Ebenso `POST /api/v1/search`: lesbare Bibliotheken, enger über die mitgegebene
Auswahl. Die Zusage aus ADR-0035 lautet: Beide Kanäle laufen über denselben Suchweg mit derselben
Rechteprüfung; was sie durchsuchen, bestimmt im Chat die Zuordnung des Space, im Fremdzugang die Auswahl
des Tokens. Eine nicht lesbare Bibliothek findet die Person in keinem Kanal.

### 6. Geänderte Zusage gegenüber der Personalvertretung

Zurückgenommen ist: „Ein Raum, in dem jemand allein arbeitet, steht fachlich nicht schlechter da als
ein gemeinsamer — der Suchbereich umfasst dort alles, was die Person lesen darf." Jeder Raum durchsucht
genau das, was ihm zugeordnet ist. **Unverändert:** Private Chats sind unbeobachtet, und ein privater
Chat hat denselben Suchbereich wie ein geteilter im selben Space. Die Änderung ist der
Personalvertretung vor dem Rollout als geänderte Zusage vorzulegen.

### 7. Favoriten sind privat

Jede Person markiert sichtbare Assets als Favorit (`asset_favorites`, `PUT …/favorite`); Favoriten
stehen im Katalog oben und sind Filter in Katalog, Space-Assistent und Token-Auswahl. Weil eine
Favoritenliste ein Interessenprofil ist: abrufbar nur für die Person selbst, keine Zählung oder Anzeige
je Asset, auch nicht aggregiert, kein Protokolleintrag, keine Historie, nicht in Berichten und Exporten
an Dritte, enthalten nur in der Selbstauskunft der Person; Löschung mit dem Konto, kein Löschblocker.
Ein nicht mehr lesbares Asset erscheint nicht mehr.

### 8. Kein A/B-Parallelbetrieb

Altes und neues Modell laufen nicht nebeneinander; die Demo-Instanz wurde mit passenden Zuordnungen neu
aufgesetzt (#2096, #2103).

## Konsequenzen

**Einfacher:** eine Regel für alle Typen; „sichtbar = lesbar"; ein Einstieg statt vier; weniger
Datenmodell (`listed`, `listed_cap`, `accessible`, Strikt-Modus).

**Schwieriger:** Kuratierende sind das Nadelöhr — eine Freigabe an „Alle Konten" wirkt im Chat erst nach
Zuordnung. Neue Konten und Spaces starten leer. Zwei Mitglieder eines Space erhalten verschiedene
Antworten, wenn ihre Leserechte verschieden sind; wie viel fehlt, erfährt das Mitglied bewusst nicht.
Die Zusage an die Personalvertretung ist schwächer (Entscheidung 6). Die Historie hat keine
`listed`-Intervalle mehr.

**Außerhalb:** Marketplace über Organisationsgrenzen, Freigabe- und Prüfworkflow, Versionierung, neue
Asset-Typen; ein Vorschlag „weiteres Wissen zuordnen" bei leerer Suche.

## Verworfene Alternativen

- Modus je Space („alles Lesbare" / „nur Zugeordnetes") — erhält die unverständliche Stufung als Schalter.
- `listed` nur für Lesbares behalten — Kuratierung ohne Rechtewirkung, die Favoriten und Zuordnung schon leisten.
- Übergangsregel nur für den persönlichen Space — persönliche und gemeinsame Spaces suchten wieder verschieden.
- Fremdzugangs-Tokens an einen Space binden — bände den Kanal an einen Raum, dessen Zuordnung sich ohne Wissen des Tokens ändert.
- Kachel mit Sichtbarkeits-Etikett, Rolle, Sortierauswahl und Filter „aus meinen Gruppen" (#2129) — selten gesuchte Fragen, lange Kachel.

## Offen

—
