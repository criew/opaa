# Anweisungen für KI-Agenten

## Projektübersicht

OPAA (Open Project AI Assistant) ist ein quelloffenes Projekt, das einen KI-gestützten Projektassistenten entwickelt.
Beiträge von Menschen und KI-Agenten sind gleichermaßen willkommen.

## Projektsprache

Die Projektsprache ist **Deutsch**. Englisch bleibt ausschließlich dem Quellcode vorbehalten.

**Deutsch:**
- GitHub-Issues, Pull Requests (Titel und Beschreibung), Commit-Beschreibung und -Body (Typ und Scope bleiben englisch: `feat(workspace): Rollenverwaltung ergänzen`)
- Dokumentation (`README.md`, `docs/`, ADRs, Feature-Spezifikationen) und Templates unter `.github/`
- Alle in der Anwendung sichtbaren Texte — Frontend-UI, `aria-label`-Attribute und nutzerseitige API-Fehlermeldungen

**Englisch:**
- Bezeichner im Quellcode (auch CSS-Klassen), Datei- und Verzeichnisnamen, Code-Kommentare, Labels und Branch-Namen
- Log-Ausgaben und entwicklerseitige Exception-Messages
- Technische Konstanten, Enum-Werte, API-Feldnamen und die OpenAPI-Spezifikation

## Architektur

> Vollständige Begründung: [ADR-0002](docs/decisions/0002-mvp-technology-stack.md)

## Build & Test

```bash
# Backend (aus backend/)
./gradlew build
./gradlew test
./gradlew test -PtestShard=api    # nur ein CI-Shard (api | indexing | core), Paketlisten in build.gradle.kts
./gradlew spotlessCheck
./gradlew spotlessApply
SPRING_PROFILES_ACTIVE=local,dev ./gradlew bootRun   # ohne Auth-Profil bricht der Start ab (AuthProfileGuard, ADR-0005)

# Frontend (aus frontend/) — Paketmanager pnpm, Version im "packageManager"-Feld der package.json
pnpm install
pnpm run dev                            # Dev-Server (benötigt Backend auf :8080)
pnpm run build
pnpm run lint
pnpm run test
pnpm run format:check                   # Prettier prüfen; pnpm run format formatiert
```

Integrationstest-Suiten, Helm-Chart-Prüfungen, Kubernetes-Erprobungsaufbau, E2E-Suite, Image-Rauchtest, Parser-Fuzzing, Sicherheitsinvarianten als Architekturtests und Frontend mit Mocks: [docs/entwicklung/build-und-ci.md](docs/entwicklung/build-und-ci.md).

## API & DTO-Konvention

- **Alle API-DTOs MÜSSEN aus der OpenAPI-Spezifikation generiert werden** — niemals DTO-Klassen in `io.opaa.api.dto` manuell schreiben
- Änderungen an Request-/Response-Schemas beginnen mit einer Spec-Änderung, dann werden die generierten DTOs verwendet
- Frontend-Typen werden aus derselben Spezifikation über `openapi-typescript` generiert
- **Die Spec liegt als eine Datei je Thema unter `opaa-api/src/main/openapi/`** und wird beim Build zu einer Spec gebündelt (`opaa-api/build/openapi-bundle/openapi/opaa-api.yaml`, im Klassenpfad `/openapi/opaa-api.yaml`). Nur die Fragmente bearbeiten, nie das Bündel. Wohin eine Änderung gehört:
  - Pfade, Tag und Schemas eines Themas stehen in der Datei des Themas, z. B. `libraries.yaml`, `groups.yaml`. Neue Operationen kommen in die Datei ihres Themas und tragen genau dessen Tag.
  - Ein Schema steht in der Datei des Themas, dem es fachlich gehört, auch wenn andere Themen es referenzieren (`SpaceRole` in `spaces.yaml`). Nur Bausteine ohne fachlichen Besitzer stehen in `common.yaml`: Fehlerhülle (`ErrorResponse`), übergreifende Enums wie `SystemRole`, gemeinsame Parameter und Antworten.
  - `root.yaml` enthält nur `openapi` und `info`, einschließlich der Beschreibung der Statuscodes.
  - Ein neues Thema bekommt eine neue Datei (`[a-z0-9-]+.yaml`) mit eigenem Eintrag unter `tags`.
  - Alle `$ref` zeigen in die gebündelte Spec (`#/components/schemas/...`), auch über Dateigrenzen hinweg. Doppelte Pfade oder Komponenten, nicht auflösbare `$ref`, YAML-Anker und Operationen ohne genau ein Tag aus der eigenen Datei brechen den Build ab.
