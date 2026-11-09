# ADR-0065: Hybride Suche, Reranking und Diagnosezugang

## Status

Akzeptiert · zuletzt geändert 2026-11-02 · ergänzt ADR-0011, ADR-0015 und ADR-0036 Entscheidung 7

## Kontext

Bis Herbst 2026 suchte OPAA nur über Vektoren. Paragrafenverweise, Aktenzeichen, Erlassnummern und
seltene Fachbegriffe trugen im Embedding zu wenig Signal und fielen aus der Kandidatenliste. Im
Verwaltungsbestand sind sie aber die Regel. Ein Reranker fehlte, und für die Frage „warum sieht
Person X das Dokument Z nicht?“ gab es kein Werkzeug außer dem Debugger.

Gebaut wurde das in Epic #1045 (Arbeitspakete #1046–#1050, #1052, #1053). Der Zuschnitt folgte der
Retrieval-Roadmap, Phase 1. Gemessen wurde gegen die Domänen der Suchqualitätsmessung (ADR-0011,
ADR-0012). Für die Diagnose in fremdem Rechtekontext galt dieselbe Mitbestimmungslage wie für
jede Auswertung über Personen: Sie musste vor dem Bau geregelt sein, nicht danach.

## Entscheidung

### 1. Benannte Stufen mit Erklärprotokoll

Die Suche ist eine Pipeline aus benannten Stufen (Scope, Zerlegung, Vektor, Volltext, Fusion,
Reranking, MMR, Relevanzschnitt, Vervollständigung). Jede Stufe liefert neben ihrem Ergebnis ein
Erklärprotokoll; es wird immer erzeugt und nur in der Diagnose ausgewertet. Einzelne Stufen sind
über `opaa.query.pipeline.disabled-stages` abschaltbar. Ohne diese Zerlegung ist weder eine Stufe
einzeln messbar noch eine Fundstelle erklärbar.

### 2. Volltextpfad Postgres-nativ

Der lexikalische Pfad nutzt `tsvector` mit der `german`-Konfiguration und einen GIN-Index auf der
Chunk-Tabelle. Er wird in derselben Transaktion wie Text und Vektor geschrieben. Kennungen wie
Paragrafen und Aktenzeichen werden zusätzlich unzerlegt indiziert (Kennungsschutz). OPAA bleibt
damit bei einem Datenspeicher, mit einer Sicherung, einer Wiederherstellung und einem
Verschlüsselungsnachweis. Eine Änderung der Aufbereitung folgt ADR-0028.

### 3. `ts_rank` statt BM25, Eskalation nur gemessen

`ts_rank` kennt keine Längennormalisierung und keine inverse Dokumenthäufigkeit. Das wird in Kauf
genommen, weil die Fusion nur Ränge nutzt und der Volltextpfad vor allem die Fälle trägt, in denen
nur ein Chunk die Zeichenfolge enthält. Weitere Stufen haben eine gemessene Eintrittsbedingung:

| Stufe | Was | Eintrittsbedingung |
|---|---|---|
| 0 (gebaut) | `tsvector`/`german`, GIN, Kennungsschutz | — |
| 1 | BM25-Erweiterung im selben PostgreSQL (pgroonga oder pg_search) | `compound_word` oder `exact_identifier` bleibt je Gruppe mit aktivem Kennungsschutz und aktivem Reranker unter Hit Rate@5 = 0,80, obwohl der Rohvektor-Pfad den Kandidaten im Fenster hat |
| 2 | externe Suchengine neben PostgreSQL | Stufe 1 gemessen unzureichend oder Last mit PostgreSQL nachweislich nicht tragbar |

Die Schwelle 0,80 stand vor dem ersten Variantenvergleich fest. Kompositazerlegung wird ebenfalls
erst gebaut, wenn das Komposita-Segment eine Lücke zeigt.

### 4. Rechtefilter in jeder Suchabfrage

Der Filter auf die lesbaren Bibliotheken ist Teil jeder Abfrage, im Vektor- wie im Volltextpfad.
Er wird nie auf das Ergebnis angewandt. Es gibt keinen Suchpfad ohne Filter, auch nicht für die
Diagnose; sie setzt den Filter anders, sie umgeht ihn nicht. Abgesichert ist das durch Tests gegen
eine echte Datenbank.

