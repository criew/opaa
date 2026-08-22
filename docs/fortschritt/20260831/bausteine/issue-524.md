# Issue #524 — Spezifikation an Chat-im-Space und @-Bibliotheksreferenzen anpassen
- Geschlossen: 2026-08-19 (completed)
- Labels: documentation, size:S
- PRs: #531 (2026-08-19)

**Laut Issue:** `docs/features/spaces-and-assets.md` sollte um die im Epic #523 entschiedene Semantik erweitert werden (Schalter „Wissen nutzen", @-Referenzen sticky pro Chat, Übergangsregel bis #203), `docs/features/user-frontends.md` auf das neue Modell umgeschrieben, `docs/STATUS.md` ergänzt und `docs/CONCEPTS.md` auf Ergänzungsbedarf geprüft werden. Reine Dokumentationsänderung, keine Codeänderung.

**Geliefert:** PR #531 liefert exakt das: Abschnitte „Chats" und „Suchbereich je Chatart" in `spaces-and-assets.md` erweitert; `user-frontends.md`-Abschnitt „Dokumentenübersicht, Gesprächsverwaltung und Suchfilter" umgeschrieben; `STATUS.md` ergänzt um Epic #523; `CONCEPTS.md` erhält neuen Glossareintrag „Suchbereich eines Chats". Zusätzlich (nicht explizit gefordert, aber sachlich naheliegend) wurde `docs/features/agents-and-tools.md` mitgeändert.

**Verifikation:** Reine Dokumentationsänderung, per Grep nicht sinnvoll gegen Code zu prüfen; Dateien existieren im Repo. Deckt sich mit den Folge-Issues (#525–#529), die gegen genau diese Spezifikation implementiert wurden.

**Themen:** doku, chats, retrieval, spaces, epic-523
