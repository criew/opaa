# Issue #387 — feat(query): Verweigerung im Zitierzwang mit Auskunft über den Suchvorgang
- Geschlossen: 2026-08-21 (not planned)
- Labels: enhancement, backend, frontend, size:M
- PRs: keine

**Laut Issue:** Teil von #354, baut auf #386 auf. Bei Verweigerung im Zitierzwang sollte eine Auskunft über den Suchvorgang erscheinen (verwendete Suchfrage, durchsuchte Bibliotheken, Trefferzahlen, ein Grund aus fester Liste) statt eines nackten „nicht feststellbar" — ohne zu verraten, ob unlesbare Bestände existieren. Verweigerung als reguläres Ergebnis mit Kennzeichen, nicht als Fehlerstatus.

**Geliefert:** Nicht umgesetzt. Laut PR #697 zu #386 (Maintainer-Entscheidung vom 21.08.2026, siehe dortiger Issue-Kommentar) wurde dieser Vorgang zusammen mit #388 und #389 verworfen. Begründung: Das Modell kommuniziert bereits selbst, wenn es nichts gefunden hat, und fehlende Belege sind im Belegfenster unmittelbar sichtbar — ein eigener Verweigerungsmodus mit Suchvorgangs-Auskunft wurde als nicht nötig bewertet. Es gibt also einen expliziten, im PR #697 dokumentierten Grund, keinen stillen Rückstand.

**Verifikation:** Kein `POST /query`-Verweigerungskennzeichen im OpenAPI-Diff von PR #697 (nur `citationValid` wurde ergänzt) — passt zur „not planned"-Einordnung.

**Themen:** zitierzwang, verweigerung, query, produktausrichtung-revidiert, verworfen