### 5. Ungewichtete Reciprocal Rank Fusion

Vektor- und Volltexttreffer werden mit RRF ohne Gewichte zusammengeführt. RRF braucht
keine vergleichbaren Scores und keine Abstimmung. Eine Gewichtung kommt erst mit einer
Messgrundlage, die sie trägt. Den Ausschlag gab die Messung in der Verwaltungsdomäne: Die Fusion
verbesserte die Trefferquote, ohne eine Fallgruppe zu verschlechtern.

### 6. Reranking als Modellrolle, Voreinstellung aus

Reranking ist eine Modellrolle neben Chat und Embedding, mit beliebigem kompatiblem Endpunkt (ADR-0060).
Ohne Rolle läuft die hybride Suche ohne Reranking; das ist ein regulärer Betriebsfall für Häuser
ohne GPU. Die Rolle hat einen ausdrücklichen Schalter. „Aus“ ist damit eine Betreiberentscheidung
und keine fehlende Zeile. Ist der Schalter an und die Rolle nicht nutzbar, meldet das der Start und
dauerhaft die Statusseite; die Suche läuft ohne Reranking weiter. Das Erklärprotokoll
unterscheidet „abgeschaltet“ und „nicht verfügbar“. Die Voreinstellung bleibt aus, bis neben dem
gemessenen Qualitätsgewinn auch ein Latenz- und Hardwareprofil vorliegt.

### 7. Drei Konfigurationsebenen

Ein neuer Parameter braucht eine Antwort auf „Wer stellt das wann um, und woher weiß er,
worauf?“. Fehlt sie, wird er ein fester Wert.

- **Ebene 1:** gemessene interne Werte wie Kandidatenzahlen, RRF-Konstante, `mmr-lambda`, Schwellen.
  Per Property überschreibbar, aber in keiner Oberfläche.
- **Ebene 2:** Installationsentscheidungen wie Modellrollen samt Rerank-Schalter und die Sprache
  der Analysekette.
- **Ebene 3:** Festlegungen je Wissensbibliothek in der Oberfläche, etwa Metadaten.

### 8. Administrationsseite „Suche & Indexierung“

Die Seite macht sichtbar und diagnostiziert, sie stellt nichts ein. Sie zeigt den Zustand der
Suchpfade, den Füllstand des Volltextindex je Bibliothek und den Rollenzustand des Rerankings. Das
Diagnosewerkzeug führt eine frisch eingegebene Testfrage durch die Pipeline und zeigt das
Erklärprotokoll je Stufe.

### 9. Leitplanken der Diagnose in fremdem Rechtekontext

- **(a)** Die Diagnose läuft nur mit einer frischen Testfrage. Sie liest nie echte Chats.
- **(b)** Sie zeigt den Jetzt-Zustand. Ein Zugriffshistorien-Nachweis ist sie nicht.
- **(c)** „Sicht als“ ist eine eigene Befugnis. Sie wird einzeln vergeben, nicht aus der
  Administratorrolle abgeleitet. Sie hat einen Geltungsbereich (eine Anbietergruppe) und eine
  Frist von höchstens 12 Monaten je Erteilung; eine Anschlusserteilung ist zulässig. Bei Erteilung
  und bei jeder Nutzung muss die Mindestgruppengröße erfüllt sein, sonst ist sie nicht nutzbar.
  Die Auswertung des Protokolls ist davon getrennt (Rolle `AUDITOR`); keine der beiden Befugnisse
  schließt die andere ein.
- **(d)** Voreinstellung ist ein Rechteprofil. Der Kontext einer konkreten Person ist die Ausnahme
  und braucht eine Freitextbegründung.
- **(e)** Jede Bibliothek ist ab Werk für die Diagnose im Personenkontext gesperrt; das gilt
  besonders für Personalvertretung, Schwerbehindertenvertretung, Gleichstellung und
  Personalvorgänge. Die Sperre löst nur die zuständige Stelle: eine Person mit `OWNER`-Recht, das
  sie sich nicht selbst erteilt hat, oder ein Mitglied der besitzenden Gruppe. Die
  Systemverwaltung kann sich in eine besitzende Gruppe ohne Verzeichnisbindung aufnehmen und so
  zur zuständigen Stelle werden; dieser Weg bleibt bewusst offen und ist protokolliert. Läufe mit
  einem Rechteprofil berührt die Sperre nicht. Die Auskunft über die Sperre hängt am Bestand, nie
  an den Rechten der Zielperson.
