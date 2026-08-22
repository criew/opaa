# Issue #523 — Epic: Chats im Space und Suchbereich per @-Bibliotheksreferenzen
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, epic, backend, frontend, size:L, workspace
- PRs: keine (Epic ohne direkt verknüpften PR)

**Laut Issue:** Chats sollen persistente, space-eigene Objekte werden; die wirkungslose Space-Auswahl im Suchfeld entfällt zugunsten eines Schalters „Wissen nutzen" plus sticky @-Bibliotheksreferenzen am Chat. Vier Phasen: Spezifikation, Backend (Chat-Persistenz + Suchbereichssteuerung parallel), Frontend (Chats im Space + neues Eingabefeld parallel), E2E. Abnahme auf Epic-Ebene: Chat übersteht Neustart, Space-Auswahl vollständig weg, @-Autocomplete mit stickyen Referenzen, Schalterverhalten testbar nachgewiesen, Spezifikation beschreibt den Stand, #205 auf Kollaborationsteil reduziert.

**Geliefert:** Kein PR ist direkt mit #523 verknüpft — das Epic wurde ausschließlich über seine Sub-Issues abgearbeitet, die in diesem Chunk vollständig vorliegen: #524 (Spezifikation, PR #531), #525 (Chat-Persistenz, PR #541), #526 (Suchbereichssteuerung Backend, PR #535), #527 (Chats im Space, Frontend, PR #548), #528 (@-Referenzen + Schalter, Frontend, PR #539), #529 (E2E-Abdeckung, PR #554). Alle sechs Sub-Issues sind „completed" mit gemergten PRs, die inhaltlich exakt die im Epic beschriebenen Phasen abdecken (Spec → Backend parallel → Frontend parallel → E2E). Die Epic-Abnahmekriterien sind damit durch die Summe der Sub-Issues erfüllt: Chat-Persistenz und -Neustart (#525/#527), Space-Auswahl entfernt (#526/#528), @-Autocomplete + sticky Chips (#528), Schalterverhalten per Test nachgewiesen (#526 Unit-Tests, #529 E2E), Spezifikation angepasst (#524). Der letzte Punkt („#205 auf Kollaborationsteil reduziert") liegt außerhalb dieses Chunks und lässt sich hier nicht verifizieren.

**Verifikation:** Kein eigener Code-Realitätscheck nötig — ergibt sich aus der Verifikation der Sub-Issues #524–#529, die alle im Worktree bestätigt werden konnten (Chat-Backend, Frontend-Routen/Komponenten, E2E-Datei vorhanden).

**Themen:** epic, chats, spaces, retrieval, projektsetup
