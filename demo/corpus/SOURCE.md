# Quellen, Lizenzen und Hinweis auf synthetische Inhalte

**Alle Inhalte in diesem Korpus sind synthetisch.** Rheinfurt ist eine erfundene Stadt; jede
Behörde, Adresse, Person, Telefonnummer, E-Mail-Adresse, jedes Aktenzeichen und jeder Euro-Betrag
in diesem Verzeichnis ist frei erfunden oder aus realen Quellen deterministisch umgeschrieben (siehe
unten). Übereinstimmungen mit realen Personen oder Behörden sind nicht beabsichtigt.

## Rohmaterial: LHM-Dienstleistungen-Corpus

| | |
|---|---|
| **Datensatz** | [`it-at-m/LHM-Dienstleistungen-Corpus`](https://huggingface.co/datasets/it-at-m/LHM-Dienstleistungen-Corpus) auf HuggingFace (Landeshauptstadt München) |
| **Lizenz** | MIT |
| **Abgerufener Commit** | `3def28953f6d8d65bde7b6b3956fe36c9791a4de` |
| **Abrufdatum** | 2026-08-21 |
| **Verwendete Dateien** | 83 von ~740 Leistungsbeschreibungen (kuratierte Auswahl, siehe `generator/leistungen_quelle.py`) |
| **Verwendung** | Rohtext für die Bibliotheken „Leistungen Meldewesen & Ausweise" und „Leistungen Kfz-Zulassung"; deterministisch auf Rheinfurt umgeschrieben (siehe `generator/rheinfurt_text.py`) |

Die verwendeten Leistungsbeschreibungen der Landeshauptstadt München wurden automatisiert
umgeschrieben: Ortsnamen, Behördenbezeichnungen (`Landeshauptstadt München` → `Stadt Rheinfurt`,
`Kreisverwaltungsreferat (KVR)` → `Bürgerbüro Rheinfurt`), E-Mail-Domains (`muenchen.de` →
`stadt-rheinfurt.example`), Telefonnummern (`089/…` → deterministisch abgeleitete `02351/44-…`)
sowie Gebührenbeträge (deterministisch pro Dokument skaliert) wurden ersetzt. Die
münchenspezifischen Abschnitte „Anlaufstellen in Ihrer Nähe" und „Links & Downloads" (reale
Adressen, Kartenwidgets, muenchen.de-Downloadlinks) wurden vollständig entfernt statt umgeschrieben.
Jedes generierte Dokument trägt zusätzlich ein Aktenzeichen- und Formularnummer-Muster sowie einen
Hinweis auf die synthetische Herkunft.

Reproduktion und SHA-256-Pins der verwendeten Rohdateien: [`generator/leistungen_quelle.py`](../generator/leistungen_quelle.py).

## Stilvorlagen (keine Textübernahme)

| Quelle | Lizenz | Verwendung |
|---|---|---|
| [FIM-Portal / LeiKa](https://fimportal.de/) | ungeklärt | Nur Katalog- und Stilreferenz zur Auswahl einer für eine Mittelstadt plausiblen Leistungsauswahl. Keine Textübernahme |
| [Pressemeldungen Stadt Köln](https://offenedaten-koeln.de/dataset/pressemeldungen) | DL-DE-BY-2.0 | Nur Stilvorlage für Ton und Meldungstypen (Sperrung, Öffnungszeiten, Veranstaltung, Jubiläum) der Bibliothek „Pressemitteilungen Stadt Rheinfurt". Kein Text übernommen, daher ohne Namensnennungspflicht nach DL-DE-BY-2.0 — hier dennoch dokumentiert |
| [RSS-Feed Stadt Düsseldorf](https://www.duesseldorf.de/rss-feed) | keine offene Lizenz | Nur Formatvorlage für die RSS-2.0-Feedstruktur. Kein Text übernommen |
| Kommunale Satzungen (Gebühren-, Straßenreinigungssatzung beliebiger deutscher Städte) | gemeinfrei (§ 5 Abs. 1 UrhG) | Nur Strukturvorlage (§§-Gliederung, Gebührenverzeichnis als Anlage) für die 19 synthetischen Satzungen Rheinfurts. Kein Text übernommen |

Details und Begründung der Quellenauswahl: [`docs/features/demo-instance.md`](../../docs/features/demo-instance.md),
Abschnitt „Quellen und Lizenzen" (Recherche Issue #709).

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

156 Dokumente über fünf Bibliotheken (Zielkorridor 150–300 laut Issue #711):

| Bibliothek | Verzeichnis | Anzahl | Formate |
|---|---|---|---|
| Leistungen Meldewesen & Ausweise | `leistungen-meldewesen-ausweise/` | 46 | `.md` |
| Leistungen Kfz-Zulassung | `leistungen-kfz-zulassung/` | 37 | `.md`, `.txt` |
| Satzungen & Gebührenordnungen | `satzungen-gebuehrenordnungen/` | 19 | `.pdf` |
| Pressemitteilungen Stadt Rheinfurt | `pressemitteilungen/` | 28 (1× `rss.xml` + 27 `.html`) | RSS-XML, HTML |
| Interne Dienstanweisungen Meldewesen | `interne-dienstanweisungen-meldewesen/` | 26 | `.docx`, `.pdf`, `.pptx` |

Gesamtgröße rund 1,5 MB.
