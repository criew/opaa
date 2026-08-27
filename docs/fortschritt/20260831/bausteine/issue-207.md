# Issue #207 — Connector sources target exactly one knowledge library
- Geschlossen: 2026-08-23 (completed)
- Labels: enhancement, backend, size:M
- PRs: keine (strukturell durch ADR-0018/Epic #486 gelöst, kein eigener PR zu diesem Issue)

**Laut Issue:** Eine Konnektorquelle soll genau eine Wissensbibliothek speisen (`SourceMapping.targetLibrary`, single-valued), mit Ausschluss einzelner Dokumente auf Bibliotheksebene. Zusätzlich sollte die Freigabe-Obergrenze definiert werden: was genau begrenzt wird (`visibility`, `listed`, Gruppengrößen-Grants), was beim nachträglichen Absenken passiert, und ob gemischt gespeiste Bibliotheken die Obergrenze tragen.

**Geliefert:** Die 1:1-Zuordnung ist nicht mehr nur Policy, sondern strukturell erzwungen: Mit ADR-0018 (Epic #486) gibt es keine separate `SourceMapping`-Tabelle mehr — die Wissensbibliothek selbst trägt `sourceType` und ihre Quellkonfiguration, unveränderlich nach Anlage. Mehrfachzuordnungen sind damit datenmodellseitig gar nicht mehr ausdrückbar. Der Ausschluss einzelner Konnektor-Dokumente wirkt an der Bibliothek. Die dritte Scope-Zeile des Issues ("System admin decides where indexing goes") ist überholt: ADR-0018 öffnet die Bibliotheksanlage zunächst für jeden Berechtigten, befristet bis #484 die Anlageberechtigung einschränkt (inzwischen geschehen). Gemischt gespeiste Bibliotheken (Upload + Konnektor) entfallen mit der Ein-Typ-Regel ersatzlos. **Nicht geliefert:** Die Definition und Durchsetzung der Freigabe-Obergrenze — der einzige inhaltlich offene Punkt — wurde in ein neues, fokussiertes Sub-Issue #797 herausgelöst (nicht Teil dieses Chunks).

**Verifikation:** `backend/src/main/java/io/opaa/library/KnowledgeLibrary.java` enthält ein `sourceType`-Feld (`DocumentSourceType`); keine `SourceMapping`-Klasse im Backend gefunden — bestätigt die Ablösung der ursprünglichen Tabelle durch ADR-0018.

**Themen:** konnektoren, wissensbibliotheken, retrieval, rechteverwaltung
