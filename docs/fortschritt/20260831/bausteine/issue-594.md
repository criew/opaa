# Issue #594 — feat(frontend): Space-Anlage als mehrstufiger Assistent
- Geschlossen: 2026-08-21 (completed)
- Labels: frontend, size:M
- PRs: #687 (2026-08-21)

**Laut Issue:** Die Space-Anlage (bisher `CreateSpaceDialog`) sollte laut Mockup 1b zu einem vierstufigen Assistenten werden: Grunddaten, Datenquellen, Mitglieder, Zusammenfassung. Für den Datenquellen-Schritt sollten vorhandene APIs genutzt werden; reicht der API-Zuschnitt nicht, sollte die Lücke als Folge-Issue festgehalten werden. „Ausstattung eines bestehenden Space übernehmen“ war ausdrücklich außerhalb des Umfangs.

**Geliefert:** PR #687 liefert die neue Seite `/spaces/new` mit Schrittleiste **Grunddaten / Mitglieder / Zusammenfassung** — also nur drei statt der im Issue skizzierten vier Schritte. Der Schritt „Datenquellen zuordnen“ entfällt bewusst, weil es keine Space↔Bibliothek-Zuordnungs-API gibt; das ist im PR-Body ausdrücklich als Abweichung benannt und als Folge-Issue #686 festgehalten — deckt sich mit der Issue-Vorgabe, Lücken als Folge-Issues zu dokumentieren. `CreateSpaceDialog` wurde entfernt.

**Verifikation:** `frontend/src/pages/SpaceCreatePage.tsx` und Test existieren im heutigen Worktree; `CreateSpaceDialog.tsx` ist laut PR-Dateiliste entfernt worden (im Diff als gelöscht geführt).

**Themen:** frontend, spaces, redesign, wizard, ui
