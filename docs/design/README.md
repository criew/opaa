# UI-Design

Quelle der Wahrheit für das opaa-UI-Design sind die **Design-Guidelines**. Die Token-Ebene
(`frontend/src/shared/theme/tokens.ts`) setzt sie um.

| Dokument                                   | Inhalt                                                                                                                       |
| ------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- |
| [guidelines.md](./guidelines.md)           | Verbindliche Gestaltungsregeln: Farben, Typografie, Abstände, Komponenten, Begriffe                                          |
| [accessibility.md](./accessibility.md)     | Barrierefreiheits-Richtlinie (Untergrenze BITV 2.0 / WCAG 2.1 AA, Ziel WCAG 2.2 AA): Prüfliste und Prüfverfahren je UI-Issue |
| [ui-pruefliste.md](./ui-pruefliste.md)     | Prüfliste für UI-Änderungen                                                                                                  |
| [redesign-prompt.md](./redesign-prompt.md) | Zielbild-Beschreibung der Oberfläche (Briefing des Redesigns)                                                                |
| [anmeldeseite-hintergrundbild.md](./anmeldeseite-hintergrundbild.md) | Mitgeliefertes Hintergrundbild der Anmeldeseite: Motiv, Prompt, Aufbereitung, Kontrastnachweis |

## Designsystem in Kürze

- **Farben:** aus dem Markenkonzept: Dunkelblau `#242855`, Mittelblau `#0055A4` als Aktionsfarbe, Hellblau `#009DDB` als Akzent, dazu Weiß; helles und dunkles Schema (auf Dunkelblau) gleichrangig
- **Schrift:** Quicksand mit Inter als Rückfall (Sklow ist Firmenschrift ohne freie Lizenz und bleibt außerhalb des Repos)
- **Flächen:** flach, 1-px-Rahmen statt Schatten, 10-px-Standardradius
- **Branding:** Produktname, Logo und Akzentfarbe sind Betreiber-Konfiguration, nicht Code
