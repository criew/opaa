# OPAA Konzepte & Glossar

Dieses Dokument erläutert die Begriffe, die in der gesamten OPAA-Dokumentation verwendet werden. Die
Beispiele sind der öffentlichen Verwaltung entnommen, weil OPAA für sie gebaut wird — siehe
[VISION.md](./VISION.md) und [ADR-0014](./decisions/0014-produktausrichtung-oeffentliche-verwaltung.md).

Das Glossar erklärt das **Zielbild**. Was davon heute tatsächlich im Code steht, führt allein
[STATUS.md](./STATUS.md); ein Begriff in diesem Glossar ist keine Aussage über den Umsetzungsstand.

---

## Die beiden Leitbegriffe

### Belegbarkeit

Die Eigenschaft, dass jede Aussage des Systems auf eine nachprüfbare Quelle zurückgeführt werden kann —
und zwar so, dass die Prüfung auch Jahre später noch möglich ist.

Eine Auskunft in der Verwaltung ist keine Meinung. Jemand steht mit seinem Namen dafür gerade. Belegbarkeit
ist deshalb kein Komfortmerkmal, sondern die Voraussetzung dafür, dass eine Antwort überhaupt verwendet
werden darf. Sie setzt sich aus mehreren Bausteinen zusammen: [Fundstelle und
Quellenbindung](#fundstelle-und-quellenbindung), [Konfidenz](#konfidenz), [erklärbares
Chunking](#erklärbares-chunking) und im schärfsten Fall dem [Zitierzwang](#zitierzwang).

- **Beispiel:** Eine Sachbearbeiterin fragt nach der Frist für einen Widerspruch. Die Antwort nennt den
  Paragrafen, die Dienstanweisung in ihrer geltenden Fassung und die Textstelle, an der es steht. Sie
  springt mit einem Klick dorthin und übernimmt die Aussage erst danach in ihren Bescheid.

---

### Verteilbarkeit

Die Eigenschaft, dass eine einmal erarbeitete KI-Fähigkeit von einer Person zu einem Team, einem Referat
und schließlich der ganzen Organisation wandern kann, ohne kopiert oder neu erfunden zu werden.

Das reale Problem ist nicht, ob es ein gutes Modell gibt, sondern wie das Können von wenigen zu allen
kommt. Ohne Antwort darauf entsteht Schatten-KI: Einzelne basteln private Prompts, kopieren Amtsdaten in
Verbraucherwerkzeuge, und das Können bleibt in Köpfen. OPAA beantwortet die Frage über [KI-Assets](#ki-asset)
und [Verteilungsstufen](#verteilungsstufe).

- **Beispiel:** Ein Referat entwickelt eine Arbeitsweise, mit der Stellungnahmen im Anhörungsverfahren
  vorbereitet werden. Statt sie mündlich weiterzugeben, wird sie ein benannter Agent mit Beschreibung,
  Fassung und Freigabe — und steht nach der Prüfung dem ganzen Amt zur Verfügung.

---

## Kernkonzepte

### Wissensbestand des Hauses

Die Gesamtheit der Informationen, Dokumente und Daten, die in den Systemen einer Behörde liegen.

- **Beispiel:** Dienstanweisungen, Erlasse und Rundschreiben, Wiki-Seiten des Hauses, Postfächer der
  Funktionsadressen, Ablagen auf dem Netzlaufwerk, Vorgänge aus Fachverfahren, Protokolle von
  Dienstbesprechungen
- **Schwierigkeit:** Der Bestand ist über viele Systeme verteilt, und wer eine Frist sucht, sucht ihn ab
- **Rolle von OPAA:** Ein Zugang über alle Bestände hinweg, mit belegter Antwort statt Trefferliste

---

### RAG (Retrieval-Augmented Generation)

Eine Technik, die den Abruf von Informationen mit der Erzeugung von Sprache verbindet. Statt dass das
Sprachmodell allein aus seinen Trainingsdaten antwortet, holt RAG zuerst die passenden Textstellen aus dem
Wissensbestand und lässt das Modell **nur auf dieser Grundlage** formulieren.

**Wie es abläuft:**
1. Eine Frage wird gestellt
2. Das System ruft die passenden Textstellen aus den lesbaren Wissensbibliotheken ab
3. Das Sprachmodell liest diese Textstellen
4. Es formuliert eine Antwort auf ihrer Grundlage
5. Die Antwort trägt ihre Fundstellen

**Warum das trägt:**
- Antworten stützen sich auf die Unterlagen des Hauses, nicht auf allgemeines Weltwissen
- Das Erfinden von Fakten wird eingegrenzt (siehe [Halluzination](#halluzination))
- Jede Aussage ist durch einen Blick in die Quelle nachprüfbar
- Neue Fassungen einer Dienstanweisung wirken, sobald sie indiziert sind — das Modell muss nicht neu
  trainiert werden

---

### Embedding (Vektor-Einbettung)

Eine numerische Darstellung von Text, die dessen Bedeutung erfasst. Ein Embedding ist eine Liste von Zahlen
(ein „Vektor"), die den Inhalt eines Textstücks oder einer Frage kodiert.

**Einfach erklärt:**
- Eine Dienstanweisung zur Telearbeit wird zu `[0,21, -0,18, 0,45, …, 0,32]` (hunderte bis tausende Zahlen)
- Die Frage „Darf ich von zu Hause arbeiten?" erzeugt einen ähnlichen Vektor `[0,20, -0,17, 0,46, …, 0,31]`
- Ähnliche Vektoren bedeuten ähnlichen Sinn
- Über diese Ähnlichkeit findet das System die passenden Stellen

**Warum das wichtig ist:**
- Es ermöglicht die [semantische Suche](#semantische-suche) — Suche nach Sinn statt nach Zeichenketten
- „Darf ich von zu Hause arbeiten?" findet die Regelung zur Telearbeit, auch wenn das Wort „zu Hause"
  darin nicht vorkommt

---

### Vektor-Datenbank

Eine Datenbank, die auf das Speichern und Durchsuchen von Embeddings ausgelegt ist.

**Beispiele der Gattung:**
- **PostgreSQL mit pgvector** — relationale Datenbank mit Vektor-Erweiterung
- **Elasticsearch** — Suchmaschine mit Vektor-Unterstützung
- **Milvus, Qdrant, Weaviate** — quelloffene Systeme für große Vektorbestände

**Warum eine eigene Gattung:**
- Klassische SQL-Datenbanken sind auf exakte Treffer optimiert
- Vektor-Datenbanken sind auf **Ähnlichkeitssuche** optimiert („finde die zehn nächstliegenden Vektoren")
- Für die semantische Suche ist das um Größenordnungen schneller

**In OPAA:** eingesetzt wird **PostgreSQL mit pgvector** — als einziger unterstützter Vektorspeicher.
Die übrigen Systeme sind hier nur zur Einordnung der Gattung genannt und keine wählbare Option. Der
Zugriff läuft zwar über eine portable Schnittstelle des eingesetzten Rahmenwerks, ein Wechsel wird aber
nicht unterstützt, nicht geprüft und nicht dokumentiert. Begründung:
[Daten-Indizierung & RAG](./features/data-indexing-rag.md#der-vektorspeicher-postgresql-mit-pgvector-und-sonst-keiner).

---

### Chunk / Chunking

Das Zerlegen großer Dokumente in kleinere, handhabbare Stücke.

**Warum nötig:**
- Eine fünfzigseitige Dienstanweisung ergäbe ein einziges, unscharfes Embedding
- Stattdessen wird sie in Abschnitte zerlegt, jeder mit eigenem Embedding
- Die Suche liefert dann die einschlägige Passage statt des ganzen Dokuments

**Beispiel:**
```
Dokument: „Dienstanweisung Personalangelegenheiten" (10.000 Wörter)
  ↓
Chunks:
  Chunk 1: „Einstellungsverfahren" (200 Wörter)
  Chunk 2: „Telearbeit und mobiles Arbeiten" (300 Wörter)
  Chunk 3: „Reisekosten" (250 Wörter)
  …
```

Wer nach mobilem Arbeiten fragt, bekommt Chunk 2 — nicht das ganze Werk.

Wie die Zerlegung zustande kam, gehört zur Belegbarkeit: siehe [erklärbares Chunking](#erklärbares-chunking).

---

### LLM (großes Sprachmodell)

Ein KI-Modell, das auf großen Textmengen trainiert wurde und Sprache verstehen und erzeugen kann.

**Im OPAA-Kontext:**
- Das Modell liest die abgerufenen Textstellen und formuliert die Antwort
- Modelle sind austauschbar; OPAA spricht jede OpenAI-kompatible Schnittstelle an
- Der Vorrang liegt bei [lokal betriebenen Modellen](#lokal-betriebene-modelle)
- Welche Modelle überhaupt erlaubt sind, entscheidet die Systemverwaltung über die
  [Modell-Policy](#modell-policy-als-obergrenze), nicht die einzelne Nutzerin

---

### Space

Ein thematischer Arbeitsraum — für ein Vorhaben, ein Team, ein Referat oder die eigene Arbeit. Der Space ist
**kein Sicherheitssilo für Dokumente**, sondern der Ort, an dem gearbeitet wird und an dem die Ergebnisse
dieser Arbeit liegen.

**Zweck:**
- Chats und Artefakte thematisch bündeln
- Kuratieren, welche Assets hier angeboten werden
- Standard-Suchbereich, Modell-Obergrenze und Zurechnung für Nutzung und Kosten

**Flaches Modell:**
Spaces sind **flach** — keine Hierarchie, keine Verschachtelung. Es gibt drei Arten:

1. **Persönlicher Space** — automatisch je Nutzerin und Nutzer, nicht teilbar, nicht löschbar
2. **Projekt-Space** — von jeder Person anlegbar, nicht im Verzeichnis gelistet, nur selbst eingeladene
   Mitglieder
3. **Team-Space** — von der Systemverwaltung angelegt, für Teams, Referate und hausweite Räume

**Wichtig:** Space-Mitgliedschaft gewährt **keinen** Zugriff auf die im Space assoziierten Assets — aber
vollen Zugriff auf die **abgelegten** space-eigenen Inhalte. Chats und Artefakte entstehen als Entwurf bei
der Person, die sie erzeugt, und werden erst durch Ablegen sichtbar. Siehe [Spaces, Assets &
Zugangskontrolle](./features/spaces-and-assets.md).

- **Beispiel:** Der Team-Space „Bauleitplanung" bündelt die Chats zum laufenden Verfahren; die
  Rechtsquellen darin gehören dem Rechtsreferat und sind nur assoziiert.

---

### Suchbereich eines Chats

Was ein Chat durchsucht, wird nicht durch eine Auswahl je Anfrage bestimmt, sondern durch zwei
Steuerungen, die **am Chat selbst** hängen: den Schalter **„Wissen nutzen"** (Standard: an) und
**@-Bibliotheksreferenzen** — beim Tippen von `@` im Eingabefeld vorgeschlagen, referenzierbar sind
alle Bibliotheken, die der Nutzer lesen darf, unabhängig vom Space. Gesetzte Referenzen bleiben als
entfernbare Chips **sticky am Chat**, nicht nur für eine einzelne Anfrage.

- **an** — im Zielbild die dem Space assoziierten Bibliotheken, geschnitten mit den lesbaren; bis zur
  Space↔Bibliothek-Assoziation (#203) gelten ersatzweise alle lesbaren Bibliotheken, und ein Space ohne
  Assoziationen verengt auch danach dauerhaft nicht. @-Referenzen bleiben in diesem Zustand ohne Wirkung
  auf den Suchbereich
- **aus** — ausschließlich die per @ referenzierten Bibliotheken, geschnitten mit den lesbaren; ohne
  Referenz findet keine Dokumentensuche statt

Eine Space-Auswahl, die den Suchbereich einer einzelnen Anfrage steuert, gibt es nicht mehr. Siehe
[Suchbereich je Chatart](./features/spaces-and-assets.md#suchbereich-je-chatart).

- **Beispiel:** Eine Sachbearbeiterin öffnet einen Chat im Team-Space „Bauleitplanung" zu einer Frage,
  die ausschließlich die Bibliothek „Rechtsquellen Denkmalschutz" betrifft. Sie schaltet „Wissen
  nutzen" aus und referenziert die Bibliothek per `@Rechtsquellen-Denkmalschutz` — die Antwort stützt
  sich dann nur auf diesen Bestand, nicht auf das gesamte lesbare Wissen.

---

### Asset

Siehe [KI-Asset](#ki-asset) — ein eigenständiges, teilbares Objekt mit genau einem Eigentümer und einer
eigenen Rechteliste.

---

### KI-Asset

Ein benanntes, beschriebenes, auffindbares Objekt, das eine KI-Fähigkeit oder einen Wissensbestand trägt und
für sich geteilt, versioniert und freigegeben werden kann. Es gibt drei Gattungen: **Agent**,
**Prompt-Bibliothek** und **[Wissensbibliothek](#wissensbibliothek)**.

Der Begriff ist der Träger der [Verteilbarkeit](#verteilbarkeit). Ein Asset hat genau einen Eigentümer, eine
eigene Rechteliste und eine Fassung — es ist damit das Gegenteil eines guten Prompts, der in einem
Chatverlauf verschwindet.

**Zweck:**
- Können und Wissen verteilbar machen, ohne es zu kopieren
- Ein Bestand liegt einmal und wird an vielen Stellen genutzt

**Assoziation:** Ein Asset wird in beliebig viele Spaces assoziiert. Die Assoziation ist reine Kuratierung
und **gewährt keinerlei Zugriff**; sie stellt das Asset nur denen im Space bereit, die ohnehin ein Recht
darauf haben.

**Merkregel:** Was im Space entsteht, gehört dem Space. Was assoziiert wird, behält seinen Eigentümer.

- **Beispiel:** Der Agent „Vorprüfung Widerspruch" gehört dem Rechtsreferat, ist in vier Team-Spaces
  assoziiert und trägt die Fassung 3. Wird Fassung 4 freigegeben, arbeiten alle vier Räume damit — ohne dass
  irgendwo eine Kopie nachgezogen werden muss.

---

### Wissensbibliothek

Der Dokumentencontainer und der Rechteanker der Suche. Jedes Dokument gehört zu genau einer
Wissensbibliothek, und jeder Chunk trägt die Bibliotheks-Kennung als Filterachse der
[rechtebewussten Suche](#berechtigungsdurchsetzung-zur-abfragezeit).

Die Wissensbibliothek ist ein [KI-Asset](#ki-asset) und kein Ordner in einem Raum. Genau darauf beruht, dass
eine Beschränkung an den **Daten** hängt und nicht am Arbeitsraum: Eine Bibliothek führt ihre Vorgabe „nur
lokale Modelle" selbst mit sich, und ein Wechsel des Space umgeht sie nicht.

**Persönliche Ablage:**
- Jede Person hat einen persönlichen Space und darin eine persönliche Wissensbibliothek „Meine Dokumente"
- Automatisch angelegt, eine je Person, nicht löschbar (beim Ausscheiden deaktiviert)
- Geteilt wird über die Bibliothek, nicht über den Raum: Eine direkte Berechtigung für eine andere Person
  genügt, der persönliche Space bleibt privat

- **Beispiel:** Die Bibliothek „Rechtsquellen Soziales" gehört dem Rechtsreferat, ist für die Gruppe
  „Amt 50" lesbar und in mehreren Team-Spaces assoziiert. Wer nicht in der Gruppe ist, bekommt daraus keinen
  Treffer — und erfährt auch nicht, dass es Treffer gäbe.

---

### Verteilungsstufe

Die Reichweite, für die ein [KI-Asset](#ki-asset) freigegeben ist. Die Stufen bauen aufeinander auf:

1. **Persönlich** — nur die Person, der das Asset gehört
2. **Team** — die Mitglieder eines Raums oder einer benannten Gruppe
3. **Fachbereich** — eine Gruppe aus dem Verzeichnisdienst, die eine Organisationseinheit abbildet
4. **Organisationsweit** — der Katalog des Hauses, erreichbar für alle Beschäftigten

Der Sprung auf die nächste Stufe ist eine **Handlung mit Freigabe- und Prüfschritt**, keine Einstellung, die
nebenbei verrutscht. Die [Organisation](#organisation-als-mandantengrenze) ist die Obergrenze: Es gibt keine
Stufe darüber.

- **Beispiel:** Eine Sachbearbeiterin baut sich einen Agenten für Aktenvermerke (persönlich). Ihr Team
  übernimmt ihn (Team). Das Referat prüft ihn fachlich und gibt ihn frei (Fachbereich). Nach einer Prüfung
  durch die Systemverwaltung steht er im hausweiten Katalog (organisationsweit).

---

### Rolle (in der Zugangskontrolle)

Ein Bündel von Rechten, das Personen oder Gruppen zugewiesen wird.

**Systemweite Rolle:**
- **System-Admin** — organisationsweite Verwaltung. Legt Team-Spaces an, richtet Konnektoren und
  Quellzuordnungen ein, verwaltet Gruppen und den Verzeichnisabgleich. Er verwaltet das System, ist aber
  **nicht automatisch leseberechtigt** für Inhalte.

**Space-Rollen (je Mitgliedschaft) — regeln Mitarbeit und Kuratierung, nicht den Dokumentenzugriff:**
- **Member** — Space betreten, Chats führen, alle Chats und Artefakte des Space lesen
- **Curator** — zusätzlich Assets assoziieren und lösen
- **Admin** — zusätzlich Mitglieder, Einstellungen und Modell-Obergrenze verwalten, Inhalte moderieren

Dazu trägt jeder Space einen **Verantwortlichen** als Attribut; nur er oder ein System-Admin darf den Space
löschen.

**Asset-Rollen (je Asset) — regeln den tatsächlichen Zugriff:**
- **User** — benutzen, ohne die Konfiguration zu sehen
- **Viewer** — zusätzlich die Konfiguration einsehen
- **Editor** — zusätzlich ändern
- **Manager** — zusätzlich teilen und Rechte vergeben
- **Owner** — zusätzlich löschen und Eigentum übertragen

Rechte werden an **Personen oder Gruppen** vergeben. Gruppen bilden die Aufbauorganisation ab und tragen die
[Verteilungsstufe](#verteilungsstufe) „Fachbereich".

---

## Retrieval und Belegbarkeit

### Semantische Suche

Suche nach **Sinn** statt nach exakter Zeichenkette.

**Beispiel:**
```
Frage: „Darf ich von zu Hause arbeiten?"

Eine reine Stichwortsuche fände:
  - „von zu Hause arbeiten"   ✓
  - „Telearbeit"              ✗ (kein wörtlicher Treffer)
  - „mobiles Arbeiten"        ✗ (kein wörtlicher Treffer)

Die semantische Suche findet:
  - „von zu Hause arbeiten"   ✓
  - „Telearbeit"              ✓
  - „mobiles Arbeiten"        ✓
  - „Dienstort außerhalb der Dienststelle" ✓
```

---

### Hybride Suche

Die Verbindung von semantischer Suche und klassischer Volltextsuche zu **einer** Trefferliste. Beide
Verfahren laufen, ihre Ergebnisse werden zusammengeführt und gemeinsam bewertet.

Der Grund ist ein praktischer: Die semantische Suche versteht Umschreibungen, versagt aber bei genau den
Merkmalen, von denen Verwaltungsdaten leben — Aktenzeichen, Paragrafen, Fristangaben, Bezeichnungen von
Formularen. Ein Aktenzeichen ist keine Bedeutung, sondern eine Zeichenkette, und die findet die
Stichwortsuche zuverlässig. Umgekehrt findet die Stichwortsuche „mobiles Arbeiten" nicht, wenn nach „zu
Hause" gefragt wird. Erst beide zusammen tragen.

- **Beispiel:** Die Frage „Wie ist der Stand zu 32.1-114/2025?" trifft über die Volltextsuche exakt das
  Aktenzeichen; die Frage „Wie war das noch mit den Fristen bei Nachbarwidersprüchen?" trifft über die
  semantische Suche die einschlägige Passage der Dienstanweisung. Dieselbe Suchmaske beantwortet beides.

---

### Reranking

Ein zweiter Bewertungsschritt: Aus einer größeren Menge grob gefundener Textstellen wählt ein eigenes,
genaueres Modell diejenigen aus, die zur Frage wirklich passen, und bringt sie in eine neue Reihenfolge.

Der erste Schritt muss schnell sein und holt deshalb bewusst zu viel. Der zweite Schritt darf langsam sein,
weil er nur noch wenige Kandidaten ansieht — und er vergleicht Frage und Textstelle unmittelbar, statt nur
zwei Vektoren aneinanderzuhalten. Was am Ende ins Sprachmodell geht, ist damit kürzer und besser.

- **Beispiel:** Zur Frage nach der Widerspruchsfrist liefert die erste Stufe fünfzig Passagen, darunter auch
  solche, in denen „Frist" in ganz anderem Zusammenhang steht. Das Reranking sortiert die einschlägige
  Regelung nach oben und die Randtreffer aus, sodass die Antwort nicht auf einer Nebenstelle gründet.

---

### Erklärbares Chunking

Die Eigenschaft, dass die Zerlegung eines Dokuments in Chunks nachvollziehbar dargestellt wird: Man kann
ansehen, wo geschnitten wurde, welchem Abschnitt ein Chunk entstammt und welcher Ausschnitt in die Antwort
eingeflossen ist.

Das ist keine Bequemlichkeit für Entwicklerinnen. Wenn eine Antwort falsch ist, liegt die Ursache häufig im
Schnitt — eine Tabelle wurde in der Mitte zerteilt, eine Ausnahmeregel vom Hauptsatz getrennt, eine Fußnote
verlor ihren Bezug. Ohne Einsicht in die Zerlegung bleibt der Fehler unauffindbar und die
[Belegbarkeit](#belegbarkeit) endet an der Dokumentgrenze.

- **Beispiel:** Eine Auskunft nennt eine Gebühr ohne die Ermäßigung. Ein Blick auf die Zerlegung zeigt, dass
  die Gebührentabelle zwischen Grundbetrag und Ermäßigungszeile geschnitten wurde. Die Ursache ist damit
  benannt und behebbar, statt dem Modell angelastet zu werden.

---

### Fundstelle und Quellenbindung

Die **Fundstelle** ist der Nachweis, den eine Antwort mitführt: welches Dokument, welche Fassung, welche
Stelle darin. Die **Quellenbindung** ist die Eigenschaft des Systems, jede einzelne Aussage an genau die
Textstelle zu binden, aus der sie stammt — im Englischen *grounded citation*.

Der Unterschied ist wesentlich. Eine Liste verwendeter Dokumente am Ende einer Antwort ist noch keine
Quellenbindung; sie sagt nur, was gelesen wurde. Gebunden ist eine Antwort erst, wenn zu **jeder** Aussage
die tragende Stelle benannt ist und ein Sprung dorthin möglich ist.

- **Beispiel:** „Der Widerspruch ist binnen eines Monats einzulegen [Dienstanweisung 12/2024, Abschnitt
  4.2]." Der Verweis führt auf den Absatz, nicht auf die Datei — und nicht auf die Startseite des Wikis.

---

### Zitierzwang

Ein Betriebsmodus, in dem das System **keine Antwort ohne belegte Quelle** ausgibt. Findet das Retrieval
keine tragfähige Fundstelle, lautet die Antwort „nicht feststellbar" statt einer plausiblen Formulierung.

Der Modus ist für haftungskritische Zusammenhänge gedacht und wird je Wissensbibliothek, Agent oder Space
gesetzt. Er kostet bewusst Trefferquote: Lieber eine ausbleibende Auskunft als eine, die niemand
verantworten kann.

- **Beispiel:** Ein Agent für Bescheidentwürfe läuft unter Zitierzwang. Zur Frage nach einer Härtefallregel,
  die im indizierten Bestand nicht vorkommt, antwortet er, dass sich dazu nichts feststellen lässt — und
  nennt, in welchen Beständen er gesucht hat.

---

### Konfidenz

Ein Maß dafür, wie gut die abgerufenen Textstellen die gestellte Frage tragen. Sie wird an der Antwort
ausgewiesen, damit sichtbar ist, ob die Auskunft belastbar ist oder nachgeprüft gehört.

**Anhaltspunkte:**
- **0,9–1,0** — sehr belastbar, eindeutig einschlägig
- **0,7–0,9** — belastbar, voraussichtlich einschlägig
- **0,5–0,7** — unsicher, könnte einschlägig sein
- **< 0,5** — nicht belastbar, voraussichtlich nicht einschlägig

Konfidenz ist mehr als der Ähnlichkeitswert der Vektorsuche: In sie gehen auch ein, wie einig sich mehrere
Fundstellen sind und ob die Frage überhaupt vollständig abgedeckt ist. Ein niedriger Wert kann den
[Zitierzwang](#zitierzwang) auslösen.

- **Beispiel:** Eine Auskunft zur Zuständigkeit erscheint mit niedriger Konfidenz, weil zwei
  Dienstanweisungen einander widersprechen. Die Sachbearbeitung sieht das an der Antwort und klärt vor der
  Verwendung.

---

### Berechtigungsdurchsetzung zur Abfragezeit

Rechte werden **innerhalb der Vektorsuche** durchgesetzt: Die lesbaren Wissensbibliotheken gehen als
Metadatenfilter in die Abfrage ein. Nicht freigegebene Chunks werden nie geladen und nie bewertet.

**Ablauf:**
1. Das System ermittelt die lesbaren Wissensbibliotheken der fragenden Person und schneidet sie mit dem
   [Suchbereich des Chats](#suchbereich-eines-chats) — bestimmt durch „Wissen nutzen" und @-Referenzen,
   oder durch den gebundenen Agenten
2. Die Frage lautet etwa: „Wie ist die Regelung zu Zulagen?"
3. Die Vektorsuche liefert nur Chunks, deren Bibliothek im ermittelten Suchbereich liegt
4. Es erscheinen ausschließlich Inhalte, für die eine Leseberechtigung besteht

**Warum das wichtig ist:**
- Niemand erfährt durch die Suche von der Existenz von Unterlagen, die er nicht lesen darf
- Ergebnisse wirken vollständig, obwohl gefiltert
- Keine Nachfilterung nötig — die Suche selbst ist rechtebewusst
- Rechteänderungen wirken sofort, ohne Neu-Indizierung

Ein Agent liest **immer** mit den Rechten der aufrufenden Person. Einen Modus, in dem er mit eigenen Rechten
liest, gibt es nicht.

---

### Halluzination

Wenn ein Sprachmodell falsche Angaben erzeugt oder Sachverhalte erfindet.

**Wie OPAA gegensteuert:**
- RAG bindet die Antwort an abgerufene Textstellen
- Die [Quellenbindung](#fundstelle-und-quellenbindung) macht jede Aussage nachprüfbar
- Der [Zitierzwang](#zitierzwang) unterbindet die Antwort, wo der Beleg fehlt
- Die [Konfidenz](#konfidenz) zeigt an, wenn die Grundlage dünn ist

---

## Agenten, Prompts und Werkzeuge

### Aufgabenbeschreibung eines Agenten

Die verbindliche Festlegung, was ein Agent tut und was nicht: Zweck, Zuschnitt der Aufgabe, gebundener
Wissensbereich, erlaubte Werkzeuge, Grenzen und der Punkt, an dem eine Person entscheiden muss.

Sie ist kein Prompt, sondern die fachliche Beschreibung, an der ein Agent geprüft und freigegeben wird —
lesbar für die Fachaufsicht, nicht nur für die Person, die ihn gebaut hat. Ohne sie ist ein Agent nicht
prüfbar und damit nicht [verteilbar](#verteilbarkeit).

- **Beispiel:** „Prüft eingehende Widersprüche auf Frist, Form und Zuständigkeit. Liest ausschließlich aus
  der Bibliothek ‚Rechtsquellen Soziales'. Erstellt einen Vermerk, versendet nichts. Bei unklarer
  Zuständigkeit legt er dem Sachgebiet vor, statt zu entscheiden."

---

### Agenten-Prüfstand

Eine Umgebung, in der ein Agent vor der Freigabe an festgelegten Fällen durchgespielt wird — mit
Soll-Ergebnis, sichtbaren Fundstellen und einem Vergleich gegen die vorherige Fassung.

Der Prüfstand macht die Freigabe zu einer belegbaren Entscheidung statt zu einem Bauchgefühl. Er zeigt
außerdem, was eine Änderung an anderer Stelle kaputt macht: Wer den Prompt einer viel genutzten Fassung
anpasst, sieht vorher, welche der hinterlegten Fälle danach anders ausgehen.

- **Beispiel:** Vor der Freigabe der Fassung 4 laufen dreißig abgelegte Widersprüche durch den Prüfstand.
  Achtundzwanzig ergeben das erwartete Ergebnis, zwei weichen ab — die Freigabe wartet, bis geklärt ist,
  warum.

---

### Prüfagent

Ein Agent, dessen Aufgabe nicht die Bearbeitung ist, sondern die Kontrolle des Ergebnisses eines anderen
Agenten oder eines Menschen: Er prüft gegen die hinterlegten Quellen, sucht nach unbelegten Aussagen,
fehlenden Pflichtangaben und Widersprüchen und meldet Befunde, statt selbst zu ändern.

Das Vier-Augen-Prinzip ist in der Verwaltung ein vertrautes Mittel; der Prüfagent überträgt es auf
KI-Ergebnisse. Er ersetzt die menschliche Verantwortung nicht — er sorgt dafür, dass sie an einer
vorbereiteten Stelle ausgeübt wird.

- **Beispiel:** Ein Prüfagent liest jeden Bescheidentwurf gegen die Rechtsquellenbibliothek und merkt an,
  wenn eine Rechtsbehelfsbelehrung fehlt oder eine zitierte Fassung nicht mehr gilt.

---

## Modelle und zentrale Steuerung

### Lokal betriebene Modelle

Sprach- und Einbettungsmodelle, die auf Rechnern der Behörde oder ihres Rechenzentrums laufen. Kein Text
verlässt dabei das Haus, und der Betrieb funktioniert ohne Netzanbindung.

In OPAA sind lokal betriebene Modelle die **Voreinstellung**, nicht die Ausweichlösung. Eine nicht
konfigurierte Installation spricht nicht nach außen. Ein Anbieter im Netz ist möglich, aber eine bewusste
Freigabe der Systemverwaltung.

- **Beispiel:** Ein Sozialamt betreibt Chat- und Einbettungsmodell auf eigener Hardware. Die Anlagen mit
  Sozialdaten werden indiziert, ohne dass ein Byte davon eine externe Schnittstelle sieht.

---

### Modell-Policy als Obergrenze

Die zentrale Festlegung der Systemverwaltung, welche Modelle für welche Aufgaben zulässig sind. Sie wirkt als
**Obergrenze**, nicht als Vorschlag: Eine Ebene darunter — Space, Asset, einzelne Person — kann strenger
sein, aber nie großzügiger.

Damit ist die Frage „Wer darf welches Modell benutzen?" einmal beantwortet und nicht in jedem Referat neu.
Eine Änderung an der Obergrenze wirkt sofort überall. Beschränkungen, die an den Daten hängen, kommen
hinzu: Eine [Wissensbibliothek](#wissensbibliothek) mit der Vorgabe „nur lokale Modelle" senkt die Grenze
überall dort, wo sie verwendet wird — unabhängig davon, wer wo fragt.

- **Beispiel:** Die Systemverwaltung erlaubt hausweit ein lokales Modell und, nach Freigabe, ein externes
  für Übersetzungen. Ein Referat schließt für seinen Space auch das aus. Ein Beschäftigter kann die Grenze
  in keinem Fall anheben.

---

### Multi-Modell-Betrieb

Verschiedene Modelle für verschiedene Aufgaben, um Qualität, Geschwindigkeit und Kosten auszubalancieren.

**Beispiel:**
- **Einbettungsmodell** (klein, lokal): wandelt Dokumente und Fragen in Vektoren
- **Antwortmodell** (größer, lokal): formuliert die Antwort aus den abgerufenen Stellen
- **Zusammenfassungsmodell** (klein): erzeugt Vorschautexte

Die Zuordnung ist Sache der [Modell-Policy](#modell-policy-als-obergrenze). Ein Modell im Netz kommt nur dort
in Betracht, wo es ausdrücklich freigegeben ist und die verarbeiteten Daten es zulassen.

---

## Datenpipeline

### Wissensquelle

Jedes System, in dem der Wissensbestand des Hauses liegt.

**Beispiele:**
- Wiki oder Intranet des Hauses
- Netzlaufwerke und Dateiablagen
- Funktionspostfächer
- Fachverfahren und Vorgangsbearbeitung
- Dokumentenmanagement und elektronische Akte
- Uploads einzelner Beschäftigter

---

### Konnektor

Software, die weiß, wie sie sich mit einer bestimmten Wissensquelle verbindet, Dokumente daraus liest und —
wo die Quelle es hergibt — deren Leserechte mitführt.

Jede Konnektorquelle indiziert in **genau eine** Wissensbibliothek. Damit bleibt der Rechteanker eindeutig.

- **Beispiel:** Ein Konnektor auf das Netzlaufwerk des Bauamts liest die Ablage „Bauleitplanung" in die
  gleichnamige Wissensbibliothek und übernimmt die Verzeichnisrechte aus dem Quellsystem.

---

### Dokumentenverarbeitung

Die Schritte, mit denen OPAA ein Dokument durchsuchbar macht.

1. **Auffinden** — Dokumente in den Wissensquellen entdecken
2. **Auslesen** — Text extrahieren (PDF, DOCX, XLSX, PPTX, Markdown, Text)
3. **Zerlegen** — in Chunks aufteilen
4. **Einbetten** — in Vektoren umwandeln
5. **Speichern** — Vektoren ablegen, Originaldatei im Speicher-Backend
6. **Indizieren** — für die Suche verfügbar machen

---

### Upload durch Beschäftigte

Das Hochladen eines Dokuments über eine Oberfläche (Web, REST-API) — im Unterschied dazu, dass OPAA
Dokumente über einen Konnektor aus einer Quelle abholt.

**Unterschiede zur Aufnahme über Konnektoren:**
- Der Anstoß kommt von einer Person, nicht vom System
- Er erfolgt bei Bedarf, nicht nach Zeitplan oder Ereignis
- Das Dokument landet ohne andere Angabe in der persönlichen Wissensbibliothek
- Die Originaldatei bleibt im Speicher-Backend erhalten

Siehe [Daten-Indizierung & RAG](./features/data-indexing-rag.md).

---

### Speicher-Backend

Der Dateispeicher für Originaldateien. Er ist von der Vektor-Datenbank getrennt: Hier liegen
die PDF- und DOCX-Dateien für Download und erneute Verarbeitung, dort die Embeddings für die Suche.

**Gebaut ist genau ein Weg: ein Verzeichnis.** OPAA schreibt und liest gegen ein konfiguriertes
Verzeichnis; eine Abstraktion über mehrere Speicherarten gibt es nicht. Das Dateisystem ist der
Vertrag — was dahinter hängt, entscheidet der Betrieb:

- **Lokales Dateisystem** — Erprobung und kleine Installationen
- **Netzlaufwerk** (SMB/NFS) — der Regelfall im Haus; es wird vom Betriebssystem auf das konfigurierte
  Verzeichnis eingehängt und braucht deshalb keinen eigenen Weg in der Anwendung
- **Objektspeicher** (S3-kompatibel) — **Zielbild, nicht gebaut**; er ist der einzige Fall, der einen
  eigenen Pfad im Code braucht

Näheres in [Betrieb & Deployment](./features/deployment-infrastructure.md#speicher-backends).

---

### Indizierungsanstoß: nach Zeitplan oder nach Ereignis

**Nach Zeitplan (Abfrage):** OPAA sieht in regelmäßigen Abständen in der Quelle nach. Einfach umzusetzen und
mit jeder Quelle möglich; Änderungen wirken erst nach dem nächsten Lauf.

**Nach Ereignis (Meldung):** Die Quelle meldet OPAA eine Änderung. Deutlich schneller, setzt aber voraus,
dass die Quelle solche Meldungen abgibt.

Beides ist vorgesehen; die Wahl hängt an der Quelle und daran, wie aktuell der Bestand sein muss.

---

### Bestände mehrfach verwenden

Ein Bestand, der an mehreren Stellen gebraucht wird, wird nicht kopiert: Dieselbe
[Wissensbibliothek](#wissensbibliothek) wird in mehreren Spaces assoziiert oder an weitere Personen und
Gruppen freigegeben. Eine Fassung, eine Pflegestelle, keine Vervielfachung von Chunks.

**Stand:** Durch das Asset-Modell gelöst. Das frühere Konzept eines raumübergreifenden Dokument-Teilens samt
seiner offenen Sicherheitsfragen ist gegenstandslos — siehe
[Dokument-Teilen](./features/document-sharing.md) (überholt).

---

## Sicherheit, Nachweis und Mitbestimmung

### Revisionssicheres Protokoll

Die unveränderliche Aufzeichnung, wer wann was getan hat und was dabei herauskam. „Revisionssicher" heißt:
nachträglich nicht änderbar, vollständig, mit Zeitbezug, und der Zugriff auf das Protokoll wird selbst
protokolliert.

**Was festgehalten wird:**
- Abfragen: Zeitpunkt, handelnde Person, Suchbereich, Zahl der Fundstellen
- Zugriffe auf Dokumente: Zeitpunkt, Person, Dokument, Ergebnis
- Verwaltungshandlungen: Rechteänderungen, Freigaben, Änderungen an Assets und an der Modell-Policy
- Zugriffe auf das Protokoll selbst

**Wozu:**
- Nachweis gegenüber Aufsicht, Datenschutz und Rechnungsprüfung
- Rekonstruktion eines Vorgangs Jahre später
- Fehlersuche im Betrieb

Es ist ausdrücklich **kein Auswertungspfad über Personen**: Aus dem Protokoll wird keine Rangliste und keine
Leistungsbewertung gezogen. Siehe [Mitbestimmungsfähigkeit](#mitbestimmungsfähigkeit).

- **Beispiel:** Zwei Jahre nach einem Bescheid fragt das Gericht, worauf sich die Behörde gestützt hat. Aus
  dem Protokoll ergibt sich, welche Fassung welcher Dienstanweisung zum damaligen Zeitpunkt in die Antwort
  eingegangen ist.

---

### C5-Fähigkeit

Die Eigenschaft, so gebaut und dokumentiert zu sein, dass ein Betreiber eine Prüfung nach dem
Kriterienkatalog C5 des BSI mit OPAA im Prüfumfang bestehen kann.

**Ausdrücklich nicht dasselbe wie „zertifiziert".** C5 prüft den **Betrieb** eines Dienstes, nicht ein Stück
Software. OPAA ist nicht zertifiziert und wird es als Software auch nicht werden; eine Formulierung wie
„C5-zertifiziert" wäre schlicht falsch. Was OPAA leisten kann, ist die Zuarbeit: nachvollziehbare
Konfiguration, sichere Voreinstellungen, Protokollierung, Software-Stückliste, Betriebsdokumentation.

- **Beispiel:** Ein Rechenzentrum lässt seinen Betrieb prüfen und hat OPAA im Prüfumfang. Die Fragen des
  Prüfers nach Protokollierung, Rechtekonzept und Schwachstellenmanagement lassen sich aus dem beantworten,
  was OPAA mitbringt — die Aussage über das Ergebnis trifft der Prüfer, nicht das Produkt.

---

### SCIM

*System for Cross-domain Identity Management* — ein standardisiertes Protokoll, über das ein
Verzeichnisdienst Konten und Gruppen an eine Anwendung übergibt: anlegen, ändern, deaktivieren, löschen.

Der Unterschied zu einem eigenen Abgleich ist der Lebenszyklus. Anmeldung allein sagt, wer gerade da ist;
SCIM sagt auch, wer gegangen ist. Ohne diesen Weg bleiben Konten und Rechte bestehen, nachdem jemand die
Behörde verlassen hat — genau der Befund, der bei jeder Prüfung auffällt.

- **Beispiel:** Eine Beschäftigte wechselt vom Ordnungsamt ins Bauamt. Der Verzeichnisdienst meldet den
  Gruppenwechsel über SCIM; ihre Leserechte auf die Bibliotheken des Ordnungsamts enden damit, ohne dass
  jemand in OPAA nachpflegen muss.

---

### Organisation als Mandantengrenze

Die Organisation ist die **harte** Trennlinie einer Installation: Keine Freigabe, keine Suche, kein
Katalogtreffer und keine Verwaltungshandlung überschreitet sie.

„Hart" heißt: Die Grenze ist nicht eine Einstellung, die man lockern kann, sondern eine Eigenschaft des
Datenmodells. Sie ist damit die Voraussetzung dafür, dass ein Rechenzentrum mehrere Häuser auf einer
Installation betreiben kann, ohne dass eines vom anderen etwas sieht. Sie ist zugleich die Obergrenze der
[Verteilungsstufen](#verteilungsstufe).

- **Beispiel:** Ein kommunales Rechenzentrum betreibt OPAA für elf Gemeinden. Eine Suche in Gemeinde A
  liefert nie einen Chunk aus Gemeinde B, und die Systemverwaltung von A sieht die Nutzerinnen von B nicht.

---

### Mitbestimmungsfähigkeit

Die Eigenschaft, mit den Anforderungen der Personalvertretung vereinbar zu sein, ohne dass dafür Funktionen
abgeschaltet werden müssen.

OPAA erzeugt Daten mit Personenbezug, und ein Rollout beginnt in aller Regel nicht ohne Dienstvereinbarung.
Mitbestimmungsfähigkeit bedeutet konkret: Sichtbarkeit ist eine Handlung und keine Automatik, der
persönliche Bereich bleibt unbeobachtet, Auswertungen sind aggregiert, und einen personenbezogenen
Auswertungspfad gibt es nicht — nicht abgeschaltet, sondern nicht gebaut. Ranglisten existieren nicht.

Damit wird die Dienstvereinbarung zu einer Konfigurationsaufgabe statt zu einem Projektrisiko.

- **Beispiel:** Der Personalrat fragt, ob sich sehen lässt, wer wie viele Fragen stellt. Die Antwort ist,
  dass Auswertungen nur je Organisationseinheit und ab einer Mindestgröße möglich sind — eine Ansicht je
  Person ist im Produkt nicht vorgesehen.

---

### Berechtigungs-Vererbung

Dokumente führen die Rechte ihres Quellsystems mit.

- **Beispiel:** Ein Ordner auf dem Netzlaufwerk ist nur für das Personalreferat lesbar. Nach der Indizierung
  gilt dieselbe Beschränkung in OPAA — die Inhalte erscheinen für niemanden sonst in der Suche.

Die Grundlage dafür ist die Anbindung an den Verzeichnisdienst des Hauses, aus dem Personen und Gruppen
stammen (siehe [SCIM](#scim)).

---

### Verschlüsselung

Daten so kodieren, dass nur Berechtigte sie lesen können.

- **Auf dem Transportweg** — verschlüsselt während der Übertragung im Netz (TLS/HTTPS)
- **Im Ruhezustand** — verschlüsselt auf dem Datenträger
- **Ende zu Ende** — bereits auf dem Gerät verschlüsselt, für den Server nicht lesbar

---

## Verwaltungs-Spezifika

### Leichte Sprache und Amtssprache

Zwei entgegengesetzte Umformulierungen desselben Sachverhalts, die beide zum Alltag einer Behörde gehören.

**Leichte Sprache** richtet sich nach festen Regeln: kurze Hauptsätze, ein Gedanke je Satz, keine
Verschachtelung, keine Fachwörter ohne Erklärung, keine Abkürzungen. Sie ist keine Stilfrage, sondern für
viele Empfänger die Voraussetzung dafür, einen Bescheid zu verstehen — und ein Baustein der Barrierefreiheit.

**Amtssprache** geht in die Gegenrichtung: aus einem Entwurf wird eine Fassung, die den Anforderungen an
Bescheide und Vermerke genügt — richtiger Fachbegriff, saubere Rechtsbezüge, angemessene Form der Anrede.

Beide sind Textwerkzeuge auf demselben Text; welche Richtung gebraucht wird, entscheidet der Empfänger.

- **Beispiel:** Aus „Der Antrag wird gemäß § 60 Abs. 1 SGB I abgelehnt, da die erforderlichen Nachweise
  trotz Aufforderung nicht beigebracht wurden" wird in Leichter Sprache: „Wir können Ihren Antrag nicht
  bewilligen. Der Grund: Uns fehlen Unterlagen. Wir haben Sie am 3. März darum gebeten." Der rechtliche
  Gehalt bleibt; nur der Zugang ändert sich.

---

## Betrieb

### Betrieb im eigenen Haus (On-Premises)

OPAA läuft auf Servern der Behörde oder ihres Rechenzentrums.

**Vorteile:**
- Vollständige Datenhoheit — Daten verlassen die eigene Infrastruktur nicht
- Keine Abhängigkeit von externen Schnittstellen
- Betrieb ohne Netzanbindung möglich
- Erfüllt strenge Anforderungen an Datenschutz und Geheimhaltung

**Preis dafür:**
- Infrastruktur, Sicherungen und Sicherheitsaktualisierungen liegen beim Betreiber

---

### Betrieb ohne Netzanbindung (air-gapped)

Eine Installation ohne jede Verbindung nach außen. Mit lokal betriebenen Modellen und ohne externe
Konnektoren ist das ein **vorgesehenes** Szenario, keine Ausnahme.

Was dafür nötig ist, geht über eine Einstellung hinaus: übertragbare Abbilder, mitgelieferte Modellgewichte,
Aktualisierung ohne Paketquellen im Netz, eine Software-Stückliste zum Mitliefern.

- **Beispiel:** Ein Bereich, in dem Sicherheitsüberprüfungen bearbeitet werden, betreibt OPAA in einem Netz
  ohne Übergang. Die Lieferung kommt als Datenträger, die Modelle liegen bei.

---

### Cloud-Deployment

Der Betrieb auf gemieteter Infrastruktur eines Anbieters statt im eigenen Rechenzentrum. Technisch ist es
dieselbe Installation an einem anderen Ort; der Unterschied liegt darin, wer die Maschinen betreibt, auf
denen die Daten liegen.

**Es ist kein eigenes Betriebsmodell von OPAA.** OPAA läuft dort, wo eine Container-Umgebung und
PostgreSQL mit pgvector stehen; wo das ist, entscheidet die verantwortliche Stelle. Entscheidend ist dabei
nicht der Ort, sondern die Verantwortlichkeit — ein Rechenzentrum der Verwaltung ist ebenfalls nicht das
eigene Haus und trotzdem unproblematisch, weil die Verantwortung dort vertraglich geregelt ist. Für einen
erheblichen Teil der Verwaltungsdaten ist die Verarbeitung außerhalb der eigenen Verantwortungssphäre
rechtlich ausgeschlossen; § 30 AO ist das schärfste Beispiel. Ein legitimer Fall bleibt die Erprobung und
Schulung außerhalb des eigenen Hauses — unter der Bedingung, dass dort keine echten Daten liegen.

- **Beispiel:** Ein Haus stellt vor der Beschaffung eine Demonstrationsumgebung auf gemieteter
  Infrastruktur bereit, gefüllt mit synthetischen Vorgängen. Für den Wirkbetrieb wandert dieselbe
  Installation anschließend ins Rechenzentrum des Landes.

Siehe [Betrieb & Deployment](./features/deployment-infrastructure.md).

---

### Container / Docker

Eine Methode, OPAA mit allen Abhängigkeiten so zu verpacken, dass es überall gleich läuft.

**Warum das zählt:**
- Die Installation ist reproduzierbar statt handgemacht
- Aktualisieren heißt, ein neues Abbild einzuspielen
- Dieselbe Lieferung funktioniert in der Testumgebung und im Wirkbetrieb

---

### Kubernetes

Ein System zur Verwaltung vieler Container.

**Was es leistet:**
- Mehrere Instanzen für Ausfallsicherheit
- Automatischer Neustart ausgefallener Instanzen
- Verteilung der Last
- Wachstum ohne Umbau

**Wofür:** größere Häuser und Rechenzentren, die mehrere Organisationen bedienen.

---

### Konfigurationsverwaltung

Wege, OPAA ohne Eingriff in den Quellcode anzupassen.

- **Umgebungsvariablen** — etwa die Wahl des Modellanbieters
- **Konfigurationsdateien** — YAML-Dateien mit Einstellungen
- **Verwaltungsoberfläche** — Einstellungen im Browser

So wird ein Modell ausgetauscht, ohne dass ein Referat seine Agenten anfassen muss.

---

### Latenz

Die Zeit von der Frage bis zur Antwort.

**Anhaltspunkte:**
- Vektorsuche: unter 500 ms
- Antwortgenerierung: 1–3 Sekunden
- Insgesamt: unter 4 Sekunden

**Einflussgrößen:** Größe des Bestands, gewähltes Modell, verfügbare Hardware. Reranking und Zitierzwang
kosten Zeit — und sind sie wert.

---

### Caching

Bereits berechnete Ergebnisse aufheben, statt sie erneut zu berechnen.

- Häufige Fragen nicht erneut einbetten
- Rechte nicht bei jeder Anfrage neu ermitteln
- Unveränderte Dokumente nicht erneut einbetten (erkannt über Prüfsummen)

**Preis dafür:** mehr Speicher gegen weniger Rechenaufwand.

---

### Stapelverarbeitung

Mehrere Elemente gemeinsam statt einzeln verarbeiten — etwa beim Einbetten während der Indizierung. Tausend
Dokumente in Bündeln sind deutlich schneller als tausend einzelne Vorgänge.

---

## Verwandte Begriffe

### Wissensgraph

Eine strukturierte Darstellung von Sachverhalten und ihren Beziehungen zueinander.

**Beispiel:**
```
Dienstanweisung 12/2024
  ├── ersetzt: Dienstanweisung 04/2019
  ├── gilt für: Amt 50
  └── beruht auf: § 84 SGB IX

Vorgang 32.1-114/2025
  ├── zuständig: Sachgebiet 32.1
  └── betrifft: Widerspruch
```

**Stand in OPAA:** als Ergänzung des Vektor-Retrievals in Phase 3 vorgesehen, nicht im Fundament. Die
Recherchegrundlage steht in [GraphRAG.md](./GraphRAG.md).

---

## Schnellreferenz

| Begriff | Kurz | Beispiel |
|---|---|---|
| **Belegbarkeit** | Jede Aussage auf eine prüfbare Quelle zurückführbar | Antwort nennt Dienstanweisung und Abschnitt |
| **Verteilbarkeit** | Können wandert von einer Person in die Organisation | Agent eines Referats wird hausweiter Standard |
| **RAG** | Abruf plus Erzeugung | Frage → Textstellen abrufen → Modell antwortet |
| **Embedding** | Vektor, der Bedeutung abbildet | [0,21, -0,18, 0,45, …] |
| **Chunk** | Abschnitt eines Dokuments | Abschnitt „Telearbeit" der Dienstanweisung |
| **Erklärbares Chunking** | Die Zerlegung ist einsehbar | Sichtbar, dass eine Gebührentabelle zerschnitten wurde |
| **Semantische Suche** | Suche nach Sinn | „mobiles Arbeiten" ≈ „von zu Hause arbeiten" |
| **Hybride Suche** | Semantik und Volltext zusammen | Aktenzeichen exakt, Umschreibung sinngemäß |
| **Reranking** | Zweite, genauere Bewertung der Treffer | 50 grobe Treffer → 5 einschlägige |
| **Fundstelle** | Der Nachweis in der Antwort | „Dienstanweisung 12/2024, Abschnitt 4.2" |
| **Quellenbindung** | Jede Aussage an ihre Textstelle gebunden | Sprung in den Absatz, nicht auf die Datei |
| **Zitierzwang** | Ohne Beleg keine Antwort | „Dazu lässt sich nichts feststellen" |
| **Konfidenz** | Wie belastbar die Grundlage ist | 0,4 — Auskunft vor Verwendung prüfen |
| **Space** | Thematischer Arbeitsraum, flach; trägt Chats und Artefakte | „Bauleitplanung" |
| **Suchbereich eines Chats** | Gesteuert am Chat über „Wissen nutzen" und @-Referenzen, nicht per Anfrage | Schalter aus + `@Rechtsquellen-Denkmalschutz` schränkt auf eine Bibliothek ein |
| **KI-Asset** | Benanntes, teilbares Objekt mit Eigentümer und Rechten | Agent, Prompt-Bibliothek, Wissensbibliothek |
| **Wissensbibliothek** | Dokumentencontainer und Rechteanker der Suche | „Rechtsquellen Soziales" |
| **Assoziation** | Asset in einem Space bereitstellen; gewährt keine Rechte | „Rechtsquellen" in fünf Team-Spaces |
| **Verteilungsstufe** | Reichweite der Freigabe | persönlich → Team → Fachbereich → organisationsweit |
| **Aufgabenbeschreibung** | Was ein Agent tut und was nicht | „Prüft Frist, Form, Zuständigkeit; versendet nichts" |
| **Agenten-Prüfstand** | Durchlauf an Testfällen vor der Freigabe | 30 Altfälle, 2 Abweichungen |
| **Prüfagent** | Kontrolliert Ergebnisse gegen die Quellen | Findet fehlende Rechtsbehelfsbelehrung |
| **Lokale Modelle** | Modelle auf eigener Hardware; Voreinstellung | Chat und Einbettung im eigenen Rechenzentrum |
| **Modell-Policy** | Zentrale Obergrenze der zulässigen Modelle | Referat darf strenger sein, nie großzügiger |
| **System-Admin** | Systemweite Rolle je Organisation | Konnektoren einrichten, Team-Spaces anlegen |
| **Space-Rolle** | Mitarbeit und Kuratierung im Raum | Member, Curator, Admin |
| **Asset-Rolle** | Tatsächlicher Zugriff auf ein Asset | User, Viewer, Editor, Manager, Owner |
| **Konnektor** | Anbindung an eine Wissensquelle | Netzlaufwerk → eine Wissensbibliothek |
| **Revisionssicheres Protokoll** | Unveränderliche Aufzeichnung des Handelns | Rekonstruktion eines Vorgangs nach zwei Jahren |
| **C5-Fähigkeit** | Auf die Prüfung des Betreibers ausgelegt — **nicht** zertifiziert | Betreiber besteht die Prüfung mit OPAA im Umfang |
| **SCIM** | Standard für den Lebenszyklus von Konten | Wechsel des Amts beendet alte Leserechte |
| **Mandantengrenze** | Organisation als harte Trennlinie | Elf Gemeinden, eine Installation, keine Überschneidung |
| **Mitbestimmungsfähigkeit** | Kein personenbezogener Auswertungspfad | Auswertung nur aggregiert, keine Ranglisten |
| **Leichte Sprache** | Feste Regeln für verständliche Texte | Ablehnung in kurzen Hauptsätzen |
| **Halluzination** | Modell erfindet Sachverhalte | „Die Frist beträgt drei Monate" — frei erfunden |
| **Latenz** | Zeit bis zur Antwort | Ziel unter 4 Sekunden |

---

## Weiterlesen

- [VISION.md](./VISION.md) — Nordstern, Leitprinzipien, Themenbereiche und Phasen
- [USE-CASES.md](./USE-CASES.md) — wie sich das im Arbeitsalltag anfühlt
- [STATUS.md](./STATUS.md) — was davon heute gebaut ist
- [INDEX.md](./INDEX.md) — vollständiger Dokumentationsindex
- [GETTING-STARTED.md](./GETTING-STARTED.md) — Lesepfade nach Publikum
