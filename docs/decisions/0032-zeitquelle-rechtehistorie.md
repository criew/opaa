# ADR-0032: Zeitquelle der Rechtehistorie — prozesslokale, streng monotone Uhr

## Status

Akzeptiert · zuletzt geändert 2026-09-20

## Kontext

Die Rechtehistorie schreibt jede Rechteänderung als halboffenes Intervall `[validFrom, validTo)` und
beantwortet damit die Prüferfrage zum Stichtag (`validFrom <= :asOf and (validTo is null or validTo
> :asOf)`). Die Grenzen stammten aus `Instant.now()`, das auf gängigen Windows-Maschinen in Ticks
von rund 3,64 ms fortschreitet: Zwei Änderungen am selben Objekt innerhalb eines Ticks bekamen
dieselbe Grenze, das Intervall dazwischen war leer, und die Rekonstruktion antwortete für einen
Stichtag darin „kein Zugriff", obwohl Zugriff bestand (#1497) — derselbe Schaden wie der, den
[ADR-0016](0016-loeschschicksal-rechtehistorie.md) auf anderem Weg korrigieren musste. Die Datenbank
stempelt nichts; es gibt eine Uhr, und sie ist zu grob. Die Wahl zwischen einer datenbankseitigen
Lösung (`clock_timestamp()` samt Sicherung im Schema, unabhängig von der Zahl der Prozesse) und einer
JVM-seitigen (billiger, aber nur unter [ADR-0021](0021-single-instance-betrieb.md) haltbar) ist eine
Festlegung mit Folgen für einen Mehrinstanzbetrieb.

## Entscheidung

### 1. Alle Intervallgrenzen stammen aus `PermissionHistoryClock`

Eine prozesslokale, streng monotone Zeitquelle, nicht die Wanduhr der JVM oder der Datenbank.
Vertrag: Jeder Aufruf (`nextBoundary()`) liefert einen Wert echt größer als der vorige; eine
Wanduhr-Lesung, die nicht größer ist als die zuletzt ausgegebene Grenze (grober Tick,
Rückwärtssprung), wird durch „letzte Grenze plus eine Mikrosekunde" ersetzt. Die Auflösung ist auf
Mikrosekunden abgeschnitten, bevor die Monotonie geprüft wird, weil `timestamptz` Mikrosekunden
speichert. Der Wert bleibt ein Wanduhr-Zeitpunkt mit begrenzter Genauigkeit: Innerhalb eines Ticks
läuft die Quelle um Mikrosekunden voraus und fällt auf die Wanduhr zurück, sobald sie wieder größer
ist; nach einem Rückwärtssprung um Δ beträgt der Vorlauf Δ, bis die Wanduhr die letzte Grenze
überschritten hat. Alle Schreiber der Historientabellen beziehen ihre Grenzen aus dieser einen
Quelle: `PermissionHistoryService` (Grants, Gruppenmitgliedschaften, Fähigkeiten),
`AssetVisibilityHistoryService`, `AssetOwnershipHistoryService`, `AccountStateHistoryService`,
`SpaceMembershipHistoryService` und `PermissionTransferService`.

### 2. Die Invariante der Tabellen

Als Vertrag im Javadoc von `PermissionHistoryService`: **Aufeinanderfolgende Zustandsintervalle
desselben Objekts haben streng aufsteigende Grenzen; Nulllängen-Zeilen sind ausschließlich
Ereignismarkierungen.** Beide Hälften sind nötig: Die `terminal`-Fabriken der Historien erzeugen
absichtlich Zeilen mit `validFrom == validTo` für Widerruf oder Löschung, die die Rekonstruktion nie
auswählt. Unverändert bleibt die lückenlose Verkettung: Schließen und Öffnen teilen sich einen
Grenzwert. Die Uhr ordnet die Vergabe der Grenzen; dass zwei nebenläufige Transaktionen keine
verschränkte Kette hinterlassen, leisten die partiellen Unique-Indizes auf den offenen Zeilen — Uhr
und Index zusammen ergeben die Zusage. Weil alle Grenzen global geordnet sind, gilt die
Rekonstruierbarkeit auch für Kombinationszustände aus mehreren Tabellen. Die Zusage gilt für Zeilen
ab #1497; vorher entstandene leere Intervalle bleiben stehen (keine Bestandsnachzüge), und das
Javadoc und die Spezifikation datieren sie deshalb ausdrücklich.

### 3. Prozesslokale Monotonie genügt, solange ADR-0021 gilt

`PermissionHistoryClock` ist als Fundstelle in ADR-0021 eingetragen. Ein Multi-Instanz-Umbau muss
die Quelle ersetzen — durch die Datenbankuhr samt datenbankseitiger Monotonie-Sicherung —, nicht
durch eine weitere prozesslokale Variable; die Umstellung trifft eine Klasse, weil alle Schreiber
durch sie laufen.

## Konsequenzen

- **Einfacher:** Ein Zustand, den ein Objekt innehatte, ist zu einem Stichtag rekonstruierbar,
  unabhängig von der Uhr des Wirtssystems. Deterministisch testbar mit einer stehenden Wanduhr. Kein
  zusätzliches Statement je historisierter Änderung.
- **Schwieriger / in Kauf genommen:** Ein Zeitstempel kann der Wanduhr vorauslaufen — normal um
  Mikrosekunden (Tests nehmen einen Stichtag „nach der Änderung" aus derselben Quelle), nach einem
  Rückwärtssprung um bis zu Δ: Die Historie sagt dann die Reihenfolge korrekt, den Zeitpunkt zu
  spät. Ein Betreiber hält Δ mit NTP-`slew` klein.
- **Restrisiko:** Die Invariante ist nicht datenbankseitig abgesichert; ein Migrationsskript oder
  eine Handkorrektur per SQL könnte sie verletzen. Der Maintainer hat die Absicherung (#1517,
  `EXCLUDE`-Constraint je Objekt mit `btree_gist`) als zu exotisch verworfen; neu zu bewerten beim
  Multiinstanzbetrieb (#1292), wo sie Ersatz statt Ergänzung wäre.

## Verworfene Alternativen

- `clock_timestamp()` für sich genommen — keine strenge Monotonie, Rückwärtssprünge möglich; „fast immer richtig" ist für eine nachweisrelevante Tabelle nicht genug.
- `clock_timestamp()` plus datenbankseitige Sicherung — **vertagt, nicht verworfen**: tragfähig und der Weg für den Mehrinstanzbetrieb, heute ohne Abnehmer (Schemaänderung an der Tabelle, deren Beweiskraft verteidigt wird; Sicherung formuliert die Fachlogik ein zweites Mal; Bestand verletzt die Invariante; Uhr-Tick nicht erzwingbar testbar). Der zweite Gewinn, Schutz gegen Schreiber an der Anwendung vorbei, bräuchte den Uhrwechsel nicht und ist mit #1517 bewusst nicht genommen.
- Wert im `INSERT` erzeugen (Default oder Trigger) — die lückenlose Verkettung braucht den Wert in der JVM für das `UPDATE` der Vorgängerzeile.
- Grenze nur bei Gleichheit anheben — prüft gegen den gelesenen statt den vergebenen Wert, verteilt die Korrektheit über alle Aufrufstellen.
- Nichts tun („kürzer als ein Tick muss nicht rekonstruierbar sein") — eine Nachweiszusage, die von der Wirtsplattform abhängt, ist keine.

## Offen

- Wechsel auf die Datenbankuhr samt datenbankseitiger Sicherung, sobald ADR-0021 fällt (#1292).
