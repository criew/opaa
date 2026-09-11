# Quellen, Lizenzen und Hinweis auf synthetische Inhalte

**Alle Inhalte in diesem Korpus sind synthetisch — mit genau einer benannten Ausnahme.** Rheinfurt
ist eine erfundene Stadt; jede Behörde, Adresse, Person, Telefonnummer, E-Mail-Adresse,
Bankverbindung, jedes Aktenzeichen und jeder Euro-Betrag in diesem Verzeichnis ist frei erfunden
oder aus realen Quellen deterministisch umgeschrieben (siehe unten). Übereinstimmungen mit realen
Personen oder Behörden sind nicht beabsichtigt. Die Ausnahme ist die Outlook-Nachricht der
Bibliothek „Formattest auf S3": Sie stammt unverändert aus einem fremden Testkorpus, weil sich das
`.msg`-Format nicht erzeugen lässt — Herkunft und Lizenz im Abschnitt zu dieser Bibliothek unten.

Dieser Abschnitt sowie die Datei- und Dokumentzahlen unten werden **vom Generator selbst
geschrieben** ([`generate_corpus.py::render_source_md`](../generator/generate_corpus.py)) — sie
können nicht veralten, weil sie bei jedem Lauf aus der tatsächlich erzeugten Dateiliste neu
berechnet werden.

## Rohmaterial: LHM-Dienstleistungen-Corpus

