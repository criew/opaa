# Agenten, Prompts & Werkzeuge

> **Status: Früher Entwurf — wesentliche offene Fragen verbleiben.**

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

Welche Wissensbibliotheken ein Agent nutzt, ist Teil **seiner** Beschreibung und nicht des Raums, in dem er läuft. Der Space verengt einen Chat ohne gebundenen Agenten, er verengt aber **nicht** den Agenten (siehe [Suchbereich je Chatart](./spaces-and-assets.md#suchbereich-je-chatart)).

Diese Asymmetrie ist der Kern der Prüfbarkeit. Würde der Space zusätzlich verengen, antwortete dieselbe freigegebene Fassung je nach Aufrufort anders — ein Prüfbericht sagte dann nichts über den nächsten Aufruf aus, und die Freigabe wäre nicht mehr als ein Datum. Umgekehrt gilt: Weil die Bindung mitreist, bringt ein geteilter Agent sein Wissen mit, statt beim Empfänger neu zusammengesetzt zu werden.

**Der Preis dafür steht in [spaces-and-assets.md](./spaces-and-assets.md#einen-agenten-weitergeben-die-freigabekette) und wird hier nicht wiederholt:** Damit ein geteilter Agent beim Empfänger tatsächlich etwas findet, muss sein Wissen mitfreigegeben werden. Ein Agent, dessen Bibliotheken nicht freigegeben werden dürfen, ist nicht teilbar. Es gibt keinen Kanal, über den Wissen an der Rechteschicht vorbeifließt: **Ein Agent ruft ausschließlich mit den Rechten der aufrufenden Person ab.**

### Skills und Prompt-Bibliotheken

Nicht jede wiederverwendbare Fähigkeit braucht einen eigenen Agenten. Drei Ausprägungen mit steigendem Gewicht:

| | Was es ist | Wann es reicht |
|---|---|---|
| **Prompt** | eine benannte, wiederverwendbare Anweisung in einer Prompt-Bibliothek | Ein wiederkehrender Arbeitsschritt ohne eigene Wissensbindung und ohne Werkzeuge |
| **Skill** | eine benannte Teilfähigkeit mit Anleitung und Beispielen, die ein Agent einbinden kann | Eine Fähigkeit, die mehrere Agenten teilen — etwa „Vermerk nach Hausstandard gliedern" |
| **Agent** | das vollständige Paket oben | Eigene Wissensbindung, eigene Werkzeuge oder eigene Befugnisse |

Alle drei sind **Assets** im Sinne von [spaces-and-assets.md](./spaces-and-assets.md#was-ein-asset-ist) und erben Rechte, Versionierung, Katalog, Freigabeweg und Portabilität unverändert. Dieses Dokument beschreibt ihren Inhalt, nicht ihre Verteilung.

*Phasenlage: Prompt-Bibliotheken je Space in Phase 1; Agenten als teilbares Paket in Phase 2. Skills als eigene Objektart sind eine Ausbaustufe und noch nicht geschnitten.*

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

**Register ist kein Charakter.** Gemeint ist die Sprachebene einer Auskunft, nicht eine Persönlichkeit. Ein Amt braucht keinen freundlich-lockeren Assistenten, sondern eine Auskunft im richtigen Register: Aktenvermerk, Bürgeranschreiben oder Leichte Sprache. Ein Persönlichkeitsprofil erzeugt dagegen einen Ton, für den niemand zuständig ist.

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

*Phasenlage: Phase 2. Die Kopplung an Freigabeweg und Versionsvergleich folgt in Phase 3.*

---

## Agenten-Prüfstand vor der Freigabe

*Ein Agent, den ein Fachbereich selbst angelegt hat, darf nicht allein deshalb organisationsweit laufen, weil er beim ersten Ausprobieren gut aussah.*

Zwischen Entwurf und Freigabe steht ein **automatisierter Prüflauf** — die technische Ergänzung zur fachlichen Prüfung durch Menschen, nicht ihr Ersatz. Er testet den Agenten gegen einen Katalog von Prüffällen und liefert einen **Prüfbericht als Teil der Freigabeunterlage**.

### Fünf Prüfkategorien

**1 · Sicherheit und Robustheit.** Hält der Agent Manipulationsversuchen stand? Geprüft wird insbesondere die **Prompt-Injection aus eingebetteten Dokumenten**: Ein Schriftsatz, ein Antrag oder eine hochgeladene Tabelle kann Anweisungen enthalten, die an den Agenten gerichtet sind, ohne dass ein Mensch sie so wahrnimmt. Weiter: Gibt er interne Anweisungen preis, versucht er Wissen außerhalb seiner Berechtigung zu ziehen, lässt er sich zur Umgehung seiner Grenzen überreden?

**2 · Fachliche Richtigkeit.** Antworten auf **Referenzfälle mit bekannter richtiger Lösung**. Das ist derselbe Gedanke wie bei der Messung der Retrieval-Qualität, nur eine Ebene höher — nicht „findet die Suche die richtige Stelle", sondern „zieht der Agent daraus den richtigen Schluss". Der Messrahmen ist in [search-quality-evaluation.md](./search-quality-evaluation.md) beschrieben.

**3 · Belegtreue.** Hält er den Zitierzwang durch? Sagt er **„nicht feststellbar"**, wo nichts belegt ist, statt eine plausible Antwort zu formulieren? Geprüft wird beides: das unbelegte Ausweichen und das falsche Zitat — eine Fundstelle, die die Aussage nicht trägt.

**4 · Grenz- und Befugnistreue.** Bleibt er innerhalb der Grenzen, die seine Aufgabenbeschreibung setzt? Gibt er ab, wo er abgeben soll? Löst er keine schreibende Aktion ohne Freigabe aus? Diese Kategorie ist der Grund, warum die Abschnitte **Befugnisse** und **Grenzen** verbindlich sind: Sie sind nicht Beiwerk, sondern die Prüfvorschrift.

**5 · Neutralität und Amtsangemessenheit.** Positioniert er sich zu politischen oder weltanschaulichen Fragen? Wird er diskriminierend? Verlässt er den amtlichen Ton? In der Verwaltung ist das **kein Reputationsthema, sondern das Neutralitätsgebot** — ein Prüfkriterium mit Rechtsbezug, kein Marketingversprechen. Die Bewertung, welche Ausgabe die Grenze verletzt, bleibt eine fachliche und wird nicht allein maschinell entschieden.

### Wie der Prüfstand betrieben wird

- **Regressionsprüfung vor jeder neuen Fassung.** Der Lauf wiederholt sich, bevor eine neue Fassung eines bereits freigegebenen Agenten veröffentlicht wird. Verschlechterungen fallen auf, bevor sie im Amt ankommen — nicht danach.
- **Prüffälle sind selbst Assets.** Fachbereiche pflegen eigene Prüffälle für ihre Domäne; sie werden versioniert, geteilt und freigegeben wie jedes andere Asset. Damit ist auch die naheliegende Frage beantwortet, wer die Prüffälle verantwortet: der Fachbereich, dem der Vorgang gehört.
- **Das Ergebnis ist Evidenz, keine Meinung.** Der Prüfbericht ist reproduzierbar — gleiche Fassung, gleiche Prüffälle, gleiches Ergebnis — und wandert ins Protokoll. Für Revision und Aufsicht bleibt nachvollziehbar, **welche Fassung womit geprüft wurde**.
- **Ein bestandener Prüflauf ist keine Freigabe.** Er ist eine Unterlage. Freigegeben wird von einer benannten Stelle, und die kann trotz grünem Bericht ablehnen.

**Was der Prüfstand nicht leistet:** Er misst gegen die Fälle, die jemand aufgeschrieben hat. Eine Lücke im Prüffallkatalog ist eine Lücke im Bericht, und ein grüner Lauf sagt nichts über einen Fall, den niemand bedacht hat. Deshalb ist der Katalog ein gepflegtes Asset und kein einmaliger Aufwand — jeder im Betrieb aufgefallene Fehler gehört als neuer Prüffall hinein.

*Phasenlage: Phase 2 als Teil der Agent-Governance; die Kopplung an den Freigabeweg in Phase 3.*

---

## Prüfagenten für kritische Vorgänge

*Der Zitierzwang schützt davor, dass Fakten erfunden werden. Er schützt nicht davor, dass aus richtigen Fundstellen ein falscher Schluss gezogen wird.*

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

**Vollständig protokolliert.** Wer geprüft hat, mit welchem Ergebnis und auf welcher Grundlage, steht revisionssicher im Protokoll — **einschließlich der Fälle, in denen die Prüfung angeschlagen hat**. Gerade diese sind der Nachweis, dass das Verfahren wirkt.

**Zwei Grenzen, die genannt gehören:** Prüfagenten sind gegen einen gemeinsamen Fehler blind — beruhen alle auf demselben Modell und derselben Wissensbindung, teilen sie dessen systematische Schwächen. Und sie verdoppeln die Kosten eines Vorgangs mindestens. Beides spricht nicht gegen das Verfahren, wohl aber gegen seinen flächendeckenden Einsatz.

*Phasenlage: Phase 2 als Teil der Agent-Governance; die Kopplung an Freigabeweg und Versionierung in Phase 3.*

---

## Werkzeuge

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

*Phasenlage: Phase 2.*

### Schreibende Aktionen mit menschlicher Freigabe

Lesen ist harmlos, Schreiben nicht. Schreibende Aktionen — einen Vorgang anlegen, einen Eintrag aktualisieren, eine Nachricht vorbereiten — werden **bewusst freigeschaltet** und laufen über ein Freigabetor:

- Der Agent **bereitet vor**; wirksam wird die Aktion durch eine menschliche Freigabe.
- Angezeigt wird, **was genau** geschehen soll, in welchem System und mit welchen Daten.
- Jede Aktion ist protokolliert, freigegebene wie abgelehnte.
- Welche Aktionen ein Agent überhaupt auslösen darf, steht in seinem Abschnitt **Befugnisse** und wird vom [Prüfstand](#agenten-prüfstand-vor-der-freigabe) gegengeprüft.

*Phasenlage: Phase 2.*

### MCP als standardisierte Anbindung

Werkzeuge und Fremdsysteme werden über das **Model Context Protocol (MCP)** angebunden — ein offener, selbst betreibbarer Standard. Das ist die Antwort auf die Alternative, Integrationen über fremde Automatisierungsdienste zu beziehen: Die widerspricht dem Betrieb im eigenen Haus, weil sie Vorgangsdaten über einen Dritten führt.

> **Offen und ausdrücklich hier nicht entschieden:** Wie sich MCP zur bestehenden Plugin-Architektur für Konnektoren verhält — ob es konkurrierende Wege sind oder ob sie sich ergänzen (Plugins für interne Konnektoren mit tiefem Zugriff auf die Indizierungspipeline, MCP für Werkzeuge und Fremdsysteme). Die Klärung läuft in **#349**; bis dahin trifft dieses Dokument dazu keine Festlegung.

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

## Integrationspunkte

| Bezug | Was dort geregelt ist |
|---|---|
| [spaces-and-assets.md](./spaces-and-assets.md) | Rechte an Agenten, Freigabekette für das Wissen, Verteilungsstufen, Katalog, Versionierung, Freigabeweg, Export und Import — **das Leitdokument** |
| [access-control.md](./access-control.md) | Identität, Gruppen, Systemverwaltung, Protokollierung |
| [data-indexing-rag.md](./data-indexing-rag.md) | Abfrageablauf, Quellenbindung und Zitierzwang, auf denen Belegtreue und Prüfagenten aufsetzen |
| [search-quality-evaluation.md](./search-quality-evaluation.md) | Messrahmen für Referenzfälle, den der Prüfstand auf Agentenebene weiterverwendet |
| [llm-integration.md](./llm-integration.md) | Modellwahl und zentrale Vorgaben als Obergrenze für die Modellwahl eines Agenten |
| [VISION.md](../VISION.md) · [USE-CASES.md](../USE-CASES.md) | Einordnung in die Themenbereiche und Anwendungsfälle im Alltag |

---

## Offene Fragen / Zukünftige Erweiterungen

- **Verhältnis von MCP und Plugin-Architektur** — offen, siehe #349. Dieses Dokument entscheidet es nicht.
- **Skills als eigene Objektart** oder als benannter Abschnitt innerhalb einer Aufgabenbeschreibung? Der Unterschied entscheidet, ob sie eigene Rechte und eine eigene Versionierung brauchen.
- **Wie streng die Struktur der Aufgabenbeschreibung erzwungen wird.** Ein Freitextfeld „Sonstiges" ist bequem und höhlt die Prüfbarkeit aus; ganz ohne Ausweichfeld wird die Struktur mancher Aufgabe nicht gerecht.
- **Wie ein Prüffallkatalog zu einem Agenten kommt, den niemand geprüft hat.** Ohne Mindestbestand ist ein grüner Prüfbericht wertlos; ob es eine erzwungene Mindestzahl gibt und wie sie bemessen wird, ist offen.
- **Welches Modell die Prüfagenten benutzen.** Dasselbe Modell teilt die Fehler des erzeugenden Agenten; ein anderes ist nicht immer verfügbar, insbesondere im Betrieb ohne Netzanbindung.
- **Umgang mit Zeitüberschreitungen und Abbrüchen in der Ausführungsumgebung** bei sehr großen Beständen — Teilergebnis oder Fehlschlag.
- **Ob ein Agent einen anderen Agenten aufrufen darf.** Naheliegend, aber ungeklärt sind Rechtekontext, Protokollierung und die Frage, wessen Prüfbericht dann gilt.

---

## Erfolgs-Metriken

Alle Angaben aggregiert je Organisationseinheit, ohne Personenbezug (siehe [Nutzungstransparenz](./spaces-and-assets.md#nutzungstransparenz)).

- **Wie viele Agenten Fachbereiche selbst anlegen** — die eigentliche Probe auf das Onboarding. Entstehen Agenten nur in der IT, hat das Verfahren sein Ziel verfehlt.
- **Anteil der Agenten mit vollständig ausgefüllten Abschnitten Befugnisse und Grenzen.**
- **Anteil der Freigaben mit vorliegendem Prüfbericht.**
- **Quote der im Prüfstand vor der Veröffentlichung abgefangenen Verschlechterungen** — sie zeigt, ob die Regressionsprüfung trägt.
- **Trefferquote der Prüfagenten**: Wie oft ihr Einspruch von der Sachbearbeitung bestätigt wird. Eine niedrige Quote bedeutet, dass das Verfahren Arbeit erzeugt statt sie zu ersparen.
