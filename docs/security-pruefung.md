# Sicherheitsprüfung des Codes

Dieses Dokument beschreibt, wie das Projekt seinen eigenen Code laufend auf Sicherheitslücken
prüft: was bei jedem Pull Request automatisch läuft, was nächtlich, wöchentlich und zweimal im
Jahr dazukommt, und was es nicht gibt. Bekannte Schwachstellen in Abhängigkeiten und Images
behandelt [`docs/cve-scanning.md`](./cve-scanning.md), von außen gemeldete Lücken
[`SECURITY.md`](../SECURITY.md). Die Kurzfassung steht in
[`SECURITY.md`, Abschnitt „Wie das Projekt seinen Code prüft“](../SECURITY.md#wie-das-projekt-seinen-code-prüft).

## Prüfrhythmus

| Wann | Was | Wer oder was prüft |
|---|---|---|
| jeder Pull Request | Review mit Sicherheits-Checkliste, CI-Prüfungen, Rechte-Matrix, Allowlist der anonymen Pfade, Architekturtests | Review-Agent, CI |
| nächtlich | Trivy-Scan der veröffentlichten Images | CI (`cve-scan.yml`) |
| wöchentlich | CodeQL über den ganzen Code, Lockfile-Pflege, Security-Delta-Review der Änderungen der Woche | CI, Renovate, geplante Routine |
| zweimal im Jahr und anlassbezogen | Tiefen-Audit je Angriffsklasse | Agenten nach Vorgabe des Maintainers |

Die Schichten ergänzen sich: Tests und CI fangen ab, was als Regel bekannt ist; Review und
wöchentliche Routine fangen neue Angriffsfläche, die noch kein Test kennt; das Tiefen-Audit sucht
ganze Fehlerklassen und macht aus seinen Funden neue Tests.

## Bei jedem Pull Request

**Review.** Jeder PR von Menschen und Agenten (zu Renovate siehe die Ausnahme unten) durchläuft
vor dem Merge das Review der Rolle
[Code-Reviewer](../agents/roles/code-reviewer.md), getrennt vom Autor
([Workflow, Schritt 5](AGENT-ORGANIZATION.md#workflow-von-der-idee-bis-zum-merge)). Berührt der PR
einen Endpunkt, eine Rechteprüfung, eine ausgehende Verbindung, gespeicherte Geheimnisse, einen
Parser oder ein Dateiformat, Anmeldung und Session, einen Workflow oder eine Abhängigkeit, arbeitet
der Reviewer die [Sicherheits-Checkliste](../agents/roles/code-reviewer.md#sicherheits-checkliste)
ab und setzt das Label `security-relevant`. Gemergt wird von einem Maintainer oder mit seiner
ausdrücklichen Freigabe. Renovate-PRs ohne Auto-Merge entscheidet der Maintainer vor dem Merge.

**Ausnahme Renovate:** Minor-, Patch-, Pin- und Digest-Updates von Abhängigkeiten (auch
Action-SHAs und Image-Digests) mergt Renovate nach einer Wartefrist und grünen Pflicht-Checks
automatisch, ohne Review und ohne Maintainer. Major-Updates,
Sicherheits-PRs und einzelne Gruppen mergen nie automatisch
([renovate.md](renovate.md#was-renovate-hier-aktualisiert),
[Sicherheits-PRs](renovate.md#sicherheits-prs-und-lockfile-pflege), Regeln in `renovate.json5`).
Für die automatisch gemergten Updates sind die CI-Prüfungen unten die einzige Schranke vor dem
Merge.

**Automatische Prüfungen in der CI:**

| Prüfung | Was sie abfängt | Wo |
|---|---|---|
| Statische Code-Analyse (CodeQL) | unsichere Datenflüsse wie Injection oder Pfadmanipulation in Java, TypeScript, Python und den Workflows | `codeql.yml`, [cve-scanning.md](cve-scanning.md#statische-code-analyse-codeql) |
| Abhängigkeits-Gate | neue oder geänderte Laufzeit-Abhängigkeit mit bekannter `critical`-Schwachstelle, auch transitiv in npm und Gradle | Job `dependency-review` in `ci.yml`, [cve-scanning.md](cve-scanning.md#pr-gate-für-abhängigkeiten) |
| Workflow-Härtung | vom Auslöser bestimmte Werte in Skripten, fehlende `permissions:`, Checkout ohne `persist-credentials` | `check_workflow_hardening.sh`, [cve-scanning.md](cve-scanning.md#härtung-der-workflows) |
| Action-Pins | eine Action, die nicht über den vollen Commit-SHA eingebunden ist | `check_action_pins.sh`, [renovate.md](renovate.md#actions-per-commit-sha) |
| Prüfsummen der Lieferkette | eine Gradle-Abhängigkeit, deren Prüfsumme nicht zu `backend/gradle/verification-metadata.xml` passt; ein `packageManager`-Feld ohne Corepack-Prüfsumme | Gradle bei jedem Build, `check_package_manager_hash.sh` |
| Lizenzprüfung | eine Laufzeit-Abhängigkeit mit nicht freigegebener Lizenz | `check_licenses.py` in den Jobs `backend-build` und `frontend`, [sbom.md](sbom.md#lizenzprüfung) |
| Compose-Vorgaben | ein Port über Loopback hinaus, anonyme Systemverwaltung, ein festes Geheimnis in den Vorlagen | `check_compose_defaults.sh` |
| Rechte-Matrix | eine API-Operation ohne Eintrag oder eine Rolle, die mehr darf als eingetragen, auch mit fremden IDs | `PermissionMatrixTest`, `PermissionMatrixIntegrationTest`, [backend/AGENTS.md](../backend/AGENTS.md#rechte-matrix) |
| Anonyme Pfade | eine Operation, die ohne Anmeldung antwortet und nicht in der Allowlist steht; fehlende Security-Header | `AnonymousSurfaceTest`, `AnonymousSurfaceIntegrationTest`, `backend/src/test/resources/security/anonymous-allowlist.yaml` |
| Architekturtests | Verstöße gegen Modulgrenzen und einzelne Sicherheitsinvarianten, etwa eine eigene Namensauflösung an der Zielprüfung vorbei | ArchUnit-Tests unter `backend/src/test/java/io/opaa/architecture/` und in den Modulen |

CI-Prüfungen mit Pfadfilter laufen im PR nur, wenn er die betroffenen Dateien berührt, die
Backend-Tests also nur bei Backend-Änderungen; auf `main` läuft nach jedem Merge alles.

**Secret Scanning und Push Protection** sind für das Repository eingeschaltet: Ein Push mit einem
erkannten Geheimnis wird abgewiesen
([cve-scanning.md](cve-scanning.md#secret-scanning-und-push-protection)).

## Nächtlich

Der [Trivy-Scan](cve-scanning.md#ebene-2--images-trivy-scan) prüft die Images von `main` und des
jüngsten Patch-Release jeder unterstützten Linie auf bekannte Schwachstellen, auch in den
Betriebssystempaketen der Basis-Images. Er ist kein PR-Gate; die Befunde werden nach
[Triage](cve-scanning.md#triage-von-image-alerts) behandelt.

## Wöchentlich

- **CodeQL** läuft zusätzlich zum PR-Lauf einmal pro Woche über den ganzen Code, damit neue
  Abfragen auch unveränderten Code erreichen.
- **Lockfile-Pflege** von Renovate löst die Lockfiles neu auf und hebt so auch rein transitive
  Pakete ([renovate.md](renovate.md#sicherheits-prs-und-lockfile-pflege)).
- **Security-Delta-Review:** Eine geplante Claude-Routine läuft sonntags um 07:52 Uhr
  (Europe/Berlin), zeitgleich mit der Architektur-Prüfroutine aus
  [ADR-0051, Entscheidung 7](decisions/0051-architekturvertrag-des-backends.md#7-prüfroutine). Sie
  liest nur die Änderungen auf `main` der letzten sieben Tage, mit den Fragen der
  [Sicherheits-Checkliste](../agents/roles/code-reviewer.md#sicherheits-checkliste). Neue Befunde
  legt sie als Issue an; was bereits ein Issue hat, meldet sie nicht erneut. Sie ändert keinen
  Code.

## Zweimal im Jahr und anlassbezogen: Tiefen-Audit

Vor jedem Funktionsrelease, also zweimal im Jahr ([Takt](releases.md#supportlinien)), läuft ein
Tiefen-Audit, das den Code je Angriffsklasse prüft. Außerdem läuft es bei Anlass: ein neuer
Konnektor, ein neues Dateiformat, eine Änderung an Anmeldung oder Rechten.

Das erste Audit lief am 29.10.2026 (Epic #3325) in acht Prüfbereichen:

| Bereich | Issue |
|---|---|
| Abhängigkeiten, SBOM und offene CVEs | #3326 |
| API-Schnittstellen, Authentifizierung und Autorisierung | #3327 |
| Injection (SQL, JPQL, Pfad, Kommando, Log) | #3328 |
| Frontend, XSS und Sicherheits-Header | #3329 |
| Secrets, Kryptografie und Konfiguration | #3330 |
| Konnektoren, ausgehende Verbindungen und SSRF | #3331 |
| Datei-Upload und Dokument-Parsing | #3332 |
| Container, Compose, Helm und CI-Lieferkette | #3333 |

Jeder Befund wurde ein eigenes Issue unter #3325 und wird über einen PR behoben; noch offene
Befunde stehen dort.

Bis zum ersten finalen Release behandelt das Projekt die Befunde eigener Audits öffentlich als
Issues und Pull Requests; bisher gibt es nur Vorab-Releases (`v0.1.0-rc.N`). Ab dem ersten finalen
Release gilt für alle Befunde, auch die eigener Audits, der vertrauliche
[Meldeweg](../SECURITY.md#meldeweg): Ausnutzbare Details erscheinen erst mit der Korrektur als
Security Advisory.

## Geplant

Diese Prüfungen sind beschlossen, aber noch nicht gebaut:

- Architekturtests für Sicherheitsinvarianten als Klassen (ausgehende Verbindungen nur über die
  Zielprüfung, Geheimnisse nur über die Bausteine „Geheimnis folgt Ziel“, Rechteprüfung an jeder
  Controller-Methode) und ein nächtliches Parser-Fuzzing über alle zugelassenen Formate (#3379)
- eine Regressionssuite für präparierte Dateien, SSRF-Ziele und Log-Hygiene (#3383)
- ein nächtlicher ZAP-Baseline-Scan gegen den laufenden CI-Stack (#3384)

## Was es nicht gibt

- **Keine externe Prüfung:** Eine Sicherheitsuntersuchung oder einen Penetrationstest durch Dritte
  gab es bisher nicht. Alle Prüfungen oben führt das Projekt selbst durch, mit CI-Werkzeugen und
  KI-Agenten unter Aufsicht des Maintainers.
- **Kein Bug-Bounty-Programm.**
- **Kein dynamischer Scan** einer laufenden Instanz in der CI (geplant, siehe oben).
