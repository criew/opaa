# Design-Guidelines

Verbindliche Gestaltungsregeln der OPAA-Weboberfläche. Sie gelten für jedes UI-Issue und jeden
Frontend-PR; Abweichungen werden im PR begründet. Quelle der Werte sind die Zielbild-Mockups
([OPAA Mockups.html](<./OPAA Mockups.html>), Seiten 1a–1i) und die Zielbild-Beschreibung
([redesign-prompt.md](./redesign-prompt.md)). Die technische Umsetzung als Token-Ebene und
MUI-Theme ist Gegenstand von Issue #581; die Barrierefreiheits-Richtlinie
([accessibility.md](./accessibility.md)) ergänzt dieses Dokument um die verbindliche Prüfliste.

**Beide Farbschemata sind gleichrangig.** Jede Regel in diesem Dokument definiert das helle und
das dunkle Schema. Ein PR, der nur eines von beiden gestaltet, ist unvollständig.

---

## 1 · Grundhaltung

**Ruhig und wertig.** Sachlich, mit Sorgfalt in Typografie, Weißraum und Detail — ein gut
gemachtes Werkzeug, kein verordnetes Fachverfahren. Daraus folgen vier Grundsätze:

1. **Rahmen statt Schatten.** Flächen trennen sich durch 1-px-Rahmen und abgestufte
   Flächenfarben, nicht durch Schlagschatten. Schatten sind schwebenden Ebenen vorbehalten
   (Menüs, Dialoge, Belegfenster).
2. **Farbe ist Bedeutung.** Blau markiert Handlung und Bezug (Schaltflächen, Links,
   Fußnoten-Ziffern, aktive Zustände). Die Semantikfarben stehen ausschließlich für Erfolg,
   Warnung und Gefahr. Nichts davon wird dekorativ eingesetzt.
3. **Hierarchie durch Größe und Gewicht,** nicht durch zusätzliche Farben oder Kästen.
4. **Bewegung erklärt, sie schmückt nicht.** Kurz, gerichtet, abschaltbar
   (`prefers-reduced-motion`).

---

## 2 · Farben

### 2.1 Skalen

Die Skalen sind der Vorrat, aus dem die semantischen Rollen (2.2) schöpfen. **Komponenten greifen
nie direkt auf Skalenwerte zu** — sie verwenden Rollen. Ausnahmen sind in 2.3 abschließend
aufgezählt.

**Blau** — Akzent- und Handlungsfarbe:

| Stufe | Wert      | Stufe | Wert                                 |
| ----- | --------- | ----- | ------------------------------------ |
| 50    | `#E7F4FE` | 500   | `#1292EE` _(Basis)_                  |
| 100   | `#C6E3FC` | 600   | `#0F80D6` _(Hover, −8 % Helligkeit)_ |
| 200   | `#9BCEFA` | 700   | `#0B6FBC` _(Aktiv/Gedrückt, −16 %)_  |
| 300   | `#61B5F6` | 800   | `#085B9C`                            |
| 400   | `#349EF2` | 900   | `#05447A`                            |

**Navy** — Struktur- und Textfarbe:

| Name     | Wert      | Verwendung                                          |
| -------- | --------- | --------------------------------------------------- |
| Navy     | `#012142` | Primärtext (hell), dunkle Grundfläche               |
| Navy-900 | `#00152D` | Überlagerungen, tiefste Fläche                      |
| Navy-700 | `#02305E` | erhöhte Fläche im dunklen Schema                    |
| Navy-600 | `#034079` | gedämpfte Fläche im dunklen Schema                  |
| Navy-500 | `#055396` | Grenzfälle, Diagramme                               |

**Grau** (kühl, auf Navy abgestimmt): `#E6EBF1` (100), `#CBD4DF` (200), `#A4B1C1` (300),
`#778797` (400), `#556473` (500), `#3B4958` (600), `#26323F` (700), `#162231` (800).

**Weißtöne:** Weiß `#FFFFFF` · Off-White `#F6F8FB` (helle erhöhte Fläche) · Smoke `#EEF2F7`
(gedämpfte Fläche, Trennlinien).

**Carbon** — neutrale Dunkel-Skala des dunklen Schemas (#654, angelehnt an das dunkle Schema
der Claude-Docs-Website, erhoben am 20.08.2026). Navy bleibt der Block der globalen Leiste und
der Anmeldeseite im hellen Schema:

| Name       | Wert      | Verwendung                      |
| ---------- | --------- | ------------------------------- |
| Carbon-950 | `#09090B` | Seitengrund dunkel              |
| Carbon-900 | `#171717` | erhöhte Fläche dunkel           |
| Carbon-850 | `#1F1F1F` | gedämpfte Fläche dunkel         |
| Carbon-800 | `#252525` | Standardrahmen dunkel           |
| Carbon-700 | `#333333` | betonter Rahmen, Tooltip dunkel |

**Semantik:** Gefahr, Warnung und Erfolg sind Rollen je Schema (`danger`, `warning`, `success`,
siehe 2.2), weil sie auch als Textfarbe dienen. Gefahr: Hell `#CE2C31`, Dunkel `#FF6369` (#2150;
der frühere gemeinsame Wert `#E5484D` erreichte als Text auf Weiß nur 3,91:1). Warnung: Hell
`#8F5A00`, Dunkel `#F5B83D`; Erfolg: Hell `#11774F`, Dunkel `#16B77B` (#2493; die früher in
beiden Schemata gemeinsamen Werte `#F5B83D` und `#16B77B` erreichten als Text auf Weiß nur
1,78:1 bzw. 2,59:1).

### 2.2 Semantische Rollen

Die Rollen sind das Vokabular aller Komponenten. Werte je Schema:

| Rolle            | Bedeutung                                                        | Hell                | Dunkel               |
| ---------------- | ---------------------------------------------------------------- | ------------------- | -------------------- |
| `bg-1`           | Seitengrund                                                      | Weiß `#FFFFFF`      | Carbon-950 `#09090B` |
| `bg-2`           | erhöhte Fläche (Karte, Kopfzeile)                                | Off-White `#F6F8FB` | Carbon-900 `#171717` |
| `bg-3`           | gedämpfte Fläche (Eingabefeld, Tabellenkopf)                     | Smoke `#EEF2F7`     | Carbon-850 `#1F1F1F` |
| `fg-1`           | Primärtext                                                       | Navy `#012142`      | `#DEDEDE`            |
| `fg-2`           | Sekundärtext                                                     | Grau-600 `#3B4958`  | `#9E9E9E`            |
| `fg-3`           | Tertiärtext, Metadaten                                           | Grau-500 `#556473`  | `#8A8A8A`            |
| `accent`         | Handlung, Bezug, aktiver Zustand (Text, Ziffern, Rahmen)         | Blau-700 `#0B6FBC`  | Blau-500 `#1292EE`   |
| `accent-surface` | Fläche gefüllter Aktionen (primäre Schaltfläche, gefüllter Chip) | Blau-700 `#0B6FBC`  | Blau-700 `#0B6FBC`   |
| `accent-fg`      | Text auf `accent-surface`                                        | Weiß                | Weiß                 |
| `border`         | Standardrahmen                                                   | Grau-100 `#E6EBF1`  | Carbon-800 `#252525` |
| `border-strong`  | betonter Rahmen (Eingaben, Tabellen)                             | Grau-200 `#CBD4DF`  | Carbon-700 `#333333` |
| `danger`         | Gefahr: zerstörende Aktion, Fehler (Text, Icon, gefüllte Fläche) | `#CE2C31`           | `#FF6369`            |
| `danger-hover`   | Hover/Gedrückt gefüllter Gefahrflächen, gefüllte Fehlermeldung   | `#901E22`           | `#FF7A80`            |
| `danger-soft`    | hellere Gefahr-Variante (MUI `error.light`)                      | `#D23B40`           | `#FF8287`            |
| `warning`        | Warnung: folgenreich, aber umkehrbar (Text, Icon, Kante, Fläche) | `#8F5A00`           | `#F5B83D`            |
| `success`        | Erfolg: abgeschlossen, in Ordnung (Text, Icon, Punkt, Fläche)    | `#11774F`           | `#16B77B`            |

`warning` und `success` führen wie `danger` je eine Hover- und eine hellere Stufe (MUI `dark` und
`light`): Warnung hell `#6B4300`/`#9A6100`, dunkel `#F7C35D`/`#F8CC73`; Erfolg hell
`#0B5A3B`/`#0E7A52`, dunkel `#38C18F`/`#5BCB9F`. Die Werte stehen in
`frontend/src/shared/theme/tokens.ts` (`SignalTones`).

