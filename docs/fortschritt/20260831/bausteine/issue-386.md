# Issue #386 — feat(query): Belege gegen die abgerufenen Fundstellen prüfen
- Geschlossen: 2026-08-21 (completed)
- Labels: enhancement, backend, size:M
- PRs: #697 (2026-08-21)

**Laut Issue:** Teil von #354, Stufe 1 des Zitierzwangs. Deterministischer Kern: jeder Beleg muss auf eine tatsächlich abgerufene Fundstelle zeigen (Dokument-Kennung, Abschnittsnummer, Bezeichnung). Leere Fundstellenmenge → Verweigerung vor dem Modellaufruf. Tragende Aussagen (Sinnabschnitte, Negativliste) brauchen mindestens einen gültigen Beleg. Formregel gegen Belegverdünnung (max. 1.000 Zeichen je Beleg). Schalter zunächst hausweit. Explizit außerhalb des Umfangs: Deckungsprüfung, Verweigerungstext, Space-Schalter.

**Geliefert:** Deutlich schmaler als im Issue verlangt — per Maintainer-Entscheidung vom 21.08.2026 (Issue-Kommentar) auf reine Belegvalidierung reduziert. Umgesetzt: `CitationParser` liefert jetzt jede Beleg-Markierung einzeln, neue Klasse `CitationValidator` gleicht sie deterministisch gegen abgerufene Chunks ab, ungültige Belege werden über `citationValid: false` markiert statt die Antwort zu verweigern. **Nicht gebaut** (verworfen, nicht nur verschoben): der Verweigerungsmodus bei fehlendem Beleg, die Abschnittszerlegung mit Negativliste für „tragende Aussagen", die Formregel gegen Belegverdünnung, der Schalter am Space. Begründung im PR: Das Modell kommuniziert bereits selbst, wenn nichts gefunden wurde, fehlende Belege sind im Belegfenster sichtbar — die Validierung stellt nur sicher, dass vorhandene Belege echt sind. Mit diesem PR wurden zugleich #387, #388 und #389 geschlossen (not planned) — das ursprüngliche Zitierzwang-Konzept aus #354 wurde damit stark zurückgeschnitten. Reproduktionsnachweis mit rotem/grünem Test erbracht.

**Verifikation:** `CitationValidator.java` existiert im Worktree unter `backend/src/main/java/io/opaa/query/`. `docs/features/data-indexing-rag.md` enthält laut PR-Beschreibung einen Absatz „Bewusst nicht gebaut", der die verworfenen Teile mit Begründung dokumentiert statt sie kommentarlos zu löschen.

**Themen:** zitierzwang, belegbarkeit, retrieval, query, backend, produktausrichtung-revidiert
