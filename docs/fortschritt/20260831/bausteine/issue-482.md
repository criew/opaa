# Issue #482 — docs(sources): Spezifikation auf Bibliothekstypen nachführen
- Geschlossen: 2026-08-19 (completed)
- Labels: documentation, size:M
- PRs: #512 (2026-08-19)

**Laut Issue:** `knowledge-sources.md` sollte die Ein-Typ-Regel nachführen (kein Konnektor-mit-Quellen-Zielbild, keine gemischt gespeisten Bibliotheken mehr), Querverweise in `spaces-and-assets.md`/`data-indexing-rag.md` prüfen und Issue #207 zur strukturell erzwungenen 1:1-Zuordnung kommentieren.

**Geliefert:** Wie gefordert. Der Konnektor-Abschnitt verweist jetzt auf ADR-0018 statt ein eigenständiges Mehrquellen-Objekt zu beschreiben; neue Sektion „Verzeichnis im Dateisystem (gebaut)"; Auslösung an der Bibliothek statt Systemverwaltung; „Eine Quelle, eine Wissensbibliothek" als strukturell erzwungen neu gefasst; Löschsemantik um „ganze Bibliothek löschen" ergänzt; Zeitplan-Abschnitt auf „je Bibliothek" umgestellt. Reine Dokumentationsänderung.

**Verifikation:** `docs/features/knowledge-sources.md`, `docs/features/spaces-and-assets.md`, `docs/features/user-frontends.md` existieren; alle wurden seither weiter aktualisiert (u. a. durch #485, #493, #507), was für ein weiterhin gepflegtes Dokument spricht statt für Verfall.

**Themen:** doku, spaces, retrieval, adr
