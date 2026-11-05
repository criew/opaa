# OCR-PoC: Ergebnis (Stand 06.11.2026)

Messung zu #1062 (Epic #2640). Gemessen wurde, welches OCR-Verfahren gescannte Verwaltungsdokumente am
besten erschließt. Aufbau und Werkzeuge stehen in [README.md](README.md). Rohdaten und Notizen des
GPU-Laufs liegen unter [results/](results/).

## Ergebnis

**Docling mit RapidOCR (PP-OCRv6-Modelle über ONNX) ist der beste Kompromiss.** Es ist die einzige
Variante, die gleichzeitig vier Dinge schafft:

- § wird zuverlässig erkannt.
- Tabellen bleiben vollständig erhalten.
- Bei schlechten Vorlagen bleibt die Fehlerrate unter 2 %.
- Sie läuft ohne GPU mit rund 3 GB RAM.

PaddleOCR liest Zeichen am genauesten, ist auf der CPU aber drei- bis viermal langsamer und braucht
11 GB RAM. EasyOCR (Doclings bisherige Vorgabe) und Tesseract scheiden aus, weil sie § nie richtig
lesen.

## Korpus und Messumgebung

- 72 synthetische Verwaltungsdokumente aus `eval/corpus/verwaltung`, 230 Seiten: Satzungen mit
  §-Gliederung, Gebührenordnungen mit Gebührentabelle samt Gitterlinien, Dienstanweisungen und
  Formularhinweise.
- Vier Scanprofile:
  - `gut`: 300 dpi, leichtes Rauschen
  - `mittel`: Schräglage, Grauschleier, Unschärfe
  - `fax`: 200 dpi, binarisiert
  - `schlecht`: 150 dpi, ungleichmäßige Ausleuchtung, JPEG mit Qualität 25
- Tesseract lief in der Cloud-Sandbox auf 4 vCPU. Alle anderen Varianten liefen auf einem Notebook
  (Ryzen AI 9 365, 24 GB RAM, Radeon RX 9060 XT mit 16 GB unter ROCm/Windows), siehe
  [results/NOTES.md](results/NOTES.md).
- Die Docling-Varianten liefen auf der GPU über den ganzen Korpus. Docling (EasyOCR, RapidOCR) und PaddleOCR liefen
  zusätzlich als CPU-Stichprobe (12 Dokumente, `mittel` und `schlecht`). PaddleOCR gibt es für
  AMD-GPUs nicht, PaddleOCR-VL lief deshalb gar nicht.

## Zahlen

Profil `mittel`. Die Spalte „schlecht“ zeigt die Zeichenfehlerrate (CER) im härtesten Profil.

| Variante | CER | CER schlecht | § erhalten | Überschrift erkannt | Tabellenzellen | Antwortstellen | s/Seite CPU | RAM |
|---|---|---|---|---|---|---|---|---|
| **Docling + RapidOCR** | **0,9 %** | **1,3 %** | **100 %** | 98 % | 99 % | 27/27 | 16–22 | 3,1 GB |
| PaddleOCR PP-StructureV3 ¹ | 0,2 % | 4,0 % | 100 % | 98 % | 100 % ² | 14/14 | 70–85 ³ | 11,3 GB |
| GraniteDocling (VLM, nur GPU) | 1,4 % | – | 96 % | 99 % | 100 % | 27/27 | – (GPU 17) | 2,5 GB + VRAM |
| Docling + EasyOCR | 4,4 % | 17,7 % | 0 % | 97 % | 90 % | 22/27 | 32–40 | 6,8 GB |
| Tesseract (OCRmyPDF) | 0,2 % | 5,2 % | 0 % | keine Struktur | keine Struktur | 27/27 | 1–1,6 | < 0,2 GB |

¹ nur CPU-Stichprobe mit 12 Dokumenten. ² Inhalt vollständig, aber die Spalten stehen in der falschen
Reihenfolge (`Amtshandlung | Gebühr | Nr.`). ³ oneDNN abgeschaltet, weil paddlepaddle 3.3.1 damit
abstürzt. Mit oneDNN wäre es schneller.

