# Zielbild der OPAA-Weboberfläche

> Briefing für einen Design-Entwurf. Beschreibt den **angestrebten Endzustand**, nicht den heutigen
> Stand — vieles davon ist noch nicht gebaut. Der Entwurf soll zeigen, wohin die Oberfläche geht,
> damit die Navigation trägt, wenn die fehlenden Teile entstehen.

---

## 1 · Das Produkt

OPAA ist eine quelloffene KI-Plattform für die **öffentliche Verwaltung**, betrieben im eigenen
Rechenzentrum. Beschäftigte befragen das Wissen ihres Hauses — Akten, Erlasse, Arbeitsanweisungen,
Intranet — und bekommen Antworten, **die ihre Fundstelle mitnennen**.

Zwei Prinzipien entscheiden jede Gestaltungsfrage im Zweifel:

**Belegbarkeit.** Eine Auskunft in der Verwaltung ist keine Meinung. Jemand steht mit seinem Namen
dafür gerade, und Jahre später muss nachvollziehbar sein, worauf sie sich stützte. Ein Beleg, den man
nicht öffnen kann, ist kein Beleg.

**Verteilbarkeit.** Das Können weniger Personen muss zu allen kommen. Wissensbestände, Agenten und
Prompt-Sammlungen sind benannte, teilbare Objekte mit eigenen Rechten — keine in Chatverläufen
vergrabenen Einzelfälle. Wer etwas besitzt und wer es nutzen darf, ist jederzeit ablesbar.

### Für wen

Optimiert wird auf die **Sachbearbeitung**: wenig Zeit, keine Schulung, kein Technikinteresse. Wo
Einfachheit und Mächtigkeit sich widersprechen, gewinnt Einfachheit — im Zweifel weniger
Bedienelemente und mehr Vorbelegung. Fortgeschrittenes wird weggeklappt oder in eigene Bereiche
verlegt, nicht in die tägliche Arbeitsfläche gemischt.

Daneben stehen Wissenspflege und Fachreferate (stellen Bestände zusammen, vergeben Rechte),
Systemverwaltung (Gruppen, Verzeichnisabgleich, Modellvorgaben) und eine getrennte Revisionsrolle
mit ausschließlich lesendem Zugriff auf Protokolldaten.

### Haltung

**Ruhig und wertig.** Sachlich, aber mit Sorgfalt in Typografie, Weißraum und Detail. Es soll sich
anfühlen wie ein gut gemachtes Werkzeug, nicht wie ein verordnetes Fachverfahren. Keine Maskottchen,
keine Gamification, keine Erfolgsmeldungen mit Konfetti — aber auch nicht behördlich-abweisend. Das
Produkt konkurriert im Kopf der Nutzer mit Verbraucherwerkzeugen, die sie sonst heimlich benutzen.

---

## 2 · Das Grundmodell

Zwei Ebenen tragen die gesamte Anwendung. Wer sie verstanden hat, versteht die Oberfläche.

### Ein Space ist ein Projekt

Ein **Space** bündelt alles, was zu einem Vorhaben gehört:

- **eine definierte Menge Datenquellen** — die Wissensbestände, die hier befragt werden
- **die Agenten**, die hier zur Verfügung stehen
- **die Mitglieder**, die hier arbeiten
- **beliebig viele Chats**

Man legt beliebig viele Spaces an, bestimmt je Space die Datenquellen und wechselt zwischen ihnen.
Der Space ist damit eine **vollständige Arbeitsausstattung**: dieses Wissen, diese Werkzeuge, diese
Leute. Der Wechsel des Space ist die häufigste Navigationshandlung überhaupt und muss entsprechend
schnell und sichtbar sein.

**Der persönliche Space ist ein Space wie jeder andere**, nur mit einem Mitglied — mit frei
wählbaren eigenen Datenquellen. Er ist kein Sonderfall und keine reduzierte Fassung.

### Ein Chat ist eine geschlossene Unterhaltung

Innerhalb eines Space liegen beliebig viele **Chats** — in sich geschlossene Unterhaltungen zu je
einem Thema. Man wechselt zwischen ihnen, **benennt und benennt sie um**. Ein Chat gehört dauerhaft
zu seinem Space und wandert nicht.

**Alle Chats eines Space sind für alle Mitglieder sichtbar.** Wer im Team-Space arbeitet, arbeitet
sichtbar. Wer für sich denken will, tut das in seinem persönlichen Space — der dieselben Datenquellen
tragen kann, wenn man sie dort zuordnet.

### Was daraus für den Suchbereich folgt

**Der Space bestimmt, was durchsucht wird.** Niemand bedient dafür etwas pro Anfrage. Es gibt keinen
Schalter „Wissen nutzen" und keine Auswahlliste am Eingabefeld — die Datenbasis ist eine Eigenschaft
des Raums, in dem man sich befindet, und dort einmal eingerichtet.

