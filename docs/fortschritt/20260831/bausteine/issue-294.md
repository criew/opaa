# Issue #294 — fix(auth): Fehler bei der Anlage des persönlichen Space darf den Login-Request nicht scheitern lassen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S, auth
- PRs: keine

**Laut Issue:** Seit PR #287 wird `ensurePersonalSpace` über `TransactionSynchronization#afterCommit` aufgerufen. Schlägt die Space-Anlage in diesem Hook fehl, propagiert die Exception zum Aufrufer, obwohl der Nutzer selbst bereits erfolgreich angelegt und committet ist. Der Zustand ist selbstheilend (nächster Login-Request legt den Space nach), aber der Nutzer sieht dennoch einen Fehler beim ersten Login. Gefordert: Fehler im Hook fangen und auf WARN loggen statt durchreichen, plus Tests für Fehlerfall und Selbstheilung.

**Geliefert:** Kein PR ist mit diesem Issue verknüpft. Der heutige Code in `UserService.ensurePersonalSpaceAfterCommit`/`ensurePersonalSpace` entspricht jedoch exakt der geforderten Lösung: Der Javadoc-Kommentar dort referenziert ausdrücklich "code review of #201/#305" und vermerkt "Failures are logged, not rethrown" — der Fix wurde also nicht als eigener PR mit `Closes #294`, sondern im Zuge der größeren Arbeit an #201/#305 (Wissensbibliothek/persönlicher Space) mitgeliefert und das Issue vermutlich manuell ohne PR-Verknüpfung geschlossen.

**Verifikation:** `backend/src/main/java/io/opaa/auth/UserService.java` (Zeilen ~187–213) zeigt die beschriebene Fehlerbehandlung im `afterCommit`-Hook, mit Javadoc-Begründung, die exakt die Selbstheilungslogik aus dem Issue beschreibt.

**Themen:** auth, backend, spaces, robustheit
