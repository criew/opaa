# OCR-PoC – lokaler Lauf mit GPU (5./6. November 2026)

Lauf auf einem lokalen Rechner mit AMD-Grafikkarte. Korpus wie im README: 72 Dokumente, 230 Seiten, die
Profile `gut`, `mittel`, `fax` und `schlecht`, gebaut mit `build_corpus.py` aus Stand `60c362e6`.
Tesseract lief hier nicht, weil die Cloud-Messung alle Profile schon abdeckt.

## Hardware und Plattform

| | |
|---|---|
| CPU | AMD Ryzen AI 9 365, 10 Kerne / 20 Threads |
| RAM | 24 GB, davon rund 23 GB für Windows nutzbar |
| GPU | AMD Radeon RX 9060 XT, 16 GB VRAM (RDNA 4), Treiber 32.0.32015.7029 |
| Betriebssystem | Windows 11 Home, Build 26300 |
| GPU-Stack | PyTorch 2.9.1+rocm7.2.1, AMD-Wheels von `repo.radeon.com/rocm/windows/rocm-rel-7.2.1`, nativ unter Windows, Python 3.12.10 |

Die Läufe sollten ursprünglich unter Linux mit CUDA laufen. Auf diesem Rechner steckt aber keine
NVIDIA-Karte. Folgende Wege habe ich versucht:

- **ROCm unter WSL2** (ROCm 7.2.4 installiert) braucht ab ROCm 7.2 die selbst zu bauende Bibliothek
  `librocdxg`. Den Bau hat die Freigabeprüfung der Agenten-Umgebung abgelehnt. Später fiel WSL mit
  `Wsl/Service/E_UNEXPECTED` ganz aus.
- **Gemessen habe ich deshalb nativ unter Windows** mit AMDs PyTorch-ROCm-Build.
  `torch.cuda.is_available()` meldet dort `True`, Docling nimmt `device="cuda"` an.
- **PaddlePaddle hat keinen GPU-Build für AMD-Radeon** (weder ROCm noch DirectML). Paddle lief daher
  nur als CPU-Stichprobe, ebenfalls unter Windows.
- **RapidOCR in Docling rechnet die OCR mit onnxruntime (CPU-Paket) auf der CPU.** In der GPU-Variante
  `docling-rapidocr` laufen nur Layout- und Tabellenmodell auf der GPU. Eine GPU-OCR für RapidOCR wäre
  hier nur über `onnxruntime-directml` oder MIGraphX möglich. Das habe ich nicht gemessen.
- `MIOPEN_FIND_MODE=FAST` war gesetzt. Ohne diese Einstellung sucht MIOpen beim ersten Dokument
  minutenlang Kernel: 423 s statt rund 15 s für drei Seiten.

Die Sekunden je Seite stammen von einem Notebook-Prozessor mit Desktop-GPU. Die CPU-Werte lassen sich
deshalb nur bedingt mit Server-Kernen vergleichen. Die Läufe liefen nacheinander, nie zwei
gleichzeitig.

## Installationsgrößen

| Umgebung | Größe | davon |
|---|---|---|
| Docling + torch-ROCm (Windows-venv) | 5,7 GB | ROCm-SDK-Wheels 3,5 GB, torch 1,2 GB |
| Docling + torch-ROCm (Linux-venv in WSL, torch 2.11+rocm7.2.4) | 5,2 GB | |
| PaddleOCR (Windows-venv, paddlepaddle 3.3.1 CPU) | 1,2 GB | |
| PaddleOCR (Linux-venv) | 1,6 GB | |
| Werkzeuge (evaluate) | 76 MB (Windows), 265 MB (Linux, mit weasyprint/ocrmypdf) | |
| ROCm 7.2.4 in WSL (apt, `--usecase=rocm`) | nicht gemessen, weil WSL danach ausfiel | nur für den verworfenen WSL-Weg |

Modelle (Download beim ersten Lauf):

| Modell | Größe |
|---|---|
| Docling-Layout und TableFormer (`docling-models`, `docling-layout-heron`) | 506 MB |
| EasyOCR (de/en) | 94 MB |
| RapidOCR PP-OCR-ONNX | 163 MB |
| granite-docling-258M | 506 MB |
| PP-StructureV3 (`~/.paddlex`) | 965 MB |

Docker-Images habe ich nicht gebaut. Für ein Produktiv-Image auf AMD kommen zu den venv-Größen die
ROCm-Laufzeitbibliotheken hinzu (bei AMDs `rocm/pytorch`-Images üblicherweise über 10 GB). Ein
CUDA-Image liegt in einer ähnlichen Größenordnung.

## Läufe

| Variante | Gerät | Umfang | Dauer |
|---|---|---|---|
| `docling-easyocr` | GPU | alle 72 Dokumente × 4 Profile | 66 min |
| `docling-rapidocr` | GPU (Layout/Tabellen), OCR auf CPU | alle 72 Dokumente × 4 Profile | 215 min |
| `docling-granite` | GPU | alle 72 Dokumente, nur `mittel` | 67 min |
| `docling-easyocr`, `docling-rapidocr` | CPU | Stichprobe, 12 Dokumente × `mittel`, `schlecht` | 48 / 26 min |
| `paddle-structure` | CPU | Stichprobe, 12 Dokumente × `mittel`, `schlecht` | 103 min |
| `paddleocr-vl` | – | nicht gelaufen | – |

