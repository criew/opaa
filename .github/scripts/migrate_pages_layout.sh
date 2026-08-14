#!/usr/bin/env bash
# Überführt das alte Layout der GitHub Page in das neue.
#
# Bis Issue #373 lag der Tagesreport im Wurzelverzeichnis des Branches
# gh-pages; dort liegt jetzt die Landing Page aus page/, der Report darunter
# in report/. Dieses Skript verschiebt einen noch im alten Layout
# vorliegenden Bestand einmalig — die Rohdaten in data/ sind dabei das
# Entscheidende, weil der Report aus ihnen sämtliche Seiten jedes Mal neu
# erzeugt. Gingen sie verloren, wäre die Historie weg.
#
# Es wird von beiden Workflows aufgerufen, die auf gh-pages schreiben, weil
# nicht feststeht, welcher zuerst läuft. Mehrfache Aufrufe sind folgenlos.
#
# Aufruf: migrate_pages_layout.sh <verzeichnis-der-ausgecheckten-seite>

set -euo pipefail

site="${1:?Verzeichnis der ausgecheckten Seite fehlt}"

if [ ! -d "$site/data" ] && [ ! -d "$site/reports" ]; then
  echo "Kein Altbestand im Wurzelverzeichnis — nichts zu tun."
  exit 0
fi

if [ -d "$site/report/data" ]; then
  echo "report/ ist bereits befüllt — Altbestand bleibt unberührt."
  exit 0
fi

mkdir -p "$site/report"
for eintrag in data reports feed.xml index.html; do
  if [ -e "$site/$eintrag" ]; then
    mv "$site/$eintrag" "$site/report/$eintrag"
    echo "verschoben: $eintrag -> report/$eintrag"
  fi
done
