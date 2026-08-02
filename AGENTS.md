# Anweisungen für KI-Agenten

## Projektübersicht

OPAA (Open Project AI Assistant) ist ein quelloffenes Projekt, das einen KI-gestützten Projektassistenten entwickelt.
Beiträge von Menschen und KI-Agenten sind gleichermaßen willkommen.

## Projektsprache

Die Projektsprache ist **Deutsch**. Englisch bleibt ausschließlich dem Quellcode vorbehalten.

**Deutsch:**

- GitHub-Issues (Titel und Beschreibung)
- Pull Requests (Titel und Beschreibung)
- Dokumentation (`README.md`, `docs/`, ADRs, Feature-Spezifikationen)
- Templates unter `.github/`
- Commit-Beschreibungen und -Body (der Conventional-Commit-Typ und -Scope bleiben englisch, z. B. `feat(workspace): Rollenverwaltung ergänzen`)
- Alle in der Anwendung sichtbaren Texte — Frontend-UI, `aria-label`-Attribute und nutzerseitige API-Fehlermeldungen

**Englisch:**

- Bezeichner im Quellcode (Klassen, Methoden, Variablen, CSS-Klassen)
- Datei- und Verzeichnisnamen
- Code-Kommentare
- Log-Ausgaben und entwicklerseitige Exception-Messages
- Technische Konstanten, Enum-Werte, API-Feldnamen und die OpenAPI-Spezifikation
- Labels und Branch-Namen

## Architektur

- **Backend:** Java 21 + Spring Boot 4.1.0 + Spring AI 2.0.0 (Gradle 9.6.1, Kotlin DSL)
- **Datenbank:** PostgreSQL 18 + pgvector, Liquibase
- **Frontend:** React 19 + TypeScript 6 + Material UI 9 + React Router 8 + Zustand + Vitest + MSW (Vite 8, Node 22+)
- **CI:** GitHub Actions
- **Deployment:** Docker Compose

> Vollständige Begründung: [ADR-0002](docs/decisions/0002-mvp-technology-stack.md)

## Build & Test

```bash
# Backend (aus backend/)
./gradlew build
./gradlew test
./gradlew bootRun
./gradlew spotlessCheck
./gradlew spotlessApply

# Frontend (aus frontend/)
npm ci                                  # Abhängigkeiten installieren
VITE_ENABLE_MOCKS=true npm run dev      # Dev-Server mit MSW-Mocks
npm run dev                             # Dev-Server (benötigt Backend auf :8080)
npm run build                           # Production-Build
npm run lint                            # Lint (ESLint)
npm run test                            # Tests (Vitest)
npm run format:check                    # Prettier-Formatierung prüfen
npm run format                          # Automatisch mit Prettier formatieren

# E2E-Suite (aus e2e/, siehe e2e/README.md)
npm ci                                  # Abhängigkeiten installieren
npx playwright install --with-deps chromium   # Browser installieren (einmalig)
npm test                                # Stack via Docker Compose starten, Suite ausführen, Stack wieder stoppen
```

## Abhängigkeitsverwaltung

- Alle Bibliotheks- und Plugin-Versionen MÜSSEN in `backend/gradle/libs.versions.toml` deklariert werden — niemals eine Version direkt in `build.gradle.kts` eintragen
- Alle Abhängigkeiten MÜSSEN im Abschnitt `[libraries]` als Bibliotheken definiert und über `libs.*` in `build.gradle.kts` referenziert werden
- Verwandte Bibliotheken in `[bundles]` zusammenfassen, wo sinnvoll (z. B. `spring-boot`, `spring-ai`, `test-deps`)
- Versions-Kataloge (`libs.versions.*`, `libs.*`, `libs.bundles.*`) für die Referenzierung von Versionen, Bibliotheken und Bundles verwenden
- Dies gilt für die Abschnitte `[versions]`, `[libraries]`, `[bundles]` und `[plugins]`

## API & DTO-Konvention

- **Alle API-DTOs MÜSSEN aus der OpenAPI-Spezifikation generiert werden** (`backend/src/main/resources/openapi/opaa-api.yaml`) — niemals DTO-Klassen in `io.opaa.api.dto` manuell schreiben
- Änderungen an Request-/Response-Schemas beginnen mit einer Spec-Änderung, dann werden die generierten DTOs verwendet
- Domain-Enums in DTOs (z. B. `WorkspaceRole`, `WorkspaceType`) werden über `typeMappings`/`importMappings` in `build.gradle.kts` gemappt
- Beim Hinzufügen neuer Domain-Enums zur API `typeMappings`, `importMappings` und den `doLast`-Cleanup-Block in `build.gradle.kts` aktualisieren
- Frontend-Typen werden aus derselben Spezifikation über `openapi-typescript` generiert

> Vollständige Begründung: [ADR-0006](docs/decisions/0006-openapi-dto-generation.md)

## Code-Konventionen

### Commit-Nachrichten

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) verwenden:

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

Typen: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `ci`, `build`

KI-Agenten müssen einen `Co-Authored-By`-Trailer in Commits einfügen.

### Git-Workflow

