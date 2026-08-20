# Barrierefreiheits-Richtlinie

OPAA zielt auf die öffentliche Verwaltung — dort ist Barrierefreiheit nach **BITV 2.0** keine
Kür, sondern Pflicht. Diese Richtlinie legt das Zielniveau fest und macht es prüfbar: Die
Prüfliste in Abschnitt 2 ist für **jedes UI-Issue und jeden Frontend-PR verbindlich**; das
PR-Template verweist hierher. Gestaltungsregeln (Farben, Fokusring, Zustände) stehen in den
[Design-Guidelines](./guidelines.md) — diese Richtlinie regelt, *was nachgewiesen* wird und
*wie geprüft* wird.

---

## 1 · Zielniveau

**BITV 2.0 / WCAG 2.1, Konformitätsstufe AA** (über EN 301 549). Das Zielniveau gilt:

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
      ([Guidelines 4.4](./guidelines.md)) — in beiden Farbschemata deutlich sichtbar.
- [ ] Fokus wird nie per `outline: none` ohne gleichwertigen Ersatz unterdrückt.

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

### 2.4 Kontrast

- [ ] Text ≥ 4,5:1 gegen seine Fläche; große Schrift (ab 24 px, oder 19 px fett) ≥ 3:1.
- [ ] UI-Komponenten und grafische Kennzeichen (Rahmen von Eingabefeldern, Icons,
      Fokusring, Diagramme) ≥ 3:1.
- [ ] Beide Farbschemata geprüft. Die Rollen-Kombinationen aus
      [Guidelines 2.2](./guidelines.md) gelten als nachgewiesen; jede andere Kombination
      wird im PR belegt.
- [ ] Farbe ist nie der einzige Informationsträger (zusätzlich Text, Icon oder Muster).

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

### 2.8 Statusmeldungen und asynchrone Vorgänge

- [ ] Statusmeldungen ohne Fokuswechsel (Indizierungsfortschritt, „Antwort läuft ein",
      Speichern-Bestätigung) laufen über eine Live-Region (`aria-live="polite"`,
      `role="status"`).
- [ ] Ladezustände sind benannt (z. B. `aria-busy`, Skeleton mit Text-Alternative), nicht
      nur visuell.

## 3 · Prüfverfahren

Drei Stufen; die erste ist automatisiert, die anderen beiden sind Handarbeit am berührten
Ausschnitt.

### 3.1 Automatisiert (jeder PR)

- `eslint-plugin-jsx-a11y-x` (ESLint-10-kompatibler Fork von `eslint-plugin-jsx-a11y`,
  Rückwechsel: #635) im Frontend-Lint, Regelset `recommended`, jeder Verstoß ein Lint-Fehler.
- **axe-core** (`@axe-core/playwright`) in der Playwright-E2E-Suite
  (`e2e/tests/accessibility.spec.ts`, eingeführt mit #586): Anmeldung, Chat in beiden
  Farbschemata, Space-Seite, Wissensbibliotheken, Verwaltungsbereich. Verstöße der Stufen
  „serious" und „critical" lassen die Prüfung fehlschlagen, „minor"/„moderate" erscheinen als
  Annotation im Report.
- Ausnahmen werden einzeln im Code begründet und mit einem Issue verknüpft — nie pauschal
  abgeschaltet: im Lint als `// eslint-disable-next-line jsx-a11y-x/<regel>` mit Begründung und
  Issue direkt darüber, in der E2E-Suite als Eintrag in `KNOWN_EXCEPTIONS` der Spec
  (`exclude` für eine Komponente, `disableRules` für eine Regel). Wer eine Ausnahme hinzufügt,
  legt das Issue an, das sie wieder entfernt.

### 3.2 Tastatur-Durchgang (jeder PR mit UI-Änderung)

Vom Einstiegspunkt der geänderten Oberfläche aus, ohne Maus:

1. Mit Tab durch alle interaktiven Elemente — Reihenfolge, Sichtbarkeit des Fokus,
   Erreichbarkeit prüfen.
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
