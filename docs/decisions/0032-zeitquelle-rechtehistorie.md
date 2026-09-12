# ADR-0032: Zeitquelle der Rechtehistorie — prozesslokale, streng monotone Uhr

## Status

Akzeptiert

## Kontext

Die Rechtehistorie aus #238 schreibt jede Rechteänderung als halboffenes Intervall `[validFrom, validTo)`
und beantwortet damit die Prüferfrage zum Stichtag
(`docs/features/security-and-compliance.md#nachweisbarkeit-historisierung-von-rechten`). Die
Rekonstruktion wählt ein Intervall über `validFrom <= :asOf and (validTo is null or validTo > :asOf)`.

Die Intervallgrenzen stammten aus `Instant.now()` der JVM. Diese Uhr ist auf gängigen
Windows-Maschinen nicht mikrosekundenfein: gemessen auf der Zielmaschine (200.000 aufeinanderfolgende
Lesungen) beträgt der kleinste Fortschritt rund 3,64 ms, und praktisch jede unmittelbar folgende
Lesung liefert denselben Wert. Zwei Rechteänderungen am selben Objekt innerhalb eines solchen Ticks
bekommen damit dieselbe Grenze — das Intervall dazwischen ist `validFrom == validTo` und damit leer.
Für ein leeres halboffenes Intervall gibt es keinen Zeitpunkt, der die Auswahlbedingung erfüllt: Die
Rekonstruktion meldet diesen Zustand für **keinen** Stichtag.

Die Folge ist keine fehlende, sondern eine falsche Auskunft — für einen Stichtag im verlorenen Zeitraum
antwortet die Rekonstruktion „kein Zugriff", obwohl Zugriff bestand. Realistische Auslöser im Betrieb:
eine Bibliothek anlegen und unmittelbar organisationsweit stellen, eine versehentlich geweitete
Sichtbarkeit sofort zurücknehmen, ein Verzeichnisabgleich, der mehrere Mitgliedschaften in einem Lauf
ändert, Massenoperationen über die API. Das trifft dieselbe Zusage, für die
[ADR-0016](0016-loeschschicksal-rechtehistorie.md) bereits einmal den Fall „Historie überlebt die
Löschung nicht" korrigieren musste — derselbe Schaden auf einem anderen Weg (#1497).

Es geht dabei **nicht** um zwei gegeneinander laufende Uhren: Die Datenbank stempelt nichts, sie
speichert den übergebenen Wert lediglich mit Mikrosekunden-Auflösung. Es gibt genau eine Uhr, und sie
ist zu grob.

Warum das eine Architekturentscheidung und nicht nur eine Javadoc-Invariante ist: Die Wahl steht
zwischen einer datenbankseitigen Lösung — die Datenbankuhr (`clock_timestamp()`) samt einer Sicherung
in der Datenbank selbst — und einer JVM-seitigen. Die datenbankseitige verlegt die Korrektheit einer
nachweisrelevanten Tabelle ins Schema und gilt unabhängig davon, wie viele Prozesse schreiben; die
JVM-seitige ist billiger und einfacher, aber nur unter der Single-Instance-Annahme aus
[ADR-0021](0021-single-instance-betrieb.md) haltbar. Das ist eine Festlegung mit Folgen für einen
künftigen Mehrinstanzbetrieb — und genau die Art Festlegung, die ohne ADR bei der nächsten
Performance-Optimierung unbemerkt zurückgedreht wird.

## Entscheidung

**Alle Intervallgrenzen der Rechtehistorie stammen aus einer prozesslokalen, streng monotonen
Zeitquelle** (`PermissionHistoryClock`), nicht direkt aus der Wanduhr — weder aus der der JVM noch aus
der der Datenbank.

Der Vertrag dieser Quelle:

1. **Streng monoton auf Mikrosekunden.** Jeder Aufruf liefert einen Wert echt größer als der vorige.
   Eine Wanduhr-Lesung, die nicht größer ist als die zuletzt ausgegebene Grenze (grober Tick,
   Rückwärtssprung durch NTP), wird durch „letzte Grenze plus eine Mikrosekunde" ersetzt.
