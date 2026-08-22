# Issue #420 — feat(upload): Dokumente über die REST-API in eine wählbare Bibliothek hochladen und wieder entfernen
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:L, workspace
- PRs: #432 (2026-08-17)

**Laut Issue:** Es gab keinen Upload — kein `multipart`-Endpunkt, kein `MultipartFile`, `documents` ohne einbringende Person. Gefordert: `POST /api/v1/libraries/{libraryId}/documents` (mindestens `EDITOR`, Formatprüfung, Größenobergrenze 50 MB Standard, Dublettenprüfung per Prüfsumme mit 409) und `DELETE .../documents/{documentId}`, `uploaded_by_user_id` an `documents`, `DocumentSourceType.UPLOAD`, sichere Dateiablage ohne Pfaddurchgriff, Standardziel persönliche Bibliothek als Client-Vorauswahl (kein zweiter Serverpfad).

**Geliefert:** PR #432 setzt den vollen Umfang um. Der PR-Body dokumentiert zwei Review-Runden mit insgesamt vier bzw. einem weiteren blockierenden Befund, alle vor Merge behoben — bemerkenswert: Löschen zerstörte ursprünglich fremde Quelldateien (jetzt nur `sourceType == UPLOAD` und Pfad unter dem Upload-Storage-Verzeichnis), und ein Race-Verlierer beim gleichzeitigen Upload derselben Datei hinterließ zunächst verwaiste Chunks im Vektorspeicher (behoben durch früheres Setzen der Prüfsumme und `vectorStore.delete` im Fehlerfall). Pfaddurchgriff durch `../../../../etc/evil.txt`-Test explizit abgesichert. Drei Follow-up-Issues ausgelagert (#434 Rate-Limit, #435 inhaltsbasierte Formaterkennung, #436 403/404-Vereinheitlichung). e2e-CI war laut PR-Body aus standortbedingter Ursache (Playwright-Chrome-Download-Fehler) rot, nicht wegen dieser Änderung.

**Verifikation:** `backend/src/main/resources/openapi/opaa-api.yaml` enthält heute `/api/v1/libraries/{libraryId}/documents` (Zeile 974) und `/api/v1/libraries/{libraryId}/documents/{documentId}` (Zeile 1075); `docs/STATUS.md` führt den Upload heute unter „Gebaut" (Zeile 25, 99–103) mit denselben Details wie im PR beschrieben (`uploaded_by_user_id`, Löschverhalten). Deckt sich vollständig.

**Themen:** upload, spaces, workspace, api, epic-198, sicherheit
