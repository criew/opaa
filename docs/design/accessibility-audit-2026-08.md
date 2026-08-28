# Barrierefreiheits-Abschluss-Audit (#598) — Prüfprotokoll

**Datum:** 28.08.2026 · **Prüfgegenstand:** `main` @ `a2f3fa9e`, lokales Deployment (Docker Compose, http://localhost:3000, dev-Auth als `dev-admin`, e2e-Seed-Datenprofil) · **Prüfmaßstab:** [`docs/design/accessibility.md`](accessibility.md) (BITV 2.0 / WCAG 2.1 AA) · **Werkzeuge:** axe-core 4.x (via `@axe-core/playwright`), Playwright/Chromium (tastaturgesteuerte Durchgänge, Accessibility-Tree-Stichproben, Viewport-/Motion-Emulation)

## 1 · Umfang und Methode

| Prüfbereich | Methode | Abdeckung |
|---|---|---|
| Automatisierte Regeln (2.3, 2.4, 2.7 tlw.) | axe-core, Schwelle „moderate“+ (Audit strenger als CI-Suite mit „serious“+) | 9 Seiten × hell/dunkel |
| Tastatur (2.1, 2.2) | Skriptgesteuerte reine Tastatur-Durchgänge der Kernabläufe | Shell-Fokusreihenfolge; Space anlegen; Space wechseln; Frage stellen; Fußnoten; Belegfenster (Escape/Fokusrückkehr); @-Vorschlag; Chat umbenennen/löschen; Bibliotheks-Assistent; Branding |
| Screenreader-Stichprobe (2.3, 2.8) | Accessibility-Tree-Snapshots (Playwright `ariaSnapshot`) der kritischen Muster | Chat-Verlauf mit Fußnoten, @-Vorschlagsliste, Belegfenster, Assistenten, Aktionen-Menüs |
| Zoom/Reflow (2.5) | Viewport 640 px (Proxy für 200 % @ 1280) und 320 px, Overflow-Messung | 9 Seiten |
| Bewegung (2.6) | `reducedMotion: reduce`-Emulation, Messung verbleibender Animations-/Transitionsdauern > 200 ms | Stichprobe 3 Seiten |
| Branding-Grenzfarbe | Grenzwertige Primärfarbe im Branding-Formular, Warnverhalten (#583) | Formular-Durchgang ohne Speichern |

**Einschränkungen:** (a) Die Anmeldeseite läuft im dev-Auth-Modus nicht an — sie ist durch die axe-E2E-Suite (OIDC-Stub) abgedeckt; ein Tastatur-Durchgang gegen echtes Keycloak steht aus. (b) Die Screenreader-Stichprobe erfolgte über den Accessibility-Tree (die Datengrundlage von VoiceOver & Co.), nicht mit nativem VoiceOver — eine menschliche VoiceOver-Sitzung bleibt als Restprüfung empfohlen. (c) 200 % Zoom wurde über den äquivalenten 640-px-Viewport geprüft (Browser-Zoom ist in Playwright nicht direkt emulierbar).

## 2 · Ergebnisse

### 2.1 Automatisiert (axe, hell + dunkel)

18 Läufe (9 Seiten × 2 Schemata). Drei Regelverstöße, alle als Issues erfasst (Abschnitt 3):

| Regel | Schwere | Seiten | Schema |
|---|---|---|---|
| `aria-hidden-focus` | serious | Administration → Branding (2 Vorschau-Panels) | hell + dunkel |
| `color-contrast` | serious | Spaces-Übersicht („Administrator“-Chip), Wissensbibliotheken („Eigentümer“-Chip) | nur dunkel |
| `heading-order` | moderate | Chat-Leerzustand (`h6` nach `h1`), Branding („Farbschema-Vorgabe“), Modelle (Accordion-`h3`) | hell + dunkel |

Ohne Befund: Einstellungen, Bibliothek anlegen, Space anlegen, Administration → Gruppen — in beiden Schemata.

### 2.2 Tastatur-Durchgänge

Alle Kernabläufe sind ohne Maus vollständig durchführbar:

| Ablauf | Ergebnis |
|---|---|
| Shell-Fokusreihenfolge (Skip-Link → Rail → Spalte → Inhalt) | ✓ Reihenfolge konsistent; Hinweis: auf der Chat-Seite fokussiert die Eingabe beim Laden automatisch, der Skip-Link ist damit nicht erster Tab-Stopp (bewusstes Muster: direkt arbeitsfähig) |
| Space anlegen (Assistent, alle Schritte) | ✓ vollständig per Tab/Enter durchlaufen, Space wurde angelegt |
| Space wechseln (Wechsler-Menü) | ✓ Enter öffnet, Fokus liegt im Menü, Pfeiltasten + Enter navigieren |
| Frage stellen | ✓ Eingabe erreichbar; Antwort-Eintreffen wird über Live-Region (`role=status`, „Antwort eingetroffen“) gemeldet |
| Fundstellen (Fußnoten-Links) | ✓ fokussier- und aktivierbar, beschriftet („Fundstelle 1: …“) |
| Belegfenster | ✓ Fokus wandert hinein, **Escape schließt und der Fokus kehrt zum Auslöser zurück** |
| @-Vorschlagsliste | ✓ erscheint nach `@`, per Pfeiltasten bedienbar |
| Chat umbenennen | ✓ Menüpunkt aktivierbar, Inline-Feld „Chat-Titel“ erhält den Fokus; ✗ nach Escape/Commit fällt der Fokus auf `body` (Befund #959) |
| Chat löschen | ✓ Bestätigung über nativen `window.confirm`-Dialog (nativ tastatur- und screenreader-tauglich; gestalterisch designsystem-fremd, kein Barriere-Befund) |
| Bibliothek anlegen (Assistent) | ✓ per Tab bedienbar |
| Branding (Formular inkl. Warnung) | ✓ Felder beschriftet und erreichbar |

### 2.3 Screenreader-Stichprobe (Accessibility-Tree)

- **Chat-Verlauf:** Frage und Antwort als Absätze, Fußnote als beschrifteter Link („Fundstelle 1: e2e-basisdokument.txt“), Aktionen („Im Dokument öffnen“, „Daumen hoch/runter“) mit klaren Namen, Antwort-Eintreffen als `status`-Live-Region. Die aktive Quellenreferenz ist als Schaltfläche „Referenz Alles-Wissen entfernen“ verständlich.
- **Chat-Aktionen-Menü:** korrektes `menu`/`menuitem`-Muster; die Einträge tragen den vollständigen Kontext im Accessible Name („Chat ‚…‘ umbenennen“ / „… löschen“) — vorbildlich, ein Screenreader-Nutzer weiß ohne Umgebungskontext, welcher Chat gemeint ist.
- **Belegfenster, @-Vorschlagsliste, Assistenten, Branding-Warnung:** Rollen und Beschriftungen vollständig; keine unbenannten interaktiven Elemente in den Stichproben.
- **Gegenbefund:** die `aria-hidden`-Vorschau der Branding-Seite enthält fokussierbare Elemente (Befund #956).

### 2.4 Zoom und Reflow

Kein horizontaler Overflow auf allen 9 Seiten — weder bei 640 px (200-%-Proxy) noch bei 320 px (Reflow-Prüfung nach WCAG 1.4.10). ✓

### 2.5 Bewegung

Unter `prefers-reduced-motion: reduce` verbleiben keine Animationen oder Transitionen über 200 ms (Stichprobe Spaces-Übersicht, Chat, Einstellungen). ✓

### 2.6 Branding-Grenzfarbe

Grenzwertige Primärfarbe `#9BCEFA` im Branding-Formular: die Kontrast-Warnung aus #583 erscheint zuverlässig; das Feld ließ sich ohne Speichern zurücksetzen. ✓

## 3 · Befunde

| Issue | Schweregrad | Befund |
|---|---|---|
| [#956](https://github.com/criew/opaa/issues/956) | hoch (axe serious) | Branding-Vorschau: `aria-hidden` mit fokussierbaren Elementen (`aria-hidden-focus`) |
| [#957](https://github.com/criew/opaa/issues/957) | hoch (axe serious) | Rollen-Chips „Administrator“/„Eigentümer“ unter 4,5:1 im Dunkelschema |
| [#958](https://github.com/criew/opaa/issues/958) | mittel (axe moderate) | Übersprungene Überschriftenebenen auf Chat-, Branding- und Modelle-Seite |
| [#959](https://github.com/criew/opaa/issues/959) | niedrig | Fokusverlust nach Escape/Commit beim Inline-Umbenennen eines Chats |

Keine „critical“-Befunde.

## 4 · Bewertung

Die Design-Migration erreicht ihr Barrierefreiheits-Ziel: **alle Kernabläufe sind vollständig tastaturbedienbar**, die kritischen Interaktionsmuster (Belegfenster-Fokusfalle mit Rückkehr, Live-Region für Antworten, kontextvollständige Menü-Beschriftungen) sind korrekt umgesetzt, Reflow und `prefers-reduced-motion` sind sauber, und das Branding-Warnverhalten fängt grenzwertige Primärfarben ab.

Die vier Befunde sind eng umrissen und mit klarer Fix-Richtung als Issues erfasst; keiner blockiert die Nutzung, die beiden „hoch“-Befunde (#956, #957) sollten zeitnah behoben werden. Als Restprüfung empfohlen: eine native VoiceOver-Sitzung und ein Tastatur-Durchgang der echten Keycloak-Anmeldeseite.
