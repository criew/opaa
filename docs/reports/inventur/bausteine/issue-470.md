# Issue #470 — docs(sources): Feed-Quellen und Quellentypmodell in der Spezifikation nachführen
- Geschlossen: 2026-08-18 (completed)
- Labels: documentation, size:S
- PRs: #494 (2026-08-18)

**Laut Issue:** `docs/features/knowledge-sources.md` sollte am Ende von Epic #463 auf den gebauten Stand gebracht werden: Feeds als gebauter Weg statt Zielbild, eigener Feed-Abschnitt, die typabhängige Löschausnahme mit Begründung, ADR-Verweis, Betriebseinstellungen in `docs/deployment.md`/`.env.example`.

**Geliefert:** Wie gefordert. PR #494 kennzeichnet Feeds als „(gebaut)", ergänzt den Abschnitt „Feeds als Quelle (gebaut)" mit dreistufigem Ablauf, Änderungserkennung und Verhalten gegenüber fremden Zielen, schärft die Löschausnahme (Verweis auf ADR-0017) und verlinkt den offenen Punkt zur Hebung des Netzwegs. `docs/deployment.md`/`.env.example` waren bereits vollständig (aus #467/#468), keine Änderung nötig. Reine Dokumentationsänderung.

**Verifikation:** `docs/features/knowledge-sources.md` und `docs/features/data-indexing-rag.md` existieren im heutigen Code; beide wurden seither mehrfach weiter nachgeführt (u. a. durch #482), was zur eigenen Natur eines lebenden Spezifikationsdokuments passt.

**Themen:** doku, retrieval, feeds, adr
