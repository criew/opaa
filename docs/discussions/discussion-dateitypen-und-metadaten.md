# Discussion: Dateitypen der Verwaltung und geführte Metadaten-Anreicherung

**Thema:** (1) Welche Dateitypen OPAA heute versteht, welche im Verwaltungskontext dazukommen müssen und wie pro Typ sinnvoll geparst und gechunkt wird. (2) Konzeptvorschlag: ein geführter Bibliotheks-Assistent („Wizard"), der beim Anlegen einer Wissensbibliothek per LLM-Vorklassifikation ein Metadatenschema vorschlägt.

**Kontext:** Konkretisiert Abschnitt 5.2 (strukturbasiertes Chunking) und Szenario 9 (Metadaten-Probleme) des [Retrieval-Tech-Reports](discussion-retrieval-strategien.md) sowie Phase 2a/2b der [Retrieval-Roadmap](discussion-retrieval-roadmap-opaa.md). Baut auf der älteren [Pipeline-pro-Dokumenttyp-Diskussion](discussion-retrieval-document-pipelines.md) auf und erweitert sie um die Verwaltungsperspektive.

---

## 1. Ist-Stand

`SupportedDocumentFormats` lässt heute zu: **`.md`, `.txt`, `.pdf`, `.docx`, `.doc`, `.pptx`** (inhaltsbasierte Formaterkennung, #404). Dazu kommt **RSS** als eigener Quellentyp (ADR-0017/0018). Alle Formate laufen durch dieselbe Pipeline: Tika-Extraktion → `TokenTextSplitter` (1000 Token, 100 Überlappung) → Embedding. Struktur (Kapitel, Folien, Paragrafen, Tabellenzeilen) geht dabei verloren; Metadaten am Chunk sind heute rein technisch (`document_id`, `chunk_index`, `file_name`, `library_id`, `location`).

## 2. Dateitypen im Verwaltungskontext: Bestand und Lücken

Bewertung der Verwaltungsrelevanz aus typischen Beständen: Satzungen/Ordnungen, Dienstanweisungen, Vermerke, Bescheid-Vorlagen, Formulare, Protokolle, Gebührenverzeichnisse, Organigramme, Projektunterlagen, E-Mail-Verkehr, Intranet-Seiten, gescannte Altakten.

| Dateityp | Status OPAA | Verwaltungsrelevanz | Parsing-Empfehlung | Chunking-Empfehlung | Typische Metadaten-Kandidaten |
|---|---|---|---|---|---|
| **PDF (digital erzeugt)** | ✅ (Tika, strukturlos) | sehr hoch — Satzungen, Bescheide, Amtsblätter | Layoutbewusst aufrüsten: `ParagraphPdfDocumentReader` (TOC-basiert) bzw. Docling für Tabellen | Nach Gliederung (§/Abschnitt/Kapitel); Tabellen als Einheit behalten | Dokumentart, Gliederungspfad (§, Absatz), Fassung/Stand, Beschlussdatum, Aktenzeichen |
| **PDF (Scan, Bild-PDF)** | ❌ (liefert leeren Text) | sehr hoch — Altakten, unterschriebene Originale | **OCR-Stufe nötig** (Tesseract via Tika-OCR-Konfiguration oder Docling); Scan-Erkennung beim Ingest, klare Fehlermeldung statt stillem Leer-Index | wie digitales PDF nach OCR; Qualitätsflag am Dokument | wie PDF + OCR-Konfidenz (niedrige Konfidenz = Warnhinweis im Beleg) |
| **DOCX/DOC** | ✅ (Tika) | sehr hoch — Vermerke, Dienstanweisungen, Vorlagen | Tika reicht; Überschriften-Ebenen aus Styles mitnehmen | Nach Überschriftenabschnitten, Fallback Token | Dokumentart, Az., Verfasser/OE, Datum, Gültigkeitsstatus (Entwurf/in Kraft) |
| **ODT/ODS/ODP (LibreOffice)** | ❌ | hoch — viele Behörden arbeiten mit LibreOffice/ODF | Tika parst ODF nativ — **fast geschenkte Erweiterung** der Zulassungsliste + Formaterkennung | wie DOCX/XLSX/PPTX-Pendants | wie die jeweiligen Pendants |
| **PPTX** | ✅ (Tika, Folien als ein Block) | mittel — Schulungen, Gremienvorlagen | Eigener Reader (Apache POI): 1 Folie = 1 Chunk, Titel+Notizen als Kontext | Pro Folie, mit Foliennummer und Titel im Präfix | Vortragstitel, Foliennummer, Datum, Gremium/Anlass |
| **XLSX/CSV** | ❌ | hoch — Gebührenverzeichnisse, Zuständigkeitslisten, Haushaltsdaten | POI/CSV-Parser; Tabellenstruktur erhalten (nicht zu Fließtext glätten) | Pro logischer Tabelle bzw. Zeilengruppe; Spaltenköpfe in jeden Chunk wiederholen | Tabellenname, Blattname, Spaltenköpfe, Stand/Gültigkeitsjahr |
| **MD/TXT** | ✅ | mittel — technische Doku, Wiki-Exporte | `MarkdownDocumentReader` (Header-Struktur) statt Tika | Pro Überschriftenabschnitt | Überschriftenpfad |
| **HTML** | ❌ (nur via RSS-Umweg) | hoch — Intranet, Government Site Builder, Ratsinformationssysteme | `JsoupDocumentReader` mit Boilerplate-Entfernung (Navigation/Footer) | Pro Abschnitt (h1–h3) | URL, Seitentitel, Abrufdatum, Gliederungspfad |
| **EML/MSG (E-Mail)** | ❌ | mittel–hoch — Vorgangskommunikation, Verfügungen per Mail | Tika parst nativ; Header (Von/An/Betreff/Datum) als Metadaten statt Fließtext; Anhänge separat durch die jeweilige Typ-Pipeline | Pro Nachricht; lange Threads pro Nachricht im Thread | Betreff, Absender/Empfänger-OE, Datum, Vorgangs-/Aktenzeichen aus Betreff |
| **XML-Fachformate (LegalDocML.de, XJustiz, XÖV)** | ❌ | wachsend — NeuRIS liefert Normen als LegalDocML | Eigener struktur­treuer Parser pro Format (kein generisches Tika-Flatten) | Entlang der Fachstruktur (Norm: §/Absatz mit eId) | Normkürzel, §, Absatz, **Fassung/Zeitscheibe**, Rechtsebene (Bund/Land), Fundstelle |
| **TIFF/PNG/JPEG (Einzelscans)** | ❌ | mittel — eingescannte Einzelblätter | OCR wie Scan-PDF; ohne OCR ablehnen statt leer indexieren | nach OCR wie TXT | wie Scan-PDF |
| **RSS** | ✅ (Quellentyp) | mittel — Pressemitteilungen, Amtliche Bekanntmachungen | bestehend | bestehend | Veröffentlichungsdatum (für Aktualitätsfragen) |

**Priorisierungsvorschlag für die Zulassungserweiterung:** (1) **ODF** (nahezu kostenlos, hohe Behördenrelevanz), (2) **XLSX/CSV** (Gebühren- und Zuständigkeitstabellen sind das Rückgrat vieler Auskunftsfragen), (3) **Scan-PDF/OCR** (größter Bestand, größter Aufwand — als eigenes Epic), (4) **HTML/EML**, (5) XML-Fachformate erst mit konkretem Quellanschluss (z. B. NeuRIS).

Zwei Querschnittsregeln aus dem Tech-Report gelten für alle Typen: Exakte Kennungen (§§, Aktenzeichen, Erlassnummern) müssen den lexikalischen Suchpfad unzerlegt erreichen, und jeder Chunk trägt seinen Struktur-Kontext (Abschnittstitel → Contextual-Chunking-Präfix) — die Typ-Pipeline ist der Ort, an dem beides entsteht.

## 3. Konzeptvorschlag: Geführter Bibliotheks-Assistent mit Metadaten-Vorklassifikation

### Die Idee

Beim Anlegen einer Wissensbibliothek führt ein Assistent durch drei Schritte:

1. **Zweck erfragen:** Der Nutzer beschreibt in Freitext, welche Art von Fragen die Bibliothek beantworten soll („Bürger fragen nach Gebühren und Unterlagen", „Sachbearbeiter suchen Regelungen in Dienstanweisungen").
2. **Stichprobe analysieren:** OPAA sichtet einige hochgeladene bzw. verknüpfte Dokumente und klassifiziert per LLM: Dokumentarten (Satzung? Protokoll? Projektplan?), erkennbare Struktur (§-Gliederung? Tabellen?), wiederkehrende Merkmale (Aktenzeichen-Muster, Datumsangaben, Ortsnamen).
3. **Schema aushandeln:** Aus Zweck + Stichprobe schlägt OPAA ein **Bibliotheksprofil** (Typ-Pipeline-Zuordnung aus Abschnitt 2) und ein **Metadatenschema** vor — z. B. für Rechtsnormen `{Normkürzel, §, Fassung, Rechtsebene}`, für Projektdokumente `{Projekt, Team, Standort, Datum}`. Der Nutzer bestätigt, streicht oder ergänzt; erst dann läuft die Indexierung, die die Felder pro Dokument/Chunk befüllt.

Die Metadaten wirken anschließend an drei Stellen des Retrievals: als **harte Filter** (Fassung, Rechtsebene, Datum — die Lösung für Szenario 9 des Tech-Reports), als **Kontextpräfix** im Embedding und Volltextindex (Contextual Chunking), und als **Beleg-Anzeige** („§ 3 Verwaltungsgebührensatzung, Fassung 2026").

### Bewertung: Ist LLM-Vorklassifikation hier schlau?

**Ja — mit fünf Leitplanken.** Das Muster ist extern validiert (RAGFlow löst dasselbe Problem mit manuell gewählten Chunking-Templates pro Wissensbasis; der Wizard automatisiert genau den Schritt, der dort Expertenwissen verlangt) und adressiert die im Tech-Report identifizierte Wahrheit, dass Fassungs-, Ebenen- und Zuständigkeitsfragen **Metadaten-Probleme** sind, die kein Embedding-Tuning löst. Die Leitplanken:

1. **Vorschlagen, nie entscheiden.** Das LLM klassifiziert und schlägt vor; das Schema beschließt der Mensch. Ein falsch geratenes Schema ist teuer (siehe 3), ein bestätigtes ist dokumentierte Absprache. Das ist zugleich die personalrats- und referatsleitungsverträgliche Form: nachvollziehbar, was warum erfasst wird.
2. **Deterministisch extrahieren, wo es geht.** §-Referenzen, Aktenzeichen-Muster, Datumsangaben, Dateinamenskonventionen sind Regex-/Parser-Arbeit — zuverlässig, billig, auditierbar. Das LLM übernimmt nur die unscharfen Felder (Dokumentart, Thema, Projekt) und liefert dabei eine Konfidenz; unsichere Werte bleiben leer statt geraten. Ein halluzinierter Metadatenwert ist schlimmer als keiner, weil er als **harter Filter** wirkt und Dokumente unsichtbar macht.
3. **Schema klein und stabil halten.** Jede Schemaänderung, die in Embedding-Präfixe einfließt, bedeutet Reindex; jedes Feld will bei jedem künftigen Dokument befüllt sein. Deshalb: wenige Felder (3–6), kontrolliertes Vokabular statt Freitext, und die Aufnahmeregel „nur Felder, die einen benannten Retrieval-Nutzen haben" (Filter, Präfix oder Beleg-Anzeige) — sonst entsteht Pflegeballast ohne Wirkung.
4. **Rechte-Invariante.** Vorklassifikation und Schema-Vorschlag laufen nur über Dokumente, die der anlegende Nutzer lesen darf; aggregierte Metadaten (z. B. Wertelisten für Filter-UI) dürfen nichts über unlesbare Dokumente verraten (ADR-0008 sinngemäß auf Metadaten erweitert).
5. **Kosten und Erwartung steuern.** Die Stichprobe klein halten (z. B. 5–20 Dokumente), Klassifikation pro Dokument einmalig beim Ingest, nicht pro Query. Der Wizard muss auch den Weg „ohne Metadaten starten, später anreichern" anbieten — sonst wird er zur Anlegehürde, was dem Ziel „Zeit bis zum ersten Nutzen" widerspricht.

**Risiko, das der Wizard nicht löst:** Metadatenqualität degradiert mit dem Bestand — neue Dokumente mit leeren Feldern, veraltete Fassungsangaben. Dazu gehört ein einfacher Pflege-Anker (Anzeige „N Dokumente ohne Fassungsangabe" in der Bibliothek), sonst tritt der Drei-Jahres-Effekt ein, den der Skeptiker-Stakeholder zu Recht anmahnen würde.

### Abgrenzung

- Kein automatisches, freies Tagging jedes Chunks durch das LLM („Auto-Keywords" à la RAGFlow) als Default — das erzeugt unkontrolliertes Vokabular ohne Filternutzen. Erst als bewusste Option, wenn ein Nutzungsszenario es trägt.
- Kein Versuch, ein amtliches Metadatenmodell (XÖV-Standards) vollständig abzubilden — das Schema dient dem Retrieval, nicht der Aktenführung.

## 4. Einordnung in die Roadmap

- Die **Typ-Pipelines** (Abschnitt 2) sind die konkrete Ausformung von Roadmap-Phase 2b; ODF/XLSX-Zulassung kann als Quick Win vorgezogen werden, OCR ist ein eigenes Epic.
- Der **Wizard** setzt sinnvoll nach Phase 1 auf (Metadaten-Filter brauchen den Volltext-/Filterpfad) und liefert die Datengrundlage für Szenario 9 (Fassung/Ebene). Vorstufe ohne UI: Metadatenschema als Bibliotheks-Konfiguration, Wizard-UX danach.
- Messbarkeit: Golden-Fälle mit Filterbezug („Gebühr nach Stand 2024", „nur Dienstanweisungen") in die Verwaltungs-Evaldomäne aus Roadmap-Phase 0 aufnehmen.
