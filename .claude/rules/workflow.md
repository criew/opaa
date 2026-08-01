# Git-Workflow-Regeln

- Immer einen Feature-Branch erstellen; niemals direkt auf main committen
- Conventional-Commits-Format für alle Commit-Nachrichten verwenden
- Das PR-Template verwenden
- PRs fokussiert halten: eine logische Änderung pro PR
- Bei der Behebung eines Issues im PR-Body mit „Closes #N" referenzieren

# Pre-Push-Checkliste

Bei reinen Dokumentationsänderungen überspringen.
Vor jedem Push müssen ALLE folgenden Punkte lokal bestehen.

- Backend-Formatierung
- Backend-Build + Test
- Frontend-Formatierung
- Frontend-Lint
- Frontend-Build + Test

Schlägt ein Schritt fehl, das Problem vor dem Push beheben.
