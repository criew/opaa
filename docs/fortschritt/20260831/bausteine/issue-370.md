# Issue #370 — docs(marketing): Screenshots der Landing-Page aus einem Verwaltungskorpus neu aufnehmen
- Geschlossen: 2026-08-23 (completed)
- Labels: documentation, size:S
- PRs: #796 (2026-08-23)

**Laut Issue:** Die Landing-Page-Screenshots (`page/img/chat-interface.png`, `document-browser.png`) zeigten englischsprachige Firmeninhalte ("Document Library", "Q3_Financial_Report") und widersprachen damit der auf Deutsch und Verwaltung umgestellten Positionierung (#338). Gefordert: neue Aufnahmen aus einem Verwaltungskorpus mit deutschsprachigen Dokumenttiteln, einer alltagsnahen Frage und sichtbarer Fundstellenangabe, ohne echte Personennamen oder Aktenzeichen. Betroffen auch `docs/design/` (drei weitere PNGs), sofern weiterverwendet.

**Geliefert:** Beide Landing-Page-Screenshots aus der laufenden Rheinfurt-Demo-Instanz neu aufgenommen (angemeldet als `maria.weber`): `chat-interface.png` zeigt einen Chat im Space "Meldewesen & Ausweise" mit belegter Antwort samt Fundstellenblock, `document-browser.png` die Wissensbibliotheken-Übersicht mit vier verwaltungsnahen Beständen. Alle sichtbaren Namen sind synthetische Demo-Personas aus `docs/demo-walkthrough.md`. Bildunterschriften und Alt-Texte in `page/index.html` blieben unverändert passend. Bewusste Abweichung vom Issue-Umfang: `docs/design/*.png` wurden **nicht** angefasst, weil sie Renderings der HTML-Design-Mockups sind (Design-Artefakte, keine Marketing-Bilder) — der PR-Body begründet das explizit als Nicht-Betroffenheit statt als vergessenen Punkt.

**Verifikation:** `page/img/chat-interface.png` und `page/img/document-browser.png` als geänderte Dateien im PR bestätigt; Inhalt nicht bildlich nachgeprüft (kein Bild-Review im Rahmen dieser Recherche).

**Themen:** marketing, doku, demo
