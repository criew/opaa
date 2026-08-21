# Korpus-Generator: Stadt Rheinfurt

Erzeugt den Demo-Korpus unter `demo/corpus/` für die fiktive Stadt Rheinfurt (siehe
[`docs/features/demo-instance.md`](../../docs/features/demo-instance.md), Epic #708, Issue #711).

Dieses Werkzeug liegt wie `eval/generator/` bewusst **außerhalb** des Gradle-Builds und der CI. Es
läuft nie automatisch, sondern nur, wenn der Demo-Korpus bewusst neu erzeugt werden soll — die
Ausgabe wird dann als reguläre Änderung committet und reviewt. Anders als `eval/generator/` gibt es
hier **keinen Ground-Truth-Zwang**: Die Demo misst nichts, sie zeigt.

## Voraussetzungen

- Python 3.11 oder neuer
- Die in `requirements.txt` gepinnten Pakete `python-docx`, `python-pptx` und `reportlab` (siehe
  unten, „Werkzeugwahl")
- Netzzugriff beim ersten Lauf, um die 83 ausgewählten Rohdateien des LHM-Dienstleistungen-Corpus
  von HuggingFace zu laden (danach genügt der lokale Cache unter `raw-source/`)

```bash
pip install -r requirements.txt
```

**Byte-Identität ist nur mit den in `requirements.txt` gepinnten Versionen zugesichert.** Ein
neueres `reportlab`/`python-docx`/`python-pptx` kann sein Standardausgabeformat ändern (z. B.
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
2. Schreibt alle fünf Bibliotheken deterministisch neu (vorhandener Inhalt der jeweiligen
   Zielverzeichnisse wird vorher gelöscht):
   - `leistungen-meldewesen-ausweise/` (`.md`) und `leistungen-kfz-zulassung/` (`.md`/`.txt`):
     46 bzw. 37 Dokumente, aus den LHM-Rohdateien München→Rheinfurt umgeschrieben
     (`rheinfurt_text.py`, `leistungen.py`).
   - `satzungen-gebuehrenordnungen/` (`.pdf`): 19 synthetische Satzungen mit
     Gebührenverzeichnis (`satzungen.py`).
   - `pressemitteilungen/` (RSS + HTML): ein `rss.xml` plus 27 Detailseiten
     (`presse.py`).
   - `interne-dienstanweisungen-meldewesen/` (`.docx`/`.pdf`/`.pptx`): 26 Dienstanweisungen,
     Eskalationsregeln, FAQ-Dokumente und Schulungsfolien (`intern.py`).
3. Validiert die erzeugten Inhalte aller fünf Bibliotheken gegen eine Liste von Verbotsmustern
   (`validation.py`): reale Ortsnamen (München/KVR/Pasing/Landeshauptstadt/Fischerei), Straßen
   außerhalb einer festen Whitelist fiktiver Rheinfurter Straßen, Postleitzahlen ungleich der
   fiktiven Rheinfurt-PLZ, sowie IBAN/BIC ungleich der fiktiven Rheinfurt-Bankverbindung. Bricht
   mit einer Liste aller Fundstellen ab, statt ein kontaminiertes Ergebnis stillschweigend zu
   schreiben.
4. Prüft, dass Verzeichnisinhalt und geschriebene Dateiliste exakt übereinstimmen (keine
   Karteileichen aus einem früheren, abweichenden Lauf).
5. Schreibt `demo/corpus/MANIFEST.sha256` mit dem SHA-256 jeder erzeugten Datei (relativ zu
   `demo/corpus/`, über alle fünf Bibliotheken hinweg) sowie `demo/corpus/SOURCE.md` selbst — die
   dort genannten Dokumentzahlen sind damit immer die tatsächlich erzeugten, nicht von Hand
   nachgeführte Werte (siehe PR #717 Review, NIT/KLEIN d).

## Verifikation

```bash
cd demo/corpus
sha256sum -c MANIFEST.sha256
```

Zwei Läufe des Generators erzeugen byte-identische Ausgaben — geprüft über `diff -rq` zweier
vollständiger Läufe mit mehreren Sekunden Abstand dazwischen (siehe PR-Beschreibung von #711).

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

## Struktur

```
demo/generator/
├── generate_corpus.py     Orchestriert alle fünf Bibliotheken, schreibt MANIFEST.sha256/SOURCE.md
├── leistungen_quelle.py    Pinning/Download der 83 ausgewählten LHM-Rohdateien
├── rheinfurt_text.py       München→Rheinfurt-Texttransformation (Orte, Straßen, Kontakte, Gebühren)
├── leistungen.py            Rendert die zwei Leistungs-Bibliotheken (.md/.txt)
├── satzungen.py              Satzungsdaten + PDF-Rendering (reportlab)
├── presse.py                 Pressemitteilungsdaten + RSS/HTML-Rendering
├── intern.py                  Interne-Dienstanweisungen-Daten + DOCX/PDF/PPTX-Rendering
├── zip_utils.py               Entfernt nicht-reproduzierbare Zip-Zeitstempel aus DOCX/PPTX
├── validation.py               Abschluss-Assert gegen reale Münchner Identifikatoren
├── requirements.txt             Gepinnte Versionen von reportlab/python-docx/python-pptx
└── raw-source/                 Gecachte LHM-Rohdaten, gitignored
```

## Was aus dem LHM-Corpus übernommen wird — und was nicht

- Übernommen werden die vollständigen Leistungsbeschreibungen (Titel, Voraussetzungen, benötigte
  Unterlagen, Gebühren, Auskunftshinweise, Fragen & Antworten) von 83 kuratiert ausgewählten
  Dienstleistungen (siehe Auswahlbegründung in `leistungen_quelle.py`).
- **Nicht übernommen**: die Abschnitte „Anlaufstellen in Ihrer Nähe" und „Links & Downloads" —
  reale Münchner Adressen, Kartenwidgets und muenchen.de-Downloadlinks, die sich nicht plausibel
  auf Rheinfurt übertragen lassen und für die dieses Projekt keine echten Rheinfurt-Geodaten hat.

## Bekannte Eigenheiten der Quelldaten

- Einzelne LHM-Rohdateien enthalten selbst kleine Tippfehler/fehlende Leerzeichen (z. B.
  „eineentsprechende", „diese16" in `Personalausweis.txt`) — unverändert aus der Quelle
  übernommen, nicht durch die Transformation verursacht.
- Manche Abschnitte wiederholen sich innerhalb einer Quelldatei (z. B. „Hinweise zur Abholung"
  taucht sowohl unter „Voraussetzungen" als auch unter „Dauer & Kosten" auf) — ebenfalls
  unverändert aus der Quelle übernommen.

## Was nicht in diesem Korpus vorkommt

- Zur Fischereierlaubnis findet sich in keiner der fünf Bibliotheken irgendein Dokument — bewusst
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
