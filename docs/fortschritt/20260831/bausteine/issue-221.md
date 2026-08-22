# Issue #221 — feat: Anwendungstexte auf Deutsch umstellen (Frontend und Backend)
- Geschlossen: 2026-08-02 (completed)
- Labels: enhancement, backend, frontend, size:L
- PRs: #223 (2026-08-02), #286 (2026-08-02, sachfremd), #291 (2026-08-02, sachfremd)

**Laut Issue:** Alle sichtbaren Frontend-Texte (Seiten, Layouts, Komponenten, Platzhalter, Fehlermeldungen, `aria-label`) sowie nutzerseitige Backend-Texte (API-Fehlermeldungen, Default-Workspace-Name) sollten auf Deutsch umgestellt werden, ohne i18n-Framework, mit `de-DE`-Datumsformatierung. Log-Meldungen und Bezeichner bleiben Englisch.

**Geliefert:** PR #223 liefert den vollen Umfang: Frontend-Texte inklusive `aria-label` umgestellt, neue Übersetzungs-Zuordnung `frontend/src/utils/labels.ts` für Enum-Anzeigewerte (API-Werte selbst unverändert), `de-DE`-Datumsformatierung, deutsche API-Fehlermeldungen in `GlobalExceptionHandler`/Controllern/`WorkspaceService`, Standard-Workspace jetzt „Meine Dokumente“, feste Locale `de_DE` für Bean-Validation, UTF-8-Fix für `JavaCompile`/`Test`-Tasks gegen kaputte Umlaute unter Windows. Ein vorbestehender, unabhängiger Accessibility-Testfehler wird im PR benannt, nicht behoben.

Auffällig: GitHub verknüpft zusätzlich #286 und #291 mit diesem Issue. Beide gehören inhaltlich nicht hierher — es sind CI-Änderungen am täglichen Report-Skript (`daily_report.py`). Die Verknüpfung entsteht vermutlich, weil #286 in seiner eigenen Prüfliste den Testfall-String „Closes #221“ als Beispiel für ein Regex-Muster nennt; GitHub übernimmt das offenbar trotz Inline-Code-Formatierung als echte Closing-Referenz. #291 behebt just diesen Fehlzuordnungs-Bug im Report-Skript selbst (u. a. genau dieses Beispiel), bleibt aber ebenfalls fälschlich mit #221 verknüpft. Für die Leistungsinventur zählt inhaltlich nur #223.

**Verifikation:** `frontend/src/utils/labels.ts` existiert. `SpaceService.java` (nach Ablösung von `WorkspaceService` durch das Space-Modell) verwendet weiterhin „Meine Dokumente“ als Namen der persönlichen Bibliothek.

**Themen:** frontend, backend, projektsprache, i18n, doku-datenqualität