- **(f)** Jede Diagnose im Personenkontext erzeugt einen Protokolleintrag (ADR-0015): ausführende
  Person, Zielperson, Zeitpunkt, Testfrage, Kennungen der Fundstellen, Rechte-Snapshot und
  Begründung. Läufe mit einem Rechteprofil werden nicht protokolliert, weil sie keine Aussage über
  einen Menschen sind.
- **(g)** Das Protokoll wird nie nach Zielperson gruppiert oder gezählt. Es gibt keine Auswertung
  „Diagnosen je Nutzer“. Einzige Leser über die eigene Selbstauskunft hinaus sind benannte Stellen.
- **(h)** Jede Person sieht in der Anwendung, wann und von wem ihr Rechtekontext eingenommen wurde.
  Das Gesamtprotokoll sehen nur Datenschutz und Personalvertretung, keine Fachvorgesetzten.
- **(i)** Einträge werden nach 12 Monaten automatisch gelöscht. Die Frist ist einstellbar, die
  Löschung nicht abschaltbar.
- **(j)** Das Ergebnis einer Diagnose im fremden Personenkontext wird nur angezeigt und nie
  gespeichert.

Den Gruppenkontext der Diagnose regelt ADR-0036, Entscheidung 7. Das Schrittprotokoll der
Werkzeugschleife nutzt die Ablage-Infrastruktur dieses Protokolls mit, übernimmt aber weder seine
Frist noch seine Lesewege ([ADR-0070](0070-werkzeugaufrufe-im-chat.md), Entscheidung 4).

## Konsequenzen

- **Einfacher:** Kennungen und seltene Begriffe werden gefunden, ohne zweite Suchinfrastruktur.
  Jede Fundstelle ist über das Erklärprotokoll nachvollziehbar, auch ohne Entwicklungsteam.
- **Schwieriger:** Eine Änderung der Volltext-Aufbereitung ist eine geplante Betriebsaufgabe mit
  Neuindizierung (ADR-0028). Jeder neue Parameter muss die Regel aus Entscheidung 7 bestehen.
- **Restrisiko:** `ts_rank` sortiert lange und ähnlich formulierte Chunks schwächer. Das fängt die
  Eintrittsbedingung aus Entscheidung 3 auf, nicht eine Vorsorge.

## Verworfene Alternativen

- Zweite Suchengine (OpenSearch, Elasticsearch, Vespa) im Lieferumfang: doppelter Rechtefilter und
  Synchronisationsrisiko auf Verdacht (3).
- SPLADE/ELSER: für Deutsch nicht belegt, Lizenzen nicht offen.
- ColBERT/Late Interaction: kein natives MaxSim in pgvector, Speicher das 10- bis 50-Fache; den
  Kennungsfall löst der Kennungsschutz billiger.
- Multi-Query-Expansion als Voreinstellung: ohne Reranker messbar schlechtere Präzision; die
  Teilfragen-Zerlegung deckt den Kern ab.
- Sprachmodell als Reranker im Antwortpfad: Cross-Encoder sind gleich gut und um Größenordnungen
  schneller.
- `pg_trgm` für Tippfehler: kein gemessener Bedarf; die Teilfragen-Zerlegung normalisiert
  Tippfehler bereits.
- Gewichtete Fusion: keine Messgrundlage für Gewichte (5).
- „Sicht als“ als Teil der Administratorrolle: Die Befugnis zeigt fremde Rechtekontexte und braucht
  eigene Vergabe, Frist und Protokoll (9c).

## Offen

- Latenz- und Hardwareprofil des Rerankings (#1051, zurückgestellt). Erst danach wird über die
  Voreinstellung der Rolle entschieden.
- Mindestschwelle für Rerank-Scores als Belegschwelle. Entscheidbar erst mit gemessenen
  Score-Verteilungen.
- Kompositazerlegung: ispell-Wörterbuch oder Decompounder. Nur, wenn das Komposita-Segment eine
  Lücke zeigt.
- Detailgrad des Erklärprotokolls im Regelbetrieb. Das ist eine Frage der gemessenen Antwortzeit.
