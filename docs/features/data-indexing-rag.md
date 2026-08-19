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
2. **Zitierzwang ist ein schaltbarer Modus**, kein Dauerzustand und keine Formulierungsbitte an das
   Modell. Ist er aktiv und findet sich kein belegender Treffer, verweigert OPAA die Antwort. Geschaltet
   wird er **am Space**, verschärfbar durch eine Systemvorgabe; geprüft wird zunächst nur die **Form** des
   Belegs, nicht seine inhaltliche Deckung (siehe [Zitierzwang](#zitierzwang)).
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

Im Zitierzwang gilt: **keine belegte Quelle, keine Antwort.** Findet die Suche keinen Treffer oberhalb der
festgelegten Schwelle, oder lässt sich eine Teilaussage nicht auf eine Fundstelle stützen, antwortet OPAA
mit „nicht feststellbar" und benennt, wonach gesucht wurde.

Drei Punkte machen den Unterschied zwischen einer Zusicherung und einer Bitte an das Modell:

1. **Die Prüfung liegt außerhalb des Modells.** Ein Systemprompt, der um Zitate bittet, ist keine Garantie.
   OPAA prüft nach der Erzeugung, ob jede tragende Aussage einer gelieferten Fundstelle zugeordnet ist,
   und hält die Antwort zurück, wenn das nicht der Fall ist.
2. **Teilweise Belegbarkeit wird ausgewiesen, nicht geglättet.** Belegte Teile werden ausgegeben, nicht
   belegte ausdrücklich als nicht feststellbar gekennzeichnet. Eine Antwort, aus der stillschweigend
   herausfällt, was nicht belegbar war, wäre die schlechtere Auskunft.
3. **Die Verweigerung ist ein Ergebnis, kein Fehler.** Sie wird als solche protokolliert und zählt in der
   Auswertung nicht als Störung. Wo „nicht feststellbar" als Ausfall gemessen wird, entsteht Druck, die
   Schwelle zu senken.

#### Zwei Stufen, und nur die erste gilt jetzt

Die Belegprüfung zerfällt in zwei Stufen, die verschieden viel zusichern und verschieden viel kosten. Sie
werden **getrennt entschieden und getrennt gebaut**, weil sonst die billige und wirksame Prüfung auf die
teure und unsichere warten müsste.

| | **Stufe 1 — Formprüfung** | **Stufe 2 — Deckungsprüfung** |
|---|---|---|
| Frage | Zeigt der Beleg auf eine tatsächlich abgerufene Fundstelle? | Trägt die zitierte Fundstelle die Aussage inhaltlich? |
| Verfahren | deterministischer Abgleich gegen die Übergabemenge, kein zusätzlicher Modellaufruf | zweiter Modelldurchlauf je Antwort |
| Ergebnis | Zusicherung — gleiche Eingabe, gleiches Urteil | Wahrscheinlichkeit — das prüfende Modell irrt ebenfalls |
| Kosten | vernachlässigbar | Zeit und Rechenleistung je Antwort |
| Stand | **entschieden, Phase 1** | **eigener Vorgang, noch nicht entschieden** |

**Stufe 1 gilt jetzt.** Sie schließt die größte Lücke zum niedrigsten Preis: Heute wird nur geprüft, ob
das Belegmuster überhaupt vorkommt — ein Modell, das die Form nachahmt, erzeugt damit einen Beleg, der
auf nichts zeigt.

**Stufe 2 ist ein eigener Vorgang.** Sie braucht einen zweiten Modelldurchlauf, kostet Zeit und
Rechenleistung je Antwort und verwandelt den Zitierzwang von einer Zusicherung in eine Wahrscheinlichkeit.
Sie ist außerdem ohne den Messaufbau aus [Suchqualität messbar
machen](./search-quality-evaluation.md) nicht bewertbar: Eine Prüfung, deren Trefferquote niemand kennt,
ist keine Prüfung, sondern eine zweite Meinung. Die Entscheidung darüber wird als eigene Vorlage
vorbereitet.

#### Was Stufe 1 prüft

Drei Bedingungen, alle deterministisch, alle vor der Ausgabe:

1. **Jeder Beleg zeigt auf eine übergebene Fundstelle.** Dokument-Kennung und Abschnittsnummer im Beleg
   müssen zu einem der Chunks gehören, die für **diese** Antwort abgerufen und an das Modell übergeben
   wurden. Auch die mitgeführte Dokumentbezeichnung muss zu dieser Kennung passen — ein Beleg, der die
   richtige Kennung mit dem falschen Dokumentnamen verbindet, ist irreführender als gar keiner. Belege,
   die diese Prüfung nicht bestehen, sind ungültig; sie werden nicht stillschweigend entfernt, sondern
   zählen als fehlender Beleg.
2. **Keine Fundstelle, keine Antwortgenerierung.** Ist die Menge der abgerufenen Chunks leer, wird
   verweigert, **bevor** das Antwortmodell überhaupt gerufen wird. Heute läuft die Generierung mit null
   Chunks weiter und erzeugt eine Antwort aus dem Modellwissen — im Zitierzwang genau der Fall, den er
   verhindern soll.
3. **Jede tragende Aussage führt einen gültigen Beleg.** Was „tragend" heißt, ist unten operationalisiert.

**Was Stufe 1 ausdrücklich nicht prüft:** ob die zitierte Fundstelle die Aussage **inhaltlich trägt**. Ein
formal gültiger Beleg ist der Nachweis, dass eine real abgerufene Passage benannt wurde — nicht der
Nachweis, dass sie das behauptete aussagt. Ein Modell kann eine korrekt bestehende Fundstelle an einen Satz
hängen, mit dem sie nichts zu tun hat, und Stufe 1 lässt das durch. Wer diesen Unterschied nicht kennt,
liest aus dem Zitierzwang eine Zusicherung heraus, die er in Stufe 1 nicht gibt. **Er verhindert erfundene
Belege, nicht falsche Schlüsse.**

#### Was „tragende Aussage" heißt

Der Begriff entscheidet über die Brauchbarkeit der ganzen Prüfung, und er ist ohne Bedeutungsverständnis
nicht scharf zu ziehen. Der Vorschlag arbeitet deshalb auf **Abschnittsebene** und über eine Negativliste:

- Die Antwort wird in Sinnabschnitte zerlegt — Absätze, Listenpunkte, Tabellenzeilen.
- Ein Abschnitt gilt als **tragend**, außer er fällt unter eine der benannten Ausnahmen: Anrede und
  Verabschiedung, Rückfrage an die fragende Person, Überschrift, Einleitungssatz einer Aufzählung, reine
  Wiedergabe der Frage, Angabe über den Suchvorgang selbst und ausdrücklich als „nicht feststellbar"
  gekennzeichnete Abschnitte.
- Jeder tragende Abschnitt muss **mindestens einen gültigen Beleg** enthalten. Fehlt er in einem
  Abschnitt, greift die Ausweisung nach Punkt 2 oben; fehlt er in allen, wird verweigert.

Abschnitt statt Satz aus zwei Gründen: Der Systemprompt verlangt den Beleg ohnehin am Ende des Satzes
**oder Absatzes**, der die Angabe verwendet, und eine verlässliche Satzgrenzenerkennung scheitert im
Verwaltungsdeutsch regelmäßig an Abkürzungen und Fundstellenangaben („§ 30 AO", „i. V. m.", „Az.").

**Die Unschärfe gehört benannt.** Die Negativliste ist eine Heuristik, und sie irrt in zwei Richtungen:

- **Belegverdünnung** — das Modell fasst mehrere Aussagen in einen langen Absatz und hängt einen Beleg
  ans Ende. Formal erfüllt, inhaltlich wertlos. Als Gegengewicht begrenzt Stufe 1 den Textumfang, den ein
  einzelner Beleg stützen darf, auf einen Absatz und setzt eine Obergrenze für dessen Länge — als
  Ausgangswert 1.000 Zeichen, gegen die Referenzfälle nachzujustieren. Das ist eine **Formregel, keine
  Deckungsregel**; die eigentliche Antwort auf die Belegverdünnung ist Stufe 2.
- **Unnötige Verweigerung** — ein rein überleitender Abschnitt wird als tragend gewertet und blockiert
  eine ansonsten belegte Antwort. Der teurere der beiden Fehler für die Nutzenden, und deshalb der, der
  in der Messung [Verweigerungsgüte](#erfolgs-metriken) sichtbar sein muss.

#### Wo der Schalter sitzt

**Entschieden: am Space, mit einer Systemvorgabe darüber.** Ein Space ist im Zitierzwang oder er ist es
nicht. Zusätzlich kann die Systemverwaltung ihn hausweit erzwingen; dann gilt er überall, unabhängig von
der Einstellung des einzelnen Space.

```
Zitierzwang aktiv  =  Systemvorgabe (erzwingend)
                   ∨  Einstellung des Space
```

Die Verrechnung läuft in derselben Richtung wie die [Modellvorgaben](./llm-integration.md): Es gibt genau
eine Richtung, und das ist Verschärfung. Ein Space kann den Zwang setzen, den die Systemvorgabe nicht
verlangt; er kann den nicht abschalten, den sie verlangt.

Der Space trägt den Schalter, weil er ohnehin bestimmt, in welchem Zusammenhang gearbeitet wird — Zweck,
Standard-Suchbereich, Aufbewahrung und Zurechnung hängen bereits dort. Ein Raum für Auskünfte mit
Außenwirkung und ein Raum für Vorüberlegungen sind verschiedene Räume, und der Modus folgt dieser
Trennung ohne eine neue Verwaltungsebene.

**Die Grenze dieser Wahl, ausgesprochen:** Der Zitierzwang ist **durch einen Raumwechsel umgehbar**.
Dieselbe Wissensbibliothek kann in einem zweiten Space ohne Zitierzwang bereitgestellt werden; dort
beantwortet OPAA dieselben Fragen aus demselben Bestand ohne Belegpflicht. Das ist genau die Schwäche, die
das Rechtemodell bei den Zugriffsrechten bewusst vermeidet, indem die Beschränkung an den **Daten** hängt
und nicht am Raum (siehe [Spaces, Assets und Zugangskontrolle](./spaces-and-assets.md)).

Für den Betrieb folgt daraus eine Pflicht, die das System nicht abnimmt: **Wer einen haftungskritischen
Bestand führt, muss dessen Bereitstellung in Spaces ohne Zitierzwang organisatorisch verhindern.** Das
System verhindert es nicht — es kennt die Anforderung des Bestands nicht. Wo diese Organisation nicht
zugetraut wird, bleibt nur die hausweite Systemvorgabe, die den Zwang dann auch dort erzwingt, wo er nur
bremst. Die Verankerung an der Wissensbibliothek wäre die stärkere Alternative und steht unter
[Offene Fragen](#offene-fragen--zukünftige-erweiterungen).

**Eine Voraussetzung fehlt heute:** Die Abfrage kennt den Space nicht. Sie läuft über alle für die
fragende Person lesbaren Wissensbibliotheken, ohne Kontext, aus dem sich ein Modus ableiten ließe. Der
Schalter am Space setzt deshalb voraus, dass die Abfrage einen Space-Bezug mitführt — das ist Teil des
Umsetzungsschnitts und keine Nebensache.

#### Was bei einer Verweigerung erscheint

„Nicht feststellbar" ohne Begründung ist unbrauchbar: Es bleibt offen, ob die Frage falsch gestellt war,
der Bestand fehlt oder das System klemmt. Die Verweigerung ist deshalb eine **Auskunft über den
Suchvorgang** und enthält vier Angaben:

| Angabe | Inhalt | Zweck |
|---|---|---|
| Wonach gesucht wurde | die tatsächlich verwendete Suchfrage, nicht nur die eingegebene | macht die Anreicherung aus dem Gesprächsverlauf sichtbar |
| Wo gesucht wurde | die Namen der durchsuchten Wissensbibliotheken | zeigt, ob der erwartete Bestand überhaupt im Suchbereich lag |
| Was gefunden wurde | Zahl der Treffer und Zahl der Treffer oberhalb der Belegschwelle | trennt „nichts gefunden" von „nichts Gutes genug gefunden" |
| Woran es lag | ein Grund aus einer festen, kurzen Liste | benennt den nächsten sinnvollen Schritt |

Die Gründe sind abschließend:

1. **„Kein Treffer über der Belegschwelle."** Die Suche lief, es blieb nichts Belegfähiges übrig.
2. **„Die erzeugte Antwort stützte sich nicht auf die gefundenen Stellen."** Es gab Fundstellen, aber die
   Antwort führte keinen oder keinen gültigen Beleg. Hier hilft ein erneuter Versuch, dort nicht.

**Die Meldung darf nicht verraten, was jemand nicht sehen darf.** Deshalb gilt: Genannt werden
ausschließlich Bestände, die die fragende Person ohnehin lesen darf. Über alles andere sagt die Meldung
nichts — auch nicht, wie viel es davon gibt. Wer auf keinen Bestand Zugriff hat und wer Zugriff hat, aber
nichts findet, bekommt in Grund 1 **denselben Text**; unterschieden wird nur über die Liste der eigenen
Bestände, die im ersten Fall leer ist. Damit bleibt die heutige bewusste Gleichbehandlung von „keine
Leserechte" und „nichts gefunden" erhalten (siehe
[Bindung der Aussage an die Fundstelle](#bindung-der-aussage-an-die-fundstelle) und
[#202](https://github.com/criew/opaa/issues/202)).

Eine Verweigerung wird **als Ergebnis** ausgeliefert, nicht als Fehler: reguläre Antwort mit
Verweigerungskennzeichen, damit sie im Verlauf steht, protokollierbar ist und in der Auswertung nicht als
Störung zählt.

#### Wechselwirkung mit der Ausgabe im Fluss

Die Prüfung setzt an der fertigen Antwort an. Erscheint der Text schrittweise, ist ein unbelegter Satz
bereits gelesen, wenn das Urteil fällt — Gelesenes ist nicht widerrufbar. Im Zitierzwang kann deshalb
entweder nicht im Fluss ausgegeben werden, oder der laufende Strom muss verworfen werden können.

Diese Frage wird **hier nicht entschieden**. Sie gehört zur Ausgabeform und steht mit ihren drei Optionen
und der Empfehlung in [Modelle und zentrale
Steuerung](./llm-integration.md#ausgabe-im-fluss). Solange die Ausgabe im Fluss nicht gebaut ist — und sie
ist es nicht —, entsteht der Konflikt praktisch nicht.

#### Umsetzungsschnitt

Der Schnitt ist in [#354](https://github.com/criew/opaa/issues/354) entschieden und in vier Vorgänge
zerlegt:

| Vorgang | Inhalt |
|---|---|
| [#386](https://github.com/criew/opaa/issues/386) | Belege gegen die abgerufenen Fundstellen prüfen — der deterministische Kern von Stufe 1 |
| [#387](https://github.com/criew/opaa/issues/387) | Verweigerung mit Auskunft über den Suchvorgang, ohne Rückschluss auf Unlesbares |
| [#388](https://github.com/criew/opaa/issues/388) | Schalter am Space und Systemvorgabe, mit Verrechnung und Space-Bezug der Abfrage |
| [#389](https://github.com/criew/opaa/issues/389) | Entscheidungsvorlage zur inhaltlichen Deckungsprüfung — Stufe 2, endet mit einer Vorlage, nicht mit Code |

### Konfidenz

Der Nutzen einer Konfidenzangabe hängt daran, dass sie eine beantwortbare Frage beantwortet. „0,73" tut
das nicht. OPAA weist deshalb **zwei getrennte Größen** aus:

| Größe | Frage | Grundlage |
|---|---|---|
| **Trefferqualität** | Wie gut passen die gefundenen Stellen zur Frage? | Bewertung nach dem Reranking, je Fundstelle |
| **Belegdeckung** | Wie viel der Antwort ist belegt? | Anteil der tragenden Aussagen mit Fundstelle |

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
eine Passage nicht mehr als Beleg taugt. Diese Schwelle ist zugleich die Schwelle des Zitierzwangs.

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
| **`top-k`** | 5 **(gebaut)** | Wie viele Belegstellen eine typische Frage braucht | Höhere Trefferwahrscheinlichkeit, aber mehr Rauschen im Antwortkontext und höherer Verbrauch |
| **Ähnlichkeitsschwelle** | 0,3 **(gebaut)** | Wie umgangssprachlich gefragt wird und wie homogen der Bestand ist | Weniger unpassende Treffer, aber mehr Fragen ohne Antwort — im Zitierzwang mehr Verweigerungen |
| **Bündelgröße der Einbettung** | 50 Chunks je Aufruf **(gebaut)** | Belastbarkeit des Einbettungsdienstes | Schnellere Läufe, aber Lastspitzen und größerer Speicherbedarf |
| **Wiederholversuche je Dokument** | 3 **(gebaut)** | Zuverlässigkeit von Quelle und Modelldienst | Weniger verlorene Dokumente, aber längere Läufe bei dauerhaft defekten Dateien |

Drei Zusammenhänge sind dabei wesentlich:

1. **Chunk-Größe und `top-k` hängen aneinander.** Beide bestimmen zusammen, wie viel Text in die
   Antwort eingeht. Wer die Chunks vergrößert, muss `top-k` in der Regel senken, sonst wächst der
   Antwortkontext über das hinaus, was das Modell verlässlich auswertet.
2. **Die Ähnlichkeitsschwelle ist zugleich die Schwelle des Zitierzwangs.** Sie zu senken, um weniger
   Verweigerungen zu erzeugen, verschiebt das Problem: Die Antworten werden dann auf schwächere Belege
   gestützt. Genau deshalb wird eine Verweigerung ausdrücklich nicht als Störung gemessen.
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

Die Liste gilt für **beide dateibasierten Aufnahmewege gleichermaßen** — den Weg über ein Verzeichnis im
Dateisystem und den Weg über ein Webverzeichnis (siehe
[Wissensquellen und Konnektoren](./knowledge-sources.md#webverzeichnis-gebaut)).
Sie ist an genau einer Stelle im Code geführt; dieselbe Datei wird deshalb unabhängig davon, wie sie
hereinkommt, gleich behandelt.

Der **Feed als Quelle** (siehe
[Wissensquellen und Konnektoren](./knowledge-sources.md#feeds-als-quelle-gebaut)) ist davon in einem
Punkt ausgenommen: Der Artikeltext einer Feed-Detailseite ist bereits extrahierter Text, keine Datei —
er durchläuft deshalb weder die Formaterkennung noch diese Liste, sondern geht direkt in Zerlegung und
Einbettung ein. `.html` steht bewusst **nicht** in der Liste oben; es wäre auch kein zutreffender
Eintrag, weil nie eine ganze HTML-Datei indiziert wird, sondern nur ihr bereinigter Hauptinhalt. Anlagen,
die der Feed-Weg auf derselben Detailseite findet, sind davon nicht betroffen: Sie sind echte Dateien und
durchlaufen dieselbe Liste wie jede andere.

Eine Einschränkung des gebauten Stands gehört dazu, weil sie sonst überrascht:

**Die Auswahl der Formate ist heute eine Endungsliste, keine Inhaltserkennung.** Die im Überblick
beschriebene Erkennung anhand des tatsächlichen Inhalts ist Zielbild und wird in
[#404](https://github.com/criew/opaa/issues/404) geführt. Der eingesetzte Extraktor beherrscht weit
mehr Formate, als die Liste zulässt — er meldet auf dem aktuellen Classpath 245 unterstützte
Medientypen. Die Begrenzung ist also eine bewusste fachliche Auswahl, keine Grenze der Extraktion.

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
- **Zitierzwang gilt auch hier**, und zwar je Abschnitt. Ein Bericht, dessen Kapitel 3 unbelegt ist,
  weist das an Ort und Stelle aus.
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
Antwort mit Fundstellen · Konfidenz · gegebenenfalls Verweigerung
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
- **Verweigerung ist ein Ergebnis, kein Fehler.** „Nicht feststellbar" wird protokolliert und zählt in
  der Auswertung nicht als Störung — sonst entsteht Druck, die Schwelle zu senken.
- **Belegprüfung in zwei Stufen, zuerst nur die deterministische — ja.** Stufe 1 prüft ohne zusätzlichen
  Modellaufruf, ob jeder Beleg auf eine tatsächlich abgerufene Fundstelle zeigt, ob überhaupt Fundstellen
  vorlagen und ob jede tragende Aussage einen gültigen Beleg führt. Die inhaltliche Deckungsprüfung
  (Stufe 2) wird getrennt entschieden (siehe [Zitierzwang](#zitierzwang), entschieden in
  [#354](https://github.com/criew/opaa/issues/354)).
- **Der Schalter sitzt am Space, mit erzwingender Systemvorgabe — ja.** Die Verankerung an der
  Wissensbibliothek wäre die stärkere Wahl, kostet aber eine Ebene mehr; die bekannte Folge ist, dass der
  Zitierzwang durch einen Raumwechsel umgehbar bleibt (siehe [Wo der Schalter
  sitzt](#wo-der-schalter-sitzt)).
- **Ein Vektorspeicher, und zwar PostgreSQL mit pgvector — ja.** Austauschbare Vektorspeicher werden
  nicht zugesagt. Der Zugriff läuft zwar über eine portable Schnittstelle des Rahmenwerks, ein Wechsel
  wird aber nicht unterstützt, nicht geprüft und nicht dokumentiert (siehe
  [Der Vektorspeicher](#der-vektorspeicher-postgresql-mit-pgvector-und-sonst-keiner), entschieden in
  [#348](https://github.com/criew/opaa/issues/348)).

---

## Offene Fragen / Zukünftige Erweiterungen

- Soll der Zitierzwang zusätzlich **an der Wissensbibliothek** verankert werden? Das ist die stärkere
  Alternative zur entschiedenen Verankerung am Space: Der Bestand führt seine Anforderung mit, und der
  Zwang ist nicht mehr durch einen Raumwechsel umgehbar — wie die Modellbeschränkung, die aus demselben
  Grund an den Daten hängt. Der Preis ist eine Ebene mehr und ein Bestand, der für explorative Arbeit
  unbrauchbar wird, sobald er irgendwo bereitgestellt ist. **Wieder aufzumachen, wenn sich die
  organisatorische Absicherung im Betrieb als zu schwach erweist** — der Auslöser wäre ein Fall, in dem
  ein haftungskritischer Bestand in einem Space ohne Zitierzwang gelandet ist.
- Soll auch ein **Agent** den Zitierzwang mitbringen können, unabhängig davon, wo er läuft? Fachlich
  naheliegend — ein Agent für Auskünfte mit Außenwirkung ist genau der Fall — und in
  [Agenten, Prompts und Werkzeuge](./agents-and-tools.md) bereits vorausgesetzt. Die Ebene fügt sich in
  dieselbe Verrechnungsrichtung ein, ist aber erst zu entscheiden, wenn es Agenten gibt.
- Kommt die inhaltliche **Deckungsprüfung (Stufe 2)**, und in welcher Form — zweiter Modelldurchlauf über
  die ganze Antwort, nur über die zweifelhaften Abschnitte, oder ein eigenes, kleines Prüfmodell? Sie
  kostet Zeit und Rechenleistung je Antwort, ist selbst fehlbar und ohne Messaufbau nicht bewertbar. Eine
  eigene Entscheidungsvorlage bereitet das vor ([#389](https://github.com/criew/opaa/issues/389)).
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

- **Belegdeckung** — Anteil der Antworten, in denen jede tragende Aussage eine Fundstelle trägt.
- **Verweigerungsgüte** — Anteil der Verweigerungen im Zitierzwang, bei denen tatsächlich kein Beleg im
  Bestand vorhanden war. Eine Verweigerung trotz vorhandener Quelle ist der teure Fehler.
- **Retrieval-Kennzahlen** gegen das Golden Dataset, gemessen nach dem in
  [ADR-0012](../decisions/0012-messvertrag-retrieval-harness.md) festgelegten Messvertrag.
- **Prüfquote der Belege** — wie oft Nutzende die Sprungmarke tatsächlich aufrufen; ein Beleg, den
  niemand öffnen kann, ist keiner.
- **Zeit bis zur belegten Auskunft** im Vergleich zur bisherigen Recherche.
- **Anteil der Dokumente, deren Zerlegung beanstandet wurde**, als Frühindikator für
  Verarbeitungsprobleme.
