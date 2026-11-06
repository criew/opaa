# Testdokument der Texterkennung

| | |
|---|---|
| **Dokument** | `satzung-scan.pdf`, fünf Seiten, reines Bild-PDF ohne Textebene, A4 |
| **Inhalt** | Baugenehmigungsgebührensatzung der fiktiven Stadt Kalkstadt (Fassung 2024) aus `eval/corpus/verwaltung/verwaltung-0010_*.md`, als Anlage das Gebührenverzeichnis aus `verwaltung-0011_*.md`. Seite 5 enthält einen für dieses Dokument geschriebenen Absatz zur Zahlung und ist quer abgelegt (`/Rotate 90`). |
| **Quelle und Lizenz** | vollständig synthetisch, CC0-1.0, siehe [`eval/corpus/verwaltung/SOURCE.md`](../../../../eval/corpus/verwaltung/SOURCE.md). Kein amtliches Werk und keine Fremdquelle. |
| **Erzeugung** | `python make_scan.py`: gesetzt mit DejaVu Serif, gerastert mit 200 dpi, verschlechtert wie das Scanprofil „mittel“ von `eval/ocr-poc/build_corpus.py` (leichte Schräglage, Unschärfe, graues Papier, Rauschen), JPEG. Deterministisch. |
| **Sollorte** | `satzung-scan.layout.json`: Lage der abgefragten Stellen und der Abschnitte, in denen sie indiziert sind (Tabellenkörper, § 13, Textblock der gedrehten Seite), als Ausdehnung der gesetzten Zeilen in Seitenbruchteilen, Ursprung oben links, aus dem Satz und nicht aus einer Erkennung |
| **Antwort von opaa-ocr** | `satzung-scan.docling.json`: das DoclingDocument eines echten `opaa-ocr`-Laufs mit den Optionen des Backends, aufgezeichnet mit `capture_docling.sh` im Workflow „OCR-Abnahme“ (Artefakt `ocr-docling-json`). Der Ersatzdienst der E2E-Suite spielt es ab. |

Wer das Dokument oder die Optionen ändert, erzeugt PDF und Sollorte neu (`make_scan.py`) und zeichnet
die Antwort neu auf. Ohne die neue Aufzeichnung passt die abgespielte Antwort nicht mehr zum PDF.

Abweichend vom Issue #3606, das eine Bundestagsdrucksache vorschlug, ist das Testdokument
synthetisch. So sind die Sollorte der Rahmen aus dem Satz bekannt, statt von Hand abgelesen. Die
Herkunft ist ohne Prüfung des Urheberrechts eindeutig. Echte Scans misst `eval/ocr-poc` (#1062).
