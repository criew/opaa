# OCR-PoC: Ergebnis (Stand 06.11.2026)

Messung zu #1062 (Epic #2640). Gemessen wurde, welches OCR-Verfahren gescannte Verwaltungsdokumente am
besten erschließt. Aufbau und Werkzeuge stehen in [README.md](README.md). Rohdaten und Notizen des
GPU-Laufs liegen unter [results/](results/).

## Ergebnis

**Docling mit RapidOCR (PP-OCRv6-Modelle über ONNX) ist der beste Kompromiss.** Es ist die einzige
Variante, die gleichzeitig vier Dinge schafft:

- § wird zuverlässig erkannt.
- Tabellen bleiben vollständig erhalten.
- Im härtesten Profil `schlecht` bleibt die Fehlerrate unter 2 % (GPU-Lauf 1,3 %, CPU-Stichprobe 2,1 %). Bei `fax` liegt sie bei 3,1 %, siehe die fehlenden Absatzzeilen.
- Sie läuft ohne GPU mit rund 3 GB RAM.

PaddleOCR liest Zeichen am genauesten, ist auf der CPU aber drei- bis fünfmal langsamer und braucht
11 GB RAM. EasyOCR (Doclings bisherige Vorgabe) und Tesseract scheiden aus, weil sie § nie richtig
lesen.

## So hängen die Teile zusammen

Die Varianten heißen „Docling + EasyOCR“, „Docling + RapidOCR“ und so weiter, weil Docling selbst kein
OCR-Verfahren ist. Docling ist ein Dokumentkonverter: Er zerlegt eine Seite in Blöcke und setzt daraus
ein strukturiertes Dokument zusammen. Den Text liest eine austauschbare OCR-Engine.

Das Schaubild zeigt den Vorschlag für Stufe 1. Gebaut ist davon heute nur der Bordmittel-Pfad.

```mermaid
flowchart LR
    subgraph opaa["opaa-Backend (Java)"]
        D["Dokument aus einer<br/>Bibliothek oder Quelle"] --> P["Indexierungspipeline"]
        P --> W{"Text<br/>vorhanden?"}
        W -- "ja, PDF mit Textebene" --> T["Bordmittel-Pfad<br/>Tika / PDFBox<br/>(heute schon gebaut)"]
        W -- "nein: Scan-PDF,<br/>JPEG, PNG, TIFF" --> C["OCR-Client"]
        MV[("Modellverwaltung<br/>Rolle Texterkennung:<br/>Art, Adresse, Schlüssel")] -.-> C
        C -- "JSON: Elemente mit<br/>Seite und Koordinaten" --> CH["Chunker entlang der<br/>Docling-Struktur<br/>(§, Tabellenzeilen)"]
        T --> K["Chunks mit Anker,<br/>Embeddings, Index,<br/>Pipeline-Version am Chunk"]
        CH --> K
    end

    C -- "Art 1: docling-serve-API,<br/>X-Api-Key" --> S
    C -. "Art 2: OpenAI-kompatibel,<br/>Markdown je Seite" .-> V["Vision-Modell hinter vLLM<br/>PaddleOCR-VL, GraniteDocling<br/>(GPU)"]

    subgraph svc["OCR-Dienst: mitgelieferter docling-serve oder eigener Dienst"]
        S["REST-Schnittstelle<br/>/v1/convert"] --> R["Seiten rastern"]
        R --> O["OCR-Engine (austauschbar)<br/>RapidOCR mit PP-OCR-Modellen<br/>oder EasyOCR, Tesseract"]
        O --> L["Layoutmodell<br/>Überschrift, Absatz,<br/>Tabelle, Liste"]
        L --> TF["TableFormer<br/>Zeilen und Spalten<br/>der Tabellen"]
        TF --> A["DoclingDocument<br/>(JSON, dazu Markdown)"]
    end
```

**Was opaa aufruft.** Der OCR-Dienst ist ein eigener Container im internen Netz, ohne veröffentlichten
Port und mit API-Schlüssel. opaa programmiert gegen die Schnittstelle von `docling-serve`
(`/v1/convert`), nicht gegen einen bestimmten Container. Daraus ergeben sich zwei Betriebswege:

- **Vorgabe:** opaa liefert einen fertig eingestellten Container mit, `docling-serve` mit RapidOCR und
  den Modellen im Image, damit er offline läuft.
- **Eigener Dienst:** Eine Behörde kann einen eigenen Dienst mit derselben Schnittstelle betreiben,
  etwa auf eigener GPU oder beim IT-Dienstleister, und trägt nur Adresse und Schlüssel ein.

