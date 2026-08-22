# Issue #475 — docs(decisions): ADR-0018 — Quellkonfiguration in der Bibliothek
- Geschlossen: 2026-08-18 (completed)
- Labels: documentation, enhancement, size:M
- PRs: #487 (2026-08-18)

**Laut Issue:** Ein ADR sollte festlegen, dass eine Wissensbibliothek künftig genau einen Quellentyp und höchstens eine Quellkonfiguration trägt (Ein-Typ-Regel), Entscheidung 4 aus ADR-0017 ablöst, Zugangsdaten-Grundsätze festlegt und die Löschregel für Konnektorbibliotheken bestimmt. Verworfene Alternativen (eigene Quellen-Tabelle, gemischte Bibliotheken) sollten dokumentiert sein.

**Geliefert:** Wie gefordert. ADR-0018 liegt unter `docs/decisions/` vor, beschreibt das Verhältnis zu ADR-0017 (Entscheidung 4 abgelöst, Registry/Löschsemantik bleiben) und ist Grundlage des Epics #486. Reine Dokumentationsänderung, keine Codeänderung in diesem PR.

**Verifikation:** `docs/decisions/0018-quellkonfiguration-in-der-bibliothek.md` und `docs/decisions/0017-quellentypmodell-indizierung.md` existieren im heutigen Repo. Die im ADR festgelegte Ein-Typ-Regel ist im Code umgesetzt (`KnowledgeLibrary.sourceType`, siehe #476) — das ADR beschreibt tatsächlich Gebautes, nicht nur Zielbild.

**Themen:** doku, adr, spaces, retrieval