Verengen ist möglich, erweitern nicht: Mit `@` grenzt man eine einzelne Frage auf eine bestimmte
Quelle **aus dem Space** ein („nur im Erlass nachsehen"). **Der Space bleibt immer die Obergrenze.**

---

## 3 · Grundriss

**Der Chat ist der Mittelpunkt.** Alles andere ist Zulieferung — Räume einrichten, Bestände pflegen,
Rechte vergeben. Wer OPAA öffnet, landet in einem Gespräch, nicht in einer Verwaltungsmaske.

```
┌────────────┬────────────────────────────────────┐
│ SPACE ▾    │                                    │
│ Widerspr.  │        Antwort mit Belegen         │
├────────────┤                                    │
│ CHATS      │                                    │
│ · Fristen  │                                    │
│ · Az. 12/4 │                                    │
│ · Erlasse  │                                    │
│ + Neu      │   ┌────────────────────────────┐   │
├────────────┤   │ Frage stellen …         @  │   │
│ Einrichten │   └────────────────────────────┘   │
│ Katalog    │   Durchsucht: 4 Bestände           │
│ Verwaltung │                                    │
└────────────┴────────────────────────────────────┘
```

Die Seitenleiste hat damit **zwei Ebenen statt vier gleichrangiger Listen**: oben der aktive Space
mit seinen Chats, unten die selteneren Wege. Der Space-Wechsel gehört an die prominenteste Stelle.

Auf Tablet und Telefon klappt die Seitenleiste weg; der Chat bleibt vollständig bedienbar.

---

## 4 · Die Bereiche

### 4.1 Chat — die Hauptfläche

Nachrichtenverlauf, darunter das Eingabefeld. Leerzustand mit Ansprache und einem Hinweis, worauf
dieser Space Zugriff hat.

**Das Eingabefeld** trägt Text und die `@`-Mechanik. **Ein Bedienmuster für alles Teilbare:** Tippen
von `@` schlägt sowohl Datenquellen als auch Agenten des Space vor. Beide müssen in der Vorschlagsliste
**sofort unterscheidbar** sein — ein Bestand verengt die Suche, ein Agent verändert die Arbeitsweise.
Das ist der wichtigste Einzelmoment der ganzen Oberfläche: Hier trifft Wissen auf Können.

Unter dem Eingabefeld steht still, was durchsucht wird. Keine Bedienung, nur Auskunft — aber
anklickbar, wer nachsehen will.

**Die Antwort** ist Fließtext mit Absätzen, Listen, Tabellen und Codeblöcken.

**Belege erscheinen zweifach**, weil sie zwei Aufgaben haben:

1. **Hochgestellte Ziffern im Fließtext** binden den Beleg an die einzelne Aussage — die Form, die
   Verwaltung und Justiz ohnehin kennen. Man sieht satzweise, was getragen ist.
2. **Eine Fundstellenliste darunter** trägt die Einzelheiten: Dokument, Relevanz, Trefferzahl.

Beides ist **ein Block, nicht zwei**. Die zitierten Fundstellen tragen ihre Ziffer als Auszeichnung,
die übrigen Treffer stehen gleichrangig ohne Ziffer in derselben Liste. Ein Sprung von der Ziffer zur
Fundstelle und von dort in das Quelldokument an die konkrete Textstelle gehört dazu.

**Rückmeldung** je Antwort und je einzelner Fundstelle („trug bei" / „Fehltreffer"), dazu ein
freiwilliger freier Hinweis. Zurückhaltend gestaltet — es ist ein Angebot, keine Aufforderung.

**Der Chatname** ist an Ort und Stelle änderbar, ohne Dialog.

### 4.2 Die Verweigerung im Zitierzwang

Für haftungskritische Zusammenhänge lässt sich ein Space in den **Zitierzwang** schalten: Findet sich
kein Beleg, verweigert das System die Antwort und sagt „nicht feststellbar", statt plausibel zu
formulieren. Das ist das Stück, das OPAA von einem Chatbot unterscheidet.

**Es sieht aus wie jede andere Antwort.** Gleiche Form, gleicher Ort, gleiche Ruhe. Kein Warnton,
keine Signalfarbe, kein Fehlerbanner. Die Auskunft „dazu lässt sich nichts belegen" ist ein
vollwertiges Ergebnis, kein Zwischenfall — sie zeigt, wo gesucht wurde, und ist damit fertig.

Ein Design, das hier ein rotes Ausrufezeichen setzt, hat das Produkt nicht verstanden.

### 4.3 Space einrichten

Der Ort, an dem ein Projekt seine Ausstattung bekommt. Erreichbar aus dem Space, nicht aus einem
fernen Verwaltungsbereich.

- **Datenquellen zuordnen** — aus den Wissensbibliotheken wählen, die man selbst lesen darf
- **Agenten zuordnen** — welche Werkzeuge hier zur Verfügung stehen
- **Mitglieder und Rollen** — aufnehmen, Rolle ändern, Eigentum übergeben
- **Name, Beschreibung, Zitierzwang**

Das Zuordnen ist die häufigste Handlung hier und muss leichtfallen: suchen, auswählen, fertig.

**Fehlende Leserechte an einer Space-Quelle** bleiben unsichtbar — die Rechte hängen an der
Wissensbibliothek, nicht am Space, und nicht jedes Mitglied darf jeden Bestand lesen. Wer eine Quelle
nicht lesen darf, sieht sie nicht in der Liste. **Fällt eine Antwort deshalb dünner aus, steht ein
Hinweis dabei** — dass dieser Space Bestände enthält, die einem nicht zugänglich sind, ohne zu
nennen, welche. Der Hinweis erscheint bei Wirkung, nicht auf Vorrat.

### 4.4 Wissensbibliotheken

Eine Wissensbibliothek ist ein Dokumentenbestand **mit eigenen Rechten** — ein eigenständiges Objekt,
kein Ordner in einem Raum. Sie kann in mehreren Spaces verwendet werden.

**Übersicht:** alle lesbaren Bestände mit Name, Beschreibung, eigener Rolle, Sichtbarkeit.

**Detailseite:** Stammdaten, Rechtevergabe, Bestand — und ein Bereich, der **je nach Herkunft anders
aussieht**:

| Herkunft           | Was der Bereich zeigt                                                        |
| ------------------ | ---------------------------------------------------------------------------- |
| **Upload**         | Dateien auswählen oder hineinziehen, Fortschritt, einzelne Dokumente löschen |
| **Dateisystem**    | Pfad, Lauf anstoßen, Stand verfolgen                                         |
| **Webverzeichnis** | Adresse, Zugangsdaten, Lauf anstoßen, Stand verfolgen                        |
| **RSS-Feed**       | Feed-Adresse, Zugangsdaten, Behandlung von Anhängen, Lauf verfolgen          |

Diese Seite trägt am meisten Information und braucht die klarste Ordnung der ganzen Anwendung.
**Rechtevergabe** (Personen und Gruppen suchen, Rolle zuweisen, entziehen) erscheint nur, wer sie hat.

### 4.5 Agenten und Prompt-Bibliotheken

Die zweite Produktsäule: Ein Agent erledigt eine wiederkehrende Aufgabe — Widerspruch prüfen,
Aktenvermerk entwerfen, in Leichte Sprache übertragen —, immer an das Wissen des Space gebunden und
immer mit den Rechten der fragenden Person.

Agenten sind **dieselbe Art Objekt wie Wissensbibliotheken**: benannt, beschrieben, mit eigenen
Rechten, versionierbar, einem Space zuordenbar, per `@` aufrufbar. Sie brauchen dieselbe
Gestaltungssprache — wer eine Bibliothek verstanden hat, versteht einen Agenten.

Dazu gehören: anlegen und beschreiben, erproben bevor man freigibt, Fassungen verwalten, zurückziehen
ohne zu löschen, Herkunft eines abgeleiteten Agenten erkennen.

### 4.6 Katalog

Der Ort, an dem verteilte Fähigkeit auffindbar wird: freigegebene Agenten, Prompt-Sammlungen und
Wissensbestände aus der ganzen Organisation, durchsuchbar, mit Angabe wer sie verantwortet und auf
welcher Verteilungsstufe sie stehen. Von hier übernimmt man etwas in den eigenen Space.

Der Katalog ist die sichtbare Einlösung des Prinzips Verteilbarkeit — ohne ihn bleibt Können dort,
wo es entstanden ist.

### 4.7 Systemverwaltung

Gruppen als Rechtesubjekt. **Abgleich mit dem Verzeichnisdienst mit Probelauf vor dem Vollzug** — der
Probelauf zeigt als eigener Zustand, was geschehen _würde_, und wird dann bestätigt oder verworfen.
Dazu Modellvorgaben und der getrennte Zugriffsweg für die Revision auf die Protokolldaten.

### 4.8 Einstellungen und Anmeldung

Persönliche Darstellung und eigene Zugänge zur Schnittstelle. Die Anmeldung läuft über den
Verzeichnisdienst des Hauses — kein anonymer Zugang, keine Selbstregistrierung. Eine schlichte Seite,
aber der erste Eindruck des Produkts.

---

## 5 · Was überall wiederkehrt

Diese vier Dinge erscheinen in mehreren Bereichen und müssen **überall gleich aussehen**. Sie sind
die eigentliche Systemarbeit dieses Entwurfs.

**Rollen an einem Objekt** — vier gestapelte Stufen: `Leser` (benutzen) → `Bearbeiter` (ändern) →
`Verwalter` (weitergeben, Rechte vergeben) → `Eigentümer` (löschen, übergeben). Die eigene Rolle ist
an jedem Objekt ablesbar und entscheidet, welche Bedienelemente überhaupt erscheinen.

**Verteilungsstufe** — `privat`, `geteilt`, `organisationsweit`, dazu unabhängig der Schalter „im
Katalog auffindbar". Gilt gleichermaßen für Bibliotheken, Agenten und Prompt-Sammlungen.

**Der Beleg** — Ziffer im Text, Fundstelle in der Liste, Sprung ins Dokument. Erscheint im Chat, im
Export und in allem, was aus einem Gespräch heraus entsteht.

**Langlaufende Vorgänge** — Indizierungsläufe, Verzeichnisabgleiche, Uploads. Sie brauchen einen Ort,
an dem ihr Stand sichtbar bleibt, ohne den Arbeitsfluss zu blockieren, und über einen Seitenwechsel
hinweg.

---

## 6 · Zustände, die mitgestaltet werden müssen

Sie werden erfahrungsgemäß vergessen und entscheiden trotzdem über die Alltagstauglichkeit:

- **Nicht feststellbar** — die begründete Verweigerung als vollwertige Auskunft (4.2)
- **Antwort dünner wegen fehlender Rechte** — Hinweis ohne Nennung der Quelle (4.3)
- **Leere Zustände** — kein Space, kein Chat, kein Bestand, kein Treffer
- **Neuer, noch leerer Space** — der Moment, in dem jemand zum ersten Mal Quellen zuordnet
- **Antwort entsteht** — sie erscheint fortlaufend, Wort für Wort; Belege setzen sich danach
- **Vorgang läuft** — Indizierung, Upload, Verzeichnisabgleich
- **Fehler** — deutsche, verständliche Meldung, nie mit technischem Innenleben

---

## 7 · Rahmenbedingungen

**Barrierefreiheit ist Pflicht.** Öffentliche Stellen unterliegen der BITV: durchgehende
Tastaturbedienung, sichtbarer Fokus, ausreichende Kontraste, keine Bedeutung allein über Farbe,
sinnvolle Beschriftungen für Screenreader, Rücksicht auf `prefers-reduced-motion`.

**Sprache:** durchgehend deutsch, in Amtssprache ohne Anglizismen. Mehrsprachigkeit ist geplant —
Layouts müssen längere Texte vertragen.

**Geräte:** Desktop ist der Arbeitsplatz und hat Vorrang; auf Tablet und Telefon muss die Oberfläche
bedienbar bleiben.

**Beide Farbschemata** werden unterstützt und müssen beide bewusst gestaltet sein.

**Technisch:** React mit Material UI. Ein Entwurf, der sich weit davon entfernt, ist teuer — eine
eigene visuelle Handschrift innerhalb dieses Rahmens ist ausdrücklich erwünscht. Der heutige Stand
(Dunkelmodus, `#137fec`, Inter, 8px Radius) entstand als Werkzeug-Voreinstellung, nicht aus einer
gestalterischen Entscheidung. **Es besteht keine Bindung daran.**

---

## 8 · Bewusste Abweichungen von der Spezifikation

Zwei Festlegungen dieses Zielbilds weichen von `docs/features/spaces-and-assets.md` ab. Sie sind
so gewollt; wer die Spezifikation kennt, soll die Abweichung nicht für ein Versehen halten.

**Chats sind im Space für alle Mitglieder sichtbar.** Die Spezifikation lässt einen Chat privat
entstehen und erst durch bewusstes Teilen sichtbar werden — begründet mit dem Denkraum für unfertige
Überlegungen und gegenüber der Personalvertretung. Dieses Zielbild wählt die klarere Ansage: Wer im
Team-Space arbeitet, arbeitet sichtbar.

**Der Denkraum wandert in den persönlichen Space**, der dafür frei wählbare eigene Datenquellen
bekommt. Damit bleibt die heikle Frage im richtigen Datenkontext möglich, ohne dass jemand mitliest.
Der Preis ist, dass dieselben Bestände zweimal zugeordnet werden — das Zuordnen muss deshalb leicht
sein, und ein Weg, die Ausstattung eines Space zu übernehmen, ist es wert, mitgedacht zu werden.

---

## 9 · Visuelle Richtung

<!-- Hier ergänzen: Farbwelt, Typografie, Referenzen, Vorbilder, Anmutung im Detail -->
