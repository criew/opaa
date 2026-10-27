# Barrierefreiheits-Richtlinie

OPAA zielt auf die öffentliche Verwaltung — dort ist Barrierefreiheit nach **BITV 2.0** keine
Kür, sondern Pflicht. Diese Richtlinie legt das Zielniveau fest und macht es prüfbar: Die
Prüfliste in Abschnitt 2 ist für **jedes UI-Issue und jeden Frontend-PR verbindlich**; das
PR-Template verweist hierher. Gestaltungsregeln (Farben, Fokusring, Zustände) stehen in den
[Design-Guidelines](./guidelines.md) — diese Richtlinie regelt, _was nachgewiesen_ wird und
_wie geprüft_ wird.

---

## 1 · Zielniveau

**Rechtliche Untergrenze bleibt BITV 2.0 / WCAG 2.1, Konformitätsstufe AA** (über EN 301 549).
**Zielniveau ist WCAG 2.2, Konformitätsstufe AA.** Ob BITV und EN 301 549 v4 WCAG 2.2 bereits
verbindlich machen, ist bewusst nicht vorab geprüft; 2.2 AA ist deshalb Ziel, nicht Pflicht:

- Ein Verstoß gegen **WCAG 2.1 AA** ist ein Mangel und wird im PR behoben.
- Verfehlt ein PR eines der **sechs mit WCAG 2.2 neuen AA-Kriterien**, begründet er das und legt ein
  Issue an (Abschnitt 4), statt es stillschweigend zu übergehen.

Die sechs neuen Kriterien stehen in der Prüfliste bei ihrem Thema und tragen den Vermerk
„WCAG 2.2, Ziel":

| Kriterium | Kurz | Prüfliste |
| --- | --- | --- |
| 2.4.11 Fokus nicht verdeckt (Minimum) | fokussiertes Element nie ganz verdeckt | 2.2 |
| 2.5.7 Ziehbewegungen | jede Ziehbewegung auch mit einem Klick bedienbar | 2.9 |
| 2.5.8 Zielgröße (Minimum) | Klickziele mindestens 24 × 24 px oder mit Abstand | 2.9 |
| 3.2.6 Konsistente Hilfe | Hilfe überall an derselben Stelle | 2.3 |
| 3.3.7 Redundante Eingabe | nichts zweimal eingeben müssen | 2.7 |
| 3.3.8 Barrierefreie Authentifizierung (Minimum) | Anmeldung ohne Gedächtnis- oder Rätseltest | 2.7 |

Das Zielniveau gilt:

