# ADR-0070: Werkzeugaufrufe im Chat

## Status

Akzeptiert · zuletzt geändert 2026-11-09

## Kontext

Der Chat ist einschüssig: eine vorgeschaltete Suche, ein Modellaufruf, eine belegte Antwort. Das ist
für eine Auskunft richtig und bleibt so. Eine Prüfung mit mehreren Anforderungen oder eine Recherche
lässt sich so nicht leisten. Dafür muss das Modell Werkzeuge aufrufen können, mehrfach, mit Grenzen,
sichtbar und nachvollziehbar. Epic #1747 baut diese Werkzeugschleife ohne schreibende Aktion; erstes
Werkzeug ist die eigene Suche. Ein Spike (`io.opaa.query.spike`, Schalter
`opaa.spike.tool-loop.enabled`) hat die Schleife hinter dem Präfix `@test` erprobt.

Auslöser der Schleife ist ein **Agent**. Das hat der Maintainer am 09.11.2026 entschieden. Ein Agent
ist ein Katalogeintrag mit Anweisung, zugeordneten Bibliotheken und Werkzeugen. Angelegt wird er mit
dem Anlegerecht `CREATE_AGENT`, gewählt im Chat per `@`. Ein installationsweiter Schalter gibt
Agenten frei, Vorgabe aus. Im ersten Schnitt gibt es die Werkzeuge `search_knowledge`,
`read_document`, `list_sources`, `run_code` (Sandbox, #3583) und `ask_user`. Ohne Agent bleibt der
Chat einschüssig. Wie das Agenten-Asset gebaut ist, regelt dieser ADR nicht (#3670).

Sechs Festlegungen laufen auseinander, wenn man sie einzeln trifft. Sie stehen deshalb vor der
ersten Zeile Code in einem ADR. Eine davon berührt eine Zusage an die Personalvertretung: die
Trennung der Bestände. Sie darf nicht als Nebenprodukt der Umsetzung entstehen
([ADR-0068](0068-mitbestimmungsfaehigkeit.md)).

## Entscheidung

### 1. Bordmittel statt Agenten-Framework

Die Schleife läuft mit den Mitteln von Spring AI (`ToolCallback`, `ToolContext`,
`ToolCallingManager`, `ToolCallingAdvisor`). Ein zusätzliches Agenten-Framework wird nicht
eingeführt, solange keine Mehrschritt-Planung mit eigenem Zustand über eine Anfrage hinaus gefordert
ist. Der Planschritt eines Agenten ist ein Modellaufruf in derselben Schleife, kein Planer.
Begründung: Ein Rahmenwerk ohne Bedarfsnachweis ist eine Bindung ohne Gegenwert. Spring AI ist
ohnehin im Stapel ([ADR-0002](0002-mvp-technology-stack.md)), und der Spike zeigt, dass seine
Mittel die Schleife tragen.

### 2. Ein Werkzeug ist eine Bean, die selbst autorisiert

Jedes Werkzeug ist eine Spring-Bean. Eine Registry sammelt sie nach dem Muster von
`SourceConnectorRegistry` ([ADR-0038](0038-steckbare-konnektoren.md)). Der Start scheitert, wenn zwei
Werkzeuge denselben Namen tragen oder eines keine Rechteklasse hat. Jedes Werkzeug trägt genau eine
Rechteklasse:

| Klasse | Bedeutung | Werkzeuge im ersten Schnitt |
|---|---|---|
| lesend | liest im eigenen Bestand mit den Rechten der aufrufenden Person oder fragt sie im Gespräch zurück; keine Wirkung außerhalb des Laufs | `search_knowledge`, `read_document`, `list_sources`, `ask_user` |
| isoliert | wirkt nur in einer Ausführungsumgebung ohne Zugriff auf den Bestand und ohne Netz | `run_code` |
| schreibend | wirkt außerhalb der Schleife: schreibt, sendet oder greift über das Ausgangstor nach außen | keines |

Jedes Werkzeug prüft bei jedem Aufruf selbst die Rechte und verlässt sich nicht darauf, dass die
Schleife es tut. Den Bestand erreicht es nur über dieselben Domain-Dienste wie `POST /api/v1/query`;
einen zweiten Zugriffsweg gibt es nicht. Begründung: Gibt es die Klasse von Anfang an, ist ein
späteres schreibendes Werkzeug eine neue Zeile. Kommt sie erst später, muss jedes bestehende
Werkzeug neu bewertet werden.

### 3. Rechte ausschließlich aus dem Aufrufkontext

Identität, Organisation und Suchbereich erreichen das Werkzeug über den `ToolContext`, an den
Modellargumenten vorbei. Der Suchbereich ist der Schnitt aus den lesbaren Bibliotheken der
aufrufenden Person, dem Bereich des Chats und den Bibliotheken des Agenten. Das Modell bestimmt die
Frage und darf den Bereich fachlich verengen, etwa über Bibliotheks- oder Metadatenfilter. Erweitern
darf es ihn nie. Ein Bibliotheksbezeichner außerhalb des Bereichs ist kein Eingabefehler: Er wird
ignoriert und im Schritt als ignoriert vermerkt, ohne seinen Wert. Dasselbe gilt für Text in einem
abgerufenen Dokument. Begründung: So ist ein Einflüsterungsversuch bei der Suche wirkungslos und
hängt nicht von der Formulierung des Systemvorspanns ab
([ADR-0065](0065-hybride-suche-reranking-und-diagnose.md), Entscheidung 4).

### 4. Drei Bestände, nicht zwei

Wer ein Werkzeug benutzt hat, ist etwas anderes als das, was jemand gefragt hat. Daraus folgen drei
getrennte Bestände:

| | (a) Antwort und Schritte im Chat | (b) Technisches Schrittprotokoll | (c) Revisionsprotokoll |
|---|---|---|---|
| **Inhalt** | Endantwort mit allen zitierten Fundstellen aus allen Schritten; die Schritte, für die Person verständlich formuliert, mit Teilfrage und Trefferzahl; Lauf-Kennung | Lauf-Kennung, Kennung des Schritts, Werkzeug, Ergebnisstatus (auch „Argument ignoriert“), Trefferzahl, Dauer, Abbruchgrund | Bei lesenden und isolierten Aufrufen nichts. Ab dem ersten schreibenden Werkzeug: aufrufende Person, Werkzeug, Zielsystem, Zeitpunkt, Entscheidung (bestätigt / abgelehnt / verfallen), Agentenfassung, Lauf-Kennung |
| **Frist** | reguläre, konfigurierbare Aufbewahrung des Chats; nach dem Lauf nachschlagbar | eigene Frist, Vorgabe 7 Tage, Höchstwert 30 | allgemeine Protokollfrist ([ADR-0059](0059-ereignisvertrag-aufbewahrung-und-integritaet-des-revisionsprotokolls.md), Entscheidung 2) |
| **Löschung** | mit dem Chat: durch die Person, die Bereinigung des Space oder die Sperre des Kontos ([ADR-0066](0066-chatliste-ablage-suche-und-bereinigung.md)) | automatischer Löschlauf, der abgelaufene Partitionen entfernt; nicht abschaltbar | Entfernen abgelaufener Monatspartitionen ([ADR-0015](0015-eigentuemertrennung-protokollablage.md)) |
| **Wer hineinsieht** | die Person selbst im Gesprächsverlauf | die Systemverwaltung zur Störungssuche, nur mit genau einer Lauf-Kennung; kein Lesepfad für `AUDITOR` | `AUDITOR` über die bestehenden Wege der Revision |

**Zu (b).** Das Schrittprotokoll ist **nicht** das Diagnoseprotokoll der Suche. Es nutzt nur dessen
Ablage-Infrastruktur mit (`diagnostic_context_log`): eine eigene Tabelle derselben Bauart im Besitz
von `opaa_audit_owner`, die das Anwendungskonto nur anfügen und lesen kann. Entfernt wird nur über
eine Löschfunktion für abgelaufene Partitionen. Die Partitionen sind nach Tagen geschnitten, weil
eine Frist in Tagen mit Monatspartitionen nicht einzuhalten ist. Was das Diagnoseprotokoll sonst
trägt, gilt für (b) ausdrücklich nicht: die Frist von zwölf Monaten, die Einzelsatzansicht und der
Lesepfad der Rolle `AUDITOR` (`/api/v1/audit/diagnostic-context-events`). (b) hat keinen Konto-Bezug
und keinen Suchtext. Die formulierte Teilfrage wird nicht persistiert, weder im Klartext noch
abgeleitet. Für die Störungssuche genügt die Kennung des Schritts, weil der Chat die Teilfrage in (a)
trägt. Schrittinhalte stehen auch nicht im Anwendungslog. Braucht die Umsetzung ein weiteres Feld,
wird es feldweise begründet und erscheint im Auszug für die Personalvertretung. Es gibt keine
Oberfläche und keine Schnittstelle, die (b) nach Person filtert, gruppiert oder sortiert. Das ist
nicht abschaltbar, sondern nicht gebaut, und eine automatisierte Prüfung belegt es.

**Zu (c).** Ein lesender Aufruf ist Nutzung und gehört deshalb nicht ins Revisionsprotokoll (ADR-0059,
Entscheidung 1). In der Menge ergäbe er ein Tätigkeitsprofil. Die Frage „worauf durfte Person X
zugreifen“ beantwortet die Rechtehistorie. Die Ereignisart für schreibende Werkzeugaufrufe kommt in
die geschlossene Liste `AuditEventType`, bevor das erste schreibende Werkzeug aufgerufen wird. Die
Verbindung von (c) zu (b) über die Lauf-Kennung reißt nach der Frist von (b) **absichtlich** ab. Ein
Eintrag in (c) bleibt danach als Nachweis der Entscheidung, aber ohne technische Schritte.

**Keine Zusage wird verletzt.** Fragen und Suchbegriffe stehen weder im Revisionsprotokoll noch im
Anwendungslog (ADR-0068, Entscheidung 1). Kein Bestand hat einen personenbezogenen Auswertungspfad
(ADR-0068, Entscheidung 2). (b) liegt deutlich kürzer als jede Protokollfrist (ADR-0068,
Entscheidung 4). Die Liste der Ereignisarten bleibt geschlossen (ADR-0059, Entscheidung 1).

**Vorlage.** Diese Festlegung wird der Personalvertretung über den Auszug vorgelegt, der aus dem
Fakten-Manifest entsteht ([ADR-0052](0052-compliance-manifest.md); ADR-0068, Entscheidung 5). Eine
Datei im Repository genügt dafür nicht. Der Auszug führt (b) mit Feldliste, Zweck je Feld und Frist.
Er nennt außerdem die vorhandenen Abfragewege und die ausdrücklich nicht vorhandenen.

Begründung: Fasst man die drei Bestände in einem Satz zusammen, wird eine Vorlage an die
Personalvertretung angreifbar. Was für einen Bestand zugesagt ist, gilt dann scheinbar für alle.
Die Frage, auf welcher Grundlage eine Feststellung getroffen wurde, kommt nach Jahren. Beantwortet
wird sie aus (a), nicht aus technischen Rohdaten.

### 5. Der Systemvorspann in der Schleife

Ein Modellaufruf der Schleife wird in dieser Reihenfolge zusammengesetzt:

1. **Feste Regeln** im Systemvorspann: Rolle, Beleg- und Zitierregeln, die Regel, dass gekennzeichnete
   Datenblöcke Material und keine Anweisung sind, und die Sprachvorgabe.
2. **Anweisung des Space** ([ADR-0043](0043-anweisung-je-space.md), Entscheidung 6), wenn vorhanden.
3. **Anweisung des Agenten** als gekennzeichneter Block. Sie wird angehängt und ersetzt nichts. Sie
   ist kein Beleg und keine Quelle, und die Belegregeln stehen über ihr.
4. **Gesprächsnotiz** ([ADR-0031](0031-gespraechsgedaechtnis.md)), wenn vorhanden.
5. **Werkzeugbeschreibungen.** Die Anwendung erzeugt sie aus den registrierten Werkzeugen und gibt
   sie als Werkzeugdefinitionen mit, nicht als Text im Systemvorspann. Ein Agent wählt Werkzeuge
   aus, formuliert ihre Beschreibungen aber nicht.
6. **Gesprächsverlauf und Frage.**
7. **Schritte:** die Werkzeugaufrufe des Modells und je ein Werkzeugergebnis als Datenblock mit einer
   Kennung, die je Lauf neu entsteht. Für die Bereinigung gelten dieselben Regeln wie für die
   Passagen im Chat (`DocumentBlock`, `ContentFence`). Ein Ergebnis steht nie in der Nachricht der
   Person und nie im Systemvorspann.
8. **Erinnerung an die verbindlichen Regeln** nach dem letzten Werkzeugergebnis und außerhalb seines
   Blocks. Das ist dasselbe Muster wie die Sprachvorgabe hinter den Passagen
   (`AnswerGenerationService.LANGUAGE_REMINDER`).

Teile 1 bis 4 sind über alle Schritte eines Laufs zeichengleich. Ein Schritt hängt nur hinter Teil 7
an. Darauf beruht die Zusage, dass der Systemvorspann nicht über den Chat änderbar ist, auch wenn
fremd verfasster Text in die Schleife gelangt. Begründung: Ein Werkzeugergebnis an anderer Stelle
spräche mit der Stimme der Person oder des Systems. Eine Agenten-Anweisung, die den Vorspann ersetzt,
machte jeden Agenten zum Hebel gegen die Belegregeln.

### 6. Erzwungen oder gemessen

| Eigenschaft | Art | Woran sie hängt |
|---|---|---|
| Suchbereich und Rechtefilter | erzwungen | Aufrufkontext (Entscheidung 3); kein Modellargument und kein Dokumenttext ändert ihn |
| Grenzen eines Laufs (Aufrufe, Laufzeit, gesammelte Menge) und Platzlimit gleichzeitiger Läufe | erzwungen | Die Schleife zählt und bricht ab; das Modell wird nicht gebeten, sich zu mäßigen |
| Schalter für Agenten und je Werkzeug | erzwungen | wirkt beim nächsten Aufruf, unabhängig vom Modell |
| Bestätigung vor einem Aufruf mit Wirkung, wenn Fremdinhalt im Kontext steht | erzwungen | Rechteklasse des Werkzeugs (Entscheidung 2), durchgesetzt an der Ausführung, nicht daran, dass das Modell fragt |
| Belegprüfung | erzwungen | läuft nach der Erzeugung gegen die Passagen aller Schritte |
| Anweisungstreue gegenüber Werkzeugergebnissen | gemessen | Die Reihenfolge aus Entscheidung 5 senkt die Trefferwahrscheinlichkeit einer eingebetteten Anweisung, schließt sie aber nicht aus |
| Grenz- und Befugnistreue eines Agenten | gemessen | nur an aufgeschriebenen Fällen |
| Belegtreue der Formulierung („nicht feststellbar“, wo nichts belegt ist) | gemessen | Modellverhalten; die Belegprüfung fängt das falsche Zitat, nicht die vorschnelle Aussage |
| Zuverlässigkeit des Werkzeugaufrufs | gemessen | Kombination aus Modell, Laufzeit und Konfiguration |

Eine gemessene Eigenschaft wird mit einer **Zahl** berichtet, nie mit einem Häkchen: an dieser
Fallbasis, mit dieser Kombination, so oft bestanden. **Keine schreibende Wirkung hängt an einer
gemessenen Eigenschaft.** Dass ein Modell eine eingebettete Anweisung nicht befolgt, darf nie der
Grund sein, warum nichts Falsches geschrieben wurde.

Am Modelleintrag ist „gemessen“ von „behauptet“ unterscheidbar. Die Werkzeugfähigkeit führt eine
**Grundlage**: *gemessen*, mit Datum und Verweis auf den Messlauf, oder *manuell gesetzt*. Eine
prüfende Stelle, Person oder Gruppe, führt sie nicht. Aussagekräftig ist, wie geprüft wurde, nicht
wer; wer die Angabe geändert hat, gehört ins Protokoll der Verwaltungsaktion.

Für spätere schreibende Werkzeuge ist die **nutzergesteuerte Ausführung** als Bauform vorgesehen. Die
Anwendung treibt die Schleife dann selbst: Sie nimmt die Aufrufwünsche des Modells entgegen und führt
sie über `ToolCallingManager` aus, statt sie dem `ToolCallingAdvisor` zu überlassen. Nur so kann sie
vor einem Aufruf anhalten. Dieser ADR legt das fest, gebaut wird es mit dem ersten schreibenden
Werkzeug. Begründung: Wer eine gemessene Eigenschaft wie eine erzwungene führt, schwächt die
erzwungenen und stärkt die gemessenen nicht. Im Prüfgespräch bricht die Aussage dann genau an der
Frage „woran haben Sie das festgestellt“.

## Konsequenzen

- **Einfacher:** Neue lesende Werkzeuge sind eine Bean mit Rechteklasse. Der gewöhnliche Chat bleibt
  unverändert, ohne Werkzeugbeschreibungen und ohne zusätzlichen Modellaufruf. Die Personalvertretung
  bekommt drei Bestände mit je eigener Antwort statt einer Mischformel.
- **Schwieriger:** Das Schrittprotokoll braucht eine eigene Tabelle mit Tagespartitionen und einen
  eigenen Löschlauf neben dem Diagnoseprotokoll. Jedes weitere Feld in (b) braucht eine Begründung
  und eine Änderung des Auszugs. Ein Agent kann den Systemvorspann nicht ersetzen. Wer das will,
  bekommt einen angehängten Block.
- **Restrisiko:** Die Lauf-Kennung steht in (a) an der Chat-Nachricht. Wer direkt in der Datenbank
  liest, kann (b) über (a) einer Person zuordnen, solange beide bestehen. Das ist ein Zugriff des
  Betriebs, kein Weg der Anwendung. Die Anweisungstreue bleibt gemessen. Deshalb hängt nichts
  Schreibendes daran.

## Verworfene Alternativen

- Agenten-Framework (etwa LangChain4j oder ein Graph-Orchestrator) neben Spring AI: zweites
  Abstraktionsmodell ohne Bedarf an Mehrschritt-Planung (1).
- Rechteprüfung zentral in der Schleife statt im Werkzeug: Ein später ergänztes Werkzeug ohne
  Schleifenprüfung wäre offen (2).
- Rechteklasse erst mit dem ersten schreibenden Werkzeug: Dann muss jedes bestehende Werkzeug neu
  bewertet werden (2).
- Suchbereich als Modellargument mit Prüfung gegen die Rechte: Das Modell würde zum Träger der
  Rechte, und jeder Prüffehler wäre ein Zugriffsfehler (3).
- Fremder Bibliotheksbezeichner als Eingabefehler mit Abbruch: Eine eingebettete Anweisung könnte
  damit jeden Lauf gezielt scheitern lassen (3).
- Schrittprotokoll im Diagnoseprotokoll der Suche mit dessen Frist und Lesepfad: zwölf Monate,
  Einzelsatzansicht und Auswertungsrolle widersprechen der kurzen, personenfreien Fassung (4).
- Eigene Ablage neuer Bauart für das Schrittprotokoll: Sie würde beim nächsten Löschauftrag oder
  Rechteumbau vergessen (4).
- Lesende Werkzeugaufrufe im Revisionsprotokoll: Sie ergäben das Tätigkeitsprofil, das ADR-0068
  ausschließt (4).
- Werkzeugergebnisse im Systemvorspann oder in der Nachricht der Person: Eine eingebettete Anweisung
  spräche dort mit Systemrang oder als Wort der Person (5).
- Agenten-Anweisung, die den Systemvorspann ersetzt: Belegregeln und Datenkennzeichnung hingen am
  Wortlaut jedes Agenten (5).
- Werkzeugfähigkeit als Ja/Nein mit prüfender Stelle: „nie gesetzt“ und „geprüft, kann es nicht“
  wären ununterscheidbar, und der Name sagt nichts über die Prüfung (6).
- Freigabe schreibender Aufrufe, weil das Modell vorher fragt: Das hinge an einer gemessenen
  Eigenschaft (6).

## Offen

- Höhe der Grenzen und des Platzlimits, kalibriert an realistischen Fällen vor der ersten
  Vorführung.
- Wie lange ein Lauf dauern darf, bevor ein Laufbegriff mit Zwischenstand nötig wird.
- Feldliste der Ereignisart für schreibende Werkzeugaufrufe, mit dem ersten schreibenden Werkzeug.