2. **Mikrosekunden-Auflösung, vor der Datenbank abgeschnitten.** `timestamptz` speichert
   Mikrosekunden; zwei Grenzen, die sich erst in Nanosekunden unterscheiden, wären nach dem Schreiben
   ein und derselbe Wert. Die Abschneidung passiert deshalb vor der Monotonie-Prüfung, nicht in der
   Datenbank.
3. **Der Wert bleibt ein Wanduhr-Zeitpunkt — mit begrenzter Genauigkeit.** Innerhalb eines groben
   Ticks läuft die Quelle der Wanduhr um eine Mikrosekunde je Aufruf voraus und fällt auf deren
   Lesung zurück, sobald diese wieder größer ist; bei 3,64 ms Tick holt die Wanduhr jeden Burst
   unterhalb von rund 3.600 Historienzeilen innerhalb eines einzigen Ticks wieder ein. Nach einem
   **Rückwärtssprung** der Wanduhr um Δ (NTP-Korrektur, Wiederherstellung eines VM-Schnappschusses)
   beträgt der Vorlauf dagegen sofort ganze Δ und bleibt es, bis die Wanduhr die zuletzt vergebene
   Grenze wieder überschritten hat — siehe Konsequenzen.

**Die daraus folgende Invariante der Tabellen** — als Vertrag im Javadoc von
`PermissionHistoryService` festgehalten:

> Aufeinanderfolgende **Zustands**intervalle desselben Objekts haben streng aufsteigende Grenzen.
> Nulllängen-Zeilen sind ausschließlich Ereignismarkierungen.

Beide Hälften sind nötig. Die Invariante „Intervalle sind nie leer" wäre falsch:
`AssetGrantHistory#terminal`, `LibraryVisibilityHistory#terminal` und `GroupMembershipHistory#terminal`
erzeugen absichtlich Zeilen mit `validFrom == validTo`, die einen Widerruf oder eine Löschung als
Ereignis festhalten. Sie werden von der Rekonstruktion nie ausgewählt und sind deshalb kein Zustand,
der verloren gehen könnte.

Unverändert bleibt die **lückenlose Verkettung**: Das Schließen eines Intervalls und das Öffnen des
folgenden teilen sich einen einzigen Grenzwert (`validTo` des alten `==` `validFrom` des neuen). Streng
aufsteigend sind die Grenzen aufeinanderfolgender *Änderungen*, nicht die beiden Seiten derselben
Änderung.

**Die Zusage ordnet die Vergabe der Grenzen, nicht die Commits um sie herum.** Dass zwei nebenläufige
Transaktionen keine verschränkte Kette am selben Objekt hinterlassen, leisten die partiellen
Unique-Indizes auf den offenen Zeilen (je Objekt höchstens ein Intervall mit `valid_to IS NULL`) — nicht
die Uhr. Uhr und Index zusammen ergeben die Zusage; keiner von beiden genügt allein.

**Weil alle Grenzen global streng geordnet sind, gilt die Rekonstruierbarkeit auch für
Kombinationszustände.** Die Rechtemenge einer Person entsteht aus drei Tabellen; ein Zustand, der erst
aus dem Zusammentreffen eines Grants, einer Mitgliedschaft und einer Sichtbarkeit entsteht, hat einen
Zeitraum, der von Grenzen aus allen dreien begrenzt wird. Da die eine Quelle sie alle vergibt und nie
zweimal denselben Wert ausgibt, ist auch dieser Zeitraum echt positiv lang und damit von einem Stichtag
treffbar — ohne die globale Ordnung wäre nur die Rekonstruierbarkeit je Einzeltabelle gesichert.

**Prozesslokale Monotonie genügt, solange ADR-0021 gilt.** `PermissionHistoryClock` ist als Fundstelle
in ADR-0021 eingetragen.

## Geprüfte Alternativen

Von den fünf hier geprüften Wegen ist genau einer **vertagt** statt verworfen: `clock_timestamp()`
samt datenbankseitiger Sicherung. Er ist tragfähig, heute schon baubar und bleibt der Weg für einen
Mehrinstanzbetrieb — er hat heute nur keinen Abnehmer. Die übrigen vier sind verworfen.

### Verworfen: `clock_timestamp()` für sich genommen