| | |
|---|---|
| **Datensatz** | [`it-at-m/LHM-Dienstleistungen-Corpus`](https://huggingface.co/datasets/it-at-m/LHM-Dienstleistungen-Corpus) auf HuggingFace (Landeshauptstadt München) |
| **Lizenz** | MIT — vollständiger Lizenztext: [`THIRD-PARTY-LICENSES/LHM-Dienstleistungen-Corpus-MIT.txt`](THIRD-PARTY-LICENSES/LHM-Dienstleistungen-Corpus-MIT.txt) |
| **Abgerufener Commit** | `3def28953f6d8d65bde7b6b3956fe36c9791a4de` |
| **Abrufdatum** | 2026-08-21 |
| **Verwendete Dateien** | 83 von ~740 Leistungsbeschreibungen (kuratierte Auswahl, siehe `generator/leistungen_quelle.py`) |
| **Verwendung** | Rohtext für die Bibliotheken „Leistungen Meldewesen & Ausweise" und „Leistungen Kfz-Zulassung"; deterministisch auf Rheinfurt umgeschrieben (siehe `generator/rheinfurt_text.py`) |

Die verwendeten Leistungsbeschreibungen der Landeshauptstadt München wurden automatisiert
umgeschrieben: Ortsnamen, Behördenbezeichnungen (`Landeshauptstadt München` → `Stadt Rheinfurt`,
`Kreisverwaltungsreferat (KVR)` → `Bürgerbüro Rheinfurt`), Straßennamen und Stadtbezirke (z. B.
`Ruppertstraße` → `Rheinauer Straße`, `Pasing` → `Rheinau`; vollständige Zuordnung in
`rheinfurt_text.py`), Postleitzahlen (auf die erkennbar fiktive `00000`), Bankverbindungen
(auf eine fiktive, prüfziffernkonforme IBAN `DE58 8888 8888 8888 8888 88` und BIC `SPRHDEXX`),
E-Mail-Domains (`muenchen.de` → `stadt-rheinfurt.example`), Telefonnummern (`089/…` → deterministisch
abgeleitete `01234/44-…`) sowie Gebührenbeträge (deterministisch pro
Dokument skaliert) wurden ersetzt. Externe Links (z. B. ein echter `bzst.de`-Deeplink), veraltete
Corona-Passagen und ins Leere verweisende Formulierungen aus der entfernten Link-Sektion
("... finden Sie hier.") wurden entfernt bzw. umformuliert. Die münchenspezifischen Abschnitte
„Anlaufstellen in Ihrer Nähe" und „Links & Downloads" (reale Adressen, Kartenwidgets,
muenchen.de-Downloadlinks) wurden vollständig entfernt statt umgeschrieben. Jedes generierte
Dokument trägt zusätzlich ein Aktenzeichen- und Formularnummer-Muster sowie einen Hinweis auf die
synthetische Herkunft.

Ein abschließender Validierungslauf (`generator/validation.py`) prüft die erzeugten Inhalte aller
sieben Bibliotheken gegen eine Liste von Verbotsmustern (reale Ortsnamen, Straßen außerhalb einer
Whitelist, reale Postleitzahlen, reale Bankverbindungen) und bricht den Generator-Lauf mit Fehler
ab, falls eines davon gefunden wird.

Reproduktion und SHA-256-Pins der verwendeten Rohdateien: [`generator/leistungen_quelle.py`](../generator/leistungen_quelle.py).

### Entscheidung zu realen Bundesbehörden (Koordinator, PR #717 Review)

Namentliche Nennungen echter Bundesbehörden — z. B. Kraftfahrt-Bundesamt, Bundesdruckerei,
Bundesamt für Justiz, Bundeszentralamt für Steuern, Bundesamt für das Personalmanagement der
Bundeswehr — **bleiben im Korpus erhalten**. Eine fiktive Kommune arbeitet fachlich korrekt mit
real existierenden Bundesbehörden zusammen; das durch eine erfundene Behörde zu ersetzen wäre
sachlich falsch. Entfernt werden ausschließlich **URLs, Postadressen und Kontodaten** dieser
Behörden (siehe `rheinfurt_text.py::strip_external_links`).

## Stilvorlagen (keine Textübernahme)

| Quelle | Lizenz | Verwendung |
|---|---|---|
| [FIM-Portal / LeiKa](https://fimportal.de/) | ungeklärt | Nur Katalog- und Stilreferenz zur Auswahl einer für eine Mittelstadt plausiblen Leistungsauswahl. Keine Textübernahme |
| [Pressemeldungen Stadt Köln](https://offenedaten-koeln.de/dataset/pressemeldungen) | DL-DE-BY-2.0 | Nur Stilvorlage für Ton und Meldungstypen (Sperrung, Öffnungszeiten, Veranstaltung, Jubiläum) der Bibliothek „Pressemitteilungen Stadt Rheinfurt". Kein Text übernommen, daher ohne Namensnennungspflicht nach DL-DE-BY-2.0 — hier dennoch dokumentiert |
| [RSS-Feed Stadt Düsseldorf](https://www.duesseldorf.de/rss-feed) | keine offene Lizenz | Nur Formatvorlage für die RSS-2.0-Feedstruktur. Kein Text übernommen |
| Kommunale Satzungen (Gebühren-, Straßenreinigungssatzung beliebiger deutscher Städte) | gemeinfrei (§ 5 Abs. 1 UrhG) | Nur Strukturvorlage (§§-Gliederung, Gebührenverzeichnis als Anlage) für die 19 synthetischen Satzungen Rheinfurts. Kein Text übernommen |

Details und Begründung der Quellenauswahl: [`docs/features/demo-instance.md`](../../docs/features/demo-instance.md),
Abschnitt „Quellen und Lizenzen" (Recherche Issue #709).

## Bibliothek „Formattest auf S3" (#1519)

Die siebte Bibliothek (`formate/`) ist keine Fachablage, sondern eine technische Schaubibliothek:
je ein Dokument pro Dateiendung, die OPAA zulässt. Ihr Inhalt ist bis auf die unten genannte
Outlook-Nachricht synthetisch und im Rheinfurt-Kontext verfasst (Dokumentenformate, Posteingang,
Langzeitarchivierung); jedes erzeugte Dokument nennt im Text sein eigenes Format, damit im Chat
erkennbar bleibt, aus welcher Datei eine Antwort stammt.

- `formate/13_rahmenvertrag-scandienstleister.doc` — committet, nicht bei jedem Lauf erzeugt: für diese Endung schreibt keine der in `generator/requirements.txt` gepinnten Bibliotheken. Herkunft: einmalig aus dem in `generator/formate.py` deklarierten Rheinfurt-Text erzeugt (`generator/make_doc_fixture.py`, LibreOffice-Export nach "MS Word 97"), danach committet. Verfahren: [`generator/README.md`](../generator/README.md), Abschnitt "Formate ohne Writer".
- `formate/14_poi-beispielnachricht-outlook.msg` — committet, nicht bei jedem Lauf erzeugt: für diese Endung schreibt keine der in `generator/requirements.txt` gepinnten Bibliotheken. Herkunft: übernommen aus dem Testkorpus des Apache-POI-Projekts (`test-data/hsmf/simple_test_msg.msg`, Apache License 2.0, Volltext in [`THIRD-PARTY-LICENSES/Apache-POI-testdata-Apache-2.0.txt`](THIRD-PARTY-LICENSES/Apache-POI-testdata-Apache-2.0.txt)). **Als einziges Dokument dieses Korpus englisch und ohne Rheinfurt-Bezug** — eine Folge seiner Herkunft, kein Versehen. Verfahren: [`generator/README.md`](../generator/README.md), Abschnitt "Formate ohne Writer".

Jede Endung der Formatübersicht des Handbuchs ist mit genau einem Dokument vertreten.

## Wie diese Dateien entstanden sind

Erzeugt durch [`demo/generator/generate_corpus.py`](../generator/generate_corpus.py); siehe
[`demo/generator/README.md`](../generator/README.md) für den vollständigen Reproduktionslauf,
die Werkzeugwahl für PDF/DOCX/PPTX und die Determinismus-Garantien.

## Integritätsprüfung

```bash
cd demo/corpus
sha256sum -c MANIFEST.sha256
```

## Umfang

182 Dokumente über sieben Bibliotheken (Zielkorridor 150–300 laut Issue #711 für die
sechs fachlichen Bibliotheken; „Formattest auf S3" ist eine technische Schaubibliothek mit genau
einem Dokument je unterstützter Endung, #1519):

| Bibliothek | Verzeichnis | Anzahl | Formate |
|---|---|---|---|
| Leistungen Meldewesen & Ausweise | `leistungen-meldewesen-ausweise/` | 46 | `.md` |
| Leistungen Kfz-Zulassung | `leistungen-kfz-zulassung/` | 37 | `.md`, `.txt` |
| Satzungen & Gebührenordnungen | `satzungen-gebuehrenordnungen/` | 19 | `.pdf` |
| Pressemitteilungen Stadt Rheinfurt | `pressemitteilungen/` | 28 | RSS-XML, HTML |
| Interne Dienstanweisungen Meldewesen | `interne-dienstanweisungen-meldewesen/` | 26 | `.docx`, `.pdf`, `.pptx` |
| Ratsinformationen Stadt Rheinfurt | `ratsinformationen/` | 12 | `.md`, `.txt` (ein Präfix je Jahrgang) |
| Formattest auf S3 | `formate/` | 14 | je ein Dokument pro unterstützter Endung |

Gesamtgröße rund 1,2 MB.
