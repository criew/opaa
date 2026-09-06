# Handausgewertete Stichprobe der Extraktionsgüte (100 Dokumente)

**Datum:** 05.09.2026 · **Rolle:** QA Engineer · **Issue:** [#1073](https://github.com/criew/opaa/issues/1073) ·
**Epic:** [#1065](https://github.com/criew/opaa/issues/1065)

Diese Auswertung löst den dritten Punkt aus „Messung und Abnahme" der
[Metadatenschema-Spezifikation](../../docs/features/metadata-schema.md#messung-und-abnahme) ein:
100 Dokumente, von Hand ausgewertet, je Feld erfasster Wert gegen den am Dokument abgelesenen Wert,
mit getrennten Fehlerarten „falscher Wert" und „fehlender Wert trotz vorhandener Angabe".

## 1. Messstand

| | |
|---|---|
| Instanz | Demo (`opaa.ewerlin.com`), Stand `471bad03` |
| Extraktionsversion | **4** (alle 175 Dokumente, kein Dokument übersprungen) |
| Modell-Extraktion | in allen sechs Bibliotheken eingeschaltet, Schlagworte aus |
| Modell | `claude-haiku-4-5` (zentrale Chat-Rolle) |
| Konfidenzschwelle | 0,80 (freigegeben am 05.09.2026, ADR-0012-Vorabfestlegung) |
| Grundgesamtheit | 175 Dokumente in sechs Bibliotheken |

Zählwerk über den gesamten Bestand (`metadata_model_extraction_stats`):
136 Modellaufrufe, 87 Werte übernommen, 12 wegen der Schwelle verworfen, 0 wegen Vokabularverstoß,
0 Fehler; 37 Aufrufe lieferten gar keinen Wert (Modell hat sich enthalten).

> **Hinweis zur Datengrundlage.** Die Exporte unter `/srv/opaa/metadata-sample/` auf dem Server waren
> zum Zeitpunkt dieser Auswertung veraltet — sie entstanden während des laufenden Bestandslaufs und
> zeigten für drei Bibliotheken noch keine Modellwerte. Die Auswertung nutzt deshalb frisch gezogene
> Exporte (`GET /api/v1/libraries/{id}/metadata/sample?size=100`, Skript
> `/srv/opaa/metadata-sample-export.sh`), abgeglichen gegen `document_metadata_values`. Am Bestand
> wurde nichts geändert und kein Schalter umgestellt.

## 2. Stichprobenauswahl

Geschichtet über die sechs Bibliotheken, proportional zum Bestand (Hare-Niemeyer; bei gleichem Rest
erhält die größere Bibliothek den Sitz), innerhalb einer Bibliothek deterministisch: Dokumente
sortiert nach `(Dateiname, Dokument-ID)`, ausgewählt sind die Positionen `floor(i·N/n)` für
`i = 0 … n-1`. Die Auswahl ist ohne Zufallszahl reproduzierbar.

| Bibliothek | Bestand N | Stichprobe n | Anteil |
|---|---|---|---|
| Interne Dienstanweisungen Meldewesen | 26 | 15 | 58 % |
| Pressemitteilungen Stadt Rheinfurt | 27 | 16 | 59 % |
| Satzungen & Gebührenordnungen | 19 | 11 | 58 % |
| Leistungen Meldewesen & Ausweise | 46 | 26 | 57 % |
| Leistungen Kfz-Zulassung | 37 | 21 | 57 % |
| Testdaten | 20 | 11 | 55 % |
| **Summe** | **175** | **100** | **57 %** |

Ablesegrundlage je Dokument: die Originaldatei unter `/srv/opaa/demo/corpus/` bzw.
`/srv/opaa/demo/testdaten/`, für Uploads und RSS-Einträge der Chunk-Text aus `vector_store`
(Chunk 0 und 1 = Dokumentkopf), zusätzlich die Dateieigenschaften (`docProps/core.xml` bzw.
PDF-`CreationDate`), wo der gespeicherte Wert offenkundig von dort stammt.

## 3. Bewertungsmaßstab

Je Feld eine von vier Einstufungen:

- **richtig** — der gespeicherte Wert entspricht dem am Dokument abgelesenen.
- **falscher Wert** — es steht ein Wert da, der dem Dokument widerspricht. Der stille Schaden.
- **fehlend trotz vorhandener Angabe** — das Feld ist leer, obwohl die Angabe im Dokument steht und
  im Vokabular abbildbar ist. Eine Lücke der Extraktionsregeln.
- **zu Recht leer** — das Dokument macht die Angabe nicht (bzw. kein Vokabularwert trifft zu).

Strenge Auslegung, wie beauftragt: Eine Leistungsbeschreibung „Fahrzeug anmelden", die in ihrem Kopf
auf ein Formular verweist (`**Formular:** RF-KFZ-001`), ist **kein** Formular. Ein FAQ ist keine
Dienstanweisung. Ein gespeicherter Titel, der aus dem Dateinamen abgeleitet wurde, obwohl das
Dokument eine eigene Überschrift trägt, ist ein falscher Wert — er erscheint so in der Beleg-Anzeige.
Ein Dateiname-Fallback bei einem Dokument **ohne** Überschrift (5 Fälle: `.ods`, `.odt`, zwei `.txt`
ohne Kopfzeile, `.csv`) zählt als richtig.

## 4. Ergebnis je Feld

### 4.1 Titel

| Bibliothek | n | richtig | falsch | fehlend | zu Recht leer | Fehlerquote |
|---|---|---|---|---|---|---|
| Dienstanweisungen | 15 | 15 | 0 | 0 | 0 | 0 % |
| Pressemitteilungen | 16 | 16 | 0 | 0 | 0 | 0 % |
| Satzungen | 11 | 11 | 0 | 0 | 0 | 0 % |
| Leistungen Meldewesen | 26 | 26 | 0 | 0 | 0 | 0 % |
| Leistungen Kfz | 21 | 9 | **12** | 0 | 0 | **57 %** |
| Testdaten | 11 | 9 | **2** | 0 | 0 | 18 % |
| **Gesamt** | **100** | **86** | **14** | **0** | **0** | **14 %** |

Alle Titel sind `DETERMINISTIC`; das Modell befüllt dieses Feld nicht. Der Füllstand von 100 % aus
dem dritten Füllstandsnachweis bleibt bestätigt — die Güte dahinter ist es nicht:

- **12 × `.txt` in „Leistungen Kfz-Zulassung".** Die Datei trägt eine Setext-Überschrift
  (`Aus dem Ausland eingeführtes Fahrzeug anmelden` mit `====`-Unterstreichung); gespeichert ist der
  aus dem Dateinamen abgeleitete Wert `aus dem ausland eingefuehrtes fahrzeug anmelden` — ohne
  Umlaute, ohne Großschreibung. Die gleichnamigen `.md`-Dateien derselben Bibliothek sind korrekt;
  die Lücke betrifft nur die Textdatei-Strecke.
- **`beispiel-praesentation.odp`** — Folientitel „OPAA Testpraesentation", gespeichert
  „beispiel praesentation" (Dateiname).
- **`smbprn.00009008.KdcPjl.pdf`** — gespeichert „Microsoft Office Outlook - Memo Style", der
  Titel-Eigenschaft des Druckertreibers. Der Betreff des gedruckten Dokuments lautet
  „Test Attachment".

### 4.2 Dokumentart

| Bibliothek | n | richtig | falsch | fehlend | zu Recht leer | Fehlerquote |
|---|---|---|---|---|---|---|
| Dienstanweisungen | 15 | 12 | **1** | 0 | 2 | 7 % |
| Pressemitteilungen | 16 | 0 | 0 | 0 | 16 | 0 % |
| Satzungen | 11 | 11 | 0 | 0 | 0 | 0 % |
| Leistungen Meldewesen | 26 | 0 | **24** | 0 | 2 | **92 %** |
| Leistungen Kfz | 21 | 0 | **20** | 0 | 1 | **95 %** |
| Testdaten | 11 | 3 | **1** | 0 | 7 | 9 % |
| **Gesamt** | **100** | **26** | **46** | **0** | **28** | **46 %** |

Nach Herkunft getrennt — und das ist der eigentliche Befund:

| Herkunft | n | richtig | falsch | Fehlerquote |
|---|---|---|---|---|
| `DETERMINISTIC` | 23 | 23 | 0 | **0 %** |
| `DERIVED` (Modell) | 49 | 3 | **46** | **93,9 %** |
| leer | 28 | — | — | — |

Die deterministische Extraktion nach #1263/#1289 ist auf dieser Stichprobe fehlerfrei. Die
modellgestützte Extraktion ist es in fast keinem Fall:

- **44 Leistungsbeschreibungen tragen `Formular`.** Ursache ist dieselbe Kopfzeile, die schon den
  zweiten Füllstandsnachweis unbrauchbar machte (`**Formular:** RF-MW-001`) — nur diesmal liest sie
  das Modell statt der Regel. Die Dokumente beschreiben eine Verwaltungsleistung (Voraussetzungen,
  benötigte Unterlagen, Gebühren, Rechtsgrundlagen); sie sind kein Formular.
- **`18_faq-fundsachen-empfang.pdf` trägt `Dienstanweisung`** (Konfidenz 0,85). Es ist ein internes
  FAQ mit Frage-Antwort-Paaren.
- **`foerderbescheid-anlage-zwei.txt` trägt `Bescheid-Vorlage`** (0,85). Der Inhalt ist ein einziger
  Satz: „Anlage zwei: Berechnungsgrundlage der Foerdersumme nach Richtlinie 7." Grenzfall, aber eine
  Anlage ist keine Vorlage; das Modell hat aus dem Dateinamen geraten.
- **Richtig sind drei Modellwerte**, alle in derselben Bibliothek: die drei Eskalationsregelungen
  (`11_`, `13_`, `14_`), die inhaltlich verbindliche interne Anweisungen sind und deren Titel das
  Wort „Dienstanweisung" nicht enthält. Genau der Fall, für den die Modellstufe gedacht war.

**Kein einziger Fall „fehlend trotz vorhandener Angabe"** — unter dem heutigen Vokabular. Das ist
kein gutes Zeichen, sondern die Kehrseite des Befunds: Für 63 der 100 Dokumente (47
Leistungsbeschreibungen, 16 Pressemitteilungen) gibt es schlicht keinen passenden Vokabularwert. Wo
das Modell sich enthält (alle 27 Pressemitteilungen: 27 Aufrufe, 0 Werte), verhält es sich richtig;
wo es antwortet, greift es zum nächstbesten Wert.

### 4.3 Datum/Stand

| Bibliothek | n | richtig | falsch | fehlend | zu Recht leer | Fehlerquote |
|---|---|---|---|---|---|---|
| Dienstanweisungen | 15 | 0 | **15** | 0 | 0 | **100 %** |
| Pressemitteilungen | 16 | 16 | 0 | 0 | 0 | 0 % |
| Satzungen | 11 | 0 | **11** | 0 | 0 | **100 %** |
| Leistungen Meldewesen | 26 | 0 | 0 | 0 | 26 | 0 % |
| Leistungen Kfz | 21 | 0 | 0 | 0 | 21 | 0 % |
| Testdaten | 11 | 3 | **1** | 0 | 7 | 9 % |
| **Gesamt** | **100** | **19** | **27** | **0** | **54** | **27 %** |

Alle 27 falschen Werte sind `DETERMINISTIC` und stammen aus **Dateieigenschaften mit
Generator-Voreinstellungen**:

| Quelle | gespeicherter Wert | belegt durch | Fälle |
|---|---|---|---|
| `python-docx`-Vorlage | `2013-12-23` | `docProps/core.xml`: `dcterms:created 2013-12-23T23:15:00Z`, `dc:creator python-docx` | 10 |
| `python-pptx`-Vorlage | `2013-01-27` | `dcterms:created 2013-01-27T09:14:16Z`, `cp:lastModifiedBy Steve Canny` | 3 |
| ReportLab-Vorgabe | `2000-01-01` | PDF: `/CreationDate (D:20000101000000+00'00')`, `/Producer ReportLab` | 14 |

Die Dienstanweisungen enthalten in ihrem Volltext **kein** Datum (geprüft über alle 26 Dokumente der
Bibliothek) — der richtige Zustand wäre leer bzw. „kein Wert ermittelbar". Die elf Satzungen
enthalten dagegen sehr wohl eines, und zwar unübersehbar: „Diese Satzung tritt am **1. Januar 2026**
in Kraft." Gespeichert ist `2000-01-01`. Ein Filter „Fassung 2026" verliert damit genau die
Bibliothek, für die er gebaut wurde — und die Verwechslung ist besonders tückisch, weil Tag und Monat
zufällig stimmen.

Richtig gelöst ist die Gegenrichtung: In acht Leistungsbeschreibungen stehen Jahreszahlen im
Fließtext (Stichtage wie „bis 31.12.2020 ausgestellt"); keine davon wurde als Datum/Stand
übernommen.

## 5. Kalibrierung der Konfidenzschwelle

Das Modell liefert Konfidenzen aus einer kleinen diskreten Menge — im gesamten Bestand kommen nur
`0,70`, `0,75`, `0,85`, `0,92` und `0,95` vor. Alle 49 übernommenen Werte der Stichprobe liegen bei
≥ 0,85, also über der Schwelle.

| Konfidenz | n | richtig | falsch | Anteil falsch |
|---|---|---|---|---|
| 0,85 | 42 | 0 | 42 | **100 %** |
| 0,92 | 5 | 2 | 3 | 60 % |
| 0,95 | 2 | 1 | 1 | 50 % |
| **≥ 0,80 (heutige Schwelle)** | **49** | **3** | **46** | **93,9 %** |

> **Kalibrierungsregel:** Anteil „falsch trotz Konfidenz ≥ 0,80" muss unter 5 % liegen.
> **Gemessen: 93,9 %.** Die Regel ist um mehr als das Achtzehnfache verfehlt.

Was eine höhere Schwelle brächte:

| hypothetische Schwelle | übernommen | richtig | falsch | Anteil falsch |
|---|---|---|---|---|
| ≥ 0,80 (heute) | 49 | 3 | 46 | 94 % |
| ≥ 0,90 | 7 | 3 | 4 | 57 % |
| ≥ 0,95 | 2 | 1 | 1 | 50 % |
| > 0,95 | 0 | 0 | 0 | — |

**Die Konfidenz ist nicht trennscharf.** Sie trennt nicht richtig von falsch, sondern nur „das Modell
hat geantwortet" von „das Modell hat sich enthalten". Auf jeder erreichbaren Stufe bleibt mindestens
jeder zweite übernommene Wert falsch; die einzige Schwelle, die die 5-%-Regel erfüllt, ist eine, die
alle Werte verwirft. Der Grund ist strukturell: Das Modell ist sich bei den 44
Leistungsbeschreibungen nicht unsicher — es hält `Formular` für gut belegt, weil die Kopfzeile das
Wort enthält. Eine Konfidenz misst die Überzeugtheit des Modells, nicht die Passung des Vokabulars.

### Vergleich mit den Verwerfungen

Alle zwölf Verwerfungen des Bestands (`metadata_model_rejections`) liegen bei 0,70/0,75, alle wegen
`BELOW_THRESHOLD`, keine wegen Vokabularverstoß:

| Vorschlag | Konf. | Dokument | wäre gewesen |
|---|---|---|---|
| `FORMULAR` | 0,75 | 5 Leistungsbeschreibungen | falsch (verhindert) |
| `DIENSTANWEISUNG` | 0,75 | `20_faq-standesamt-zusammenarbeit.pdf` | falsch (verhindert) |
| `DIENSTANWEISUNG` | 0,70 | `beispiel-webseite.html` | falsch (verhindert) |
| `VERMERK` | 0,75 | `nachricht.eml` | falsch (verhindert) |
| `GEBUEHRENVERZEICHNIS` | 0,75 | `beispiel-tabelle.xlsx` | falsch (verhindert) |
| `SONSTIGES` | 0,70–0,75 | `15_faq-…`, `beispiel-pdf.pdf`, `weitergeleitete-mail-…` | vertretbar (Kosten) |

Die Schwelle wirkt also in die richtige Richtung — sie hat neun falsche Werte verhindert und drei
vertretbare gekostet. Nur fängt sie 12 von 61 Vorschlägen ab, während 46 falsche Werte oberhalb der
Schwelle durchgehen. Eine Anhebung auf 0,90 würde zusätzlich 42 falsche Werte verhindern und keinen
richtigen kosten (bei 0,85 ist die Trefferquote null) — der Nutzen ist real, führt aber immer noch
nicht in die Nähe der 5-%-Regel.

## 6. Empfehlung

**1. Die Modell-Extraktion der Dokumentart ist auf diesem Bestand nicht abnahmefähig.** Sie bleibt
je Bibliothek voreingestellt **aus**; auf der Demo ist sie nach dieser Messung wieder abzuschalten.
Die Fehlerquote von 93,9 % oberhalb der Schwelle erzeugt genau den Schaden, den die Spezifikation
ausschließt: 44 Dokumente, die ein Filter „nur Formulare" fälschlich einschließt und ein Filter
„nur Leistungsbeschreibungen" nie finden könnte.

**2. Die Schwelle wird von 0,80 auf 0,90 angehoben** — das ist die von der Kalibrierungsregel
verlangte Konsequenz und ohne neue Messung zulässig (nur Anheben ist erlaubt). Sie verhindert die
42 Werte der Stufe 0,85, von denen keiner richtig war, ohne einen richtigen zu kosten. Sie ist
ausdrücklich **keine** Behebung: Auch bei 0,90 bleiben 57 % der übernommenen Werte falsch. Die
Schwelle ist ab jetzt eine Untergrenze, keine Rechtfertigung.

**3. Die Ursache ist das Vokabular, nicht die Schwelle.** Für 63 der 100 Stichprobendokumente gibt es
keinen passenden Wert. Vorschlag für die ausgelieferte Liste: **`LEISTUNGSBESCHREIBUNG`**,
**`PRESSEMITTEILUNG`** und **`FAQ_MERKBLATT`**. Damit werden aus 63 „das Modell rät" 63
entscheidbare Fälle — und aus dem heutigen Nullwert „fehlend trotz vorhandener Angabe" eine Zahl,
die überhaupt etwas misst.

**4. Der Prompt braucht Negativbeispiele.** Mindestens: „Eine Kopfzeile, die auf ein Formular
verweist (`Formular: RF-…`), macht das Dokument nicht zu einem Formular." · „Ein Frage-Antwort-Text
ist keine Dienstanweisung." · „Wenn kein Wert der Liste zutrifft, antworte nicht." Die dritte Regel
funktioniert nachweislich — bei den Pressemitteilungen hat sich das Modell 27-mal enthalten.

**5. Der billige Weg steht bereit.** Die vier problematischen Bibliotheken sind je in sich homogen;
die Sammelzuweisung aus #1068 setzt die Dokumentart in vier Vorgängen für 110 Dokumente — mit
Herkunft `MANUAL`, nachvollziehbar im Audit, ohne Modellaufruf.

**6. Zwei Befunde außerhalb des Auftrags gehören in eigene Issues:**

- **Datum/Stand aus Dateieigenschaften ist unbrauchbar** (27 % falsch, alle deterministisch). Die
  Quelle „Dokumenteigenschaften" liefert bei erzeugten Dateien die Voreinstellung der erzeugenden
  Bibliothek. Vorschlag: Diese Quelle nur mit Plausibilitätsprüfung nutzen (kein Datum vor der
  ersten Erwähnung einer Jahreszahl im Dokument; keine bekannten Generator-Vorgaben), und den
  Dokumentkopf um das Muster „tritt am … in Kraft" erweitern — das hätte allein elf Fälle gerettet.
- **Titel-Fallback greift zu früh** (14 % falsch): Setext-Überschriften in `.txt` werden nicht
  gelesen, ein PDF-Titel aus dem Druckertreiber wird ungeprüft übernommen.

**7. Für die Eintrittsbedingung von #1070** ändert sich nichts zum Guten: Der Dokumentart-Filter
darf im Suchbereich „alle Bibliotheken" weiterhin nicht angeboten werden. Der scheinbar erreichte
Füllstand von 94 % in den beiden Leistungsbibliotheken ist ausschließlich mit falschen Werten
erkauft — ein Beispiel dafür, dass ein Füllstand ohne Gütemessung nichts aussagt.

## Anhang: die 100 Dokumente

Bibliothekskürzel: DA = Dienstanweisungen, PM = Pressemitteilungen, SA = Satzungen,
LM = Leistungen Meldewesen, LK = Leistungen Kfz, TD = Testdaten.

| # | Bib. | Dokument | Titel | Dokumentart (Herkunft, Konf.) | Datum/Stand |
|---|---|---|---|---|---|
| 1 | DA | 01_identitaetszweifel-ausweisantrag.docx | richtig | Dienstanweisung (det.) — richtig | 2013-12-23 — **falsch** |
| 2 | DA | 02_gebuehrenbefreiung-beduerftigkeit.docx | richtig | Dienstanweisung (det.) — richtig | 2013-12-23 — **falsch** |
| 3 | DA | 04_vertretungsregelung-meldewesen.docx | richtig | Dienstanweisung (det.) — richtig | 2013-12-23 — **falsch** |
| 4 | DA | 06_datenschutz-melderegisterauskunft.docx | richtig | Dienstanweisung (det.) — richtig | 2013-12-23 — **falsch** |
| 5 | DA | 07_vier-augen-prinzip-ausweisausstellung.docx | richtig | Dienstanweisung (det.) — richtig | 2013-12-23 — **falsch** |
| 6 | DA | 09_terminvergabe-wartezeitmanagement.docx | richtig | Dienstanweisung (det.) — richtig | 2013-12-23 — **falsch** |
| 7 | DA | 11_eskalation-beschwerden-buergerbuero.docx | richtig | Dienstanweisung (Modell, 0.92) — richtig | 2013-12-23 — **falsch** |
| 8 | DA | 13_eskalation-kindeswohlgefaehrdung.docx | richtig | Dienstanweisung (Modell, 0.95) — richtig | 2013-12-23 — **falsch** |
| 9 | DA | 14_eskalation-medizinischer-notfall.docx | richtig | Dienstanweisung (Modell, 0.92) — richtig | 2013-12-23 — **falsch** |
| 10 | DA | 16_faq-ummeldung.pdf | richtig | leer — zu Recht leer | 2000-01-01 — **falsch** |
| 11 | DA | 18_faq-fundsachen-empfang.pdf | richtig | Dienstanweisung (Modell, 0.85) — **falsch** | 2000-01-01 — **falsch** |
| 12 | DA | 20_faq-standesamt-zusammenarbeit.pdf | richtig | leer — zu Recht leer | 2000-01-01 — **falsch** |
| 13 | DA | 21_onboarding-buergerbuero.pptx | richtig | Präsentation (det.) — richtig | 2013-01-27 — **falsch** |
| 14 | DA | 23_datenschutzgrundlagen-meldewesen.pptx | richtig | Präsentation (det.) — richtig | 2013-01-27 — **falsch** |
| 15 | DA | 25_barrierefreie-kommunikation.pptx | richtig | Präsentation (det.) — richtig | 2013-01-27 — **falsch** |
| 16 | PM | 150 Jahre Marktbrunnen: Stadt Rheinfurt feiert Jubiläum | richtig | leer — zu Recht leer | 2026-04-14 — richtig |
| 17 | PM | 20 Jahre Städtepartnerschaft mit Vézelay | richtig | leer — zu Recht leer | 2026-05-30 — richtig |
| 18 | PM | Bahnhofsvorplatz wird ab Herbst umgebaut | richtig | leer — zu Recht leer | 2026-08-10 — richtig |
| 19 | PM | Bürgerbüro am 19. Juni wegen Stadtfest geschlossen | richtig | leer — zu Recht leer | 2026-06-08 — richtig |
| 20 | PM | Bürgerbüro erweitert Online-Terminvergabe auf alle Sachgebiete | richtig | leer — zu Recht leer | 2026-01-15 — richtig |
| 21 | PM | Erster Jahrgang der 'Digitallotsen' verabschiedet | richtig | leer — zu Recht leer | 2026-11-05 — richtig |
| 22 | PM | Herbstliche Pflegearbeiten in den städtischen Grünanlagen | richtig | leer — zu Recht leer | 2026-10-05 — richtig |
| 23 | PM | Kfz-Zulassungsstelle: Angepasste Öffnungszeiten in den Sommerfer | richtig | leer — zu Recht leer | 2026-07-01 — richtig |
| 24 | PM | Kostenlose Stadtführungen im Sommer 2026 | richtig | leer — zu Recht leer | 2026-05-12 — richtig |
| 25 | PM | Neu gestalteter Spielplatz Rheinau wieder geöffnet | richtig | leer — zu Recht leer | 2026-06-25 — richtig |
| 26 | PM | Neuer Radweg entlang der Rheinpromenade eröffnet | richtig | leer — zu Recht leer | 2026-05-05 — richtig |
| 27 | PM | Rheinbrücke wegen Bauarbeiten halbseitig gesperrt | richtig | leer — zu Recht leer | 2026-03-03 — richtig |
| 28 | PM | Stadt Rheinfurt sucht Sachbearbeitung für das Meldewesen | richtig | leer — zu Recht leer | 2026-02-10 — richtig |
| 29 | PM | Stadtbibliothek testet Sonntagsöffnung ab April | richtig | leer — zu Recht leer | 2026-03-18 — richtig |
| 30 | PM | Standesamt bietet ab April Trausamstage im Monatstakt an | richtig | leer — zu Recht leer | 2026-03-10 — richtig |
| 31 | PM | Winterdienst: Stadt Rheinfurt erinnert an Räum- und Streupflicht | richtig | leer — zu Recht leer | 2026-11-15 — richtig |
| 32 | SA | 01_verwaltungsgebuehrensatzung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 33 | SA | 02_gebuehrenordnung-kfz-zulassung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 34 | SA | 04_strassenreinigungssatzung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 35 | SA | 06_hundesteuersatzung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 36 | SA | 07_zweitwohnungsteuersatzung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 37 | SA | 09_obdachlosensatzung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 38 | SA | 11_gestaltungssatzung-altstadt.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 39 | SA | 13_baumschutzverordnung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 40 | SA | 14_entwaesserungssatzung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 41 | SA | 16_personenstandsgebuehrensatzung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 42 | SA | 18_sperrzeitensatzung.pdf | richtig | Satzung/Ordnung (det.) — richtig | 2000-01-01 — **falsch** |
| 43 | LM | 001_personalausweis.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 44 | LM | 002_personalausweis-oder-reisepass-abholen.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 45 | LM | 004_vorlaeufiger-reisepass.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 46 | LM | 006_zweitpass-weitere-reisepaesse.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 47 | LM | 008_verlust-oder-diebstahl-reisepass.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 48 | LM | 009_widerruf-der-verlust-oder-diebstahlanzeige-von-personalauswe | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 49 | LM | 011_nachtraegliches-einschalten-eid-funktion-oder-aenderung-der- | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 50 | LM | 013_verlust-oder-diebstahl-der-eid-karte.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 51 | LM | 015_befreiung-von-der-ausweispflicht.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 52 | LM | 016_ausweisdokumente-fuer-die-ganze-familie.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 53 | LM | 018_fuehrungszeugnis-fuer-ehrenamtliche.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 54 | LM | 020_beglaubigung-von-dokumenten-bis-zu-5-dokumenten.md | richtig | leer — zu Recht leer | leer — zu Recht leer |
| 55 | LM | 022_beglaubigung-von-mehr-als-20-dokumenten.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 56 | LM | 024_wohnsitz-anmelden-oder-ummelden.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 57 | LM | 025_wohnsitz-abmelden.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 58 | LM | 027_melderechtliche-bescheinigung.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 59 | LM | 029_lebensbescheinigung-beantragen.md | richtig | leer — zu Recht leer | leer — zu Recht leer |
| 60 | LM | 031_uebermittlungssperre-von-daten.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 61 | LM | 032_geburtsurkunde-erwachsene-und-kinder-ab-3-monate.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 62 | LM | 034_sterbeurkunde.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 63 | LM | 036_anmeldung-einer-eheschliessung.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 64 | LM | 038_namensaenderung-nach-heirat-oder-scheidung.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 65 | LM | 039_namensaenderungen-fuer-kinder.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 66 | LM | 041_kirchenaustrittsbescheinigung.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 67 | LM | 043_waehlbarkeitsbescheinigung.md | richtig | Formular (Modell, 0.92) — **falsch** | leer — zu Recht leer |
| 68 | LM | 045_briefwahl-beantragen.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 69 | LK | 001_fabrikneues-fahrzeug-anmelden.md | richtig | Formular (Modell, 0.95) — **falsch** | leer — zu Recht leer |
| 70 | LK | 002_aus-dem-ausland-eingefuehrtes-fahrzeug-anmelden.txt | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 71 | LK | 004_fahrzeug-umschreiben-von-ausserhalb-nach-rheinfurt.txt | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 72 | LK | 006_fahrzeug-online-abmelden-ausser-betrieb-setzen.txt | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 73 | LK | 008_wunschkennzeichen.txt | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 74 | LK | 009_wechselkennzeichen.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 75 | LK | 011_kurzzeitkennzeichen-beantragen.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 76 | LK | 013_kennzeichen-fuer-elektrofahrzeuge-e-kennzeichen.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 77 | LK | 015_rotes-dauerkennzeichen-fuer-oldtimer-beantragen.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 78 | LK | 016_rotes-dauerkennzeichen-fuer-handel-werkstaetten-und-herstell | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 79 | LK | 018_verlust-oder-diebstahl-der-zulassungsbescheinigung-teil-i.tx | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 80 | LK | 020_namensaenderung-in-den-fahrzeugpapieren.txt | **falsch** | leer — zu Recht leer | leer — zu Recht leer |
| 81 | LK | 022_technische-aenderungen-in-fahrzeugpapiere-eintragen-lassen.t | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 82 | LK | 023_halter-und-datenbestaetigungen-fuer-ein-kraftfahrzeug-beantr | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 83 | LK | 025_fuehrerschein-mit-17.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 84 | LK | 027_fahrerlaubnis-neuantrag-nach-entzug-oder-verzicht.md | richtig | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 85 | LK | 029_ersatzfuehrerschein-aenderung-von-auflagen-und-beschraenkung | richtig | Formular (Modell, 0.92) — **falsch** | leer — zu Recht leer |
| 86 | LK | 030_umtausch-in-kartenfuehrerschein.txt | **falsch** | Formular (Modell, 0.92) — **falsch** | leer — zu Recht leer |
| 87 | LK | 032_umschreibung-eines-auslaendischen-fuehrerscheins.txt | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 88 | LK | 034_namensaenderung-im-fuehrerschein.txt | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 89 | LK | 036_auskunft-aus-dem-fahreignungsregister.txt | **falsch** | Formular (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 90 | TD | beispiel-kalkulation.ods | richtig | leer — zu Recht leer | leer — zu Recht leer |
| 91 | TD | beispiel-markdown.md | richtig | leer — zu Recht leer | leer — zu Recht leer |
| 92 | TD | beispiel-praesentation.odp | **falsch** | Präsentation (det.) — richtig | leer — zu Recht leer |
| 93 | TD | beispiel-textdokument.odt | richtig | leer — zu Recht leer | leer — zu Recht leer |
| 94 | TD | beispiel-word.docx | richtig | Dienstanweisung (det.) — richtig | 2013-12-23 — **falsch** |
| 95 | TD | foerderbescheid-anlage-zwei.txt | richtig | Bescheid-Vorlage (Modell, 0.85) — **falsch** | leer — zu Recht leer |
| 96 | TD | formatdokument-anhang.txt | richtig | leer — zu Recht leer | leer — zu Recht leer |
| 97 | TD | gebuehrentabelle.csv | richtig | Gebührenverzeichnis (det.) — richtig | leer — zu Recht leer |
| 98 | TD | mail-mit-txt-anhang.eml | richtig | leer — zu Recht leer | 2026-09-01 — richtig |
| 99 | TD | nachricht.eml | richtig | leer — zu Recht leer | 2026-09-01 — richtig |
| 100 | TD | smbprn.00009008.KdcPjl.pdf | **falsch** | leer — zu Recht leer | 2010-06-18 — richtig |
