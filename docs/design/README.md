# UI-Design

Quelle der Wahrheit für das OPAA-UI-Design sind die **Design-Guidelines**. Die Token-Ebene
(`frontend/src/shared/theme/tokens.ts`) setzt sie um.

| Dokument                                   | Inhalt                                                                                                                       |
| ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| [guidelines.md](./guidelines.md)           | Verbindliche Gestaltungsregeln: Farben, Typografie, Abstände, Komponenten, Begriffe                                          |
| [accessibility.md](./accessibility.md)     | Barrierefreiheits-Richtlinie (Untergrenze BITV 2.0 / WCAG 2.1 AA, Ziel WCAG 2.2 AA): Prüfliste und Prüfverfahren je UI-Issue |
| [ui-pruefliste.md](./ui-pruefliste.md)     | Prüfliste für UI-Änderungen                                                                                                  |
| [redesign-prompt.md](./redesign-prompt.md) | Zielbild-Beschreibung der Oberfläche (Briefing des Redesigns)                                                                |
| [anmeldeseite-hintergrundbild.md](./anmeldeseite-hintergrundbild.md) | Mitgeliefertes Hintergrundbild der Anmeldeseite: Motiv, Prompt, Aufbereitung, Kontrastnachweis |

## Designsystem in Kürze

- **Farben:** Blau `#1292EE` auf Navy `#012142` und Weiß; helles und dunkles Schema gleichrangig
- **Schrift:** Quicksand mit Inter als Rückfall (Sklow ist Firmenschrift ohne freie Lizenz und bleibt außerhalb des Repos)
- **Flächen:** flach, 1-px-Rahmen statt Schatten, 10-px-Standardradius
- **Branding:** Produktname, Logo und Akzentfarbe sind Betreiber-Konfiguration, nicht Code
