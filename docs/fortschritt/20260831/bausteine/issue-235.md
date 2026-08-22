# Issue #235 — feat(demo): Demo-Domänen in getrennte Wissensbibliotheken legen (blockiert)
- Geschlossen: 2026-08-22 (not planned)
- Labels: enhancement, size:M, demo
- PRs: keine

**Laut Issue:** Die vier Demo-Domänen (ursprünglich der Superhelden-Eval-Korpus) sollten in getrennte Wissensbibliotheken gelegt und je einem Space zugeordnet werden, um die rechtebewusste Trennung von Wissensbeständen vorzuführen — inklusive eines Demo-Nutzers ohne Grant auf eine der Bibliotheken, um zu zeigen, dass Space-Mitgliedschaft allein keinen Asset-Zugriff gewährt. Blockiert durch #207, #229 und #234.

**Geliefert:** Nicht umgesetzt — als „not planned" geschlossen. Laut Abschlusskommentar des Maintainers (2026-08-22) ist der Zweck des Vorgangs bereits durch das eigenständige Rheinfurt-Demo-Konzept (Epic #708) erreicht: Die Demo besteht dort aus fünf getrennten Wissensbibliotheken mit je eigener Quellkonfiguration und eigenen VIEWER-Rechten. Der ursprünglich adressierte Superhelden-Korpus bleibt reines Eval-Artefakt in einem gemeinsamen Index, getrennt nur über Dateinamen-Präfix — dafür ist keine Bibliothekstrennung nötig. Das Issue ist damit durch eine parallel entstandene, umfassendere Lösung überholt worden, nicht aus inhaltlichen Bedenken verworfen. Wiedereröffnung vorgesehen, falls die Eval-Seite später doch getrennte Bibliotheken braucht.

**Verifikation:** Entfällt (kein Code geliefert). Die im Abschlusskommentar genannte Ersatzlösung (fünf Bibliotheken im Rheinfurt-Demo-Konzept) ist Gegenstand der Epic-#708-Issues (u. a. #232, #233) und dort verifiziert.

**Themen:** demo, spaces, wissensbibliotheken, rechte, not-planned
