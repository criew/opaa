# Issue #351 — Umfang der Storage-Backend-Abstraktion festlegen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #380 (2026-08-14)

**Laut Issue:** Prüfauftrag aus Epic #344: Abstraktion über S3, Netzlaufwerk (SMB/NFS) und lokales Dateisystem soll air-gapped-Betrieb und Rechenzentrumsbetrieb stützen und bleibt voraussichtlich bestehen — offen war der Umfang. Zu klären: welche Backends sind für die Zielgruppe nötig, was ist heute gebaut vs. nur dokumentiert, gehört MinIO in den Compose-Stack.

**Geliefert:** PR #380 stellt als tragenden Befund fest, dass die Abstraktion **im Code nicht existiert**: `DocumentService` arbeitet direkt mit `java.nio.file.Path` gegen ein einziges konfiguriertes Verzeichnis (`OPAA_INDEXING_DOCUMENT_PATH`), obwohl die Spezifikation drei gleichrangige Backends beschrieb. Entschiedene Linie: Dateisystem ist der Vertrag; Netzlaufwerke (SMB/NFS) brauchen keine eigene Abstraktion, da vom Betriebssystem eingehängt; Objektspeicher wird als eigener Weg ohne Termin geführt, aber ohne Code heute; kein Objektspeicher-Dienst im mitgelieferten Compose-Stack. Damit liegt die Lieferung näher an „Erwartung korrigieren" als an „Abstraktion ausbauen" — die Spezifikation wird an den tatsächlichen (schmaleren) Code-Stand angepasst statt umgekehrt.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/DocumentService.java` existiert, `OPAA_INDEXING_DOCUMENT_PATH` ist in `backend/src/main/resources/application.yml` referenziert — der im PR beschriebene Ist-Zustand (ein Verzeichnis, keine Backend-Abstraktion) ist damit im Code bestätigt. `docs/features/deployment-infrastructure.md` enthält den Abschnitt „Speicher-Backends" (Zeile 124).

**Themen:** doku, deployment, architektur, produktvision