- Weitere Backend-Regeln — Abhängigkeitsverwaltung, Enum-Mapping, Mapper-Schicht, Statuscode-Deklaration, Rechte-Matrix je Operation, Spring-Testkontexte, Datenbankmigrationen (Flyway) — stehen in [backend/AGENTS.md](backend/AGENTS.md). **Vor jeder Änderung unter `backend/` oder `opaa-api/` lesen.**

> Vollständige Begründung: [ADR-0006](docs/decisions/0006-openapi-dto-generation.md)

## Code-Konventionen

### Code-Kommentare

Ein Kommentar beschreibt den Verhaltensvertrag oder eine nicht offensichtliche Invariante — in 1–5 Zeilen; Entstehungsgeschichte (Review-Runden, verworfene Alternativen, Fehlversuche) gehört in Commit-Nachrichten und PR-Beschreibungen. Eine Issue-/PR-Referenz im Code ist nur zulässig, wenn sie eine aktive Einschränkung markiert (Workaround bis zu einem Upstream-Fix, `@Disabled`/`.skip` mit Ticketverweis, Deprecation mit Ablösungs-Issue). Gilt projektweit für Javadoc, TSDoc, Inline-Kommentare in `.java`/`.ts`/`.tsx` sowie `application.yml`, `build.gradle.kts`, `vite.config.ts` und Workflow-Dateien, nicht für Markdown-Dokumentation und ADRs.

In Tests darf ein Kommentar zusätzlich die abgesicherte Regression benennen (`// regression guard for #307: ...`), sofern er die Invariante nennt und nicht den Review-Verlauf.

**Positivbeispiel** (Vertrag/Invariante):

```java
/**
 * Cached values are invalidated on every write path in {@link SpaceService};
 * a cache hit is therefore always consistent with the last committed state.
 */
```

`.github/scripts/check_code_comments.sh` prüft das in der CI; welche Muster den Lauf fehlschlagen lassen und welche Bäume geprüft werden, steht im Skriptkopf.

### GitHub-Actions-Workflows

