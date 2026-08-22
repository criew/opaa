# Issue #519 — fix(deployment): nginx-Limit von 1 MB verursacht 413 beim Dokument-Upload im Compose-Setup
- Geschlossen: 2026-08-19 (completed)
- Labels: bug, frontend, size:S
- PRs: #532 (2026-08-19)

**Laut Issue:** Der nginx-Reverse-Proxy im Frontend-Container setzte kein `client_max_body_size`, der nginx-Default von 1 MB griff daher vor dem eigentlichen Backend-Limit von 50 MB — jeder Upload über 1 MB scheiterte im Compose-Setup mit HTML-413 statt der JSON-Fehlermeldung des Backends. Gefordert: Limit angleichen, Zusammenhang dokumentieren, Fehlerbehandlung einer nicht-JSON-413-Antwort robust machen.

**Geliefert:** Wie gefordert. `client_max_body_size 50m;` in `frontend/nginx.conf`, mit Kommentar zum Zusammenhang mit `OPAA_UPLOAD_MAX_FILE_SIZE` (kein automatisches Templating, da die Datei fest ins Image gebacken wird — beide Werte müssen manuell synchron gehalten werden). `normalizeError` übersetzt eine nicht-JSON-413-Antwort jetzt in eine deutsche Meldung, während die echte JSON-`ErrorResponse` des Backends unverändert vorrangig bleibt. Reproduktionsnachweis mit rotem/grünem Test im PR dokumentiert.

**Verifikation:** `frontend/nginx.conf` und `frontend/src/services/api.ts` existieren mit den beschriebenen Änderungen.

**Themen:** frontend, deployment, bugfix, upload
