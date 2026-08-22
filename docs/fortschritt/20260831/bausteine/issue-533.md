# Issue #533 — Veraltete Space-Arten und „Ablegen“-Terminologie in CONCEPTS.md bereinigen
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation, size:S
- PRs: #568 (2026-08-20)

**Laut Issue:** `docs/CONCEPTS.md` beschrieb noch drei Space-Arten (Persönlich/Projekt/Team) und Sichtbarkeit durch „Ablegen" (DRAFT/PLACED) — beides seit #333 überholt (eine Space-Art über `isDefault`/`memberSource`, PRIVATE/SHARED-Terminologie „in den Space geteilt"). Reine Dokumentationsänderung, Abgleich mit `spaces-and-assets.md` gefordert, plus Prüfung umliegender Glossareinträge.

**Geliefert:** PR #568 gleicht den Space-Abschnitt in `CONCEPTS.md` an `spaces-and-assets.md` an und passt umliegende Glossareinträge an (Beispiele, System-Admin-Eintrag, Schnellreferenz), die implizit noch von einer eigenen „Team-Space"-Art ausgingen. Einzige geänderte Datei ist `docs/CONCEPTS.md`, wie im Issue gefordert.

**Verifikation:** `docs/CONCEPTS.md` im Worktree enthält keine Treffer mehr für „Persönlich/Projekt/Team" oder „Ablegen"; stattdessen `isDefault`/`memberSource` als aktuelle Attribute dokumentiert. Deckt sich mit dem PR-Anspruch.

**Themen:** doku, spaces, cleanup
