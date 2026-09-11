# ADR-0032: Zeitquelle der Rechtehistorie — prozesslokale, streng monotone Uhr

## Status

Vorgeschlagen

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

Warum das eine Architekturentscheidung und nicht nur eine Javadoc-Invariante ist: Die naheliegende
Abhilfe — die Datenbankuhr (`clock_timestamp()`) als maßgebliche Zeitquelle — koppelt die Korrektheit
einer nachweisrelevanten Tabelle an die Datenbank und kostet je Schreibvorgang einen Roundtrip. Die
billigere, JVM-seitige Alternative ist nur unter der Single-Instance-Annahme aus
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
3. **Der Wert bleibt ein Wanduhr-Zeitpunkt.** Die Quelle läuft der Wanduhr um höchstens eine
   Mikrosekunde je Aufruf voraus und fällt auf deren Lesung zurück, sobald diese wieder größer ist.
   Bei 3,64 ms Tick und einer Mikrosekunde je Schreibvorgang holt die Wanduhr jeden Burst unterhalb von
   rund 3.600 Historienzeilen innerhalb eines einzigen Ticks wieder ein.

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

**Prozesslokale Monotonie genügt, solange ADR-0021 gilt.** `PermissionHistoryClock` ist als Fundstelle
in ADR-0021 eingetragen.

## Verworfene Alternativen

**`clock_timestamp()` der Datenbank je Schreibvorgang** (der ursprüngliche Vorschlag in #1497).
Verworfen aus drei Gründen:

- **Sie liefert die gebrauchte Zusage nicht.** `clock_timestamp()` ist die Systemuhr des
  Datenbankhosts mit Mikrosekunden-Auflösung. Strenge Monotonie ist nicht zugesichert: Zwei Aufrufe
  innerhalb derselben Mikrosekunde liefern denselben Wert, und ein Rückwärtssprung der Systemuhr ist
  möglich. In der Praxis fast immer richtig — „fast immer richtig" ist für eine nachweisrelevante
  Tabelle aber genau die Eigenschaft, die dieses ADR ersetzen soll. Wer die Zusage tatsächlich will,
  braucht zusätzlich eine Monotonie-Sicherung; die ist prozesslokal, womit der Unterschied zur
  JVM-Variante auf die Frage schrumpft, wessen Uhr den Anker liefert.
- **Sie kostet einen Roundtrip je historisierter Änderung** auf einem Pfad, der sonst keinen Grund
  hätte, vor seinem `INSERT` mit der Datenbank zu sprechen — multipliziert mit der Menge an
  Mitgliedschaftsänderungen, die ein Verzeichnisabgleich in einem Lauf schreibt.
- **Sie ist nicht deterministisch prüfbar.** Der Nachweis, dass ein Zustand innerhalb eines Uhr-Ticks
  rekonstruierbar bleibt, verlangt einen Tick, der sich erzwingen lässt. Eine stehende Uhr lässt sich
  einspeisen, die Uhr eines laufenden Postgres nicht — der Test könnte den Fall nur wahrscheinlich
  machen, nicht herstellen (Wartezeit und Wiederholungsschleife sind in #1497 ausdrücklich
  ausgeschlossen).

**Die Grenze nur dann um eine Mikrosekunde anheben, wenn der neue `validFrom` dem vorigen `validTo`
gleicht** (Nachbesserung an der Aufrufstelle statt einer eigenen Zeitquelle). Verworfen: Das prüft
gegen den zuletzt *gelesenen* Zustand des betroffenen Objekts statt gegen die zuletzt *vergebene*
Grenze, verteilt die Korrektheit über neun Aufrufstellen, die jede für sich richtig bleiben müssen, und
lässt die Fälle ungelöst, in denen die Vorgängerzeile gar nicht gelesen wird (`recordGrantCreated`,
`recordMembershipAdded`, `recordLibraryCreated`).

**Nichts tun und die Grenze im Fachmodell ziehen** („ein Zustand, der kürzer als einen Uhr-Tick
bestand, muss nicht rekonstruierbar sein"). Vertretbar formulierbar, aber verworfen: Die Grenze läge
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
- Kein zusätzlicher Datenbank-Roundtrip auf dem Schreibpfad.

**Schwieriger / bewusst in Kauf genommen:**

- Ein aufgezeichneter Zeitstempel kann der Wanduhr um Mikrosekunden vorauslaufen. Für die
  Stichtagsfrage (ein Datum, ein Zeitpunkt) ist das ohne Bedeutung; für einen Vergleich mit einem
  frisch gelesenen `Instant.now()` nicht — Tests, die einen Stichtag „nach der Änderung" brauchen,
  nehmen ihn deshalb aus derselben Quelle, nicht aus der Wanduhr.
- Die Zusage gilt je Prozess. Bei mehreren Backend-Instanzen könnten zwei Änderungen am selben Objekt
  aus verschiedenen Prozessen wieder dieselbe Grenze bekommen; die Uhren zweier Hosts können zudem
  gegeneinander driften. Ein Multi-Instanz-Umbau muss die Quelle deshalb ersetzen — durch die
  Datenbankuhr samt datenbankseitiger Monotonie-Sicherung (etwa einer Sequenz oder einem
  `EXCLUDE`-Constraint auf dem Intervall je Objekt), nicht durch eine weitere prozesslokale Variable.
  Eingetragen in der Fundstellenliste von ADR-0021.
- Bestandsdaten werden nicht nachbearbeitet: Bereits geschriebene leere Intervalle bleiben, wie sie
  sind (stehende Maintainer-Festlegung, keine Bestandsnachzüge). Die Korrektur wirkt vorwärts.
