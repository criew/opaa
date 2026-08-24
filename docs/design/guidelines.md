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

| Stufe | Wert | Stufe | Wert |
|---|---|---|---|
| 50 | `#E7F4FE` | 500 | `#1292EE` *(Basis)* |
| 100 | `#C6E3FC` | 600 | `#0F80D6` *(Hover, −8 % Helligkeit)* |
| 200 | `#9BCEFA` | 700 | `#0B6FBC` *(Aktiv/Gedrückt, −16 %)* |
| 300 | `#61B5F6` | 800 | `#085B9C` |
| 400 | `#349EF2` | 900 | `#05447A` |

**Navy** — Struktur- und Textfarbe:

| Name | Wert | Verwendung |
|---|---|---|
| Navy | `#012142` | Primärtext (hell), Seitenleiste, dunkle Grundfläche |
| Navy-900 | `#00152D` | Überlagerungen, tiefste Fläche |
| Navy-700 | `#02305E` | erhöhte Fläche im dunklen Schema |
| Navy-600 | `#034079` | gedämpfte Fläche im dunklen Schema |
| Navy-500 | `#055396` | Grenzfälle, Diagramme |

**Grau** (kühl, auf Navy abgestimmt): `#E6EBF1` (100), `#CBD4DF` (200), `#A4B1C1` (300),
`#778797` (400), `#556473` (500), `#3B4958` (600), `#26323F` (700), `#162231` (800).

**Weißtöne:** Weiß `#FFFFFF` · Off-White `#F6F8FB` (helle erhöhte Fläche) · Smoke `#EEF2F7`
(gedämpfte Fläche, Trennlinien).

**Carbon** — neutrale Dunkel-Skala des dunklen Schemas (#654, angelehnt an das dunkle Schema
der Claude-Docs-Website, erhoben am 20.08.2026). Navy bleibt der Seitenleisten-Block des
hellen Schemas:

| Name | Wert | Verwendung |
|---|---|---|
| Carbon-950 | `#09090B` | Seitengrund dunkel |
| Carbon-900 | `#171717` | erhöhte Fläche dunkel |
| Carbon-850 | `#1F1F1F` | gedämpfte Fläche dunkel |
| Carbon-800 | `#252525` | Standardrahmen dunkel |
| Carbon-700 | `#333333` | betonter Rahmen, Tooltip dunkel |

**Semantik:** Erfolg `#16B77B` · Warnung `#F5B83D` · Gefahr `#E5484D`. In beiden Schemata
identisch; Text auf diesen Flächen muss die Kontrastanforderung (2.4) erfüllen.

### 2.2 Semantische Rollen

Die Rollen sind das Vokabular aller Komponenten. Werte je Schema:

| Rolle | Bedeutung | Hell | Dunkel |
|---|---|---|---|
| `bg-1` | Seitengrund | Weiß `#FFFFFF` | Carbon-950 `#09090B` |
| `bg-2` | erhöhte Fläche (Karte, Kopfzeile) | Off-White `#F6F8FB` | Carbon-900 `#171717` |
| `bg-3` | gedämpfte Fläche (Eingabefeld, Tabellenkopf) | Smoke `#EEF2F7` | Carbon-850 `#1F1F1F` |
| `fg-1` | Primärtext | Navy `#012142` | `#DEDEDE` |
| `fg-2` | Sekundärtext | Grau-600 `#3B4958` | `#9E9E9E` |
| `fg-3` | Tertiärtext, Metadaten | Grau-500 `#556473` | `#8A8A8A` |
| `accent` | Handlung, Bezug, aktiver Zustand | Blau-500 `#1292EE` | Blau-500 `#1292EE` |
| `accent-fg` | Text auf Akzentfläche | Weiß | Weiß |
| `border` | Standardrahmen | Grau-100 `#E6EBF1` | Carbon-800 `#252525` |
| `border-strong` | betonter Rahmen (Eingaben, Tabellen) | Grau-200 `#CBD4DF` | Carbon-700 `#333333` |

