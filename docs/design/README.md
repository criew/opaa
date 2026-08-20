# UI-Design

Quelle der Wahrheit für das OPAA-UI-Design sind die **Zielbild-Mockups** und die daraus
abgeleiteten **Design-Guidelines**. Umgesetzt wird das Zielbild schrittweise über das
Redesign-Epic #600.

## Zielbild

| Dokument | Inhalt |
|---|---|
| [OPAA Mockups.html](<./OPAA Mockups.html>) | 9 Mockup-Seiten (Claude Design; zum Ansehen im Browser öffnen, JavaScript erforderlich) |
| [guidelines.md](./guidelines.md) | Verbindliche Gestaltungsregeln: Farben, Typografie, Abstände, Komponenten, Begriffe |
| [accessibility.md](./accessibility.md) | Barrierefreiheits-Richtlinie (BITV 2.0 / WCAG 2.1 AA): Prüfliste und Prüfverfahren je UI-Issue |
| [redesign-prompt.md](./redesign-prompt.md) | Zielbild-Beschreibung der Oberfläche (Briefing, aus dem die Mockups entstanden) |

### Mockup-Seiten

| Seite | Inhalt |
|---|---|
| 1a | Space mit Chats — Antwort mit Fundstellen und @-Vorschlag |
| 1h | Eingabezeile mit @-Vorschlag — Bibliothek eingrenzen oder Agent aufrufen |
| 1i | Belegfenster — seitliche Leiste mit allen Fundstellen |
| 1b | Neuen Space anlegen — Schritt „Datenquellen zuordnen" |
| 1c | Spaces — Übersicht als Kartenliste |
| 1d | Wissensbibliotheken — Übersicht als Tabelle |
| 1e | Neue Wissensbibliothek — Herkunft und Verbindung |
| 1f | Anmeldung — Verzeichnisdienst (OIDC) oder Kennung |
| 1g | Registrierung — Konto beantragen |

Die Seiten 1f (Kennung) und 1g zeigen Zielbild-Funktionen, die es im Backend noch nicht gibt —
siehe Epic #600, „Außerhalb des Umfangs".

### Designsystem in Kürze

- **Farben:** Blau `#0B6FBC` auf Navy `#012142` und Weiß; helles und dunkles Schema gleichrangig
- **Schrift:** Inter (Sklow ist Firmenschrift ohne freie Lizenz und bleibt außerhalb des Repos)
- **Flächen:** flach, 1-px-Rahmen statt Schatten, 10-px-Standardradius
- **Branding:** Produktname, Logo und Akzentfarbe sind Betreiber-Konfiguration, nicht Code

## Abgelöst: Stitch-Entwürfe

Die früheren Entwürfe aus [Google Stitch](https://stitch.withgoogle.com/) (Dunkelmodus,
Primärfarbe `#137fec`, Inter, 8-px-Radius) sind durch das Zielbild **abgelöst** und nur noch
historische Referenz:

| Screen | HTML | Screenshot |
|--------|------|------------|
| Chat-Schnittstelle | [chat-interface.html](./chat-interface.html) | [chat-interface.png](./chat-interface.png) |
| Dokument-Browser | [document-browser.html](./document-browser.html) | [document-browser.png](./document-browser.png) |
| Systemeinstellungen | [system-settings.html](./system-settings.html) | [system-settings.png](./system-settings.png) |
