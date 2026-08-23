# Issue #486 — feat: Bibliothekstypen — Quellkonfiguration wandert in die Bibliothek
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, epic, backend, frontend
- PRs: keine

**Laut Issue:** Epic-Tracking-Issue für den Umbau, bei dem eine Wissensbibliothek aus einem Template angelegt wird und genau einen Quellentyp samt Konfiguration trägt. Drei Phasen (Entscheidung/Datenmodell, Verhalten, Oberfläche/Doku) plus Sicherheits-Nachzügler (#483, #484) vor Produktivbetrieb.

**Geliefert:** Kein eigener PR — wie bei einem Epic üblich, ist die Lieferung die Summe seiner Sub-Issues: #475 (ADR-0018), #476 (Schema/Entity/API), #477 (Dokumentzahl), #478 (Anstoß je Bibliothek), #479 (Upload-/Löschregeln), #480 (Anlage mit Template), #481 (Detailseite), #482 (Spezifikation), #483 (Zugangsdaten-Verschlüsselung), #484 (Pfad-Allowlist). Alle wurden mit eigenen PRs gemergt (siehe jeweilige Bausteine). Die Epic-Abnahmekriterien (unveränderlicher Typ, Bestand bleibt `UPLOAD`, Anstoß kennt nur die Bibliothek, `/documents` und Admin-Drawer-Indizierung entfernt, Zugangsdaten in keiner API-Antwort) sind laut den Einzel-PRs erfüllt.

**Verifikation:** Siehe die Einzel-Bausteine der Sub-Issues; dort ist jeweils der heutige Codezustand geprüft. Nachträgliche Sicherheits- und Sichtbarkeitsfunde (#491, #493, #501, #505, #507, #513–#519) zeigen, dass der Umbau nach dem Epic-Abschluss noch mehrere Review-Nachzügler auslöste — üblich bei einem Umbau dieser Größe, kein Hinweis auf einen unvollständigen Kern.

**Themen:** epic, spaces, retrieval, adr, agenten-organisation
