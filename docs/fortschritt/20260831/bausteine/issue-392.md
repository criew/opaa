# Issue #392 — feat(audit): Rechte- und Verwaltungsereignisse an den bestehenden Diensten erfassen
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:L, security
- PRs: #444 (2026-08-17)

**Laut Issue:** Baut auf #391 auf. Alle Ereignisse, die Zugriff verändern oder Verwaltungshandeln sind, sollen an den bestehenden Diensten (`AssetGrantService`, `KnowledgeLibraryService`, `SpaceService`, `GroupService`, `DirectorySyncPlanExecutor`, Systemrollen/-einstellungen) protokolliert werden — auch abgelehnte Aktionen. Ausdrücklich nicht erfasst: Abfragen, Antwortinhalte, Lesezugriffe, erfolgreiche Anmeldungen.

**Geliefert:** Neue `AuditEventRecorder`-Bündelklasse instrumentiert die genannten Dienste; Verzeichnisabgleich erzeugt je bewirkter Änderung einen Eintrag plus Kopfeintrag über `correlation_ref`. **Nur Ereignisse, die im Code bereits entstehen**, wurden verdrahtet — mehrere in der Spezifikation genannte Ereignistypen (Grant-Ablauf, Freigabe-Obergrenzen, Asset-Nachfolge, API-Tokens, diverse Systemeinstellungsänderungen) bleiben unverdrahtet, weil die zugrundeliegende Funktionalität im Repository noch nicht existiert — offen benannt, nicht verschwiegen. Wichtiger Nebenbefund: `@Transactional(noRollbackFor = ResponseStatusException.class)` war nötig, weil Springs Standard-Rollback sonst auch den `DENIED`-Nachweis eines abgelehnten Eskalationsversuchs mitgelöscht hätte — mit rot/grün-Reproduktionsnachweis belegt. `DENIED` ist bewusst nur für den einen im Issue explizit genannten Eskalations-Fall verdrahtet; weitere Ablehnungspfade als Folge-Issue #447 herausgelöst.

**Verifikation:** `AuditEventRecorder.java` existiert im Worktree unter `backend/src/main/java/io/opaa/audit/`.

**Themen:** audit, protokoll, security, backend, verwaltungshandeln, revisionssicherheit
