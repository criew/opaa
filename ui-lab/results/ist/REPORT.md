# UI-Lab Dienstkonten (#3589) · Ist-Stand

Auf `main` bzw. dem Backend-Branch #3588 gibt es keine Oberfläche für Dienstkonten: Der Ablauf
`service-accounts` findet in allen Szenarien weder Liste noch Bibliotheksabschnitt (36 Zustände
`not-found`, siehe `metrics.jsonl`). Zum Vergleich liegen unter `seiten/` die beiden Ausgangsseiten
(Reiter „Externe KI-Werkzeuge“, Reiter „Freigaben“ der Bibliothek „Projektablage“) in `default` und
`scale`, hell bei 1280 und 320 px.

Befund am Ist: Der Reiter „Externe KI-Werkzeuge“ liegt schon ohne Dienstkonten mit 171 Wörtern
über dem Wortbudget (150).