`fg-3` im hellen Schema ist Grau-500, nicht Grau-400 (#725): Grau-400 (`#778797`) erreicht gegen
Weiß nur 3,68:1 — unter der 4,5:1-Anforderung für Fließtext (2.4) — und lag als Tertiärtext in
der Wissensbibliotheken-Tabelle (Spaltenkopf, Metadaten) sichtbar unter der Schwelle. Grau-500
erreicht 6,08:1 gegen Weiß und bleibt auch gegen `bg-2`/`bg-3` klar über 4,5:1; die Grau-Skala
selbst (2.1) bleibt dabei unverändert.

Die Seitenleiste im hellen Schema verwendet ein eigenes Rollenset auf Navy-Basis
(`navyRoles`: Flächen Navy-800/700/600, Text Weiß/`#B9C6D4`/`#7A8BA0`, Ränder
`rgba(255,255,255,0.08/0.14)`) — die Werte des früheren dunklen Schemas, jetzt auf diese eine
Fläche begrenzt (#654).

Die globale Leiste (Rail, #786, Mockup 2a) verwendet im hellen Schema das Rollenset `railRoles`,
eine Stufe dunkler als die Seitenleiste (Grund Navy-900, Hover Navy-800, Aktivkachel Navy-700
mit Navy-600-Rahmen, Text Weiß/`#99A1AB`/`#7A8BA0`) — so lesen sich globale und Space-Ebene auf
einen Blick auseinander. Im dunklen Schema folgt die Rail wie die Seitenleiste dem Carbon-Schema;
Carbon hat keine dunklere Stufe, die Trennung übernimmt der Standardrahmen.

`fg-3` (`#7A8BA0`) erreicht in `navyRoles` gegen `bg-1` 4,65:1, gegen `bg-2` 3,80:1 und gegen
`bg-3` 2,99:1; in `railRoles` (eine Stufe dunklere Flächen) gegen `bg-1` 5,26:1, gegen `bg-2`
4,65:1 und gegen `bg-3` 3,80:1 — je Rollenset unterschreitet nur `bg-3` (und im Navy-Set
zusätzlich `bg-2`) die 4,5:1-Schwelle (#853). Das ist folgenlos, weil `fg-3` dort nie als Text
auf Hover- oder Aktivflächen landet: Rail-Kacheln zeigen im Hover `fg-2` (inaktiv, 6,20:1 gegen
`bg-2`) bzw. `fg-1` (aktiv, Weiß) — nie `fg-3`. In der Seitenleiste nutzt der einzige `fg-3`-Text
außerhalb der Rail (`MuiOutlinedInput`-Hover-Rahmen im Umbenennen-Feld von `ChatList.tsx`) nur
die Rahmenfarbe, für die die 3:1-UI-Schwelle gilt — dort erfüllt (3,80:1 auf `bg-2`). Die
Space-Navigation-Einträge der Seitenleiste bleiben bei 72 % Weiß, nicht `fg-3`. Eine künftige
Komponente, die `fg-3` als Text auf `bg-2`/`bg-3` dieser Rollensets einsetzt, verletzt 2.4 und
braucht einen dunkleren Ton oder eine andere Rolle.

### 2.3 Regeln

- **Nur Rollen in Komponenten.** Kein Hex-Wert und kein Skalenwert in Komponenten-Code; alles
  läuft über die Rollen aus 2.2. Zulässige Ausnahmen: der Markenblock der Anmeldeseite, die
  Seitenleiste und die globale Leiste (siehe nächste Punkte), Diagramm-Farbreihen,
  Hover-/Aktiv-Stufen von Blau in Schaltflächen-Definitionen.
- **Die Seitenleiste ist im hellen Schema Navy, im dunklen folgt sie dem dunklen Schema**
  (#654). Hell ist sie der bewusste Kontrastblock der App (Rollenset `navyRoles`); dunkel
  verschmilzt sie wie bei den Claude-Docs mit der Carbon-Grundfläche, getrennt durch den
  Standardrahmen. Zwei Textstellen weichen hart codiert von den Rollen ab (#853, Ausnahme laut
  vorigem Punkt): Die Space-Navigation-Einträge nutzen `rgba(255,255,255,0.72)` — auf allen drei
  Navy-Flächen AA-konform (6,20–8,88:1). Die Overline-Beschriftungen ("Chats") nutzen
  `rgba(255,255,255,0.55)`, aber ausschließlich auf `bg-1` (5,70:1); auf `bg-2`/`bg-3` würde der
  Wert auf 5,03:1 bzw. 4,28:1 fallen — eine künftige Verwendung auf diesen Flächen bräuchte einen
  höheren Alpha-Wert.
- **Die globale Leiste folgt derselben Regel eine Stufe dunkler** (Rollenset `railRoles`,
  #786). Ihre Aktivkachel (Navy-700 auf Navy-900, Rahmen Navy-600) liegt als Flächenkontrast
  unter 3:1 — zulässig, weil der Zustand nicht allein über die Fläche getragen wird: die
  Textfarbe wechselt auf Weiß und `aria-current` zeichnet den Eintrag programmatisch aus
  (siehe 2.4).
- **Der globale Verwaltungsrahmen** (#787, Mockup 2b) nutzt ausschließlich die Rollen aus 2.2:
  Sekundärspalte auf `bg-2`, Aktivkarte auf `bg-1` mit `border-strong`-Rahmen. Auch hier liegt
  die Zustandsfläche unter 3:1 — getragen wird der Zustand wie bei der Rail über Textfarbe,
  Schriftgewicht und `aria-current`. Das **„Global"-Badge** leitet sich mit 10 % (Fläche) und
  40 % (Rahmen) aus dem Akzent ab — analog zum 32-%-Fokusring aus 4.4 — und folgt damit einem
  Branding-Override; der Geltungsbereich steht immer als sichtbarer Text im Chip, nie nur als
  Farbe.
- **Akzent ist austauschbar.** Die Branding-Konfiguration (Issues #582/#583) darf `accent`
  ersetzen. Deshalb darf keine Komponente sich auf „Blau" verlassen (z. B. Blau hart mit einem
  Icon mischen) — sie verlässt sich auf die Rolle.
- **Semantikfarben tragen Bedeutung, keine Stimmung.** Eine Verweigerungs-Antwort im Zitierzwang
  ist keine Warnung und erhält keine Signalfarbe (siehe redesign-prompt.md §4).

### 2.4 Kontrast

Text mindestens 4,5:1 gegen seine Fläche, große Schrift (ab 24 px bzw. 19 px fett) und
UI-Komponenten/Grafik mindestens 3:1 — in beiden Schemata. Die Rollen aus 2.2 erfüllen das in
den vorgesehenen Kombinationen (`fg-*` auf `bg-*`, `accent-fg` auf `accent`); wer andere
Kombinationen bildet, weist den Kontrast im PR nach. Ausnahme: `fg-3` in `navyRoles`/`railRoles`
erfüllt das nicht gegen `bg-2`/`bg-3` (siehe 2.2) — dort ist `fg-3` als Text ausgeschlossen, nicht
nachgewiesen. Details regelt die [Barrierefreiheits-Richtlinie](./accessibility.md).

---

## 3 · Typografie

### 3.1 Schriftentscheidung

| Rolle | Schrift | Lizenz |
|---|---|---|
| Fließtext & Überschriften | **Quicksand** | SIL Open Font License — im Repo via `@fontsource/quicksand` |
| Rückfall im Stapel | Inter | SIL Open Font License |
| Mono (Aktenzeichen, Werte, Code) | **JetBrains Mono** | SIL Open Font License |

Die Zielbild-Mockups verwenden „Sklow", eine Firmenschrift ohne freie Lizenz. **Sie wird nicht
ins Repository aufgenommen.** Quicksand (#658) ist ihre freie Entsprechung — rund-geometrisch,
einstöckiges g, monolineare Strichführung — und damit Standard der offenen Codebasis; Inter
bleibt als Rückfall im Stapel. Eine Firmenschrift kann ein Betreiber
später über die Branding-Konfiguration nachladen; die Schriftstapel enden deshalb immer in
`system-ui, sans-serif`.

### 3.2 Skala

Feste Pixelstufen; die App nutzt im Alltag 11–30 px, die großen Stufen gehören Markenmomenten
(Anmeldeseite, Leerzustände):

| Stufe | Größe | Typische Verwendung |
|---|---|---|
| 2xs | 11 px | Versal-Etiketten, Tabellen-Metadaten |
| xs | 12 px | Metadaten, Chips, Eyebrow |
| sm | 14 px | Sekundärtext, Tabellen, Seitenleiste |
| base | 16 px | Fließtext, Chat-Antworten |
| md | 18 px | Chat-Titel, hervorgehobener Text |
| lg | 20 px | h4-Äquivalent |
| xl | 24 px | Abschnittsüberschriften (h3) |
| 2xl | 30 px | Seitenüberschriften (h2) |
| 3xl | 36 px | große Seitenköpfe |
| 4xl–7xl | 48–104 px | Markenblock, nicht im Arbeits-UI |

Die Arbeitsflächen folgen zusätzlich dem **Feinraster aus Mockup 1a** (#658): Fließtext
14,5 px / 1.65, UI-Listen 13 px, Metadaten 11–12,5 px, Versal-Etiketten 9,5 px / +0.12em,
Seitentitel 27 px, Chat-Kopf 18 px. Das Theme bildet dieses Raster ab; die Skalenstufen oben
bleiben das Vokabular für Markenmomente.

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

| Stufe | Wert | Verwendung |
|---|---|---|
| xs | 4 px | kleine Chips, Tastenkürzel |
| sm | 6 px | Schaltflächen, Menüeinträge (#658, Mockup 1a) |
| **md** | **10 px** | **Standard: Karten, Eingaben, Dialoge** |
| lg | 16 px | große Karten, Nachrichtenblasen |
| xl | 24 px | Bühnenflächen |
| pill | 999 px | Rollen- und Statuschips |

### 4.3 Ebenen: Rahmen statt Schatten

- Ebene 0/1 (Seite, Karte): Trennung ausschließlich über `border` und `bg`-Abstufung. **Kein
  Schatten auf ruhenden Karten.**
- Ebene 2 (schwebend: Menü, Popover, @-Vorschlagsliste): Rahmen **plus** dezenter Schatten
  `0 2px 6px rgba(1,32,66,0.08)`.
- Ebene 3 (Dialog, Belegfenster): Rahmen plus `0 8px 24px rgba(1,32,66,0.10)`.
- Im dunklen Schema bleiben die Schattenwerte gleich; die Trennung leisten dort vor allem die
  helleren Flächenstufen.

### 4.4 Fokus

Sichtbarer Fokusring für jedes interaktive Element: 3 px in `accent` mit 32 % Deckung
(`0 0 0 3px rgba(18,144,239,0.32)`) zusätzlich zum Rahmen, in beiden Schemata deutlich.
Fokus wird nie unterdrückt, nur gestaltet. Umsetzung: Issue #585; Prüfung:
[Barrierefreiheits-Richtlinie](./accessibility.md).

### 4.5 Bewegung

Dauern 120 ms (klein: Hover, Chips), 200 ms (Standard: Menüs, Einblendungen), 360 ms (groß:
Belegfenster, Drawer); Kurve `cubic-bezier(0.22, 1, 0.36, 1)` (ease-out). Nur `transform` und
`opacity` animieren. Unter `prefers-reduced-motion` entfallen Bewegungen bzw. schrumpfen auf
Zustandswechsel.

---

## 5 · Komponentenregeln

### 5.1 Schaltflächen

| Variante | Ruhe | Hover | Aktiv |
|---|---|---|---|
| Primär | `accent`-Fläche, `accent-fg`-Text | Blau-600 (−8 % Helligkeit) | Blau-700 (−16 %) |
| Sekundär | `bg-1` mit `border-strong` | `bg-2` | `bg-3` |
| Still (Text/Ghost) | transparent, `accent`-Text | `bg-2` | `bg-3` |

Radius sm, Gewicht 500, 13,5 px, Höhe 34 px (kompakt 28 px), Beschriftung als Verb („Fragen",
„Anlegen", „Speichern"). Genau eine primäre Schaltfläche je Fläche. Auf Navy-Flächen gilt die
Dunkel-Spalte der Rollen. Zerstörende Aktionen: Sekundär-Variante mit Gefahr-Text, niemals eine
rote Primärfläche als Standardaktion.

### 5.2 Formularfelder

Beschriftung oberhalb als eigenständiges Label (12 px, `fg-2`, 5 px Abstand — kein
schwebendes Label im Feld). Das Feld liegt auf `bg-1` mit `border-strong`, Radius sm,
Haarlinien-Schatten und Höhe 40 px — keine gefüllte Grau-Fläche; im Dunkel-Schema hebt sich
das Feld stattdessen eine Flächenstufe ab (`bg-2`, ohne Schatten). Suchfelder tragen keine
sichtbare Beschriftung, sondern Platzhalter plus `aria-label` (Muster: Mockup-Suchfelder).
Hilfetext unterhalb in `fg-3`. Fokus: Rahmen in `accent` plus Fokusring. Fehler:
Rahmen und Meldungstext in Gefahr, Meldung programmatisch dem Feld zugeordnet
(Details: [Barrierefreiheits-Richtlinie](./accessibility.md), 2.7).
Pflichtfelder werden nicht mit Sternchen markiert — optionale Felder tragen „(optional)".
Zugangsdaten immer als Kennwortfeld, nie im Klartext zurückgespiegelt.

### 5.3 Tabellen

Spaltenköpfe im Eyebrow-Muster (`fg-3`), Zeilen durch `border` getrennt, keine Zebrastreifen.
Zeilen-Hover `bg-2`; ist die Zeile Navigationsziel (z. B. Wissensbibliotheken → Detailseite),
ist die **ganze Zeile ein Link** mit einer Tab-Position. Zahlen rechtsbündig in Mono, Stände
und Metadaten in `fg-3`. Unterhalb Tablet-Breite werden breite Tabellen zu Kartenlisten
(Muster: Mockup 1d, Issue #595).

### 5.4 Karten

`bg-1` oder `bg-2` mit `border`, Radius md–lg, Innenabstand 16–24 px, kein Schatten. Klickbare
Karten heben sich im Hover über `border-strong` und `bg-2` ab, nicht über Schatten oder
Skalierung.

### 5.5 Chips und Etiketten

Pill-Radius, 12 px, Gewicht 500, dezent — Umriss (`border-strong` + `fg-2`) oder stille
Tintfläche (`bg-3`), **keine Signalfarben**. Feste Wortlisten:

- **Rollen:** Leser · Bearbeiter · Verwalter · Eigentümer
- **Verteilungsstufen:** privat · geteilt · organisationsweit
- **Herkunft:** Upload · Dateisystem · Webverzeichnis · RSS-Feed
- **Space-Art:** Persönlich · Team

Ein laufender Vorgang („Lauf läuft · 62 %") ist Text mit Fortschrittsangabe in `fg-2`, kein
farbiger Chip.

### 5.6 Menüs und Overlays

`bg-1`, `border`, Radius md, Schatten der Ebene 2/3, Einträge 14 px mit 8–12 px Innenabstand,
Hover `bg-2`, zerstörende Einträge in Gefahr-Text am Ende, durch Trennlinie abgesetzt.
Vollständige Tastaturbedienung (Pfeile, Enter, Escape) ist Teil der Komponente, nicht der Kür.

### 5.7 Leer-, Lade- und Fehlerzustände

- **Leer:** ein Satz, was hier stünde, plus die eine Handlung, die ihn füllt. Keine
  Illustrationsfriedhöfe.
- **Laden:** Skeleton in `bg-3` ohne Layoutsprung; laufende Hintergrundvorgänge bleiben über
  Seitenwechsel sichtbar (Muster Indizierung).
- **Fehler:** deutsch, ruhig, mit nächstem Schritt. Signalfarbe nur am betroffenen Element,
  nicht flächig.

### 5.8 Fußnoten und Fundstellen (Signaturmuster des Chats)

Belege erscheinen als hochgestellte Ziffern in `accent` im Antworttext (auch Bereiche „1–3");
unter der Antwort folgt der Fundstellen-Block: Eyebrow „Fundstellen", Zeile „n Stellen in
m Dokumenten", je Dokument Ziffern + Titel (Gewicht 500) + Fundort und Stand in `fg-3`.
Die Verweigerung im Zitierzwang ist eine vollwertige Auskunft in normaler Antwort-Typografie —
kein Banner, keine Signalfarbe (Ausgestaltung: Issues #590/#592, Mockups 1a/1i).

---

## 6 · Sprache und Begriffe

UI-Sprache ist Deutsch, Anrede „Sie", `aria-label` deutsch. Verbindliche Begriffe:

| Begriff | Bedeutung / Regel |
|---|---|
| **Space** | Arbeitsraum mit Datenquellen, Chats, Mitgliedern. Bleibt unübersetzt. |
| **Chat** | in sich geschlossene Unterhaltung in einem Space; benennbar |
| **Wissensbibliothek** | benannter Wissensbestand; kurz „Bibliothek", im Fließtext auch „Bestand" |
| **Datenquellen** | die einem Space zugeordneten Bibliotheken |
| **Herkunft** | woher eine Bibliothek ihre Dokumente bezieht (Upload, Dateisystem, Webverzeichnis, RSS-Feed) |
| **Verteilungsstufe** | privat · geteilt · organisationsweit |
| **Rolle** | Leser · Bearbeiter · Verwalter · Eigentümer |
| **Fundstellen** | Belegblock unter einer Antwort |
| **Belege / Belegfenster** | alle Fundstellen einer Antwort in der seitlichen Leiste |
| **Systemverwaltung** | Admin-Bereich und -Rolle |
| **Anmeldung / Kennung** | nie „Login"/„Username" in Nutzertexten |
| Claim | „Fragen. Belegen. Entscheiden." |

Nicht verwendet werden: „Workspace", „Library", „Datei-Upload" (stattdessen „Upload"),
„User", englische Mischformen in Nutzertexten. Technische Enum-Werte und API-Felder bleiben
englisch (Projektsprache, siehe AGENTS.md).

---

## 7 · Branding-Überschreibbarkeit

Von Anfang an gilt: **Produktname, Claim, Logo, Akzentfarbe und Farbschema-Vorgabe sind
Konfiguration**, nicht Code (Issues #582/#583). Daraus folgt für jede Komponente:

- Name, Claim und Logo werden nie hart eingebettet, sondern aus der Branding-Quelle bezogen
  (mit OPAA-Standard als Fallback).
- Akzentfarbe nur über die Rolle `accent`; abgeleitete Zustände (Hover −8 %, Aktiv −16 %,
  Fokusring 32 % Deckung) werden aus der konfigurierten Farbe berechnet, nicht aus Blau-600/700
  fest verdrahtet.
- Die Kontrastprüfung der konfigurierten Farbe warnt, blockiert aber nicht (#583).

---

## 8 · Geltung und Pflege

- Diese Guidelines sind Prüfmaßstab für jedes Frontend-Review; das PR-Template verweist auf die
  [Barrierefreiheits-Prüfliste](./accessibility.md).
- Änderungen an den Guidelines laufen als eigener PR mit Begründung; Wertänderungen werden mit
  der Token-Ebene (#581) synchron gehalten — die Token-Datei setzt um, dieses Dokument
  entscheidet.
- Die abgelösten Stitch-Entwürfe (`chat-interface.html`, `document-browser.html`,
  `system-settings.html`) sind historisch und keine Gestaltungsreferenz mehr.