`paddleocr-vl` lief nicht, weil PaddleOCR-VL über die Paddle-Pipeline eine Paddle-GPU braucht.

Das Profil `schlecht` lief getrennt nach `work/out-schlecht`, weil `runlib.cli` noch
`gut,mittel,fax` als Vorgabe hat. Danach habe ich die Ergebnisse in `work/out` zusammengeführt,
einschließlich der `timing.json`-Einträge. `run()` überschreibt die `timing.json` einer Variante bei
jedem Lauf. Wer Profile nachholt, muss sie also zusammenführen, sonst fehlen die Zeiten der früheren
Profile.

Der GPU-Lauf `docling-rapidocr/schlecht` brach nach 35 Dokumenten ohne Fehlermeldung und ohne
Absturzeintrag ab. Die übrigen 37 Dokumente liefen in einem zweiten Prozess nach. Dieser Nachlauf war
mit 70–90 s je Dokument auffällig langsamer als der erste Teil mit rund 40 s. Der Wert von 19,2 s je
Seite für `schlecht` ist deshalb zu hoch angesetzt.

## Ergebnisse

- GPU, ganzer Korpus: [table-gpu.md](table-gpu.md), [results-gpu.json](results-gpu.json)
- CPU-Stichprobe: [table-cpu.md](table-cpu.md), [results-cpu.json](results-cpu.json)
- GPU-Läufe auf dieselben 12 Dokumente eingeschränkt, zum direkten Vergleich mit der CPU:
  [table-gpu-stichprobe.md](table-gpu-stichprobe.md)

Sekunden je Seite, Stichprobe aus 12 Dokumenten:

| Variante | Profil | GPU | CPU | Beschleunigung |
|---|---|---|---|---|
| docling-easyocr | mittel | 4,2 | 31,9 | 7,6× |
| docling-easyocr | schlecht | 5,3 | 40,3 | 7,6× |
| docling-rapidocr | mittel | 12,7 | 22,5 | 1,8× |
| docling-rapidocr | schlecht | 20,8 | 16,1 | keine (siehe Nachlauf oben) |
| docling-granite | mittel | 23,6 | – | – |
| paddle-structure | mittel / schlecht | – | 69,5 / 85,4 | – |

Bei der Erkennungsqualität liefern GPU und CPU praktisch dieselben Zahlen.

## Qualitative Beobachtungen

Angesehen habe ich je Variante die Gebührentabelle aus `verwaltung-0003` in den Profilen `mittel` und
`fax` sowie die Satzung `verwaltung-0001`, Profil `mittel`.

### docling-easyocr

- **§ wird nie erkannt.** In Überschriften steht `## 8 1 Geltungsbereich und Zweck`, im Fließtext
  `regelt $ 3 dieser Satzung`. Deshalb ist die §-Quote in allen Profilen 0,0. Für
  Verwaltungsdokumente, in denen nach Paragraphen gesucht wird, ist das ein Ausschlussgrund.
- Überschriften sind als `##` markiert. Die Hierarchie ist flach, das gilt für alle Docling-Varianten.
- Vor Satzzeichen stehen Leerzeichen (`überprüft .`, `Antragstellende Person … ist;`).
- Die Tabelle erkennt Docling als Markdown-Tabelle, die Spalte Nr. ist aber lückenhaft:
  - `mittel`: Kopf „Nr.“ fehlt, Nr. 4, 7 und 8 sind leer, in Zeile 1 steht nur „Euro“ ohne Betrag.
  - `fax`: Kopf lautet „Nr:“, Nr. 6–8 sind leer, alle Beträge stimmen.
- Bei `schlecht` bricht die Qualität ein: CER 17,7 %, nur 16 von 27 Antwortstellen auffindbar.

### docling-rapidocr

- § wird korrekt gelesen, in Überschriften (`## § 1 Geltungsbereich und Zweck`) wie im Fließtext.
- Die Tabelle ist in `mittel` und `fax` vollständig: Kopf „Nr.“, alle acht Nummern und Beträge. Bei
  `fax` fehlt nur ein Leerzeichen (`Eilbearbeitung(Sozialleistungsbescheinigung)`).
- Robust gegen schlechte Vorlagen: CER bei `schlecht` 1,3 %, alle 27 Antwortstellen gefunden.
- **Schwäche: Die letzte Zeile mancher Absätze fehlt.** Beispiel ist der Einleitungsabsatz von 0001:
  „auf Grundlage des Kommunalabgabengesetzes.“ fehlt. Die letzten vier Wörter eines Absatzes fand ich
  in 95 % (`gut`), 97 % (`mittel`) und 95 % (`fax`) der Fälle wieder.
