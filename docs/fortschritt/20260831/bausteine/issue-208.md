# Issue #208 — Stewards: group role for accepting shares
- Geschlossen: 2026-08-14 (completed)
- Labels: enhancement, backend, size:M, auth
- PRs: #331 (2026-08-14)

**Laut Issue:** Forderte eine Annahmeseite für Gruppen-Shares: neue Gruppenrollen `STEWARD` (akzeptiert/lehnt Shares an die Gruppe ab) und `LEAD` (ernennt Stewards, verwaltet bei `AD_HOC`-Gruppen die Mitgliedschaft). Ohne Steward routet die Entscheidung an die System-Admin-Arbeitsliste. Zusätzlich eine Schwellenwert-Regel gegen das Umgehen der Kuratierung über kumulative Reichweite je Asset/Empfängerkreis, inklusive Nachprüfung bei Gruppenwachstum durch Verzeichnissynchronisation.

**Geliefert:** PR #331 liefert das Gegenteil des geforderten Umfangs — es entfernt das gesamte Konzept der Annahmeseite ersatzlos, statt `STEWARD`/`LEAD` einzuführen: „Ein Grant an eine Gruppe braucht keine Zustimmung.“ Begründung laut PR: Ein Grant setzt niemanden etwas aus, das Risiko ist Katalog-Rauschen statt Datenabfluss, dagegen wirken `listed = false` und die Governance-Arbeitsliste. Der PR-Body vermerkt ausdrücklich „Schließt #208 gegenstandslos ab“. Zusätzlich entfernt der PR die Asset-Rolle `USER` (Migration 014, `USER`-Grants werden auf `VIEWER` gehoben, nicht gelöscht). Keines der im Issue formulierten Abnahmekriterien (Steward-/Lead-Rollen, Schwellenwert, Liegezeit-Liste) wurde umgesetzt — sie wurden als überflüssig verworfen.

**Verifikation:** `grep -rl STEWARD backend/src/main/java` liefert keinen Treffer — die Rolle existiert im heutigen Code nicht. `AssetRole.java` dokumentiert die verworfene `USER`-Rolle nur noch im Kommentar.

**Themen:** auth, spaces, rechtemodell, governance, verworfenes-feature
