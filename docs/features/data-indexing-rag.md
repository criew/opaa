# Wissensschicht und Retrieval

> **Status: Entwurf — wesentliche offene Fragen verbleiben.**

**Themenbereich A** der [Produktvision](../VISION.md). **Phasenlage:** Der Kern — Quellenbindung,
Zitierzwang, Konfidenz, hybride Suche mit Reranking, Formaterkennung und erklärbares Chunking — gehört
in **Phase 1**. Deep Research und die Bilderkennung folgen in **Phase 2**, der Wissensgraph in
**Phase 3**.

## Motivation

Eine Auskunft in der Verwaltung ist keine Meinung. Jemand steht mit seinem Namen dafür gerade, und Jahre
später muss nachvollziehbar sein, worauf sie sich stützte. Ein Assistent, der plausibel klingende Sätze
erzeugt, ist in dieser Lage nicht nur nutzlos, sondern gefährlich: Er verschiebt Arbeit vom Suchen zum
Nachprüfen, ohne dass jemand merkt, wann das Nachprüfen ausbleibt.

Die Wissensschicht ist deshalb nicht als „Suche mit Sprachmodell davor" entworfen, sondern als
**Nachweisapparat**. Sie beantwortet drei Fragen, die jede Antwort mitführen muss: Woher stammt diese
Aussage? Wie sicher ist der Treffer? Und was ist zu tun, wenn nichts Belastbares gefunden wurde?

Dieses Dokument beschreibt, wie aus indizierten Dokumenten belegte Antworten werden. **Woher die
Dokumente kommen**, steht in [Wissensquellen und Konnektoren](./knowledge-sources.md); **wer sie sehen
darf**, in [Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md); **welches Modell antwortet**,
in [Modelle und zentrale Steuerung](./llm-integration.md).

**Lesehinweis zum Umsetzungsstand.** Diese Spezifikation beschreibt überwiegend das Zielbild. Wo sie
bereits ausgelieferte Funktionalität beschreibt, ist das ausdrücklich mit **(gebaut)** gekennzeichnet.
Alles ohne diese Kennzeichnung ist noch nicht vorhanden.

---

## Überblick

1. **Jede Aussage ist an ihre Fundstelle gebunden.** Nicht die Antwort als Ganzes trägt eine Quellenliste,
   sondern der einzelne Satz trägt seinen Beleg.