Der BM25-Recall@5 der 49 Golden-Fragen liegt bei allen Varianten zwischen 0,86 und 0,90, gegenüber
0,88 auf dem Solltext. Die Suche verträgt die verbleibenden Fehler. Das Maß trennt die Varianten auf
diesem Korpus aber nicht.

## Abgleich mit den vorgeschlagenen Schwellen (Docling + RapidOCR)

| Kriterium | Schwelle | gemessen | |
|---|---|---|---|
| CER bei mittlerer Scanqualität | ≤ 2 % | 0,9 % | erfüllt |
| §-Überschriften erkannt | ≥ 90 % | 98 % | erfüllt |
| Tabellenzellen korrekt | ≥ 85 % | 99 % | erfüllt |
| Recall@5 Scan zu Original | ≥ 0,9 × Original | 0,857 / 0,878 = 0,98 | erfüllt |
| Laufzeit CPU | ≤ 3 s/Seite | 16–22 s/Seite | **verfehlt** |
| RAM je Worker | ≤ 8 GB | 3,1 GB | erfüllt |
| Offline-Betrieb | Modelle im Image | nicht gemessen | offen |

Die Laufzeitschwelle war zu optimistisch angesetzt. Bei rund 20 s je Seite braucht ein Worker für
10.000 Scanseiten etwa 55 Stunden. Für den laufenden Zugang neuer Dokumente reicht das, für einen
großen Altbestand braucht es mehrere Worker oder eine GPU-OCR für RapidOCR (`onnxruntime-gpu`,
nicht gemessen). Auf der GPU beschleunigt Docling nur Layout und Tabellen, die OCR selbst rechnet
weiter auf der CPU.

## Auffälligkeiten

- **§ als `$` oder `8`:** EasyOCR und Tesseract lesen das Paragraphenzeichen in keinem einzigen Fall
  richtig. Für Satzungen und Verordnungen ist das ein Ausschlussgrund.
- **Letzte Absatzzeile fehlt:** Bei Docling mit RapidOCR (und bei GraniteDocling) fehlt bei 3–5 % der
  Absätze die letzte Zeile. Die Ursache liegt vermutlich in der Zuordnung der OCR-Zeilen zu den
  Layout-Blöcken in Docling, nicht in der Zeichenerkennung. Deshalb ist `gut` (2,4 %) schlechter als
  `mittel` (0,9 %). Das ist vor einer Umsetzung zu klären (Docling-Option, Seitenrand, Upstream-Issue).
- **GraniteDocling** hatte zwei Ausreißer, einmal 443 s mit rund einem Fünftel Textverlust und einmal
  15 % CER. Im Betrieb bräuchte es eine Abbruch- und Rückfallregel.
- **PaddleOCR** lässt an Zeilenumbrüchen Leerzeichen weg (`2023),beschlossen`). Das treibt die
  Wortfehlerrate (2,5 % bei 0,2 % CER) und schadet der Wortsuche.

## Grenzen dieser Messung

- Der Korpus ist synthetisch: eine Schrift, sauberer Satz, künstliche Scanfehler. Echte Scans mit
  Stempeln, Handschrift, Formularen, Mehrspaltensatz und Fraktur sind nicht dabei. Vor einer
  Umsetzungsentscheidung braucht es 10–20 echte, freigegebene Scans.
- Die Sekunden je Seite stammen von einem Notebook-Prozessor. Server-Kerne können anders liegen.
- Nicht gemessen: Container-Betrieb (`docling-serve`), Offline-Betrieb mit Modellen im Image,
  Koordinaten für die Fundstellen-Markierung, PaddleOCR-VL auf NVIDIA.

## Empfehlung

1. Für die weitere Arbeit (Stufe 1 des Konzepts) Docling mit RapidOCR als Zielvariante setzen,
   betrieben über `docling-serve`.
2. Vorher in einem kurzen Nachtrag messen:
   - **echte Scans:** 10–20 freigegebene Dokumente
   - **die fehlenden Absatzzeilen:** Ursache klären
   - **`docling-serve` als Container:** Imagegröße, Offline-Start mit `DOCLING_SERVE_ARTIFACTS_PATH`,
     `X-Api-Key`
   - **Koordinaten im JSON:** ob sie für eine Sandwich-Anzeige reichen
3. PaddleOCR bleibt die Ausweichoption, falls echte Scans Doclings Layout-Erkennung überfordern.