- in **beiden Farbschemata** — ein PR, der nur eines prüft, ist unvollständig;
- auch **mit konfiguriertem Branding**: Die Akzentfarbe ist Betreiber-Konfiguration, deshalb
  warnt die Branding-Verwaltung bei kontrastschwachen Farben (Issue #583) und das
  Abschluss-Audit prüft mit einer grenzwertigen Farbe (Issue #598);
- für alle Ein- und Ausgabewege: Maus, Tastatur, Touch, Screenreader, Zoom.

Einzelne WCAG-Kriterien oberhalb von AA (z. B. 2.4.8 Standort) sind willkommen, aber nicht
Prüfmaßstab.

## 2 · Prüfliste je UI-Issue

Jeder Punkt gilt für die vom PR berührten Oberflächen. Nicht einschlägige Punkte (z. B.
Formularregeln in einem PR ohne Formular) entfallen ersatzlos — alles andere wird geprüft.

### 2.1 Tastatur

- [ ] Jedes interaktive Element ist mit der Tastatur erreichbar **und** bedienbar
      (Tab/Umschalt+Tab, Enter/Leertaste, Pfeiltasten in Menüs und Listen).
- [ ] Die Fokus-Reihenfolge folgt der visuellen Logik; keine Tastaturfalle.
- [ ] Escape schließt Overlays (Dialog, Menü, Belegfenster); der Fokus kehrt zum
      auslösenden Element zurück.
- [ ] Öffnende Overlays erhalten den Fokus; Hintergrund ist nicht fokussierbar (Fokusfang
      im Dialog).
- [ ] Es gibt keine Funktion, die ausschließlich per Maus (Hover, Drag) erreichbar ist.

### 2.2 Sichtbarer Fokus

- [ ] Jedes fokussierbare Element zeigt den Fokusring des Designsystems
      ([Guidelines 4.4](./guidelines.md)) — in beiden Farbschemata deutlich sichtbar und nicht von
      einem Behälter mit `overflow: hidden` abgeschnitten.
- [ ] Fokus wird nie per `outline: none` ohne gleichwertigen Ersatz unterdrückt.
- [ ] _WCAG 2.2, Ziel — 2.4.11 Fokus nicht verdeckt:_ Ein fokussiertes Element wird nie ganz von
      eigenen Ebenen verdeckt (feste Kopf- und Fußleisten, Popup-Benachrichtigungen, Cookie- oder
      Hinweisleisten). Prüfung: im Tastatur-Durchgang (3.2) bis ans Seitenende tabben, bei
      200 % Zoom und offener Benachrichtigung.

### 2.3 Name, Rolle, Wert

- [ ] Native Elemente zuerst (`button`, `a`, `label`, `table`, `nav`); ARIA nur, wo natives
      HTML nicht reicht.
- [ ] Jedes Bedienelement hat einen zugänglichen Namen; reine Icon-Schaltflächen tragen ein
      **deutsches** `aria-label` (Projektsprache, siehe AGENTS.md).
- [ ] Zustände sind programmatisch: `aria-expanded`, `aria-selected`, `aria-current`,
      `aria-disabled` entsprechen dem sichtbaren Zustand.
- [ ] Überschriftenhierarchie ohne Sprünge; Landmarken (`header`/`nav`/`main`) vorhanden und
      benannt, sobald mehrere gleichartige existieren.
- [ ] Jede Seite setzt einen deutschen `document.title`.
- [ ] _WCAG 2.2, Ziel — 3.2.6 Konsistente Hilfe:_ Hilfe-Angebote (Hilfelink, Kontakt,
      Handbuch-Verweis) stehen auf allen Seiten, die sie anbieten, an derselben relativen Stelle und
      in derselben Reihenfolge. Prüfung: zwei Seiten mit Hilfe-Angebot nebeneinander vergleichen.

### 2.4 Kontrast

- [ ] Text ≥ 4,5:1 gegen seine Fläche; große Schrift (ab 24 px, oder 19 px fett) ≥ 3:1.
- [ ] Das gilt auch für Text in Hinweis-, Warn- und Statusfarben (`danger`, `warning`,
      `success`): nur über die Rolle, nie aufgehellt oder unter gesenkter Deckkraft, auch nicht
      als Begründung an einem gesperrten Element ([Guidelines 2.4](./guidelines.md)).
- [ ] UI-Komponenten und grafische Kennzeichen (Rahmen von Eingabefeldern, Icons,
      Fokusring, Diagramme) ≥ 3:1. Der Fokusring ist deckend und hält 3:1 gegen jede Fläche, an
      die er grenzt: Grund, erhöhte und gedämpfte Fläche, getönte Auswahl- und Hinweisflächen;
      an Akzentflächen über den Abstand zum Element ([Guidelines 4.4](./guidelines.md)).
      Umrandete Eingaben zeigen den Fokus stattdessen über ihren 2 px breiten Rahmen in `accent`
      bzw. `danger` (≥ 4,5:1). Eigene Fokusstile mit Transparenz sind ausgeschlossen.
- [ ] Beide Farbschemata geprüft. Die Rollen-Kombinationen aus
      [Guidelines 2.2](./guidelines.md) gelten als nachgewiesen; jede andere Kombination
      wird im PR belegt.
- [ ] Farbe ist nie der einzige Informationsträger (zusätzlich Text, Icon oder Muster).
- [ ] Steht Text über einem Bild, das eine Installation selbst hinterlegt (Hintergrundbild der
      Anmeldeseite, #1910), ist der Nachweis über den **schlechtesten Fall** zu führen: die feste
      Deckschicht über einem rein weißen und einem rein schwarzen Bild. Ein Nachweis gegen das
      gerade hochgeladene Motiv sagt über das nächste nichts. Beispiel:
      `frontend/src/features/auth/components/AuthLayout.contrast.test.ts`.

### 2.5 Zoom und Reflow

- [ ] Bei 200 % Browser-Zoom gehen weder Inhalt noch Funktion verloren.
- [ ] Bei 320 px Viewport-Breite (bzw. 1280 px @ 400 %) entsteht kein horizontales
      Scrollen für Fließtext (WCAG 1.4.10); Ausnahmen nur für inhärent breite Inhalte
      (Tabellen, Code) mit eigenem Scrollbereich.
- [ ] Text-Abstände (WCAG 1.4.12) brechen das Layout nicht.

### 2.6 Bewegung

- [ ] `prefers-reduced-motion` reduziert Animationen auf Zustandswechsel
      ([Guidelines 4.5](./guidelines.md)).
- [ ] Keine Information wird ausschließlich über Bewegung vermittelt; nichts blinkt öfter
      als dreimal pro Sekunde.

### 2.7 Formulare und Fehler

- [ ] Jedes Feld hat ein programmatisch verknüpftes, sichtbares Label; Platzhalter ersetzen
      keine Labels.
- [ ] Hilfetexte und Fehlermeldungen sind dem Feld per `aria-describedby` zugeordnet;
      Fehler werden als Text benannt (nicht nur roter Rahmen) und sind deutsch.
- [ ] Optionale Felder tragen „(optional)" — Pflicht ist der Normalfall
      ([Guidelines 5.2](./guidelines.md)).
- [ ] Nach einem Abschickfehler bleibt die Eingabe erhalten; der Fokus geht auf die erste
      Fehlermeldung oder eine Fehlerzusammenfassung.
- [ ] _WCAG 2.2, Ziel — 3.3.7 Redundante Eingabe:_ Was die Person im selben Vorgang schon
      eingegeben hat, wird vorbelegt oder zur Auswahl angeboten, nicht erneut abgefragt — auch
      nach einem Schritt zurück im Assistenten. Ausnahme: Sicherheitsabfragen (Kennwort
      bestätigen). Prüfung: Assistenten vor und zurück durchlaufen.
- [ ] _WCAG 2.2, Ziel — 3.3.8 Barrierefreie Authentifizierung:_ Die Anmeldung verlangt keinen
      kognitiven Test (Rätsel, Abtippen eines Bilds, Merken von Zeichen); Einfügen und
      Passwortmanager funktionieren in Kennungs-, Kennwort- und Codefeldern (kein Blockieren von
      `paste`, passendes `autocomplete`). Prüfung: Anmeldung mit Passwortmanager und per Einfügen.

### 2.8 Statusmeldungen und asynchrone Vorgänge

- [ ] Statusmeldungen ohne Fokuswechsel (Indizierungsfortschritt, „Antwort läuft ein",
      Speichern-Bestätigung) laufen über eine Live-Region (`aria-live="polite"`,
      `role="status"`).
- [ ] Ladezustände sind benannt (z. B. `aria-busy`, Skeleton mit Text-Alternative), nicht
      nur visuell.
- [ ] Popup-Benachrichtigungen pausieren ihren Timer bei Hover und Fokus (WCAG 2.2.1); Fehler,
      die eine Handlung verlangen, stehen auch am Ort ([Guidelines 5.9](./guidelines.md)).

### 2.9 Zeiger und Touch

- [ ] _WCAG 2.2, Ziel — 2.5.7 Ziehbewegungen:_ Was sich per Ziehen bedienen lässt (Sortieren,
      Verschieben, Schieberegler, Splitter), geht auch mit einzelnen Klicks oder Tippen, ohne
      Tastatur vorauszusetzen. Prüfung: jede Ziehfunktion einmal ohne Ziehen ausführen.
- [ ] _WCAG 2.2, Ziel — 2.5.8 Zielgröße:_ Klickziele messen mindestens 24 × 24 CSS-px, oder ein
      Kreis von 24 px Durchmesser um ihre Mitte überschneidet kein anderes Ziel. Ausgenommen sind
      Links im Fließtext und vom Browser gestaltete Elemente. Prüfung: kleine Ziele (Icon-Knöpfe,
      Chips, Checkboxen, Schließen-Kreuz) in den DevTools ausmessen.

## 3 · Prüfverfahren

Drei Stufen; die erste ist automatisiert, die anderen beiden sind Handarbeit am berührten
Ausschnitt.

### 3.1 Automatisiert

- `eslint-plugin-jsx-a11y-x` (ESLint-10-kompatibler Fork von `eslint-plugin-jsx-a11y`,
  Rückwechsel: #635) im Frontend-Lint, Regelset `recommended`, jeder Verstoß ein Lint-Fehler.
  Takt: an jedem PR.
- **axe-core** (`@axe-core/playwright`) in der Playwright-E2E-Suite
  (`e2e/tests/accessibility.spec.ts`, eingeführt mit #586). Takt: nach jedem Merge auf `main`,
  nächtlich und auf Anforderung (`.github/workflows/e2e.yml`), keine Bedingung eines PRs; ein
  Fehlschlag öffnet ein Alarm-Issue. Geprüft werden Anmeldung, Chat in beiden
  Farbschemata, Space-Seite, Wissensbibliotheken, Verwaltungsbereich, Benutzer-Einstellungen.
  Verstöße der Stufen
  „serious" und „critical" lassen die Prüfung fehlschlagen, „minor"/„moderate" erscheinen als
  Annotation im Report. Geprüft wird gegen die Regelsätze WCAG 2.0 und 2.1, A und AA
  (`e2e/fixtures/a11y.ts`) — also die rechtliche Untergrenze; die neuen Kriterien von WCAG 2.2
  prüft der Handdurchgang (3.2).
- Gemessen wird stets der **ausklingende** Zustand: Vor jedem axe-Lauf wartet die Prüfung auf das
  Ende der laufenden endlichen Animationen und Übergänge der Seite (`e2e/fixtures/a11y.ts`), in
  mehreren Durchgängen, damit gestaffelte Ketten mitgenommen werden. axe leitet die wirksame Farbe
  aus dem Bild ab, das es gerade sieht — während einer Einblendung sind das Zwischenwerte, die es
  in keinem Endzustand gibt, und `color-contrast` meldet dann Farbpaare, die die Oberfläche nie
  zeigt (#1643). Ausgenommen bleibt, was nie zur Ruhe käme: unendliche Wiederholungszahl
  (Ladeanzeigen), unbegrenzte Dauer und angehaltene Animationen. Läuft nach der Wartezeit noch
  etwas, erscheint das als Annotation im Report, statt still gemessen zu werden. Die Regel
  deswegen abzuschalten wäre der falsche Weg — sie ist genau die, die einen echten Kontrastfehler
  findet.
- Ausnahmen werden einzeln im Code begründet und mit einem Issue verknüpft — nie pauschal
  abgeschaltet: im Lint als `// eslint-disable-next-line jsx-a11y-x/<regel>` mit Begründung und
  Issue direkt darüber, in der E2E-Suite als dokumentierter `exclude`-/`disableRules`-Eintrag
  am Aufrufort (`exclude` für eine Komponente, `disableRules` für eine Regel; derzeit gibt es
  keine). Wer eine Ausnahme hinzufügt,
  legt das Issue an, das sie wieder entfernt.

### 3.2 Tastatur-Durchgang (jeder PR mit UI-Änderung)

Vom Einstiegspunkt der geänderten Oberfläche aus, ohne Maus:

1. Mit Tab durch alle interaktiven Elemente — Reihenfolge, Sichtbarkeit des Fokus (auch: nicht
   verdeckt), Erreichbarkeit prüfen.
2. Jede Aktion ausführen (Enter/Leertaste/Pfeiltasten), Overlays öffnen und mit Escape
   schließen, Fokus-Rückkehr beobachten.
3. Einmal in beiden Farbschemata, wenn der PR Farben oder Zustände berührt.

### 3.3 Screenreader-Stichprobe (bei neuen Mustern und Abschluss-Audits)

Pflicht, wenn ein PR ein neues Interaktionsmuster einführt (z. B. @-Vorschlagsliste,
Belegfenster, Assistent) — nicht bei reinen Stiländerungen:

- **VoiceOver** (macOS, Safari) als Mindestprüfung; **NVDA** (Windows, Firefox/Chrome) nach
  Möglichkeit, spätestens im Abschluss-Audit (Issue #598).
- Geprüft wird: Werden Name, Rolle und Zustand angesagt? Wird ein Seitenwechsel angesagt?
  Sind Live-Meldungen hörbar, ohne den Arbeitsfluss zu unterbrechen?

### Werkzeuge

Kontrast: Browser-DevTools oder WebAIM Contrast Checker gegen die Token-Werte. Zoom: Browser
auf 200 %, Fenster auf 320 px. Reduzierte Bewegung: Systemeinstellung oder DevTools-Emulation.

## 4 · Nachweis im PR

Der PR hakt den Prüfpunkt im Template erst ab, wenn:

- die automatisierten Prüfungen grün sind,
- der Tastatur-Durchgang gemacht ist (ein Satz im PR genügt: was, womit, Befund),
- Abweichungen einzeln begründet und als Issue erfasst sind.

Wer einen Befund findet, der außerhalb des PR-Umfangs liegt, legt ein Issue an, statt ihn
stillschweigend zu übergehen.

## 5 · Geltung

Diese Richtlinie ändert sich per PR mit Begründung. Sie ergänzt die
[Design-Guidelines](./guidelines.md); bei Widerspruch im Barrierefreiheits-Teil gilt diese
Richtlinie.