- Deshalb schneidet `gut` schlechter ab als `mittel`: CER 2,4 % gegenüber 0,9 %, Antwortstellen 23/27
  gegenüber 27/27. In `verwaltung-0005` (`gut`) sind vier Absätze unvollständig, in `mittel` zwei. Die
  Ursache liegt vermutlich in der Zuordnung der OCR-Zeilen zu den Layout-Clustern in Docling, nicht in
  der Zeichenerkennung.

### docling-granite (VLM)

- § ist korrekt, Überschriften als `## § 1 …`.
- Nummerierte Absätze gibt Granite als Liste aus: `- (1) Diese Satzung …`.
- Die Tabelle ist vollständig und korrekt. Einziger Fehler ist ein doppeltes Leerzeichen in
  `31,00  Euro`.
- Auch hier fehlt gelegentlich die letzte Zeile eines Absatzes, zum Beispiel in der Einleitung von
  0001.
- **Ausreißer:**
  - `verwaltung-0009` brauchte 443 s statt rund 45 s. Rund ein Fünftel des Textes fehlt (CER 0,20),
    vermutlich weil eine Seite bis `max_new_tokens` generiert und dann verworfen wurde.
  - `verwaltung-0038` hat eine CER von 0,15.
  - Ohne diese beiden Dokumente läge die CER deutlich unter 1 %. Für den Betrieb bräuchte Granite eine
    Abbruch- und Rückfallregel.

### paddle-structure (PP-StructureV3, CPU)

- Beste Zeichenerkennung: CER 0,22 % bei `mittel`. Die WER liegt trotzdem bei 2,5 %, weil an
  Zeilenumbrüchen Leerzeichen nach Satzzeichen fehlen (`(Fassung 2023),beschlossen`,
  `Unterstützung.Sie`). Das schadet der Wortsuche.
- § ist korrekt, Überschriften als `## § 1 …`. Hinter Überschriften steht ein Leerzeichen am
  Zeilenende.
- Die Tabelle kommt als HTML. Der Läufer setzt sie in Markdown um, siehe unten. Inhaltlich ist sie
  vollständig und korrekt, aber die **Spaltenreihenfolge ist vertauscht**:
  `Amtshandlung | Gebühr | Nr.`.
- Speicherspitze 11,3 GB. Auf der CPU ohne oneDNN braucht Paddle 70–85 s je Seite.

## Änderungen an den Läufern

- `runlib.py`: Läuft auch unter Windows. Ohne das Modul `resource` nimmt `run()` den Spitzen-Working-Set
  aus `psutil`.
- `run_docling.py`: Das Gerät kommt aus `OCR_DEVICE`, Vorgabe bleibt `cpu`, für GPU `cuda`. Der
  ROCm-Build von torch meldet sich ebenfalls als `cuda`.
- `run_paddle.py`:
  - Gerät aus `OCR_DEVICE`.
  - oneDNN ist standardmäßig aus (`OCR_MKLDNN=1` schaltet es ein). Mit oneDNN bricht paddlepaddle 3.3.1
    bei jedem Dokument nach 0,2 s ab:
    `NotImplementedError: ConvertPirAttribute2RuntimeAttribute not support [pir::ArrayAttribute<pir::DoubleAttribute>]`
    (Linux und Windows).
  - HTML-Tabellen der PP-StructureV3-Ausgabe werden in Markdown-Pipe-Zeilen umgesetzt, die übrigen
    `<div>`/`<html>`-Hüllen entfernt. Ohne diese Umsetzung bewertet `evaluate.py` Paddle-Tabellen mit
    0 Zellen und zählt das Markup als Zeichenfehler.
- `run_docling_vlm.py` (neu): Variante `docling-granite`, Docling-VLM-Pipeline mit
  `GRANITEDOCLING_TRANSFORMERS`.
- `evaluate.py` blieb unverändert. Unter Windows muss es mit `PYTHONUTF8=1` laufen, weil es Dateien
  ohne Encoding-Angabe liest.

## Einschätzung

1. **docling-rapidocr** ist auf diesem Korpus der beste Kompromiss:
   - § wird erkannt, Tabellen sind vollständig, die Variante ist robust bei `fax` und `schlecht`.
   - Auf der CPU sind 16–22 s je Seite machbar.
   - Die GPU bringt nur wenig, solange die OCR auf der CPU läuft.
   - Offen sind die verlorenen letzten Absatzzeilen.
2. **paddle-structure** erkennt Zeichen am genauesten. Dagegen sprechen:
   - fehlende Leerzeichen an Zeilenumbrüchen und vertauschte Tabellenspalten
   - 11 GB RAM, langsam auf der CPU
   - der oneDNN-Fehler
   - kein GPU-Weg auf AMD
3. **docling-granite** kommt dem Original strukturell am nächsten, hat aber gelegentliche Ausfälle
   ganzer Textteile und braucht eine GPU.
4. **docling-easyocr** ist auf der GPU mit rund 4 s je Seite am schnellsten, scheidet aber wegen des
   §-Fehlers und der Schwäche bei schlechten Vorlagen aus.
