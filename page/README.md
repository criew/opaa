# Landing Page

Die öffentliche Einstiegsseite des Projekts. `index.html` ist eine einzelne,
in sich geschlossene Seite ohne Build-Schritt — Stile und Skripte stehen darin,
Bilder liegen in `img/`. Zum Ansehen genügt es, die Datei im Browser zu öffnen.

Inhaltlich leitet sie sich aus [`docs/market/MESSAGING.md`](../docs/market/MESSAGING.md)
ab; Positionierungsänderungen beginnen dort, nicht hier.

## Wo das landet

Veröffentlicht wird über GitHub Pages aus dem Branch `gh-pages`. Dieser Branch
wird nicht von Hand gepflegt, sondern von zwei Workflows beschrieben, die sich
das Verzeichnis teilen:

| Adresse | Inhalt | Erzeugt von |
|---|---|---|
| `https://criew.github.io/opaa/` | diese Landing Page | `.github/workflows/landing-page.yml`, bei jeder Änderung an `page/` |
| `https://criew.github.io/opaa/report/` | täglicher Fortschrittsbericht samt Archiv, Rohdaten und Atom-Feed | `.github/workflows/daily-report.yml`, nächtlich |

Beide committen auf denselben Branch und teilen sich deshalb eine
`concurrency`-Gruppe. Die Landing-Page-Veröffentlichung räumt im
Wurzelverzeichnis auf, lässt `report/` aber unangetastet; der Report schreibt
ausschließlich unterhalb von `report/`.

Die Rohdaten unter `report/data/` sind der eigentliche Bestand: Der Report
erzeugt aus ihnen bei jedem Lauf sämtliche Seiten neu, damit Layoutänderungen
rückwirkend gelten. Sie dürfen nicht gelöscht werden — sonst ist die Historie
verloren.

## Links, die stimmen müssen

- Testinstallation: `https://opaa.ewerlin.com`
- Repository: `https://github.com/criew/opaa`
- Fortschrittsbericht: `report/` (relativ, damit die Seite auch lokal und in
  einem Fork funktioniert)
