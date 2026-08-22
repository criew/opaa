# Issue #463 — Epic: Quellentypen erweiterbar machen und RSS-Feeds erschließen
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, epic, backend
- PRs: keine (Epic, geschlossen über Sub-Issues)

**Laut Issue:** Epic mit zwei Zielen — den Quellentyp der Indizierung von einer festverdrahteten `if`-Unterscheidung (URL gesetzt vs. nicht) zu einer ausdrücklichen, erweiterbaren Registry machen, und als ersten Praxistest dieser Erweiterbarkeit RSS-Feeds erschließen. Geplant in drei Phasen (Modell/ADR, RSS-Typ+Parser+Executor, Anlagen+Oberfläche) mit Abhängigkeitskette #464→#465→#466→#467→#468→(#469 Frontend, #470 Doku). Ausdrücklich außerhalb des Umfangs: Zeitplan/Scheduling für Läufe, Zielprüfung gegen private Adressbereiche (#267), Zuordnung Quelle↔Bibliothek (#207), Speicherung von Quellkonfigurationen, weitere Quellentypen.

**Geliefert:** Kein eigener PR — das Epic wurde als Sammelticket über seine Sub-Issues #464 (ADR-0017), #465 (Registry/Executor-Umbau), #466 (RSS_FEED-Typ + Parser), #467 (RSS-Indizierungslauf) und #468 (Anlagen + GSB-Profil) abgearbeitet, die alle einzeln als „completed" mit PR verknüpft sind (siehe jeweilige Bausteine). Die im Epic genannten Folgeschritte #469 (Frontend-Wahl des Quellentyps) und #470 (Doku-Nachführung) sind in diesem Chunk nicht enthalten und wurden hier nicht geprüft — laut Abnahmekriterium „Systemverwaltung kann Quellentyp in der Oberfläche wählen" wäre das Epic ohne #469 nicht vollständig erreicht; ob #469/#470 tatsächlich umgesetzt wurden, muss anderswo in der Inventur festgestellt werden.

**Verifikation:** Alle für die Phasen 1–3 genannten Kernartefakte existieren im heutigen Worktree: `docs/decisions/0017-quellentypmodell-indizierung.md`, `backend/src/main/java/io/opaa/indexing/IndexingSourceType.java`, `RssFeedParser.java`, `RssFeedIndexingExecutor.java`, `AttachmentProfile.java`. Ob die Oberfläche (#469) den Quellentyp tatsächlich wählbar macht, wurde hier nicht verifiziert.

**Themen:** epic, indexing, rss, konnektoren, erweiterbarkeit, adr
