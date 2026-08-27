# Issue #780 — Browservorschau für Markdown-, Text- und DOCX-Originale statt stillem Download
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, frontend
- PRs: #781 (2026-08-23)

**Laut Issue:** „Im Dokument öffnen" öffnete PDFs und Bilder in einem Vorschau-Tab, löste für Markdown, Klartext und DOCX aber einen stillen Download aus — für den zentralen Vertrauensmoment „Beleg bis ins Original prüfen" wirkte das wie ein Fehler. Gefordert war eine Browser-Vorschau für Markdown/Klartext (clientseitig gerendert, ohne HTML-Passthrough) sowie für DOCX entweder eine Konvertierung oder mindestens sichtbares Download-Feedback, falls DOCX beim Download bleibt.

**Geliefert:** Markdown/Klartext werden jetzt in einem neuen `DocumentTextPreviewDialog` gerendert — Markdown über die bestehende sichere `MarkdownRenderer`-Komponente (kein `rehype-raw`, `javascript:`-URLs werden entfernt), Klartext als reiner Text in `<pre>`. **Abweichung vom Issue:** DOCX bleibt bewusst beim Download (keine serverseitige Konvertierung umgesetzt), erhält aber wie im Issue als Mindestanforderung beschrieben eine sichtbare Snackbar-Rückmeldung. PDF-/Bild-Verhalten blieb unverändert. Vier gezielte Sicherheitstests belegen, dass gerendertes Markdown kein Script im App-Origin ausführen kann.

**Verifikation:** `frontend/src/components/DocumentTextPreviewDialog.tsx` existiert im Worktree weiterhin.

**Themen:** frontend, spaces, sicherheit, doku
