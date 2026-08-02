# Git-Workflow-Regeln

- Immer einen Feature-Branch erstellen; niemals direkt auf main committen
- Branch-Format `feature/<issue-id>_<kurze-beschreibung>` — ausnahmslos, auch bei Fehlerbehebungen und dringenden Korrekturen; kein `fix/`- oder `hotfix/`-Präfix. Die Art der Änderung steht im Conventional-Commit-Typ, nicht im Branch-Namen
- Conventional-Commits-Format für alle Commit-Nachrichten verwenden
- Das PR-Template verwenden
- PRs fokussiert halten: eine logische Änderung pro PR
- Bei der Behebung eines Issues im PR-Body mit „Closes #N" referenzieren

## Git Worktrees für parallele Sessions

Wenn mehrere Agent-Sessions gleichzeitig in diesem Verzeichnis arbeiten (z. B. mehrere Features parallel), für jede neue Aufgabe einen eigenen Git Worktree nutzen, statt im Hauptverzeichnis zu branchen. So blockieren sich parallele Sessions nicht gegenseitig durch Branch-Wechsel im selben Arbeitsverzeichnis.

- Neue Aufgabe → eigenen Worktree anlegen (eigener Branch, eigenes Arbeitsverzeichnis)
- Aufgabe fertig & gemerged → Worktree entfernen
- Aufgabe unterbrochen, später weiterführen → Worktree behalten

# Pre-Push-Checkliste

Bei reinen Dokumentationsänderungen überspringen.
Vor jedem Push müssen ALLE folgenden Punkte lokal bestehen.

- Backend-Formatierung
- Backend-Build + Test
- Frontend-Formatierung
- Frontend-Lint
- Frontend-Build + Test

Schlägt ein Schritt fehl, das Problem vor dem Push beheben.
