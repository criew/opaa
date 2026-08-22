# Issue #569 — Veraltete Space- und „Ablegen“-Terminologie in access-control.md und security-and-compliance.md bereinigen
- Geschlossen: 2026-08-20 (completed)
- Labels: documentation, size:S
- PRs: #605 (2026-08-20)

**Laut Issue:** Beim Review von PR #568 (#533) aufgefallen: `docs/features/access-control.md` und `docs/features/security-and-compliance.md` enthalten noch das überholte Drei-Arten-Space-Modell (seit #333 ersetzt durch eine Space-Art mit `isDefault`/`memberSource`) sowie die alte „Ablegen“-Terminologie statt „in den Space geteilt“ (PRIVATE/SHARED). Beide Dateien sollen an `spaces-and-assets.md` angeglichen und vollständig auf weitere Reste geprüft werden.

**Geliefert:** Entspricht dem Issue. Beide Dateien wurden an das aktuelle Space-Modell und die Teilen-Terminologie angeglichen; laut PR-Beschreibung wurden beide Dateien vollständig nach weiteren Resten durchsucht.

**Verifikation:** Grep nach den im Issue genannten Begriffen („abgelegte“, „Fachbereichs-Spaces“, Drei-Arten-Formulierungen) in `docs/features/access-control.md` und `docs/features/security-and-compliance.md` liefert keine Treffer mehr — die Bereinigung ist im aktuellen Stand sichtbar.

**Themen:** doku, spaces, access-control
