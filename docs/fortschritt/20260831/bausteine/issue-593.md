# Issue #593 — feat(frontend): Spaces-Übersicht als Kartenliste
- Geschlossen: 2026-08-20 (completed)
- Labels: frontend, size:M
- PRs: #683 (2026-08-20)

**Laut Issue:** Die Spaces-Übersicht sollte laut Mockup 1c als Kartenliste umgebaut werden — Kopfzeile mit Raumanzahl, Knopf „Neuer Space“, je Karte Art-Etikett, Name, Kurzbeschreibung, Kennzahlen (Quellen/Chats/Mitglieder), Rollen-Etikett sowie eine Abschlusskarte „+ Neuen Space anlegen“. Kennzahlen sollten aus vorhandenen API-Daten stammen; fehlende Zählwerte als Backend-Folge-Issue.

**Geliefert:** PR #683 baut `/spaces` von einer Weiterleitung zu einer echten Kartenraster-Übersicht um: Kopfzeile mit Zählzeile und Primärknopf, Kartenraster mit Eyebrow-Etikett (Persönlich/Team), Name, zweizeilig begrenzter Beschreibung, Rollen-Chip, Archiviert-Etikett, gestrichelte Abschlusskarte sowie gestaltetem Leerzustand. Bewusste Abweichung: Kennzahlen zeigen laut PR-Body nur die Mitgliederzahl („nur Sie“ beim persönlichen Space) — „n Quellen · n Chats“ fehlen in `SpaceListResponse` und wurden als Folge-Issue #682 ausgelagert, wie im Issue vorgesehen.

**Verifikation:** `frontend/src/pages/SpacesOverviewPage.tsx` und der zugehörige Test existieren im heutigen Worktree.

**Themen:** frontend, spaces, redesign, ui