- Immer einen Feature-Branch erstellen; niemals direkt auf `main` committen
- PRs fokussiert halten: eine logische Änderung pro PR
- Bei der Behebung eines Issues in der PR-Beschreibung mit `Closes #N` referenzieren

### Git Worktrees für parallele Sessions

Wenn mehrere Agent-Sessions gleichzeitig in diesem Verzeichnis arbeiten (z. B. mehrere Features parallel), für jede neue Aufgabe einen eigenen Git Worktree nutzen, statt im Hauptverzeichnis zu branchen. So blockieren sich parallele Sessions nicht gegenseitig durch Branch-Wechsel im selben Arbeitsverzeichnis.

- Neue Aufgabe → eigenen Worktree anlegen (eigener Branch, eigenes Arbeitsverzeichnis)
- Aufgabe fertig & gemerged → Worktree entfernen
- Aufgabe unterbrochen, später weiterführen → Worktree behalten

### Branch-Benennung

Format: `feature/<issue-id>_<kurze-beschreibung>`

Jeder Branch ist über seine ID mit einem GitHub-Issue verknüpft.

**Branch-Regel (verbindlich):**
- Branches immer mit `feature/` erstellen.
- Immer die GitHub-Issue-ID im Branch-Namen angeben.
- Keine generischen Namen wie `feature/workspace` ohne Issue-ID verwenden.

### GitHub-Issues

- Beim Erstellen eines GitHub-Issues IMMER passende Labels basierend auf dem Inhalt zuweisen
- Vorhandene Labels verwenden (z. B. `bug`, `enhancement`, `backend`, `frontend`, `security`, `auth`, `size:S/M/L`, usw.)
- Issue-Titel und -Beschreibungen MÜSSEN auf Deutsch verfasst werden (siehe [Projektsprache](#projektsprache))

### Pull Requests

- Keine direkten Pushes zu `main` — alle Änderungen gehen über PRs
- Der Code Reviewer prüft jeden PR vor dem Merge; seine Befunde gehen zurück an den Autor
- Ein formales Approval in GitHub ist nicht erforderlich. Es genügt, dass einer der Maintainer (`criew` oder `bigpuritz`) den PR merged, sobald CI grün ist
- Kein Agent merged jemals einen PR
- Beim Erstellen eines PRs IMMER passende Labels basierend auf dem Inhalt zuweisen
- PR-Titel und -Beschreibungen MÜSSEN auf Deutsch verfasst werden (siehe [Projektsprache](#projektsprache))
- IMMER das PR-Template (Zusammenfassung, Zugehörige Issues, Art der Änderung, Checkliste, KI-Agenten-Offenlegung) in [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) für neue Pull Requests verwenden

### Pre-Push-Checkliste

Bei reinen Dokumentationsänderungen überspringen. Vor jedem Push müssen alle folgenden Punkte lokal bestehen:

- Backend-Formatierung
- Backend-Build + Test
- Frontend-Formatierung
- Frontend-Lint
- Frontend-Build + Test

## Wichtige Pfade

- `docs/AGENT-ORGANIZATION.md` — Agenten-Rollen, Idee-bis-Merge-Workflow und Kollaborationsregeln
- `docs/decisions/` — Architecture Decision Records (ADRs)
- `docs/features/` — Feature-Spezifikationen
- `.github/ISSUE_TEMPLATE/` — Issue-Templates
- `.github/PULL_REQUEST_TEMPLATE.md` — PR-Template
- `CONTRIBUTING.md` — Leitfaden für Beitragende
- `AGENTS.md` — Anweisungen für KI-Agenten
- `backend/` — Spring Boot Backend (Gradle-Projekt)
- `frontend/` — React-Frontend (Vite-Projekt)
- `frontend/src/test/test-utils.tsx` — Gemeinsame Test-Render-Helfer
- `e2e/` — Browserbasierte End-to-End-Tests (Playwright), siehe `e2e/README.md`

## Contributor License Agreement

OPAA verlangt von allen Beitragenden, die [Contributor License Agreement](./CLA.md) zu unterzeichnen, bevor ein Pull Request zusammengeführt werden kann. Der menschliche Betreiber des KI-Agenten ist für die Unterzeichnung verantwortlich — nicht die KI selbst.

**So unterzeichnen:** Posten Sie folgenden Kommentar im ersten PR:

> I have read the CLA Document and I hereby sign the CLA

Die Unterschrift wird automatisch erfasst und muss nur einmal pro GitHub-Account geleistet werden.

## Agenten-Verhalten

- In der Sprache antworten, in der der Benutzer schreibt
- Code nicht umstrukturieren, sofern nicht ausdrücklich verlangt
- Vor dem Erstellen neuer Dateien prüfen, ob ähnliche Muster oder Hilfsfunktionen bereits existieren
- Kleine, fokussierte Commits gegenüber großen bevorzugen
- Bei der Behebung eines Bugs zuerst einen Test schreiben, der den Bug reproduziert
- `docs/decisions/` für Architecture Decision Records vor größeren strukturellen Änderungen lesen

## Sicherheit

- Niemals Secrets, API-Schlüssel oder Anmeldeinformationen committen
- Umgebungsvariablen für die Konfiguration verwenden
- `.env`-Dateien nicht committen