**`clock_timestamp()` der Datenbank je Schreibvorgang, ohne weitere Sicherung** (der Vorschlag in
#1497). Verworfen, weil sie die gebrauchte Zusage nicht liefert: `clock_timestamp()` ist die Systemuhr des
Datenbankhosts mit Mikrosekunden-Auflösung. Strenge Monotonie ist nicht zugesichert — zwei Aufrufe
innerhalb derselben Mikrosekunde liefern denselben Wert, und ein Rückwärtssprung der Systemuhr ist
möglich. In der Praxis fast immer richtig; „fast immer richtig" ist für eine nachweisrelevante Tabelle
aber genau die Eigenschaft, die dieses ADR ersetzen soll. Der Ausdruck allein ist damit kein Kandidat —
die ernstzunehmende Alternative ist die nächste.

### Vertagt, nicht verworfen: `clock_timestamp()` plus datenbankseitige Sicherung

Das ist der echte Gegenentwurf, und er ist **heute schon baubar**, nicht erst bei einem
Mehrinstanz-Umbau: die Datenbankuhr als Anker, ergänzt um eine Sicherung, die die strenge Ordnung in
der Datenbank selbst erzwingt. Die Bauart ist hier bewusst nicht festgelegt — eine Sequenz allein
leistet sie nicht, weil streng aufsteigende Zeitstempel gespeicherten Zustand brauchen (der Sache nach
`GREATEST(clock_timestamp(), letzte Grenze + 1 µs)`); in Frage kommen ein `EXCLUDE`-Constraint, der
Überschneidungen und leere Zustandsintervalle je Objekt zurückweist, oder eine schreibende
Hilfsstruktur je Objekt. Die Festlegung gehört in #1517.

- **Was sie besser kann — zwei Gewinne, die getrennt zu haben sind:**
  - *Instanzunabhängigkeit.* Die Zusage hinge nicht mehr an ADR-0021: Sie gälte über Prozessgrenzen
    hinweg, weil alle Instanzen dieselbe Uhr und dieselbe Sicherung benutzten. **Dafür** braucht es
    tatsächlich den Wechsel auf die Datenbankuhr.
  - *Schutz gegen Schreiber, die die Anwendung umgehen* (Migrationsskript, Handkorrektur per SQL).
    **Dafür braucht es den Zeitquellenwechsel nicht** — das leistet die datenbankseitige Sicherung
    allein, auch über der heutigen JVM-Uhr. Wer nur diesen Gewinn will, kauft den Wechsel nicht mit;
    genau das ist der Gegenstand von #1517.
- **Was sie heute kostet:** eine Migration auf einer nachweisrelevanten Tabelle, dauerhaft ein
  zusätzliches Statement je historisierter Änderung, und eine Sicherung, die die beiden Ausnahmen der
  Invariante kennen muss — die geteilte Grenze zwischen schließender und öffnender Zeile und die
  absichtlichen Nulllängen-Marker. Ein `EXCLUDE`-Constraint müsste beide ausnehmen, also genau die
  Fachlogik abbilden, die schon im Java-Code steht.
- **Zum zusätzlichen Statement, genau:** Von den neun Aufrufstellen lesen sechs ohnehin die offene
  Zeile, bevor sie schreiben — `recordGrantRoleChanged`, `recordGrantRevoked` und
  `recordGrantClosedByLibraryDeletion` über `closeOpenGrantInterval`, dazu `recordMembershipRemoved`,
  `recordVisibilityChanged` und `recordVisibilityClosedByLibraryDeletion`. Nur `recordGrantCreated`,
  `recordMembershipAdded` und `recordLibraryCreated` schreiben ohne vorheriges Lesen. Alle neun laufen
  zudem in einer offenen Transaktion auf einer bestehenden Verbindung: Es geht um ein weiteres
  Statement, nicht um einen Verbindungsaufbau. Auch der Multiplikator „Mitgliedschaftsänderungen eines
  Verzeichnisabgleichs" trägt nur zur Hälfte, weil die Entfernungen über `recordMembershipRemoved`
  bereits lesende Pfade sind. Das Kostenargument ist also real, aber klein — es allein würde die
  Alternative nicht abräumen.
- **Warum trotzdem nicht heute:** Der Gewinn, der nur über diesen Weg zu haben ist, ist
  Instanzunabhängigkeit — und die hat keinen Abnehmer, solange ADR-0021 gilt; der zweite Gewinn steht
  über #1517 ohnehin ohne Zeitquellenwechsel offen. Dem steht eine Schemaänderung an genau der
  Tabelle gegenüber, deren Beweiskraft hier verteidigt wird, plus eine Sicherung, die die Invariante
  ein zweites Mal formulieren muss — und zwar auf einem Bestand, der sie nachweislich verletzt, weil
  die vor dieser Änderung entstandenen leeren Intervalle bewusst nicht nachbearbeitet werden: Eine
  Validierung gegen den Bestand schlüge fehl, und ob `NOT VALID`, eine Einschränkung auf
  `valid_from >= <Migrationszeitpunkt>` oder doch eine Bereinigung, ist offen (#1517). Dazu kommt die
  Prüfbarkeit: Der geforderte Nachweis verlangt einen erzwungenen Uhr-Tick ohne Wartezeit und ohne
  Wiederholungsschleife. Eine stehende Uhr lässt sich einspeisen, die Uhr eines laufenden Postgres
  nicht — der Fall ließe sich nur wahrscheinlich machen, nicht herstellen. Die Entscheidung ist damit
  eine **Vertagung**, keine Ablehnung: Fällt ADR-0021, ist dies der Weg, und die Umstellung trifft
  eine einzige Klasse, weil alle neun Aufrufstellen bereits durch sie laufen.

### Verworfen: den Wert im `INSERT` erzeugen lassen

**Spalten-Default `clock_timestamp()` oder ein `BEFORE INSERT`-Trigger.** Diese Ausprägung kostet
**null** zusätzliche Statements und entkräftet das Kostenargument vollständig — sie scheitert an
etwas anderem: Die lückenlose Verkettung verlangt, dass
schließende und öffnende Zeile sich denselben Wert teilen. Ein Default oder Trigger erzeugt den Wert
je Zeile und erst beim Schreiben; die JVM kennt ihn nicht und kann ihn dem `UPDATE ... SET valid_to`
der Vorgängerzeile deshalb nicht mitgeben. Ihn zurückzulesen kostet genau das Statement wieder, das
man gespart hat — unter Hibernate zudem an der unangenehmsten Stelle, weil die neue Zeile im selben
Flush geschrieben und sofort wieder gelesen werden müsste (`@Generated`/`refresh`-Semantik), und zwar
vor dem `UPDATE`, das die Reihenfolge-Zusicherung in `closeOpenGrantInterval` ohnehin schon erzwingt.
Hinzu käme, dass ein Trigger die Nulllängen-Marker von den Zustandsintervallen unterscheiden müsste,
die Fachlogik also ein zweites Mal trüge.

### Verworfen: die Grenze nur bei Gleichheit anheben

**Die Grenze um eine Mikrosekunde anheben, wenn der neue `validFrom` dem vorigen `validTo` gleicht** —
eine Nachbesserung an der Aufrufstelle statt einer eigenen Zeitquelle. Verworfen: Das prüft gegen den
zuletzt *gelesenen* Zustand des betroffenen Objekts statt gegen die zuletzt *vergebene* Grenze,
verteilt die Korrektheit über neun Aufrufstellen, die jede für sich richtig bleiben müssen, und lässt
die Fälle ungelöst, in denen die Vorgängerzeile gar nicht gelesen wird (`recordGrantCreated`,
`recordMembershipAdded`, `recordLibraryCreated`).

### Verworfen: nichts tun

**Die Grenze im Fachmodell ziehen** („ein Zustand, der kürzer als einen Uhr-Tick bestand, muss nicht
rekonstruierbar sein"). Vertretbar formulierbar, aber verworfen: Die Grenze läge
dann bei der Zeitgeberauflösung des Betriebssystems — auf einer Maschine 3,64 ms, auf einer anderen
1 µs. Eine Nachweiszusage, die von der Wirtsplattform abhängt, ist keine.

## Konsequenzen

**Einfacher:**

- Ein Zustand, den ein Objekt wirklich innehatte, ist zu einem Stichtag rekonstruierbar — unabhängig
  davon, wie fein die Uhr des Wirtssystems läuft.
- Der Fall ist deterministisch testbar: eine stehende Wanduhr modelliert einen Uhr-Tick exakt, ohne
  Wartezeit und ohne Wiederholungsschleife.
- Drei Tests in `PermissionHistoryServiceIntegrationTest`, die einen Stichtag *zwischen* zwei schnell
  aufeinanderfolgenden Operationen nehmen und deshalb in Vollbuilds sporadisch rot waren, sind es
  nicht mehr — die Ursache war dieselbe.
- Kein zusätzliches Statement je historisierter Änderung — klein, wie oben eingeordnet, aber
  dauerhaft und ohne Gegenwert, solange ADR-0021 gilt.

**Schwieriger / bewusst in Kauf genommen:**

- Ein aufgezeichneter Zeitstempel kann der Wanduhr vorauslaufen. Im Normalfall um Mikrosekunden —
  für die Stichtagsfrage (ein Datum, ein Zeitpunkt) ohne Bedeutung; für einen Vergleich mit einem
  frisch gelesenen `Instant.now()` nicht, weshalb Tests, die einen Stichtag „nach der Änderung"
  brauchen, ihn aus derselben Quelle nehmen.
- **Nach einem Rückwärtssprung der Wanduhr ist der Vorlauf die Sprunghöhe, nicht eine Mikrosekunde.**
  Springt die Uhr um Δ zurück (NTP-Korrektur, VM-Schnappschuss), liegen die ab dann vergebenen Grenzen
  bis zum Ablauf von Δ in der Zukunft und drängen sich in Mikrosekunden-Schritten um den Wert vor dem
  Sprung. Die Monotonie bleibt unberührt, die absolute Genauigkeit nicht: Für diese Spanne sagt die
  Historie die Reihenfolge der Änderungen korrekt, ihren Zeitpunkt aber um bis zu Δ zu spät. Bewusst
  in Kauf genommen — die Gegenrichtung wäre, dem Sprung zu folgen und damit die Ordnung aufzugeben,
  also genau den Fehler zu wiederholen, den dieses ADR behebt. Ein Betreiber, der Δ klein hält (NTP
  mit `slew` statt `step`), hält damit auch diese Abweichung klein.
- Die Zusage gilt je Prozess. Bei mehreren Backend-Instanzen könnten zwei Änderungen am selben Objekt
  aus verschiedenen Prozessen wieder dieselbe Grenze bekommen; die Uhren zweier Hosts können zudem
  gegeneinander driften. Ein Multi-Instanz-Umbau muss die Quelle deshalb ersetzen — durch die
  Datenbankuhr samt datenbankseitiger Monotonie-Sicherung (Bauart offen, siehe #1517), nicht durch
  eine weitere prozesslokale Variable.
  Eingetragen in der Fundstellenliste von ADR-0021.
- Bestandsdaten werden nicht nachbearbeitet: Bereits geschriebene leere Intervalle bleiben, wie sie
  sind (stehende Maintainer-Festlegung, keine Bestandsnachzüge). Die Korrektur wirkt vorwärts — die
  Zusage ist deshalb auch im Javadoc und in der Feature-Spezifikation ausdrücklich auf Zeilen ab dieser
  Änderung datiert, damit aus einer leeren Rekonstruktion über einen Altzeitraum niemand auf „kein
  Zugriff" schließt.
- **Die Invariante ist nicht datenbankseitig abgesichert.** Sie hält, solange alle Schreiber durch
  `PermissionHistoryService` gehen; ein Migrationsskript oder eine Handkorrektur per SQL könnte sie
  verletzen, ohne dass etwas es bemerkt. Ein `EXCLUDE`-Constraint, der leere Zustandsintervalle und
  Überschneidungen je Objekt zurückweist und dabei die Nulllängen-Marker ausnimmt, wäre die
  Ergänzung — bewusst offen gelassen und als #1517 geführt, nicht Teil dieser Entscheidung. Dort
  ist auch zu klären, wie der Constraint mit dem Bestand umgeht, der die Invariante nachweislich
  verletzt: Die vor dieser Änderung entstandenen leeren Intervalle bleiben stehen, eine Validierung
  gegen den Bestand schlüge also fehl.