**Jeder Workflow bindet seine Actions über den vollen Commit-SHA ein**, mit exakter Version als Kommentar (`uses: docker/login-action@<40-stelliger SHA> # v4.6.0`), nie über einen Tag wie `@v7`. Wie man den SHA ermittelt und im Review prüft, steht in [docs/renovate.md](docs/renovate.md#actions-per-commit-sha); `.github/scripts/check_action_pins.sh` prüft jede Datei unter `.github/workflows/` in der CI, ein neuer Workflow ist ohne Liste erfasst. Release-Builds in `publish-images.yml` lesen und schreiben keinen gha-Cache; wer das ändert, liest vorher dort den Abschnitt „Sicherheitsmodell des gha-Caches“.

**Kontextwerte, die der Auslöser bestimmt, erreichen ein Skript nur über `env:`**: kein `${{ github.head_ref }}`, `${{ github.ref }}`, `${{ github.ref_name }}`, `${{ github.workflow_ref }}`, `${{ github.event.* }}` (auch `toJSON(github.event)`, `github['…']`), `${{ inputs.* }}`, `${{ secrets.* }}`, `${{ vars.* }}` oder `${{ env.* }}` in einem `run:`- oder `script:`-Block, sondern `env: HEAD_REF: ${{ github.head_ref }}` und im Skript `"$HEAD_REF"`. Jeder Workflow setzt `permissions:` auf Workflow-Ebene minimal (in der Regel `contents: read`, in `publish-images.yml` `{}`); ein Job, der mehr braucht, nennt es in seinem eigenen Block. Jeder Checkout nennt `persist-credentials` ausdrücklich: `false`, oder `true`, wenn der Job danach aus diesem Checkout pusht (heute nur die `gh-pages`-Checkouts von `daily-report.yml` und `landing-page.yml`). `.github/scripts/check_workflow_hardening.sh` prüft alle drei Regeln in der CI für jede Datei unter `.github/workflows/`. Python-Werkzeuge der CI stehen gepinnt in `.github/requirements-ci.txt`, die Renovate pflegt.

### Farben und Kontrast

Text, auch in Hinweis-, Warn- und Statusfarben, erreicht im hellen und im dunklen Farbschema mindestens 4,5:1 gegen seine Fläche (große Schrift 3:1); Regeln zu Farbrollen und Kontrast stehen in den [Gestaltungsleitlinien 2.3 und 2.4](docs/design/guidelines.md#23-regeln).

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
- Aufgabe fertig & gemerged → Worktree **sofort entfernen**. Ein bebauter Worktree belegt durch `backend/build` und `.gradle` schnell viele GB — liegengebliebene Worktrees füllen die Platte. (`frontend/node_modules` ist seit der pnpm-Migration nur noch ein Link-Baum in den benutzerweiten Store und fällt kaum noch ins Gewicht.)
- Aufgabe unterbrochen, später weiterführen → Worktree behalten
- `pnpm install` in einem Worktree erst ausführen, wenn tatsächlich am Frontend gearbeitet wird

### Branch-Benennung

Format: `feature/<issue-id>_<kurze-beschreibung>` — **ausnahmslos** mit `feature/` und mit der ID des zugehörigen GitHub-Issues, auch bei Fehlerbehebungen, dringenden Korrekturen und Dokumentationsänderungen. Es gibt kein `fix/`-, `hotfix/`- oder `docs/`-Präfix und keine Namen ohne Issue-ID; die Art der Änderung steht im Conventional-Commit-Typ von Commit und PR-Titel.

**Cloud-Sessions:** Gibt die Umgebung einen `claude/…`-Branch vor, haben Agenten hiermit die ausdrückliche Erlaubnis und die Pflicht, stattdessen auf `feature/<issue-id>_<kurz>` zu pushen (`git push -u origin HEAD:feature/…`) und den PR von dort zu öffnen. Auf den `claude/`-Branch wird nichts gepusht.

**Ausnahme Renovate**, ausschließlich für den Bot ([docs/renovate.md](docs/renovate.md)): Branches `renovate/<slug>` ohne `feature/`-Präfix und Issue-ID; PRs ohne PR-Template, mit deutschem Titel und Kopfsatz und generiertem englischem Rest, eröffnet mit GitHub-Auto-Merge. Menschen und Agenten bleiben an die Regeln gebunden.

### GitHub-Issues

- Beim Erstellen IMMER passende vorhandene Labels zuweisen (z. B. `bug`, `backend`, `size:S/M/L`)
- **Vor Arbeitsbeginn das Issue selbst zuweisen** (`gh issue edit <nr> --add-assignee @me`). Ist es einem anderen Account zugewiesen, nicht anfangen, sondern Bescheid geben — Details in [docs/AGENT-ORGANIZATION.md](docs/AGENT-ORGANIZATION.md#bearbeitung-anzeigen-issue-selbst-zuweisen)
- Titel und Beschreibung MÜSSEN auf Deutsch verfasst werden (siehe [Projektsprache](#projektsprache))
- **Epics führen ihre Tickets als native Sub-Issues**, nicht als Checkliste im Body; Aufbau des Epic-Bodys: [.github/ISSUE_TEMPLATE/epic.md](.github/ISSUE_TEMPLATE/epic.md)
- **Ein Epic wird geschlossen, sobald alle seine Sub-Issues geschlossen sind**, mit kurzem Abschlusskommentar: was geliefert wurde (PR-Verweise genügen) und welche Folge-Issues außerhalb des Epic-Umfangs entstanden sind. Wer das letzte Sub-Issue abschließt, prüft den Stand der Geschwister. Befehle dazu in [docs/AGENT-ORGANIZATION.md](docs/AGENT-ORGANIZATION.md#issues), Abschnitt Issues

### Pull Requests

- Keine direkten Pushes zu `main` — alle Änderungen gehen über PRs
- Der Code Reviewer prüft jeden PR vor dem Merge; seine Befunde gehen zurück an den Autor
- Kein formales Approval nötig: ein Maintainer merged, sobald CI grün ist, per Auto-Merge (`gh pr merge --auto --squash`); der PR muss dafür nur konfliktfrei sein, nicht up to date mit `main`. Wer Auto-Merge setzen darf, regelt [docs/AGENT-ORGANIZATION.md](docs/AGENT-ORGANIZATION.md), Schritt 6
- Beim Erstellen IMMER passende Labels zuweisen; Titel und Beschreibung MÜSSEN auf Deutsch verfasst werden
- IMMER das PR-Template [.github/PULL_REQUEST_TEMPLATE.md](.github/PULL_REQUEST_TEMPLATE.md) verwenden

### Pre-Push-Checkliste

Vor dem **ersten Push eines PRs** müssen lokal bestehen: Backend-Formatierung, Backend-Build + Test, Frontend-Formatierung, Frontend-Lint, Frontend-Build + Test. Bei reinen Dokumentationsänderungen entfällt die Checkliste.

**Nachbesserungsrunden** (Folge-Pushes auf einen bestehenden PR) verwenden die verkürzte Prüfung — Formatierung, Kompilieren und die berührten Testklassen — und prüfen das CI-Ergebnis des Folge-Pushes, bevor der PR als bereit gilt.

**Builds und Tests im Vordergrund ausführen** und aktiv abwarten (mit ausreichendem Timeout), statt einen Hintergrundlauf zu starten und auf dessen Benachrichtigung zu warten.

## Wichtige Pfade

- `docs/AGENT-ORGANIZATION.md` — Agenten-Rollen, Idee-bis-Merge-Workflow und Kollaborationsregeln
- `docs/decisions/` — Architecture Decision Records (ADRs), u. a. [ADR-0053](docs/decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md) zum Betrieb mit einer oder mehreren Backend-Instanzen. Regeln und Vorlage in [docs/decisions/README.md](docs/decisions/README.md): ein ADR hält den gültigen Endstand fest und wird bei Änderungen im Kerntext fortgeschrieben, keine Nachträge; Status von der ersten Fassung an „Akzeptiert · zuletzt geändert <Datum>“, der Merge des einführenden PRs ist die Zustimmung — **vor jeder Änderung an einem ADR lesen**
- `docs/features/` — Feature-Spezifikationen
- `docs/behoerden/` — Unterlagen für Behörden entlang des Einführungswegs (redaktionelle Vorlagen); Aufbau, Kopfvermerk, Haftungshinweis, Faktenblatt-Platzhalter und Regeln in `docs/behoerden/AGENTS.md`, geprüft durch `.github/scripts/check_behoerden_headers.sh` — vor jeder Änderung dort lesen
- `docs/handbuch/` — Produkthandbuch des gebauten Ist-Stands (Kapitelbaum in `docs/handbuch/README.md`, Abschnitt „Aufbau des Handbuchs"; Ordner nach dem Kapitelbaum: `betrieb/`, `konten-und-rechte/`, `spaces/`, `arbeitsmittel/`, Konnektoren und Formate je mit Übersicht unter `arbeitsmittel/wissensbibliotheken/konnektoren/` und `…/formate/`, Begriffe in `glossar.md`); Regeln für Aufbau, Verweise und Stil in `docs/handbuch/AGENTS.md` — vor jeder Änderung am Handbuch lesen
- `backend/AGENTS.md` — Backend-Konventionen (Abhängigkeitsverwaltung, API-/Mapper-Regeln, logische Module und Schichtung, Spring-Testkontexte, Datenbankmigrationen), Nachschlagewissen dazu unter `backend/docs/` (Register des prozesslokalen Zustands, Testkontexte, Migrationen); `backend/` ist zugleich Gradle-Root des Multi-Modul-Builds. Je logischem Modul ergänzt eine kurze `AGENTS.md` im Hauptpaket unter `backend/src/main/java/io/opaa/` die Regeln des Moduls (Übersicht in `backend/AGENTS.md`)
- `opaa-api/` — Gradle-Modul mit OpenAPI-Spec, Generator-Konfiguration und geteilten Domain-Enums (`io.opaa.api.types`), siehe [ADR-0006](docs/decisions/0006-openapi-dto-generation.md)
- `frontend/AGENTS.md` — Gliederung des Frontends (`src/app/`, `src/shared/`, `src/features/<thema>/`, `src/test/`), die per ESLint geprüften Themengrenzen ohne Ausnahmen und Ablauf eines Umzugs-PRs; **vor jeder Änderung unter `frontend/src/` lesen**
- `frontend/src/test/test-utils.tsx` — Gemeinsame Test-Render-Helfer (`renderWithProviders`, `setMockAuthState`); MSW-Server der Tests in `frontend/src/test/server.ts`, Handler-Sammelstelle in `frontend/src/app/mocks/handlers.ts`
- `e2e/` — Browserbasierte End-to-End-Tests (Playwright), siehe `e2e/README.md`
- `deploy/helm/opaa/` — Helm-Chart für den Kubernetes-Betrieb (eine Backend-Instanz als Vorgabe, mehrere mit `backend.replicas` unter den Voraussetzungen von [ADR-0053](docs/decisions/0053-mehrinstanzbetrieb-aktiv-aktiv.md), Entscheidung 15), siehe [ADR-0042](docs/decisions/0042-kubernetes-lieferung-mit-helm.md); eine betriebsrelevante Änderung zieht ihn im selben PR nach
- `docs/renovate.md` — selbst betriebene Abhängigkeits-Updates (Renovate); Regeln in `renovate.json5`
- `docs/releases.md` — Release-Verfahren: Versions-Tags `vX.Y.Z`, unveränderliche Release-Images, Versionszählung, Patch-Releases für Sicherheitsupdates
- `docs/sbom.md` — Software Bill of Materials: Image-Attestierungen und CycloneDX-CI-Artefakte; Lizenzprüfung der Abhängigkeiten gegen `.github/licenses/`
- `docs/cve-scanning.md` — CVE-Erkennung: Dependabot-Alerts, Trivy-Image-Scan und Triage-Verfahren
- `eval/` — Korpora, Golden Datasets und Generatoren der Suchqualitäts-Evaluierung, siehe `eval/README.md`. Liegt bewusst außerhalb von Gradle-Build und CI; die Generatoren laufen nur bei bewussten Korpus-Änderungen, nie automatisch. Der Metrik-Harness selbst ist ein Integrationstest im Backend

## Contributor License Agreement

Ohne unterzeichnete [Contributor License Agreement](./CLA.md) kann kein Pull Request zusammengeführt werden. **Ein Agent unterzeichnet nie selbst** — verantwortlich ist der menschliche Betreiber, einmal pro GitHub-Account.

Verfahren und Wortlaut des Unterzeichnungskommentars stehen in [CONTRIBUTING.md](./CONTRIBUTING.md#contributor-license-agreement-cla).

## Agenten-Verhalten

- In der Sprache antworten, in der der Benutzer schreibt
- Code nicht umstrukturieren, sofern nicht ausdrücklich verlangt
- Vor dem Erstellen neuer Dateien prüfen, ob ähnliche Muster oder Hilfsfunktionen bereits existieren
- Kleine, fokussierte Commits gegenüber großen bevorzugen
- Bei der Behebung eines Bugs zuerst einen Test schreiben, der den Bug reproduziert — und **nachweisen, dass er auf dem fehlerhaften Stand tatsächlich fehlschlägt** (siehe [Reproduktionsnachweis](#reproduktionsnachweis))
- `docs/decisions/` für Architecture Decision Records vor größeren strukturellen Änderungen lesen

### Reproduktionsnachweis

Bei jeder Fehlerbehebung den Fix vorübergehend zurücknehmen, den Test laufen lassen, das Fehlschlagen belegen, den Fix wiederherstellen, erneut laufen lassen. **Beide Ergebnisse gehören in die PR-Beschreibung**, mit der konkreten Fehlermeldung des roten Laufs.

**Beleg-Läufe nur auf aktuellem Stand:** Vor dem roten und dem grünen Lauf `origin/main` in den Branch mergen — ein Nachweis auf veraltetem Stand belegt nichts. Widerspricht ein lokaler grüner Lauf einem roten CI-Lauf, gilt die CI; der lokale Lauf ist dann auf einem anderen (meist älteren oder anders kombinierten) Stand gelaufen.

Typische Ursachen dafür, dass ein Test den Fehler verfehlt: [backend/docs/reproduktionsnachweis.md](backend/docs/reproduktionsnachweis.md).

## Sicherheit

- Niemals Secrets, API-Schlüssel oder Anmeldeinformationen committen
- Umgebungsvariablen für die Konfiguration verwenden
- `.env`-Dateien nicht committen