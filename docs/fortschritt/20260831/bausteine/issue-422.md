# Issue #422 — feat(frontend): Dokumente je Wissensbibliothek anzeigen und hochladen
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, frontend, size:M, workspace
- PRs: #442 (2026-08-17)

**Laut Issue:** Die Platzhalterseite „Dokumente" (nur Symbol + „Demnächst verfügbar") sollte durch eine echte Ansicht ersetzt werden: Bibliotheksauswahl mit persönlicher Bibliothek als Vorbelegung, Dokumentliste mit Status/Herkunft/Größe/Abschnitten, Upload per Auswahl und Drag-and-drop mit Fehlermeldungen (Format, Größe, Dublette), Löschen ab EDITOR, sichtbare FAILED-Kennzeichnung.

**Geliefert:** PR #442 setzt den vollen Umfang um, ohne Backend-Änderungen — die Endpunkte aus #420 existierten bereits vollständig. Bibliotheksauswahl an angezeigte Bibliothek gebunden statt separatem Zielselektor (kein zweiter Upload-Ziel-Wähler wie im Issue skizziert, sondern nur der Ablagebereich der gerade angezeigten Bibliothek — funktional gleichwertig, aber enger geführt). Polling für PENDING-Dokumente, automatischer Stopp beim Verlassen der Seite. 196/196 Frontend-Tests grün. Ein Multipart-Upload-Testfall wurde bewusst auf Store-/Seiten-Ebene statt gegen den rohen MSW-Handler getestet (jsdom/undici-Limitation).

**Verifikation:** `frontend/src/pages/DocumentsPage.tsx` existiert im heutigen Code **nicht mehr**. Git-Historie zeigt: PR #506 („Bibliotheksdetailseite mit typspezifischem Bereich") hat die eigenständige `DocumentsPage` später durch eine in die Bibliotheksdetailseite integrierte Ansicht ersetzt. Die hier gelieferte Funktionalität (Anzeigen/Hochladen/Löschen von Dokumenten je Bibliothek) lebt heute in `LibraryDetailPage.tsx` weiter, nicht in einer eigenen Seite — der PR-Umfang wurde also durch eine spätere Umstrukturierung abgelöst, inhaltlich aber nicht zurückgenommen.

**Themen:** workspace, spaces, frontend, upload, dokumentverwaltung
