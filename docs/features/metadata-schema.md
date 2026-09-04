# Metadatenschema für Wissensbibliotheken

> **Status: Entwurf zur Review.**
>
> Diese Spezifikation setzt die Maintainer-Entscheidungen um, die aus Abschnitt 3 des
> Diskussionspapiers
> [Dateitypen der Verwaltung und geführte Metadaten-Anreicherung](../discussions/discussion-dateitypen-und-metadaten.md)
> hervorgegangen sind — dort als Konzept mit fünf Leitplanken, hier als Festlegung. Was hier nicht
> steht, ist nicht entschieden; das Diskussionspapier bleibt die Quelle für die dort geführte Abwägung.

**Themenbereich A** der [Produktvision](../VISION.md). Diese Spezifikation ist der Umsetzungsschnitt
eines Punkts, den [Wissensschicht und Retrieval](./data-indexing-rag.md) als Zielbild bereits benennt —
[Extraktion von Dokumentmetadaten](./data-indexing-rag.md#extraktion-von-dokumentmetadaten--phase-2) —
und der bis #1066 im Code nicht existierte: Chunk-Metadaten waren rein technisch (`document_id`,
`chunk_index`, `file_name`, `library_id`, `organization_id`, `location`), und es gibt in der Suche
keinen Metadatenfilter.

