# Issue #516 — feat(frontend): Quellkonfiguration einer Bibliothek nachträglich bearbeiten
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, frontend, size:M
- PRs: #542 (2026-08-19)

**Laut Issue:** Die Quellkonfiguration (Feed-URL, Verzeichnispfad, Proxy, Zugangsdaten) ließ sich nachträglich nicht über die Oberfläche ändern, obwohl das Backend es bereits erlaubte (`PUT /libraries/{id}`). Gefordert: Bearbeitungsdialog mit denselben typspezifischen Feldern wie im Erstellungsdialog, Zugangsdaten nur neu setzen oder unverändert lassen, Quellentyp bleibt unveränderlich.

**Geliefert:** Wie gefordert, plus zwei im PR selbst gefundene und behobene Backend-Fehler. Neuer Dialog `EditLibrarySourceDialog`. Beim Review zeigte sich, dass die bestehende Update-Semantik gespeicherte Zugangsdaten beim alleinigen Ändern eines anderen Feldes stillschweigend auf `null` setzte — behoben mit Rückfall auf den gespeicherten Wert. Eine zweite, sicherheitsrelevante Nachbesserung: Der erste Fix hätte Zugangsdaten bei einem Wechsel der Quell-URL auf einen fremden Host mitwandern lassen (Datenabfluss-Risiko) — der Fallback greift jetzt nur noch bei unverändertem Origin (Schema/Host/Port), mit eigenem Reproduktionsnachweis. Ein explizites Entfernen von Zugangsdaten bleibt bewusst nicht möglich; der Verbindungstest (#537, parallel gemergt) wurde bewusst nicht in diesen Dialog integriert, da der Testendpunkt keine gespeicherten Zugangsdaten wiederverwenden kann.

**Verifikation:** `frontend/src/components/EditLibrarySourceDialog.tsx` existiert; `frontend/src/utils/librarySourceConfig.ts` bündelt die geteilte Validierungslogik mit der Anlage.

**Themen:** frontend, backend, sicherheit, spaces, retrieval
