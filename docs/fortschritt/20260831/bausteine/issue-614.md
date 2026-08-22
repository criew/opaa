# Issue #614 — Nacharbeiten zum asynchronen Upload: Pool-Konfiguration, Lösch-Restfenster, PENDING-Recovery
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:S
- PRs: #631 (2026-08-20)

**Laut Issue:** Vier aus der zweiten Review-Runde zu PR #589 (#434) festgehaltene Punkte: (1) eigene Pool-Konfiguration für den Upload-Executor statt Mitbenutzung der Indexing-Pool-Properties, (2) ein Restfenster im Löschpfad, durch das ein parallel abschließender Upload-Task verwaiste Chunks im Vektorspeicher hinterlassen kann, (3) fehlende PENDING-Recovery nach einem Prozessabsturz während eines Upload-Tasks, (4) Textpflege (stale Javadoc-Verweise, MSW-Handler-Verhalten bei nicht extrahierbarem Text).

**Geliefert:** PR #631 setzt alle vier Punkte um: eigene `opaa.upload.thread-pool`-Property (dokumentiert in `application.yml`, `.env.example`, `docs/deployment.md`); `LibraryDocumentService#deleteDocument` löscht jetzt Zeile und Chunks vor der Datei-Nachbehandlung in `deleteAfterCommit`, um das Restfenster zu schließen; neuer `UploadPendingRecoveryRunner` setzt beim Start alte PENDING-Uploads auf FAILED (neue Spalte `documents.created_at`, Migration 041/043 je nach Zählweise); Textpflege inkl. MSW-Handler-Anpassung auf PENDING→FAILED-Modell. Reproduktionsnachweis für Punkt 2 im PR-Body dokumentiert (Mockito-Verify-Fehler bei zurückgenommener Reihenfolge).

**Verifikation:** `backend/src/main/java/io/opaa/library/UploadPendingRecoveryRunner.java` und `UploadProperties.java` existieren im heutigen Worktree.

**Themen:** backend, upload, indexing, nacharbeiten, robustheit
