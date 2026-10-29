#!/usr/bin/env bash
# Prüft vor dem Veröffentlichen des Tagesreports, ob der Job „publish“ auf
# demselben Stand von gh-pages aufsetzt wie der Job „report“.
#
# „report“ baut die vollständige Seite aus seinem Checkout von gh-pages,
# „publish“ spiegelt sie mit rsync --delete auf seinen eigenen Checkout. Weichen
# beide Stände voneinander ab, würde der Push alles löschen oder überschreiben,
# was „report“ nicht gesehen hat: die Landing Page und den Report-Bestand, wenn
# dessen Checkout fehlschlug, oder einen Push, der zwischen beiden Jobs auf
# gh-pages ankam. Das Skript bricht dann ab.
#
# Beide Seiten leer bedeutet: gh-pages existiert noch nicht, der erste Lauf
# legt den Branch an.
#
# Aufruf: check_pages_base.sh <verzeichnis-der-ausgecheckten-seite> <commit-aus-report>

set -euo pipefail

site="${1:?Verzeichnis der ausgecheckten Seite fehlt}"
expected="${2-}"

actual=""
if [ -d "$site/.git" ]; then
  actual="$(git -C "$site" rev-parse --verify --quiet HEAD || true)"
fi

if [ "$expected" != "$actual" ]; then
  echo "::error::gh-pages steht bei ${actual:-<kein Commit>}, der Report wurde aber auf ${expected:-<kein Commit>} erzeugt. Nicht veröffentlicht, damit kein Bestand verloren geht." >&2
  exit 1
fi

echo "Basis von gh-pages unverändert: ${actual:-<noch kein Branch>}"
