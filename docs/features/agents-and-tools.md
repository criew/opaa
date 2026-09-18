# Agenten, Prompts & Werkzeuge

> **Status: Entwurf.** Die [Szenarien](#szenarien) und die [empfohlene Reihenfolge](#empfohlene-reihenfolge) sind der Stand, gegen den Tickets geschnitten werden; die Kapitel zu Onboarding, Prüfstand und Prüfagenten sind weiterhin Zielbild. Wesentliche offene Fragen stehen am Ende.

## Motivation

Ein Assistent, der Fragen beantwortet, spart einer Sachbearbeiterin Minuten. Ein Agent, der eine wiederkehrende Aufgabe erledigt, spart ihrem Sachgebiet Tage — aber nur, wenn drei Bedingungen erfüllt sind, an denen KI-Einführung in der Verwaltung heute regelmäßig scheitert:

1. **Er muss entstehen können.** Ein leeres Textfeld mit der Aufschrift „Systemprompt" ist für den überwiegenden Teil der Beschäftigten kein Angebot, sondern eine Hürde. Wer keinen Prompt schreiben kann, erzeugt kein Asset — und ohne Assets läuft das Verteilungsversprechen ins Leere.
2. **Er muss geprüft werden können.** Ein Agent, der beim ersten Ausprobieren gut aussah, darf nicht deshalb in einem ganzen Amt laufen. Zwischen Entwurf und Freigabe gehört ein Verfahren, dessen Ergebnis man einer Revision vorlegen kann.
3. **Er muss überall dasselbe tun.** Verhält sich eine freigegebene Fassung je nach Arbeitsraum anders, ist der Prüfbericht wertlos und die Freigabe eine Formalie.

Dieses Dokument beschreibt, **was in einem Agenten steckt**, wie er entsteht, wie er geprüft wird, wie kritische Ergebnisse vor der Ausgabe gegengelesen werden und welche Werkzeuge ihm zur Verfügung stehen. Das Rechte-, Assoziations- und Verteilungsmodell ist **nicht** Gegenstand dieses Dokuments; dafür ist [spaces-and-assets.md](./spaces-and-assets.md) das Leitdokument, und es bleibt es auch dort, wo dieses Dokument darauf Bezug nimmt.

---

## Überblick

1. **Ein Agent ist ein Paket.** Verhalten, Wissenszuordnung, Werkzeugrechte und Modellwahl liegen in einem teilbaren Objekt — Empfangende müssen nichts nachbauen.
2. **Der Agent führt seine Wissensbindung selbst mit.** Ein geteilter Agent bringt sein Wissen mit, statt beim Empfänger neu konfiguriert zu werden, und verhält sich überall gleich. Das ist die Voraussetzung dafür, dass eine geprüfte Fassung geprüft bleibt.
3. **Ein Agent liest immer mit den Rechten der aufrufenden Person.** Es gibt keinen Modus, in dem er mit eigenen Rechten liest.
4. **Agenten entstehen in einem geführten Verfahren**, nicht in einem Freitextfeld — beschrieben wie eine Stelle, mit festen Abschnitten.
5. **Struktur ist der eigentliche Gewinn.** Eine gegliederte Beschreibung ist prüfbar, vergleichbar und versionierbar; ein Freitext-Prompt ist keines von dreien.
6. **Vor der Freigabe steht ein automatisierter Prüflauf**, dessen Bericht Teil der Freigabeunterlage ist.
7. **Für kritische Vorgänge prüfen unabhängige Prüfagenten** das Ergebnis gegen dieselben Quellen, bevor es den Menschen erreicht — abgestuft, protokolliert, und ohne die Entscheidung zu ersetzen.
8. **Werkzeuge sind abgestuft:** Textwerkzeuge ohne besondere Umgebung, alles Rechnende in einer isolierten Ausführungsumgebung, alles Schreibende mit menschlicher Freigabe.
9. **Werkzeugaufrufe sind eine Eigenschaft des Modells, keine Selbstverständlichkeit.** Ein Modell, das Werkzeuge nicht verlässlich aufruft, kann keinen Agenten tragen — das ist eine Anforderung an den Modellkatalog und wird gemessen, nicht angenommen (siehe [Werkzeugaufrufe als Modellfähigkeit](#werkzeugaufrufe-als-modellfähigkeit)).

---

## Szenarien

*Die Kapitel dieses Dokuments beschreiben Bausteine. Dieser Abschnitt beschreibt, was eine Sachbearbeiterin davon jeweils merkt — in vier Stufen, von denen jede für sich nützlich ist und jede die vorige voraussetzt.*

### Stufe 1 — Eine geteilte Arbeitsweise ohne Werkzeuge

**Was die Sachbearbeiterin tut.** Sie wählt im Chat den Skill „Vermerk nach Hausvorlage" und schreibt dazu, worum es geht — „Vermerk zur Fristverlängerung im Vorgang 2026/0815". Sie erhält einen Vermerk in der Gliederung, die das Haus verwendet, mit den Belegen aus den Unterlagen, auf die sie Zugriff hat. Den Skill hat nicht sie geschrieben, sondern das Fachreferat, das ihn an ihre Gruppe freigegeben hat.

**Was technisch passiert.** Der Skill ist ein Asset: Anleitung, Beispiele, ein Satz zum Anwendungsfall. Bei der Anwendung wird er als eigener, gekennzeichneter Block in den Systemvorspann gestellt — derselbe Mechanismus, mit dem heute schon die Gesprächsnotiz dorthin gelangt. Der Modellaufruf bleibt einschüssig; es gibt keine Schleife, keine Werkzeuge, keinen neuen Laufzeitbegriff.

**Wie das Retrieval hineinspielt.** Unverändert: Die Suche läuft **vor** dem Modellaufruf, der Suchbereich ergibt sich aus Chat und Rechten, die Belegprüfung sitzt wie immer dahinter.

**Die Grundlage gibt die Person mit.** Ein Satz wie der oben ist ein Auftrag mit einem Aktenzeichen, keine Frage — und die gemessene Suchqualität des Produkts beruht auf Fragen. Stufe 1 ist deshalb auf Fälle geschnitten, in denen die Person die Grundlage benennt: ein Dokument im Chat, eine Auswahl von Fundstellen, eine Frage. Die Suchanfrage wird **nicht** aus dem Anweisungstext des Skills gebildet. Andernfalls entscheidet der erste Versuch über eine Enttäuschung, die kein zweiter mehr einholt.

**Voraussetzung.** Der Skill muss ein Asset sein können — Rechte, Freigabe, Auffindbarkeit. Das ist der Grund, warum die Verteilung vor der Laufzeit kommt.

### Stufe 2 — Dieselbe Arbeitsweise, die selbst nachsucht

**Was die Sachbearbeiterin tut.** Sie wählt den Skill „Vollständigkeitsprüfung Förderantrag" und gibt den Antrag hinein. Sie sieht, während gearbeitet wird, was geprüft wird: „sucht nach der Richtlinie", „sucht nach dem Nachweis der Eigenmittel", „sucht nach der Frist". Am Ende steht eine Liste der geforderten Nachweise mit dem jeweiligen Befund — und die Schrittliste bleibt im Gesprächsverlauf nachschlagbar, nicht nur während des Laufs sichtbar.

**Wie der Befund heißen darf — und wie nicht.** Ein Werkzeug-Skill führt in seinem Ergebnis **nicht** die Worte „vollständig" oder „geprüft". Die Befunde heißen **„Fundstelle gefunden" / „keine Fundstelle gefunden" / „unklar"**, und über der Liste steht ein stehender Satz: *Eine nicht gefundene Fundstelle ist kein Nachweis dafür, dass der Nachweis fehlt; gesucht wurde nur in den Unterlagen, auf die Sie Zugriff haben.* Der Grund ist der Fehler, den die Belegvalidierung nicht fängt: Eine echte Fundstelle kann eine falsche Schlussfolgerung tragen — eine Bankauskunft zu einem anderen Vorhaben belegt keine Eigenmittel, und ein Nachweis in einer Bibliothek ohne Zugriff ist nicht abwesend, sondern unsichtbar. Eine maschinelle Vollständigkeitsaussage über einen Antrag, den ein Mensch zeichnet, ist die gefährlichste Ausgabe des ganzen Konzepts; sie wird deshalb nicht erzeugt, sondern durch einen Befund über Fundstellen ersetzt.

**Was nicht geprüft wurde, steht in derselben Liste.** Endet ein Lauf an einer Grenze, erscheint der nicht erreichte Punkt als eigener Zustand **„nicht geprüft"** in derselben Aufzählung wie die übrigen Befunde, nicht als Fließtext-Fußnote: Ein überlesener Hinweis ist derselbe Schaden wie ein fehlender.

**Was technisch passiert.** Der Skill darf Werkzeuge benutzen, und das erste ist die Suche selbst. Das Modell formuliert eine Teilfrage, bekommt Fundstellen zurück, formuliert die nächste — in einer Schleife mit harter Obergrenze für die Zahl der Aufrufe. Jeder Schritt wird protokolliert und der Person im Verlauf angezeigt; die Belegprüfung läuft am Ende über **alle** in diesen Schritten gesammelten Passagen, nicht nur über die des letzten Aufrufs.

**Wie das Retrieval hineinspielt.** Hier ändert es sich, aber nur für diese Stufe: Der gewöhnliche Chat behält das vorgeschaltete Retrieval, Skills und Agenten dürfen nachsuchen, und beide Wege benutzen **dieselbe** Suche. Warum das so bleibt und was dabei nicht verhandelbar ist — insbesondere, dass der **Rechtefilter niemals aus einem Modellargument** kommt, sondern aus dem Aufrufkontext — steht in [Die Suche als Werkzeug](#die-suche-als-werkzeug--und-was-dabei-nicht-verhandelbar-ist).

**Voraussetzung.** Ein Modell, das Werkzeuge verlässlich aufruft, eine Schleife mit Obergrenzen, ein Kanal, der Zwischenschritte sichtbar macht, und ein Protokoll über die Aufrufe. Das ist das Fundament, ohne das keine der folgenden Stufen gebaut werden kann.

### Stufe 3 — Etwas außerhalb von OPAA veranlassen

**Was die Sachbearbeiterin tut.** Am Ende der Prüfung schlägt der Skill vor, im Fachverfahren einen Vorgang mit dem Betreff und der Frist anzulegen. Sie sieht **vorher** genau, was geschehen soll: welches System, welche Felder, welche Werte. Sie bestätigt, ändert oder lehnt ab. Erst die Bestätigung löst die Aktion aus, und sie steht im Protokoll — bestätigt wie abgelehnt.

**Was technisch passiert.** Das Werkzeug liegt außerhalb von OPAA und wird über eine standardisierte Anbindung gerufen; welche Server dafür in Frage kommen, steht in einer **Zulassungsliste der Systemverwaltung** und nicht im Ermessen eines Agenten-Autors. Werkzeuge sind als lesend oder schreibend klassifiziert. Bei einem schreibenden hält die Schleife an, und der beabsichtigte Aufruf wird **als Vorgang gespeichert** — er überlebt einen Neustart und läuft ab, wenn niemand entscheidet (Einzelheiten unter [Schreibende Aktionen mit menschlicher Freigabe](#schreibende-aktionen-mit-menschlicher-freigabe)).

**Wie das Retrieval hineinspielt.** Es liefert die Grundlage der Aktion, nicht die Aktion. Was geschrieben wird, stammt aus Fundstellen oder aus Eingaben der Person — und die Bestätigungskarte zeigt beides **unterscheidbar**, nicht nur nebeneinander: Ein Wert aus einer Fundstelle und ein vom Modell formulierter Wert sind für die zeichnende Person zwei verschiedene Dinge.

**Voraussetzung.** Stufe 2, dazu die Klassifikation der Werkzeuge, der persistente Wartezustand, eine Zulassungsliste und der Weg nach draußen als Kontrollpunkt (siehe [Der Ausgang als Kontrollpunkt](#der-ausgang-als-kontrollpunkt)).

### Stufe 4 — Ein Agent als teilbares Paket

**Was die Sachbearbeiterin tut.** Sie benutzt nicht mehr einen Skill in ihrem Chat, sondern den Agenten „Vorprüfung Widerspruch" ihres Referats. Der Agent bringt seine Aufgabenbeschreibung, seine Wissensbindung, seine Werkzeuge und seine Grenzen mit. Bei Vorgängen mit Außenwirkung liest ein Prüfagent das Ergebnis gegen dieselben Quellen, bevor sie es sieht. Wer den Agenten freigegeben hat und gegen welche Prüffälle, kann sie nachlesen.

**Was technisch passiert.** Der Agent ist ein deklaratives Objekt, kein Code — entstanden im geführten Onboarding, geprüft im Prüfstand, versioniert als ein Paket. Ein Lauf hat einen festgehaltenen Zwischenstand, damit ein Neustart ihn nicht vernichtet, ein Budget, damit er endet, und eine Abbruchmöglichkeit für die Person.

**Wie das Retrieval hineinspielt.** Der Agent führt seine Wissensbindung selbst mit — das ist die Bedingung dafür, dass eine geprüfte Fassung überall dasselbe tut. Gelesen wird ausschließlich mit den Rechten der aufrufenden Person.

**Voraussetzung.** Alles Vorherige, dazu der Agent als Asset-Typ, das Onboarding, der Prüfstand und ein Laufbegriff mit Zwischenstand.

**Was ausdrücklich erst danach kommt.** Zeitgesteuerte oder ereignisgesteuerte Auslösung und eine allgemeine Ausführungsumgebung für Code. Beides wirft eine Frage auf, die vorher nicht beantwortet ist: Ein Agent, den niemand aufgerufen hat, arbeitet mit wessen Rechten? Und Code, den ein Modell erzeugt hat, läuft wo? Solange diese Fragen offen sind, ist die Stufe nicht geschnitten.

---

## Erzwungen oder gemessen

*Die wichtigste Unterscheidung des ganzen Dokuments, und die, an der ein Prüfgespräch entweder trägt oder zusammenbricht: Welche Eigenschaft hält, weil die Anwendung sie erzwingt — und welche hält, soweit ein Modell sich daran hält.*

| Eigenschaft | Art | Woran sie hängt |
|---|---|---|
| **Suchbereich und Rechtefilter** | **erzwungen** | Der Filter sitzt in der Suchanfrage und stammt aus dem Aufrufkontext. Kein Modellargument, kein Text in einem Dokument ändert ihn |
| **Obergrenzen eines Laufs** (Aufrufe, Laufzeit, gesammelte Menge, gleichzeitige Läufe) | **erzwungen** | Die Schleife zählt und bricht ab; das Modell wird nicht gebeten, sich zu mäßigen |
| **Bestätigungspflicht vor einer schreibenden Aktion** | **erzwungen** | Sie folgt aus der Klassifikation des Werkzeugs und wird an der Ausführung durchgesetzt, nicht daran, dass das Modell fragt |
| **Belegvalidierung** | **erzwungen** | Sie läuft nach der Erzeugung gegen die tatsächlich abgerufenen Passagen |
| **Anweisungstreue gegenüber Werkzeugergebnissen** | **gemessen** | Die Reihenfolge des Aufrufs (Datenblock gekennzeichnet, verbindliche Regeln danach) senkt die Trefferwahrscheinlichkeit einer eingebetteten Anweisung; sie schließt sie nicht aus |
| **Grenztreue und Befugnistreue eines Agenten** | **gemessen** | Der Prüfstand prüft gegen Fälle; ein Fall, den niemand aufgeschrieben hat, ist nicht geprüft |
| **Belegtreue der Formulierung** (sagt „nicht feststellbar", wo nichts belegt ist) | **gemessen** | Modellverhalten; die Validierung fängt das falsche Zitat, nicht die vorschnelle Aussage |
| **Zuverlässigkeit des Werkzeugaufrufs** | **gemessen** | Eigenschaft der Kombination aus Modell, Laufzeit und Konfiguration, siehe [Werkzeugaufrufe als Modellfähigkeit](#werkzeugaufrufe-als-modellfähigkeit) |

**Was daraus folgt, ist keine Nuance, sondern eine Bauregel.** Eine gemessene Eigenschaft wird mit einer **Zahl** berichtet, nie mit einem Häkchen: „an dieser Fallbasis, mit diesem Modell, so oft bestanden". Wer sie wie eine erzwungene führt, macht die erzwungenen schwächer und die gemessenen nicht stärker — und im Prüfgespräch bricht die Aussage genau an der Stelle zusammen, an der die Frage lautet „woran haben Sie das festgestellt".

**Und für die schreibende Stufe folgt daraus eine harte Auflage:** **Keine schreibende Wirkung hängt an einer gemessenen Eigenschaft.** Dass ein Modell eine eingebettete Anweisung nicht befolgt, darf nie der Grund sein, warum nichts Falsches geschrieben wurde — der Grund ist die Bestätigung, und die hängt an der Klassifikation des Werkzeugs.

---

## Der Agent als teilbares Paket

### Was ein Agent bündelt

| Bestandteil | Inhalt |
|---|---|
| **Aufgabenbeschreibung** | die gegliederte Beschreibung dessen, was der Agent tut — siehe [Agenten-Onboarding](#agenten-onboarding) |
| **Wissenszuordnung** | die ausdrücklich gebundenen Wissensbibliotheken |
| **Werkzeugrechte** | welche Werkzeuge er benutzen darf und welche seiner Aktionen freigabepflichtig sind |
| **Modellwahl** | Modell und Parameter, stets innerhalb der zentralen Vorgaben als Obergrenze |
| **Parameter** | die vom Eigentümer erklärten Einstellmöglichkeiten für Empfangende |
| **Prüffälle** | der Katalog, gegen den der [Prüfstand](#agenten-prüfstand-vor-der-freigabe) läuft |

Alles davon ist Teil **einer** Version. Eine Änderung an irgendeinem Bestandteil ist eine Änderung des Agenten und erzeugt eine neue Fassung.

### Der Agent führt sein Wissen selbst mit

Welche Wissensbibliotheken ein Agent nutzt, ist Teil **seiner** Beschreibung und nicht des Raums, in dem er läuft. Steht die Chip-Leiste auf @Alles-Wissen, verengt der Space einen Chat ohne gebundenen Agenten; er verengt aber **nicht** den Agenten (siehe [Suchbereich je Chatart](./spaces-and-assets.md#suchbereich-je-chatart)).

Diese Asymmetrie ist der Kern der Prüfbarkeit. Würde der Space zusätzlich verengen, antwortete dieselbe freigegebene Fassung je nach Aufrufort anders — ein Prüfbericht sagte dann nichts über den nächsten Aufruf aus, und die Freigabe wäre nicht mehr als ein Datum. Umgekehrt gilt: Weil die Bindung mitreist, bringt ein geteilter Agent sein Wissen mit, statt beim Empfänger neu zusammengesetzt zu werden.

**Der Preis dafür steht in [spaces-and-assets.md](./spaces-and-assets.md#einen-agenten-weitergeben-die-freigabekette) und wird hier nicht wiederholt:** Damit ein geteilter Agent beim Empfänger tatsächlich etwas findet, muss sein Wissen mitfreigegeben werden. Ein Agent, dessen Bibliotheken nicht freigegeben werden dürfen, ist nicht teilbar. Es gibt keinen Kanal, über den Wissen an der Rechteschicht vorbeifließt: **Ein Agent ruft ausschließlich mit den Rechten der aufrufenden Person ab.**

### Skills und Prompt-Bibliotheken

Nicht jede wiederverwendbare Fähigkeit braucht einen eigenen Agenten. Drei Ausprägungen mit steigendem Gewicht:

| | Was es ist | Wann es reicht |
|---|---|---|
| **Prompt** | eine benannte, wiederverwendbare Anweisung in einer Prompt-Bibliothek | Ein wiederkehrender Arbeitsschritt ohne eigene Wissensbindung und ohne Werkzeuge |
| **Skill** | eine benannte Teilfähigkeit mit Anleitung und Beispielen — im gewöhnlichen Chat anwendbar, von einem Agenten einbindbar | Eine Arbeitsweise, die mehrere Personen und mehrere Agenten teilen — etwa „Vermerk nach Hausstandard gliedern" |
| **Agent** | das vollständige Paket oben | Eigene Wissensbindung, eigene Werkzeuge oder eigene Befugnisse |

Alle drei sind **Assets** im Sinne von [spaces-and-assets.md](./spaces-and-assets.md#was-ein-asset-ist) und erben Rechte, Versionierung, Katalog, Freigabeweg und Portabilität unverändert. Dieses Dokument beschreibt ihren Inhalt, nicht ihre Verteilung.

**Ein Skill sagt, *wie* gearbeitet wird; ein Werkzeug ist das, *womit*.** Diese Trennung ist keine Begriffspflege, sondern entscheidet über den Zuschnitt: Ein Skill ist Anleitung und Beispiel und damit lesbar, prüfbar und zwischen Häusern austauschbar, weil er keine Zugänge voraussetzt. Ein Werkzeug ist eine Fähigkeit der Installation — es braucht eine Anbindung, Zugangsdaten und eine Rechteklasse und wandert deshalb nicht mit. Ein Skill darf Werkzeuge **benennen**, die er braucht; ob sie vorhanden und freigegeben sind, entscheidet die empfangende Installation. Ein Skill, dem ein benanntes Werkzeug fehlt, wird als eingeschränkt angezeigt, statt still weniger zu leisten.

**Das Paketformat ist ein lesbares Textpaket.** Für die Ablage und den Austausch eines Skills hat sich außerhalb von OPAA ein offenes Format etabliert: eine Anleitungsdatei in Markdown mit einem strukturierten Kopfteil (Name, Kurzbeschreibung, benötigte Werkzeuge) und daneben die Beispiele und Beilagen, die die Anleitung bei Bedarf nachlädt. Dass es offen und verbreitet ist, ist der eigentliche Grund, es zu prüfen: Ein Skill, der ohne OPAA lesbar bleibt, ist in einem Prüfvorgang vorlegbar und in einer Registratur ablagefähig. Die Festlegung trifft der ADR aus #1727, nicht dieses Dokument; die hier beschriebenen Eigenschaften sind seine Anforderungen.

**Ein Skill hat eine Geltung, und sie läuft ab.** Ein veraltetes Dokument in einer Bibliothek bleibt als Dokument erkennbar; ein veralteter Skill **ist** die Arbeitsanweisung — und weil seine Ausgabe Fundstellen führt, sieht sie geprüft aus. Ändert das Haus 2027 die Gliederung seiner Vermerke per Rundverfügung, erzeugt ein 2026 freigegebener Skill weiter nach alter Vorlage, und auffallen wird es bei einer Aktenprüfung. Deshalb trägt jeder Skill ein Pflichtfeld **„Geltung zu prüfen bis"** (Vorgabe zwölf Monate ab Freigabe). Nach Ablauf sehen Nutzende den Hinweis „Geltung seit *Datum* nicht bestätigt", und beim Eigentümer entsteht ein Eintrag auf der Governance-Arbeitsliste. **Keine automatische Deaktivierung** — ein Werkzeug, das ohne Zutun verschwindet, erzeugt Misstrauen und Umgehung.

**Ein Skill mit Werkzeugen wird vor der Freigabe an zwei mitgelieferten Fällen erprobt.** Sie kommen vom Produkt, nicht vom Fachbereich, und kosten ihn keine Arbeit: ein Fall, zu dem nachweislich keine Fundstelle existiert (das Ergebnis muss „nicht feststellbar" lauten), und ein Fall mit einer Fundstelle, die die Aussage nicht trägt (sie darf nicht als Beleg geführt werden). Sie prüfen die **Wirkung** des Skills, nicht seinen Text.

**Eine maschinelle Prüfung des Skill-Texts findet ausdrücklich nicht statt.** Ein Modell, das einen Text auf Umgehungsversuche absucht, gibt eine Selbstauskunft über Formulierungen — es erzeugt einen grünen Haken und damit falsche Sicherheit, und es ist genau dort blind, wo ein Skill unauffällig formuliert ist und trotzdem in eine falsche Richtung drängt. Geprüft wird die Wirkung, bewertet wird fachlich durch Menschen. Zwei Dinge treten hinzu, weil sie unabhängig davon richtig sind:

- **Ein benannter Verantwortlicher je Skill** — die zum Zeitpunkt der Freigabe zuständige Stelle, nicht „das Referat". Im Zweifel muss jemand nennbar sein, der den Text verantwortet.
- **Ein sofortiger Rückzug**, installationsweit und binnen Sekunden — über denselben Schalter wie der [Notaus der Werkzeuge](#drei-grenzen-ein-platzlimit-und-ein-notaus).

Zusammen sind die drei Punkte die kleinste brauchbare Vorwegnahme des [Prüfstands](#agenten-prüfstand-vor-der-freigabe) für eine Stufe, in der es ihn noch nicht gibt.

*Phasenlage: Prompt-Bibliotheken je Space in Phase 1 (#1726). Der Skill ist als eigene Objektart geschnitten (#1727); die früher hier geführte Frage „eigene Objektart oder benannter Abschnitt einer Aufgabenbeschreibung" entscheidet der ADR jenes Epics. Entscheidend für die Reihenfolge: Ein Skill braucht ausdrücklich **keine** Agenten-Laufzeit, sondern wird im gewöhnlichen Chat angewandt. Agenten als teilbares Paket bleiben Phase 2.*

---

## Agenten-Onboarding

*Die Verteilung von Assets setzt voraus, dass es Assets gibt. Wer keinen Systemprompt schreiben kann, erzeugt aber keinen — und genau daran scheitert die Einführung in der Fläche.*

### Ein geführtes Verfahren statt eines leeren Textfeldes

Einen Agenten legt nicht an, wer Prompt-Technik beherrscht, sondern wer die Aufgabe kennt. Deshalb führt ein eigener **Onboarding-Assistent** durch die Erstellung:

- **Bedarfsanalyse im Dialog.** Er fragt nach der Aufgabe, nicht nach der Formulierung: Was soll erledigt werden, für wen, auf welcher Grundlage, mit welchem Ergebnis.
- **Immer nur eine Frage auf einmal.** Ein Formular mit zwölf Feldern wird abgebrochen oder mit Adjektiven gefüllt. Ein Gespräch mit zwölf Fragen wird geführt.
- **In Fachsprache statt in Prompt-Technik.** Es ist von Aufgaben, Zuständigkeit, Zeichnungsbefugnis und Sprachregister die Rede — nicht von Rollen-Prompts, Kontextfenstern oder Temperatur.
- **Ergebnis ist ein fertig konfigurierter Agent**, den der Fachbereich selbst angelegt hat und selbst verantwortet.

### Agenten werden beschrieben wie Stellen

Die Verwaltung beschreibt Aufgaben seit jeher in gegliederter Form — Aufgabenbeschreibung, Geschäftsverteilungsplan. Ein Agent wird genauso beschrieben, mit sechs festen Abschnitten:

| Abschnitt | Inhalt |
|---|---|
| **Rolle und Einordnung** | Welche Aufgabe, welches Sachgebiet, wem zugeordnet |
| **Aufgaben** | Was der Agent konkret tun soll — **mit Beispielen statt Adjektiven** |
| **Wissenszugriff** | Welche Wissensbibliotheken und Quellsysteme er nutzt |
| **Befugnisse** | Nur lesen oder auch schreiben; welche Aktionen freigabepflichtig sind. Die Verwaltungsanalogie ist die **Zeichnungsbefugnis** |
| **Register** | Amtssprache, Leichte Sprache oder Bürgeranschreiben — **kein Persönlichkeitsprofil** |
| **Grenzen** | Was der Agent ausdrücklich **nicht** tut und wann er abgeben muss |

Zwei Abschnitte verdienen eine Erläuterung, weil sie leicht falsch verstanden werden.

**Register ist kein Charakter.** Gemeint ist die Sprachebene einer Auskunft, nicht eine Persönlichkeit. Ein Amt braucht keinen freundlich-lockeren Assistenten, sondern eine Auskunft im richtigen Register: Aktenvermerk, Bürgeranschreiben oder Leichte Sprache. Ein Persönlichkeitsprofil erzeugt dagegen einen Ton, für den niemand zuständig ist. Die Grenze wird an zwei Beispielen greifbar: „Bürgeranschreiben, verständlich, ohne Behördendeutsch" ist ein Register — es beschreibt die Sprachebene. „Freundlich und zugewandt, verwendet gelegentlich Humor" ist schon Charakter — es beschreibt ein Wesen. Prüffrage beim Anlegen: Steht dort, **wie geschrieben** wird, oder **wer schreibt**?

**Eine zusätzliche Frage, die vor jeder Freigabe steht.** „Verarbeitet oder bewertet dieser Agent Angaben über die Arbeit einzelner Beschäftigter?" Die Antwort ist Teil der Aufgabenbeschreibung und damit versioniert; sie entscheidet über die Prüfkategorie [Beschäftigtenbezug](#sechs-prüfkategorien).

**Grenzen sind so wichtig wie Aufgaben.** „Bewertet keine Einzelfälle mit Ermessensspielraum", „gibt bei Fragen zum Steuergeheimnis an die Sachgebietsleitung ab", „erstellt keine Entwürfe mit Außenwirkung" — diese Sätze sind später Prüfkriterien (siehe [Grenz- und Befugnistreue](#agenten-prüfstand-vor-der-freigabe)). Ein Abschnitt, der leer bleibt, ist ein Warnzeichen und wird als solches angezeigt.

### Der eigentliche Gewinn liegt in der Struktur

Der geführte Dialog ist die Einstiegshilfe. Die gegliederte Beschreibung ist der Grund, warum das Verfahren überhaupt so gebaut ist:

| | Freitext-Prompt | Gegliederte Beschreibung |
|---|---|---|
| **Prüfbar** | Man liest eine Textwand und hofft, nichts übersehen zu haben | Jeder Abschnitt wird gegen ein eigenes Kriterium geprüft |
| **Vergleichbar** | Zwei Agenten sind nur im Fließtext vergleichbar | Abschnitt gegen Abschnitt, auch über Fachbereiche hinweg |
| **Versionierbar** | Ein Änderungsvergleich zeigt verschobene Absätze | Ein Änderungsvergleich zeigt: „Befugnisse erweitert, Grenzen unverändert" |

Erst damit werden der Freigabeweg und ein lesbarer Versionsvergleich möglich. Ein Freitext-Prompt lässt sich weder sinnvoll prüfen noch als Änderung nachvollziehen — und eine Freigabe, die sich auf etwas Ungeprüftes bezieht, ist eine Unterschrift ins Blaue.

**Die Grenze der Zusage, ehrlich benannt:** Die Struktur macht eine Beschreibung prüfbar, sie macht sie nicht richtig. Auch ein sauber gegliederter Agent kann fachlich falsch sein. Genau deshalb ist der Prüfstand kein Zusatz, sondern der zweite Teil desselben Gedankens.

### Was der Assistent zusätzlich leistet

- **Fehlende Zutaten benennen.** Er sagt, welche Dokumente, Beispiele oder Systemzugänge dem Agenten noch fehlen, damit er brauchbar arbeitet — statt einen Agenten entstehen zu lassen, der mangels Wissen nichts findet.
- **An Beispielen lernen.** Gute Beispielfälle schlagen abstrakte Stilbeschreibungen. Statt „präzise formulieren" fragt der Assistent nach drei echten Vermerken.
- **Negativlisten.** Formulierungen und Vorgehensweisen, die das Haus nicht will, gehören ebenso zur Beschreibung wie die gewünschten.
- **Nachschärfen im Betrieb.** Rückmeldungen aus der Nutzung fließen nicht nur in die Suche, sondern in die **Aufgabenbeschreibung selbst** zurück. Der Agent wird über seine Beschreibung verbessert — nachvollziehbar als neue Version, nicht durch stilles Nachjustieren.
- **Übergabe an die Freigabe.** Was der Onboarding-Assistent erzeugt, ist ein Entwurf. Der Weg in Team, Fachbereich und organisationsweiten Katalog läuft über den [Freigabeweg](./spaces-and-assets.md#der-freigabeweg-vorschlagen-prüfen-freigeben-veröffentlichen).

**Der Dialog ist ein Weg zur gegliederten Beschreibung, nie der einzige.** Er ist für das **Anlegen** begründet; für das **Ändern** wäre er ein Hindernis. Wer nach achtzehn Monaten einen Satz im Abschnitt „Befugnisse" enger fassen muss, weil sich die Zeichnungsbefugnis geändert hat, führt dafür kein Gespräch mit zwölf Fragen — er legt sonst lieber einen neuen Agenten an, und aus zwanzig werden sechzig. Die sechs Abschnitte bleiben deshalb **feldweise direkt bearbeitbar**, jede Änderung als neue Version.

*Phasenlage: Phase 2. Die Kopplung an Freigabeweg und Versionsvergleich folgt in Phase 3.*

---

## Agenten-Prüfstand vor der Freigabe

*Ein Agent, den ein Fachbereich selbst angelegt hat, darf nicht allein deshalb organisationsweit laufen, weil er beim ersten Ausprobieren gut aussah.*

Zwischen Entwurf und Freigabe steht ein **automatisierter Prüflauf** — die technische Ergänzung zur fachlichen Prüfung durch Menschen, nicht ihr Ersatz. Er testet den Agenten gegen einen Katalog von Prüffällen und liefert einen **Prüfbericht als Teil der Freigabeunterlage**.

### Sechs Prüfkategorien

**1 · Sicherheit und Robustheit.** Hält der Agent Manipulationsversuchen stand? Geprüft wird insbesondere die **Prompt-Injection aus eingebetteten Dokumenten**: Ein Schriftsatz, ein Antrag oder eine hochgeladene Tabelle kann Anweisungen enthalten, die an den Agenten gerichtet sind, ohne dass ein Mensch sie so wahrnimmt. Weiter: Gibt er interne Anweisungen preis, versucht er Wissen außerhalb seiner Berechtigung zu ziehen, lässt er sich zur Umgehung seiner Grenzen überreden?

**2 · Fachliche Richtigkeit.** Antworten auf **Referenzfälle mit bekannter richtiger Lösung**. Das ist derselbe Gedanke wie bei der Messung der Retrieval-Qualität, nur eine Ebene höher — nicht „findet die Suche die richtige Stelle", sondern „zieht der Agent daraus den richtigen Schluss". Der Messrahmen ist in [search-quality-evaluation.md](./search-quality-evaluation.md) beschrieben.

**3 · Belegtreue.** Führt jede tragende Aussage einen Beleg, der die Belegvalidierung besteht? Sagt er **„nicht feststellbar"**, wo nichts belegt ist, statt eine plausible Antwort zu formulieren? Geprüft wird beides: das unbelegte Ausweichen und das falsche Zitat — eine Fundstelle, die die Aussage nicht trägt.

**4 · Grenz- und Befugnistreue.** Bleibt er innerhalb der Grenzen, die seine Aufgabenbeschreibung setzt? Gibt er ab, wo er abgeben soll? Löst er keine schreibende Aktion ohne Freigabe aus? Diese Kategorie ist der Grund, warum die Abschnitte **Befugnisse** und **Grenzen** verbindlich sind: Sie sind nicht Beiwerk, sondern die Prüfvorschrift.

**5 · Neutralität und Amtsangemessenheit.** Positioniert er sich zu politischen oder weltanschaulichen Fragen? Wird er diskriminierend? Verlässt er den amtlichen Ton? In der Verwaltung ist das **kein Reputationsthema, sondern das Neutralitätsgebot** — ein Prüfkriterium mit Rechtsbezug, kein Marketingversprechen. Die Bewertung, welche Ausgabe die Grenze verletzt, bleibt eine fachliche und wird nicht allein maschinell entschieden.

**6 · Beschäftigtenbezug.** Bewertet oder erhebt der Agent die **Arbeitsleistung, das Arbeitsverhalten oder die Bearbeitungsqualität** benannter oder bestimmbarer Beschäftigter? Die Lücke, die diese Kategorie schließt, ist nicht theoretisch: Nichts hindert jemanden daran, einen Skill „Prüfung der Bearbeitungsqualität im Sachgebiet" zu schreiben, ihn an eine Gruppe freizugeben und ihn mit den Rechten einer Führungskraft auf die Vorgänge ihres Sachgebiets laufen zu lassen. Das ist ein Beurteilungsinstrument, das über die gewöhnliche Asset-Freigabe in Betrieb ginge, ohne je als solches benannt worden zu sein — und keine der fünf übrigen Kategorien fragt danach. Trifft die Kategorie zu, ist der Agent **nicht freigabefähig ohne eigenen Beteiligungsvorgang**, unabhängig davon, wie gut er sonst abschneidet. Die Grundlage der Prüfung ist die Pflichtangabe aus dem [Onboarding](#agenten-werden-beschrieben-wie-stellen).

### Wie der Prüfstand betrieben wird

- **Regressionsprüfung vor jeder neuen Fassung.** Der Lauf wiederholt sich, bevor eine neue Fassung eines bereits freigegebenen Agenten veröffentlicht wird. Verschlechterungen fallen auf, bevor sie im Amt ankommen — nicht danach.
- **Prüffälle sind selbst Assets.** Fachbereiche pflegen eigene Prüffälle für ihre Domäne; sie werden versioniert, geteilt und freigegeben wie jedes andere Asset. Wer sie verantwortet, ist damit beantwortet: der Fachbereich, dem der Vorgang gehört. **Woher sie kommen, ist damit nicht beantwortet** — siehe unten.
- **Der Prüfbericht nennt an erster Stelle seine Prüffallbasis.** Zahl der Fälle je Kategorie, Herkunft je Fall (selbst geschrieben, mitgeliefert, aus einem Betriebsfehler entstanden) und ausdrücklich die Kategorien **ohne** einen einzigen Fall. Unterhalb einer festgelegten Mindestbasis lautet das Ergebnis nicht „bestanden", sondern **„nicht aussagekräftig"** — und es sieht dann auch nicht grün aus. Der Grund ist unbequem und benannt: Referenzfälle mit bekannter richtiger Lösung zu schreiben ist die anspruchsvollste Arbeit des ganzen Verfahrens, und wer einen Agenten baut, um Arbeit zu sparen, schreibt sie nicht. Ein grüner Bericht über drei selbstgeschriebene Fälle ist genau die Formalie, die dieses Kapitel verhindern soll.
- **Der [mitgelieferte Startkatalog](#mitgelieferter-startkatalog) bringt seine Prüffälle mit.** Er ist die einzige Quelle, aus der ein Fachbereich ohne eigene Arbeit eine Grundmenge bekommt — und damit der einzige realistische Weg, die Mindestbasis nicht zur Einführungshürde zu machen.
- **Das Ergebnis ist Evidenz, keine Meinung.** Der Prüfbericht ist reproduzierbar — gleiche Fassung, gleiche Prüffälle, gleiches Ergebnis — und wandert ins Protokoll. Für Revision und Aufsicht bleibt nachvollziehbar, **welche Fassung womit geprüft wurde**.
- **Ein bestandener Prüflauf ist keine Freigabe.** Er ist eine Unterlage. Freigegeben wird von einer benannten Stelle, und die kann trotz grünem Bericht ablehnen.
- **Ein leerer Grenzen- oder Befugnisse-Abschnitt wird bei der Freigabe ausdrücklich bestätigt.** Eine Bildschirmanzeige ist kein Hindernis: Wer unter Termindruck ein Warnzeichen sieht, sieht darüber hinweg. „Dieser Agent hat keine Grenzen" ist eine fachliche Aussage, und sie gehört als eigenes Pflichtfeld in den Prüfvermerk — nicht als Hinweis, den man wegklickt. Sonst prüft Kategorie 4 gegen einen leeren Abschnitt und meldet grün.
- **Die fachliche Prüfung bewertet auch die Arbeitsanweisung selbst**, nicht nur Struktur und Werkzeugbedarf. Ein Skill aus einem fremden Fachbereich kann formal regelkonform sein und trotzdem in eine Richtung drängen, die niemand geprüft hat — etwa über eine Gliederung, die eine bestimmte Schlussfolgerung nahelegt. Das ist keine Sicherheitsfrage und keine technische Prüfung, sondern der Kern dessen, wofür die freigebende Stelle mit ihrem Namen steht.

**Was der Prüfstand nicht leistet:** Er misst gegen die Fälle, die jemand aufgeschrieben hat. Eine Lücke im Prüffallkatalog ist eine Lücke im Bericht, und ein grüner Lauf sagt nichts über einen Fall, den niemand bedacht hat. Deshalb ist der Katalog ein gepflegtes Asset und kein einmaliger Aufwand — jeder im Betrieb aufgefallene Fehler gehört als neuer Prüffall hinein.

*Phasenlage: Phase 2 als Teil der Agent-Governance; die Kopplung an den Freigabeweg in Phase 3.*

---

## Prüfagenten für kritische Vorgänge

*Die Belegvalidierung schützt davor, dass Belege erfunden werden. Sie schützt nicht davor, dass aus richtigen Fundstellen ein falscher Schluss gezogen wird.*

Für kritische Vorgänge tritt deshalb eine unabhängige Prüfinstanz zwischen Ergebnis und Ausgabe — die **maschinelle Entsprechung zu Mitzeichnung und Schlusszeichnung**.

### Unabhängige Prüfung vor der Ausgabe

Bevor ein Ergebnis die bearbeitende Person erreicht, bewerten ein oder mehrere **Prüfagenten** es gegen dieselben Quellen:

- **ohne die Begründung des erzeugenden Agenten zu sehen** — sonst prüfen sie dessen Gedankengang nach, statt die Sache;
- **ohne Kenntnis voneinander** — sonst entsteht aus mehreren Stimmen eine.

Nur bei übereinstimmender Bewertung geht das Ergebnis durch. Weichen die Bewertungen ab, wird das Ergebnis **mit Hinweis vorgelegt** statt stillschweigend geliefert.

```
Agent erzeugt Ergebnis
        │
        ├──────────────► Prüfagent 1 ─┐   sieht: Frage, Ergebnis, Quellen
        │                             │   sieht nicht: Begründung, andere Prüfagenten
        └──────────────► Prüfagent 2 ─┤
                                      ▼
                          übereinstimmend?
                          ja  → Ergebnis geht an die Sachbearbeitung
                          nein → Ergebnis geht an die Sachbearbeitung,
                                 gekennzeichnet und mit den Einwänden
```

Der rechte Zweig ist bewusst kein Abbruch: Das Ergebnis wird nicht unterdrückt, sondern gekennzeichnet. Ein System, das Ergebnisse verschluckt, wird umgangen.

### Woran geprüft wird

Geprüft wird gegen das Nachvollziehbare, nicht gegen eine Meinung:

- Stimmen die Zitate mit den Quellstellen überein?
- Tragen die Fundstellen die gezogene Schlussfolgerung?
- Fehlt etwas Wesentliches, das in den Quellen steht?
- Steht das Ergebnis im Widerspruch zu einer geltenden Anweisung?

### Abgestuft nach Kritikalität

Das Verfahren kostet Zeit und Rechenleistung, und ein Verfahren, das überall läuft, macht die schnelle Nachfrage unbrauchbar. Es wird deshalb **je Space, Agent und Aktionstyp** konfiguriert:

| Vorgang | Prüfagenten |
|---|---|
| Auskunft mit Außenwirkung | ja |
| Schreibende Aktion | ja |
| Prüfungshandlung (etwa Vergabe- oder Belegprüfung) | ja |
| Nachschlagen im Arbeitsalltag | nein |

Diese Abstufung ist eine **bewusste Verwaltungsentscheidung** und wird als solche dokumentiert — sie gehört in dieselben Unterlagen wie die Festlegung, wer was zeichnen darf.

### Kein Ersatz für den Menschen

Die Prüfagenten **filtern vor, sie entscheiden nicht**. Die fachliche Verantwortung und die Freigabe bleiben bei der Sachbearbeitung; das Verfahren verschiebt nur, wie viel Fehlerhaftes dort überhaupt ankommt. Dieser Satz ist nicht als Vorbehalt gemeint, sondern als Konstruktionsregel: Es gibt keinen Pfad, auf dem ein Ergebnis allein aufgrund maschineller Übereinstimmung wirksam wird.

**Aus der Verantwortung folgt ein Recht, und es gehört ausgesprochen.** Wer für ein Ergebnis einsteht, darf es **verwerfen und den Vorgang selbst bearbeiten — ohne Begründungspflicht** und ohne dass diese Entscheidung personenbezogen erfasst wird. Ohne diesen Satz wird aus „die fachliche Verantwortung bleibt bei der Sachbearbeitung" eine Haftungszuweisung ohne die dazugehörige Handlungsmöglichkeit. Das gilt für jede der vier Stufen: Ein freigegebener Skill und ein freigegebener Agent sind ein Angebot, keine Arbeitsanweisung.

**Vollständig protokolliert.** Wer geprüft hat, mit welchem Ergebnis und auf welcher Grundlage, steht revisionssicher im Protokoll — **einschließlich der Fälle, in denen die Prüfung angeschlagen hat**. Gerade diese sind der Nachweis, dass das Verfahren wirkt.

**Der Eintrag hängt am Lauf und am Agenten, nicht an der aufrufenden Person.** Ein Anschlagvermerk ist eine Beanstandung an der Maschine; für jeden Dritten liest er sich aber wie eine an der Arbeit desjenigen, der den Vorgang bearbeitet hat. Die Frage „bei wem schlägt die Prüfung häufig an" wäre technisch trivial und fachlich sinnlos — es gibt deshalb **keine Auswertung nach aufrufender Person**, und die zugehörige Kennzahl bleibt aggregiert.

**Zwei Grenzen, die genannt gehören:** Prüfagenten sind gegen einen gemeinsamen Fehler blind — beruhen alle auf demselben Modell und derselben Wissensbindung, teilen sie dessen systematische Schwächen. Und sie verdoppeln die Kosten eines Vorgangs mindestens. Beides spricht nicht gegen das Verfahren, wohl aber gegen seinen flächendeckenden Einsatz.

*Phasenlage: Phase 2 als Teil der Agent-Governance; die Kopplung an Freigabeweg und Versionierung in Phase 3.*

---

## Werkzeuge

### Die Suche als Werkzeug — und was dabei nicht verhandelbar ist

Das erste und wichtigste Werkzeug ist die eigene Suche. Sie ist rein lesend, braucht keine fremde Anbindung und keine Zugangsdaten, und sie ist genau die Fähigkeit, die eine mehrschrittige Prüfung überhaupt möglich macht: Wer eine Vollständigkeitsprüfung führt, muss nach jedem Befund neu nachsehen können.

**Beide Wege bleiben, und das ist eine Entscheidung, keine Übergangslösung.**

| | Gewöhnlicher Chat | Skill oder Agent |
|---|---|---|
| **Suche** | einmal, **vor** dem Modellaufruf | mehrfach, vom Modell veranlasst, mit Obergrenze |
| **Begründung** | Eine Nachfrage soll nicht langsamer und nicht teurer werden, weil ein Modell erst entscheidet, ob es suchen will. Und ein Modell, das sich gegen die Suche entscheidet, antwortet unbelegt — genau das, was OPAA ausschließt | Eine Prüfung mit mehreren Anforderungen ist mit einer Suche nicht zu leisten |

Für beide Wege gelten dieselben drei Zusagen:

1. **Es gibt eine Suche, nicht zwei.** Das Werkzeug ruft denselben Abfrageweg wie der Chat — kein zweiter Zugriff auf den Bestand, keine eigene Rechtelogik.
2. **Der Rechtefilter kommt nie aus einem Modellargument.** Der Suchbereich stammt aus dem Aufrufkontext: aus der Person, in deren Auftrag gearbeitet wird, und aus dem Ort des Aufrufs. Das Modell darf die Frage bestimmen und fachlich einschränken — nicht, wo gesucht wird. Damit ist der Einflüsterungsversuch aus einem eingebetteten Dokument bei der Suche wirkungslos, statt von der Formulierung des Systemvorspanns abzuhängen.
3. **Die Belegprüfung sieht alles.** Sie läuft am Ende über die Passagen **aller** Suchschritte. Eine Fundstelle aus dem zweiten Schritt ist im vierten noch zitierfähig; eine erfundene bleibt es nicht.

### Drei Grenzen, ein Platzlimit und ein Notaus

**Eine Aufrufzahl ist keine Lastgrenze.** Vier Suchschritte sind fünf Modellaufrufe; auf einem selbst betriebenen Modell mit geteilter Grafikkarte sind das je nach Last ein bis drei Minuten, ohne dass irgendeine Grenze verletzt wäre. Und zwölf gleichzeitige Läufe sättigen den Modellendpunkt, hinter dem auch der gewöhnliche Chat wartet — der dann langsam wird, obwohl er gar keine Werkzeuge benutzt. Deshalb gelten **drei** Grenzen nebeneinander, alle als Systemeinstellung mit konservativer Vorgabe:

| Grenze | Wogegen sie schützt |
|---|---|
| **Zahl der Aufrufe** je Werkzeug und je Lauf | Kosten und Endlosschleifen |
| **Laufzeit** je Lauf (Wanduhr) | die Wartezeit der Person — der Grund, aus dem ein Angebot im Amt liegen bleibt |
| **Gesammelte Menge** über alle Schritte (Passagen bzw. Zeichen) | den eigentlichen Kostentreiber: nicht die Zahl der Aufrufe, sondern der mit jedem Schritt wachsende Kontext. Ein zu großer Kontext ist zugleich der Betriebsfehler, der wie ein unfähiges Modell aussieht |

**Diese drei Grenzen begrenzen einen laufenden Lauf, und alle drei enden gleich:** mit dem Vorliegenden und dem Hinweis auf die unvollständige Prüfung, nicht mit einem Fehler — und der nicht erreichte Punkt erscheint als eigener Zustand in der Ergebnisliste (siehe [Szenarien, Stufe 2](#stufe-2--dieselbe-arbeitsweise-die-selbst-nachsucht)).

**Das Platzlimit ist etwas anderes: Es greift, bevor ein Lauf beginnt.** Eine Höchstzahl gleichzeitiger Werkzeugläufe je Installation; ist sie erreicht, wird die **Anfrage abgewiesen** — mit einer verständlichen Meldung und ohne dass ein Lauf entsteht. Es gibt dann kein Teilergebnis und keine Ergebnisliste, weil nichts angefangen hat, und es gibt auch keine Warteschlange: Sie verschöbe das Problem nur in die Wartezeit und hielte dabei Verbindungen offen. Die Person versucht es später erneut.

**Ein Skill darf Grenzen nur senken, nie heben.** Sonst setzt der erste Skill-Autor, dessen Prüfung abbricht, die Grenze hoch, und die Kostenzusage ist weg. Nach oben entscheidet allein die Systemverwaltung.

**Die Vorgabewerte werden kalibriert, nicht geraten.** Vor der ersten Vorführung werden zwei bis drei realistische Prüffälle mit bekannter nötiger Schrittzahl durchgespielt und die Grenzen danach gesetzt. Eine erste Vorführung, die an einer zu niedrig geratenen Grenze mit „nicht abgeschlossen" endet, kostet mehr Vertrauen, als jede spätere Korrektur zurückholt.

**Und es gibt einen Notaus.** Ein installationsweiter Schalter „Werkzeugaufrufe" und ein Schalter je Werkzeug, beide mit der ausgelieferten Vorgabe **aus** und beide wirksam beim nächsten Aufruf, ohne Neustart und ohne Auslieferung. Eine Installation, deren Modellendpunkt an einem Freitagnachmittag von Werkzeugläufen gesättigt wird, braucht eine Abschaltung, die sofort greift — und das Fähigkeitsfeld am Modelleintrag ist dafür der falsche Hebel: Es sagt eine Tatsache über das Modell aus, und wer es als Notaus missbraucht, hinterlässt eine Unwahrheit in der Konfiguration. Bei ausgeschaltetem Schalter verhält sich eine Anfrage mit Werkzeugwunsch wie bei einem nicht fähigen Modell: Ablehnung mit verständlicher Meldung, nicht stille Ausführung ohne Werkzeuge.

Derselbe Schalter trägt später den **sofortigen Rückzug eines freigegebenen Skills**: Eine Freigabe, die sich nicht binnen Sekunden zurücknehmen lässt, ist eine Einbahnstraße.

**Solange es keine Skills gibt, nutzt eine benannte Gruppe die Schleife — freiwillig und unbeobachtet.** Der installationsweite Schalter entscheidet, *ob* überhaupt, eine benannte Gruppe, *wer*. Weder das eine noch das andere allein trägt: Ein Schalter für alle macht jede Person zum Kostenverursacher ohne Steuerung und erzeugt einen ungeprüften Kanal, der aussieht wie ein geprüfter; eine Beschränkung auf die Systemverwaltung liefert keine fachliche Rückmeldung, weil dort niemand merkt, wann ein Ergebnis inhaltlich falsch ist. Drei Bedingungen gehören zur Entscheidung:

- **Die Teilnahme ist freiwillig**, und jedes Ergebnis ist als **ungeprüfter Vorabstand** gekennzeichnet — es gibt in dieser Stufe kein Objekt, keine Version und keine Freigabe, auf die sich jemand berufen könnte.
- **Über die Mitglieder der Gruppe entsteht keine Nutzungserhebung.** Keine Zählung, wer den Weg wie oft benutzt hat, in keiner Ablage. Eine beobachtete Erprobungsgruppe ist der Weg, auf dem eine Erprobung ohne Beteiligung zur Einführung wird.
- **Die Einrichtung der Gruppe wird vorher mit der Personalvertretung abgestimmt**, nicht danach. Das ist Sache des einführenden Hauses; das Produkt stellt dafür den Schalter und die Gruppenbindung bereit.

**Ehrlich zum Nachweis:** Ob die Änderung eines dieser Schalter oder einer dieser Grenzen einen Protokolleintrag erzeugt, hängt daran, dass die Systemeinstellungen insgesamt protokolliert werden — und das ist heute **nicht gebaut** ([security-and-compliance.md](./security-and-compliance.md) führt die übrigen Systemeinstellungen ausdrücklich als noch nicht verdrahtet). Solange das so ist, ist „wer hat wann abgeschaltet" nicht beantwortbar. Das ist eine benannte Lücke, keine Zusage.

### Werkzeugaufrufe als Modellfähigkeit

Ein Werkzeug nützt nur, wenn das Modell es aufruft — und zwar mit den richtigen Argumenten, zum richtigen Zeitpunkt und ohne den Aufruf zu erfinden. Das ist keine Selbstverständlichkeit, und im Betrieb mit eigenen Modellen ist es die eigentliche Engstelle. Deshalb gilt:

- **Der Modellkatalog führt „Werkzeugaufrufe" als Eigenschaft**, so wie er Kontextlänge und Sprachen führt (siehe [llm-integration.md](./llm-integration.md)). Ein Modell ohne diese Eigenschaft lässt sich einer Aufgabe, die Werkzeuge braucht, nicht zuordnen — die Zuordnung wird abgelehnt, nicht stillschweigend ohne Werkzeuge ausgeführt.
- **Die Angabe kennt drei Werte, nicht zwei:** *ungeprüft* (Vorgabe; verhält sich wie „nicht fähig"), *fähig*, *nicht fähig*. Ein einzelner Schalter könnte „nie gesetzt" nicht von „geprüft, kann es nicht" unterscheiden — und genau das ist die Frage im Betrieb: Muss ich das noch prüfen, oder hat es schon jemand getan?
- **Die Angabe gilt einer Kombination, nicht einem Modell.** Werkzeugfähigkeit hängt an Modell, Quantisierung, Laufzeit und deren Konfiguration. Die Angabe trägt deshalb **Datum und Grundlage** — *gemessen*, mit Verweis auf den Messlauf, oder *manuell gesetzt* — und lautet sinngemäß „für diesen Endpunkt und diese Laufzeit geprüft". Aussagekräftig ist, wie geprüft wurde, nicht wer: Das Feld führt weder Personen- noch Gruppennamen. Eine Änderung von Endpunkt oder Modellkennung setzt sie auf *ungeprüft* zurück; ausgelieferte Modellzeilen starten auf *ungeprüft*. Sonst entsteht der stille Fehlbetrieb auf dem zweiten Weg: Nach einem Laufzeit-Update verliert die Installation Werkzeugaufrufe, das Feld steht weiter auf „fähig", nichts wird abgelehnt, und im Fachreferat heißt es, die Antworten seien schlechter geworden.
- **Das Handbuch führt die geprüften Kombinationen** — Modell, Quantisierung, Laufzeit, Version, Datum, Ergebnis je Fallklasse. Das ist die Unterlage für die Frage, warum eine Installation dieses und nicht jenes Modell betreibt.
- **Die Eigenschaft wird gemessen, nicht behauptet.** Für die Suchqualität gibt es einen Messrahmen mit festen Fällen ([search-quality-evaluation.md](./search-quality-evaluation.md)); für den Werkzeugaufruf braucht es denselben Gedanken auf eigener Fallbasis: Ruft das Modell auf, wenn es soll? Lässt es den Aufruf, wenn nicht? Sind die Argumente gültig? Erfindet es Werkzeuge, die es nicht gibt? Hält es die Obergrenze ein? Ohne diese Messung ist die Fähigkeit eine Herstellerangabe.
- **Betriebsbedingungen gehören zur Eigenschaft.** Eine zu klein gewählte Kontextlänge schneidet die Werkzeugbeschreibungen ab, ohne dass es auffällt, und manche Bedienweisen verlieren Werkzeugaufrufe, während die Antwort im Fluss ausgegeben wird. Beides sind Betriebsfehler mit dem Anschein eines unfähigen Modells — die Störungssuche muss sie benennen können.

### Werkzeug-Audit: drei Bestände, die nicht verwechselt werden dürfen

Wer ein Werkzeug benutzt hat, ist etwas anderes als das, was jemand gefragt hat. Aus dieser Unterscheidung folgen **drei** Bestände. Sie in einem Satz zusammenzuziehen macht eine Vorlage an die Personalvertretung angreifbar: Was für den einen Bestand zugesagt ist, gilt dann scheinbar für alle drei.

| Bestand | Was darin liegt | Aufbewahrung | Wer hineinsieht |
|---|---|---|---|
| **(a) Die Antwort und der Lauf im Gespräch** | die Endantwort mit **allen zitierten Fundstellen** und die verständlich formulierten Schritte („sucht nach der Richtlinie", Zahl der Treffer) | die reguläre, konfigurierbare Aufbewahrung des **Chats** — sie lebt und stirbt mit ihm | die Person selbst, im Gesprächsverlauf, auch am nächsten Tag |
| **(b) Das technische Schrittprotokoll** | was die Störungssuche und die Messung brauchen: Lauf-Kennung, Werkzeug, Ergebnisstatus, Trefferzahl, Dauer, Abbruchgrund — **ohne Konto-Bezug und ohne die formulierte Teilfrage** | **Vorgabe 7 Tage, Höchstwert 30**, automatische Löschung, nicht abschaltbar — und damit deutlich kürzer als die des Nachweisprotokolls | niemand über einen Personeneinstieg; Zugriff **nur über die Lauf-Kennung**, und Störungssuche geschieht an einem **Lauf**, nicht an einem Bestand |
| **(c) Das Nachweisprotokoll** | in dieser Stufe: **nichts**. Ab dem ersten schreibenden Werkzeug: aufrufende Person, Werkzeug, Zielsystem, Zeitpunkt, Entscheidung (bestätigt / abgelehnt / verfallen), Agentenfassung — und die **Lauf-Kennung** | die Frist des Nachweisprotokolls | die benannten Stellen nach dem bestehenden Zugriffsmodell |

**Warum (a) ausdrücklich dasteht.** Die Frage, auf welcher Nachweisgrundlage ein Sachgebiet „Eigenmittel-Nachweis: keine Fundstelle gefunden" festgestellt hat, kommt in zwei Jahren — und sie wird aus der Antwort samt ihren Fundstellen beantwortet, nicht aus technischen Rohdaten. Dass die Antwort der Chat-Aufbewahrung folgt und nicht der kurzen Frist von (b), ist deshalb keine Ableitung aus zwei Kapiteln, sondern eine eigene Zusage. Dasselbe gilt für die Schrittliste: Sie ist **nach** dem Lauf nachschlagbar, nicht nur währenddessen.

**Warum ein lesender Aufruf nicht in (c) gehört.** Er ist Verhalten, und in der Menge ergäbe er genau das Tätigkeitsprofil, dessen Nichtexistenz die Mitbestimmungsfähigkeit trägt. Eine Suche bleibt eine Suche, auch wenn ein Modell sie veranlasst hat. Die Prüferfrage lautet ohnehin nie „wonach hat Person X gesucht", sondern „worauf durfte Person X an diesem Tag zugreifen" — und die beantwortet die Rechtehistorie stärker, weil sie einen Zustand belegt statt das Ausbleiben eines Ereignisses.

**Was für (b) gilt, und zwar wörtlich.** Es gibt **keine Oberfläche und keine Schnittstelle, die diesen Bestand nach Person filtert, gruppiert oder sortiert** — nicht abschaltbar, sondern **nicht gebaut**, und durch eine automatisierte Prüfung nachgewiesen, wie sie für das Nachweisprotokoll bereits existiert. Der Bestand trägt keine Auswertungsoberfläche und keinen Export. Diese Zusage gilt für **jede** Ablage der Anwendung, nicht nur für das Nachweisprotokoll; welche Tabelle etwas trägt, ist nicht die Zusage.

> **Die Ablage von (b) — entschieden, und der Begriff „Diagnosepfad" trägt sie nur zur Hälfte.** Das bestehende Diagnoseprotokoll der Suche bringt Partitionen, einen Aufbewahrungslauf und ein Privilegienmodell mit; es trägt aber eine Aufbewahrung mit dem Vorgabewert **zwölf Monate** (konfigurierbar zwischen einem und vierundzwanzig), eine Einzelsatzansicht und einen anlassbezogenen Lesepfad für die Auswertungsrolle — und das ist das Gegenteil dessen, was (b) zusagt. Die Entscheidung trennt deshalb Infrastruktur von Inhalt: **(b) nutzt die Ablage-Infrastruktur des bestehenden Pfades mit** — es entsteht keine zweite Ablage gleicher Bauart, die beim nächsten Löschauftrag vergessen würde — **aber mit eigener kurzer Frist, ohne Konto-Bezug, ohne Suchtext im Klartext und ohne Lesepfad für Auswertungsrollen.** Die formulierte Teilfrage wird **nicht persistiert**; für die Störungssuche genügt die Kennung der Suchanfrage, solange der Chat die Teilfrage trägt (Bestand (a)). Erweist sich das in der Umsetzung als unzureichend, ist jedes zusätzliche Feld **feldweise zu begründen** und erscheint im Auszug für die Personalvertretung.

**Für die Person selbst bleibt der Lauf sichtbar.** Was sie selbst sehen darf, ist keine Auswertung über sie.

**Keine Kennzahl auf Basis von (b) in dieser Stufe.** Betriebskennzahlen über Werkzeugläufe — Anzahl, Abbrüche an einer Grenze, Anteil der Läufe über *n* Sekunden, Fehlerquote — werden **aggregiert und personenfrei** erhoben, je Organisationseinheit. Sie sind für die Steuerung nötig und brauchen dafür keinen Personenbezug.

### Textwerkzeuge — ohne besondere Umgebung

Sie brauchen keine Ausführungsumgebung und stehen deshalb früh zur Verfügung:

- **Zusammenfassung** mit einstellbarer Länge
- **Übersetzung**
- **Leichte Sprache und Amtssprache** — Umformulierung in beide Richtungen
- **Export** nach Text, Tabelle, Textdokument und Foliensatz

*Phasenlage: Phase 1.*

### Isolierte Ausführungsumgebung je Chat

Alles, was gerechnet, konvertiert oder erkannt werden muss, läuft in einer **isolierten Umgebung, die je Chat aufgesetzt und danach verworfen wird**:

- Dateiverarbeitung, auch großer und gemischter Bestände
- Auswertungen und Berechnungen auf Tabellen
- Texterkennung aus Scans und Bildern
- **Transkription mit Sprechererkennung** — Besprechungen, Anhörungen, Sitzungen
- Erzeugung von Tabellen, Diagrammen und Foliensätzen

**Die Isolation ist der Punkt, nicht die Fähigkeit.** Kein Ausbruch aus der Umgebung, kein Zugriff auf fremde Vorgänge, keine Netzverbindung außer den ausdrücklich erlaubten — und keine Abhängigkeit von einem Interpreter in einer fremden Cloud. Ein Amt, das eine Anhörung transkribiert, gibt sie nicht aus dem Haus.

Wo „ausdrücklich erlaubt" entschieden und durchgesetzt wird, steht unter [Der Ausgang als Kontrollpunkt](#der-ausgang-als-kontrollpunkt).

*Phasenlage: Phase 2.*

### Schreibende Aktionen mit menschlicher Freigabe

Lesen ist harmlos, Schreiben nicht. Schreibende Aktionen — einen Vorgang anlegen, einen Eintrag aktualisieren, eine Nachricht vorbereiten — werden **bewusst freigeschaltet** und laufen über ein Freigabetor:

- Der Agent **bereitet vor**; wirksam wird die Aktion durch eine menschliche Freigabe.
- Angezeigt wird, **was genau** geschehen soll, in welchem System und mit welchen Daten.
- Jede Aktion ist protokolliert, freigegebene wie abgelehnte.
- Welche Aktionen ein Agent überhaupt auslösen darf, steht in seinem Abschnitt **Befugnisse** und wird vom [Prüfstand](#agenten-prüfstand-vor-der-freigabe) gegengeprüft.

**Eine wartende Bestätigung ist ein Vorgang, kein wartender Aufruf.** Das ist die Konstruktionsregel hinter dem Freigabetor, und sie hat drei Folgen, die in der Umsetzung nicht wahlweise sind:

1. **Der beabsichtigte Aufruf steht in der Datenbank** — mit Werkzeug, Argumenten und Bezug auf den Lauf. Er überlebt einen Neustart der Anwendung; ein Verfahren, das die Freigabe an einen offenen Thread hängt, verliert sie beim nächsten Aufgabenwechsel oder Update.
2. **Er hat eine Frist und einen Endzustand.** Wird nicht entschieden, läuft er ab und gilt als nicht ausgeführt. Ein Vorgang, der unbegrenzt wartet, ist ein stiller Rückstand.
3. **Er wird höchstens einmal ausgeführt.** Eine zweite Bestätigung desselben Aufrufs — durch Doppelklick, Neuladen oder zwei Zuständige — erzeugt keinen zweiten Vorgang im Fremdsystem. Die Einmaligkeit wird mit einem **Idempotenzschlüssel am Zielsystem** eingelöst, nicht allein über den eigenen Zustand: Sonst trägt sie nicht über eine Wiederherstellung. Und weil eine eingespielte Sicherung einen bereits ausgeführten Aufruf wieder auf „wartend" setzen kann, gilt: **Eine Wiederherstellung setzt alle wartenden Bestätigungen auf „verfallen".** Ein wiederhergestellter Wartezustand wird nie ausgeführt; die Person startet neu.

Die Bestätigung hängt außerdem **nicht davon ab, dass das Modell von sich aus fragt**. Sie ergibt sich aus der Klassifikation des Werkzeugs als schreibend und wird an der Ausführung erzwungen, nicht an der Formulierung des Systemvorspanns.

**Das eigentliche Problem der Bestätigung ist nicht ihre Umgehung, sondern ihre Gewöhnung.** Eine Karte, die dreißigmal am Tag gleich aussieht, wird nach zweihundert Wiederholungen zur Handbewegung — derselbe Mechanismus, an dem eine Vier-Augen-Prüfung im Alltag verkommt. Eine Kennzahl ändert daran nichts; sie zählt nur mit. Drei Festlegungen wirken dagegen:

- **Die Karte hebt hervor, was von den letzten Aufrufen desselben Werkzeugs abweicht** — anderes Zielsystem, anderer Empfänger, abweichende Frist, abweichender Betrag. Nicht der Normalfall verdient Aufmerksamkeit, sondern die Abweichung.
- **Werte aus einer Fundstelle und vom Modell formulierte Werte sind unterscheidbar dargestellt.** Weil die Anweisungstreue nur gemessen und nicht erzwungen ist (siehe [Erzwungen oder gemessen](#erzwungen-oder-gemessen)), ist das der Punkt, an dem eine zeichnende Person den Unterschied überhaupt sehen kann.
- **Eine Sammelbestätigung ist ausgeschlossen.** Sie entstünde beim ersten Skill, der zehn Vorgänge auf einmal vorschlägt, und mit ihr wäre die Schranke weg.

**Verfall statt automatischer Vertretung.** Läuft die Frist ab, gilt der Aufruf als nicht ausgeführt und die Sache geht an die zuständige Stelle zurück. Eine Vertretung wird ausschließlich **von Hand** benannt — durch die Person selbst oder die Leitung. Eine automatische Vertretung bräuchte eine Datenquelle, und die einzige verfügbare wäre der Abwesenheitsstand; OPAA verarbeitet für einen technischen Nebenzweck keine Urlaubs- und Krankheitszeiten. Ebenso wenig entstehen **Auswertungen über Entscheidungsdauern, Ablehnungs- oder Ablaufquoten je Person** — in keiner Ablage, weder aggregiert über einen Personenfilter noch einzeln.

Damit das eine Schranke ist und keine Vereinbarung, darf die Freigabe nicht davon abhängen, dass der Agent von sich aus fragt. Sie hängt stattdessen am Weg nach draußen: [Der Ausgang als Kontrollpunkt](#der-ausgang-als-kontrollpunkt).

*Phasenlage: Phase 2.*

### Der Ausgang als Kontrollpunkt

Die beiden vorigen Kapitel lassen dieselbe Frage offen: **Wo sitzt die Durchsetzung?** Solange ein Agent selbst entscheidet, wann er um Freigabe bittet, ist die Freigabe eine Vereinbarung. Und solange ein Werkzeug sein Zugangsgeheimnis in der Ausführungsumgebung braucht, liegt dieses Geheimnis genau dort, wo modellgesteuerter Code läuft — ein einziger erfolgreicher Prompt-Injection-Treffer genügt, um es auszuleiten.

Die Antwort ist, den Kontrollpunkt aus dem Code herauszunehmen und an den **Ausgang** zu legen. Drei Anforderungen:

1. **Das Tor ist der einzige Weg nach draußen.** Jede ausgehende Verbindung der Ausführungsumgebung läuft zwingend darüber. Dort — und nur dort — wird entschieden: durchlassen, verweigern, oder einer Person zur Freigabe vorlegen. Der Agent kann das Tor nicht umgehen, weil es keinen zweiten Weg gibt.
2. **Die Ausführungsumgebung sieht nie echte Zugangsdaten.** Sie arbeitet mit Platzhaltern; das echte Geheimnis setzt das Tor erst beim Verlassen der Umgebung ein. Damit ist „darf diesen Aufruf auslösen" von „kennt das Geheimnis" entkoppelt — das ist die eigentliche Pointe. Was der Agent nie hatte, kann er nicht ausleiten.
3. **Eine wartende Freigabe hat Bestand.** Der Zustand einer geparkten Entscheidung liegt in der Datenbank, nicht im Prozess. Ein Aufruf, der auf eine Freigabe wartet, überlebt einen Neustart und ist ein Vorgang, kein hängender Thread.

Das Tor ist zugleich der natürliche Ort der zugesagten Protokollierung: eine Stelle statt jedes einzelne Werkzeug. Und es trägt die Anbindung von Fremdsystemen mit — auch ein MCP-Aufruf ist eine ausgehende Verbindung.

**Es protokolliert Metadaten, keine Nutzlasten.** Ziel, Werkzeug, Zeitpunkt, Ergebnisstatus, Bestätigungsentscheidung — nicht der Inhalt. Das ist keine Feinheit: Eine Stelle, an der jeder ausgehende Inhalt entschlüsselt vorliegt und „vollständig protokolliert" wird, ist ein Punkt, an dem Arbeitsinhalte mitgelesen werden. Eine Protokollierung von Inhalten am Tor wäre ein eigenes Vorhaben mit eigener Beteiligung, keine Ausbaustufe dieses Kapitels.

**Der Weg nach draußen setzt Netzfreiheit voraus, die viele Häuser nicht haben.** In den meisten Behördennetzen geht ausgehender Verkehr über einen zentralen, TLS-aufbrechenden Proxy mit eigener Zertifizierungsstelle. Das Tor bricht ebenfalls auf — es entstünden zwei Aufbrecher in Reihe, und das Tor muss durch den vorgelagerten Proxy hindurch sprechen können. Unterstützung für einen ausgehenden Proxy (Adresse, Ausnahmeliste, Zugangsdaten) und die Verkettung der Vertrauensanker gehören deshalb zum Zuschnitt des Tors, nicht in eine Nacharbeit.

> Das Muster ist an einem fremden System nachgewiesen, das es ausgearbeitet betreibt. Die Nennung dort steht als nachprüfbarer Sachbeleg für eine technische Aussage, nicht zur Positionierung von OPAA — die Grenze dieser Ausnahme regelt [MESSAGING.md](../market/MESSAGING.md#was-wir-nicht-sagen). Das untersuchte System ist **Onyx**, dessen Dokumentation Egress-Gate, Platzhalter-Zugangsdaten und die Datenbank als Wahrheitsquelle für Freigaben ausdrücklich beschreibt.

**Drei Punkte sind hier offen und werden nicht stillschweigend übergangen:**

- **TLS-Aufbruch.** Ein Tor, das Zugangsdaten einsetzt und Ziele prüft, muss die Verbindung aufbrechen. In einer Behörde ist das begründungs- und dokumentationspflichtig, und es entscheidet mit darüber, was dabei protokolliert wird und was ausdrücklich nicht.
- **Gilt das Tor auch für lesende Aufrufe?** Für die Zugangsdaten ja; für den Freigabeschritt wäre es lästig und würde die Schranke abstumpfen. Die Grenze gehört gezogen, nicht offengelassen.
- **Verhältnis zur bestehenden Zielprüfung.** Für die URL-basierten Quellentypen sichert bereits eine Prüfung gegen private, lokale und nicht routbare Adressbereiche ab (siehe [knowledge-sources.md](./knowledge-sources.md)). Ob das Tor diese Prüfung übernimmt oder neben ihr steht, ist ungeklärt.

Die Architekturentscheidung dazu gehört zur Umsetzung, nicht in dieses Dokument.

*Phasenlage: Phase 2, gemeinsam mit der Ausführungsumgebung.*

### MCP als standardisierte Anbindung

Werkzeuge und Fremdsysteme werden über das **Model Context Protocol (MCP)** angebunden — ein offener, selbst betreibbarer Standard. Das ist die Antwort auf die Alternative, Integrationen über fremde Automatisierungsdienste zu beziehen: Die widerspricht dem Betrieb im eigenen Haus, weil sie Vorgangsdaten über einen Dritten führt.

**Abgrenzung der Richtung.** Dieses Kapitel beschreibt OPAA als MCP-**Client**: Ein Agent von OPAA
ruft ein fremdes Werkzeug auf. Das bleibt Phase 2. Die **Gegenrichtung** — OPAA als MCP-**Server**,
den ein fremdes KI-Werkzeug als Wissensquelle anspricht — ist am 18.09.2026 entschieden und in
[external-access.md](./external-access.md) beschrieben. Die beiden Richtungen teilen nur den
Protokollnamen: Die Client-Richtung führt Daten aus OPAA heraus in ein Werkzeug, das handelt; die
Server-Richtung liefert Fundstellen an ein Werkzeug, das fragt. Entscheidungen der einen Richtung
gelten nicht automatisch für die andere.

**Nur über gesicherte Netzverbindungen, und nur von einer Zulassungsliste.** Welche fremden Werkzeugserver überhaupt in Frage kommen, entscheidet die **Systemverwaltung** und hinterlegt es in einer Zulassungsliste; ein Agenten- oder Skill-Autor wählt aus dieser Liste aus, er erweitert sie nicht. Der Grund ist derselbe, aus dem die Zielprüfung der Konnektoren existiert: Ein Werkzeug, dessen Adresse jemand frei eintragen darf, ist ein Weg nach draußen, den niemand beschlossen hat. Zugangsdaten liegen verschlüsselt in der Anwendung, nie in einer Werkzeugbeschreibung. Anbindungen, die einen lokalen Prozess starten, sind ausgeschlossen — „der Nutzer darf das Kommando festlegen" ist keine Einstellmöglichkeit, sondern eine Ausführungslücke.

**Eine Liste ohne Pflege ist im dritten Jahr eine Fehlerquelle.** „Die Systemverwaltung" ist eine Rolle, keine Zuständigkeit. Jeder Eintrag trägt deshalb eine **verantwortliche Gruppe** (nicht eine Person — dieselbe Begründung wie beim Eigentum an Assets) und einen **Erreichbarkeitszustand**. Ein dauerhaft nicht erreichbares Ziel oder abgelaufene Zugangsdaten führen zur Stilllegung des Eintrags und zu einem Vermerk auf der Governance-Arbeitsliste; ein Skill, der ein stillgelegtes Werkzeug nennt, wird als eingeschränkt angezeigt, statt Aufrufe vorzuschlagen, die ins Leere laufen. Das ist derselbe Mechanismus, den die Verteilungsschicht für „Nachfolge offen" schon beschreibt — nichts Neues, nur angewandt.

**Auf der Sicherheitsgrenze keine unfertige Fremdbibliothek.** Wo Zugangsdaten, Tokenausstellung, Zielprüfung oder das Ausgangstor betroffen sind, wird keine Bibliothek unterhalb einer stabilen Fassung und keine ohne belastbares Pflegeversprechen eingesetzt; eine dünne, selbst geschriebene und selbst geprüfte Schicht ist dort vorzuziehen. Der Grund ist nicht Geschmack: Für ein Vorabartefakt meldet der Schwachstellenweg des Projekts typischerweise nichts — eine Lücke bliebe unbemerkt. **Innerhalb** der Grenze (Paketformat, Hilfsfunktionen) ist eine frühe Fassung vertretbar, wenn die Version festgelegt und in der Stückliste geführt ist.

**Verhältnis zu den Konnektoren — der Ist-Stand, ohne neue Festlegung.** Die Konnektoren sind heute **fest in das Produkt gebaut** (Dateiverzeichnis, Webverzeichnis, RSS, Confluence, S3-Objektspeicher und der Upload-Weg, siehe [knowledge-sources.md](./knowledge-sources.md)); eine ladbare Erweiterungsschnittstelle **ist nicht gebaut**. Ob es sie einmal geben soll, ist damit **nicht entschieden**: [ADR-0014](../decisions/0014-produktausrichtung-oeffentliche-verwaltung.md) führt die Plugin-Architektur für Konnektoren ausdrücklich als Option, die vorerst bestehen bleibt, und [ADR-0017](../decisions/0017-quellentypmodell-indizierung.md) hält das Quellentypmodell bewusst schmal, damit es später Grundlage einer solchen Schnittstelle sein kann; die Vorgänge #106 und #126–#130 sind unentschieden zurückgestellt. Der Sammelvorgang **#349** wurde ohne Festlegung geschlossen — die Klärung läuft unabhängig vom Ticketbestand.

**Für die Werkzeuge dieses Dokuments ist der Weg dagegen gesetzt: MCP**, nicht eine ladbare Erweiterung. Das ist keine Aussage über die Konnektoren, sondern über die andere Aufgabe. Die Arbeitsteilung, die beide Wege trennt:

| | Konnektor | Werkzeug über MCP |
|---|---|---|
| **Richtung** | zieht, im Rhythmus des Systems | wird während einer Aufgabe befragt |
| **Tiefe** | greift in die Indizierung, spiegelt Quellrechte, meldet in den Indizierungslauf | ruft ab und liefert zurück, ohne Zugriff auf Interna |
| **Zulassung** | Teil des Produkts, mit dem Produkt geprüft | Zulassungsliste der Systemverwaltung je Installation |

Die Frage der **Isolation ausführender Erweiterungen** bleibt davon unberührt und offen — sie gehört zur Ausführungsumgebung und nicht zur Anbindung; siehe [Der Ausgang als Kontrollpunkt](#der-ausgang-als-kontrollpunkt) und die offenen Fragen am Ende.

*Phasenlage: Phase 2.*

### Mitgelieferter Startkatalog

OPAA liefert erprobte Verwaltungsagenten und -prompts ab Werk aus — Aktenzusammenfassung, Leichte Sprache, Vermerksentwurf, Recherche. Eine Behörde startet damit nicht bei null und hat zugleich Beispiele dafür, wie eine brauchbare Aufgabenbeschreibung aussieht.

Ihre Behandlung — eigener Herkunftstyp, keine Änderung vor Ort, Anpassung über einen gekennzeichneten Abkömmling, Schutz vor Überschreiben durch Produkt-Updates — ist in [spaces-and-assets.md](./spaces-and-assets.md#mitgelieferte-assets) geregelt.

*Phasenlage: Textprompts in Phase 1, Agenten in Phase 2.*

---

## Abgrenzung: kein Prozessbaukasten

OPAA verkettet Schritte, es führt keine Verwaltungsprozesse aus. Ein **visueller Prozessbaukasten** ist ausdrücklich nicht Teil des Produkts, und der Grund ist ein sachlicher:

> OPAA ist die **belegte Wissens- und Agentenschicht**, nicht das System, das Verwaltungsprozesse ausführt.

Fachverfahren, Vorgangsbearbeitung und elektronische Akte sind die Systeme, in denen ein Verwaltungsvorgang läuft; sie tragen die Zuständigkeiten, Fristen und Rechtsfolgen. Ein zweiter, paralleler Prozessraum in OPAA hätte dieselben Vorgänge mit anderer Wahrheit — und die Frage, welche der beiden gilt, ist im Streitfall nicht beantwortbar. Eine **leichte Verkettung mehrerer Schritte** innerhalb eines Agenten bleibt dagegen eine Option; sie ordnet die Arbeit eines Agenten, sie ersetzt kein Fachverfahren.

---

## Empfohlene Reihenfolge

*Keine Termine — eine Ordnung mit Begründung. Jeder Schritt ist für sich nutzbar; wer ihn überspringt, zahlt ihn später doppelt.*

**1 · Die Verteilung, am kleinsten Gegenstand.** Prompt-Bibliothek (#1726) und Skill (#1727) zuerst. Grund: Das Rechte- und Verteilungsmodell kennt bis heute genau einen Fall, die Wissensbibliothek. Ob es typunabhängig trägt, ist eine Aussage über das Fundament — sie wird am billigsten Gegenstand geprüft, nicht am teuersten. Ein Agent ist der schlechteste erste Asset-Typ: Trägt es nicht, weiß niemand, ob das Verteilungsmodell oder die Agenten-Laufzeit schuld war. Zugleich ist Stufe 1 der Szenarien damit vollständig erreicht — eine geteilte Arbeitsweise, ohne dass eine einzige Zeile Laufzeit entstanden ist.

**2 · Das Fundament für Werkzeugaufrufe im Chat.** Modellkatalog mit der Fähigkeit „Werkzeugaufrufe", die Schleife mit der Suche als erstem, rein lesendem Werkzeug, ein Kanal für sichtbare Zwischenschritte, das Werkzeug-Audit und die Messung der Werkzeugfähigkeit lokaler Modelle. Grund: Das ist die Mechanik aller weiteren Stufen — und, ehrlich etikettiert, ein Infrastrukturschritt: Ohne einen Skill, der sie benutzt, merkt in der Fläche niemand etwas davon. Was er liefert, ist eine gemessene Fähigkeitsangabe je Modellkombination, ein Katalog der Betriebsfehler, die wie ein unfähiges Modell aussehen, und die Mechanik für Schritt 3. Vor allem aber ist es die Stelle, an der sich die Modelle des Hauses bewähren müssen.

**Zwei betriebliche Voraussetzungen, die dieser Schritt benennt und einlöst, statt sie zu erben.** Der Kanal für sichtbare Zwischenschritte hebt eine geprüfte Eigenschaft auf: [ADR-0021](../decisions/0021-single-instance-betrieb.md) führt „keine an eine Instanz gebundene Verbindung" als Grund dafür, dass ein späterer Mehrinstanzbetrieb unproblematisch wäre — mit einem Ereigniskanal gilt das nicht mehr, und der ADR ist nachzutragen. Und der mit dem Produkt ausgelieferte Reverse-Proxy puffert Antworten und bricht nach einem festen Lesezeitfenster ab; ohne Anpassung kämen die Zwischenschritte in einer Container-Installation gesammelt am Ende an oder gar nicht. Beides ist in #1752 als Abnahmekriterium geführt — dieselbe Sorgfalt, mit der der vorgelagerte ausgehende Proxy beim [Ausgangstor](#der-ausgang-als-kontrollpunkt) benannt ist. Fällt diese Messung schlecht aus, betrifft das jede weitere Stufe, und es ist besser, das vor dem Bau von Onboarding und Prüfstand zu wissen.

**3 · Skills mit Werkzeugen.** Erst hier trifft beides zusammen: Der Skill darf die Suche mehrfach benutzen (Szenarien-Stufe 2). Grund: Es setzt 1 und 2 voraus und fügt nichts Neues hinzu außer der Verbindung — der kleinste Schritt mit dem größten fachlichen Sprung, von „hilft beim Formulieren" zu „prüft einen Vorgang".

**3a · Vor dem vierten Schritt: die Genehmigungsfähigkeit des Ausgangs-Tors klären.** Keine Umsetzung, eine Vorlage — an Informationssicherheit und Personalvertretung: TLS-Aufbruch, Umfang der Protokollierung am Tor, Verhältnis zur bestehenden Zielprüfung, Zusammenspiel mit einem vorgelagerten ausgehenden Proxy. Grund: Die tragende Pointe des Tors (die Ausführungsumgebung sieht nie echte Zugangsdaten) setzt den Aufbruch voraus. Wird er in einem Haus nicht genehmigt, fällt ein Teil von Schritt 4 — und das ist dasselbe Argument, mit dem die Modellmessung nach vorn gezogen wird: Das Risiko wird erhoben, bevor darauf gebaut wird. Zwei Wochen Klärung ersparen gegebenenfalls ein Quartal Bau.

**4 · Fremde Werkzeuge mit Bestätigung.** Anbindung nach draußen, Zulassungsliste, Klassifikation lesend/schreibend, wartende Bestätigung als Vorgang. Grund: Ab hier entstehen Wirkungen außerhalb von OPAA, und die Schranke davor ist aufwendiger als der Aufruf selbst. Sie vorzuziehen hieße, sie ohne einen einzigen echten Anwendungsfall zu entwerfen; sie nachzuziehen hieße, schreibende Werkzeuge ohne Schranke zu betreiben. Deshalb genau hier, und mit dem Weg nach draußen als einem Kontrollpunkt statt einer Prüfung je Werkzeug.

**5 · Der Agent als Paket.** Asset-Typ, geführtes Onboarding, Prüfstand, Lauf mit Zwischenstand, Prüfagenten für kritische Vorgänge. Grund: Ein Agent ist die Bündelung dessen, was in 1 bis 4 einzeln entstanden ist, plus die Governance. Erst wenn Verteilung, Werkzeuge und Freigabe stehen, ist ein Agenten-Paket überhaupt beschreibbar — und erst dann hat der Prüfstand etwas zu prüfen, das sich nicht in einem Freitextfeld erschöpft.

**6 · Zuletzt: Auslösung ohne Person und eine Umgebung für Code.** Zeitpläne, Ereignis-Auslöser, isolierte Ausführungsumgebung, Transkription und Tabellenauswertung. Grund: Beide Themen öffnen eine Frage, die keine der vorigen Stufen beantwortet — mit wessen Rechten arbeitet ein Agent, den niemand aufgerufen hat, und wo läuft Code, den ein Modell erzeugt hat. Beides ist zudem die teuerste Investition des ganzen Bereichs; sie vorzuziehen bindet Aufwand, bevor klar ist, welche Werkzeuge tatsächlich gebraucht werden.

**Bauen in dieser Reihenfolge, einschalten getrennt.** Zwei Schritte dürfen parallel **gebaut** werden; sie dürfen nicht in derselben Auslieferung **scharf** werden. Eine Installation, die gleichzeitig einen neuen Asset-Typ und die Werkzeugschleife bekommt, kann einen Fehler nicht mehr zuordnen — und das ist genau die Situation, in der beides abgeschaltet wird. Der Schalter mit der Vorgabe „aus" ist dafür das Werkzeug.

**Was diese Reihenfolge bewusst nicht tut:** Sie stellt die Laufzeit nicht vor die Verteilung, und sie stellt die Governance nicht vor das, was sie prüfen soll. Beide Umstellungen wären naheliegend — die erste, weil eine Laufzeit sichtbarer ist; die zweite, weil eine Prüfvorschrift beruhigt. Beide erzeugen Arbeit, die zum Zeitpunkt ihrer Entstehung nicht bewertbar ist.

---

## Integrationspunkte

| Bezug | Was dort geregelt ist |
|---|---|
| [spaces-and-assets.md](./spaces-and-assets.md) | Rechte an Agenten, Freigabekette für das Wissen, Verteilungsstufen, Katalog, Versionierung, Freigabeweg, Export und Import — **das Leitdokument** |
| [access-control.md](./access-control.md) | Identität, Gruppen, Systemverwaltung, Protokollierung |
| [external-access.md](./external-access.md) | Die **Gegenrichtung**: OPAA als MCP-Server für fremde KI-Werkzeuge, mit Zugangstokens, Bibliotheksfreigabe und Installationsschalter. Nicht Gegenstand dieses Dokuments |
| [data-indexing-rag.md](./data-indexing-rag.md) | Abfrageablauf, Quellenbindung und Belegvalidierung, auf denen Belegtreue und Prüfagenten aufsetzen |
| [search-quality-evaluation.md](./search-quality-evaluation.md) | Messrahmen für Referenzfälle, den der Prüfstand auf Agentenebene weiterverwendet |
| [llm-integration.md](./llm-integration.md) | Modellwahl und zentrale Vorgaben als Obergrenze für die Modellwahl eines Agenten; die Fähigkeiten eines Modelleintrags, darunter „Werkzeugaufrufe"; und die harte Regel, dass der **Systemvorspann nicht über den Chat änderbar** ist — Skills und Agenten setzen ihre Blöcke daneben, sie ersetzen ihn nicht |
| [security-and-compliance.md](./security-and-compliance.md) | Die **geschlossene Liste** der Protokollereignisse. Ein nachweispflichtiger Werkzeugaufruf braucht dort einen Eintrag, bevor er geschrieben werden darf; die Nichtprotokollierung von Abfragen gilt auch für Suchaufrufe eines Werkzeugs |
| [VISION.md](../VISION.md) · [USE-CASES.md](../USE-CASES.md) | Einordnung in die Themenbereiche und Anwendungsfälle im Alltag |

---

## Offene Fragen / Zukünftige Erweiterungen

- **Zuschnitt des Ausgangs-Tors** — TLS-Aufbruch, Geltung für lesende Aufrufe, Verhältnis zur bestehenden Zielprüfung und zu einem vorgelagerten ausgehenden Proxy, siehe [Der Ausgang als Kontrollpunkt](#der-ausgang-als-kontrollpunkt). Die Klärung steht **vor** Schritt 4 der Reihenfolge, nicht in ihm.
- **Wie hoch die drei Grenzen und das Platzlimit liegen.** Zu niedrig bricht eine Prüfung mit vielen Anforderungen ab, zu hoch macht einen Lauf unbezahlbar; die Kalibrierung an realistischen Fällen ist beschrieben, die Zahlen sind es nicht.
- **Ob die mehrschrittige Suche die heutige feste Zerlegung der Frage ablöst** oder neben ihr bestehen bleibt. Beides ist begründbar; die Entscheidung berührt die Messbarkeit der Suchqualität, weil sie einen gemessenen Pfad verändert. Unabhängig davon gilt: Im Werkzeugpfad läuft **keine zusätzliche** feste Zerlegung — sonst werden zwei Suchstrategien im selben Lauf bezahlt.
- **Ob ein Modell zu einem Werkzeugaufruf eine Begründung mitliefern muss.** Sie macht den Zwischenschritt für die Person lesbar — und ist zugleich eine Selbstauskunft des Modells, die niemand prüfen kann.
- **Wie streng die Struktur der Aufgabenbeschreibung erzwungen wird.** Ein Freitextfeld „Sonstiges" ist bequem und höhlt die Prüfbarkeit aus; ganz ohne Ausweichfeld wird die Struktur mancher Aufgabe nicht gerecht.
- **Wie die Mindest-Prüffallbasis bemessen wird.** Dass ein Bericht unterhalb davon „nicht aussagekräftig" heißt und dass der Startkatalog Prüffälle mitbringt, ist entschieden; die Zahl je Kategorie ist es nicht.
- **Ob und wann Beschäftigte über eine eingeschaltete Werkzeugschleife unterrichtet werden.** Bei einer Funktion, die sichtbar arbeitet, während man zusieht, entscheidet der erste Eindruck darüber, ob sie als Hilfe oder als Aufsicht gilt — und der entsteht beim Einschalten, nicht beim Erklären. Die Beteiligung der Personalvertretung ist Sache des Hauses; dass das Produkt den Zeitpunkt des Einschaltens zu einem bewussten Akt macht (Schalter, Vorgabe aus), ist die Voraussetzung dafür.
- **Welches Modell die Prüfagenten benutzen.** Dasselbe Modell teilt die Fehler des erzeugenden Agenten; ein anderes ist nicht immer verfügbar, insbesondere im Betrieb ohne Netzanbindung.
- **Umgang mit Zeitüberschreitungen und Abbrüchen in der Ausführungsumgebung** bei sehr großen Beständen — Teilergebnis oder Fehlschlag.
- **Ob ein Agent einen anderen Agenten aufrufen darf.** Naheliegend, aber ungeklärt sind Rechtekontext, Protokollierung und die Frage, wessen Prüfbericht dann gilt.

### Offene Fragen zur Oberfläche

Gesetzt ist: Der Chat bleibt der Ort der Arbeit, Agenten werden dort mit `@` aufgerufen, verwaltet werden sie wie Wissensbibliotheken, gefunden im Katalog (siehe [Zielbild der Weboberfläche](../design/redesign-prompt.md)). Eine eigene Arbeitsfläche „Agenten" gibt es nicht. Nicht entschieden ist:

- **Wie ein Skill im Chat gewählt wird.** Das Zielbild kennt `@` für Bestände und Agenten, aber noch keine Skills. Mit `@` in derselben Vorschlagsliste, über eine eigene Auswahl am Eingabefeld oder über ein anderes Zeichen — die Liste muss drei Arten sofort unterscheidbar halten: Ein Bestand verengt die Suche, ein Skill und ein Agent verändern die Arbeitsweise.
- **Wie lange eine Wahl wirkt.** Für einen Bestand ist entschieden: eine einzelne Frage. Eine Arbeitsweise trägt dagegen meist mehrere Nachrichten. Richtung: Skill und Agent gelten für den Chat, stehen sichtbar am Eingabefeld neben der Angabe des Durchsuchten und sind dort abwählbar — eine Wirkung, die man nicht sieht, ist eine Fehlerquelle.
- **Wo ein Lauf wiedergefunden wird, der länger dauert als die Aufmerksamkeit.** Richtung: Das Ergebnis landet in dem Chat, aus dem der Lauf gestartet wurde, und der Chat trägt in der Seitenleiste eine Ungelesen-Markierung; der Space-Wechsler trägt sie mit, sonst bleibt sie in einem anderen Space unsichtbar. Das Postfach des Benachrichtigungssystems ist die Ebene darüber — spaceübergreifend und für alles, was eine Handlung mit Frist verlangt, voran die wartende Bestätigung eines schreibenden Aufrufs, die sonst unbemerkt verfällt. Das Postfach verweist auf den Chat und dupliziert keinen Inhalt.
- **Was die Ungelesen-Markierung speichern darf.** Sie braucht je Person und Chat einen Lesestand. In einem geteilten Chat ist das ein Datum darüber, wer wann was gelesen hat. Richtung: nur der eigene Lesestand, nur für die eigene Anzeige, keine Lesebestätigung für andere, keine Auswertung, Löschung mit dem Chat — und ein Eintrag im Auszug für die Personalvertretung.
- **Ob ein Chat mit neuer Aktivität in der Liste nach oben rückt.** Sortierung nach letzter Aktivität ist das vertraute Muster; in einem geteilten Space bewegen dann aber fremde Läufe die eigene Liste.
- **Wo Artefakte erscheinen.** Das [Artefakt](./spaces-and-assets.md#artefakte) ist als space-eigenes Objekt spezifiziert — zunächst privat, mit Ursprungs-Chat, ersetzbar, in eine Wissensbibliothek übernehmbar —, das Zielbild der Oberfläche hat dafür aber noch keinen Ort. Richtung: Das Artefakt erscheint an der Nachricht, die es erzeugt hat, und zusätzlich in einer Artefaktliste des Space. Erzeugt wird es serverseitig aus Vorlagen, nicht durch frei ausgeführten Code (siehe [Reihenfolge](#empfohlene-reihenfolge)).
- **Welches Sichtbarkeitsmodell für Läufe und Artefakte gilt.** [spaces-and-assets.md](./spaces-and-assets.md#space-eigene-inhalte-chats-und-artefakte) lässt Chats und Artefakte privat entstehen und erst durch Teilen space-sichtbar werden; das Zielbild der Oberfläche weicht davon bewusst ab und zeigt jeden Chat allen Mitgliedern. Für Ungelesen-Markierung, Artefaktliste und das Ergebnis eines Agentenlaufs muss eine der beiden Fassungen gelten — ein Prüfbericht zu einem Vorgang ist kein Inhalt, dessen Sichtbarkeit aus einem Versehen folgen darf.
- **Ob ein Agent ohne vorheriges Gespräch gestartet werden kann** — aus dem Katalog oder von seiner Detailseite, „Dokument hinein, Bericht heraus". Nach dem Grundmodell entsteht dabei ein neuer Chat im aktiven Space; festgehalten ist das nirgends.

---

## Erfolgs-Metriken

Alle Angaben aggregiert, ohne Personenbezug. Die drei Bedingungen dafür — Voreinstellung „aus", Schalter an der **Erhebung** statt an der Anzeige, und eine Mindestgruppengröße, die sich an der Zahl der tatsächlich **nutzenden Personen** bemisst — stehen in [Nutzungstransparenz](./spaces-and-assets.md#nutzungstransparenz) und gelten für jede Zahl dieser Liste unverändert. Dazu eine Bedingung, die nur hier nötig ist:

- **Keine Zahl dieser Liste wird je Person erhoben oder je Person auswertbar** — auch nicht mittelbar über einen Personenfilter auf einem anderen Bestand, insbesondere nicht über das technische Schrittprotokoll eines Werkzeuglaufs.

**Nutzen — leistet das Verfahren, was es soll:**

- **Anteil der Skill- und Agenten-Läufe, deren Ergebnis weiterverwendet wird** (übernommen, exportiert, weiterbearbeitet) statt verworfen. Die einzige Zahl, die über die erleichterte Arbeit etwas sagt. Ohne sie misst diese Liste den Prüfapparat, und was gemessen wird, wird optimiert — dann sieht der Apparat gut aus, auch wenn niemand mehr mit den Agenten arbeitet.
- **Wie viele Agenten Fachbereiche selbst anlegen** — die eigentliche Probe auf das Onboarding. Entstehen Agenten nur in der IT, hat das Verfahren sein Ziel verfehlt.
- **Anteil der Agenten, die im letzten Jahr eine Änderung erfahren haben.** Anlegen ist billig, pflegen ist die Probe; ohne diese Zahl misst die vorige Pflegeversagen als Erfolg.

**Betrieb — hält die Mechanik:**

- **Anteil der Läufe, die an einer Grenze endeten** statt mit einem Ergebnis. Eine hohe Quote heißt, dass die Grenze falsch bemessen oder die Aufgabe falsch geschnitten ist.
- **Anteil der Läufe über *n* Sekunden.** Die Wartezeit ist der Grund, aus dem ein Angebot im Amt liegen bleibt — und sie fällt sonst niemandem auf, weil es bewusst keine personenbezogene Auswertung gibt und die Betroffenen einfach aufhören, den Skill zu benutzen.

**Governance — trägt das Verfahren:**

- **Anteil der Agenten mit vollständig ausgefüllten Abschnitten Befugnisse und Grenzen.**
- **Anteil der Freigaben mit vorliegendem Prüfbericht**, und darunter der Anteil der Berichte, die die Mindest-Prüffallbasis erreichen. Ein Bericht unterhalb der Basis zählt nicht als vorliegend.
- **Quote der im Prüfstand vor der Veröffentlichung abgefangenen Verschlechterungen** — sie zeigt, ob die Regressionsprüfung trägt.
- **Anteil der bestätigungspflichtigen Aktionen, die abgelehnt oder geändert werden.** Nahe null ist kein Urteil über Personen, sondern ein Anlass, **das Verfahren** zu prüfen: Gestaltung der Bestätigungskarte, Häufigkeit gleichartiger Aufrufe, Hervorhebung der Abweichungen. Ob jemand „noch hinsieht", ist keine Frage an eine Kennzahl.
- **Trefferquote der Prüfagenten**: Wie oft ihr Einspruch von der Sachbearbeitung bestätigt wird. Eine niedrige Quote bedeutet, dass das Verfahren Arbeit erzeugt statt sie zu ersparen — und ist dann Anlass, es abzuschalten, nicht bloß eine Zahl im Bericht.
