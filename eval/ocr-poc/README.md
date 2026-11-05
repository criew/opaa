# OCR-PoC (Issue #1062, Epic #2640)

Messstand für die Frage, welches OCR-Verfahren gescannte Verwaltungsdokumente am besten erschließt.
Liegt wie die übrigen Werkzeuge unter `eval/` außerhalb von Gradle-Build und CI und berührt das
Produkt nicht.

## Ablauf

1. `build_corpus.py` rendert jedes Dokument aus `eval/corpus/verwaltung` zu PDF, rastert es mit 300 dpi
   und erzeugt je Scanprofil (`gut`, `mittel`, `fax`) ein reines Bild-PDF. Den Solltext kennen wir
   (`truth.txt`, Überschriften und Gebührentabelle in `truth.json`). Gebührenordnungen bekommen ihr
   Gebührenverzeichnis als Tabelle mit Gitterlinien angehängt.
2. Je Variante ein Läufer, jeweils in einer eigenen Python-Umgebung:
   - `run_tesseract.py`: OCRmyPDF mit Tesseract `deu` (Messlatte)
   - `run_docling.py --variant docling-easyocr|docling-rapidocr`: Docling mit EasyOCR bzw. RapidOCR (PP-OCRv6)
   - `run_paddle.py`: PaddleOCR PP-StructureV3
3. `evaluate.py` bewertet alle Ausgaben unter `work/out`. Gemessen werden Zeichen- und Wortfehlerrate,
   erkannte und als Überschrift markierte Überschriften, erhaltene §-Zeichen, Tabellenzellen,
   auffindbare Antwortstellen des Golden Datasets, BM25-Recall@5 der Golden-Fragen, Sekunden je
   Seite und Speicherspitze.

```bash
python build_corpus.py --out work/corpus            # Umgebung requirements-tools.txt, dazu pandoc und pdftoppm
python run_tesseract.py --corpus work/corpus --out work/out
python run_docling.py --variant docling-rapidocr --corpus work/corpus --out work/out [--profiles mittel] [--docs verwaltung-0001,...]
python run_paddle.py --corpus work/corpus --out work/out
python evaluate.py
```

Die Modelle laden Docling und PaddleOCR beim ersten Lauf nach (Hugging Face, ModelScope). Ergebnisse und
Bewertung stehen in `REPORT.md`.
