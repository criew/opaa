# Landing Page

Die öffentliche Einstiegsseite des Projekts. `index.html` ist eine einzelne,
in sich geschlossene Seite ohne Build-Schritt — Stile und Skripte stehen darin,
Bilder liegen in `img/`. Zum Ansehen genügt es, die Datei im Browser zu öffnen.

Inhaltlich leitet sie sich aus [`docs/market/MESSAGING.md`](../docs/market/MESSAGING.md)
ab; Positionierungsänderungen beginnen dort, nicht hier.

## Wo das landet

Ausgeliefert wird unter `https://opaa.io` vom VPS des Maintainers. Gebaut wird
weiterhin in den Branch `gh-pages`; den beschreiben zwei Workflows, die sich das
Verzeichnis teilen:

| Adresse | Inhalt | Erzeugt von |
|---|---|---|
| `https://opaa.io/` | diese Landing Page | `.github/workflows/landing-page.yml`, bei jeder Änderung an `page/` |
| `https://opaa.io/report/` | täglicher Fortschrittsbericht samt Archiv, Rohdaten und Atom-Feed | `.github/workflows/daily-report.yml`, nächtlich |

Beide committen auf denselben Branch und teilen sich deshalb eine
`concurrency`-Gruppe. Die Landing-Page-Veröffentlichung räumt im
Wurzelverzeichnis auf, lässt `report/` aber unangetastet; der Report schreibt
ausschließlich unterhalb von `report/`.

**Auslieferung per Pull:** GitHub schiebt nichts auf den Server. Ein Timer auf
dem VPS holt den öffentlichen Branch `gh-pages` (`git fetch --depth 1`) und
spiegelt ihn ohne `.git` in das Wurzelverzeichnis des Webservers. Dafür braucht
weder GitHub einen Zugang zum Server noch der Server einen Zugang zu GitHub;
eine Änderung ist nach dem nächsten Timerlauf sichtbar. Skript, systemd-Units
und nginx-Konfiguration liegen unter [`deploy/landing-page/`](../deploy/landing-page/README.md).

Die Rohdaten unter `report/data/` sind der eigentliche Bestand: Der Report
erzeugt aus ihnen bei jedem Lauf sämtliche Seiten neu, damit Layoutänderungen
rückwirkend gelten. Sie dürfen nicht gelöscht werden — sonst ist die Historie
verloren.

## Links, die stimmen müssen

- Testinstallation: `https://demo.opaa.io`
- Repository: `https://github.com/criew/opaa`
- Fortschrittsbericht: `report/` (relativ, damit die Seite auch lokal und in
  einem Fork funktioniert)
