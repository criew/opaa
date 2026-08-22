# Issue #344 — Epic: Konzepte und Abstraktionen gegen die neue Produktausrichtung prüfen
- Geschlossen: 2026-08-15 (completed)
- Labels: documentation, epic
- PRs: keine (Epic, Arbeit läuft über Sub-Issues)

**Laut Issue:** Prüf-Epic zu Konzepten/Abstraktionen aus der Zeit des generischen Enterprise-Wissensmanagements, die die neue Ausrichtung (#338) fragwürdig macht — ausdrücklich Prüfung mit Entscheidungsvorlage, keine eigenmächtige Streichung. Drei Phasen: was dem Zielbild widerspricht (Vektorspeicher-Austauschbarkeit, Cloud-Deployment, Chat-Kanäle, Modellanbieter-Standard), was fehlt (Zitierzwang, Audit-Logging, Organisationsgrenze), was offenzuhalten ist (Plugin/MCP, Storage-Abstraktion, Bürgerassistent).

**Geliefert:** Als Sub-Issue-Epic geführt. Aus dem bearbeiteten Chunk bekannt: #348 (Vektorspeicher-Austauschbarkeit → pgvector festgelegt, PR #377), #350 (Cloud-Deployment/Managed Service → als Möglichkeit gefasst, Managed Service gestrichen, PR #378), #351 (Storage-Backend-Umfang → Abstraktion existiert im Code nicht, Dateisystem als Vertrag festgelegt, PR #380). Weitere Sub-Issues zu Chat-Kanälen, Modellanbieter-Standard, Zitierzwang, Audit-Logging, Organisationsgrenze und Phase-3-Themen liegen außerhalb dieses Chunks und wurden hier nicht geprüft.

**Verifikation:** Die drei im Chunk enthaltenen Entscheidungen sind im heutigen Dokumentenbestand nachweisbar umgesetzt (siehe issue-348.md, issue-350.md, issue-351.md). Ob alle Phase-1/2/3-Punkte des Epics abgedeckt sind, lässt sich aus diesem Chunk allein nicht abschließend beurteilen.

**Themen:** doku, produktvision, architektur, agenten-organisation