`fg-3` im hellen Schema ist Grau-500, nicht Grau-400 (#725): Grau-400 (`#778797`) erreicht gegen
Weiß nur 3,68:1 — unter der 4,5:1-Anforderung für Fließtext (2.4) — und lag als Tertiärtext in
der Wissensbibliotheken-Tabelle (Spaltenkopf, Metadaten) sichtbar unter der Schwelle. Grau-500
erreicht 6,08:1 gegen Weiß und bleibt auch gegen `bg-2`/`bg-3` klar über 4,5:1; die Grau-Skala
selbst (2.1) bleibt dabei unverändert.

Das Rollenset auf Navy-Basis (`navyRoles`: Flächen Navy-800/700/600, Text
Weiß/`#B9C6D4`/`#7A8BA0`) trägt seit #1922 nur noch **den Markenblock der Anmeldeseite**
(`AuthLayout.tsx`) — die Werte des früheren dunklen Schemas, auf diese eine Fläche begrenzt
(#654). Die Seitenleiste des Space nutzt es nicht mehr (siehe 2.3).

Die globale Leiste (Rail, #786, Mockup 2a) verwendet im hellen Schema das Rollenset `railRoles`
(Grund Navy-900, Hover Navy-800, Aktivkachel Navy-700 mit Navy-600-Rahmen, Text
Weiß/`#99A1AB`/`#7A8BA0`) — sie ist damit die einzige dunkle Navigationsebene und hebt sich von
allem rechts davon ab. Im dunklen Schema folgt die Rail dem Carbon-Schema; Carbon hat keine
dunklere Stufe, die Trennung übernimmt der Standardrahmen.

`fg-3` (`#7A8BA0`) erreicht in `navyRoles` gegen `bg-1` 4,65:1, gegen `bg-2` 3,80:1 und gegen
`bg-3` 2,99:1; in `railRoles` (eine Stufe dunklere Flächen) gegen `bg-1` 5,26:1, gegen `bg-2`
4,65:1 und gegen `bg-3` 3,80:1 — je Rollenset unterschreitet nur `bg-3` (und im Navy-Set
zusätzlich `bg-2`) die 4,5:1-Schwelle (#853). Das ist folgenlos, weil `fg-3` dort nie als Text
auf Hover- oder Aktivflächen landet: Rail-Kacheln zeigen im Hover `fg-2` (inaktiv, 6,20:1 gegen
`bg-2`) bzw. `fg-1` (aktiv, Weiß) — nie `fg-3`. Eine künftige Komponente, die `fg-3` als Text auf
`bg-2`/`bg-3` dieser Rollensets einsetzt, verletzt 2.4 und braucht einen dunkleren Ton oder eine
andere Rolle.

### 2.3 Regeln

- **Nur Rollen in Komponenten.** Kein Hex-Wert und kein Skalenwert in Komponenten-Code; alles
  läuft über die Rollen aus 2.2. Zulässige Ausnahmen: der Markenblock der Anmeldeseite und die
  globale Leiste (siehe nächste Punkte), Diagramm-Farbreihen, Hover-/Aktiv-Stufen von Blau in
  Schaltflächen-Definitionen.
- **Untermenüleisten sind hell und überall gleich** (#1922). Die Seitenleiste des Space und die
  Sekundärspalte der globalen Bereiche tragen dieselben Rollen des Anwendungsschemas: Fläche
  `bg-2`, Kante `border-strong`, angehobene Karte auf `bg-1`: der Aktiveintrag, in der festen
  Spalte des Space also die Zeile des aktiven Space (5.13); der Space-Umschalter gilt als
  angehobene Karte nur noch in der Schublade.
  Text läuft über `text.secondary` (= `fg-2`), nicht über hart codierte Weißtöne. Bis #1922 war
  die Seitenleiste im hellen Schema ein Navy-Block mit eigenem Rollenset (`navyRoles`) und zwei
  hart codierten Alpha-Weißtönen; beides ist ersatzlos entfallen — ein Navy-Block neben einer
  hellen Bereichsspalte las sich als zwei verschiedene Anwendungen.
- **Die globale Leiste ist die einzige dunkle Navigationsebene** (Rollenset `railRoles`, #786;
  seit #1922 ohne den Navy-Block der Seitenleiste daneben, der bis dahin die Zwischenstufe
  bildete). Ihre Aktivkachel (Navy-700 auf Navy-900, Rahmen Navy-600) liegt als Flächenkontrast
  unter 3:1 — zulässig, weil der Zustand nicht allein über die Fläche getragen wird: die
  Textfarbe wechselt auf Weiß und `aria-current` zeichnet den Eintrag programmatisch aus
  (siehe 2.4).
- **Der globale Verwaltungsrahmen** (#787, Mockup 2b) nutzt ausschließlich die Rollen aus 2.2:
  Sekundärspalte auf `bg-2`, Aktivkarte auf `bg-1` mit `border-strong`-Rahmen. Auch hier liegt
  die Zustandsfläche unter 3:1 — getragen wird der Zustand wie bei der Rail über Textfarbe,
  Schriftgewicht und `aria-current`. Das **„Global"-Badge** ist ein Etikett wie jedes andere
  (5.5): Umriss in `border-strong`, Text in `fg-2`; der Geltungsbereich steht als sichtbares Wort,
  nie als Farbe.
- **Akzent ist austauschbar.** Die Branding-Konfiguration (Issues #582/#583) darf `accent`
  ersetzen. Deshalb darf keine Komponente sich auf „Blau" verlassen (z. B. Blau hart mit einem
  Icon mischen) — sie verlässt sich auf die Rolle.
- **Semantikfarben tragen Bedeutung, keine Stimmung.** Ein als nicht bestätigt gekennzeichneter Beleg
  ist keine Warnung und erhält keine Signalfarbe (siehe redesign-prompt.md §4).
- **Signalfarbe markiert, der Satz spricht.** In Zuständen, Hinweisen und Bestätigungen steht
  die Signalfarbe in Punkt, Symbol oder Kante; der Fließtext daneben bleibt in `fg-*`. Die
  Bedeutung trägt der Satz, die Farbe zeigt nur, wo er hingehört. Ausnahme ist ein knapper
  Zustand ohne eigenes Zeichen, etwa der Grund unter einer gesperrten Kachel (5.11) — dort ist
  die Rolle selbst die Textfarbe und hält 2.4.
- **Markierung von Suchtreffern** liegt auf dem eigenen Ton `markHighlight` (blasses Amber zu
  25 %, in beiden Schemata gleich), nicht auf einer Signalrolle; der Text darauf bleibt `fg-1`.

### 2.4 Kontrast

Text mindestens 4,5:1 gegen seine Fläche, große Schrift (ab 24 px bzw. 19 px fett) und
UI-Komponenten/Grafik mindestens 3:1 — in beiden Schemata. Die Rollen aus 2.2 erfüllen das in
den vorgesehenen Kombinationen (`fg-*` auf `bg-*`, `accent-fg` auf `accent-surface`, `accent`,
`danger`, `warning` bzw. `success` als Text auf `bg-1..3` des eigenen Schemas, `bg-1` als Text
auf jeder Stufe von `danger`, `warning` und `success`); wer andere Kombinationen bildet, weist
den Kontrast im PR nach.

**Hinweis-, Warn- und Statusfarben sind Textfarben.** Text in `danger`, `warning` oder `success`
— Sperrgründe, Hinweise, Etiketten — erreicht mindestens 4,5:1 (große Schrift 3:1) gegen seine
Fläche, im hellen und im dunklen Schema. Er steht in der Rolle selbst, nie in einem Skalenwert,
einem Alpha-Ton oder unter gesenkter Deckkraft; ein gesperrtes Element darf ausgegraut sein,
seine Begründung nicht (5.11). Wann Signalfarbe überhaupt im Text steht, regelt 2.3.
`frontend/src/shared/theme/theme.test.ts` weist die Rollen nach, die Barrierefreiheitsprüfung der
E2E-Suite misst die gescannten Seiten (#2493).

Der Hover-Zustand gefüllter Flächen zählt mit: Er behält die Textfarbe der Fläche, und
deshalb wird er bei dunklem Text heller statt dunkler. Im hellen Schema tragen Gefahr, Warnung
und Erfolg Weiß, ihr Hover ist dunkler (`danger-hover` `#901E22` mit 8,77:1); im dunklen Schema
tragen sie dunklen Text (`bg-1`), ihr Hover ist heller (`danger-hover` `#FF7A80` mit 7,91:1)
(#2150, #2493). `accent-fg` auf `accent` ist seit #634 **keine** nachgewiesene Kombination
mehr — Weiß erreichte auf Blau-500 nur 3,3:1; gefüllte Flächen liegen deshalb auf
`accent-surface` (Blau-700, 5,2:1), und das helle Schema führt `accent` selbst als Blau-700,
damit Akzent-Text auf Weiß 4,5:1 hält (dunkles Schema: Blau-500 auf Carbon-950 = 6,0:1). Ausnahme: `fg-3` in `navyRoles`/`railRoles`
erfüllt das nicht gegen `bg-2`/`bg-3` (siehe 2.2) — dort ist `fg-3` als Text ausgeschlossen, nicht
nachgewiesen. Details regelt die [Barrierefreiheits-Richtlinie](./accessibility.md).

---

## 3 · Typografie

### 3.1 Schriftentscheidung

| Rolle                            | Schrift            | Lizenz                                                      |
| -------------------------------- | ------------------ | ----------------------------------------------------------- |
| Fließtext & Überschriften        | **Quicksand**      | SIL Open Font License — im Repo via `@fontsource/quicksand` |
| Rückfall im Stapel               | Inter              | SIL Open Font License                                       |
| Mono (Aktenzeichen, Werte, Code) | **JetBrains Mono** | SIL Open Font License                                       |

Die Zielbild-Mockups verwenden „Sklow", eine Firmenschrift ohne freie Lizenz. **Sie wird nicht
ins Repository aufgenommen.** Quicksand (#658) ist ihre freie Entsprechung — rund-geometrisch,
einstöckiges g, monolineare Strichführung — und damit Standard der offenen Codebasis; Inter
bleibt als Rückfall im Stapel. Eine Firmenschrift kann ein Betreiber
später über die Branding-Konfiguration nachladen; die Schriftstapel enden deshalb immer in
`system-ui, sans-serif`.

### 3.2 Skala

Feste Pixelstufen; die App nutzt im Alltag 11–30 px, die großen Stufen gehören Markenmomenten
(Anmeldeseite, Leerzustände):

| Stufe   | Größe     | Typische Verwendung                  |
| ------- | --------- | ------------------------------------ |
| 2xs     | 11 px     | Versal-Etiketten, Tabellen-Metadaten |
| xs      | 12 px     | Metadaten, Chips, Eyebrow            |
| sm      | 14 px     | Sekundärtext, Tabellen, Seitenleiste |
| base    | 16 px     | Fließtext, Chat-Antworten            |
| md      | 18 px     | Chat-Titel, hervorgehobener Text     |
| lg      | 20 px     | h4-Äquivalent                        |
| xl      | 24 px     | Abschnittsüberschriften (h3)         |
| 2xl     | 30 px     | Seitenüberschriften (h2)             |
| 3xl     | 36 px     | große Seitenköpfe                    |
| 4xl–7xl | 48–104 px | Markenblock, nicht im Arbeits-UI     |

Die Arbeitsflächen folgen zusätzlich dem **Feinraster aus Mockup 1a** (#658): Fließtext
14,5 px / 1.65, UI-Listen 13 px, Metadaten 11–12,5 px, Versal-Etiketten 9,5 px / +0.12em,
Seitentitel 27 px, Chat-Kopf 18 px. Das Theme bildet dieses Raster ab; die Skalenstufen oben
bleiben das Vokabular für Markenmomente.

**Titel einer Detailseite** (Asset, Space, Lauf), abweichend vom Seitentitel (27 px): 20 px / 28 px
ab 600 px Breite, darunter 17 px / 22 px. Er steht ungekürzt und trennt lange Wörter (`hyphens: auto`), reserviert aber keine
Leerzeilen. Bei 320 px beginnt die Reiterleiste spätestens 360 px unter der Oberkante.

### 3.3 Gewichte, Zeilenhöhen, Laufweiten

- **Gewichte:** 400 (Regular, Fließtext) · 500 (Medium, Betonung, Schaltflächen,
  Tabellenköpfe) · 600 (SemiBold, Überschriften in Arbeitsflächen) · 700 (Bold, Seitentitel,
  Markenblock). Keine Gewichte über 700. Das Sklow-Zwischengewicht 450 wird mit Inter 500
  wiedergegeben.
- **Zeilenhöhen:** 1.1 (Überschriften ab 2xl) · 1.25 (Überschriften bis xl, mehrzeilige
  UI-Texte) · 1.5 (Fließtext) · 1.7 (lange Lesetexte, z. B. Chat-Antworten).
- **Laufweiten:** −0.02em (Überschriften ab xl) · −0.005em (Fließtext) · +0.08em
  (Versal-Etiketten).
- **Eyebrow-Muster** (Etikett über Überschriften und Tabellenspalten): 11–12 px, Versalien,
  +0.08em, Gewicht 500, Farbe `accent` über Überschriften bzw. `fg-3` in Tabellen.

---

## 4 · Abstände, Radien, Ebenen, Bewegung

### 4.1 Abstände

4-px-Raster, verbindliche Stufen: 4, 8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96, 128.
Zwischenwerte sind unzulässig. Rhythmus statt Gleichmaß: Zusammengehöriges eng (4–12),
Gruppen deutlich getrennt (24–48).

### 4.2 Radien

| Stufe  | Wert      | Verwendung                                    |
| ------ | --------- | --------------------------------------------- |
| xs     | 4 px      | kleine Chips, Tastenkürzel                    |
| sm     | 6 px      | Schaltflächen, Menüeinträge (#658, Mockup 1a) |
| **md** | **10 px** | **Standard: Karten, Eingaben, Dialoge**       |
| lg     | 16 px     | große Karten, Nachrichtenblasen               |
| xl     | 24 px     | Bühnenflächen                                 |
| pill   | 999 px    | Rollen- und Statuschips                       |

### 4.3 Ebenen: Rahmen statt Schatten

- Ebene 0/1 (Seite, Karte): Trennung ausschließlich über `border` und `bg`-Abstufung. **Kein
  Schatten auf ruhenden Karten.**
- Ebene 2 (schwebend: Menü, Popover, @-Vorschlagsliste): Rahmen **plus** dezenter Schatten
  `0 2px 6px rgba(1,32,66,0.08)`.
- Ebene 3 (Dialog, Belegfenster): Rahmen plus `0 8px 24px rgba(1,32,66,0.10)`. Der Grund
  dahinter tritt unter einem Schleier mit 2 px Unschärfe zurück; der Schleier trägt den Ton des
  Schemas — hell `navy-900` mit 42 %, dunkel das neutrale `carbon-950` mit 72 %, nie Navy auf
  dem Carbon-Grund.
- Im dunklen Schema bleiben die Schattenwerte gleich; die Trennung leisten dort vor allem die
  helleren Flächenstufen.

### 4.4 Fokus

Sichtbarer Fokusring für jedes interaktive Element: eine **deckende Linie von 2 px in `accent`
mit 2 px Abstand** zum Element (`outline: 2px solid` plus `outline-offset: 2px`), in beiden
Schemata gleich. Fokus wird nie unterdrückt, nur gestaltet.

**Ausnahme: umrandete Eingaben.** Formularfelder, Auswahllisten, Autocomplete und der Eingaberahmen
des Chats zeigen den Fokus nur über ihren eigenen Rahmen: 2 px in `accent`, mit Fehler 2 px in
`danger` (5.2), ohne Ring. Ein Ring um das Feld liefe durch das Label, das auf der oberen Kante
sitzt. Der Rahmen erreicht als `accent` bzw. `danger` 4,5:1 gegen `bg-1` bis `bg-3` und wechselt
gegenüber der Ruhe Farbe und Breite.

Warum so:

- **Deckend, weil der Ring ein grafisches Kennzeichen ist.** Er braucht 3:1 gegen jede Farbe, an
  die er grenzt (2.4). `accent` erreicht als Textfarbe 4,5:1 gegen `bg-1` bis `bg-3` jedes
  Schemas; eine deckende Linie in `accent` erbt diesen Nachweis, auch für eine konfigurierte
  Hausfarbe, die je Schema abgeleitet wird (7). Ein halbtransparenter Ring nimmt die Farbe der
  Fläche an und blieb mit 32 % Deckung bei etwa 1,6:1.
- **Mit Abstand, weil Akzentflächen den Ring sonst schlucken.** Auf einer primären Schaltfläche
  träfe `accent` auf `accent-surface`, also auf sich selbst. Der Abstand zeigt den Grund, auf dem
  das Element steht, und der Ring grenzt innen wie außen an diesen Grund. In gefüllten Hinweisen
  und Popup-Benachrichtigungen legt MUI unter Abstand und Ring einen 4 px breiten Schatten in
  `bg-1`: Der Abstand und die Innenkante des Rings liegen auf `bg-1`, die Außenkante grenzt an die
  Füllfarbe des Hinweises. Diesen Fall deckt der Kontrasttest nicht ab.
- **Kein weicher Außenring.** Ein Schleier außen an der Linie senkt deren Kontrast genau an der
  Kante, an der er gemessen wird; in `danger` fiel er dort unter 3:1.
- **Nach innen, wo ein Behälter abschneidet.** Menüeinträge, Listeneinträge, Reiter, Optionen
  einer Vorschlagsliste und Zeilen in scrollenden Bereichen tragen den Ring innerhalb ihrer
  Fläche (negativer Abstand); er grenzt dann an deren eigene Füllung, auch getönt bei Auswahl oder
  Hover, und hält dort ebenfalls 3:1. Ein Behälter, der Text kürzt, lässt um seinen Inhalt
  4 px Raum für den Ring.
- **Ohne Layoutversatz.** `outline` nimmt keinen Platz ein. Der Rahmen eines Felds ist absolut
  positioniert und wird ohne Versatz breiter; der Eingaberahmen des Chats legt das zweite Pixel
  als inneren Schatten an (`focusFrameStyle`).

Umsetzung: `focusVisible` im MUI-Theme und `*:focus-visible` für alle übrigen Elemente
(`frontend/src/shared/theme/theme.ts`), Werte in `tokens.ts`; Nachweis gegen alle Flächenrollen in
`frontend/src/shared/theme/focusRing.contrast.test.ts`. Prüfung:
[Barrierefreiheits-Richtlinie](./accessibility.md).

### 4.5 Bewegung

Dauern 120 ms (klein: Hover, Chips), 200 ms (Standard: Menüs, Einblendungen), 360 ms (groß:
Belegfenster, Drawer); Kurve `cubic-bezier(0.22, 1, 0.36, 1)` (ease-out). Nur `transform` und
`opacity` animieren. Unter `prefers-reduced-motion` entfallen Bewegungen bzw. schrumpfen auf
Zustandswechsel.

---

## 5 · Komponentenregeln

### 5.1 Schaltflächen

| Variante           | Ruhe                                      | Hover                      | Aktiv            |
| ------------------ | ----------------------------------------- | -------------------------- | ---------------- |
| Primär             | `accent-surface`-Fläche, `accent-fg`-Text | Blau-800 (−8 % Helligkeit) | Blau-900 (−16 %) |
| Sekundär           | `bg-1` mit `border-strong`                | `bg-2`                     | `bg-3`           |
| Still (Text/Ghost) | transparent, `accent`-Text                | `bg-2`                     | `bg-3`           |

Radius sm, Gewicht 500, 13,5 px, Höhe 34 px (kompakt 28 px), Beschriftung als Verb („Fragen",
„Anlegen", „Speichern"). Genau eine primäre Schaltfläche je Fläche. Auf Navy-Flächen gilt die
Dunkel-Spalte der Rollen. Zerstörende Aktionen: Sekundär-Variante mit Gefahr-Text, niemals eine
rote Primärfläche als Standardaktion.

**Nur-Icon-Schaltflächen** tragen immer beides: ein deutsches `aria-label` mit konkretem Bezug
(„Original von bescheid.pdf öffnen") **und** einen `Tooltip`, der die Funktion beim Zeigen mit
der Maus erklärt („Original öffnen"). Ein Icon allein ist keine Beschriftung — weder für
Sehende, die das Symbol nicht kennen, noch für Screenreader.

**Anlege-Knöpfe** — die Schaltfläche oben rechts über einer Übersicht oder Verwaltungsliste, die
ein neues Objekt anlegt — tragen das Plus-Icon (`Add`) als `startIcon` vor der Beschriftung, und
die Beschriftung nennt das Objekt: „Konto anlegen", „Gruppe anlegen", „Neuer Space", „Neues
Modell", „Neuer Katalogeintrag". Ein bloßes „Neu" oder „Anlegen" genügt nicht; das Icon ist Zierde
und ersetzt die Beschriftung nicht. Folgt eine Typwahl, nennt die Beschriftung den Oberbegriff.
Je Objekt gibt es eine Beschriftung: Seitenkopf, Leerzustand und Menüeinträge wie der
Space-Umschalter der Seitenleiste tragen denselben Text („Neuer Space", „Gruppe anlegen", „Neuer
Anbieter"). Ausgenommen sind die Köpfe „Spaces" und „Chats" der Seitenleiste des Space: Dort steht
das Plus als Nur-Icon-Knopf, dessen zugänglicher Name das Objekt nennt („Neuer Space", „Neuer
Chat"; 5.13).

**Hinzufügen in einem Abschnitt.** Fügt eine Aktion einem bestehenden Objekt etwas hinzu
(Freigabe, Zuordnung, Feld, Ordner), steht sie als Textaktion rechts im Abschnittskopf der
Detailseite (`DetailSection` mit `TextAction`): ohne Plus-Icon, als Verb mit Objekt, wo das Verb
allein nicht eindeutig ist („Freigeben", „In Space verwenden", „Feld anlegen"). Je Abschnitt gibt es
höchstens eine. Ergänzt die Aktion eine Liste innerhalb einer Zeile („Wert ergänzen"), ist sie
ebenfalls eine `TextAction`. Eine Aktion, die den Zustand eines Hinweises behebt („Zugang
zuordnen"), bleibt die Aktion dieses Hinweises.

**Personen und Gruppen hinzufügen.** Die Schaltfläche der Formularzeile aus 5.2 heißt
„Hinzufügen" — in den Space-Mitgliedern, in „Meine Gruppen" und im Mitgliederdialog der
Gruppenverwaltung. Fachlich begründet sind drei andere Verben:

- „Vormerken" im Assistenten (Space, Wissens- und Prompt-Bibliothek): Gespeichert wird erst mit dem
  Anlegen.
- „Freigeben" an einem bestehenden Arbeitsmittel: Die Handlung erteilt eine Berechtigung mit Rolle
  und Befristung, keine Mitgliedschaft.
- „Als verantwortlich benennen" bei internen Gruppen: Verantwortliche werden nicht Mitglied.

Die Schaltfläche bleibt ohne Auswahl aktiv (Prüfliste P28). Fehlt die Auswahl, steht „Bitte eine
Person oder Gruppe auswählen." (bzw. „Bitte eine Person auswählen.") am Suchfeld, und der Fokus geht
hinein. Primär ist sie nur, wo sie die einzige Handlung der Fläche ist (Space-Mitglieder,
Mitgliederdialog); im Assistenten, bei den Verantwortlichen und neben „Speichern" ist sie
sekundär. Eine eigene Überschrift „Mitglied hinzufügen" trägt die Zeile nur, wo sonst keine
Überschrift sie gliedert (Reiter „Mitglieder", 5.12); unter einer Liste mit eigener Überschrift
(„Mitglieder" in „Meine Gruppen") gehört sie zu dieser.

**Link oder Schaltfläche.** Was an einen anderen Ort führt, ist im Code ein Link (`href` oder
Router-Link). Was auf der Seite etwas auslöst (Dialog öffnen, speichern, starten, abbrechen), ist
eine Schaltfläche, auch wenn sie wie ein Link aussieht (`component="button"`). Link-Optik für eine
Schaltfläche steht für die Hinzufügen-Aktion im Abschnittskopf, für „Ändern" in einer Wertzeile
(5.12), zum Auf- und Zuklappen („alle anzeigen", „Mitglieder ausblenden") und für kleine Schritte
in einer Liste oder einem Auswahldialog, die nichts speichern („Auswahl ansehen", „Suche
zurücksetzen", „Erneut laden"). Sie kommt aus `TextAction`
(`frontend/src/shared/ui/DetailSection.tsx`), nicht aus einem Nachbau je Thema. Was einen Lauf
startet oder Daten ändert, ist eine sichtbare Schaltfläche, auch im Abschnittskopf: „Vollabgleich
starten" steht wie „Jetzt indizieren" als kompakte Sekundär-Schaltfläche. Ein Umschalter in einer
Wertzeile („Sperren"/„Entsperren") bleibt Textaktion. „Abbrechen" ist in Assistenten, Formularen
und Dialogen überall ein stiller Text-Knopf (Still-Variante, `Button variant="text"`), auch im
Bibliotheksassistenten und in den Zeilen des Reiters „Quelle", nie ein Textlink.

**Verweise im Inhalt sehen aus wie ein Link.** Ein frei stehender Verweis im Inhalt einer Seite,
eines Panels oder einer Zeile, der an einen anderen Ort führt, ist ein Textlink aus `TextLink`
(ebenfalls `DetailSection.tsx`): Schrift, Gewicht und Mindestgröße wie `TextAction`, unterstrichen
beim Zeigen („Zugang ansehen", „Passwort ändern", „Zur Gruppenverwaltung"). Ein Link im Fließtext
übernimmt dessen Schrift und bleibt unterstrichen, damit er nicht allein an der Farbe zu erkennen
ist. Wie eine Schaltfläche sieht ein solcher Verweis nur aus, wenn er die eine Handlung eines
Hinweises oder Leerzustands ist (5.7): als Aktion eines `Alert` still in dessen Farbe („Wissen
zuordnen"), in einem Hinweisfenster als Sekundär-Schaltfläche („Zum Verzeichnisabgleich").
Anlege-Knöpfe (auch das Plus in 5.13) und der Schritt am Ende eines Ablaufs („Zur Anmeldung")
sind Handlungen und bleiben Schaltflächen. Reiter, globale Leiste, Zeilen der Seitenleiste und von
Listen, Menüeinträge und Kacheln haben ihre eigene Form.

**Linktexte** nennen das Ziel oder was dort zu tun ist, ohne Pfeil am Ende („Verbindung ändern",
nicht „Verbindung ändern ›"). Ausgenommen sind die Kennzahlen im Detailkopf („eine Gruppe ›"):
Dort zeigt ein „›" (`aria-hidden`), dass die Zahl zu ihrer Liste führt. Ein Ort in OPAA wird als
Pfad mit „→" geschrieben („Einstellungen → Zugangstokens", „Suche & Indexierung → Indexstatus").

### 5.2 Formularfelder

Beschriftung oberhalb als eigenständiges Label (12 px, `fg-2`, 5 px Abstand — kein
schwebendes Label im Feld). Das Feld liegt auf `bg-1` mit `border-strong`, Radius sm,
Haarlinien-Schatten und Höhe 40 px — keine gefüllte Grau-Fläche; im Dunkel-Schema hebt sich
das Feld stattdessen eine Flächenstufe ab (`bg-2`, ohne Schatten). Suchfelder tragen keine
sichtbare Beschriftung, sondern Platzhalter plus `aria-label` (Muster: Mockup-Suchfelder).
Hilfetext unterhalb in `fg-3`. Fokus: Rahmen 2 px in `accent`, ohne Fokusring (Ausnahme aus 4.4).
Fehler:
Rahmen und Meldungstext in Gefahr, Meldung programmatisch dem Feld zugeordnet
(Details: [Barrierefreiheits-Richtlinie](./accessibility.md), 2.7). Der Fehlerrahmen bleibt
auch bei Hover und Fokus in `danger`; den Fokus zeigt dann ein 2 px breiter Rahmen in `danger`
ohne Fokusring, für Textfelder, Auswahllisten und Autocomplete
gleichermaßen (`frontend/src/shared/theme/fieldErrorFocus.test.tsx`). Ein deaktiviertes Feld
behält auch mit Fehler den grauen Rahmen des deaktivierten Zustands.
Pflichtfelder werden nicht mit Sternchen markiert — optionale Felder tragen „(optional)".
Zugangsdaten immer als Kennwortfeld, nie im Klartext zurückgespiegelt.

**Personen- und Gruppensuche.** Wo Personen und Gruppen Empfänger sein können (Freigaben,
Space-Mitglieder, Eigentum), gibt es **ein** Suchfeld ohne Umschalter „Person / Gruppe"
(`SubjectPicker`, Platzhalter und `aria-label` „Person oder Gruppe suchen"). Die Treffer stehen
gemischt in einer Liste, die passendsten zuerst. Eine Person trägt ein Kopf-Symbol, eine Gruppe
ein Gruppen-Symbol und zusätzlich „Gruppe" im Text, damit die Art auch ohne Symbol erkennbar ist;
die Gruppe eines externen Anbieters trägt ein eigenes Warnsymbol. „Alle Konten" erscheint nur, wo
es diesen Empfänger gibt (Freigaben an einem bestehenden Asset), als eigener Eintrag mit
Welt-Symbol. Das Formular dazu steht in einer Zeile: Suche (breit), Rolle (schmal), Schaltfläche in
normaler Breite (`SubjectFormRow`); unter 600 px steht die Suche allein oben, Rolle und
Schaltfläche darunter. Dieselbe Zeile gilt, wo nur Personen in Frage kommen (`UserPicker`,
Platzhalter „Person suchen …") und die Rolle entfällt: Mitglieder und Verantwortliche einer Gruppe.

### 5.3 Tabellen

Spaltenköpfe im Eyebrow-Muster (`fg-3`), Zeilen durch `border` getrennt, keine Zebrastreifen.
Zeilen-Hover `bg-2`; ist die Zeile Navigationsziel (z. B. Gruppenverwaltung → Gruppe),
ist die **ganze Zeile ein Link** mit einer Tab-Position. **Übersichten von Assets sind keine
Tabellen**: Der Katalog zeigt Wissen, Prompts und künftige Asset-Arten ausschließlich als Kacheln
(5.4, [ADR-0039](../decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md)); Tabellen
bleiben Verwaltungslisten vorbehalten. Zahlen rechtsbündig in Mono, Stände
und Metadaten in `fg-3`. Unterhalb Tablet-Breite werden breite Tabellen zu Kartenlisten
(Muster: Mockup 1d, Issue #595).

**Filter nur, wenn er kürzt.** Ein Listenfilter erscheint erst ab 5 Einträgen und nur, wenn er die
Liste tatsächlich kürzt; zeigt jede Wahl dieselbe Liste, entfällt er. Beispiel: „Alle · Nur mit
Fehlern (n)" fehlt, wenn alle oder keiner der Einträge Fehler haben.

### 5.4 Karten

`bg-1` oder `bg-2` mit `border`, Radius md–lg, Innenabstand 16–24 px, kein Schatten. Klickbare
Karten heben sich im Hover über `border-strong` und `bg-2` ab, nicht über Schatten oder
Skalierung.

**Kopf einer Übersicht.** Jede Übersicht aus Kacheln (Katalog, Spaces) trägt denselben Kopf
(`OverviewPage`): den Seitennamen als Überschrift, daneben still in `fg-2` die Zahl der Einträge
(„1 Eintrag", „11 Einträge"), rechts die Schaltfläche zum Anlegen. Einen Untertitel gibt es nicht;
die Zahl gehört nicht in die Überschrift.

**Space-Kachel.** Name, Beschreibung (auf zwei Zeilen gekürzt) und die Zahl der Chats, dazu nur
Zustände wie „Archiviert" oder „Nachfolge offen". Eigene Rolle und Mitglieder stehen nicht auf der
Kachel, sondern in den Einstellungen des Space.

**Asset-Kachel.** Eine Kachel für jedes Asset, im Katalog wie in jeder Auswahl (`AssetTile`, #2131).
Kompakt und überall mit demselben Inhalt (#3262, #3305): Es zählt nur, was ein Asset ist, wie groß
es ist und ob ein Zustand Aufmerksamkeit braucht. Kopfzeile: links die Asset-Art (Icon und Wort),
daneben „Privat"/„Wird gelöscht", rechts der Favoriten-Stern. Darunter Name, Beschreibung (auf zwei
Zeilen gekürzt, ohne die Art noch einmal zu nennen), Umfang ohne Zahl der Spaces („12 Dokumente"),
ein Zustand mit Punkt (auch „Nachfolge offen") und eine Zeile, die die Quelle beisteuert (etwa eine
gesperrte Quelle oder das Ende einer Freigabe). Zuständige Stelle, „Aktualisiert am", Zahl der
Spaces, Welt-Symbol und eigene Rolle stehen nicht auf der Kachel, nur auf der Detailseite. Was eine
Quelle nicht kennt (die Token-Auswahl kennt etwa keine Zahlen), lässt die Kachel weg; zugeordnete
Assets zeigt sie mit den Daten des Katalogs. Weil die Kachel eigene Bedienelemente
trägt, ist sie **kein umschließendes Bedienelement**: Ihr Hauptelement ist der Name, seine
Klickfläche wird über die Kachel gestreckt, und der Stern liegt darüber als eigene Tab-Position.
Alles, was beim Überfahren etwas zeigt (Tooltip), liegt ebenfalls über der
gestreckten Fläche (`position: relative`, `z-index`), sonst fängt sie den Zeiger ab und der Tooltip
erscheint nie.

- **Im Katalog** ist der Name ein Link zur Detailseite. Nur hier steht neben dem Stern „⋯" für
  weitere Aktionen („In Space verwenden"); es heißt nach 5.6 „Weitere Aktionen für „<Name>"" und
  öffnet ein Menü.
- **In einer Auswahl** ist der Name ein Umschalter (`role="checkbox"`,
  `aria-checked`), dessen Beschreibung der Rest der Kachel ist. Gewählt zeigt allein das Zeichen
  links oben: gefülltes Häkchen in `accent` statt leerem Kreis in `fg-2` — ein Unterschied der Form,
  nicht nur der Farbe, beide mit mindestens 3:1 gegen die Fläche (2.4). Rahmen und Fläche bleiben
  die einer ungewählten Kachel, **kein `accent`-Rahmen**. Ein Klick auf die Kachel wählt, ein Klick
  auf den Stern setzt nur den Favoriten.
- **„Nachfolge offen" sperrt die neue Wahl** (#3276): Solange die Nachfolge offen ist, lehnt das
  Backend jede neue Zuordnung ab (ADR-0036, Entscheidung 6). Eine nicht gewählte Kachel ist deshalb
  nach 5.11 gesperrt: ausgegraut, `aria-disabled`, aber weiter eine Tab-Position, damit der Grund
  hörbar bleibt; Klick und Leertaste wählen nicht. Unter dem Zustand steht der Grund „Lässt sich
  nicht neu zuordnen, solange die Nachfolge offen ist." in `warning`, nicht ausgegraut, außerhalb des
  Umschalters und als `aria-describedby` angebunden. Die Beschreibung des Umschalters folgt der
  sichtbaren Reihenfolge, Zustandszeilen eingeschlossen. Eine gewählte Kachel ist eine bestehende
  Zuordnung: Sie bleibt lösbar und nennt denselben Grund vorab; nach dem Lösen ist sie gesperrt. Wo
  das Lösen sofort wirkt (Reiter „Inhalte"), bietet die Meldung kein „Rückgängig" an, sondern sagt,
  dass die Zuordnung bis zum Abschluss der Nachfolge nicht wiederherzustellen ist. Im
  Space-Assistenten ist jede Wahl neu: Eine Vorauswahl aus „In Space verwenden" mit offener
  Nachfolge übernimmt er nicht, sagt das im Schritt „Inhalte" als Warnung und legt den Space erst an,
  wenn der Zustand der Vorauswahl bekannt ist. Im Dialog „In Space verwenden" selbst sind dann alle
  Space-Einträge und „Neuen Space damit anlegen" ebenso gesperrt, mit dem Grund über der Liste.

### 5.5 Chips und Etiketten

Pill-Radius, 12 px, Gewicht 500, dezent — Umriss (`border-strong` + `fg-2`) oder stille
Tintfläche (`bg-3`), **keine Signalfarben und kein Akzent**: weder `danger`/`error`, `warning` noch
`success` und auch nicht `accent` als Fläche, Rahmen oder Text. Das gilt für hervorgehobene
Etiketten ebenso („Standard", die eigene Rolle, „Archiviert", „extern", „Sicherheitsrelevant",
„Global", „Alle Konten"): Die Bedeutung trägt das Wort. Akzentfarbene Chips bleiben
Bedienelementen vorbehalten (Filter-Chips, Bereichs-Chips der Chat-Eingabe). Ein **Zustand**
(verbunden, abgelaufen, gesperrt, Index unvollständig, Stufe übersprungen, „lange offen") ist kein
Etikett, sondern ein Punkt vor dem Wort (`StateDot`, 2.3); ein Symbol im Etikett darf die
Signalfarbe tragen, Rahmen und Text nicht („Wird gelöscht"). „lange offen" trägt den Ton
`warning`, weil es einen Zustand markiert, der Aufmerksamkeit verdient, ohne etwas auszulösen
(keine Frist, keine Erinnerung); die Farbe trägt diese Bedeutung, keine Stimmung (2.3). Feste
Wortlisten:

- **Rollen:** Leser · Bearbeiter · Verwalter · Eigentümer
- **Sichtbarkeit:** kein Textetikett. Ein öffentliches Asset (Spezifikation: öffentlich, an alle
  Konten freigegeben) trägt im Kopf der Detailseite ein Welt-Symbol mit Tooltip und `aria-label`
  „Für alle Konten freigegeben"; ein geschlossenes trägt nichts, insbesondere kein Schloss. Auf der
  Asset-Kachel entfällt es (5.4). Abgeleitet aus den Freigaben, nie eingestellt
- **Stand:** „Aktualisiert am TT.MM.JJJJ" ohne Punkt, wenn bereit; ein farbiger Punkt nur vor
  „Wird aktualisiert", „Aktualisierung fehlgeschlagen", „Noch kein Inhalt" und „Nachfolge offen".
  „Nachfolge offen" ist auf der Kachel eine eigene Zustandszeile ohne „zuständig:"; den Adressaten
  nennt die Detailseite. „Aktualisiert am" steht nur auf der Detailseite, die Kachel zeigt nur
  Zustände mit Punkt
- **Herkunft:** Upload · Dateisystem · Webverzeichnis · RSS-Feed · Confluence · S3-Objektspeicher;
  als Badge auf Kacheln die Kurzform Upload · Dateisystem · Web · Feed · Confluence · S3
- **Asset-Art:** Wissen · Prompts — im Filter wie als Etikett, stets mit dem Icon der Art

Ein laufender Vorgang („Lauf läuft · 62 %") ist Text mit Fortschrittsangabe in `fg-2`, kein
farbiger Chip.

**Ein Etikett, eine Form.** Die Regel gilt für jedes Etikett, ob MUI-`Chip` oder selbst gebaut
(Rolle in Tabelle und Kopf, Asset-Art auf der Kachel, „Privat", „Global", Hinweis-Etiketten in
Listen und Vorschlagslisten). Ein eckiges oder fettes Etikett ist ein Fehler. Selbst gebaute
Etiketten nehmen Radius, Größe (12 px, `fontSize.xs`) und Gewicht aus `badgeShape`
(`frontend/src/shared/theme/tokens.ts`), den Umriss aus `outlinedBadgeSx`
(`frontend/src/shared/ui/badgeStyles.ts`); für ein reines Textetikett gibt es `MetaBadge`, das
`MuiChip` des Themes hat dieselben Werte. Passt ein Etikett nicht in eine schmale Tabellenspalte,
bricht es um (`MetaBadge wrap`), statt abgeschnitten zu werden. Geprüft in
`frontend/src/shared/ui/badgeShape.test.tsx` und `labels.test.ts`.

**Kontrast** (2.4): `fg-2` auf der Fläche des Etiketts erreicht hell 9,21:1 (`bg-1`), 8,66:1
(`bg-2`) und 8,19:1 (`bg-3`), dunkel 7,43:1, 6,69:1 und 6,15:1. Auf den Flächen, auf denen
Etiketten tatsächlich stehen: überfahrene Tabellenzeile (`bg-2`) wie oben; MUI-Hovertönung über
`bg-1` hell 8,45:1, dunkel 6,28:1, über `bg-3` hell 7,48:1, dunkel 4,86:1; Akzent-Auswahltönung
von `ListItemButton` und `MenuItem` über `bg-1` hell 8,22:1, dunkel 6,27:1, gewählt und
überfahren hell 7,81:1, dunkel 5,51:1. Ausgenommen ist die Auswahltönung über `bg-3` (gewählt
und überfahren dunkel nur 4,37:1, neutrales `action.selected` 3,70:1): Dort steht kein Etikett;
wer eins dorthin setzt, weist den Kontrast im PR nach. Der Umriss in `border-strong` ist Schmuck,
kein Erkennungsmerkmal — das Wort macht das Etikett erkennbar, deshalb gilt für ihn keine
3:1-Anforderung.

### 5.6 Menüs und Overlays

`bg-1`, `border`, Radius md, Schatten der Ebene 2/3, Einträge 14 px mit 8–12 px Innenabstand,
Hover `bg-2`, zerstörende Einträge in Gefahr-Text am Ende, durch Trennlinie abgesetzt.
Gefahr-Text (`danger`) erreicht im hellen Schema 5,21:1 gegen `bg-1`, 4,90:1 gegen `bg-2` und
4,64:1 gegen `bg-3`, im dunklen Schema 6,86:1 / 6,18:1 / 5,68:1 (#2150).
Vollständige Tastaturbedienung (Pfeile, Enter, Escape) ist Teil der Komponente, nicht der Kür.

**Weitere Aktionen hinter „⋯".** Seltenere Aktionen einer Zeile, Kachel oder Detailseite öffnet
ein Nur-Icon-Knopf mit den waagrechten drei Punkten (`MoreHoriz`), nie den senkrechten. Jedes
„⋯"-Menü rendert der Baustein `RowMenu` (`frontend/src/shared/ui/RowMenu.tsx`); kein Thema baut
Auslöser oder Menü nach. Der Baustein legt fest:

- **Zugänglicher Name:** „Weitere Aktionen für „<Name>"" – der Name des Objekts, wie Zeile, Kachel
  oder Seitenkopf ihn zeigen, ohne Objektart („Weitere Aktionen für „bescheid.pdf"", nicht
  „Aktionen für Dokument bescheid.pdf"). Unterscheidet der Name allein die Zeilen einer Seite
  nicht, folgt ein kurzer Zusatz nach dem Anführungszeichen („Weitere Aktionen für „Claude Code"
  von Maria Musterfrau"). Das Menü trägt denselben Namen; ein Eintrag heißt wie sein sichtbarer
  Text, ohne das Objekt zu wiederholen.
- **Auslöser:** Tooltip „Weitere Aktionen" (5.1), `aria-haspopup="menu"`, `aria-expanded` und,
  solange das Menü offen ist, `aria-controls`. Während eine Aktion der Zeile läuft, ist er
  deaktiviert. Das Icon misst 20 px in Listen, Kacheln und Detailköpfen, 16 px nur in der dichten
  Chat-Liste der Seitenleiste.
- **Aufbau:** Zerstörende Einträge – die, deren Bestätigung den Ton `danger` trägt (5.10) – stehen
  am Ende, durch eine Trennlinie abgesetzt, in Gefahr-Text; kein anderer Eintrag ist rot. Ein
  dauerhaft gesperrter Eintrag (wegen Rolle oder Zustand des Objekts) nennt seinen Grund als Text
  im Menü und liest ihn mit vor (`aria-describedby`); ein Eintrag, der nur gesperrt ist, solange
  eine Aktion läuft, braucht keinen. Einträge ohne Icons: Einen zerstörenden Eintrag erkennt man
  an Trennlinie und Gefahr-Text. Was an einen anderen Ort führt, ist ein Link-Eintrag (5.1).
- **Tastatur:** Pfeile, Enter, Escape; beim Schließen kehrt der Fokus auf den Auslöser zurück.

Geprüft in `frontend/src/shared/ui/RowMenu.test.tsx` und `frontend/src/shared/ui/moreActionsTrigger.test.ts`.

**Jeder Dialog hat eine sichtbare Schließen- oder Abbruchstelle.** Dialoge ohne eigene
Abbruch-Schaltfläche (Ansichten, Vorschauen, Ereignislisten) tragen rechts im Titel ein ✕ mit
`aria-label` „Schließen" und Tooltip (5.1). Formulardialoge mit „Abbrechen" bekommen kein
zusätzliches ✕: eine Abbruchstelle genügt. Bestätigungen folgen 5.10. Escape und ein Klick daneben
schließen jeden Dialog weiterhin (bei Änderungen nach Rückfrage, 5.10). Den Titel mit Schließen-Kreuz liefert ein gemeinsamer Baustein in
`frontend/src/shared/ui/`, nie ein Nachbau je Dialog.

### 5.7 Leer-, Lade- und Fehlerzustände

- **Leer:** ein Satz, was hier stünde, plus die eine Handlung, die ihn füllt. Keine
  Illustrationsfriedhöfe.
- **Laden:** Skeleton in `bg-3` ohne Layoutsprung; laufende Hintergrundvorgänge bleiben über
  Seitenwechsel sichtbar (Muster Indizierung).
- **Fehler:** deutsch, ruhig, mit nächstem Schritt. Signalfarbe nur am betroffenen Element,
  nicht flächig.

### 5.8 Fußnoten und Fundstellen (Signaturmuster des Chats)

Belege erscheinen im Antworttext als **Fußnotenmarken**: die Ziffer in Mono und `accent` in einem
Kreis mit 10 % `accent`-Fläche (dunkel 22 %), hochgestellt über der x-Höhe, ohne den
Zeilenabstand zu ändern; ein Bereich „1–3" wird zur Pille.
Beim Hover oder Fokus füllt sich die Marke in `accent-press` mit weißer Ziffer (`accent`
selbst trüge Weiß im Dunkelschema nur mit 3,3:1); die Ziffer der ruhenden Marke rückt, wo die
Tönung sie unter 4,5:1 drückt, wie jeder Akzenttext vom Grund weg, und ein Tooltip nennt je Fundstelle
Ziffer, Dokumentname und Fundort; erst ein Klick öffnet das Belegfenster.
Unter der Antwort steht keine Quellenliste. Eine 1-px-Haarlinie in `border`, nach rechts
auslaufend, schließt den Antworttext ab; darunter folgt der Knopf **„Belege anzeigen"** als
Pille (Rahmen `border`, Radius `pill`). Er trägt links die Fußnotenmarken der Antwort, überlappend — dieselben Marken wie im
Text, ab der vierten als „+n" —, rechts einen Pfeil in `accent`. Beim Hover fächern die Marken leicht auf und der Pfeil
rückt 2 px nach (120 ms, nur `transform`). Daneben die Zählzeile „n Stellen in m Dokumenten ·
k weitere geprüft" in `fg-2`, mit dem Knopf per `aria-describedby` verbunden. Die Antwort bleibt
Fließtext, auch in langen Gesprächen. Alle Fundstellen stehen im **Belegfenster** rechts. Sein Kopf
trägt nur „Belege dieser Antwort", darunter das Suchfeld „In Belegen suchen". Es öffnet mit den
zitierten Belegen in Zitatreihenfolge; die geprüften, nicht zitierten blendet der Link
**„Nicht zitierte anzeigen"** unter der Liste ein und wieder aus. Er folgt dem Disclosure-Muster:
Die Beschriftung bleibt, nur `aria-expanded` und ein Chevron wechseln; eine höfliche Statusmeldung
(„3 nicht zitierte Belege eingeblendet") sagt die neuen Zeilen an, die oberhalb des Links
erscheinen. Eine Suche durchsucht immer alle Belege; solange sie läuft, tritt der Link zurück.
Nicht zitierte Zeilen treten über die Farbrolle zurück (Titel in `fg-2`), nie über Deckkraft, die
den Kontrast unter 4,5:1 drückte. Je Dokument stehen Ziffern + Titel (Gewicht 500) + Fundort und die
Aktion „Dokument öffnen"; Rang, Indexierungsdatum, Metadatenzeile und Quelladresse zeigt das
Belegfenster nicht — sie sind Fehlersuche, nicht Nachschlagen. Ein Klick auf eine Fußnote öffnet das
Belegfenster an ihrer Fundstelle und markiert sie dort (Fläche `accent` 7 %, Akzentkante links) —
markiert wird der Beleg, nicht der Antworttext. Ein Beleg, den die Belegvalidierung nicht
bestätigt, trägt einen dezenten Hinweis in normaler Beleg-Typografie — kein Banner, keine
Signalfarbe (Ausgestaltung: Issues #590/#592/#1449/#3259, Mockups 1a/1i).

### 5.9 Popup-Benachrichtigungen

Rückmeldungen auf einmalige Aktionen — ein fehlgeschlagener oder gestarteter Download, ein
abgeschlossener Hintergrundschritt — erscheinen als **Popup-Benachrichtigung** oben mittig
(Ebene 2: Rahmen plus Schatten auf `bg-1`), dort, wo der Blick ohnehin liegt. Auf schmalen
Bildschirmen beginnt sie unter der mobilen Kopfzeile und verdeckt weder Menü noch Glocke. Mehrere
Meldungen erscheinen nacheinander an derselben Stelle. Eine Meldung wird nach sechs Sekunden
automatisch ausgeblendet und ist jederzeit per Schließen-Knopf verwerfbar.
**Der Timer steht still, solange der Mauszeiger oder der Tastaturfokus auf der Meldung liegt**, und
läuft erst nach dem Verlassen weiter (WCAG 2.2.1). Sie laufen über den globalen Mechanismus
(`frontend/src/shared/stores/notificationStore.ts` → `notify(message, severity)`, gerendert genau
einmal durch `NotificationHost` in der AppShell) — nie über eine komponenteneigene Snackbar und
**nie als Inline-Alert, der über einer Tabelle oder Liste eingeschoben wird** und dabei fremden
Inhalt verdrängt.

Eine Benachrichtigung darf **eine** Aktion tragen, etwa „Rückgängig" nach einer sofort wirksamen
Änderung (`notify(message, severity, { label, onClick })`). Die Aktion ist nie der einzige Weg: Was
sie rückgängig macht, lässt sich auch am Ort der Änderung zurücknehmen, weil die Meldung nach
sechs Sekunden verschwindet.

**Was ein Popup sagen darf.** Es quittiert, es trägt keine Information, die man noch braucht:

- **Fehler, die eine Handlung verlangen, stehen nie nur im Popup.** Muss die Person etwas
  korrigieren, erneut versuchen oder nachtragen, steht der Fehler auch am Ort — am Feld, an der
  Zeile, im Bereich — und bleibt dort, bis er behoben ist. Das Popup darf ihn zusätzlich melden.
- **Ein Popup wiederholt keine Aussage, die schon auf der Seite steht.** Zeigt die Seite das
  Ergebnis selbst (neuer Zustand, neue Zeile, Inline-Hinweis), entfällt das Popup.

Inline-`Alert`s bleiben Zuständen vorbehalten, die an ihren Ort gebunden sind und dort bestehen
bleiben: der Validierungsfehler am Formular, der Ladefehler des Bereichs, ein dauerhafter
Zustandshinweis („nur Leserechte"). Faustregel: Was nach einem Seitenwechsel noch gilt, ist ein
Inline-Zustand — was einen Klick quittiert, ist eine Benachrichtigung.

### 5.10 Bestätigungen

Eine folgenreiche Handlung wird über das **Bestätigungs-Overlay** der Anwendung abgesichert, nie
über `window.confirm` (#1610). Der Browser-Dialog stellt seine Frage in Systemschrift, presst die
Folge in denselben Textklumpen und lässt mit „OK" bestätigen — er benennt also genau das nicht,
was zugesagt wird.

Der Mechanismus ist derselbe wie bei den Benachrichtigungen: `confirmAction(options)` aus
`frontend/src/shared/stores/confirmStore.ts`, gerendert genau einmal durch `ConfirmHost` in der
AppShell. Der Aufruf bleibt dadurch so kurz wie der ersetzte:

```ts
if (!(await confirmAction({ question, consequence, confirmLabel, tone }))) return
```

**Aufbau.** Die **Folge ist der Hauptinhalt**, nicht die Frage. Die Frage steht als Titel darüber
in 17 px, die Folge darunter in 13,5 px `fg-2` in Lesebreite. Links trägt der Dialog eine 3 px
starke Signalkante, bei gewichtigen Tönen zusätzlich ein Symbol neben dem Titel.

**Die Schaltfläche trägt das Verb der Handlung** — „Löschen", „Abschalten", „Verwerfen",
„Sperren". Nie „OK", nie „Ja". Wer nur die Schaltfläche liest, muss trotzdem wissen, was er
zusagt.

**Drei Töne**, mehr nicht:

| Ton | Wofür | Kante | Vorgabefokus |
| --- | --- | --- | --- |
| `danger` | unwiderruflich: löschen, Zugang entziehen | Gefahr | **Abbrechen** |
| `caution` | folgenreich, aber umkehrbar: abschalten, einschalten | Warnung | die Handlung |
| `neutral` | Eingaben verwerfen, Zuordnung lösen | Rahmen | die Handlung |

Bei `danger` liegt der Fokus bewusst auf „Abbrechen": Ein Enter aus dem Reflex heraus bricht dann
ab, statt zu löschen.

**Abbruch ist die sichere Antwort.** Escape, ein Klick daneben und eine verdrängende zweite
Anfrage lösen alle zu „nein" auf. Eine Bestätigung entsteht nur durch den Klick auf die
benannte Handlung.

Die Signalfarbe steht in Kante und Symbol, nie im Fließtext (2.3).

**Wann überhaupt bestätigen?** Nur, wenn die Handlung nicht folgenlos rückgängig zu machen ist
oder fremde Arbeit trifft. Eine Bestätigung, die reflexhaft weggeklickt wird, schützt nichts und
kostet jeden Vorgang einen Klick.

### 5.11 Auswahl aus einer Menge

**Wie eine Wahl aussieht, entscheidet die Art der Menge**
([ADR-0039](../decisions/0039-ein-katalog-und-ausdrueckliche-space-zuordnung.md), Entscheidung 1).
Es gibt drei Mengenarten:

| Mengenart | Darstellung | Beispiele |
| --- | --- | --- |
| **Geschlossene Artenmenge** — Arten mit eigenem Icon | Kacheln mit Icon | Asset-Art, Quellart |
| **Assets** — eigene Objekte aus dem Katalog | Asset-Kacheln (5.4) mit Filterzeile | Zuordnung zu einem Space, Bibliotheken eines Zugangstokens |
| **Fremde Objekte aus einer Quelle** — was ein angebundenes System liefert | Liste oder Baum mit Suche, **nie Kacheln** | Sites, Spaces, Buckets, Ordner |

Die Wahlen im Einzelnen:

| Wahl | Mengenart | Auswahl | Wo |
| --- | --- | --- | --- |
| **Asset-Art** beim Anlegen | Arten | einfach | erster Schritt von „Neuer Katalogeintrag" im Katalog; nur Arten mit Anlegerecht |
| **Quellart** einer Wissensbibliothek | Arten | einfach | Assistent „Neue Wissensbibliothek" |
| **Zuordnung** von Assets zu einem Space | Assets | mehrfach | Space-Assistent, Space-Einstellungen, „In Space verwenden" |
| **Bibliotheken eines Zugangstokens** | Assets | mehrfach | Ausstellung eines Zugangstokens für externe KI-Werkzeuge |
| **Umfang** einer Bibliothek (Sites, Spaces, Buckets, Ordner) | fremde Objekte | mehrfach | Quellteil des Assistenten „Neue Wissensbibliothek" und der Bibliothekseinstellungen |

Weitere Wahlen dieser Art folgen dem Muster ohne eigene Entscheidung. Ein **Dropdown** bleibt
richtig, wo die Menge lang und homogen ist und kein Icon unterscheidet (Rolle, Sprache, Zeitraum).

**Geschlossene Artenmengen** haben keine feste Obergrenze an Kacheln. Ab etwa zwölf Arten werden
sie gruppiert (je Gruppe ein sichtbarer Titel), damit die Wahl überschaubar bleibt.

**Gleichartige Wahl ohne unterscheidendes Icon** — etwa zwischen mehreren Zugängen derselben
Quellart, die sich nur im Namen unterscheiden — erscheint als **Radioliste**, nicht als Kacheln: Ein Icon,
das bei allen Optionen gleich wäre, unterscheidet nichts. Gibt es nur **eine** Option, gibt es keine
Wahl: Sie steht als einfache Zeile da, ohne Radioknopf. Ausgenommen ist der private Weg einer
Quellart, die Bibliotheken **und** Personen zulässt: Er bleibt auch als einzige Option ein Radio, das
die Person ausdrücklich wählt, denn „privat“ lässt sich später nicht ändern
([ADR-0049](../decisions/0049-private-bibliotheken-und-kontingente.md), Entscheidung 1). Bei einer
Quellart nur für Personen (OneDrive) ist schon die Wahl der Quellart diese ausdrückliche Wahl; ihr
einziger Zugang steht als Zeile da, mit dem Hinweis „nur für Sie, später nicht änderbar“.

**Fremde Objekte aus einer Quelle** erscheinen als Liste oder Baum, nie als Kacheln — ihre Zahl und
Benennung bestimmt das fremde System, nicht OPAA. Die Schwelle:

- **Bis sieben Einträge der obersten Ebene** stehen als Checkboxen direkt in der Zeile, ohne
  Suche — auch bei hierarchischen Quellen wie Nextcloud, SMB, Dropbox oder S3.
  So bleibt der häufige kleine Fall mit zwei, drei Ordnern ohne Dialog.
- **Tiefere Ebenen und Mengen ab acht Einträgen** laufen immer über den **gemeinsamen
  Auswahldialog**, derselbe für jede Quellart: Breadcrumb für die Ebene, Suche und Teilauswahl
  (ein Knoten, dessen Kinder nur zum Teil gewählt sind, zeigt das als gemischten Zustand,
  `aria-checked="mixed"`). Die Zeile fasst danach die Wahl zusammen („3 von 42 Spaces").
- **Bei hierarchischen Quellen** steht unter den Checkboxen der obersten Ebene ein Textlink
  „<Objekte> auswählen …" (etwa „Ordner auswählen …"); er öffnet den Dialog für die tieferen
  Ebenen. Ab acht Einträgen ersetzt die Liste mit demselben Link die Checkboxen.

**Aufbau der Kacheln.** Jede Kachel trägt ein Icon (dekorativ, `aria-hidden`), ein Wort als Namen
und einen kurzen Satz darunter in `fg-2`; bei Assets die Asset-Art (5.5).
Die Kachel hat Kartenform (5.4). **Gewählt** zeigt sich bei Artenkacheln an Rahmen in `accent`
**und** einem Häkchen-Symbol — nie allein über Farbe. Asset-Kacheln zeigen gewählt allein am
gefüllten Häkchen statt leerem Kreis, ohne Rahmen (5.4) — ein Unterschied der Form, nicht der Farbe. Eine **gesperrte** Kachel (fehlendes Recht, nicht
verfügbare Quellart) bleibt sichtbar, ausgegraut, mit dem Grund im Satz darunter, wenn ihr Fehlen
sonst Fragen aufwirft; Arten ohne Anlegerecht werden dagegen gar nicht angeboten. Der Grund steht
in `warning`, nicht ausgegraut und außerhalb des gesperrten Bedienelements, das ihn als
Beschreibung (`aria-describedby`) trägt — so gilt für ihn die Textschwelle aus 2.4.

**Bedienung und Ansage.** Einfachauswahl ist eine `radiogroup`: eine Tab-Position für die Gruppe,
Pfeiltasten wechseln (gesperrte Kacheln werden übersprungen), Leertaste und Enter wählen.
Mehrfachauswahl ist eine beschriftete Gruppe von Umschaltern (`role="checkbox"` je Kachel), jede
Kachel eine eigene Tab-Position, Leertaste schaltet. Radioliste und Checkboxen fremder Objekte
folgen denselben Regeln mit nativen Elementen. Die Gruppe trägt einen sichtbaren Titel, der
zugleich ihr zugänglicher Name ist. Die ganze Kachel bzw. Zeile ist Klickfläche; Fokus nach 4.4
([Barrierefreiheits-Richtlinie](./accessibility.md)).

**Große Mengen von Assets: eine Filterzeile.** Katalog, Zuordnung und Token-Auswahl haben dieselbe Zeile
(`AssetFilterBar`), in dieser Reihenfolge: **Suche → Asset-Art → Favoriten**. Die Asset-Art ist eine
Umschaltgruppe ohne sichtbaren Titel; ihr zugänglicher Name ist „Typ" (`aria-label`), und sie
erscheint nur, wo mehr als eine Art angeboten wird.
„Favoriten" ist ein Umschalter mit Stern-Icon, gefüllt, wenn aktiv, und durch einen senkrechten
Trenner von der Typgruppe abgesetzt, damit er nicht wie eine weitere Art wirkt. Wo gewählt wird,
folgt „Nur ausgewählte" — es zeigt die Auswahl zum Zeitpunkt des Einschaltens — und die Zahl „n
ausgewählt". Die Umschalter verbinden sich mit UND; keiner gedrückt heißt „alle". Auf schmalen
Bildschirmen bricht die Zeile als Einheit um: Die Suche steht oben, Typgruppe und Umschalter darunter;
ein Umschalter steht nie allein in einer Zeile. Unter etwa 600 px Breite entfallen die Icons der
Typknöpfe, und Knöpfe und
Chips rücken enger zusammen, damit Typgruppe und Favoriten bis hinab zu 320 px (Reflow-Breite nach
WCAG 1.4.10) in eine Zeile passen. Weitere Filter (Sichtbarkeit, Gruppenherkunft) und eine
Sortierauswahl gibt es nicht; die Reihenfolge ist fest: Favoriten zuerst, dann nach Name — auch
unter „Nur ausgewählte" bzw. „Nur zugeordnete". Angeboten
wird nur, was die Person lesen darf. Über „Weitere laden" steht „n von m angezeigt".

**Ein Baustein je Art von Kachel.** Die Wahl von Arten und Quellen ist im Frontend eine Komponente
(`ChoiceTileGroup`, #2094). Assets – im Katalog, in der Zuordnung zu einem Space und in der
Token-Auswahl – zeigt immer die Asset-Kachel aus 5.4 (`AssetTile`, #2131), nie ein Nachbau je Stelle.
Fremde Objekte wählt jede Quellart über denselben Auswahldialog, nie über einen eigenen.

### 5.12 Reiter und Überschriften

**Keine Überschrift wiederholt den Reiternamen.** Unter dem Reiter „Stammdaten" steht keine
Abschnittsüberschrift „Stammdaten", unter „Inhalte" keine „Inhalte"; der gewählte Reiter benennt das
Panel bereits. Eine Überschrift im Panel steht nur dort, wo sie etwas Eigenes gliedert, etwa
„Mitglied hinzufügen" oder „Gefahrenbereich". Die Gliederung bleibt lückenlos: Die erste Überschrift
eines Panels ist eine h2 unter der h1 der Seite (siehe
[Barrierefreiheits-Richtlinie](./accessibility.md), 2.3). Ein Panel braucht keine Überschrift, wenn
es nichts weiter zu gliedern gibt.

**Einzelwerte als Wertzeile.** Ein einzelner Wert mit höchstens einer Handlung steht als Zeile
„Titel · Wert · Textaktion" (etwa „Zuständig · Referat 21 · Ändern"), nicht als Abschnitt mit
eigener Überschrift. Die Textaktion steht als `TextAction` an der rechten Kante (Prüfliste P45, P48).

### 5.13 Seitenleiste des Space

Entschieden in #3266 (Prototyp #3280, Variante B). Ab dem Breakpoint `md` (900 px, `AppShell`)
steht die Seitenleiste fest als Spalte, darunter öffnet sie als Schublade.

**Space-Liste nur in der festen Spalte.** Spaces-Bereich und Space-Zeilen gelten nur, solange die
Seitenleiste fest steht. In der Schublade bleibt der Space-Wechsel das Space-Menü
(Space-Umschalter, Menü-Knopf nach 5.6): Dort ist die Höhe knapp, fünf Space-Zeilen nähmen den
Chats zu viel weg. Für die Schublade gilt außerdem:

- Plus und Lupe im Kopf „Chats" wie in der Spalte;
- Zähler in den Köpfen „Angeheftet" und „Zuletzt verwendet";
- die Suche öffnet im Inhaltsbereich, auch „Alle Spaces anzeigen" aus dem Space-Menü.

**Zwei Bereiche, die Chats scrollen.** Oben steht der Bereich **Spaces**, darunter, durch eine
Trennlinie abgesetzt, der Bereich **Chats**. Der Spaces-Bereich nimmt höchstens knapp die Hälfte
der Spaltenhöhe ein und scrollt nur in sich, wenn diese Höhe für seine Zeilen nicht reicht; die
Chats sind die einzige Fläche, die im Normalfall scrollt. So bleibt der Space-Kontext sichtbar,
wie weit man in den Chats auch scrollt.

**Spaces als Zeilen statt Menü.** Der Spaces-Bereich zeigt bis zu fünf Spaces als Zeilen
(Kürzelkästchen und Name). Ausgewählt werden die zuletzt genutzten, aufgefüllt in Listenreihenfolge;
angezeigt werden sie in fester Reihenfolge, privater Bereich zuerst, dann nach Name, damit eine
angeklickte Zeile nicht unter dem Zeiger springt. Der aktive Space ist immer dabei, trägt
`aria-current` und ist der Aktiveintrag nach 2.3 (`bg-1`, `border-strong`). Ein archivierter Space
erscheint nur, wenn er zuletzt genutzt wurde, mit dem Hinweis „archiviert". Alle weiteren Spaces
erreicht man über die Lupe im Kopf. Eine Zeile führt in einen neuen Chat des Space, bei einem
archivierten Space auf seine Übersicht.

**Gleichrangige, zuklappbare Gruppenköpfe mit Zählern.** Die Köpfe „Spaces", „Angeheftet" und
„Zuletzt verwendet" sind Disclosure-Köpfe mit Chevron, `aria-expanded` und `aria-controls`. Der
Klappzustand bleibt im Browser gespeichert, wie die Spaltenbreite. Ist „Spaces" zugeklappt, bleibt
die Zeile des aktiven Space sichtbar. Jeder dieser Köpfe trägt einen Zähler als Text hinter der
Beschriftung („Angeheftet 8"), kein abweichendes `aria-label`, damit sichtbarer und zugänglicher
Name übereinstimmen. Der Zähler nennt die ganze Gruppe: bei „Spaces" die nicht archivierten Spaces
der Person, bei „Zuletzt verwendet" auch die Chats, die erst „… weitere" aufdeckt.

**Ein Aktionsmuster für beide Bereiche.** Die Köpfe „Spaces" und „Chats" tragen rechts dasselbe
Paar aus Nur-Icon-Knöpfen nach 5.1 (`aria-label` und Tooltip): Plus („Neuer Space", „Neuer Chat")
und Lupe („Alle Spaces anzeigen und durchsuchen", „Chats durchsuchen"). Das Plus ist die in 5.1
genannte Ausnahme von den Anlege-Knöpfen; sein Name ist derselbe wie überall sonst für das Objekt.
Kein Text „Neu", keine umrandete Schaltfläche „Neuer Chat". In einem archivierten Space ist das
Plus der Chats gesperrt, der Tooltip nennt den Grund.

**Die Suche öffnet im Inhaltsbereich.** Beide Lupen öffnen ihre Suche im Inhaltsbereich; die
Seitenleiste mit Spaces und Chats bleibt dabei stehen. Die Space-Suche ersetzt die Seitenleiste
nicht durch die Spalte eines globalen Bereichs.

**Der Rail-Eintrag heißt „Chat".** Der Eintrag der globalen Leiste, der zu dieser Seitenleiste
führt, heißt „Chat" (Singular) und trägt ein Sprechblasen-Icon; die Leiste lautet damit
Chat · Katalog · Admin (für Prüfende „Revision" statt „Admin"). Er ist für `/spaces` und `/chat`
aktiv und führt auf `/chat`, nicht auf die Space-Übersicht: Das öffnet einen neuen Chat im zuletzt
genutzten nicht archivierten Space, ohne einen solchen im privaten Bereich. Die Übersicht erreicht
man in der Spalte über die Lupe im Kopf „Spaces", in der Schublade über „Alle Spaces anzeigen" im
Space-Menü.

---

## 6 · Sprache und Begriffe

UI-Sprache ist Deutsch, Anrede „Sie", `aria-label` deutsch.

**Eigener Begriff oder Herstellerbegriff.** Für die Wahl eines Begriffs gilt:

- **Eigene Konzepte tragen überall denselben eigenen Begriff** — bei jeder Quellart, in jedem
  Assistenten und jeder Einstellung: Verbindung, Umfang, Zeitplan, Zugang, „Gesetzt · Ersetzen".
- **Werte, die man aus einem Herstellerportal abschreibt oder dort wiederfinden muss, tragen den
  Begriff des Herstellers** in dessen offiziellem Deutsch: API-Token, App-Passwort, Access Key,
  Secret Key, Endpoint, Space, Dokumentbibliothek, Geteilte Ablage. Wer den Wert sucht, findet ihn
  unter genau diesem Wort.
- **Keine Doppel-Labels** in Klammern („Token (Kennwort)") und kein künstlich allgemeines
  „Kennwort" für ein Token.

**Erklärtexte knapp.** Die Beschreibung unter einem Seiten- oder Abschnittstitel sagt in einem Satz,
wofür die Seite da ist, höchstens zwei Zeilen bei 1280 px. Ein Hilfetext am Feld hat eine Zeile
und sagt nur, was einen wahrscheinlichen Fehler verhindert oder wo man einen Wert findet
([UI-Prüfliste](./ui-pruefliste.md), P13). Hintergrund, Ausnahmen und Folgen stehen im
[Produkthandbuch](../handbuch/README.md), nicht in der Oberfläche.

Verbindliche Begriffe:

| Begriff                   | Bedeutung / Regel                                                                            |
| ------------------------- | -------------------------------------------------------------------------------------------- |
| **Space**                 | Arbeitsraum mit Datenquellen, Chats, Mitgliedern. Bleibt unübersetzt.                        |
| **Chat**                  | in sich geschlossene Unterhaltung in einem Space; benennbar                                  |
| **Wissensbibliothek**     | benannter Wissensbestand; kurz „Bibliothek", im Fließtext auch „Bestand"                     |
| **Datenquellen**          | die einem Space zugeordneten Bibliotheken                                                    |
| **Katalog**               | der eine Einstieg für alle Assets (Wissen, Prompts, später Skills und Agenten); zeigt nur Lesbares, nur als Kacheln |
| **Zuordnung / zuordnen**  | ein Asset einem Space bereitstellen; die harte Grenze dessen, was der Space im Chat nutzt. Nicht „assoziieren" in Nutzertexten |
| **Favorit**               | persönliche Markierung an einem Asset, nur für die Person selbst sichtbar                    |
| **@Space-Wissen**         | der vorbelegte Chip am Eingabefeld: das dem Space zugeordnete, für die Person lesbare Wissen |
| **Sichtbarkeit**          | öffentlich (an alle Konten freigegeben) · geschlossen; Beschriftung in der Oberfläche siehe 5.5 |
| **Herkunft**              | woher eine Bibliothek ihre Dokumente bezieht (Upload, Dateisystem, Webverzeichnis, RSS-Feed, Confluence, S3-Objektspeicher) |
| **Verbindung**            | die Anbindung einer Bibliothek an ihre Quelle: Adresse und Anmeldung, selbst eingetragen oder über einen Zugang; bei jeder Quellart derselbe Begriff |
| **Zugang**                | eine von der Systemverwaltung angelegte und freigegebene, benannte Anbindung an ein Quellsystem (etwa „Microsoft 365 Rheinfurt"), die man beim Anlegen einer Bibliothek nur noch auswählt ([Glossar](../handbuch/glossar.md)) |
| **Umfang**                | was eine Bibliothek aus ihrer Quelle aufnimmt (Sites, Spaces, Buckets, Ordner; Auswahl nach 5.11) |
| **Zeitplan**              | wann eine Bibliothek ihre Quelle erneut liest                                                |
| **Gesetzt · Ersetzen**    | Anzeige eines hinterlegten Geheimnisses: „Gesetzt" statt des Werts, daneben die Aktion „Ersetzen"; nie zurückgespiegelt |
| **Geltungsbereich**       | ein Eintrag im Umfang einer S3-Bibliothek: ein Bucket mit optionalem Präfix; eine Bibliothek hat einen bis fünfzig, die sich nicht überschneiden |
| **Endpoint**              | Herstellerbegriff: die Adresse eines S3-Objektspeichers (`https://host[:port]`, ohne Pfad) |
| **Rolle**                 | Leser · Bearbeiter · Verwalter · Eigentümer                                                  |
| **Fundstellen**           | die Stellen in Dokumenten, auf die sich eine Antwort stützt                                  |
| **Belege / Belegfenster** | alle Fundstellen einer Antwort in der seitlichen Leiste                                      |
| **Systemverwaltung**      | Admin-Bereich und -Rolle                                                                     |
| **Anmeldung / Kennung**   | nie „Login"/„Username" in Nutzertexten                                                       |
| **Kontozustand**          | Eingeladen · Aktiv · Gesperrt · Abgelaufen — als Punkt **und** Wort, nie als farbiger Chip; die Sperre nennt ihren Grund in Klammern (Verwalter · Fehlversuche · Inaktivität) |
| **Anlagegrund**           | der dienstliche Anlass eines lokalen Kontos; Pflichtangabe, für die betroffene Person einsehbar |
| **Aktivität**             | nie · länger als 90 Tage nicht · aktiv — nur diese Klassen, nie ein Zeitstempel und nie ein Sortierkriterium |
| Claim                     | „Fragen. Belegen. Entscheiden."                                                              |

Nicht verwendet werden: „Workspace", „Library", „Datei-Upload" (stattdessen „Upload"),
„User", englische Mischformen in Nutzertexten. Technische Enum-Werte und API-Felder bleiben
englisch (Projektsprache, siehe AGENTS.md).

---

## 7 · Branding-Überschreibbarkeit

Von Anfang an gilt: **Produktname, Claim, Logo, Akzentfarbe und Farbschema-Vorgabe sind
Konfiguration**, nicht Code (Issues #582/#583). Daraus folgt für jede Komponente:

- Name, Claim und Logo werden nie hart eingebettet, sondern aus der Branding-Quelle bezogen
  (mit OPAA-Standard als Fallback).
- Akzentfarbe nur über die Rollen `accent`/`accent-surface`; die Fläche gefüllter Aktionen wird
  aus der konfigurierten Farbe abgeleitet (#634): unverändert, wenn Weiß darauf 4,5:1 erreicht,
  sonst in −8-%-Schritten abgedunkelt (höchstens sechs Schritte). Hover (−8 %), Aktiv (−16 %)
  und Fokusring (deckend in der abgeleiteten Textfarbe, 4.4) werden berechnet, nicht aus
  Blau-800/900 fest verdrahtet.
- Die Kontrastprüfung der konfigurierten Farbe warnt, blockiert aber nicht (#583); sie prüft
  die Beschriftung gegen die tatsächlich abgeleitete Fläche.

---

## 8 · Geltung und Pflege

- Diese Guidelines sind Prüfmaßstab für jedes Frontend-Review; das PR-Template verweist auf die
  [Barrierefreiheits-Prüfliste](./accessibility.md).
- Änderungen an den Guidelines laufen als eigener PR mit Begründung; Wertänderungen werden mit
  der Token-Ebene (#581) synchron gehalten — die Token-Datei setzt um, dieses Dokument
  entscheidet.
- Die abgelösten Stitch-Entwürfe (`chat-interface.html`, `document-browser.html`,
  `system-settings.html`) sind historisch und keine Gestaltungsreferenz mehr.
