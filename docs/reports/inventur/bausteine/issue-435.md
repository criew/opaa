# Issue #435 — feat(upload): Inhaltsbasierte Formaterkennung für nutzerkontrollierte Uploads
- Geschlossen: 2026-08-20 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #577 (2026-08-20)

**Laut Issue:** Der Upload-Endpunkt entscheidet nur über die Dateiendung, nicht über den tatsächlichen Inhalt. Anders als bei betriebsverwalteten Archiven (#404, wo Endungslogik bewusst bleibt) ist der Upload-Inhalt vollständig nutzerkontrolliert — eine als `.pdf` benannte Binärdatei wird ohne Prüfung angenommen. Gefordert: Inhaltserkennung mit deutscher Fehlermeldung bei Abweichung, begrenzt auf den Upload-Pfad.

**Geliefert:** PR #577 setzt genau das um — Tika-Magic-Byte-Erkennung (`Tika#detect`) gegen die behauptete Endung, `400` bei Widerspruch. Toleranz für Text-Formate (`.md`/`.txt`) über `MediaTypeRegistry#isInstanceOf(text/plain)`. Strikte Formate (`.pdf`/`.doc`/`.docx`/`.pptx`) verlangen konkrete Medientypen statt generischer Tika-Fallback-Typen — verhindert, dass unklassifizierbare OLE2-Dateien durchrutschen. Betriebswege (Verzeichnis/URL) bewusst unverändert gelassen, wie im Issue gefordert. Schadsoftwareprüfung bleibt wie vorgesehen außerhalb des Umfangs. Reproduktionsnachweis mit rotem/grünem Testlauf erbracht.

**Verifikation:** `backend/src/main/java/io/opaa/indexing/SupportedDocumentFormats.java` existiert im heutigen Code.

**Themen:** upload, security, backend, formaterkennung
