# Issue #354 — Zitierzwang in der bestehenden Query-Pipeline bewerten
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #396 (2026-08-14)

**Laut Issue:** Teil von #344. Belegbarkeit ist Leitprinzip, seine schärfste Form ist der Zitierzwang: keine belegte Quelle, keine Antwort. Heute liefere die Pipeline nur Quellenangaben ohne Verweigerung. Zu klären: technische Bedeutung für `io.opaa.query`, ob der Schalter je Space, Bibliothek oder systemweit sitzt, ab wann er Phase-1-pflichtig ist. Ergebnis sollte eine Entscheidungsvorlage sein.

**Geliefert:** Reine Dokumentationsänderung, die den Zitierzwang in zwei Stufen schneidet. Stufe 1 (deterministisch, kein Modellaufruf): Beleg muss auf tatsächlich abgerufene Fundstelle zeigen, keine Fundstellen → keine Antwort, tragende Aussagen brauchen gültigen Beleg. Stufe 2 (inhaltliche Deckungsprüfung) bleibt eigener, unentschiedener Vorgang. Schalter sitzt am Space, verschärfbar durch Systemvorgabe — mit offen benannter Schwäche (Umgehbarkeit durch Raumwechsel). Daraus wurden vier Umsetzungsvorgänge geschnitten: #386 (Belegprüfung), #387 (Verweigerung), #388 (Schalter am Space), #389 (Deckungsprüfung Stufe 2). Wichtig für spätere Bewertung: Der Maintainer hat später (21.08., siehe Kommentare zu #386/#387/#388/#389) entschieden, nur #386 in reduziertem Umfang umzusetzen und #387–#389 zu verwerfen — das hier entschiedene Zielbild wurde also nachträglich revidiert.

**Verifikation:** `docs/features/data-indexing-rag.md` existiert im Worktree; Abschnitt „Zitierzwang" wurde laut PR #697 (siehe Baustein #386) inzwischen erneut umgeschrieben, weil die hier getroffene Zwei-Stufen-Entscheidung nicht vollständig umgesetzt wurde.

**Themen:** zitierzwang, belegbarkeit, retrieval, query, produktausrichtung