Die maßgeblichen Einstellungen schickt opaa bei jedem Aufruf mit: OCR erzwingen, OCR-Engine
`rapidocr`, Tabellenerkennung an, Ausgabe als JSON und Markdown. Ein fremder Dienst verhält sich dann
wie der mitgelieferte, sofern er die RapidOCR-Modelle hat.

Zurück kommt das `DoclingDocument` als JSON. Nur daraus werden die Chunks geschnitten, das Markdown ist
eine flache Ansicht für Vorschau und Fehlersuche. Am Chunk steht, welches Verfahren den Text geliefert
hat. Fällt der Dienst aus, bleibt das Dokument wie heute als „kein extrahierbarer Text“ stehen.

Gepflegt wird der Dienst als Rolle „Texterkennung“ in der Modellverwaltung der Systemadministration
(Festlegung im Epic #2640). Es gibt genau zwei Anbindungsarten:

1. **docling-serve-API:** Das ist der mitgelieferte Container oder ein eigener Dienst. Zugang über
   `X-Api-Key`, Ergebnis ist das JSON mit Fundorten.
2. **OpenAI-kompatibles Vision-Modell:** etwa PaddleOCR-VL oder GraniteDocling hinter vLLM. Es liefert
   Markdown je Seite, Fundstellen gibt es dort meist nur als Seitensprung.

Eine Adresse, an die opaa Dokumente schickt, ist sicherheitsrelevant. Deshalb darf nur die
Systemadministration sie eintragen, und opaa prüft das Ziel wie bei den übrigen Modellrollen.

**Welche Dateien.** Docling verarbeitet nicht nur PDF. Für Scans zählen vor allem die Bildformate:
JPEG, PNG, TIFF (auch mehrseitig, jede Seite einzeln), BMP, WEBP und GIF. Sie laufen durch dieselbe
Pipeline wie ein Scan-PDF, ebenso durch die VLM-Pipeline weiter unten. Heute weist opaa Bilddateien
ab. Mit dem OCR-Dienst würden sie wie Scans indexiert. Die übrigen Formate, die Docling kann (DOCX,
PPTX, XLSX, HTML), liest opaa schon mit eigenen Bordmitteln. Hier bringt Docling nichts Neues.

**Was in Docling passiert.** Docling rechnet in mehreren Stufen. Keine davon ist ein Sprachmodell:

1. **Rastern:** Docling wandelt jede Seite in ein Bild um, bei Bilddateien entfällt das.
2. **OCR:** Die OCR-Engine findet auf dem Bild die Textzeilen und liest sie.
3. **Layout:** Ein Layoutmodell erkennt die Blöcke (Überschrift, Absatz, Liste, Tabelle, Kopf- und
   Fußzeile) und ihre Lage. Docling sortiert die gelesenen Zeilen diesen Blöcken zu. Bei diesem
   Schritt gehen vermutlich die letzten Absatzzeilen verloren, siehe
   [Auffälligkeiten](#auffälligkeiten).
4. **Tabellen:** TableFormer bestimmt in Tabellenblöcken Zeilen und Spalten.
5. **Ausgabe:** Docling baut aus allem das `DoclingDocument`. Jedes Element trägt dort seine Seite und
   seinen Begrenzungsrahmen. Dazu kommen die Lesereihenfolge, die Überschriftenhierarchie und die
   Tabellen als Raster.

Die OCR-Engine ist eine Einstellung von Docling. Deshalb tragen die Varianten zwei Namen:
- **EasyOCR** ist Doclings Vorgabe.
- **RapidOCR** ist eine schlanke Laufzeit für die Erkennungsmodelle von PaddleOCR (PP-OCRv6, als ONNX
  umgewandelt).

**Docling mit Paddle.** Das geht, und es ist genau die empfohlene Variante. Docling + RapidOCR nutzt
die Texterkennung von PaddleOCR, braucht dafür aber nicht das Framework PaddlePaddle. Layout und Tabellen
kommen weiter von Docling. Für die Gegenrichtung gilt:

- **„PaddleOCR“ in der Tabelle** meint die komplette Paddle-Pipeline PP-StructureV3. Sie ersetzt
  Docling ganz, mit eigenem Layout, eigenen Tabellen und eigenem Markdown, und wäre ein eigener Dienst
  (PaddleX-Serving).
- **Eine Paddle-OCR mit Docling-Layout** ist damit schon abgedeckt.
- **Zeichenerkennung und Tabelleninhalte** sind bei beiden nahezu gleich gut, weil dieselbe
  Modellfamilie liest (PaddleOCR 0,2 % CER, Docling + RapidOCR 0,9 % im Profil `mittel`).
- **Die Unterschiede** liegen im Drumherum: Leerzeichen an Zeilenumbrüchen, Spaltenreihenfolge der
  Tabellen, verlorene Absatzzeilen. Hinzu kommen der Ressourcenbedarf (11 GB gegenüber 3 GB RAM) und
  die Laufzeit auf der CPU (70–85 gegenüber 16–22 s je Seite).

**Bildsprachmodelle (VLM) als zweiter Weg.** Docling hat eine zweite Pipeline. Dort ersetzt ein
kleines Bildsprachmodell Layout und OCR zugleich: Es bekommt das Seitenbild und schreibt das
strukturierte Dokument direkt. Gemessen ist GraniteDocling (258 Mio. Parameter, braucht praktisch eine
GPU). Über dieselbe Pipeline ließe sich auch ein anderes Modell hinter einer OpenAI-kompatiblen
Schnittstelle anbinden, etwa PaddleOCR-VL. Das ist nicht gemessen. Ein VLM erkennt Struktur und Tabellen gut,
kann aber ganze Textteile verlieren, siehe die Ausreißer unten. Für opaa ändert sich an der
Schnittstelle nichts: Die VLM-Pipeline nimmt dieselben Eingaben (Scan-PDF und Bilddateien) und liefert
ebenfalls ein `DoclingDocument`. GraniteDocling gibt dabei Positionsangaben je Element mit aus.

## Fundstellen bei Scans

Ein Beleg springt heute über den Anker des Chunks zur Seite und markiert dort fein über zwei Zitate
(`quoteStart`, `quoteEnd`), die der PDF-Viewer im Textlayer sucht ([ADR-0062](../../docs/decisions/0062-fundstellen-anker-je-chunk.md)). Ein Scan hat
keinen Textlayer. Würde opaa nur das Markdown indexieren, bliebe für einen Scan nur „Seite 3 öffnen“,
ohne Markierung. Entschieden ist deshalb **„Rahmen ohne Kopie“**:

1. **Anker aus dem JSON.** Der Chunker schneidet entlang der Docling-Elemente. Jeder Chunk bekommt
   `pageFrom`/`pageTo`, den vollen Überschriftenpfad (`§ 3 Gebührenbefreiung`) und die Zitate, genau wie
   ein Text-PDF.
2. **Rahmen am Chunk.** Zusätzlich speichert opaa die Begrenzungsrahmen der Elemente des Chunks (Seite
   und Koordinaten aus dem JSON). Der Viewer zeichnet sie als Markierung auf das Original. opaa legt
   dafür keine Kopie des Dokuments ab, das Original kommt wie bisher live von der Quelle. ADR-0062, der
   Bounding-Boxes für Text-PDFs verworfen hat, wird für Scans fortgeschrieben.

**Verworfene Alternativen:**

- **Durchsuchbares PDF (Sandwich, „Anzeigefassung“).** opaa würde aus dem JSON einen unsichtbaren
  Textlayer über das Scanbild legen, wie OCRmyPDF. Der bestehende PDF-Viewer könnte dann über die Zitate
  markieren, und die Suche im Original würde funktionieren.

  Verworfen, weil:
  - opaa je Scan eine abgeleitete Kopie speichern und pflegen müsste, da Originale sonst live von der
    Quelle kommen,
  - das Dokumente in opaa dupliziert, mit Folgen für Speicher, Löschung und Berechtigungen,
  - die Kopie bei jeder Änderung des Originals neu entstehen müsste.
- **Nur Seitensprung ohne Markierung.** Das wäre zu ungenau für mehrseitige Satzungen und Tabellen,
  und das JSON liefert die Koordinaten ohnehin mit.

Das JSON lohnt sich über die Fundstellen hinaus:
- Chunks folgen der §-Gliederung statt einer Zeichenzahl.
- Eine Tabellenzeile wird ein vollständiger Chunk (`Nr. 4 · Zweitausfertigung · 46,00 Euro`) statt
  zerrissenem Fließtext.
- Kopf- und Fußzeilen, Seitenzahlen und Stempel lassen sich als eigene Elementtypen aus dem Index
  heraushalten.

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
- Docling mit EasyOCR und mit RapidOCR liefen auf der GPU über den ganzen Korpus, GraniteDocling nur im Profil `mittel`. Docling (EasyOCR, RapidOCR) und PaddleOCR liefen
  zusätzlich als CPU-Stichprobe (12 Dokumente, `mittel` und `schlecht`). PaddleOCR gibt es für
  AMD-GPUs nicht, PaddleOCR-VL lief deshalb gar nicht.

## Zahlen

Profil `mittel`. Die Spalte „schlecht“ zeigt die Zeichenfehlerrate (CER) im härtesten Profil.

| Variante | CER | CER schlecht | § erhalten | als Überschrift markiert ⁴ | Tabellenzellen | Antwortstellen | s/Seite CPU ⁵ | RAM |
|---|---|---|---|---|---|---|---|---|
| **Docling + RapidOCR** | **0,9 %** | **1,3 %** | **100 %** | 98 % | 99 % | 27/27 | 16–22 | 3,1 GB |
| PaddleOCR PP-StructureV3 ¹ | 0,2 % | 4,0 % | 100 % | 98 % | 100 % ² | 14/14 | 70–85 ³ | 11,3 GB |
| GraniteDocling (VLM, nur GPU) | 1,4 % | – | 96 % | 99 % | 100 % | 27/27 | – (GPU 17) | 2,5 GB + VRAM |
| Docling + EasyOCR | 4,4 % | 17,7 % | 0 % | 97 % | 90 % | 22/27 | 32–40 | 6,8 GB |
| Tesseract (OCRmyPDF) | 0,2 % | 5,2 % | 0 % | keine Struktur | keine Struktur | 27/27 | 1–1,6 | unter 0,2 GB ⁶ |

¹ nur CPU-Stichprobe mit 12 Dokumenten. ² Inhalt vollständig, aber die Spalten stehen in der falschen
Reihenfolge (`Amtshandlung | Gebühr | Nr.`). ³ oneDNN abgeschaltet, weil paddlepaddle 3.3.1 damit
abstürzt. Mit oneDNN wäre es schneller. ⁴ Unscharfer Abgleich (Ähnlichkeit ab 90 %): eine
Überschrift `8 1 Geltungsbereich` statt `§ 1 Geltungsbereich` zählt noch als markiert. Ob die
§-Gliederung selbst erhalten bleibt, zeigt die Spalte „§ erhalten“. `evaluate.py` misst das inzwischen
auch je Überschrift mit exaktem Präfix `§ N` (`paragraph_headings_marked`). Für die lokalen Läufe liegt
dieser Wert nicht vor, weil ihre Ausgaben nicht eingecheckt sind. ⁵ Tesseract auf 4 vCPU in der Cloud,
alle anderen auf dem Notebook-Prozessor. ⁶ Die Speicherspitze der Tesseract-Prozesse erfasst die
Messung nur ungenau (15 bis 123 MB).

Der BM25-Recall@5 der 49 Golden-Fragen liegt bei allen Varianten zwischen 0,86 und 0,90, gegenüber
0,88 auf dem Solltext. Die Suche verträgt die verbleibenden Fehler. Das Maß trennt die Varianten auf
diesem Korpus aber nicht.

## Abgleich mit den vorgeschlagenen Schwellen (Docling + RapidOCR)

| Kriterium | Schwelle | gemessen | |
|---|---|---|---|
| CER bei mittlerer Scanqualität | ≤ 2 % | 0,9 % | erfüllt |
| §-Überschriften erkannt | ≥ 90 % | § zu 100 % erhalten, Überschriften zu 98 % markiert | erfüllt |
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

- Die Laufzeiten der lokalen Docling-Läufe enthalten noch das Laden der Modelle im jeweils ersten
  Dokument. Das erklärt zum Teil, warum `mittel` in der CPU-Stichprobe langsamer ist als `schlecht`.
  Seit dem Review lädt `setup()` die Modelle vorab. Beim GPU-Lauf `docling-rapidocr/schlecht` stammen
  die Zeiten nach einem Abbruch zum Teil aus einem langsameren Nachlauf, siehe
  [results/NOTES.md](results/NOTES.md). Die Werte für die Laufzeit sind deshalb grobe Größenordnungen.
- Seit dem Review wird jedes Scanprofil mit einem eigenen Startwert erzeugt (`Dokument/Profil`). Ein
  neu gebauter Korpus weicht deshalb im Detail von dem gemessenen ab. Die Profile selbst sind
  unverändert.
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
   - **Koordinaten im JSON:** ob Seite und Rahmen je Element (auch Tabellenzellen) stabil und im erwarteten Koordinatensystem kommen, damit der Viewer die Fundstelle auf dem Original einrahmen kann
3. PaddleOCR bleibt die Ausweichoption, falls echte Scans Doclings Layout-Erkennung überfordern.