2. **Jeder Beleg wird deterministisch validiert**, keine Formulierungsbitte an das Modell: Dokument-
   Kennung, Abschnittsnummer und Dokumentbezeichnung müssen zu den für diese Antwort tatsächlich
   abgerufenen Fundstellen passen. Geprüft wird die **Form** des Belegs, nicht seine inhaltliche Deckung;
   ein Zwangs- und Verweigerungsapparat darüber ist bewusst nicht gebaut (siehe
   [Zitierzwang](#zitierzwang)).
3. **Konfidenz wird ausgewiesen**, getrennt nach Trefferqualität und Belegdeckung — zwei verschiedene
   Aussagen, die nicht zu einer Zahl verschmolzen werden.
4. **Gesucht wird hybrid**: Vektorsuche und Volltextsuche laufen nebeneinander, ihre Ergebnisse werden
   zusammengeführt und durch ein Reranking auf Präzision gebracht.
5. **Die Rechteprüfung sitzt in der Suche**, nicht dahinter. Was jemand nicht lesen darf, wird nicht
   geladen und nicht gerankt.
6. **Das Chunking ist erklärbar und darstellbar.** Wer wissen will, warum eine Fundstelle so und nicht
   anders zugeschnitten ist, bekommt die Zerlegung angezeigt.
7. **Formate werden erkannt, nicht geraten** — die Extraktions- und Zerlegungsstrategie richtet sich nach
   dem tatsächlichen Dokumenttyp.
8. **Bild- und Handschriftenverständnis ist eine Fähigkeit des eingesetzten Modells**, keine Zusicherung
   des Produkts.
9. **Deep Research** liefert für größere Fragen einen zitierbaren Bericht statt einer Chatantwort.
10. **Der Wissensgraph ist eine Ergänzung des Retrievals**, kein Ersatz, und eine spätere Ausbaustufe.

---

## Belegbarkeit

### Bindung der Aussage an die Fundstelle

Eine Quellenliste am Ende einer Antwort ist kein Beleg. Sie sagt, welche Dokumente beteiligt waren, aber
nicht, welcher Satz woher stammt — und genau das ist die Frage, die eine Prüferin stellt.

OPAA bindet deshalb **auf Aussageebene**. Jede tragende Aussage der Antwort führt eine Fundstelle mit, und
zwar so genau, wie das Quelldokument es hergibt:

| Element | Inhalt |
|---|---|
| Dokument | Titel, Bibliothek, Fassung und Stand |
| Stelle | Seite, Abschnitt, Randnummer oder Zeitmarke, je nach Format |
| Sprungmarke | Aufruf der Textstelle im Original, mit Hervorhebung der belegenden Passage |
| Auszug | die verwendete Passage im Wortlaut |

Der **Sprung in das Quelldokument wird beim Klick erneut gegen die Rechte des Lesenden geprüft**. Das ist
kein doppelter Aufwand, sondern verhindert das Weiterhangeln vom zitierten Auszug in den vollen Bestand,
etwa nachdem ein Chat geteilt wurde (siehe
[Das Ableitungsleck](./spaces-and-assets.md#das-ableitungsleck)).

Zusätzlich wird ausgewiesen, **was durchsucht wurde**: welche Wissensbibliotheken im Suchbereich lagen und
wie viele Treffer in die Antwort eingegangen sind. Bei fehlendem Zugriff bleibt diese Anzeige bewusst
unspezifisch — „in diesem Space ist für dich derzeit kein Wissen verfügbar" statt einer Angabe, wie viele
Bestände gesperrt sind.

### Zitierzwang

**Gebaut ist die deterministische Belegvalidierung** (#386): Jeder Beleg, den das Modell in seiner Antwort
erzeugt, wird gegen die Menge der für **diese** Antwort tatsächlich abgerufenen Fundstellen geprüft — kein
zweiter Modellaufruf, keine Wahrscheinlichkeit, sondern ein deterministischer Abgleich mit immer demselben
Ergebnis bei gleicher Eingabe.

#### Was geprüft wird

Drei Bedingungen, alle deterministisch, alle nach der Erzeugung und vor der Auslieferung der Antwort:

1. **Die Dokument-Kennung muss zu einem abgerufenen Chunk gehören.** Ein Beleg, dessen Kennung unter den
   für diese Antwort abgerufenen Chunks nicht vorkommt, ist ungültig — auch wenn er formal korrekt
   aufgebaut ist. Ein Modell, das nur das Belegmuster nachahmt, erzeugt damit erkennbar einen Beleg, der
   auf nichts zeigt.
2. **Die Abschnittsnummer muss zu diesem Dokument unter den abgerufenen Chunks gehören.** Ein Beleg auf
   einen Abschnitt, der zwar zum richtigen Dokument, aber nicht zur abgerufenen Menge gehört, ist
   ungültig — das Modell kann diesen Abschnitt für diese Antwort gar nicht gesehen haben.
3. **Die mitgeführte Dokumentbezeichnung muss zur Kennung passen.** Ein Beleg mit gültiger Kennung, aber
   abweichendem Dokumentnamen ist ungültig — er ist irreführender als gar keiner, weil er auf ein
   existierendes, aber falsches Dokument verweist.

**Grenze der Erkennung:** Ein Beleg, der das Muster `【source: id#n | name】` nicht erfüllt — etwa ohne
`#n` oder mit einem Zeichen in der Kennung, das der Parser nicht zulässt —, wird gar nicht erst als Beleg
erkannt und damit weder geprüft noch gekennzeichnet.

**Was die Prüfung ausdrücklich nicht prüft:** ob die zitierte Fundstelle die Aussage **inhaltlich trägt**.
Ein formal gültiger Beleg ist der Nachweis, dass eine real abgerufene Passage benannt wurde — nicht der
Nachweis, dass sie das Behauptete aussagt. Ein Modell kann eine korrekt bestehende Fundstelle an einen Satz
hängen, mit dem sie nichts zu tun hat, und die Prüfung lässt das durch. Diese inhaltliche Deckungsprüfung
bräuchte einen zweiten Modelldurchlauf und ist bewusst nicht gebaut (siehe unten).

#### Was mit einem ungültigen Beleg passiert

Ein ungültiger Beleg wird **nicht stillschweigend entfernt und nicht stillschweigend als gültig
behandelt.** Er bleibt im Antworttext stehen — die Belegprüfung greift nicht in die generierte Antwort ein
— und die zugehörige Quellenangabe wird in der API-Antwort als ungültig gekennzeichnet
(`SourceReference.citationValid = false`). Zeigt ein ungültiger Beleg auf ein Dokument, das gar nicht unter
den abgerufenen Chunks war, entsteht dafür eine eigene, synthetische Quellenangabe ohne Relevanzwert und
ohne Trefferzahl (`relevanceScore`/`matchCount` beide `0`) — nur so lässt sich auch dieser Fall überhaupt
kennzeichnen, da er sonst keiner realen Fundstelle zuzuordnen wäre. Diese synthetische Angabe wird nie mit
einer echten Quellenangabe gleichen Dateinamens zusammengeführt: Ein erfundener Beleg, der zufällig den
Namen einer tatsächlich abgerufenen, aber unbenutzten Datei trägt, darf diese Datei nicht rückwirkend als
zitiert erscheinen lassen, mit ihrem echten Relevanzwert und ihrem echten Sprunglink.

Im Frontend erscheint eine so gekennzeichnete Quelle im Belegfenster mit dem dezenten Hinweis „Beleg nicht
bestätigt" (`SourceEvidenceDrawer`). Auf Backend-Seite wird die Zahl der Antworten mit mindestens einem
ungültigen Beleg als Log-Information festgehalten (`QueryService`) — ohne eigene Metrik-Infrastruktur, als
Grundlage für eine spätere Auswertung.

#### Bewusst nicht gebaut

Der ursprüngliche Zuschnitt in [#354](https://github.com/criew/opaa/issues/354) sah einen vollständigen
Zwangs- und Verweigerungsapparat vor: eine Verweigerung mit Auskunft über den Suchvorgang, wenn keine
Fundstelle vorliegt oder eine tragende Aussage keinen gültigen Beleg führt
([#387](https://github.com/criew/opaa/issues/387)), einen Schalter am Space mit erzwingender
Systemvorgabe ([#388](https://github.com/criew/opaa/issues/388)) sowie eine Entscheidungsvorlage zur
inhaltlichen Deckungsprüfung ([#389](https://github.com/criew/opaa/issues/389), „Stufe 2"). Der
Maintainer hat diesen Teil am 21.08.2026 verworfen und alle drei Vorgänge geschlossen — nicht
aufgeschoben, sondern entschieden nicht gebaut:

Das Modell kommuniziert bereits selbst, wenn es nichts gefunden hat, und fehlende Belege sind für Nutzende
im Belegfenster unmittelbar sichtbar. Die Belegvalidierung stellt sicher, dass die vorhandenen Belege echt
sind; ein Zwangs- und Verweigerungsapparat darüber — mit Abschnittszerlegung samt Negativliste, einer
Formregel gegen Belegverdünnung und einem eigenen Schalter am Space — stünde in keinem Verhältnis zum
Nutzen. Damit entfällt auch die inhaltliche Deckungsprüfung (Stufe 2): Ohne den Verweigerungsapparat, den
sie hätte absichern sollen, fehlt ihr die Grundlage.

### Konfidenz

Der Nutzen einer Konfidenzangabe hängt daran, dass sie eine beantwortbare Frage beantwortet. „0,73" tut
das nicht. OPAA weist deshalb **zwei getrennte Größen** aus:

| Größe | Frage | Grundlage |
|---|---|---|
| **Trefferqualität** | Wie gut passen die gefundenen Stellen zur Frage? | Bewertung nach dem Reranking, je Fundstelle |
| **Belegdeckung** | Wie viel der Antwort ist belegt? | Anteil der Quellenangaben mit einem gültigen Beleg (`cited = true` und `citationValid = true`, siehe [Zitierzwang](#zitierzwang)) |

Beide werden in Stufen dargestellt — hoch, mittel, gering — und nicht als Nachkommastelle, die eine
Genauigkeit vortäuscht, die das Verfahren nicht hat. Der Zahlenwert bleibt in der Detailansicht und im
Protokoll erhalten, weil die Qualitätsmessung ihn braucht.

Eine hohe Trefferqualität bei geringer Belegdeckung ist der gefährlichste Fall — gute Quellen, freie
Antwort — und wird ausdrücklich als solcher gekennzeichnet, nicht zu einem Mittelwert verrechnet.

---

## Suche

### Hybride Suche

Vektorsuche und Volltextsuche haben komplementäre Schwächen, und beide fallen in der Verwaltung ins
Gewicht:

- Die **Vektorsuche** findet sinnverwandte Formulierungen und verträgt Umgangssprache in der Frage. Sie
  versagt bei Aktenzeichen, Paragrafen, Erlassnummern und Eigennamen — genau den Zeichenfolgen, mit denen
  in der Verwaltung gesucht wird.
- Die **Volltextsuche** trifft diese exakt, findet aber nichts, was anders benannt ist.

OPAA führt beide Wege parallel aus und verschmilzt die Ergebnislisten. Der Zusammenführung liegt eine
rangbasierte Verrechnung zugrunde: Ein Dokument, das in beiden Listen weit oben steht, gewinnt gegenüber
einem, das nur in einer Liste weit oben steht. Damit müssen die Bewertungen beider Verfahren nicht auf
eine gemeinsame Skala gebracht werden, was sie ohnehin nicht sind.

```
Frage
  ├── Vektorsuche      → Trefferliste V   (Sinnverwandtschaft)
  └── Volltextsuche    → Trefferliste T   (exakte Begriffe, Aktenzeichen)
            ↓
      Zusammenführung nach Rang
            ↓
      Reranking (Frage + Passage gemeinsam bewertet)
            ↓
      Auswahl der Passagen für die Antwort
```

Beide Wege tragen **denselben Rechtefilter**: die Menge der für den Aufrufenden lesbaren
Wissensbibliotheken, geschnitten mit dem Suchbereich des Kontexts (siehe
[Suchbereich je Chatart](./spaces-and-assets.md#suchbereich-je-chatart)). Ein zweiter Pfad ohne
Rechtefilter darf nicht entstehen — das ist die häufigste Art, wie eine rechtebewusste Suche in einem
Nachrüstschritt undicht wird.

### Reranking

Die zusammengeführte Liste ist auf Abdeckung optimiert, nicht auf Präzision. Das Reranking bewertet
anschließend jede Passage **gemeinsam mit der Frage** und ordnet neu. Der Unterschied zur ersten Stufe ist
wesentlich: Dort werden Frage und Passage getrennt in Vektoren überführt und verglichen; hier sieht das
bewertende Modell beide zusammen und kann erkennen, ob die Passage die Frage tatsächlich beantwortet.

Das Reranking ist der Punkt, an dem sich Aufwand und Qualität abwägen lassen, und deshalb
konfigurierbar:
Größe der Kandidatenmenge, Zahl der an die Antwort übergebenen Passagen und die Schwelle, unterhalb derer
eine Passage nicht mehr als Beleg taugt. Diese Schwelle entscheidet damit auch, welche Passagen überhaupt
für die Belegvalidierung (siehe [Zitierzwang](#zitierzwang)) infrage kommen.

Zusätzliche Signale — Aktualität eines Dokuments, Vielfalt der Quellen, damit nicht fünf Passagen aus
derselben Datei die Antwort tragen — wirken **nach** dem Reranking und sind einzeln abschaltbar. Ein
Aktualitätsbonus ist im Rechtsbereich nicht immer erwünscht: Die geltende Fassung ist nicht immer die
jüngste Datei.

Mit der Auswahl endet die Zuständigkeit dieses Dokuments. **Wie** die ausgewählten Passagen an das
Modell übergeben werden — mit welchem Kopf je Passage, in welcher Form die Belege zurückkommen und was
bei Überschreitung der Kontextgrenze entfällt — ist eine Eigenschaft des Modellaufrufs und steht in
[Modelle und zentrale Steuerung](./llm-integration.md#übergabe-der-passagen-und-form-der-antwort). Diese
Übergabe ist die Nahtstelle, an der die Belegprüfung ansetzt.

### Stellschrauben und ihre Wirkung

Die Güte des Retrievals hängt an wenigen Zahlen. Sie gehören in die Spezifikation, weil die
Regressionsmessung (siehe [Qualitätssicherung](#qualitätssicherung)) genau ihre Wirkung misst — eine
Kennzahl über einen Parameter, den niemand beschrieben hat, ist nicht auswertbar.

| Stellschraube | Heutige Voreinstellung | Wovon die Wahl abhängt | Wirkung einer Erhöhung |
|---|---|---|---|
| **Chunk-Größe** | 1000 Token **(gebaut)** | Wie lang eine in sich verständliche Sinneinheit im Bestand ist | Mehr Zusammenhang je Fundstelle, aber unschärfere Treffer und längere Belegauszüge |
| **Mindestgröße eines Chunks** | 350 Zeichen **(gebaut)** | Wie stark der Bestand zu Kurzabschnitten neigt | Weniger inhaltsleere Splitter, aber Verlust kurzer, präziser Definitionen |
| **Überlappung** | 100 Token **(gebaut)** | Ob Aussagen regelmäßig über Abschnittsgrenzen laufen | Weniger an der Grenze zerschnittene Aussagen, aber mehr Chunks, mehr Speicher und doppelte Treffer |
| **`top-k`** | 8 **(gebaut)** | Wie viele Belegstellen eine typische Frage braucht | Höhere Trefferwahrscheinlichkeit, aber mehr Rauschen im Antwortkontext und höherer Verbrauch |
| **`fetch-k`** | 25 **(gebaut)** | Wie viele Kandidaten die Vektorsuche liefert, bevor die Vielfaltsauswahl (MMR, siehe direkt unter der Tabelle) daraus `top-k` auswählt | Mehr Spielraum für Vielfalt bei Mehrthemen-Fragen, aber mehr Rechenaufwand für die Auswahl |
| **MMR-λ (`mmr-lambda`)** | 1,0 **(gebaut, Vielfalt per Default deaktiviert)** | Abwägung zwischen Relevanz und Vielfalt bei der Auswahl aus `fetch-k` Kandidaten (Maximal Marginal Relevance) — `1,0` schaltet die Vielfalt ab und wählt reine Top-`k`-Relevanz | Höherer Wert bevorzugt Relevanz stärker, niedrigerer Wert verdrängt redundante Fundstellen zugunsten thematisch anderer stärker |
| **Ähnlichkeitsschwelle** | 0,3 **(gebaut)** | Wie umgangssprachlich gefragt wird und wie homogen der Bestand ist | Weniger unpassende Treffer, aber häufiger keine ausreichend ähnliche Fundstelle und damit eine Antwort ohne Beleg |
| **Bündelgröße der Einbettung** | 50 Chunks je Aufruf **(gebaut)** | Belastbarkeit des Einbettungsdienstes | Schnellere Läufe, aber Lastspitzen und größerer Speicherbedarf |
| **Wiederholversuche je Dokument** | 3 **(gebaut)** | Zuverlässigkeit von Quelle und Modelldienst | Weniger verlorene Dokumente, aber längere Läufe bei dauerhaft defekten Dateien |

**`fetch-k`/`mmr-lambda` steuern gemeinsam die Vielfaltsauswahl (Maximal Marginal Relevance, MMR,
#914).** Die Vektorsuche holt zunächst `fetch-k` Kandidaten statt nur `top-k`. Daraus wählt MMR
schrittweise `top-k` Fundstellen: Der erste Treffer ist stets der relevanteste; jeder weitere
Treffer bekommt einen Abzug, wenn er inhaltlich zu nah an einer bereits gewählten Fundstelle liegt
- ein etwas weniger relevanter, aber thematisch neuer Treffer gewinnt dann gegen eine Wiederholung.
Ohne diese Auswahl konnte eine einzelne, im Bestand dominante Frage alle `top-k` Plätze mit
untereinander redundanten Fundstellen füllen und eine zweite, in derselben Frage mitgemeinte Frage
vollständig verdrängen. Die inhaltliche Nähe zwischen zwei Kandidaten wird über die Kosinus-Ähnlichkeit
ihrer bereits vorhandenen Einbettungsvektoren berechnet - ein einziger, leichtgewichtiger
Datenbank-Nachschlag über die `fetch-k` Kandidaten-Kennungen je Anfrage, kein zusätzlicher Aufruf
beim Einbettungsdienst.

**MMR ist gebaut, aber per Voreinstellung deaktiviert (`mmr-lambda: 1.0`).** Gegen die 20
`multi_topic`-Golden-Fälle aus #915 gemessen (beide erwarteten Dokumente unter den zurückgegebenen
Fundstellen vertreten) erreichte die reine `top-k`-Anhebung auf 8 ohne Vielfaltsauswahl 20 von 20
Fällen, `mmr-lambda: 0,7` mit echten Chunk-Embeddings dagegen 19 von 20 - ein Fall schlechter, obwohl
deutlich besser als die zuerst erprobte, inzwischen verworfene lexikalische Näherung (15 von 20, siehe
PR zu #914). Die reine `top-k`-Anhebung liefert auf diesem Datensatz also das bessere Ergebnis; MMR
bleibt als betreiberseitig aktivierbare Option bestehen (`opaa.query.mmr-lambda` unter 1,0 setzen),
ohne bislang selbst als Standard zu überzeugen. Eine künftige, größere oder heterogenere
Mehrthemen-Stichprobe kann diese Einschätzung revidieren.

Drei Zusammenhänge sind dabei wesentlich:

1. **Chunk-Größe und `top-k` hängen aneinander.** Beide bestimmen zusammen, wie viel Text in die
   Antwort eingeht. Wer die Chunks vergrößert, muss `top-k` in der Regel senken, sonst wächst der
   Antwortkontext über das hinaus, was das Modell verlässlich auswertet.
2. **Die Ähnlichkeitsschwelle bestimmt, welche Passagen als Beleg infrage kommen.** Sie zu senken, um
   mehr Fragen mit einer Antwort statt mit einer Fundstellenlücke zu beantworten, verschiebt das
   Problem: Die Antworten werden dann auf schwächere Belege gestützt. Die Belegvalidierung (siehe
   [Zitierzwang](#zitierzwang)) stellt nur sicher, dass ein zitierter Beleg echt ist — nicht, dass er
   inhaltlich trägt.
3. **Die Überlappung entscheidet, ob ein Beleg seinen Bezug behält.** Ohne sie kann eine Definition
   von ihrer Überschrift getrennt werden, und der Beleg zeigt eine Passage, der ihr Bezug fehlt.
   Jeder Chunk wiederholt deshalb die letzten 100 Token seines Vorgängers — ein Zehntel der
   Chunk-Größe, gezählt in derselben Tokenisierung.

   Dieser Wert ist **gesetzt, nicht gemessen**, und das ist eine bewusste Aussage: Der
   Evaluierungskorpus taugt für diese Frage nicht. Er unterliegt der Ein-Chunk-Invariante aus
   [ADR-0010](../decisions/0010-ein-chunk-invariante-evaluierungskorpus.md) — jedes seiner 1448
   Dokumente ergibt genau einen Chunk. Wo es keine zweite Chunk-Grenze gibt, kann eine Überlappung
   nichts bewirken. Läufe mit 0, 100 und 200 Token liefern folgerichtig über alle 121 Referenzfälle
   identische Kennzahlen (Hit Rate@5 0,5207 · MRR 0,4608 · nDCG@10 0,4453 · Recall@10 0,4896, in
   allen drei Läufen bis auf die letzte Stelle gleich). Eine belastbare Messung braucht Referenzfälle
   an mehrchunkigen Dokumenten; solange die fehlen, ist die Wahl fachlich begründet und ausdrücklich
   als ungemessen gekennzeichnet.

Diese Werte sind **je Installation konfigurierbar**. Im Zielbild kommt eine Festlegung je
Wissensbibliothek hinzu: Rechtsquellen, Besprechungsnotizen und Tabellenwerke vertragen nicht dieselbe
Zerlegung. Ob eine hausweite Voreinstellung dafür ausreicht, steht unter
[Offene Fragen](#offene-fragen--zukünftige-erweiterungen).

### Speicherung und Filterachse

Jeder Chunk führt neben seinem Text und seiner Vektordarstellung die Metadaten mit, die Retrieval und
Beleg brauchen: Dokument, Position im Dokument, Fundstellenangabe, Stand, Bibliotheks-Kennung und
Organisations-Kennung.

Die **Bibliotheks-Kennung ist einwertig** — jedes Dokument gehört zu genau einer Wissensbibliothek — und
dient als Filterachse der rechtebewussten Suche. Die Mehrfachverwendung eines Bestands wird eine Ebene
höher gelöst, indem dieselbe Bibliothek in mehreren Spaces bereitgestellt wird, und muss deshalb nicht je
Chunk vervielfacht werden.

**Führender Speicher ist die relationale Datenbank; der Suchindex ist abgeleitet.** Das bestimmt das
Sicherungsverfahren: Nach dem Einspielen einer Datenbanksicherung können indizierte Chunks auf
Bibliotheken verweisen, die inzwischen anders berechtigt oder gelöscht sind. Liegen Datenbank und Index
zusammen, entschärft sich das; liegen sie getrennt, nicht. Ein Konsistenzprüflauf gleicht beide Seiten ab
und meldet Abweichungen.

### Der Vektorspeicher: PostgreSQL mit pgvector, und sonst keiner

OPAA speichert Vektoren in **PostgreSQL mit pgvector**. Das ist der unterstützte Vektorspeicher, und er
ist der einzige. Es gibt bei der Installation keine Auswahl und keine Zusage, dass weitere hinzukommen.

Technisch läuft der Zugriff über die portable Vektorspeicher-Schnittstelle des eingesetzten Rahmenwerks,
einschließlich der rechtebewussten Filterung. Ein Wechsel auf einen anderen Vektorspeicher ist damit
möglich — aber er wird **nicht unterstützt, nicht geprüft und nicht dokumentiert**: kein
Integrationstest, kein Betriebsleitfaden, keine Unterstützung im Fehlerfall. Wer ihn dennoch vornimmt,
verlässt den unterstützten Stand.

**Warum diese Festlegung für ein Verwaltungsprodukt die richtige ist**

- **Die Betreiberin wählt den Vektorspeicher in aller Regel nicht selbst.** Wer OPAA in einem
  Behördenrechenzentrum oder ohne Netzanbindung betreibt, hat Vorgaben zu Datenbankbetrieb, Sicherung
  und Wiederherstellung — aber praktisch nie die Anforderung, gerade dieses Bauteil auszutauschen. Eine
  Wahlmöglichkeit, die niemand ausübt, ist kein Nutzen, sondern nur eine weitere Entscheidung, die bei
  der Einführung getroffen werden muss.
- **Jede zugesagte Variante kostet dauerhaft.** Eine Zusage ist erst eingelöst, wenn ein
  Integrationstest sie absichert, ein Betriebsleitfaden sie beschreibt und jemand im Fehlerfall dafür
  einsteht. Diesem laufenden Aufwand steht kein Bedarf gegenüber.
- **Weniger bewegliche Teile heißt weniger Nachweislast.** Ein Betreiber, der eine Prüfung besteht,
  weist Sicherung, Verschlüsselung und Zugriffswege *einer* Datenbank nach. Metadatenbestand und
  Vektorindex liegen ohnehin in derselben PostgreSQL-Instanz; ein zweites System zöge einen zweiten
  Nachweispfad, eine zweite Rechteprüfung und einen zweiten Wiederherstellungsplan nach sich.
- **Die Abstraktion bleibt trotzdem — sie kostet nichts.** Sie kommt aus dem Rahmenwerk und wird nicht
  von diesem Projekt gepflegt. Deshalb wird auch nichts ausgebaut: Es ändert sich das Versprechen, nicht
  der Code.

**Die bekannte Grenze:** [ADR-0002](../decisions/0002-mvp-technology-stack.md) benennt selbst, dass
pgvector bei sehr großen Beständen im Bereich von Millionen Vektoren an Grenzen stößt. Das wird hier
nicht verschwiegen, sondern als bekannter Punkt geführt. Tritt der Fall in einer realen Installation
ein, ist das eine neue Entscheidung mit eigenem ADR — keine Überraschung und kein stillschweigend
eingelöstes Versprechen.

Entschieden in [#348](https://github.com/criew/opaa/issues/348), festgehalten als Nachtrag in
[ADR-0014](../decisions/0014-produktausrichtung-oeffentliche-verwaltung.md).

---

## Deep Document Understanding

### Formaterkennung

Eingehende Dateien werden **anhand ihres tatsächlichen Inhalts erkannt**, nicht anhand der Endung. Das ist
keine Feinheit: In gewachsenen Ablagen tragen Dateien routinemäßig die falsche Endung, und ein als
Textdatei behandeltes Tabellenblatt erzeugt Chunks, die aussehen wie Zahlenkolonnen ohne Zusammenhang.

Aus dem erkannten Typ folgt die Verarbeitungsstrategie:

| Typ | Extraktion | Zerlegung |
|---|---|---|
| Textdokument mit Gliederung | Text samt Überschriftenebene | an Gliederungsgrenzen |
| Seitenlayout-Dokument | Text, Seiten, Absätze; bei Bildseiten Texterkennung | seitentreu, mit Seitenangabe je Chunk |
| Tabellenkalkulation | Blätter, Kopfzeilen, Zellbereiche | Blatt- und Bereichsweise, Kopfzeile je Chunk wiederholt |
| Präsentation | Folientexte samt Notizen | je Folie |
| Auszeichnungssprache | Struktur aus der Auszeichnung | an Abschnittsgrenzen |
| Strukturierte Daten | Datensätze samt Feldnamen | datensatzweise oder in Blöcken |
| Nachricht aus einem Postfach | Kopfdaten, Text, Anhänge einzeln | Nachricht und Anhänge getrennt |
| Bild oder gescannte Seite | Texterkennung, modellabhängig Bildverständnis | siehe unten |

Wird ein Typ nicht erkannt oder scheitert die Extraktion, wird das Dokument **übersprungen und gemeldet**,
nicht mit einem Notverfahren durchgereicht. Ein halb extrahiertes Dokument im Index ist schlimmer als ein
fehlendes: Es liefert Treffer, die niemand einordnen kann.

#### Welche Dateien OPAA verarbeitet

Die Typklassen oben beschreiben das Verfahren. Die erste Frage jeder Fachseite ist aber eine andere:
*Kann OPAA meine Dateien?* Deshalb hier die konkreten Formate je Typklasse, getrennt nach dem, was
heute läuft, und dem, was zum Zielbild gehört.

| Typklasse | Gebaut | Zielbild |
|---|---|---|
| Textdokument mit Gliederung | Markdown (`.md`), Klartext (`.txt`) | AsciiDoc (`.adoc`), reStructuredText |
| Seitenlayout-Dokument | PDF (`.pdf`), Word (`.docx`, `.doc`) | OpenDocument-Text (`.odt`), RTF |
| Tabellenkalkulation | — | Excel (`.xlsx`, `.xls`), OpenDocument (`.ods`), CSV |
| Präsentation | PowerPoint (`.pptx`) | OpenDocument (`.odp`) |
| Auszeichnungssprache | — | HTML, XML |
| Strukturierte Daten | — | JSON, CSV, XML-Datensätze |
| Nachricht aus einem Postfach | — | Einzelnachrichten (`.eml`, `.msg`), Postfachexporte (MBOX, PST) |
| Bild oder gescannte Seite | — | Rasterbilder (`.png`, `.jpg`, `.tiff`), Bild-PDF über Texterkennung |

Die Liste gilt für **alle dateibasierten Aufnahmewege gleichermaßen** — den Weg über ein Verzeichnis im
Dateisystem, den Weg über ein Webverzeichnis und Anlagen eines Feed-Eintrags (siehe
[Wissensquellen und Konnektoren](./knowledge-sources.md#webverzeichnis-gebaut)).
Sie ist an genau einer Stelle im Code geführt (`SupportedDocumentFormats`); dieselbe Datei wird deshalb
unabhängig davon, wie sie hereinkommt, gleich behandelt — und zwar anhand ihres **tatsächlichen,
per Tika erkannten Inhalts (gebaut, #404)**, nicht anhand ihres Namens. Die Endung geht nur noch als
Hinweis ein: Weicht sie vom erkannten Inhalt ab, wird das im Protokoll des Indizierungslaufs vermerkt
(Kategorie `FORMAT_MISMATCH`) — das Dokument wird dennoch indiziert, denn eine falsche Endung in einem
gewachsenen Bestand ist typischerweise ein Beschriftungsfehler, kein Grund, einen sonst lesbaren Inhalt
zu verwerfen. Für den manuellen Upload gilt eine bewusste Ausnahme: Wer eine Datei über die Oberfläche
hochlädt, hat Datei und Namen in derselben Handlung selbst gewählt — eine Abweichung dort wird abgewiesen,
nicht nur vermerkt (#435).

**Bei Markdown und Klartext bleibt die Endung Teil der Entscheidung, nicht nur ein Hinweis.** Tika kann
am Inhalt allein nicht erkennen, ob eine lesbare Textdatei als Markdown, Klartext oder etwas fachfremdes
(eine CSV-Exportdatei, eine Logdatei, Quellcode) gemeint war — jede dieser Dateien liest sich als
schlichter Text. Ohne die Endung als Unterscheidungsmerkmal würde deshalb jede lesbare Textdatei,
gleich wie benannt, in den zugelassenen Bestand aufgenommen — eine stille Erweiterung, die diese
Umstellung ausdrücklich nicht wollte. Für diese beiden Typen gilt deshalb: Der Inhalt muss lesbarer Text
sein, **und** die Datei muss bereits `.md` oder `.txt` heißen — eine lesbare Textdatei namens `README`
oder `export.csv` wird abgewiesen, dieselben Bytes unter `notiz.txt` angenommen. Für die eindeutig
erkennbaren Formate (PDF, Word, PowerPoint) gilt diese Einschränkung nicht: Ihr Byte-Muster ist
eindeutig genug, dass die Endung dort wirklich nur noch Hinweis ist.

Beim **RSS-Anlagenweg** kommt eine zweite, davon unabhängige Einschränkung hinzu: Welche Verweise einer
Detailseite überhaupt als Anlage in Frage kommen, entscheidet weiterhin die Endung im Link — man kann
nicht jeden Verweis einer Seite herunterladen, nur um seinen Inhalt zu prüfen. Diese Vorauswahl verlangt
inzwischen nur noch irgendeine Dateiendung, nicht mehr eine der sechs zugelassenen; erst der
heruntergeladene Inhalt der so gefundenen Kandidaten entscheidet dann, wie auf den anderen beiden Wegen,
über Zulassung und eine etwaige Abweichungsmeldung.

Der **Feed als Quelle** (siehe
[Wissensquellen und Konnektoren](./knowledge-sources.md#feeds-als-quelle-gebaut)) ist davon in einem
Punkt ausgenommen: Der Artikeltext einer Feed-Detailseite ist bereits extrahierter Text, keine Datei —
er durchläuft deshalb weder die Formaterkennung noch diese Liste, sondern geht direkt in Zerlegung und
Einbettung ein. `.html` steht bewusst **nicht** in der Liste oben; es wäre auch kein zutreffender
Eintrag, weil nie eine ganze HTML-Datei indiziert wird, sondern nur ihr bereinigter Hauptinhalt. Anlagen,
die der Feed-Weg auf derselben Detailseite findet, sind davon nicht betroffen: Sie sind echte Dateien und
durchlaufen dieselbe Liste wie jede andere.

Eine Einschränkung des gebauten Stands gehört dazu, weil sie sonst überrascht:

**Die Liste oben bleibt eine bewusste fachliche Auswahl, keine Grenze der Extraktion.** Der eingesetzte
Extraktor beherrscht weit mehr Formate, als die Liste zulässt — er meldet auf dem aktuellen Classpath 245
unterstützte Medientypen. Zugelassen ist nur, was oben unter "Gebaut" steht; alles andere wird abgewiesen,
selbst wenn Tika es lesen könnte. Eine Erweiterung der Liste ist eine eigene fachliche Entscheidung, kein
Nebeneffekt der in #404 umgesetzten Inhaltserkennung.

Dateien mit nicht zugelassenem Format werden **nicht stillschweigend übersprungen**: Sie zählen zur
Gesamtzahl des Indizierungslaufs, erscheinen dort als übersprungen und werden namentlich protokolliert.
Ohne diesen Ausweis könnte eine Fachseite einen Bestand für erschlossen halten, von dem ein Teil nie im
Index angekommen ist.

### Erklärbares und darstellbares Chunking

Die Zerlegung eines Dokuments entscheidet darüber, was gefunden wird und wie ein Beleg aussieht. Sie ist
damit prüfrelevant und wird nicht als interne Optimierung behandelt.

**Erklärbar** heißt: Zu jedem Chunk ist festgehalten, nach welcher Regel er entstanden ist — an welcher
Gliederungsgrenze getrennt wurde, ob die Größenbegrenzung eine weitere Teilung erzwungen hat, wie viel
Überlappung zum Nachbarn besteht und welche Kopfzeilen oder Überschriften mitgeführt wurden.

**Darstellbar** heißt: Die Zerlegung lässt sich ansehen. In der Dokumentansicht sind die Chunkgrenzen dem
Original überlagert; ein Chunk lässt sich anwählen und zeigt seinen Text, seine Fundstellenangabe und
seine Nachbarn. Wer eine schlechte Antwort untersucht, sieht damit sofort, ob das Retrieval oder die
Zerlegung die Ursache war — etwa wenn eine Tabelle mitten in einer Zeile getrennt wurde oder eine
Definition von ihrer Überschrift abgeschnitten ist.

Der Nutzen ist doppelt. Für die Fachseite ist es eine Vertrauensfrage: Ein Beleg, dessen Zuschnitt man
nachvollziehen kann, ist ein anderer Beleg als eine Textkachel unbekannter Herkunft. Für den Betrieb ist
es das wirksamste Werkzeug zur Fehlersuche im Retrieval.

Grenzen der Zerlegung sind **fassungsgebunden**: Ändert sich ein Dokument, entsteht eine neue Zerlegung,
und ältere Antworten verweisen weiterhin auf die Fassung, mit der sie erzeugt wurden. Ein Beleg, der
stillschweigend auf eine neuere Fassung zeigt, wäre kein Beleg.

### Bild- und Handschriftenverständnis

Gescannte Seiten, Fotografien von Aushängen, Screenshots, Diagramme und handschriftliche Randnotizen
kommen in Verwaltungsvorgängen regelmäßig vor. OPAA behandelt sie zweistufig:

1. **Texterkennung** an Bildseiten ist Grundausstattung und läuft ohne Sprachmodell. Sie liefert
   maschinellen Text mit Seitenbezug.
2. **Bildverständnis** — Beschreibung von Diagrammen, Erfassung von Formularstrukturen, Lesen von
   Handschrift — ist eine **Fähigkeit des eingesetzten Modells** und damit nicht in jeder Installation
   verfügbar.

Diese Abhängigkeit wird ausdrücklich benannt statt kaschiert. Wo ein Haus nur lokale Modelle ohne
Bildverständnis betreibt, entfällt die Fähigkeit — sie wird nicht durch einen Cloud-Aufruf ersetzt, denn
das wäre genau die Umgehung, die die Modellvorgaben verhindern sollen (siehe
[Modelle und zentrale Steuerung](./llm-integration.md)).

Daraus folgen drei Regeln:

- **Verfügbarkeit ist sichtbar.** Die Systemverwaltung sieht je Modell, welche Fähigkeiten es mitbringt;
  Nutzende sehen am Dokument, ob es bildverstehend ausgewertet wurde.
- **Handschriftliches wird gekennzeichnet.** Aus Handschrift gewonnener Text ist unsicherer als gedruckter
  und wird als solcher markiert — im Beleg und in der Konfidenz.
- **Nichts wird stillschweigend ausgelassen.** Eine Bildseite ohne verfügbares Verfahren wird als nicht
  ausgewertet vermerkt, damit niemand annimmt, der Vorgang sei vollständig erschlossen.

---

## Deep Research

Manche Fragen sind keine Nachschlagefragen. „Wie hat sich die Verwaltungsauffassung zu diesem Sachverhalt
über die letzten Jahre entwickelt?" lässt sich nicht mit fünf Passagen beantworten.

Deep Research ist dafür ein eigener Betriebsmodus: OPAA zerlegt die Fragestellung in Teilfragen, sucht zu
jeder eigenständig, prüft die gefundenen Stellen gegeneinander und erzeugt einen **gegliederten Bericht
mit durchgehender Fundstellenangabe**. Der Ablauf ist sichtbar — welche Teilfragen gestellt, welche
Bestände durchsucht und welche Stellen verworfen wurden.

Merkmale:

- **Derselbe Rechtekontext** wie jede andere Suche; Deep Research eröffnet keinen zusätzlichen Zugriff.
- **Die Belegvalidierung gilt auch hier** (siehe [Zitierzwang](#zitierzwang)): Jeder Beleg im Bericht wird
  gegen die für den jeweiligen Teilbericht abgerufenen Fundstellen geprüft, ein ungültiger Beleg wird
  gekennzeichnet statt entfernt. Die je Kapitel zerlegte Ausweisung unbelegter Abschnitte war Teil des
  am 21.08.2026 verworfenen Verweigerungsapparats und ist nicht gebaut.
- **Widersprüche werden benannt statt geglättet.** Wenn zwei Fundstellen einander widersprechen, ist das
  ein Ergebnis und keine Störung — der Bericht stellt beide dar.
- **Das Ergebnis ist ein Artefakt im Space** und unterliegt dessen Regeln zu Sichtbarkeit und
  Aufbewahrung.
- **Laufzeit und Verbrauch sind spürbar** und werden vorab angezeigt; der Modus wird ausdrücklich gewählt
  und läuft nicht beiläufig an.

**Phase 2.**

---

## Qualitätssicherung

### Messung statt Eindruck

Ob eine Änderung an Chunking, Modell oder Reranking die Suche besser macht, ist ohne Messung nicht
entscheidbar. Der Messaufbau — Referenzkorpus, Golden Dataset, Kennzahlen und das Fehlerkriterium in der
Bauprüfung — ist eigenständig beschrieben in
[Suchqualität messbar machen](./search-quality-evaluation.md) und in den ADRs
[0011](../decisions/0011-search-quality-evaluation-harness.md),
[0012](../decisions/0012-messvertrag-retrieval-harness.md) und
[0013](../decisions/0013-fehlerkriterium-retrieval-regression.md).

Für diese Spezifikation ist entscheidend: **Jede Änderung an der Retrieval-Kette wird gegen dieselben
Referenzfälle gemessen wie die vorige.** Ein Wechsel des Einbettungsmodells, eine neue
Zerlegungsstrategie oder ein anderes Reranking sind Eingriffe mit Regressionsrisiko und werden als solche
behandelt.

### Rückmeldung aus dem Betrieb

Nutzende bewerten Antworten und einzelne Treffer. Die Rückmeldung fließt in zwei Richtungen:

- **In die Kuratierung** — welche Bestände liefern regelmäßig unbrauchbare Treffer, welche Dokumente
  fehlen, wo ist die Ablage veraltet. Das ist der Regelweg und wirkt sofort.
- **In die Relevanzabstimmung** — Gewichte der Zusammenführung, Schwellen, Auswahl der Signale.

Was ausdrücklich **nicht** geschieht: eine automatische Anpassung der Rangfolge aus laufendem
Nutzerverhalten. Sie wäre nicht reproduzierbar, ließe sich nicht gegen Referenzfälle prüfen und würde in
einem Bereich, in dem Antworten zurechenbar sein müssen, eine unerklärbare Veränderlichkeit einführen.
Rückmeldungen sind Eingangsgröße für eine bewusste Änderung, nicht deren Auslöser.

Die Auswertung von Rückmeldungen erfolgt **aggregiert und ohne personenbezogenen Auswertungspfad** (siehe
[Mitbestimmung und Personalvertretung](./spaces-and-assets.md#mitbestimmung-und-personalvertretung)).

---

## Wissensgraph als spätere Ausbaustufe

Mehrstufige Fragen — „welche Regelung hat diese Verfügung abgelöst, und welche Verweise darauf sind
dadurch überholt?" — sind mit Ähnlichkeitssuche schlecht zu beantworten. Sie brauchen die Beziehungen
zwischen Dokumenten, nicht nur deren Inhalt.

Ein Wissensgraph, der Entitäten und Beziehungen aus dem Bestand ableitet und das Retrieval ergänzt, ist
deshalb vorgesehen — **als Ergänzung, nicht als Ersatz**, und in **Phase 3**. Die Gründe für die späte
Einordnung sind sachlich:

- Der Aufbau ist nur so gut wie die Extraktion der Beziehungen; ein falsch abgeleiteter Graph erzeugt
  Belege, die keine sind.
- Der Nutzen entsteht erst bei gepflegten, umfangreichen Beständen — vorher überwiegt der Pflegeaufwand.
- Die Rechteprüfung muss auch im Graphen zur Abfragezeit greifen. Ein Beziehungsnetz, das Kanten zu nicht
  lesbaren Dokumenten zeigt, verrät deren Existenz und wäre ein Rückschritt gegenüber der heutigen
  Zusicherung.

Bis dahin decken hybride Suche und Deep Research den größten Teil dieser Fragen ab, wenn auch mit mehr
Aufwand je Frage.

---

## Weitere Fähigkeiten des Zielbilds

Vier Fähigkeiten gehören zum Zielbild, tragen den Kern aber nicht und sind deshalb später eingeordnet.
Sie stehen hier, damit über sie entschieden werden kann — nichts davon ist gebaut.

### Mehrsprachige Verarbeitung — Phase 2

Bestände in mehreren Sprachen sind in der Verwaltung der Normalfall, sobald europäische Vorgaben,
Normen oder Herstellerdokumentation im Spiel sind. Vorgesehen ist: Jedes Dokument führt seine erkannte
Sprache mit, das Einbettungsmodell muss die Sprachen des Bestands abdecken, eine Frage in einer Sprache
findet Fundstellen in einer anderen, und die Antwort gibt den Auszug **im Wortlaut des Originals**
wieder. Eine Übersetzung des Belegs käme nicht in Betracht — ein übersetztes Zitat ist kein Zitat.

### Extraktion von Dokumentmetadaten — Phase 2

Aus jedem Dokument werden Titel, verantwortliche Stelle, Erstellungs- und Änderungsdatum, Dokumentart
(Verfügung, Vermerk, Protokoll, Richtlinie) und Sachbegriffe abgeleitet. Der Nutzen liegt in der
Einschränkung der Suche („nur Dienstanweisungen", „nur aus diesem Jahr") und in der Einordnung eines
Belegs. Die Grenze ist bekannt: Abgeleitete Metadaten sind Vermutungen. Sie werden deshalb als
abgeleitet gekennzeichnet und dürfen keine Rechtefrage entscheiden.

### Zwischenspeicherung wiederkehrender Fragen — offen, keine Phase

Häufig gestellte Fragen mehrfach vollständig zu beantworten, kostet Zeit und Verbrauch. Eine
Zwischenspeicherung der Antwort verträgt sich aber schlecht mit rechteabhängigen Ergebnissen: Zwei
Personen mit unterschiedlichem Leserecht müssen unterschiedliche Antworten erhalten, und ein
Zwischenspeicher, der das nicht abbildet, ist ein Leck. Hinzu kommt die Ungültigkeit bei jeder Änderung
im Bestand. Die Fähigkeit bleibt deshalb ausdrücklich **unentschieden** und steht unter
[Offene Fragen](#offene-fragen--zukünftige-erweiterungen).

### Ablauf und Archivierung von Dokumenten — Phase 2

Ein Dokument kann seine Gültigkeit verlieren, ohne aus der Quelle zu verschwinden. Vorgesehen sind
Zustände von „aktiv" über „archiviert" bis „abgelaufen", die Wirkung auf die Suche haben — beschrieben
im [Lebenszyklus der Dokumente](./knowledge-sources.md#lebenszyklus-der-dokumente). Für das Retrieval
folgt daraus: Archivierte Fundstellen werden gefunden, aber als älterer Stand gekennzeichnet;
abgelaufene werden nicht mehr gefunden, bleiben für den Nachweis älterer Antworten aber auffindbar.

---

## Leistungs- und Skalierungsziele

Die folgenden Werte sind **Zielwerte für die Auslegung**, keine Messergebnisse und keine Zusicherung
für eine bestimmte Installation. Sie hängen an Hardware, Modellwahl und Bestandsgröße. Ihr Zweck ist,
eine Grundlage für die Auslegung und für die Bewertung einer Messung zu geben — ohne sie ist jede
Messung ein Zahlenwert ohne Bezugsgröße.

### Abfragelatenz

| Abschnitt | Zielwert (P95) |
|---|---|
| Hybride Suche einschließlich Rechtefilter | unter 500 ms |
| Reranking der Kandidatenmenge | unter 200 ms zusätzlich |
| Gesamte Retrieval-Zeit bis zur Übergabe an das Antwortmodell | unter 1 Sekunde |

Die Zeit des Antwortmodells ist darin **nicht** enthalten; sie hängt am eingesetzten Modell und wird in
[Modelle und zentrale Steuerung](./llm-integration.md) behandelt. Die Rechteprüfung erzeugt keinen
eigenen Abschnitt in dieser Rechnung, weil sie als Filter in der Suche selbst sitzt.

### Indizierungsdurchsatz

Zielgrößen für einen Erstlauf auf einer Installation üblicher Auslegung:

| Bestandsgröße | Zielwert Erstlauf |
|---|---|
| einige hundert Dokumente | Minuten |
| einige zehntausend Dokumente | wenige Stunden, planbar über Nacht |
| Hunderttausende Dokumente | über mehrere Läufe verteilt, mit sichtbarem Fortschritt |

Bestimmend ist fast immer der Einbettungsschritt, nicht die Extraktion. Ein Erstlauf ist deshalb
ausdrücklich **nachrangig** eingeplant und darf die tägliche Aktualisierung gepflegter Bestände nicht
verdrängen (siehe [Zeitpläne und Vorrang](./knowledge-sources.md#vorrang)).

### Skalierungsverhalten

- **Bestandsgröße:** Die Suche muss bei wachsendem Bestand flach bleiben; ein Suchindex, dessen Antwortzeit
  linear mit der Dokumentzahl wächst, ist die falsche Auslegung.
- **Gleichzeitige Nutzung:** Die Auslegung zielt auf gleichzeitige Nutzung durch ein ganzes Haus, nicht
  durch einzelne Fachreferate. Begrenzend ist regelmäßig der Modelldienst, nicht der Suchindex.
- **Mehrere Quellen gleichzeitig:** Indizierungsläufe verschiedener Konnektoren laufen nebeneinander,
  begrenzt durch die für die Indizierung bereitgestellten Arbeitsfäden und den Schonzeitraum je
  Quellsystem.
- **Trennung der Lasten:** Ein laufender Erstlauf darf die Antwortzeit der Suche nicht spürbar
  verschlechtern. Das ist die härteste dieser Anforderungen und zugleich die, die bei Einführungen am
  häufigsten verfehlt wird.

---

## Verarbeitungskette im Überblick

```
Dokument aus Upload oder Konnektor           → knowledge-sources.md
        ↓
Formaterkennung anhand des Inhalts
        ↓
Extraktion je Typ  (Text · Layout · Tabelle · Texterkennung · Bildverständnis)
        ↓
Zerlegung mit Begründung und Fundstellenbezug
        ↓
Einbettung  (Modell nach zentraler Vorgabe)  → llm-integration.md
        ↓
Ablage: Chunk + Vektor + Metadaten + Bibliotheks-Kennung
        ↓
─────────── Abfragezeit ───────────
        ↓
Suchbereich bestimmen  (lesbare Bibliotheken ∩ Kontext)  → spaces-and-assets.md
        ↓
Hybride Suche  →  Zusammenführung  →  Reranking
        ↓
Antwort mit Fundstellen, ungültige Belege gekennzeichnet
```

Die Aktualisierung läuft **inkrementell**: Nur neue und geänderte Dokumente werden verarbeitet, geänderte
Chunks ersetzt, entfernte Dokumente aus dem Index genommen. Eine vollständige Neuindizierung ist
ausdrücklich auslösbar und bei einem Wechsel des Einbettungsmodells zwingend.

---

## Integrationspunkte

- **[Wissensquellen und Konnektoren](./knowledge-sources.md)** — liefert die Dokumente, bestimmt Zeitpläne
  und Lebenszyklus; dieses Dokument setzt beim eingehenden Dokument an.
- **[Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md)** — Wissensbibliothek als Filterachse,
  Suchbereich je Chatart, Durchsetzung zur Abfragezeit, Umgang mit geteilten Ergebnissen.
- **[Modelle und zentrale Steuerung](./llm-integration.md)** — Einbettungs-, Rerank- und Antwortmodell,
  Modellvorgaben als Obergrenze, Fähigkeitsabhängigkeit des Bildverständnisses.
- **[Suchqualität messbar machen](./search-quality-evaluation.md)** — Referenzkorpus, Kennzahlen und
  Regressionsprüfung für jede Änderung an dieser Kette.
- **[Zugangskontrolle](./access-control.md)** — Protokollierung von Abfragen, Belegen und Verweigerungen.
- **[Benutzer-Frontends](./user-frontends.md)** — Darstellung von Fundstellen, Konfidenz und
  Chunk-Ansicht.

---

## Geklärte Fragen

Entscheidungen, die bereits getroffen sind. Sie stehen hier, damit sie nicht in einem Jahr als neue
Idee wieder aufgemacht werden.

- **Hybrides Retrieval — ja.** Vektorsuche allein trägt in der Verwaltung nicht, weil Aktenzeichen,
  Paragrafen und Erlassnummern exakt getroffen werden müssen. Vektor- und Volltextsuche laufen
  nebeneinander, ihre Listen werden rangbasiert zusammengeführt (siehe
  [Hybride Suche](#hybride-suche)).
- **Reranking als eigener Schritt — ja.** Die zusammengeführte Liste ist auf Abdeckung optimiert; die
  Präzision entsteht erst durch die gemeinsame Bewertung von Frage und Passage. Über welches Modell
  das läuft, ist offen.
- **Dokumentenversionierung — ja.** Die Abfolge der Fassungen bleibt erhalten. Für das Retrieval folgt
  daraus, dass Chunkgrenzen und Belege **fassungsgebunden** sind: Eine ältere Antwort verweist
  weiterhin auf die Fassung, mit der sie erzeugt wurde. Ein Beleg, der stillschweigend auf eine neuere
  Fassung zeigt, wäre kein Beleg. Der Weg dorthin hängt am Upload-Verfahren und ist in
  [Wissensquellen und Konnektoren](./knowledge-sources.md#geklärte-fragen) mit **Issue #119** erfasst.
- **Nur die deterministische Belegprüfung wird gebaut, kein Zwangs- und Verweigerungsapparat darüber —
  ja.** Verweigerung, Schalter am Space und die inhaltliche Deckungsprüfung (vormals „Stufe 2") wurden am
  21.08.2026 verworfen, nicht aufgeschoben (siehe [Zitierzwang](#zitierzwang), Schnitt entschieden in
  [#354](https://github.com/criew/opaa/issues/354)).
- **Ein Vektorspeicher, und zwar PostgreSQL mit pgvector — ja.** Austauschbare Vektorspeicher werden
  nicht zugesagt. Der Zugriff läuft zwar über eine portable Schnittstelle des Rahmenwerks, ein Wechsel
  wird aber nicht unterstützt, nicht geprüft und nicht dokumentiert (siehe
  [Der Vektorspeicher](#der-vektorspeicher-postgresql-mit-pgvector-und-sonst-keiner), entschieden in
  [#348](https://github.com/criew/opaa/issues/348)).

---

## Offene Fragen / Zukünftige Erweiterungen

- Soll ein Zwangs- und Verweigerungsapparat über die Belegvalidierung hinaus doch noch gebaut werden — als
  Schalter am Space, an der Wissensbibliothek verankert, oder in anderer Form? Der Maintainer hat das am
  21.08.2026 verworfen, weil das Modell fehlende Belege bereits selbst kommuniziert und die Validierung
  bereits sicherstellt, dass vorhandene Belege echt sind ([#387](https://github.com/criew/opaa/issues/387),
  [#388](https://github.com/criew/opaa/issues/388)). **Wieder aufzumachen, wenn sich diese Einschätzung im
  Betrieb als falsch erweist** — der Auslöser wäre ein Fall, in dem ein haftungskritischer Bestand ohne
  erkennbaren fehlenden Beleg zu einer falschen Auskunft geführt hat.
- Soll auch ein **Agent** seine Belege auf dieselbe Weise validieren lassen, unabhängig davon, wo er
  läuft? Fachlich naheliegend — ein Agent für Auskünfte mit Außenwirkung ist genau der Fall — und in
  [Agenten, Prompts und Werkzeuge](./agents-and-tools.md) bereits vorausgesetzt. Erst zu entscheiden, wenn
  es Agenten gibt.
- Die inhaltliche **Deckungsprüfung** (ob die zitierte Fundstelle die Aussage tatsächlich trägt, vormals
  „Stufe 2") wurde am 21.08.2026 mit derselben Begründung verworfen
  ([#389](https://github.com/criew/opaa/issues/389), geschlossen) — ohne den Verweigerungsapparat, den sie
  hätte absichern sollen, fehlt ihr die Grundlage. Wieder aufzumachen, falls der Verweigerungsapparat
  selbst wieder aufgemacht wird.
- Soll der Beleg zusätzlich den **verwendeten Wortlaut** mitführen, damit sich deterministisch prüfen
  lässt, ob die zitierte Passage überhaupt so im Chunk steht? Das wäre eine Verschärfung von Stufe 1 ohne
  Modellaufruf, verlängert aber die Antwort und ist bei sinngemäßer Wiedergabe wirkungslos.
- Welche Schwelle trennt „belegt" von „nicht feststellbar", und wird sie je Bestand gesetzt? Eine
  hausweite Zahl passt selten auf Rechtsquellen und Besprechungsnotizen zugleich.
- Läuft das Reranking über ein eigenes Modell oder über das Antwortmodell? Ein eigenes Modell ist
  genauer und billiger, erhöht aber die Zahl der zu betreibenden Modelle.
- Wie werden sehr große Einzeldokumente behandelt, bei denen schon die Zerlegung den Index dominiert?
- Soll die Chunk-Ansicht allen Nutzenden offenstehen oder nur Rollen mit Kuratierungsauftrag?
- Wie werden mehrere Fassungen desselben Dokuments im Retrieval behandelt — nur die geltende, alle mit
  Kennzeichnung, oder eine ausdrückliche Auswahl durch die Fragestellerin?
- Zwischenspeicherung von Antworten auf wiederkehrende Fragen: spart Aufwand, verträgt sich aber schlecht
  mit rechteabhängigen Ergebnissen und mit Beständen, die sich laufend ändern.
- Werden Chunk-Größe, Überlappung, `top-k` und Ähnlichkeitsschwelle hausweit gesetzt oder je
  Wissensbibliothek? Je Bibliothek ist fachlich richtig, vervielfacht aber die Zahl der Größen, die
  gegen die Referenzfälle gemessen werden müssen.
- Bleiben **mehrsprachige Verarbeitung** und **Extraktion von Dokumentmetadaten** im Zielbild? Beide
  waren im früheren Bestand als Erweiterung geführt und sind hier unverändert übernommen. Für die
  Mehrsprachigkeit spricht der reale Bestand; gegen die Metadatenextraktion spricht, dass abgeleitete
  Angaben genau die Scheingenauigkeit erzeugen, die diese Spezifikation sonst vermeidet.
- Soll die heutige **Endungsliste** durch die im Zielbild beschriebene Inhaltserkennung ersetzt und
  zwischen den Indizierungswegen vereinheitlicht werden? Solange zwei Listen bestehen, hängt es vom
  Weg ab, ob eine Datei aufgenommen wird.

---

## Erfolgs-Metriken

- **Belegdeckung** — Anteil der Antworten mit mindestens einer gültigen Quellenangabe
  (`SourceReference.cited = true` und `citationValid = true`). Bewusst nicht „Anteil der tragenden
  Aussagen mit Fundstelle": Die dafür nötige Abschnittszerlegung mit Negativliste wurde am 21.08.2026
  verworfen (siehe [Zitierzwang](#zitierzwang)) und ist ohne sie nicht messbar.
- **Anteil ungültiger Belege** — Anteil der Antworten mit mindestens einem Beleg, der die deterministische
  Validierung nicht besteht (#386, heute als Log-Information erfasst). Ein durchgängig hoher Anteil
  deutet auf ein Modell hin, das die Belegform nachahmt, statt echte Fundstellen zu zitieren.
- **Retrieval-Kennzahlen** gegen das Golden Dataset, gemessen nach dem in
  [ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md) festgelegten Messvertrag.
- **Prüfquote der Belege** — wie oft Nutzende die Sprungmarke tatsächlich aufrufen; ein Beleg, den
  niemand öffnen kann, ist keiner.
- **Zeit bis zur belegten Auskunft** im Vergleich zur bisherigen Recherche.
- **Anteil der Dokumente, deren Zerlegung beanstandet wurde**, als Frühindikator für
  Verarbeitungsprobleme.