Sie ist die dritte von drei zusammengehörigen Spezifikationen und die letzte in der Kette:
[Hybrides Retrieval](./hybrid-retrieval.md) baut die zwei Suchpfade, in denen ein Filter überhaupt
wirken kann; [Ingestion-Pipelines](./ingestion-pipelines.md) baut die Aufnahmestrecke, in der
Metadaten entstehen; dieses Dokument beschreibt, **welche** Metadaten das sind, **woher sie kommen**
und **was sie im Retrieval bewirken**. **Gebaut sind bisher die Arbeitspakete 1, 2 und der
Korrekturteil von 3** (Kernfelder, Herkunft, deterministische Extraktion, Beleg-Anzeige — #1066,
siehe [Umgesetzt (#1066)](#umgesetzt-1066) und
[ADR-0024](../decisions/0024-metadatenschema-kernfelder.md); deterministischer Bestandslauf — #1067,
siehe [Umgesetzt (#1067)](#umgesetzt-1067); manuelle Korrektur, Sammelzuweisung und Audit-Ereignis —
#1068, siehe [Umgesetzt (#1068)](#umgesetzt-1068); Dokumentart auch aus Dokumentkopf und
Dateiformat — #1263, siehe [Umgesetzt (#1263)](#umgesetzt-1263)); alles Weitere — Pflege-Anker, Filter,
Bibliotheksfelder, Modell-Extraktion — ist noch nicht gebaut.

---

## Motivation

Der Tech-Report hat neun Frageszenarien durchgespielt und für acht davon eine Retrieval-Strategie
benennen können. Für das neunte nicht:

> **Szenario 9 — Fassungs- und Ebenenfragen.** „Galt die Regelung auch schon 2024?", Landesrecht gegen
> Bundesrecht. Zwei deutschlandspezifische Fehlerbilder, die **keine** generische Retrieval-Strategie
> löst. Beides sind **Metadaten-Probleme**. Wer hier nur Embeddings tuned, verliert.
> (siehe [Tech-Report, Szenario 9](../discussions/discussion-retrieval-strategien.md))

Das ist der Grund für dieses Dokument. Hybrid-Suche und Reranking heben, was inhaltlich passt; sie
haben keine Handhabe gegen einen Treffer, der inhaltlich perfekt passt und trotzdem falsch ist, weil
er die Fassung von 2022 zitiert. Ein Bestand, in dem dieselbe Satzung in drei Fassungen liegt, macht
jede Suche, die den Unterschied nicht kennt, zu einem Zufallsgenerator mit drei Ausgängen — und der
falsche Ausgang sieht genauso überzeugend belegt aus wie der richtige.

Der zweite Antrieb ist die Gegenrichtung: **Metadaten sind der billigste Weg, eine Suche unbrauchbar
zu machen.** Ein geratenes Feld, das als harter Filter wirkt, entfernt ein Dokument aus der Suche,
ohne dass irgendwo eine Fehlermeldung entsteht. Dieselbe Fähigkeit, die Szenario 9 löst, ist damit
auch das Werkzeug, mit dem man sich einen stillen, monatelang unentdeckten Qualitätsverlust einbaut.
Deshalb ist der größere Teil dieser Spezifikation nicht die Beschreibung dessen, was das Schema kann,
sondern die Beschreibung dessen, was es **nicht darf**.

---

# Teil I — Begriffe

Dieser Teil richtet sich an Leserinnen und Leser mit Softwarehintergrund, aber ohne RAG-Vorwissen. Wer
den Unterschied zwischen einem typisierten Metadatenfeld und einem Schlagwort kennt, springt zu
[Teil II](#teil-ii--das-schema).

## Was Metadaten hier sind

Ein Dokument in einer Wissensbibliothek wird beim Aufnehmen in Abschnitte zerlegt (Chunks), und jeder
Abschnitt wird einzeln durchsuchbar gemacht. **Metadaten sind Angaben über das Dokument, die nicht in
seinem Fließtext stehen** oder die dort zwar stehen, aber verstreut und unzuverlässig: Was für ein
Dokument ist das (Satzung, Vermerk, Protokoll)? Von wann ist es? Welche Fassung? Für welche
Rechtsebene gilt es?

Der entscheidende Punkt ist, wie diese Angaben benutzt werden. Sie sind **keine Suchbegriffe**,
sondern **Bedingungen an das Ergebnis**. Der Unterschied lässt sich an einem Satz zeigen:

```
„Was kostet die Ummeldung nach dem Stand 2024?"
 └──────────── Suchinhalt ────────────┘└─ Bedingung ─┘
```

Der linke Teil gehört in die Suche: Er wird eingebettet, in Teilfragen zerlegt, gegen den Volltext
abgeglichen. Der rechte Teil gehört **nicht** dorthin. „2024" als Suchbegriff findet Dokumente, in
denen die Zahl 2024 häufig vorkommt — das ist etwas völlig anderes als Dokumente, die im Jahr 2024
gültig waren. Der rechte Teil muss die Ergebnismenge **einschränken**, bevor gerankt wird.

## Warum das kein Embedding-Problem ist

Die naheliegende Vermutung lautet: Ein besseres Einbettungsmodell würde den Unterschied schon
erkennen. Das ist nachweislich falsch, und zwar aus einem strukturellen Grund.

Ein Einbettungsmodell bildet **Bedeutungsähnlichkeit** ab. Nun stelle man sich dieselbe
Gebührensatzung in zwei Fassungen vor:

```
Fassung 2023:  § 7  Für die Ausstellung eines Personalausweises wird eine Gebühr von 37,00 EUR erhoben.
Fassung 2026:  § 7  Für die Ausstellung eines Personalausweises wird eine Gebühr von 41,00 EUR erhoben.
```

Diese beiden Absätze sind einander **maximal ähnlich** — sie unterscheiden sich in zwei Ziffern. Im
Vektorraum liegen sie praktisch aufeinander; jedes Einbettungsmodell, das sie auseinanderhielte, wäre
für alles andere unbrauchbar, weil es dann auch bedeutungsgleiche Umformulierungen trennen würde. Das
Modell tut hier also nicht etwas falsch, sondern genau das Richtige. Der Unterschied zwischen den
beiden Absätzen ist **keine Bedeutungsfrage**, sondern eine Gültigkeitsfrage — und Gültigkeit steht
nicht im Text, sondern am Dokument.

Dasselbe gilt für die zweite Fehlerklasse: 16 Landesmeldegesetze sind einander semantisch fast
identisch. Welches gilt, entscheidet nicht der Wortlaut, sondern die Angabe, aus welchem Bundesland
das Dokument stammt.

Daraus folgt die Aussage, die diese Spezifikation trägt:

> **Was ein Filter lösen muss, löst kein Ranking.** Ein Ranking sortiert; es kann einen falschen
> Treffer nach hinten schieben, aber nicht aus der Menge entfernen. Bei zwei nahezu identischen Texten
> hat es dafür auch kein Merkmal zur Verfügung.

## Typisiertes Feld gegen Schlagwort — und warum die Trennung existiert

Es gibt zwei grundverschiedene Arten, ein Dokument zu beschreiben, und sie werden im Folgenden strikt
getrennt gehalten.

**Ein typisiertes Feld** hat einen Namen, einen Datentyp und einen begrenzten Wertevorrat: `Fassung`
ist ein Jahr, `Rechtsebene` ist eines von `{Bund, Land, Kommune}`, `Dokumentart` ist eines von einer
festen Liste. Man kann darauf filtern, weil man weiß, welche Werte es gibt und was sie bedeuten. Wer
`Rechtsebene = Land` wählt, bekommt eine Menge, deren Umfang er versteht.

**Ein Schlagwort** ist ein freies Wort, das jemand — hier: ein Sprachmodell — dem Dokument angeheftet
hat: „Bürgerbüro", „Gebührenbefreiung", „Fristverlängerung". Es gibt keinen festen Vorrat; morgen
kommt „Gebührenerlass" dazu, und niemand weiß, ob das dasselbe meint.

Der Unterschied wird zur **Sicherheitsfrage**, sobald das Sprachmodell im Spiel ist. Ein Sprachmodell,
das ein Feld befüllt, kann sich irren, und es irrt sich auf eine besonders unangenehme Weise: Es
liefert einen plausiblen Wert, nicht eine Fehlermeldung. Was das anrichtet, hängt allein davon ab, wie
der Wert benutzt wird:

| | Der Wert ist richtig | Der Wert ist falsch geraten |
|---|---|---|
| **als harter Filter** | Die Suche wird präziser | **Das Dokument wird unsichtbar.** Es ist im Index, es passt inhaltlich, es wird nie gefunden — und niemand merkt es, weil eine leere Trefferliste genauso aussieht wie „gibt es nicht" |
| **als Suchhilfe** | Ein zusätzlicher Weg, das Dokument zu finden | Ein Wort mehr im Index. Der Treffer über den eigentlichen Inhalt bleibt unberührt |

Die untere Zeile ist verzeihend, die obere nicht. Deshalb die Regel, die sich durch dieses ganze
Dokument zieht:

> **Nur typisierte Felder mit kontrolliertem Vokabular dürfen filtern. Freie Schlagworte dürfen es
> nie.** Ein halluzinierter Wert soll höchstens einen überflüssigen Fundweg erzeugen, nie eine
> Fundstelle verschließen.

## Die drei Stellen, an denen Metadaten wirken

Metadaten sind keine Verwaltungsaufgabe, sondern eine Retrieval-Zutat. Sie wirken an genau drei
Stellen — und ein Feld, das an keiner davon wirkt, wird nicht eingeführt (siehe
[Aufnahmeregel](#die-aufnahmeregel)).

```
Frage
  ↓
Filter setzen                → 1. Harter Filter: schränkt beide Suchpfade ein, vor dem Ranking
  ↓
Vektor- und Volltextsuche    → 2. Kontextpräfix: steht im indizierten Text jedes Chunks und
  ↓                                wird dadurch mitgesucht
Antwort mit Beleg            → 3. Beleg-Anzeige: „§ 3 Verwaltungsgebührensatzung, Fassung 2026"
```

---

# Teil II — Das Schema

## Drei Arten von Metadaten

Das Schema kennt drei Arten, und sie unterscheiden sich nicht im Format, sondern in **wer sie
festlegt** und **wie viel sie dürfen**.

| | (a) Kernfelder | (b) Bibliotheksfelder | (c) Freie Schlagworte |
|---|---|---|---|
| **Geltung** | fest eingebaut, jedes Dokument jeder Bibliothek | je Bibliothek festgelegt, max. ~5 Felder | je Bibliothek an- oder abschaltbar |
| **Wer legt sie fest** | das Produkt | ein Mensch beim Einrichten der Bibliothek | niemand — das Modell vergibt sie je Dokument |
| **Typisiert** | ja | ja, mit kontrolliertem Vokabular | nein |
| **Filterbar** | **ja** | **ja** | **nie** |
| **Im Kontextpräfix** | ja | ja | ja |
| **In der Beleg-Anzeige** | ja | je Feld entscheidbar | nein |
| **Voreinstellung** | immer aktiv | leer — eine Bibliothek ohne eigene Felder ist der Normalfall | **aus** |

Die letzte Zeile ist wichtiger, als sie aussieht. **Eine Bibliothek ohne Bibliotheksfelder und ohne
Schlagworte ist vollständig funktionsfähig** und der erwartete Zustand der meisten Bibliotheken. Das
Schema ist eine Fähigkeit für Bestände, die sie brauchen, keine Pflichtübung beim Anlegen — sonst wird
es zur Anlegehürde und arbeitet gegen das Ziel „Zeit bis zum ersten Nutzen".

## (a) Kernfelder

Drei Felder, fest eingebaut, für jedes Dokument in jeder Bibliothek:

| Feld | Typ | Zweck | Herkunft |
|---|---|---|---|
| **Titel** | Text | Beleg-Anzeige, Kontextpräfix | Dokumenteigenschaften, Überschrift erster Ebene, Dateiname — in dieser Reihenfolge |
| **Dokumentart** | kontrolliertes Vokabular | Filter („nur Dienstanweisungen"), Beleg-Einordnung | Dateinamenskonvention, Dokumentkopf; sonst — sofern für die Bibliothek eingeschaltet — Sprachmodell mit Konfidenz |
| **Datum/Stand** | Datum oder Jahr | Filter („nach dem Stand 2024"), Beleg-Anzeige, Aktualitätsfragen | Datumsangaben im Kopfbereich, Dateiname, Dokumenteigenschaften |

Drei Festlegungen dazu:

**Der Wertevorrat der Dokumentart ist eine ausgelieferte Liste, keine Freitextspalte.** Sie ist auf
den Verwaltungsbestand zugeschnitten (Satzung/Ordnung, Dienstanweisung, Vermerk, Protokoll,
Bescheid-Vorlage, Formular, Gebührenverzeichnis, Präsentation, Sonstiges) und je Installation
erweiterbar. Zwei Werte für dieselbe Sache — „Dienstanweisung" und „DA" — machen jeden Filter
darüber wertlos; das ist der Zweck des Vorrats.

**„Datum/Stand" ist bewusst ein Feld und nicht drei.** Erstellungs-, Änderungs- und
Inkrafttretensdatum auseinanderzuhalten ist aktenführungsrichtig und für das Retrieval nutzlos: Die
Frage lautet „welcher Stand gilt", nicht „wann wurde die Datei zuletzt geöffnet". Wo eine Bibliothek
den Unterschied tatsächlich braucht, ist das ein Fall für ein Bibliotheksfeld (b) mit benanntem
Nutzen.

**Ein Kernfeld darf leer sein.** Ein Dokument ohne erkennbares Datum bekommt kein geschätztes; es
bekommt keines. Was daraus folgt, steht in [Teil IV](#leerwerte-schließen-nicht-aus).

## (b) Bibliotheksfelder

Zusätzlich zu den Kernfeldern kann eine Bibliothek **bis zu etwa fünf** eigene typisierte Felder
führen. Die Zahl ist eine Obergrenze mit Begründung, keine gerundete Schätzung: Jedes Feld will bei
jedem künftigen Dokument befüllt sein, und jedes Feld, das im Kontextpräfix landet, macht eine
Schemaänderung zu einem Reindex-Vorgang.

Beispiele, die die Bandbreite zeigen:

| Bibliothek | Felder | Warum genau diese |
|---|---|---|
| Satzungen & Gebührenordnungen | `§` (Gliederungspfad), `Fassung` (Jahr), `Rechtsebene` `{Bund, Land, Kommune}` | Genau die drei Angaben, an denen Szenario 9 hängt |
| Projektunterlagen | `Projekt`, `Standort`, `Phase` | Filterachsen einer Bestandssuche, die inhaltlich kaum trennbar ist |
| Gremienunterlagen | `Gremium`, `Sitzungsdatum` | Beleg-Anzeige („Hauptausschuss, 12.03.2026") und Zeitfilter |

### Die Aufnahmeregel

> **Ein Feld wird nur aufgenommen, wenn beim Anlegen benannt wird, an welcher der drei Wirkstellen es
> wirkt — Filter, Kontextpräfix oder Beleg-Anzeige.** Wer keine benennen kann, hat kein Feld gefunden,
> sondern eine Ordnungsidee.

**„Beleg-Anzeige" allein genügt nicht.** Jedes Bibliotheksfeld MUSS mindestens eine der beiden
retrievalwirksamen Stellen bedienen — Filter oder Kontextpräfix; die Beleg-Anzeige ist eine zulässige
Zugabe, nie die einzige Wirkung. Der Grund ist der, gegen den die Regel überhaupt gebaut ist: „steht
im Beleg" ist die Wirkstelle, die sich für jedes denkbare Feld behaupten lässt, und damit die
Hintertür, durch die das Ordnungsbedürfnis doch wieder fünfzehn Felder anlegt. Ein Feld, das eine
Suche nicht verbessert, sondern nur die Trefferzeile schmückt, ist kein Retrieval-Feld.

Die Regel ist nicht als Formalie gemeint, sondern als das Gegenmittel gegen die absehbare
Entwicklung: Ein Metadatenschema, das ohne diese Regel entsteht, wächst innerhalb eines Jahres auf
fünfzehn Felder, von denen zwölf niemand ausfüllt und keines etwas bewirkt. Die Wirkstelle wird
deshalb **am Feld gespeichert** und ist in der Verwaltungsansicht sichtbar; ein Feld ohne Wirkstelle
ist kein gültiger Schemazustand.

### Kontrolliertes Vokabular statt Freitext

Jedes Bibliotheksfeld hat einen Typ, und die zulässigen Typen sind absichtlich wenige:

- **Auswahl aus einer Werteliste** (der Regelfall) — die Liste gehört zum Feld und ist Teil des
  Schemas.
- **Jahr oder Datum** — für Fassungen und Stände.
- **Kennung nach Muster** (etwa Gliederungspfade, Aktenzeichen) — mit dem Muster als Teil der
  Felddefinition, damit ein Wert prüfbar ist.

Eine Werteliste lässt sich jederzeit **erweitern** — ein neuer Wert hat keine Rückwirkung auf
bestehende Dokumente. Für die Gegenrichtung gilt eine Schutzregel:

> **Ein Wert einer kontrollierten Liste MUSS entfernt oder umbenannt werden können, ohne dass ein
> Dokument mit einem ungültigen Wert zurückbleibt.** Die Änderung wird deshalb nur zusammen mit einer
> bestätigten Abbildung wirksam: Jedes Dokument, das den entfallenden Wert trägt, wird auf einen
> anderen Wert der Liste oder auf „leer" abgebildet. Die Zahl der betroffenen Dokumente steht vor der
> Bestätigung fest.

Damit kann der Zustand „Dokument trägt einen Wert, den es im Schema nicht mehr gibt" nicht entstehen.
Das ist keine Bequemlichkeit, sondern die Voraussetzung dafür, dass ein Filter über die Liste
vollständig bleibt: Ein ungültiger Wert ist von keinem Filter erreichbar und macht das Dokument
genauso unsichtbar wie ein halluzinierter — nur diesmal ohne Modell im Spiel. Die Abbildung auf
„leer" ist der ehrliche Ausweg, wenn kein passender Nachfolgewert existiert; sie stellt das Dokument
über die [Leerwert-Regel](#leerwerte-schließen-nicht-aus) wieder in jede Trefferliste und meldet es
über den [Pflege-Anker](#der-pflege-anker) zur Nachpflege an.

**Freitextfelder gibt es nicht.** Ein Freitextfeld ist ein Schlagwort mit dem Anschein von Struktur:
Man kann darauf filtern, aber niemand weiß, welche Werte existieren, und ein Tippfehler beim Anlegen
erzeugt eine stille zweite Kategorie. Wer Freitext braucht, braucht (c).

## (c) Freie Schlagworte

Optional je Bibliothek, voreingestellt aus. Ist die Option aktiv, vergibt das Sprachmodell beim
Aufnehmen eines Dokuments einige wenige Schlagworte.

Was sie dürfen:

- Sie fließen **in den Volltextindex** ein — eine Frage in Alltagssprache kann darüber ein Dokument
  finden, das in Amtssprache formuliert ist.
- Sie fließen **in den Kontextpräfix** ein und wirken damit auch im Vektorpfad.

Was sie nicht dürfen, in dieser Reihenfolge der Wichtigkeit:

- **Sie filtern nie.** Weder in der Suche, noch über eine Filter-Oberfläche, noch als „Facette".
  Diese Zusage ist der Existenzgrund der Unterscheidung aus [Teil I](#typisiertes-feld-gegen-schlagwort--und-warum-die-trennung-existiert)
  und wird als Testfall abgesichert, nicht als Absichtserklärung geführt.
- **Sie erscheinen nicht in der Beleg-Anzeige.** Ein Beleg trägt Angaben, für die das Produkt
  geradesteht; ein maschinell geratenes Wort gehört nicht dazu.
- **Sie werden nicht zu Bibliotheksfeldern befördert**, weder automatisch noch als Vorschlag aus der
  Häufigkeit. Ein häufiges Schlagwort ist ein häufiges Wort, kein bewährtes Filterkriterium.

---

# Teil III — Extraktion beim Aufnehmen

## Die Reihenfolge: deterministisch, dann Modell, dann nichts

Metadaten entstehen **einmal beim Aufnehmen des Dokuments**, nie zur Abfragezeit. Die
Extraktionsreihenfolge ist verbindlich:

1. **Deterministische Extraktion.** Regex und Parser: Paragrafenangaben, Aktenzeichenmuster,
   Datumsangaben in den üblichen deutschen Schreibweisen, Dateinamenskonventionen
   (`2026-03-12_Dienstanweisung_IT-Nutzung.pdf` trägt zwei Felder im Namen),
   Dokumenteigenschaften des Dateiformats, Struktur-Metadaten der Typ-Pipeline. Zuverlässig, billig,
   nachvollziehbar und ohne Modellaufruf.
2. **Sprachmodell als Nachrang, mit Konfidenz** — sofern für die Bibliothek eingeschaltet (siehe
   [Die modellgestützte Extraktion im Betrieb](#die-modellgestützte-extraktion-im-betrieb)). Nur für
   Felder, die Schritt 1 nicht liefern konnte, und nur für die unscharfen: Dokumentart, Thema,
   Projektzuordnung. Das Modell liefert Wert **und** Konfidenz.
3. **Leer.** Liegt die Konfidenz unter der Schwelle oder liefert das Modell einen Wert außerhalb des
   Vokabulars, bleibt das Feld leer. Es wird nicht auf den nächstähnlichen Wert abgebildet und nicht
   auf einen Vorgabewert gesetzt.

> **Ein halluzinierter Metadatenwert ist schlimmer als keiner.** Ein leeres Feld ist ein sichtbarer,
> behebbarer Zustand; ein falsch geratenes Feld ist ein unsichtbarer Dauerschaden, der sich als
> ordentlich befülltes Schema tarnt.

Die Klassifikation läuft **einmal je Dokument beim Aufnehmen**, nicht je Anfrage — der Kostenunterschied
ist der zwischen einem einmaligen Lauf über den Bestand und einem Modellaufruf in jedem Suchvorgang.

### Umgesetzt (#1066)

Arbeitspaket 1 — Datenmodell, Herkunftsangabe, deterministische Extraktion (Schritt 1 oben) und die
Beleg-Anzeige (Wirkstelle 3). Die tragenden Festlegungen stehen in
[ADR-0024](../decisions/0024-metadatenschema-kernfelder.md); hier der Umsetzungsstand und die
Abweichungen.

**Datenmodell (Migration 018).** `document_metadata_values` hält je `(document_id, field_key)` genau
eine Zeile mit `origin`, `extraction_version`, `confidence` (nur bei `DERIVED` speicherbar),
`actor_user_id`, `model_id` und Zeitstempeln — die Regeln aus
[Jeder Wert trägt seine Herkunft](#jeder-wert-trägt-seine-herkunft) sind CHECK-Constraints. Ein leeres
Feld ist die Abwesenheit der Zeile; der dritte Zustand „kein Wert ermittelbar" (#1069) ist als
`value_state = 'NOT_DETERMINABLE'` vorgesehen, nur mit `origin = 'MANUAL'` speicherbar, und wird noch
nicht geschrieben. Die Dokumentart ist ein Fremdschlüssel auf die Seed-Tabelle
`document_type_vocabulary` (Codes `SATZUNG_ORDNUNG`, `DIENSTANWEISUNG`, `VERMERK`, `PROTOKOLL`,
`BESCHEID_VORLAGE`, `FORMULAR`, `GEBUEHRENVERZEICHNIS`, `PRAESENTATION`, `SONSTIGES`, deutsche Labels,
Synonymliste in `document_type_synonyms`) — ein Wert außerhalb des Vokabulars ist nicht speicherbar. Das
Datum trägt eine Genauigkeit (`DAY`/`MONTH`/`YEAR`); „Fassung 2024" ist `(2024-01-01, YEAR)`.
`documents.metadata_extraction_version` (NULL = nie extrahiert) ist die Selektionsspalte des
Bestandslaufs (#1067).

**Extraktion.** `CoreMetadataExtractor` (Version 1) ist der einzige Interpreter; die Pipelines liefern
über `DocumentProperties` nur, was ihr Format erklärt — PDF-Info-Dictionary, OOXML-Core-Properties
(DOCX, PPTX), ODF-`meta.xml` (ODT, ODP), Markdown-Frontmatter (`titel`, `dokumentart`, `stand_datum`,
`fassung` — die Schlüssel des Verwaltungskorpus, exakt gegen das Vokabular abgeglichen), HTML-`<title>`/
`<h1>`, Mail-Betreff und -Datum — sowie jeweils die erste Überschrift erster Ebene. Reihenfolgen: Titel
aus Eigenschaft → Frontmatter → Überschrift → Dateiname (humanisiert über `ChunkContextTitle`, daher
immer befüllt); Dokumentart aus Frontmatter (eine Deklaration außerhalb des Vokabulars lässt das Feld
leer, sie fällt nicht auf den Dateinamen durch) → Dateinamens-Token (genau ein eindeutiger Code);
Datum aus Frontmatter/Mail-Datum/RSS-Veröffentlichungsdatum → Überschrift → Dateiname (ISO, deutsche
Schreibweise, `JJJJ-MM`, Monatsname + Jahr; ein nacktes Jahr nur als eigenständiges Token im Dateinamen
oder Frontmatter, in der Überschrift nur mit Anker wie „Stand 2026"; ein unmöglicher Kalendertag wird
übersprungen) → Änderungs- → Erstell-Eigenschaft. Ein Dateisystem-Änderungsdatum ist keine Quelle. Ein
manueller Wert wird nie überschrieben; ein abgeleiteter Wert weicht nur einem echten deterministischen
Ergebnis; nur eine deterministische Zeile entfällt, wenn die Extraktion nichts mehr liefert. Die
Extraktion läuft als
Systemprozess im Ingest ohne Personenrechtekontext (Beschluss 1 des Maintainers am Epic #1065) — sie
zeigt niemandem Inhalte. `DocumentPipeline#readProperties` liefert dieselben Rohquellen ohne Chunking;
`DocumentMetadataService#reextractFromFile` ist damit der Baustein je Dokument, den der Bestandslauf
wiederholt: Datei parsen (außerhalb jeder Transaktion) → Werte speichern und Chunk-Schlüssel per
JSON-Update nachziehen (eine Transaktion, Index `idx_vector_store_document_id`), ohne Neu-Einbetten.

**Chunk-Metadaten.** `storeChunks` schreibt `doc_type`, `doc_date` und `doc_date_precision` zentral auf
jeden Chunk des Dokuments (nicht über `passthroughMetadataKeys()` — die Werte hängen am Dokument); der
Titel wird nicht dupliziert. Keine `version()` einer Pipeline ist gestiegen: Die erzeugten Chunks
ändern sich nicht (Regel (d) der Ingestion-Spezifikation), die Nachrüstung läuft über
`metadata_extraction_version`.

**Beleg-Anzeige.** `SourceReference.metadata` trägt die Metadaten eines Belegs als **generische
Feld-Wert-Liste** (`SourceMetadataEntry`: `fieldKey`, deutsches `label`, maschinenlesbarer `value`,
`displayValue`, `origin`, bei Datumswerten `datePrecision`) — Maintainer-Beschluss vom 04.09.2026 am
Epic #1065: keine formatspezifischen Felder mehr am `SourceReference`. Die Kernfelder sind die ersten
Einträge (Titel, Dokumentart mit deutschem Label, Datum/Stand als „12.03.2026"/„03/2026"/„2024"),
Bibliotheksfelder (#1071) hängen sich an dieselbe Liste. Fundstellenzeile und Belegfenster rendern die
Liste ohne Feldwissen (Anzeigewerte mit „ · " verbunden, Label als barrierefreie Beschreibung) — ein
leeres Feld ist nicht in der Liste und erscheint gar nicht, ein `DERIVED`-Wert ist mit „(abgeleitet)"
gekennzeichnet; `location` bleibt die Fundstelle. Die vier Mail-Sonderfelder
`mailFrom`/`mailTo`/`mailSubject`/`mailDate` bleiben in diesem Schnitt unverändert; ihre Ablösung
über Schemafelder ist ein eigenes Sub-Issue nach #1071.

**Abweichungen und bewusst nicht Gebautes.** Das Abnahmekriterium „Die Extraktion läuft im
Rechtekontext" ist durch Beschluss 1 des Maintainers ersetzt (Systemprozess; die Rechte-Invariante gilt
für Aggregate, Stichproben und Modell-Extraktion). Das Korpus-Frontmatter `dokumentart: formularhinweis`
bleibt leer — es ist kein Vokabularwert, und die Regel verbietet die Abbildung auf `FORMULAR`.
Tabellen-Pipelines (XLSX/CSV/ODS) liefern keine `DocumentProperties`; dort greifen nur Dateiname und
Struktur. Der Tika-Fallback lieferte in diesem Schnitt ebenfalls keine — seit #1263 liefert er einen
Kopftext.

### Umgesetzt (#1263)

Die Dokumentart aus **Dokumentkopf und Dateiformat**, nachgezogen nach dem ersten Füllstandsnachweis
auf der Demo-Instanz (04.09.2026): Dort lag die Dokumentart bei 0–5 %, weil die Dateinamen des
Bestands entweder gar keinen Vokabular-Token tragen (`01_identitaetszweifel-ausweisantrag.docx`,
`21_onboarding-buergerbuero.pptx`) oder ihn als deutsches Kompositum
(`01_verwaltungsgebuehrensatzung.pdf`), das ein exakter Token-Abgleich nicht sieht. Die
Extraktionsversion steigt auf **2**; der Bestandslauf (#1067) wählt jedes Dokument der Version 1
dadurch erneut aus.

**Quellenreihenfolge der Dokumentart:** Frontmatter → Dateiname → Dokumentkopf → Dateiformat. Erste
Quelle mit genau einem eindeutigen Treffer gewinnt. Für die drei Token-Quellen gilt: mehrere
verschiedene Treffer in **einer** Quelle liefern aus dieser Quelle nichts, die nächste Quelle wird
aber noch gefragt — ein mehrdeutiger Dateiname ist keine Aussage über das Dokument, sondern nur eine
Quelle ohne Ergebnis. Die Frontmatter-Deklaration bleibt davon ausgenommen: Sie ist eine ausdrückliche
Erklärung, und ein Wert außerhalb des Vokabulars lässt das Feld leer, statt auf den Dateinamen
durchzufallen (unverändert seit #1066).

**Dokumentkopf.** `DocumentProperties` trägt neben der ersten Überschrift einen **Kopftext**: den
Anfang des Fließtextes, auf 300 Zeichen begrenzt — die Begrenzung sitzt im Record selbst, damit keine
Pipeline versehentlich ein ganzes Dokument als Kopf übergeben kann. Befüllt von DOCX, ODT, Markdown,
HTML, PDF (die erste Seite, auf beiden Wegen — `run` und `readProperties` — dieselbe) und dem
Tika-Fallback (aus dem extrahierten Text; er liefert damit erstmals überhaupt Rohquellen). Der
Abgleich läuft über Wortgrenzen, groß-/kleinschreibungs- und umlautunempfindlich, **exakt** gegen
Code, Label und Synonyme — die Endungsregel unten gilt hier ausdrücklich **nicht**: Fließtext ist voll
von Komposita, die keine Dokumentart sind („die Tagesordnung wird festgestellt", „in dieser
Größenordnung"), und ein falscher Wert mit Herkunft `DETERMINISTIC` ist genau der unsichtbare
Dauerschaden, den die Leitregel ausschließt. Ein Wort **jenseits** des Kopfbereichs kann ohnehin keine
Dokumentart auslösen; der Schnitt bei 300 Zeichen läuft bis zur letzten Wortgrenze, damit kein
Wortfragment zum Treffer wird. Ein Kopftext entsteht nur für eine echte Datei: Der Fließtext eines
RSS-Beitrags ist kein Kopfbereich, weil eine Pressemitteilung *andere* Dokumente benennt als sich
selbst.

**Kompositum-Endungsregel (Migration 020).** Je Vokabularwert sind Endungen geseedet
(`document_type_suffixes`: `satzung`, `ordnung`, `dienstanweisung`, `gebuehrenverzeichnis`,
`protokoll`, `formular`, `vermerk`) mit einer Mindestlänge des Vorderteils (3). Sie gilt **nur für
Dateinamen-Token**. Ein Token, das auf eine Endung endet und genug davor trägt, denotiert diese
Dokumentart:
`verwaltungsgebuehrensatzung` → `SATZUNG_ORDNUNG`, `verordnung` (eine Rechtsnorm) ebenso,
`anordnung` dagegen nicht. `document_type_suffix_exclusions` führt die Komposita, die eine Endung
sonst zu Unrecht beanspruchen würde — `Tagesordnung`, `Größenordnung`, `Sitzordnung`, `Rangordnung`,
`Ein-/Neu-/Um-/Unter-/Zu-/Neuzuordnung` sowie `Sperr-`, `Sicht-` und `Eingangsvermerk`; `anordnung`
und `zuordnung` stehen dort zusätzlich, damit ein späteres Nachjustieren der Mindestlänge sie nicht
stillschweigend zu Satzungen macht. Die Liste ist bewusst konkret statt schlau: Keine Längenregel
trennt `Tagesordnung` von `Verordnung` oder `Hausordnung`, die Rechtsnormen sind und zugelassen
bleiben. Deterministischer Zeichenvergleich, kein Distanzmaß — ein exakter Vokabularbegriff
schlägt jede Endung (`dienstanordnung` ist eine Dienstanweisung), und ein Token, auf das zwei
verschiedene Dokumentarten passen, liefert nichts. Die Endungsregel gilt nur für Token aus Dateiname
und Kopf; ein **deklarierter oder manuell gesetzter** Wert wird weiterhin ausschließlich exakt gegen
das Vokabular geprüft.

**Dateiformat.** PPTX/ODP → `PRAESENTATION` (die beiden Präsentationsformate, die
`SupportedDocumentFormats` überhaupt zulässt), als letzte Quelle: Jede Textquelle geht vor, und ein
Vokabular ohne diesen Code liefert nichts. Keine Ableitung für PDF/DOCX — diese Formate tragen jede
Dokumentart. Die geroutete Formatkennung hängt zentral an den Rohquellen
(`DocumentPipelineRunner` im Ingest, `DocumentMetadataService#reextractFromFile` im Bestandslauf),
nicht in den einzelnen Pipelines — sie ist ein Befund des Routings, nicht des Formats.

## Deterministischer Bestandslauf über den Altbestand

Metadaten entstehen beim Aufnehmen — aber jede Installation, die diese Fähigkeit bekommt, hat ihren
Bestand längst aufgenommen. Ein Schema, das nur für künftige Dokumente gilt, ist am Tag seiner
Auslieferung für den ganzen vorhandenen Bestand leer, und ein Filter über einen leeren Bestand ist
schlimmer als kein Filter: Er sieht aus, als wäre er in Betrieb.

> **Die Nachrüstung der Kernfelder auf dem vorhandenen Bestand ist Teil des Lieferumfangs, nicht eine
> Folgeaufgabe.** Der deterministische Bestandslauf ist bibliotheksweise startbar und unterliegt
> denselben Betriebszusagen wie jeder andere Nachlauf — verfügbare Suche, definierter Mischzustand,
> dokumentgranulare und idempotente Wiederaufnahme, ausdrückliche Freigabe (siehe
> [Nachlauf im Betrieb](#nachlauf-im-betrieb)).

Ein Punkt daran ist folgenreich für die Umsetzung: **Der Lauf MUSS die Originaldateien erneut lesen,
ein Lauf über die gespeicherten Chunk-Texte genügt nicht.** Die ergiebigsten deterministischen Quellen
der Kernfelder stehen nämlich gar nicht im Text — der Dateiname
(`2026-03-12_Dienstanweisung_IT-Nutzung.pdf` trägt Datum und Dokumentart), die
Dokumenteigenschaften des Dateiformats, die Struktur-Metadaten der Typ-Pipeline. Wer nur über die
Chunk-Texte läuft, verliert genau die Felder, deretwegen der Lauf stattfindet, und erzeugt einen
Füllstand, der die Extraktionsgüte unterschätzt. Der Bestandslauf ist damit im Aufwand einem
erneuten Einlesen näher als einer Datenbankabfrage — was ihn nicht teuer macht (es fällt kein
Einbetten und kein Modellaufruf an), aber seine Laufzeit bestimmt.

**Der Füllgrad je Feld ist in der Filter-Oberfläche sichtbar**, nicht nur in der Verwaltungsansicht:
Wer nach `Fassung` filtert, sieht am Feld, für wie viele Dokumente der Bibliothek überhaupt ein Wert
vorliegt. Ein Filter auf ein zu 12 % befülltes Feld ist eine andere Handlung als ein Filter auf ein zu
97 % befülltes — und der Unterschied ist an der Trefferliste allein nicht erkennbar, weil die
Leerwert-Regel beide Fälle gleich aussehen lässt.

### Umgesetzt (#1067)

Arbeitspaket 2 — der Bestandslauf über den Altbestand, gebaut als **zweiter Anwender derselben
Mechanik wie der Pipeline-Reindex** ([Ingestion-Pipelines, Umgesetzt (#1056)](./ingestion-pipelines.md#umgesetzt-1056)),
nicht als zweiter Nachlauf-Mechanismus.

**Ein Chargen-Endpunkt, kein Hintergrundprozess.** `POST /api/v1/admin/indexing/metadata-backfill`
(`SYSTEM_ADMIN`, auf die eigene Organisation begrenzt; eine fremde Bibliothek ist abwesend, 404)
verarbeitet **eine Charge** einer Bibliothek (`libraryId`, `batchSize` 1–100) und wird wiederholt
aufgerufen, bis `done` gemeldet wird. Der Reststand wird bei jedem Aufruf neu aus
`documents.metadata_extraction_version` abgeleitet (`NULL` oder kleiner als
`CoreMetadataExtractor.EXTRACTION_VERSION`, nur `INDEXED`-Dokumente) — es gibt keine Cursor-Tabelle
und keinen Lauf-Datensatz. Damit sind die vier Zusagen aus
[Nachlauf im Betrieb](#nachlauf-im-betrieb) so erfüllt:

- **Suche verfügbar, Mischzustand definiert:** Es wird kein Chunk gelöscht, neu zerlegt oder neu
  eingebettet. Je Dokument liest der Lauf die Originaldatei über `DocumentPipeline#readProperties`
  (Dateiname, Dokumenteigenschaften, Frontmatter, erste Überschrift — nicht die Chunk-Texte), speichert
  die Werte und zieht `doc_type`/`doc_date`/`doc_date_precision` per JSON-Update auf die vorhandenen
  Chunks nach (`DocumentMetadataService#reextractFromFile`). Der Mischzustand ist je Bibliothek
  abfragbar (siehe unten).
- **Dokumentgranular und idempotent:** Werte, Chunk-Nachzug und Extraktionsversion sind **eine
  Transaktion je Dokument**; ein Fehler bei einem Dokument kostet nur dieses (übersprungen, geloggt,
  beim nächsten Aufruf erneut versucht; eine Charge scannt höchstens das Zehnfache ihrer Größe an
  Übersprungenen, dann endet der Aufruf). Ein verarbeitetes Dokument trägt die aktuelle Version und
  fällt aus der Auswahl; ein zweiter Lauf über verarbeitete Dokumente ändert nichts und meldet `done`.
  Ein manueller Wert wird nie überschrieben (`DocumentMetadataService`). **Eine seit dem Indexlauf
  geänderte Datei wird übersprungen** (Prüfsumme der Zeile gegen die Datei, dieselbe Regel wie im
  Anhangspfad): Ihre Chunks stammen aus dem alten Inhalt, und Kernfelder eines anderen Textes auf
  diese Chunks zu schreiben, hieße, Filter und Beleg beschreiben etwas, das so nicht im Index steht;
  der nächste Konnektorlauf indiziert sie neu und extrahiert dabei.
- **Bewusste, eigene Freigabe:** Der Lauf startet nur über den Endpunkt, **bibliotheksweise** — die
  Bibliothek ist Pflichtparameter, nicht Filter. Nichts löst ihn von selbst aus, auch keine
  Erhöhung der Extraktionsversion; der auslösende Aufruf wird protokolliert
  (`INDEXING_METADATA_BACKFILL_TRIGGERED`, Objekt ist die Bibliothek, mit Extraktionsversion und
  Chargenzählern — ein Eintrag je Aufruf, nicht je Dokument).
- **Anhaltbar und wieder aufnehmbar:** Der Chargenaufruf **ist** die Wiederaufnahme; Anhalten ist das
  Ausbleiben des nächsten Aufrufs. Die Seite „Suche & Indexierung" treibt den Lauf als Schleife von
  Chargenaufrufen und bietet je Bibliothek „Kernfelder nachrüsten" / „Anhalten" / „Weiter" — „Anhalten"
  beendet die Schleife nach der laufenden Charge, „Weiter" ruft erneut auf. Es gibt keinen serverseitigen
  Zustand, der weiterliefe.

**Entfernte Quellen.** Ein RSS-Eintrag hat nie eine Datei gehabt; seine deklarierten Quellen —
Headline und Veröffentlichungsdatum des Feeds — stehen in der Zeile (`file_name`,
`last_modified_remote`) und werden **ohne Download** erneut durch die Extraktion geführt. Alles andere
Entfernte (Datei eines HTTP-Verzeichnisses, jeder entfernte Anhang samt Elternkette) kann nur der
eigene Konnektorlauf neu lesen und wird dafür vorgemerkt — derselbe Mechanismus wie beim
Pipeline-Reindex (beide Änderungsmarker geleert); der Konnektorlauf führt die Extraktion seit #1066
bei jedem Zufluss ohnehin aus. Bis dahin bleibt das Dokument als ausstehend ausgewiesen und fällt aus
der Auswahl, damit der Lauf abschließt. Anhangsdokumente lokaler Quellen werden über ihre Elternkette
neu gewonnen (ADR-0022) — dieselbe Prüfsummen- und Kettenlogik wie beim Reindex.

**Geteilte Infrastruktur.** Dateiauflösung mit Laufzeitprüfung (Allowlist, Lage unterhalb des
konfigurierten Quell- bzw. Upload-Verzeichnisses über `toRealPath`; ADR-0018, Entscheidung 6),
Anhangs-Elternkette und Vormerkung für den nächsten Konnektorlauf sind aus dem Reindex-Dienst nach
`StoredDocumentSourceAccess` herausgelöst und von beiden Läufen benutzt. Die Auswahl bleibt je Lauf
eigen (Chunk-Metadaten nach Pipeline-Version dort, `documents`-Tabelle nach Extraktionsversion hier),
ebenso die Verarbeitungseinheit (Neu-Zerlegen dort, `reextractFromFile` hier).

**Zustand je Bibliothek.** `GET /api/v1/admin/search/status` trägt je Bibliothek
`metadataBackfill`: Dokumente insgesamt (`INDEXED`), auf aktueller Extraktionsversion, ausstehend
(die Obermenge der Laufauswahl), davon **wartend auf den nächsten Konnektorlauf** (entfernte
Dokumente mit geleerten Änderungsmarkern — der Grund, warum „ausstehend" nach einem vollständigen
Lauf über 0 bleiben kann, ohne dass ein weiterer Aufruf etwas daran ändert),
zuletzt übersprungen (Zähler des letzten Aufrufs, prozesslebenslang — ADR-0021, Single-Instance),
`complete` und den **Füllgrad je Kernfeld** (Dokumente mit Wert, absolut und anteilig, deutsches
Label). Der Füllgrad wird bei jeder Abfrage aus `document_metadata_values` gebildet, nie
vorberechnet; im Verwaltungskontext ist die Organisation der Rechtekontext (Beschluss 1 des
Maintainers am Epic #1065). Die Seite „Suche & Indexierung" zeigt das in derselben Tabelle wie
Vektor- und Volltextindex ([Was die Seite anzeigt](./hybrid-retrieval.md#was-die-seite-anzeigt)).
Die Füllgrad-Anzeige in der **Filter-Oberfläche** (oben) gehört zu Arbeitspaket 4 (#1070).
Auswahl und Füllgrad-Abfrage laufen über den Index `documents (library_id, status)` (Migration 019);
die Seite ruft Chargen zu 50 Dokumenten ab und lädt den Status nach jeder Charge neu, bricht aber nach
drei Chargen ohne Fortschritt oder 1000 Chargen je Start von sich aus ab, damit kein Defekt auf einer
Seite zur Endlosschleife wird.

## Die modellgestützte Extraktion im Betrieb

Schritt 2 der Reihenfolge ist der einzige Teil des Schemas, der Geld kostet, ausfallen kann und
Dokumentinhalte an ein Modell übergibt. Er wird deshalb als eigenständig steuerbarer Schalter geführt:

- **Je Bibliothek an- und abschaltbar, voreingestellt aus.** Eine Bibliothek mit ausgeschalteter
  Modell-Extraktion ist vollständig funktionsfähig; sie führt dann genau die Werte, die Schritt 1
  liefert. Der Regelfall ist der ausgeschaltete Zustand — eingeschaltet wird, wo jemand den Nutzen für
  diesen Bestand benennen kann.
- **Sie benutzt die zentral gesteuerte Chat-Rolle** des Schichtenmodells (siehe
  [Modelle und zentrale Steuerung](./llm-integration.md)) und verlangt keine eigene Modellrolle. Wer
  diese Rolle auf ein lokal betriebenes Modell legt, betreibt die Extraktion vollständig ohne
  ausgehende Verbindung; die Fähigkeit ist damit auch in einer abgeschotteten Installation nutzbar.
- **Bei einem extern betriebenen Modell erzeugt sie einen neuen, dauerhaften Abfluss von
  Dokumentinhalten.** Das ist ausdrücklich festzuhalten, weil der Unterschied zum Chat nicht offen
  zutage liegt: Im Chat verlässt Text das Haus, wenn jemand fragt; hier verlässt ihn **jedes
  aufgenommene Dokument**, ohne dass eine Person den Vorgang auslöst. Für eine Bibliothek mit
  schutzbedürftigen Unterlagen ist das die datenschutzrechtlich relevante Entscheidung an diesem
  Schalter — und der Grund für die Voreinstellung „aus".
- **Ein Modellausfall blockiert nie die Aufnahme.** Ist das Modell nicht erreichbar, überschreitet der
  Aufruf sein Zeitlimit oder antwortet es unbrauchbar, bleibt das betroffene Feld leer und das
  Dokument wird regulär aufgenommen und durchsuchbar. Die Nachbefüllung geschieht über denselben
  Nachlauf wie jede andere nachträgliche Extraktion (siehe
  [Nachlauf im Betrieb](#nachlauf-im-betrieb)) — es gibt keine Warteschlange, in der Dokumente auf ein
  Modell warten.
- **Die Zahl der Extraktionsaufrufe wird je Bibliothek geführt** und erscheint in derselben
  Zustandsübersicht wie der übrige Indexzustand (siehe
  [Was die Seite anzeigt](./hybrid-retrieval.md#was-die-seite-anzeigt)). Ohne dieses Zählwerk ist die
  einzige Rückmeldung über die Kosten dieser Fähigkeit die Rechnung des Modellanbieters.

## Jeder Wert trägt seine Herkunft

Ein Metadatenwert ohne Herkunftsangabe ist nach drei Monaten nicht mehr bewertbar: Niemand weiß, ob
`Dokumentart = Vermerk` aus dem Dateinamen kam, aus einem Modellaufruf oder von einer Person. Deshalb
trägt **jeder befüllte Wert** verbindlich:

| Angabe | Werte |
|---|---|
| **Herkunft** | `deterministisch`, `abgeleitet` (Modell), `manuell` |
| **Konfidenz** | nur bei `abgeleitet` |
| **Akteur** | bei `manuell` die Nutzerkennung; bei `abgeleitet` Modellkennung und Extraktionsversion; bei `deterministisch` die Extraktionsversion |
| **Zeitpunkt und Verfahrensstand** | wann und mit welcher Extraktionsversion erzeugt |

Daraus folgen drei Fähigkeiten, die ohne die Angabe nicht existieren: Ein abgeleiteter Wert ist in
der Oberfläche **als abgeleitet gekennzeichnet** (die Zusage aus
[Extraktion von Dokumentmetadaten](./data-indexing-rag.md#extraktion-von-dokumentmetadaten--phase-2));
eine verbesserte Extraktion kann gezielt **nur die abgeleiteten Werte** neu erzeugen und lässt
manuelle Korrekturen unberührt; und die Frage „wie gut ist unsere Extraktion eigentlich" wird
beantwortbar, weil abgeleitete und korrigierte Werte unterscheidbar sind.

**Ein manuell gesetzter Wert wird von keiner automatischen Extraktion überschrieben.** Wer korrigiert
und beim nächsten Lauf wieder den alten Wert vorfindet, korrigiert kein zweites Mal.

### Manuelle Setzungen sind protokollpflichtig

Ein manuell gesetzter Wert ist die einzige Angabe im Schema, die kein Lauf reproduzieren kann: Der
Bestandslauf erzeugt deterministische Werte jederzeit neu, die modellgestützte Extraktion ebenfalls —
eine Korrektur von Hand entsteht genau einmal. Daraus folgt:

> **Jede manuelle Setzung, Änderung und Löschung eines Metadatenwertes MUSS ein Audit-Ereignis
> erzeugen, das Dokument, Feld, Altwert, Neuwert, Akteur und Zeitpunkt trägt.** Das gilt für die
> Einzelkorrektur wie für jedes Dokument einer Sammelzuweisung.

Diese Ereignisse gehören in die bestehende Protokollablage und erben deren Eigenschaften unverändert —
nur anfügend, mit der Eigentümertrennung nach
[ADR-0015](../decisions/0015-eigentuemertrennung-protokollablage.md); es wird kein zweiter
Protokollmechanismus dafür gebaut.

Der Zweck ist nicht Nachweisführung, sondern Wiederherstellbarkeit: **Die manuellen Metadatenwerte
einer Bibliothek sind aus dem Audit-Bestand rekonstruierbar**, auch wenn eine Wiederherstellung die
Bibliothek auf einen älteren Stand zurücksetzt. Das ist eine zugesicherte Eigenschaft des
Ereignisformats — deshalb der Altwert im Ereignis, nicht nur der neue —, und das
Wiederherstellungs-Runbook MUSS den Schritt benennen, mit dem manuelle Werte nach einem Restore
abgeglichen werden. Alles andere Wiederherstellbare (deterministische und abgeleitete Werte) entsteht
über einen erneuten Lauf und braucht diese Zusage nicht.

## Manuelle Korrektur ist Teil des ersten Schnitts

Die Möglichkeit, einen Metadatenwert eines Dokuments von Hand zu setzen oder zu korrigieren, ist
keine Ausbaustufe. Ohne sie ist ein leeres Feld ein Dauerzustand, und die Regel „lieber leer als
geraten" wird zur Verschlechterung statt zur Absicherung. Sie ist zugleich die Bedingung dafür, dass
der [Pflege-Anker](#der-pflege-anker) mehr ist als eine Zahl, auf die niemand reagieren kann.

**Wer ein Dokument bearbeiten darf, darf auch seine Metadaten korrigieren.** Die Korrektur ist eine
Arbeit am Dokument und braucht kein Verwaltungsrecht an der Bibliothek — sonst landet die Pflege
genau bei den wenigen Personen, die sie am seltensten leisten können, und der Pflege-Anker zeigt
dauerhaft eine Zahl an, auf die die zuständige Fachperson nicht reagieren darf. **Das Schema selbst
zu ändern — Felder anlegen, Wertelisten pflegen, Wirkstellen setzen — bleibt dagegen am
Verwaltungsrecht der Bibliothek.** Der Unterschied ist der zwischen einem Wert und der Regel, nach
der alle Werte entstehen.

### Sammelzuweisung auf einer Auswahl

Eine Korrektur, die dreihundertmal einzeln ausgeführt werden muss, wird nicht ausgeführt. Deshalb
gehört von Anfang an dazu: Eine Person wählt eine Menge von Dokumenten aus und setzt für diese
Auswahl **ein** Feld auf **einen** Wert („diese 300 Dokumente: Dokumentart = Satzung"). Die
gesetzten Werte tragen die Herkunft `manuell`, den auslösenden Akteur und erzeugen je Dokument ein
Audit-Ereignis wie jede Einzelkorrektur; die Auswahl erfolgt über dieselbe rechtegefilterte Sicht wie
jede andere Dokumentliste.

Das ist **kein** Widerspruch zu den ausgeschlossenen Pflege-Automatismen (siehe
[Bewusst nicht gebaut](#bewusst-nicht-gebaut)). Ausgeschlossen ist, dass das System Werte setzt, die
niemand entschieden hat. Hier entscheidet ein Mensch genau einmal statt dreihundertmal — die Zahl der
Entscheidungen sinkt, nicht ihre Verbindlichkeit.

### Umgesetzt (#1068)

Der Korrekturteil von Arbeitspaket 3 — Einzelkorrektur, Sammelzuweisung und die protokollpflichtige
Setzung; der Pflege-Anker und der dritte Zustand folgen in #1069 und hängen sich an dieselbe
Setz-Operation.

**Rechte.** Die Korrektur läuft an der Schwelle, an der auch Hochladen und Löschen eines Dokuments
liegen: `EDITOR` an der Bibliothek (`LibraryAccessService#requireRole`), dieselbe Prüfung wie
`LibraryDocumentService` — kein Verwaltungsrecht. Lesen der Metadatenansicht verlangt `VIEWER`. Eine
Bibliothek einer fremden Organisation oder ohne jedes Recht ist abwesend (404), zu wenig Recht ist 403;
ein Dokument einer anderen Bibliothek ist so abwesend wie ein nicht existierendes.

**API.** `GET /api/v1/libraries/{libraryId}/documents/{documentId}/metadata` liefert alle drei
Kernfelder — auch leere — mit Wert, Anzeigewert, Herkunft, Konfidenz, Modell, Extraktionsversion,
Akteur (Kennung und Anzeigename) und Zeitpunkt. `PUT …/metadata/{fieldKey}` setzt oder ändert einen
Wert (Textwert, Vokabularcode oder Datum mit Genauigkeit — genau eines, passend zum Feld; ein Wert
außerhalb des Vokabulars, ein leerer Titel, ein Datum ohne Genauigkeit oder ein Wert falscher Art ist
400, nichts wird auf den nächstähnlichen Wert abgebildet). `DELETE …/metadata/{fieldKey}` entfernt die
Zeile. `POST /api/v1/libraries/{libraryId}/documents/metadata/bulk` setzt ein Feld auf einen Wert für
eine Liste von Dokument-IDs (höchstens 1000). `GET /api/v1/metadata/document-types` liefert das
Vokabular als Auswahlliste — Schema, kein Aggregat, deshalb für jede angemeldete Person sichtbar. Der
Wertkörper ist so geschnitten, dass #1069 den Zustand „kein Wert ermittelbar" als weitere Alternative
derselben Anfrage ergänzt.

**Herkunft und Überschreibschutz.** Ein gesetzter Wert trägt `origin = MANUAL`, `actor_user_id` und
Zeitpunkt; Konfidenz, Modell und Extraktionsversion sind leer — auch wenn die Zeile vorher
deterministisch oder abgeleitet war (sie wird umetikettiert, nicht ersetzt). `DocumentMetadataService`
lässt eine `MANUAL`-Zeile bei jeder Reconciliation unangetastet; abgesichert Ende-zu-Ende über den
Bestandslauf (`DocumentMetadataCorrectionServiceIntegrationTest`). Ein identischer manueller Wert, der
bereits steht, ist keine Änderung: nichts wird geschrieben, kein Ereignis entsteht.

**Löschsemantik.** Löschen entfernt die Zeile unabhängig von ihrer Herkunft; das Feld ist danach
**leer**, und die nächste automatische Extraktion darf es wieder befüllen — dafür setzt die Löschung
in derselben Transaktion `documents.metadata_extraction_version` auf `NULL`, sodass der Bestandslauf
das Dokument wieder auswählt und aus der unveränderten Datei neu extrahiert (abgesichert im
Integrationstest; ein Konnektorlauf liest ein unverändertes Dokument sonst nie wieder). Eine
`MANUAL`-Zeile eines anderen Feldes bleibt dabei unberührt. Eine Löschung ist keine
Sperre — „hier gibt es dauerhaft keinen Wert" ist genau der dritte Zustand aus #1069 und wird dort als
eigener Wert gesetzt, nicht als gelöschte Zeile nachgebildet. Die Regel „ein manuell gesetzter Wert
wird nie überschrieben" gilt für gesetzte Werte; eine gelöschte Zeile ist kein gesetzter Wert.

**Chunk-Nachzug.** Jede Setzung und Löschung von Dokumentart oder Datum/Stand schreibt `doc_type`,
`doc_date` und `doc_date_precision` per JSON-Update auf die vorhandenen Chunks nach
(`VectorChunkStore#updateDocumentMetadata`), in derselben Transaktion wie die Zeile — kein
Neu-Zerlegen, kein Neu-Einbetten. Der Titel ist kein Chunk-Schlüssel (ADR-0024, Entscheidung 5) und
löst keinen Nachzug aus.

**Sammelzuweisung.** Ein Feld, ein Wert, eine Liste von Dokument-IDs aus derselben rechtegefilterten
Dokumentliste. Eine ID, die kein Dokument dieser Bibliothek ist (gelöscht, fremde Bibliothek), wird
**abgewiesen und in der Antwort benannt** (`rejectedDocumentIds`), nicht stillschweigend übersprungen;
die übrigen Dokumente werden in derselben Transaktion verarbeitet. Die Antwort trägt Zähler
(aktualisiert, unverändert) und die Korrelationsreferenz der Ereignisse. Bewusst kein
Alles-oder-nichts: Der häufige Fall einer abgewiesenen ID ist ein Dokument, das zwischen Auswahl und
Bestätigung gelöscht wurde — die Person soll die Zuweisung deswegen nicht wiederholen müssen.

**Audit.** Jede wirksame Setzung, Änderung und Löschung schreibt ein Ereignis
`DOCUMENT_METADATA_CHANGED` über den bestehenden `AuditEventRecorder` in die Protokollablage nach
ADR-0015 — kein zweiter Mechanismus. **Objekt ist die Bibliothek** (`KNOWLEDGE_LIBRARY`), das Dokument
steht mit Kennung und Dateiname im Ereignis: `before` und `after` tragen je `documentId`, `fileName`,
`fieldKey` und entweder `state = EMPTY` oder `state = SET` mit `value`, `displayValue`, `origin`,
`datePrecision`, `extractionVersion`, `confidence`, `modelId` — der Altwert vollständig, auch wenn er
deterministisch war. Akteur (pseudonymisiert) und Zeitpunkt kommen aus der Ablage. Die Bibliothek als
Objekt ist eine bewusste Entscheidung: Der Objektzugriffspfad des Protokolls
(`GET /api/v1/audit/events/by-object`, Index `organization_id, object_type, object_id, recorded_at`)
liefert damit alle Metadatenereignisse **einer Bibliothek** in Aufzeichnungsreihenfolge — genau die
Abfrage, die die Wiederherstellung braucht — und die Ablage braucht keinen neuen Objekttyp (ihre
`CHECK`-Constraint auf `object_type` liegt bei der Eigentümerrolle `opaa_audit_owner`, ADR-0015). Bei
der Sammelzuweisung entsteht **ein Ereignis je Dokument**; alle Ereignisse eines Aufrufs teilen sich
eine `correlationRef` (`metadata-bulk-<uuid>`), über den Korrelationspfad des Protokolls als ein
Vorgang lesbar. Dafür nimmt `AuditEventRecorder#recordUserAction` seit #1068 eine
Korrelationsreferenz an; die subjektbezogene Variante weist sie weiterhin zurück.

**Wiederherstellbarkeit.** Aus der Ereignisfolge einer Bibliothek lässt sich ihr manueller Stand
rekonstruieren (Test: Wiedereinspielen der `after`-Nutzlasten in Aufzeichnungsreihenfolge ergibt
genau die `MANUAL`-Zeilen). Das Wiederherstellungs-Runbook in
[Betrieb und Infrastruktur](./deployment-infrastructure.md#manuelle-metadatenwerte-nach-einem-restore-abgleichen)
benennt den Abgleichschritt.

**Oberfläche.** In der Dokumentliste der Bibliothek klappt „Metadaten von … anzeigen" je Dokument die
drei Kernfelder auf: Wert, Herkunftskennzeichnung (`automatisch ermittelt` / `abgeleitet` /
`manuell`; Akteur und Zeitpunkt bzw. Konfidenz und Modell im Tooltip) und — nur mit Bearbeitungsrecht
— Bearbeiten (Dokumentart als Auswahl aus dem Vokabular, Datum mit Genauigkeit Tag/Monat/Jahr, Titel
als Text) und Löschen. Mit Bearbeitungsrecht trägt jede Zeile ein Auswahlkästchen; „Feld setzen" auf
der Auswahl öffnet die Sammelzuweisung: ein Feld, ein Wert, Bestätigung mit Anzahl, Ergebnis mit
Zählern und abgewiesenen Dokumenten. Für Konnektorbibliotheken gilt dasselbe — der Wert hängt am
Dokument, nicht an der Datei.

Zwei Regeln, die aus dem Rechtemodell folgen und nicht verhandelbar sind (Grundsatz siehe
[Durchsetzung zur Abfragezeit](./spaces-and-assets.md#durchsetzung-zur-abfragezeit)):

- **Extraktion und Schemavorschlag laufen nur über Dokumente, die die anlegende Person lesen darf.**
  Eine Stichprobe, die den ganzen Bestand sichtet, wäre ein Leseweg an der Berechtigung vorbei —
  unauffällig, weil ihr Ergebnis nur ein Schemavorschlag ist.
- **Jedes aus dem Bestand abgeleitete Aggregat MUSS im Rechtekontext der abfragenden Person gebildet
  werden.** Die Regel gilt nicht nur für die Filter-Wertelisten, sondern für jede Zahl und jede
  Aufzählung, die aus Dokumenten entsteht: die im Bestand **vorkommenden** Feldwerte, den
  [Pflege-Anker](#der-pflege-anker) („N Dokumente ohne Wert"), den Füllgrad je Feld und die
  Statistiken der [Extraktionsgüte](#messung-und-abnahme). Eine Werteliste, die alle vorkommenden
  Projektnamen zeigt, ist eine Aufzählung von Vorhaben, deren Unterlagen die fragende Person nicht
  lesen darf; eine Zahl „412 Dokumente ohne Fassungsangabe" verrät den Umfang eines Bestands, den
  dieselbe Person mit 30 Dokumenten sieht. Kein Aggregat wird global vorberechnet und dann angezeigt.
  **Eine eng begrenzte Ausnahme** ist der `SYSTEM_ADMIN`-Verwaltungspfad des Bestandslaufs
  ([Umgesetzt (#1067)](#umgesetzt-1067)): Dort ist die Organisation der Rechtekontext — der
  Extraktionsstand und der Füllgrad je Kernfeld auf der Seite „Suche & Indexierung" zählen über alle
  Bibliotheken der eigenen Organisation, weil der Lauf ein Systemprozess ist (Beschluss 1 des
  Maintainers am Epic #1065) und diese Zahlen keine Feldwerte, nur Anzahlen zeigen. Die Ausnahme gilt
  ausschließlich für diese Verwaltungsansicht; die Füllgrad-Anzeige in der Filter-Oberfläche (#1070)
  und jede Werteliste bleiben an die Regel gebunden.

**Die konfigurierte Werteliste eines Bibliotheksfeldes ist von dieser Regel ausgenommen** — sie ist
Schemabestandteil, kein Aggregat: Ihre Werte hat ein Mensch beim Anlegen des Feldes festgelegt, sie
existieren unabhängig davon, ob ein Dokument sie trägt, und sie sind für jede Person sichtbar, die
die Bibliothek benutzen darf. Daraus folgt eine Betriebsregel, die in die Verwaltungsansicht gehört:
**Wertelisten dürfen keine schutzbedürftigen Bezeichnungen tragen.** Ein Feld `Vorgang` mit einer
Liste aus Personennamen oder Disziplinarvorgängen ist genau der Fehlgriff, den diese Ausnahme möglich
macht — wer solche Werte braucht, braucht kein Filterfeld.

Für die rechtegefilterte Filter-Werteliste ist ein **nutzerbezogener Zwischenspeicher ausdrücklich
zulässig**, damit die Filter-Oberfläche nicht bei jedem Öffnen den Bestand aggregieren muss. Er ist
an die Person **und** ihren Rechtestand gebunden und MUSS bei jeder Rechteänderung, die diese Person
betrifft, verworfen werden. Ein bibliotheks- oder organisationsweiter Zwischenspeicher bleibt
ausgeschlossen: Ein Zwischenspeicher, der Rechte nicht abbildet, ist an dieser Stelle dasselbe Leck
wie bei der
[Antwort-Zwischenspeicherung](./data-indexing-rag.md#zwischenspeicherung-wiederkehrender-fragen--offen-keine-phase).

## Abgrenzung zu den Struktur-Metadaten der Aufnahmestrecke

Die Typ-Pipelines erzeugen bereits Metadaten, und zwar andere:
[Ingestion-Pipelines, Teil 5](./ingestion-pipelines.md#teil-5--übergabepunkt-an-das-metadatenschema)
definiert den Übergabepunkt. Die Trennung ist scharf und in beide Richtungen gemeint:

| | Struktur-Metadaten (Aufnahmestrecke) | Schema-Metadaten (dieses Dokument) |
|---|---|---|
| **Beispiele** | Gliederungspfad, Überschriftenpfad, Foliennummer, Blattname, Mail-Kopfdaten, Seitenzahl | Dokumentart, Datum/Stand, Fassung, Rechtsebene, Projekt |
| **Entstehung** | **abgeleitet** — aus dem Dokument selbst, während der Reader es strukturiert vor sich hat | **interpretiert** — deterministisch, wo möglich; sonst Modell mit Konfidenz |
| **Geltung** | je Chunk | je Dokument, an alle seine Chunks vererbt |
| **Kann fehlschlagen** | nein, nur fehlen | ja — deshalb Konfidenz, Herkunft und Leerwert |

Ein Feld wechselt die Seite, wenn seine Herkunft wechselt: Der Gliederungspfad „§ 7" ist ein
Struktur-Metadatum, weil der Parser ihn liest. Das Bibliotheksfeld `§` einer Satzungsbibliothek nimmt
diesen Wert entgegen — es rät ihn nicht neu.

**Metadaten hängen am Dokument, nicht am Chunk.** Alle Chunks eines Dokuments tragen dieselben
Schema-Werte. Das ist keine Speicheroptimierung, sondern eine fachliche Aussage: Eine Fassung gilt für
das Dokument, nicht für seinen dritten Absatz. Struktur-Metadaten sind der Gegenfall und bleiben
deshalb, wo sie sind — am Chunk.

---

# Teil IV — Wirkung im Retrieval

## Wirkstelle 1: Harte Filter in beiden Suchpfaden

Ein Metadatenfilter schränkt die Kandidatenmenge ein, **bevor** gerankt wird. Vier Festlegungen:

**Der Filter ist Teil beider Abfragen, nicht ein Nachfilter auf deren Ergebnis.** Vektorpfad und
Volltextpfad tragen ihn identisch — dasselbe Prinzip und derselbe Grund wie beim Rechtefilter (siehe
[Rechtefilter im Volltextpfad](./hybrid-retrieval.md#rechtefilter-im-volltextpfad)). Ein Nachfilter
über einer auf `fetch-k` begrenzten Trefferliste liefert je nach Verteilung ein bis null Ergebnisse,
obwohl passende Dokumente im Bestand liegen: Der Filter hat dann nicht die Menge eingeschränkt,
sondern das bereits gezogene Fenster leergeräumt.

**Nur (a) und (b) filtern.** Freie Schlagworte nicht — siehe oben, und dies ist die Stelle, an der die
Zusage eingelöst wird.

**Der Filter ist dem Rechtefilter nachgeordnet, nie nebengeordnet.** Metadaten sind eine
Komfort-Einschränkung, Rechte sind eine Zusicherung. Ein Metadatenfilter kann die lesbare Menge
verkleinern, nie vergrößern — und ein leerer, fehlender oder fehlerhafter Metadatenfilter darf unter
keinen Umständen zu einem weiteren Suchbereich führen als der Rechtekontext erlaubt. Diese Richtung
wird als Test abgesichert.

**Woher der Filter kommt, ist im ersten Schnitt eine bewusste Auswahl, keine Modellinterpretation.**
Die Filterwerte setzt die fragende Person (Filter-Oberfläche) oder der Kontext des Chats. Aus der
Frage „Galt das auch 2024?" automatisch `Fassung = 2024` abzuleiten, ist attraktiv und riskant: Eine
falsch verstandene Frage erzeugt dann eine leere Trefferliste, die aussieht wie „nichts gefunden".
Diese Ableitung steht unter [Offene Punkte](#offene-punkte), nicht im Lieferumfang.

### Leerwerte schließen nicht aus

Die Regel „lieber leer als geraten" erzeugt eine unvermeidliche Folgefrage: Was passiert mit einem
Dokument ohne Wert für das gefilterte Feld?

> **Ein Dokument mit leerem Feldwert wird von einem Filter auf dieses Feld nicht ausgeschlossen. Es
> wird gefunden und als „ohne Angabe" gekennzeichnet.**

Die Alternative — leer heißt ausgeschlossen — würde die Extraktionslücke in einen unsichtbaren
Bestandsverlust verwandeln und wäre damit genau der Schaden, gegen den die Leerwert-Regel gebaut ist.
Der Preis ist ein weniger scharfer Filter, und er wird bewusst gezahlt: Ein zu weiter Filter ist ein
sichtbares Ärgernis, ein zu enger ein unsichtbarer Fehler. Der [Pflege-Anker](#der-pflege-anker) ist
die Gegenmaßnahme auf der richtigen Seite — er behebt die Ursache, statt die Folge zu verstecken.

## Wirkstelle 2: Kontextpräfix

Metadaten fließen in den Kontextpräfix, den jeder Chunk trägt (`ChunkContextTitle`, #933/#940;
ausgebaut in [Ingestion-Pipelines, Regel (b)](./ingestion-pipelines.md#b-jeder-chunk-trägt-seinen-strukturkontext)):

```
Verwaltungsgebührensatzung › Fassung 2026 › § 7 Gebühren für Personaldokumente › 37,00 EUR
└──── Titel ────────────┘ └─ Metadatum ─┘ └──── Strukturkontext ──────────┘ └ Chunktext ┘
```

Der Präfix geht in **Embedding und Volltextindex** — der belegte Effekt liegt gerade auf der
lexikalischen Seite. Er ist Teil der Chunk-Darstellung, nicht des Rohtexts: Der zitierte Auszug im
Beleg bleibt der Originalwortlaut.

### Der Reindex-Preis, ehrlich ausgewiesen

Das ist die teuerste Eigenschaft des ganzen Schemas, und sie wird hier benannt statt später entdeckt:

> **Jede Änderung an einem Feld, das im Kontextpräfix steht, ändert den indizierten Text jedes Chunks
> jedes betroffenen Dokuments — und macht damit Neu-Einbetten notwendig.**

Daraus folgen drei Festlegungen:

- **Die Wirkstelle „Kontextpräfix" ist je Feld eine bewusste Entscheidung**, keine Voreinstellung für
  alle Felder. Ein Feld, das nur filtert und im Beleg erscheint, kostet bei Änderung nichts.
- **Die Folgekosten sind vor dem Speichern sichtbar.** Wer ein präfixwirksames Feld anlegt, ändert
  oder entfernt, bekommt vor dem Bestätigen die Zahl der betroffenen Dokumente und Chunks angezeigt,
  dazu die daraus geschätzte **Zahl der Einbettungsaufrufe und die erwartete Laufzeit** — nicht eine
  allgemeine Warnung. „4.812 Chunks neu einzubetten, rund 40 Minuten" ist eine Angabe, an der eine
  Entscheidung möglich ist; „dies kann länger dauern" ist keine.
- **Der Nachlauf ist derselbe wie bei einer Pipeline-Umstellung**: selektiv, wiederaufnehmbar, mit
  abfragbarem Fortschritt je Bibliothek (siehe
  [Ingestion-Pipelines, Regel (d)](./ingestion-pipelines.md#d-jeder-chunk-trägt-die-version-des-verfahrens-das-ihn-erzeugt-hat)).
  Es wird kein zweiter Mechanismus dafür gebaut.

Die Übersicht, welche Schemaänderung was kostet:

| Änderung | Wirkung | Nachlauf |
|---|---|---|
| Feld angelegt, nur Filter/Beleg | Werte fehlen bei Bestandsdokumenten | Extraktionslauf, kein Einbetten |
| Feld angelegt, präfixwirksam | wie oben, zusätzlich neuer Chunk-Text | Extraktion **und** Neu-Einbetten |
| Werteliste erweitert | keine Rückwirkung auf bestehende Werte | keiner |
| Wert aus Liste entfernt/umbenannt | bestätigte Abbildung auf einen anderen Wert oder „leer"; kein ungültiger Zustand | Umschlüsselung der betroffenen Dokumente; bei präfixwirksamem Feld zusätzlich Neu-Einbetten |
| Feld entfernt | Werte und Filter entfallen | bei präfixwirksamem Feld Neu-Einbetten |

### Nachlauf im Betrieb

Ein Nachlauf über einen gewachsenen Bestand läuft nicht in Sekunden, sondern in Stunden — und er
läuft, während dieselbe Bibliothek benutzt wird. Vier Zusagen gelten deshalb für **jeden** Nachlauf
dieser Spezifikation, gleich ob er aus einer Schemaänderung, einem
[Bestandslauf](#deterministischer-bestandslauf-über-den-altbestand) oder einer neuen
Extraktionsversion entsteht:

- **Die Suche bleibt während des gesamten Nachlaufs verfügbar.** Ein bereits indizierter Chunk bleibt
  gültig und auffindbar, bis seine Neufassung vorliegt; es gibt kein Zeitfenster, in dem eine
  Bibliothek leer oder halb leer sucht. Der Mischzustand — ein Teil des Bestands trägt die neue
  Fassung, der Rest die alte — ist damit ein **definierter, zulässiger Betriebszustand** und kein
  Fehler. Er MUSS je Bibliothek abfragbar sein (verarbeitet, ausstehend, fehlgeschlagen) und erscheint
  in derselben Zustandsübersicht wie der übrige Indexzustand (siehe
  [Was die Seite anzeigt](./hybrid-retrieval.md#was-die-seite-anzeigt)). Eine Bibliothek, deren
  Metadaten gerade umgestellt werden, ist an dieser Anzeige erkennbar — nicht nur an schwankenden
  Suchergebnissen.
- **Wiederaufnahme ist dokumentgranular und idempotent.** Ein abgebrochener Lauf — Neustart,
  Modellausfall, Verbindungsabbruch — setzt beim nächsten unverarbeiteten **Dokument** fort, nicht am
  Anfang. Ein Dokument ist dabei die kleinste Einheit, die je Zustand wechselt: Es trägt entweder
  vollständig die alte oder vollständig die neue Fassung, nie eine Mischung aus umgeschriebenen und
  alten Chunks. Ein zweiter Lauf über bereits verarbeitete Dokumente ändert nichts und kostet keinen
  Modellaufruf.
- **Der Nachlauf startet als bewusste, eigene Freigabe.** Das Speichern einer Schemaänderung ändert
  das Schema; es setzt keinen Bestand in Bewegung. Der Lauf wird danach ausdrücklich gestartet,
  bibliotheksweise, mit der Kostenanzeige vor Augen. Dass eine Feldkonfiguration in einem
  Nebensatz eine stundenlange Neuindizierung auslöst, ist genau die Überraschung, die diese Trennung
  verhindert — und sie erlaubt es, die Umstellung auf eine Randzeit zu legen.
- **Ein laufender Nachlauf ist anhaltbar und wieder aufnehmbar**, ohne dass der bereits verarbeitete
  Teil verloren geht. Er benutzt denselben versionsgetriebenen Mechanismus wie eine
  Pipeline-Umstellung (siehe
  [Ingestion-Pipelines, Regel (d)](./ingestion-pipelines.md#d-jeder-chunk-trägt-die-version-des-verfahrens-das-ihn-erzeugt-hat));
  es wird kein zweiter gebaut.

## Wirkstelle 3: Beleg-Anzeige

Ein Beleg ist heute Dokument plus Fundstelle. Mit Metadaten wird er einordbar:

```
vorher:   01_verwaltungsgebuehrensatzung.pdf, Seite 4
nachher:  § 3 Verwaltungsgebührensatzung, Fassung 2026 — Seite 4
```

Drei Regeln:

- **Der Beleg zeigt nur, was das Produkt verantworten kann.** Kernfelder und Bibliotheksfelder mit der
  Wirkstelle „Beleg-Anzeige"; keine Schlagworte.
- **Die Belegzeile bleibt lesbar: höchstens zwei Bibliotheksfelder** neben den Kernfeldern und der
  Fundstelle. Ein Beleg ist eine Zeile, die im Lesefluss der Antwort steht; trägt sie fünf Angaben,
  liest sie niemand mehr, und die eine Angabe, auf die es ankommt — die Fassung — geht in der
  Aufzählung unter. Welche zwei es sind, gehört zur Feldkonfiguration.
- **Ein leeres Feld erscheint in der Belegzeile gar nicht.** Kein „Projekt — ohne Angabe". Die
  Kennzeichnung „ohne Angabe" gehört an die Stelle, an der sie eine Aussage trägt: in die
  Trefferliste eines gesetzten Filters (siehe [Leerwerte schließen nicht aus](#leerwerte-schließen-nicht-aus))
  und in die Metadatenansicht des Dokuments. Im Beleg wäre sie eine Lücke, die bei jeder Antwort
  mitgelesen wird und nichts einordnet.
- **Ein abgeleiteter Wert ist im Beleg als abgeleitet erkennbar**, wenn er die Aussage einordnet — eine
  Fassungsangabe, die aus einem Modellaufruf stammt, darf nicht wie eine gelesene aussehen.
- **`location` bleibt die Fundstellenangabe.** Metadaten ergänzen den Beleg, sie ersetzen seine
  Fundstelle nicht (siehe [Ingestion-Pipelines, Teil 5](./ingestion-pipelines.md#teil-5--übergabepunkt-an-das-metadatenschema)).
  Der [Zitierzwang](./data-indexing-rag.md#zitierzwang) gilt unverändert.

---

# Teil V — Verwaltung des Schemas

## Der erste Schnitt: Bibliotheks-Konfiguration, keine Assistenten-Oberfläche

Das Schema wird zunächst als **Konfiguration der Wissensbibliothek** geführt — über die API und eine
Verwaltungsansicht in den Bibliothekseinstellungen. Eine Person mit Verwaltungsrecht an der Bibliothek
legt Felder an, wählt Typ und Werteliste, benennt die Wirkstelle und schaltet Schlagworte an oder aus.

Das ist die Konfigurations-**Ebene 3** aus
[Hybrides Retrieval](./hybrid-retrieval.md#konfigurations-ebenenmodell) — die einzige der drei Ebenen,
die in eine Oberfläche gehört, und die dort benannte, aber ausgelagerte Lücke. Die Begründung gilt
unverändert: Rechtsquellen, Besprechungsnotizen und Tabellenwerke vertragen nicht dieselbe Behandlung,
und das entscheiden Menschen, die den Bestand kennen — nicht ein Regler für Zahlen, die niemand ohne
Benchmark beurteilen kann.

**Warum die Konfiguration vor dem Assistenten steht:** Der Assistent erzeugt am Ende genau diese
Konfiguration. Ohne sie hätte er kein Ergebnis, in das er münden könnte, und man baute die
Benutzerführung für ein Datenmodell, das es noch nicht gibt. Umgekehrt ist die Konfiguration allein
bereits vollständig nutzbar.

## Der Pflege-Anker

Metadatenqualität degradiert mit dem Bestand: Neue Dokumente kommen mit leeren Feldern, niemand merkt
es, und nach drei Jahren ist das Schema eine Attrappe. Dagegen steht **genau eine** Maßnahme:

> Je Bibliothek und je Feld wird angezeigt: **„N Dokumente ohne Wert für Feld X" — absolut und als
> Anteil am Bestand der Bibliothek** — mit der Möglichkeit, genau diese N Dokumente aufzulisten und
> die Werte zu setzen, einzeln oder per
> [Sammelzuweisung](#sammelzuweisung-auf-einer-auswahl).

Die absolute Zahl allein ist nicht handlungsleitend: „120 ohne Wert" ist bei 130 Dokumenten ein
kaputtes Feld und bei 12.000 Dokumenten eine Randmenge. Beide Angaben stehen deshalb nebeneinander,
und der Anteil ist zugleich die Größe, an der die
[Eintrittsbedingung des Kernfeld-Filters](#eintrittsbedingung-für-den-kernfeld-filter) gemessen wird.

**Der Anker steht dort, wo die Pflege stattfindet.** Er erscheint in den Einstellungen der
Bibliothek — bei der Person, die den Bestand kennt und ihn korrigieren darf — und zusätzlich in der
betrieblichen Zustandsübersicht. Ein Pflegehinweis, der nur auf einer Administrationsseite steht,
erreicht die Fachperson nicht, die als Einzige weiß, welche Fassung das Dokument hat.

### „Kein Wert ermittelbar" ist ein dritter Zustand

Ohne ihn ist der Anker eine Zahl, die nie null wird: Manche Dokumente **haben** kein Datum, keine
Fassung, keine sinnvolle Dokumentart. Sie bleiben dauerhaft in der Restliste, und eine Liste, die sich
nicht leeren lässt, wird nach dem dritten Blick nicht mehr angesehen. Deshalb gibt es je Feld und
Dokument drei Zustände statt zwei:

| Zustand | Bedeutung | Im Anker |
|---|---|---|
| **Wert gesetzt** | ein Wert liegt vor, mit Herkunft | nein |
| **leer** | noch nicht ermittelt — offen | **ja** |
| **kein Wert ermittelbar** | eine Person hat festgestellt, dass es keinen gibt | nein |

Der dritte Zustand wird ausschließlich von Hand gesetzt, trägt die Herkunft `manuell` mit Akteur und
erzeugt ein Audit-Ereignis wie jede andere manuelle Setzung; keine automatische Extraktion vergibt
ihn, und keine setzt ihn zurück. Für Filter und Beleg verhält er sich wie ein Leerwert — das Dokument
wird nicht ausgeschlossen (siehe [Leerwerte schließen nicht aus](#leerwerte-schließen-nicht-aus)) und
die Belegzeile zeigt nichts an.

**Damit wird N zu einer abarbeitbaren Restliste statt zu einer Bodenzahl.** Das ist der eigentliche
Zweck: Eine Bibliothek kann den Zustand „vollständig gepflegt" tatsächlich erreichen, und eine Zahl,
die wieder steigt, ist dann ein echtes Signal.

Das ist zugleich die vollständige Aufzählung der Pflegemechanik. Es gibt **keine** Erinnerungen, keine
Pflichtfeld-Erzwingung beim Hochladen, keine Qualitätsnoten je Bibliothek und keine automatische
Nachbefüllung. Jeder dieser Automatismen erzeugt Arbeit, die niemand beauftragt hat, und der erste
davon würde die Regel „lieber leer als geraten" untergraben, indem er zum Ausfüllen drängt, wo niemand
den Wert kennt.

Die Zahl ist derselbe Datentyp wie der Füllstand des Volltext-Backfills: **abfragbarer Zustand, kein
Logeintrag** — und gehört damit in dieselbe Zustandsübersicht wie dieser (siehe
[Was die Seite anzeigt](./hybrid-retrieval.md#was-die-seite-anzeigt)).

## Spätere Ausbaustufe: der geführte Assistent

Der im Diskussionspapier beschriebene Wizard ist **nicht Teil des ersten Umsetzungsschnitts**. Er
setzt auf die fertige Bibliotheks-Konfiguration auf und automatisiert deren Befüllung in drei
Schritten:

1. **Zweck erfragen.** Die anlegende Person beschreibt in Freitext, welche Fragen die Bibliothek
   beantworten soll.
2. **Stichprobe analysieren.** OPAA sichtet 5 bis 20 Dokumente — nur lesbare — und klassifiziert:
   Dokumentarten, erkennbare Struktur, wiederkehrende Merkmale.
3. **Schema aushandeln.** Aus Zweck und Stichprobe entsteht ein **Vorschlag** von Feldern und
   Wertelisten. Die Person bestätigt, streicht, ergänzt.

Vier Bedingungen gelten für diese Stufe von Anfang an, damit sie später nicht nachverhandelt werden:

- **Vorschlagen, nie entscheiden.** Das Modell klassifiziert; das Schema beschließt ein Mensch. Ein
  bestätigtes Schema ist eine dokumentierte Absprache — das ist zugleich die Form, die gegenüber
  Personalvertretung und Fachaufsicht vertretbar ist: nachvollziehbar, was warum erfasst wird.
- **Die Aufnahmeregel gilt unverändert.** Auch ein vorgeschlagenes Feld trägt eine benannte
  Wirkstelle. Ein Assistent, der Felder ohne Wirkung vorschlägt, ist ein Ballastgenerator mit
  Fortschrittsbalken.
- **Der Weg ohne Metadaten bleibt gleichberechtigt sichtbar.** „Ohne Schema starten, später
  anreichern" ist eine Schaltfläche, kein Abbruch — sonst wird der Assistent zur Anlegehürde.
- **Die Stichprobe bleibt klein.** 5 bis 20 Dokumente, einmalig beim Anlegen. Ein Assistent, der beim
  Anlegen den ganzen Bestand durch ein Sprachmodell schickt, ist weder bezahlbar noch abwartbar.

Erst mit dieser Stufe wird auch die **Typ-Pipeline-Zuordnung** Teil des Vorschlags (siehe
[Ingestion-Pipelines](./ingestion-pipelines.md)); im ersten Schnitt ergibt sie sich aus dem
Dateiformat.

---

## Messung und Abnahme

Der Messaufbau ist eigenständig beschrieben in
[Suchqualitäts-Benchmark](./retrieval-benchmark.md). Für diese Spezifikation gilt:

**Die Fallklasse `metadata_filter` ist die Abnahmegrundlage.** Sie ist bereits definiert (siehe
[`metadata_filter` — Filterfragen](./retrieval-benchmark.md#e-metadata_filter--filterfragen)) und dort
ausdrücklich als die einzige Klasse geführt, die eine **noch nicht vorhandene** Produktfähigkeit
misst — nämlich die aus diesem Dokument. Ihre Konstruktion ist die passende: Dokumente, die sich
**nur** im Metadatum unterscheiden, dieselbe Regelung in zwei Fassungen, die falsche als eingebauter
Verwechslungspartner.

Daraus folgen drei bindende Punkte:

1. **Kein Feld ohne messbaren Nutzen.** Die Aufnahmeregel verlangt eine benannte Wirkstelle; die
   Messung verlangt zusätzlich, dass mindestens die Kernfelder und die Fassungs-/Ebenenfelder der
   Satzungsbibliothek durch Golden-Fälle abgedeckt sind. Ein Feld, dessen Nutzen sich nicht in einem
   Fall ausdrücken lässt, ist ein Kandidat für die Streichung.
2. **Zwei Fehlerrichtungen werden getrennt gemessen.** „Der Filter greift nicht" (die falsche Fassung
   erscheint) und „der Filter greift zu stark" (das richtige Dokument verschwindet, weil sein Feld
   leer war) sind verschiedene Fehler mit verschiedenen Abhilfen. Ein Gesamtwert, in dem sie sich
   ausmitteln, verdeckt beide.
3. **Die Extraktionsgüte wird eigenständig gemessen**, nicht nur über das Retrieval-Ergebnis: Anteil
   deterministisch befüllter, modellbefüllter, leerer und als „kein Wert ermittelbar" gekennzeichneter
   Werte je Feld, und — auf einer handausgewerteten Stichprobe — der Anteil der falsch befüllten. Ein
   Filter, der auf einer Extraktion mit 30 % Fehlern arbeitet, ist auch mit tadellosem Filtercode
   wertlos, und ohne diese Messung ist nicht unterscheidbar, welche der beiden Seiten hakt.

   Ein Messversprechen ohne Umfang, Zuständigkeit und Zeitpunkt wird nicht eingelöst. Deshalb
   verbindlich: **Die Stichprobe umfasst 100 Dokumente, wird von der QA-Engineer-Rolle
   handausgewertet und läuft einmalig vor der Abnahme der modellgestützten Extraktion sowie erneut bei
   jeder Änderung der Extraktionsversion.** Die Auswertung vergleicht je Feld den erfassten Wert mit
   dem am Dokument abgelesenen und trennt dabei die Fehlerarten „falscher Wert" und „fehlender Wert
   trotz vorhandener Angabe" — die erste ist der stille Schaden, die zweite eine Lücke der
   Extraktionsregeln.

### Eintrittsbedingung für den Kernfeld-Filter

Der `metadata_filter`-Korpus misst den Filter unter Idealbedingungen: Seine Dokumente tragen die
Metadaten im Frontmatter, jeder Wert ist gesetzt und richtig (siehe
[`metadata_filter` — Filterfragen](./retrieval-benchmark.md#e-metadata_filter--filterfragen)). Das ist
für seinen Zweck richtig — er soll den Filter prüfen, nicht die Extraktion —, lässt aber die Frage
offen, ob dieselbe Fähigkeit auf einem Bestand trägt, dessen Werte erst extrahiert werden mussten.
Der Füllstandsnachweis ist die Ergänzung, die diese Lücke schließt:

> **Der Filter auf die Kernfelder gilt erst als abgenommen, wenn der Füllstand dieser Felder auf einem
> echten Bestand ausgewiesen ist und einen vorab festgelegten Schwellwert erreicht.** Ein echter
> Bestand ist dabei kein konstruierter Korpus; die Demo-Instanz genügt.

Bleibt ein Feld unter dem Schwellwert, ist die Folge nicht „trotzdem ausliefern": **Das Feld wird in
der Filter-Oberfläche nicht angeboten.** Ein angebotener Filter über ein zu 12 % befülltes Feld ist
schlimmer als ein fehlender — er wirkt, sieht aus wie eine Einschränkung des Bestands und schränkt
tatsächlich nur die Menge der zufällig befüllten Dokumente ein. Der Schwellwert wird wie jede
Benchmark-Schwelle **vor** der Messung festgelegt und committet
([ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md)); er steht bei den offenen Punkten,
solange keine Füllstandsverteilung eines echten Bestands vorliegt.

---

## Reihenfolge der Arbeitspakete

| # | Paket | Abhängig von | Nutzen allein |
|---|---|---|---|
| 1 | Kernfelder: Datenmodell, Herkunft/Konfidenz/Akteur, deterministische Extraktion beim Aufnehmen — **umgesetzt mit #1066**, siehe [Umgesetzt (#1066)](#umgesetzt-1066) | — | Beleg-Anzeige wird einordbar; Grundlage für alles Weitere |
| 2 | **Deterministischer Bestandslauf** über den Altbestand, bibliotheksweise, mit den Nachlauf-Zusagen — **umgesetzt mit #1067**, siehe [Umgesetzt (#1067)](#umgesetzt-1067) | 1 | Die Kernfelder gelten für den vorhandenen Bestand, nicht nur für künftige Dokumente |
| 3 | Manuelle Korrektur, Sammelzuweisung, Audit-Ereignis — **umgesetzt mit #1068**, siehe [Umgesetzt (#1068)](#umgesetzt-1068) — und Pflege-Anker („N ohne Wert", absolut und anteilig; #1069) | 1, 2 | Die Leerwert-Regel wird behebbar statt Dauerzustand |
| 4 | Metadatenfilter in beiden Suchpfaden, mit Füllstandsanzeige je Feld | 2, 3, Hybrid-Suche AP 3 | Löst Szenario 9; die `metadata_filter`-Fälle werden erstmals lösbar |
| 5 | Bibliotheksfelder: Schemakonfiguration je Bibliothek, Wertelisten mit bestätigter Abbildung | 1, 4 | Fassung und Rechtsebene werden führbar |
| 6 | Metadaten im Kontextpräfix, mit Folgekostenanzeige und selektivem Nachlauf | 5, Ingestion Regel (b)/(d) | Wirkung auch ohne gesetzten Filter |
| 7 | Modellgestützte Extraktion mit Konfidenz, je Bibliothek abschaltbar | 1, 5 | Felder, die deterministisch nicht erreichbar sind |
| 8 | Freie Schlagworte (optional je Bibliothek) | 7, Volltextpfad | Zusätzlicher Fundweg bei Vokabellücken |
| 9 | Geführter Assistent | 5, 7 | Bedienkomfort beim Anlegen; keine neue Fähigkeit |

Paket 2 ist kein Nachzügler, sondern die Bedingung dafür, dass Paket 4 überhaupt beurteilbar ist: Ein
Filter auf Felder, die nur bei den seit gestern aufgenommenen Dokumenten befüllt sind, lässt sich
weder abnehmen noch sinnvoll benutzen — die
[Eintrittsbedingung](#eintrittsbedingung-für-den-kernfeld-filter) verlangt einen ausgewiesenen
Füllstand auf einem echten Bestand, und den erzeugt genau dieses Paket. Es steht zugleich früh, weil
es die einzige Gelegenheit ist, die Güte der deterministischen Regeln an einem realen Bestand zu
sehen, bevor darauf aufgebaut wird.

Paket 4 steht bewusst **vor** Paket 5: Der Filter auf die drei Kernfelder ist die Fähigkeit, an der
sich das Verfahren bewähren muss. Zeigt sich dort, dass die Extraktionsgüte nicht trägt, sind
bibliotheksweite Schemata das falsche nächste Paket.

Paket 7 steht **hinter** 4 und 5, obwohl es fachlich attraktiver ist: Die modellgestützte Extraktion
ist der Teil mit dem größten Schadenspotenzial, und sie sollte gegen eine Filterfähigkeit gebaut
werden, deren Verhalten bei sauberen Werten bereits gemessen ist.

---

## Integrationspunkte

- **[Hybrides Retrieval](./hybrid-retrieval.md)** — die beiden Suchpfade, in denen der Metadatenfilter
  identisch wirkt, das [Konfigurations-Ebenenmodell](./hybrid-retrieval.md#konfigurations-ebenenmodell)
  (dieses Dokument füllt dessen Ebene 3) und die Zustandsübersicht, in der der Pflege-Anker erscheint.
- **[Ingestion-Pipelines](./ingestion-pipelines.md)** — der
  [Übergabepunkt](./ingestion-pipelines.md#teil-5--übergabepunkt-an-das-metadatenschema): Die
  Aufnahmestrecke liefert Struktur-Metadaten, dieses Dokument die interpretierten. Der Nachlauf für
  präfixwirksame Schemaänderungen benutzt denselben versionsgetriebenen Mechanismus.
- **[Retrieval-Benchmark](./retrieval-benchmark.md)** — die Fallklasse `metadata_filter` ist die
  Abnahmegrundlage; Messvertrag und Fehlerkriterium gelten unverändert
  ([ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md),
  [ADR-0013](../decisions/0013-fehlerkriterium-retrieval-regression.md)).
- **[Wissensschicht und Retrieval](./data-indexing-rag.md)** — Zielbild der
  [Metadaten-Extraktion](./data-indexing-rag.md#extraktion-von-dokumentmetadaten--phase-2), die
  [Filterachse](./data-indexing-rag.md#speicherung-und-filterachse) und der
  [Zitierzwang](./data-indexing-rag.md#zitierzwang), an dem die Beleg-Anzeige hängt.
- **[Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md)** — der Rechtekontext, in dem
  Extraktion, Schemavorschlag und **jedes aus dem Bestand abgeleitete Aggregat** laufen; das
  Bearbeitungsrecht am Dokument trägt die Metadatenkorrektur, das Verwaltungsrecht an der Bibliothek
  die Schemaänderung; der Metadatenfilter ist dem Rechtefilter nachgeordnet.
- **[Sicherheit und Nachvollziehbarkeit](./security-and-compliance.md)** — die Protokollablage, in der
  die Audit-Ereignisse manueller Metadatensetzungen landen
  ([ADR-0015](../decisions/0015-eigentuemertrennung-protokollablage.md)); aus ihr sind manuelle Werte
  nach einer Wiederherstellung rekonstruierbar.
- **[Wissensquellen und Konnektoren](./knowledge-sources.md)** — der
  [Lebenszyklus der Dokumente](./knowledge-sources.md#lebenszyklus-der-dokumente); Gültigkeitszustände
  („aktiv", „archiviert", „abgelaufen") sind eine eigene Achse und werden **nicht** in ein Metadatenfeld
  dieses Schemas dupliziert.
- **[Modelle und zentrale Steuerung](./llm-integration.md)** — die modellgestützte Extraktion nutzt
  die Chat-Rolle des Schichtenmodells und erbt dessen Zusagen; sie verlangt keine eigene Modellrolle,
  ist je Bibliothek abschaltbar und bei einem lokal betriebenen Modell ohne ausgehende Verbindung
  nutzbar.

---

## Bewusst nicht gebaut

Jede Zeile ist eine Entscheidung, keine Auslassung.

- **Freies Auto-Tagging als Voreinstellung.** Schlagworte gibt es (c), aber je Bibliothek
  abschaltbar und **voreingestellt aus**. Ein unkontrolliert wachsendes Vokabular, das automatisch
  über jedem Bestand liegt, erzeugt Umfang ohne Filternutzen.
- **Modellgestützte Extraktion als Voreinstellung.** Es gibt sie (Schritt 2), aber je Bibliothek
  abschaltbar und **voreingestellt aus**. Eine Fähigkeit, die jedes aufgenommene Dokument an ein
  Modell übergibt, wird nicht stillschweigend eingeschaltet — bei einem extern betriebenen Modell wäre
  das ein Datenabfluss, den niemand veranlasst hat.
- **Ein amtliches Metadatenmodell (XÖV, Aktenplan, Aufbewahrungsfristen).** Das Schema dient dem
  Retrieval, nicht der Aktenführung. Die Felder sind danach ausgewählt, ob sie eine Suche verbessern,
  nicht danach, ob sie eine Akte vollständig beschreiben. Wer Aktenführung braucht, braucht ein
  DMS — und OPAA soll es nicht ersetzen.
- **Freitextfelder als Feldtyp.** Sie sähen aus wie Struktur und verhielten sich wie Schlagworte;
  siehe [Kontrolliertes Vokabular](#kontrolliertes-vokabular-statt-freitext).
- **Automatische Filterableitung aus der Frage** im ersten Schnitt. Attraktiv, aber der Fehlerfall ist
  eine leere Trefferliste, die wie „nicht vorhanden" aussieht. Steht unter Offene Punkte.
- **Metadaten je Chunk statt je Dokument.** Eine Fassung gilt für das Dokument. Chunk-genaue
  Schema-Werte würden dieselbe Angabe an hunderten Stellen pflegbar machen — und damit inkonsistent.
- **Pflege-Automatismen über den Anker hinaus** — Pflichtfelder beim Hochladen, Erinnerungen,
  Qualitätsnoten je Bibliothek, automatische Nachbefüllung leerer Felder. Sie erzeugen unbeauftragte
  Arbeit, und die Pflichtfeld-Variante erzeugt zusätzlich geratene Werte. Die
  [Sammelzuweisung](#sammelzuweisung-auf-einer-auswahl) ist davon nicht berührt: Dort setzt ein Mensch
  einen Wert für eine selbst gewählte Menge — das System entscheidet nichts.
- **Beförderung häufiger Schlagworte zu Bibliotheksfeldern**, automatisch oder als Vorschlag.
  Häufigkeit ist kein Beleg für Filtertauglichkeit.
- **Vererbung eines Schemas über Bibliotheken hinweg** (Organisationsvorlagen, Schema-Bibliotheken).
  Naheliegend, sobald mehrere Satzungsbibliotheken existieren — aber ohne Betriebserfahrung mit
  einem einzigen Schema ist der Zuschnitt einer Vorlage geraten.

---

## Offene Punkte

Nur Fragen, die tatsächlich offen sind und vor oder während der Umsetzung entschieden werden müssen.

- **Ab welcher Konfidenz wird ein modellbefüllter Wert übernommen?** Die Regel „unsicher bleibt leer"
  steht fest, die Schwelle nicht. Sie ist erst mit gemessenen Konfidenzverteilungen auf einem echten
  Bestand festlegbar — und sie muss, wie jede Benchmark-Schwelle, **vor** dem ersten Variantenvergleich
  festgelegt und committet sein.
- **Darf ein Filterwert aus der Frage abgeleitet werden, und wenn ja, wie sichtbar?** Die
  Ableitung ist der eigentliche Bedienkomfort („Galt das auch 2024?" ohne Filterklick), und ihr
  Fehlerfall ist besonders unangenehm. Denkbar ist ein Mittelweg: Ableiten, aber sichtbar als
  gesetzter Filter anzeigen und mit einem Klick entfernbar machen. Zu entscheiden nach den ersten
  Messwerten des gesetzten Filters, nicht davor.
- **Wie verhalten sich Fassungen zu den Gültigkeitszuständen des Dokumentlebenszyklus?**
  „archiviert"/„abgelaufen" (siehe
  [Ablauf und Archivierung](./data-indexing-rag.md#ablauf-und-archivierung-von-dokumenten--phase-2))
  und ein Metadatenfeld `Fassung` beschreiben überlappende Sachverhalte auf zwei Achsen. Dass der
  Zustand **nicht** in ein Schemafeld dupliziert wird, ist entschieden; wie beide in derselben Abfrage
  zusammenwirken — insbesondere ob eine Fassungsfrage abgelaufene Dokumente erreichen darf — ist es
  nicht.
- **Woher kommt der Wertevorrat für `Rechtsebene` und `Dokumentart` bei einer Erweiterung?** Die
  ausgelieferte Liste ist je Installation erweiterbar. Offen ist, ob eine Erweiterung auf
  Organisationsebene oder je Bibliothek gilt — Ersteres hält das Vokabular zusammen, Letzteres passt
  zur Ebene-3-Zuordnung des übrigen Schemas.
- **Welcher Füllstand genügt, damit ein Kernfeld als Filter angeboten wird?** Dass es einen vorab
  festgelegten Schwellwert gibt und ein Feld darunter nicht angeboten wird, ist entschieden (siehe
  [Eintrittsbedingung](#eintrittsbedingung-für-den-kernfeld-filter)); die Zahl selbst ist es nicht.
  Sie ist erst mit der Füllstandsverteilung eines echten Bestands festlegbar und muss — wie jede
  Benchmark-Schwelle — vor der ersten Messung committet sein. Denkbar ist auch, dass sie je Feld
  verschieden ausfällt: Ein Datum ist deterministisch häufiger erreichbar als eine Dokumentart.
