# ADR-0070: Werkzeugaufrufe im Chat

## Status

Akzeptiert · zuletzt geändert 2026-11-10

## Kontext

Der Chat ist einschüssig: eine vorgeschaltete Suche, ein Modellaufruf, eine belegte Antwort. Das ist
für eine Auskunft richtig und bleibt so. Eine Prüfung mit mehreren Anforderungen oder eine Recherche
lässt sich so nicht leisten. Dafür muss das Modell Werkzeuge aufrufen können, mehrfach, mit Grenzen,
sichtbar und nachvollziehbar. Epic #1747 baut diese Werkzeugschleife ohne schreibende Aktion. Das
erste Werkzeug ist die eigene Suche.

Die Schleife wird von einem **Agenten** ausgelöst. Ein Agent ist ein Katalogeintrag mit Anweisung,
zugeordneten Bibliotheken und Werkzeugen. Angelegt wird er mit dem Anlegerecht `CREATE_AGENT`,
gewählt im Chat per `@`. Ein installationsweiter Schalter gibt Agenten frei, Vorgabe aus. Im ersten
Schnitt gibt es die Werkzeuge `search_knowledge`, `read_document`, `list_sources`, `run_code`
(Sandbox, #3583) und `ask_user`. Ohne Agent bleibt der Chat einschüssig. Wie das Agenten-Asset
gebaut ist, regelt dieser ADR nicht (#3670).

Sechs Festlegungen laufen auseinander, wenn man sie einzeln trifft. Sie stehen deshalb vor der
ersten Zeile Code in einem ADR. Eine davon berührt eine Zusage an die Personalvertretung: die
Trennung der Bestände. Sie darf nicht als Nebenprodukt der Umsetzung entstehen
([ADR-0068](0068-mitbestimmungsfaehigkeit.md)). OPAA läuft mit einer oder mehreren Instanzen
([ADR-0053](0053-mehrinstanzbetrieb-aktiv-aktiv.md)).

## Entscheidung

### 1. Bordmittel statt Agenten-Framework, ein Lauf je Anfrage

Die Schleife läuft mit den Mitteln von Spring AI (`ToolCallback`, `ToolContext`,
`ToolCallingManager`, `ToolCallingAdvisor`). Ein zusätzliches Agenten-Framework kommt erst, wenn
Mehrschritt-Planung mit Zustand über eine Anfrage hinaus gefordert ist.

Ein Lauf lebt genau eine Anfrage lang. `ask_user` und die Bestätigung des Planschritts **beenden**
den Lauf: Die Rückfrage oder der Plan wird Teil der Antwort in (a) (Entscheidung 4). Die Antwort der
Person startet einen neuen Lauf mit eigener Lauf-Kennung und eigenen Grenzen. Kein Lauf wartet
geparkt in einer Instanz.

Begründung: Ein Rahmenwerk ohne Bedarfsnachweis ist eine Bindung ohne Gegenwert, und Spring AI ist
ohnehin im Stapel ([ADR-0002](0002-mvp-technology-stack.md)). Ein wartender Lauf im Speicher ginge
bei jedem Pod-Tausch verloren (ADR-0053, Entscheidung 10). Ein Lauf ohne Wartezustand braucht weder
Laufbegriff noch Wiederaufnahme.

### 2. Ein Werkzeug ist eine Bean, die selbst autorisiert

Jedes Werkzeug ist eine Spring-Bean. Eine Registry sammelt sie nach dem Muster von
`SourceConnectorRegistry` ([ADR-0038](0038-steckbare-konnektoren.md)). Der Start scheitert, wenn zwei
Werkzeuge denselben Namen tragen oder eines keine Rechteklasse hat.

| Klasse | Bedeutung | Erster Schnitt |
|---|---|---|
| lesend | liest im eigenen Bestand mit den Rechten der aufrufenden Person oder fragt sie im Gespräch zurück | `search_knowledge`, `read_document`, `list_sources`, `ask_user` |
| isoliert | läuft in einer Ausführungsumgebung ohne Zugriff auf den Bestand und ohne Netz; die Eingaben gibt die Anwendung hinein, erzeugte Dateien werden nur Anhang der Antwort und damit Teil von (a) | `run_code` |
| schreibend | wirkt außerhalb des Laufs: schreibt, sendet oder greift über das Ausgangstor nach außen | keines |

Jedes Werkzeug prüft bei jedem Aufruf selbst die Rechte und verlässt sich nicht auf die Schleife.
Den Bestand erreicht es nur über dieselben Domain-Dienste wie `POST /api/v1/query`; einen zweiten
Zugriffsweg gibt es nicht. Begründung: Gibt es die Klasse von Anfang an, ist ein späteres
schreibendes Werkzeug eine neue Zeile. Kommt sie erst später, muss jedes bestehende Werkzeug neu
bewertet werden.

### 3. Rechte ausschließlich aus dem Aufrufkontext

Identität, Organisation und Suchbereich erreichen das Werkzeug über den `ToolContext`, an den
Modellargumenten vorbei. Der Suchbereich ist der Schnitt aus den lesbaren Bibliotheken der
aufrufenden Person, dem Bereich des Chats und den Bibliotheken des Agenten. Das Modell bestimmt die
Frage und darf den Bereich verengen, etwa über Bibliotheks- oder Metadatenfilter. Erweitern darf es
ihn nie. Ein Bibliotheksbezeichner außerhalb des Bereichs ist kein Eingabefehler: Er wird ignoriert
und im Schritt als ignoriert vermerkt, ohne seinen Wert. Ein Bezeichner, den ein abgerufenes
Dokument dem Modell eingibt, wird genauso behandelt; Dokumenttext erreicht den Suchbereich auf
keinem anderen Weg. Begründung: So ist ein Einflüsterungsversuch bei der Suche wirkungslos und hängt
nicht an der Formulierung des Systemvorspanns
([ADR-0065](0065-hybride-suche-reranking-und-diagnose.md), Entscheidung 4).

### 4. Drei Bestände, nicht zwei

| | (a) Antwort und Schritte im Chat | (b) Technisches Schrittprotokoll | (c) Revisionsprotokoll |
|---|---|---|---|
| **Inhalt** | Endantwort mit allen zitierten Fundstellen aller Schritte; die Schritte verständlich formuliert, mit Teilfrage und Trefferzahl; Rückfrage oder Plan; erzeugte Dateien; Lauf-Kennung | Lauf-Kennung, Kennung des Schritts, Zeitpunkt nach Datenbankuhr (für Frist und Partition), Organisationskennung (Mandantengrenze des Lesewegs), Werkzeug, Ergebnisstatus (auch „Argument ignoriert“), Trefferzahl, Dauer, Abbruchgrund | jeder Zugriff auf (b); bei lesenden und isolierten Werkzeugaufrufen sonst nichts; ab dem ersten schreibenden Werkzeug dessen Aufrufe (Feldliste unter Offen) |
| **Frist** | Aufbewahrung des Chats | eigene Frist, Vorgabe 7 Tage, Höchstwert 30 | allgemeine Protokollfrist ([ADR-0059](0059-ereignisvertrag-aufbewahrung-und-integritaet-des-revisionsprotokolls.md), Entscheidung 2) |
| **Löschung** | mit dem Chat: durch die Person, die Bereinigung des Space oder die Sperre des Kontos ([ADR-0066](0066-chatliste-ablage-suche-und-bereinigung.md), Entscheidungen 7 und 8) | Löschlauf, der abgelaufene Tagespartitionen entfernt; nicht abschaltbar | Entfernen abgelaufener Monatspartitionen ([ADR-0015](0015-eigentuemertrennung-protokollablage.md)) |
| **Wer hineinsieht** | die Person selbst im Gesprächsverlauf | die Systemverwaltung der eigenen Organisation zur Störungssuche, nur mit genau einer Lauf-Kennung und einem Anlass; kein Lesepfad für `AUDITOR` | `AUDITOR` über die bestehenden Wege der Revision |

**Zu (b).** Das Schrittprotokoll ist **nicht** das Diagnoseprotokoll der Suche
(`diagnostic_context_log`). Es nutzt nur dessen Bauart mit: eigene Tabelle im Besitz von
`opaa_audit_owner`, das Anwendungskonto fügt an und liest, entfernt wird nur über eine Funktion für
abgelaufene Partitionen. Die Partitionen sind nach Tagen geschnitten, weil eine Frist in Tagen mit
Monatspartitionen nicht einzuhalten ist. Was das Diagnoseprotokoll sonst trägt, gilt für (b)
ausdrücklich nicht: zwölf Monate Frist, Einzelsatzansicht und der Lesepfad der Rolle `AUDITOR`.
(b) hat keinen Konto-Bezug und keinen Suchtext. Die formulierte Teilfrage wird nicht persistiert,
weder im Klartext noch abgeleitet; für die Störungssuche genügt die Kennung des Schritts, solange
der Chat die Teilfrage in (a) trägt. Schrittinhalte stehen nicht im Anwendungslog. Ein weiteres Feld
wird feldweise begründet und erscheint im Auszug für die Personalvertretung. Es gibt keine
Oberfläche und keine Schnittstelle, die (b) nach Person filtert, gruppiert oder sortiert. Das ist
nicht abschaltbar, sondern nicht gebaut, und eine automatisierte Prüfung belegt es.

**Zugriff auf (b).** Jeder Lesezugriff schreibt `AUDIT_LOG_ACCESSED` mit eigenem `accessPath`, der
Lauf-Kennung und dem Anlass als Pflichtangabe. Ein abgewiesener Zugriff wird als `DENIED`, ein
gescheiterter als `FAILURE` erfasst, wie bei `DiagnosticContextLogQueryService`. Eine Lauf-Kennung
einer fremden Organisation ist von einer unbekannten nicht zu unterscheiden. Der Leseweg ist genau
eine Operation (`GET /api/v1/admin/agent-runs/{runId}/log`); die Frist stellt die Systemverwaltung
unter „Datenschutz“ ein. Die Schritte eines Laufs schreibt die Schleife gesammelt an seinem Ende; ein
Schreibfehler verwirft nie die Antwort.

**Zu (c).** Ein lesender Aufruf ist Nutzung und gehört nicht ins Revisionsprotokoll. In der Menge
ergäbe er ein Tätigkeitsprofil; die Frage „worauf durfte Person X zugreifen“ beantwortet die
Rechtehistorie. Die Ereignisart für schreibende Aufrufe kommt in die geschlossene Liste
`AuditEventType`, bevor das erste schreibende Werkzeug aufgerufen wird. Die Verbindung von (c) zu
(b) über die Lauf-Kennung reißt nach der Frist von (b) **absichtlich** ab.

| Zusage | Stand |
|---|---|
| Fragen und Suchbegriffe stehen weder im Revisionsprotokoll noch im Anwendungslog (ADR-0068, Entscheidung 1) | gehalten: Teilfragen nur in (a), nie in (b), (c) oder im Log |
| Nutzung steht nicht im Revisionsprotokoll, Zugriffe auf Protokolldaten schon (ADR-0059, Entscheidung 1) | gehalten: lesende Aufrufe nicht in (c), jeder Zugriff auf (b) schon |
| Kein personenbezogener Auswertungspfad (ADR-0068, Entscheidung 2) | gehalten: (b) ohne Konto-Bezug, Zugriff nur über eine Lauf-Kennung |
| Begrenzte Aufbewahrung (ADR-0068, Entscheidung 4) | gehalten: (b) höchstens 30 Tage |
| Getrennte Zugriffswege von Revision und Verwaltung (ADR-0068, Entscheidung 5) | gehalten, mit neuer Lage: Die Verwaltung liest erstmals ein Protokoll, das die Revision nicht liest. (b) enthält nur technische Zustände ohne Konto-Bezug; die Revision sieht in (c) jeden dieser Zugriffe |
| Geschlossene Liste der Ereignisarten (ADR-0059, Entscheidung 1) | gehalten: neue Art vor dem ersten schreibenden Aufruf |

Diese Festlegung wird der Personalvertretung über den Auszug vorgelegt, der aus dem Fakten-Manifest
entsteht ([ADR-0052](0052-compliance-manifest.md)), nicht über eine Datei im Repository. Der Auszug
führt (b) mit Feldliste, Zweck je Feld, Frist sowie den vorhandenen und den ausdrücklich nicht
vorhandenen Abfragewegen.

Begründung: In einem Satz zusammengefasst, gälte eine Zusage für einen Bestand scheinbar für alle.
Die Frage, auf welcher Grundlage eine Feststellung getroffen wurde, wird aus (a) beantwortet, solange
der Chat besteht, nicht aus technischen Rohdaten.

### 5. Der Systemvorspann in der Schleife

Ein Modellaufruf der Schleife wird in dieser Reihenfolge zusammengesetzt:

1. **Feste Regeln** im Systemvorspann: Rolle, Beleg- und Zitierregeln, die Regel, dass gekennzeichnete
   Datenblöcke Material und keine Anweisung sind, und die Sprachvorgabe.
2. **Anweisung des Agenten** als gekennzeichneter Block, angehängt, nie als Ersatz. Sie darf Ton,
   Form und Vorgehen bestimmen, nicht die Antwortsprache. Sie ist kein Beleg und keine Quelle.
3. **Anweisung des Space** ([ADR-0043](0043-anweisung-je-space.md), Entscheidung 6), wenn vorhanden.
   Sie geht der Agenten-Anweisung vor und steht deshalb hinter ihr.
4. **Gesprächsnotiz** ([ADR-0031](0031-gespraechsgedaechtnis.md)), wenn vorhanden.
5. **Werkzeugbeschreibungen**, von der Anwendung aus den registrierten Werkzeugen erzeugt. Ein Agent
   wählt Werkzeuge aus, formuliert ihre Beschreibungen aber nicht. Auf API-Ebene sind sie
   Werkzeugdefinitionen, kein Text im Systemvorspann; das Template des Modellservers rendert sie
   oft trotzdem in den Systemteil.
6. **Gesprächsverlauf und Frage.**
7. **Schritte:** die Werkzeugaufrufe des Modells und je ein Werkzeugergebnis als Datenblock mit einer
   Kennung, die je Lauf neu entsteht, bereinigt wie die Passagen im Chat (`DocumentBlock`,
   `ContentFence`). Ein Ergebnis steht nie in der Nachricht der Person und nie im Systemvorspann.
8. **Erinnerung an die verbindlichen Regeln** am Ende jedes Werkzeugergebnisses, hinter dessen
   Datenblock und außerhalb davon. Damit ist vor jedem Modellaufruf mit Fremdinhalt die Erinnerung
   das Letzte, was das Modell liest. Sie besteht wie im Chat aus Zitier- und Sprachzeile
   (`AnswerGenerationService.CITATION_REMINDER`, dazu `LANGUAGE_REMINDER` oder mit einer
   Space-Anweisung `SPACE_LANGUAGE_REMINDER`).

Die Teile 1 bis 4 sind über alle Schritte eines Laufs zeichengleich. Ein Schritt hängt nur ein
weiteres Ergebnis mit eigener Erinnerung hinter Teil 7 an. Darauf beruht die Zusage, dass der
Systemvorspann nicht über den Chat änderbar ist, auch wenn fremd verfasster Text in die Schleife
gelangt. Begründung: Eine Erinnerung als Nachricht der Person spräche mit deren Stimme. Eine
Systemnachricht mitten im Verlauf lehnen manche Chat-Templates über den OpenAI-kompatiblen Weg ab
([ADR-0067](0067-modellanbindung-und-verwaltete-chat-modelle.md), Entscheidung 1). Das
Werkzeugergebnis erzeugt die Anwendung ohnehin selbst.

### 6. Erzwungen oder gemessen, und was dafür im Betrieb gilt

| Eigenschaft | Art | Woran sie hängt |
|---|---|---|
| Suchbereich und Rechtefilter | erzwungen | Aufrufkontext (Entscheidung 3) |
| Grenzen eines Laufs (Aufrufe, Laufzeit, gesammelte Menge) und Platzlimit | erzwungen | Die Schleife zählt und bricht ab, das Modell wird nicht gebeten |
| Schalter für Agenten und je Werkzeug | erzwungen | wirkt beim nächsten Aufruf, unabhängig vom Modell |
| Bestätigung vor einem Aufruf mit Wirkung, wenn Fremdinhalt im Kontext steht | erzwungen | Rechteklasse (Entscheidung 2), durchgesetzt an der Ausführung |
| Belegprüfung | erzwungen | läuft nach der Erzeugung gegen die Passagen aller Schritte |
| Anweisungstreue gegenüber Werkzeugergebnissen | gemessen | Entscheidung 5 senkt die Trefferwahrscheinlichkeit, schließt sie nicht aus |
| Grenz- und Befugnistreue eines Agenten | gemessen | nur an aufgeschriebenen Fällen |
| Belegtreue der Formulierung | gemessen | Die Belegprüfung fängt das falsche Zitat, nicht die vorschnelle Aussage |
| Zuverlässigkeit des Werkzeugaufrufs | gemessen | Kombination aus Modell, Laufzeit und Konfiguration |

Eine gemessene Eigenschaft wird mit einer **Zahl** berichtet, nie mit einem Häkchen: an dieser
Fallbasis, mit dieser Kombination, so oft bestanden. **Keine schreibende Wirkung hängt an einer
gemessenen Eigenschaft.** Dass ein Modell eine eingebettete Anweisung nicht befolgt, darf nie der
Grund sein, warum nichts Falsches geschrieben wurde.

Die Werkzeugfähigkeit am Modelleintrag führt eine **Grundlage**: *gemessen*, mit Datum und Verweis
auf den Messlauf, oder *manuell gesetzt*. Eine prüfende Person oder Gruppe führt sie nicht. Wer die
Angabe geändert hat, steht im Protokoll der Verwaltungsaktion.

Für die Grundlage *gemessen* gilt eine **Untergrenze** als notwendige, nicht hinreichende
Bedingung: Jeder Lauf der Klassen `calls_when_needed`, `valid_arguments` und `respects_limit` hat
bestanden; ein nicht erreichter, abgeschnittener oder fehlerhafter Lauf zählt als nicht bestanden.
Der Messbericht stellt nur fest, ob die Untergrenze erfüllt ist. Die Angabe am Modelleintrag setzt
er nie selbst, sie setzt immer eine Person.

Die erzwungenen Eigenschaften halten auf jeder Instanz (ADR-0053):

- **Platzlimit:** eine Reservierung je laufendem Lauf in der Datenbank, mit Ablauf nach Datenbankuhr
  in Höhe der Laufzeitgrenze plus der Zeit für den abschließenden Antwortaufruf. In einer
  Transaktion sperrt die Anwendung die feste Zählerzeile des Platzlimits (`SELECT … FOR UPDATE`),
  zählt die nicht abgelaufenen Reservierungen und fügt die neue ein. Ohne diese Sperre überbuchten
  zwei gleichzeitige Anfragen unter `READ COMMITTED`. Ist das Limit erreicht, wird die Anfrage vor
  dem Lauf abgewiesen. Anders als die Deckel aus ADR-0053, Entscheidung 12, schließt das Platzlimit
  bei nicht erreichbarer Datenbank: Ohne Datenbank gibt es weder Suche noch Schrittprotokoll, ein
  Lauf könnte also ohnehin nicht korrekt laufen.
- **Schalter:** stehen in der Datenbank; jede Instanz hält sie am Invalidierungsbus (ADR-0053,
  Entscheidung 5).
- **Nutzergesteuerte Ausführung** für spätere schreibende Werkzeuge: Die Anwendung nimmt die
  Aufrufwünsche des Modells entgegen und führt sie über `ToolCallingManager` aus, statt sie dem
  `ToolCallingAdvisor` zu überlassen. Nur so kann sie vor einem Aufruf anhalten. Die wartende
  Entscheidung liegt in der Datenbank (ADR-0053, Entscheidung 10), nie in einer Instanz. Gebaut wird
  das mit dem ersten schreibenden Werkzeug.

Begründung: Wer eine gemessene Eigenschaft wie eine erzwungene führt, schwächt die erzwungenen und
stärkt die gemessenen nicht. Das Platzlimit schützt den Modellendpunkt der ganzen Installation; die
absolute Zahl ist die Zusage, und ein durch N geteilter Wert wiese bei ungleicher Verteilung ab,
während andere Instanzen frei sind. Die Reservierung mit Ablauf gibt den Platz auch nach dem Absturz
einer Instanz zurück, und ein Lauf ist selten genug, dass eine Zeile je Lauf nicht ins Gewicht fällt.

## Konsequenzen

- **Einfacher:** Neue lesende Werkzeuge sind eine Bean mit Rechteklasse. Der gewöhnliche Chat bleibt
  unverändert. Die Personalvertretung bekommt drei Bestände mit je eigener Antwort.
- **Schwieriger:** Das Schrittprotokoll braucht eine eigene Tabelle mit Tagespartitionen und einen
  eigenen Löschlauf. Jedes weitere Feld in (b) braucht eine Begründung und eine Änderung des Auszugs.
  Eine Rückfrage kostet einen neuen Lauf mit erneutem Aufbau des Kontexts.
- **Restrisiko:** Die Lauf-Kennung steht in (a) an der Chat-Nachricht. Wer direkt in der Datenbank
  liest, kann (b) über (a) einer Person zuordnen, solange beide bestehen. Das ist ein Zugriff des
  Betriebs, kein Weg der Anwendung.

## Verworfene Alternativen

- Agenten-Framework (etwa LangChain4j oder ein Graph-Orchestrator) neben Spring AI: zweites
  Abstraktionsmodell ohne Bedarf an Mehrschritt-Planung (1).
- Lauf, der auf eine Rückfrage wartet: Zustand über eine Anfrage hinaus, der beim Pod-Tausch
  verloren ginge oder einen Laufbegriff bräuchte (1).
- Rechteprüfung zentral in der Schleife: Ein später ergänztes Werkzeug ohne Schleifenprüfung wäre
  offen (2).
- Rechteklasse erst mit dem ersten schreibenden Werkzeug: Dann muss jedes Werkzeug neu bewertet
  werden (2).
- Suchbereich als Modellargument mit Prüfung gegen die Rechte: Das Modell würde zum Träger der
  Rechte (3).
- Fremder Bibliotheksbezeichner als Eingabefehler mit Abbruch: Eine eingebettete Anweisung könnte
  jeden Lauf gezielt scheitern lassen (3).
- Schrittprotokoll im Diagnoseprotokoll der Suche: zwölf Monate, Einzelsatzansicht und
  Auswertungsrolle widersprechen der kurzen Fassung ohne Konto-Bezug (4).
- Ablage neuer Bauart für das Schrittprotokoll: Sie würde beim nächsten Löschauftrag oder
  Rechteumbau vergessen (4).
- Lesende Werkzeugaufrufe im Revisionsprotokoll: Sie ergäben das ausgeschlossene Tätigkeitsprofil (4).
- Werkzeugergebnisse im Systemvorspann oder in der Nachricht der Person: Eine eingebettete Anweisung
  spräche mit Systemrang oder als Wort der Person (5).
- Erinnerung als Systemnachricht vor jedem Aufruf: von manchen Chat-Templates abgelehnt (5).
- Agenten-Anweisung, die den Systemvorspann ersetzt oder der Space-Anweisung vorgeht: Belegregeln
  und Vorgaben des Space hingen am Wortlaut jedes Agenten (5).
- Werkzeugfähigkeit als Ja/Nein mit prüfender Stelle: „nie gesetzt“ und „geprüft, kann es nicht“
  wären ununterscheidbar (6).
- Platzlimit je Instanz, durch N geteilt: wiese bei ungleicher Last zu früh ab (6).
- Freigabe schreibender Aufrufe, weil das Modell vorher fragt: hinge an einer gemessenen
  Eigenschaft (6).

## Offen

- Höhe der Grenzen und des Platzlimits, kalibriert an realistischen Fällen vor der ersten
  Vorführung.
- Feldliste der Ereignisart für schreibende Werkzeugaufrufe; vorgesehen sind aufrufende Person,
  Werkzeug, Zielsystem, Zeitpunkt, Entscheidung (bestätigt / abgelehnt / verfallen), Agentenfassung
  und Lauf-Kennung.
