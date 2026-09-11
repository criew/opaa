# Korpus-Generator: Stadt Rheinfurt

Erzeugt den Demo-Korpus unter `demo/corpus/` für die fiktive Stadt Rheinfurt (siehe
[`docs/features/demo-instance.md`](../../docs/features/demo-instance.md), Epic #708, Issue #711).

Dieses Werkzeug liegt wie `eval/generator/` bewusst **außerhalb** des Gradle-Builds und der CI. Es
läuft nie automatisch, sondern nur, wenn der Demo-Korpus bewusst neu erzeugt werden soll — die
Ausgabe wird dann als reguläre Änderung committet und reviewt. Anders als `eval/generator/` gibt es
hier **keinen Ground-Truth-Zwang**: Die Demo misst nichts, sie zeigt.

## Voraussetzungen

- Python 3.11 oder neuer
- Die in `requirements.txt` gepinnten Pakete `python-docx`, `python-pptx`, `reportlab` und
  `openpyxl` (siehe unten, „Werkzeugwahl")
- **Optional, nur für die Word-97-Datei der Bibliothek „Formattest auf S3":** LibreOffice. Die Datei
  ist committet und wird von einem regulären Generator-Lauf nicht angefasst — LibreOffice braucht
  nur, wer ihren Inhalt ändern will (siehe unten, „Formate ohne Writer")
- Netzzugriff beim ersten Lauf, um die 83 ausgewählten Rohdateien des LHM-Dienstleistungen-Corpus
  von HuggingFace zu laden (danach genügt der lokale Cache unter `raw-source/`)

```bash
pip install -r requirements.txt
```

**Byte-Identität ist nur mit den in `requirements.txt` gepinnten Versionen zugesichert.** Ein
neueres `reportlab`/`python-docx`/`python-pptx`/`openpyxl` kann sein Standardausgabeformat ändern (z. B.
Font-Metriken, XML-Formatierung, Zip-Kompressionsdetails) und würde dann andere Bytes erzeugen,
selbst bei identischem Input und identischer Generator-Logik. Wer die Pakete absichtlich
aktualisiert, aktualisiert `requirements.txt` im selben Commit und dokumentiert das in der
PR-Beschreibung.

## Lauf

```bash
cd demo/generator
python generate_corpus.py
```

Das Skript:

1. Lädt die 83 ausgewählten Rohdateien des LHM-Dienstleistungen-Corpus von einem **fest
   verankerten Dataset-Commit** (`3def28953f6d8d65bde7b6b3956fe36c9791a4de`) nach `raw-source/` —
   oder nutzt die dort bereits vorhandenen Dateien, falls ihr SHA-256 zu den in
   `leistungen_quelle.py` hinterlegten Werten passt. Bricht bei Abweichung mit einer klaren
   Fehlermeldung ab, statt still weiterzuarbeiten.
2. Schreibt alle sieben Bibliotheken deterministisch neu (vorhandener Inhalt der jeweiligen
   Zielverzeichnisse wird vorher gelöscht, bis auf die unter „Formate ohne Writer" genannten
   committeten Dateien):
   - `leistungen-meldewesen-ausweise/` (`.md`) und `leistungen-kfz-zulassung/` (`.md`/`.txt`):
     46 bzw. 37 Dokumente, aus den LHM-Rohdateien München→Rheinfurt umgeschrieben
     (`rheinfurt_text.py`, `leistungen.py`).
   - `satzungen-gebuehrenordnungen/` (`.pdf`): 19 synthetische Satzungen mit
     Gebührenverzeichnis (`satzungen.py`).
   - `pressemitteilungen/` (RSS + HTML): ein `rss.xml` plus 27 Detailseiten
     (`presse.py`).
   - `interne-dienstanweisungen-meldewesen/` (`.docx`/`.pdf`/`.pptx`): 26 Dienstanweisungen,
     Eskalationsregeln, FAQ-Dokumente und Schulungsfolien (`intern.py`).
   - `ratsinformationen/<jahr>/` (`.md`/`.txt`): 12 Niederschriften und Beschlussvorlagen des
     Stadtrats und des Hauptausschusses, je Jahrgang ein Unterverzeichnis (`rat.py`) — der
     Ausschnitt, den der Demo-Stack in seinen MinIO-Bucket `rheinfurt-archiv` spiegelt
     (`S3`-Bibliothek, #1383).
   - `formate/` (je ein Dokument pro unterstützter Endung): 14 Dokumente rund um Dokumentenformate,
     Posteingang und Langzeitarchivierung (`formate.py`, `odf_utils.py`) — die technische
     Schaubibliothek „Formattest auf S3", die der Demo-Stack in den MinIO-Bucket `formattest`
     spiegelt (#1519, #1520).
3. Validiert die erzeugten Inhalte aller sieben Bibliotheken gegen eine Liste von Verbotsmustern
   (`validation.py`): reale Ortsnamen (München/KVR/Pasing/Landeshauptstadt/Fischerei), Straßen
   außerhalb einer festen Whitelist fiktiver Rheinfurter Straßen, Postleitzahlen ungleich der
   fiktiven Rheinfurt-PLZ, sowie IBAN/BIC ungleich der fiktiven Rheinfurt-Bankverbindung. Bricht
   mit einer Liste aller Fundstellen ab, statt ein kontaminiertes Ergebnis stillschweigend zu
   schreiben.
4. Prüft, dass Verzeichnisinhalt und geschriebene Dateiliste exakt übereinstimmen (keine
   Karteileichen aus einem früheren, abweichenden Lauf).
5. Schreibt `demo/corpus/MANIFEST.sha256` mit dem SHA-256 jeder erzeugten Datei (relativ zu
   `demo/corpus/`, über alle sieben Bibliotheken hinweg) sowie `demo/corpus/SOURCE.md` selbst — die
   dort genannten Dokumentzahlen sind damit immer die tatsächlich erzeugten, nicht von Hand
   nachgeführte Werte (siehe PR #717 Review, NIT/KLEIN d).

## Verifikation

```bash
cd demo/corpus
sha256sum -c MANIFEST.sha256
```

Zwei Läufe des Generators erzeugen byte-identische Ausgaben — geprüft über `diff -rq` zweier
vollständiger Läufe mit mehreren Sekunden Abstand dazwischen (siehe PR-Beschreibung von #711).

## Formate ohne Writer

Die Bibliothek „Formattest auf S3" (#1519) deckt jede Endung ab, die OPAA zulässt. Für zwei davon
gibt es in Python keinen brauchbaren Writer — sie sind deshalb **committet** statt bei jedem Lauf
erzeugt:

| Endung | Herkunft | Verfahren |
|---|---|---|
| `.doc` (Word 97) | eigener Rheinfurt-Text | `make_doc_fixture.py` rendert den in `formate.py` deklarierten Text über `python-docx` und lässt LibreOffice ihn nach „MS Word 97" umwandeln. Zwei LibreOffice-Läufe erzeugen **keine** byte-gleichen Dateien — genau deshalb ist der Schritt einmalig und nicht Teil von `generate_corpus.py`. |
| `.msg` (Outlook) | Apache-POI-Testkorpus, Apache License 2.0 | Byte-Kopie von `test-data/hsmf/simple_test_msg.msg`. Das Format ist ein proprietärer OLE2/MAPI-Container: keine Python-Bibliothek schreibt ihn, LibreOffice kann es nicht, Apache POI selbst bietet nur einen Leser. Dieselben Dateien liegen bereits als Testfixturen unter `backend/src/test/resources/test-documents/mail/` (mit eigener `NOTICE.md`). **Dieses eine Dokument ist englisch und hat keinen Rheinfurt-Bezug** — Folge seiner Herkunft, kein Versehen; die Bibliotheksbeschreibung im Seed (`demo/seed/profiles.py`) sagt das auch in der Oberfläche. Lizenztext: `corpus/THIRD-PARTY-LICENSES/Apache-POI-testdata-Apache-2.0.txt`. |

Beide Dateien überleben den Clean-Schritt (`PRESERVED_FILES` in `generate_corpus.py`) und gehen
unverändert in `MANIFEST.sha256` ein; fehlt eine, bricht der Lauf ab, statt die Bibliothek
stillschweigend schrumpfen zu lassen. Die Herkunftssätze in `corpus/SOURCE.md` stehen als Wert
neben dem jeweiligen Pfad in `PRESERVED_FILES` — Pfad und Herkunft können nicht auseinanderlaufen.

**Die Lückenliste pflegt sich selbst.** `render_formate_gaps` vergleicht die tatsächlich vorhandenen
Dateien mit der Endungsliste, die `admitted_extensions()` **aus der Formatübersicht des Handbuchs
liest** (`docs/handbuch/indexierung.md`, Abschnitt „Anhang: Formatübersicht") statt sie ein drittes
Mal zu kopieren — kommt dort eine fünfzehnte Endung dazu, erscheint sie ohne Zutun als Lücke in
`corpus/SOURCE.md`. Lässt sich das Kapitel, der Abschnitt oder die Tabelle nicht lesen, bricht der
Lauf ab, statt stillschweigend vollständige Abdeckung zu behaupten. Geschrieben wird dann entweder
die Liste der fehlenden Endungen oder der Satz „Jede Endung der Formatübersicht des Handbuchs ist
mit genau einem Dokument vertreten."

**Ein nachgeliefertes Format braucht trotzdem einen Handgriff, wenn es sich nicht erzeugen lässt:**
Eine Datei, die einfach nur in `corpus/formate/` abgelegt wird, löscht `clean_library_dirs()` beim
nächsten Lauf wieder, bevor `build_formate()` sie überhaupt sieht. Wer ein Format ohne Writer
ergänzt, trägt es deshalb zusammen mit seinem Herkunftssatz in `PRESERVED_FILES` ein — erst dann
überlebt es den Clean-Schritt und landet in `MANIFEST.sha256`. Für ein Format **mit** Writer gilt
das nicht: Es gehört als Renderer in `formate.py` und wird bei jedem Lauf neu geschrieben.

Änderung der Word-97-Datei:

```bash
cd demo/generator
python make_doc_fixture.py          # --soffice <Pfad>, falls LibreOffice nicht gefunden wird
python generate_corpus.py           # zieht MANIFEST.sha256/SOURCE.md nach
```

Die Outlook-Nachricht wird nie neu erzeugt; sie wird höchstens durch eine andere lizenzklare Datei
ersetzt — dann `formate.MSG_FILE_NAME`, den Herkunftssatz in `PRESERVED_FILES` und den Lizenztext
unter `corpus/THIRD-PARTY-LICENSES/` mit ändern.

## Werkzeugwahl für PDF/DOCX/PPTX

Issue #711 verlangt ausdrücklich eine begründete Werkzeugwahl. Kandidaten waren pandoc (+LaTeX),
LibreOffice headless und reine Python-Bibliotheken (`python-docx`/`python-pptx`/`reportlab`).
Kriterien laut Issue: reproduzierbares Ergebnis, im Container ohne Handarbeit lauffähig, von Tika
sauber extrahierbar.

**Entscheidung: reine Python-Bibliotheken** (`reportlab` für PDF, `python-docx` für DOCX,
`python-pptx` für PPTX), keine externen Binärwerkzeuge.

- **Keine externe Laufzeitabhängigkeit.** pandoc und LibreOffice headless brauchen ein
  System-Binary im Container/CI-Image; ein `pip install` genügt hier. Das erfüllt „im Container
  ohne Handarbeit lauffähig" mit dem kleinstmöglichen Fußabdruck und passt zum bereits
  Python-basierten Generator-Muster aus `eval/generator/`.
- **Reproduzierbarkeit ist eine Codeeigenschaft, kein Zufallstreffer.** Reine Bibliotheksaufrufe
  ohne Subprozess, Systemzeit oder Locale-Abhängigkeit lassen sich vollständig kontrollieren. Zwei
  Stolperfallen wurden dabei konkret gefunden und behoben (siehe PR-Beschreibung):
  - `reportlab` schreibt standardmäßig `CreationDate`/`ModDate` und eine zufällige `/ID` in jede
    PDF — behoben über `reportlab.rl_config.invariant = 1`, das reportlab selbst für seine eigene
    reproduzierbare Testsuite vorsieht.
  - `python-docx`/`python-pptx` selbst verwenden feste Werte für die Dokumenteigenschaften
    (`docProps/core.xml`), aber der **Zip-Container** stempelt jeden Eintrag mit der aktuellen
    Systemzeit. Behoben über `zip_utils.normalize_zip_timestamps()`, das jeden Zip-Eintrag nach
    dem Speichern auf einen festen Zeitstempel zurücksetzt.
  - pandoc/LaTeX und LibreOffice headless betten ebenfalls Zeitstempel und/oder
    maschinenabhängige Metadaten ein und bräuchten eigene Nacharbeit, um dasselbe
    Determinismus-Niveau zu erreichen — ohne den Vorteil, dass sich das schon im Python-Prozess
    selbst beheben lässt.
- **Tika-Extrahierbarkeit ist stichprobenartig belegt** (PR-Beschreibung von #711): `reportlab`
  erzeugt Standard-PDF/A-nahe Textobjekte, `python-docx`/`python-pptx` erzeugen reguläres OOXML —
  beides Formate, die Tika (und in den Stichproben `pdfminer.six`/`python-docx`/`python-pptx` zur
  Gegenprobe) ohne Sonderbehandlung extrahiert.

### Nachtrag für XLSX und die OpenDocument-Formate (#1519)

- **XLSX: `openpyxl`**, dieselbe Begründung wie oben. Zwei Eigenheiten waren dafür zu beheben:
  `Workbook.save` überschreibt `properties.modified` unmittelbar vor dem Schreiben mit der
  aktuellen Uhrzeit (deshalb schreibt `formate.py` über `ExcelWriter`), und `openpyxl` legt
  `[Content_Types].xml` als **letzten** Zip-Eintrag ab, wo eine strömende Formaterkennung ihn erst
  nach allen anderen erreicht (deshalb sortiert `zip_utils.normalize_zip_timestamps` ihn auf Wunsch
  nach vorn).
- **ODT/ODS/ODP: von Hand gebaute Pakete** (`odf_utils.py`), keine Writer-Bibliothek. `odfpy` stempelt
  jedes Paket mit seinem eigenen Erzeugungszeitpunkt, was die Byte-Identität brechen würde, und die
  drei ODF-Pipelines des Backends lesen `content.xml`/`meta.xml`/`styles.xml` mit einem einfachen
  SAX-Parser — ein selbst gebautes Paket läuft damit über genau denselben Pfad wie ein
  LibreOffice-Export. Dasselbe Vorgehen nutzt `backend/src/test/resources/test-documents/generate-odf-fixtures.py`
  für die Testfixturen. Vertrag des Containers: `mimetype` als erster, unkomprimierter Eintrag.

## Struktur

```
demo/generator/
├── generate_corpus.py     Orchestriert alle sieben Bibliotheken, schreibt MANIFEST.sha256/SOURCE.md
├── leistungen_quelle.py    Pinning/Download der 83 ausgewählten LHM-Rohdateien
├── rheinfurt_text.py       München→Rheinfurt-Texttransformation (Orte, Straßen, Kontakte, Gebühren)
├── leistungen.py            Rendert die zwei Leistungs-Bibliotheken (.md/.txt)
├── satzungen.py              Satzungsdaten + PDF-Rendering (reportlab)
├── presse.py                 Pressemitteilungsdaten + RSS/HTML-Rendering
├── intern.py                  Interne-Dienstanweisungen-Daten + DOCX/PDF/PPTX-Rendering
├── rat.py                     Ratsinformationen (Niederschriften, Beschlussvorlagen) + Markdown/Text-Rendering
├── formate.py                 Formattest-Bibliothek: je ein Dokument pro unterstützter Endung
├── make_doc_fixture.py        Einmal-Schritt für die Word-97-Datei (LibreOffice), siehe „Formate ohne Writer"
├── odf_utils.py               Baut deterministische ODT/ODS/ODP-Pakete ohne Writer-Bibliothek
├── zip_utils.py               Entfernt nicht-reproduzierbare Zip-Zeitstempel aus DOCX/PPTX/XLSX
├── validation.py               Abschluss-Assert gegen reale Münchner Identifikatoren
├── requirements.txt             Gepinnte Versionen von reportlab/python-docx/python-pptx/openpyxl
└── raw-source/                 Gecachte LHM-Rohdaten, gitignored
```

## Was aus dem LHM-Corpus übernommen wird — und was nicht

- Übernommen werden die vollständigen Leistungsbeschreibungen (Titel, Voraussetzungen, benötigte
  Unterlagen, Gebühren, Auskunftshinweise, Fragen & Antworten) von 83 kuratiert ausgewählten
  Dienstleistungen (siehe Auswahlbegründung in `leistungen_quelle.py`).
- **Nicht übernommen**: die Abschnitte „Anlaufstellen in Ihrer Nähe" und „Links & Downloads" —
  reale Münchner Adressen, Kartenwidgets und muenchen.de-Downloadlinks, die sich nicht plausibel
  auf Rheinfurt übertragen lassen und für die dieses Projekt keine echten Rheinfurt-Geodaten hat.

## Gebührenbeträge (#1525)

Jeder Euro-Betrag wird mit **einem einzigen korpusweiten Faktor** skaliert
(`rheinfurt_text.FEE_SCALE_FACTOR`, derzeit 1,15). Der Rheinfurter Betrag hängt damit am
Gebührentatbestand und nicht an der Datei, die ihn zufällig nennt: Ein Dokument, das die Gebühren
einer anderen Leistung zitiert („Personalausweis oder Reisepass abholen"), nennt dieselben Beträge
wie deren eigene Leistungsbeschreibung und wie das Gebührenverzeichnis der
Verwaltungsgebührensatzung — ohne Nacharbeit von Hand.

Das gilt für alle drei Schreibweisen, in denen die Quelle Beträge notiert (`_scale_fees`):
`37,50 Euro`, die untere Grenze eines Rahmens (`60 bis 150 Euro` — nur die obere Zahl trägt das
Wort „Euro") und die ausgeschriebene Zahl (`sechs Euro`). Die letzten beiden wurden bis
einschließlich der ersten Fassung von #1525 übersprungen und blieben damit echte Münchner Werte;
seither ist in den Leistungsbibliotheken jeder Betrag ein skalierter Quellbetrag — prüfbar, indem
man die Beträge der erzeugten Dokumente gegen die skalierten Quellbeträge hält.

Der Faktor der Satzungen kommt aus derselben Funktion, ihre **Basisbeträge** stehen aber von Hand in
`satzungen.py`. Wer dort eine Zeile ergänzt oder ändert, nimmt den Betrag aus der Rohquelle der
zugehörigen Leistung — sonst nennt das Gebührenverzeichnis eine andere Zahl als die
Leistungsbeschreibung, ohne dass an der Skalierung etwas falsch wäre.

Verworfen wurden zwei naheliegende Alternativen: ein Faktor **je Quelldatei** (der frühere Stand;
er erzeugte genau diesen Widerspruch und brauchte eine Handkorrektur nach jedem Lauf) und ein je
Betrag **gehashter** Faktor. Der zweite ist über Dokumentgrenzen hinweg widerspruchsfrei, aber
nicht ordnungserhaltend: Zwei Beträge, deren Verhältnis kleiner ist als das Verhältnis zweier
möglicher Faktoren, können in der Ausgabe die Plätze tauschen — in der gepinnten Quellauswahl acht
solcher Paare innerhalb eines Dokuments. Ein einzelner Faktor kann das nicht.

## Bekannte Eigenheiten der Quelldaten

- Einzelne LHM-Rohdateien enthalten selbst kleine Tippfehler/fehlende Leerzeichen (z. B.
  „eineentsprechende", „diese16" in `Personalausweis.txt`) — unverändert aus der Quelle
  übernommen, nicht durch die Transformation verursacht.
- Manche Abschnitte wiederholen sich innerhalb einer Quelldatei (z. B. „Hinweise zur Abholung"
  taucht sowohl unter „Voraussetzungen" als auch unter „Dauer & Kosten" auf) — ebenfalls
  unverändert aus der Quelle übernommen.

## Was nicht in diesem Korpus vorkommt

- Zur Fischereierlaubnis findet sich in keiner der sieben Bibliotheken irgendein Dokument — bewusst
  so belassen, damit die Drehbuchfrage „Wie beantrage ich in Rheinfurt eine Fischereierlaubnis?"
  aus `docs/features/demo-instance.md` unbeantwortbar bleibt.
- Keine echte Münchner Straße, kein echter Stadtbezirk, keine echte Postleitzahl und keine echte
  Bankverbindung überlebt die Umschreibung — geprüft durch den Abschluss-Assert in `validation.py`
  (PR #717 Review). Vollständige Zuordnungstabelle: `rheinfurt_text.py`,
  `_STREET_AND_DISTRICT_REPLACEMENTS`.
- Externe Links auf reale Behördendomains (z. B. ein `bzst.de`-Deeplink in der Quelle) werden
  entfernt; der **Name** der Behörde bleibt, siehe „Entscheidung zu realen Bundesbehörden" in
  `demo/corpus/SOURCE.md`.
- Veraltete Corona-Passagen aus der Quelle ("Antrag nur wegen der Corona-Pandemie möglich") werden
  entfernt, nicht nur umformuliert — die Zugangsbeschränkung selbst gilt im 2026er-Korpus nicht
  mehr, nicht nur ihr Anlass.
- Sätze, die ausschließlich auf einen entfernten Link verweisen ("Diese finden Sie hier.", "Das
  Formular können Sie hier herunterladen.") werden auf einen Verweis auf das Bürgerbüro Rheinfurt
  umformuliert, damit kein Satz mehr ins Leere zeigt.
