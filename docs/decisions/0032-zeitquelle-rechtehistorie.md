# ADR-0032: Zeitquelle der Rechtehistorie — streng monotone Uhr der Datenbank

## Status

Akzeptiert · zuletzt geändert 2026-10-20 · ergänzt durch ADR-0053 Entscheidung 9

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

Eine streng monotone Zeitquelle in der Datenbank (`permission_history_next_boundary()`), nicht die
Wanduhr der JVM und nicht `clock_timestamp()` allein. Vertrag: Jeder Aufruf (`nextBoundary()`)
liefert einen Wert echt größer als jede zuvor vergebene Grenze der Installation; eine Lesung der
Datenbankuhr, die nicht größer ist als die zuletzt vergebene Grenze (grober Tick, Rückwärtssprung),
wird durch „letzte Grenze plus eine Mikrosekunde" ersetzt. Gerechnet wird in Mikrosekunden, weil
`timestamptz` Mikrosekunden speichert. Der Wert bleibt ein Uhrzeitpunkt mit begrenzter Genauigkeit:
Innerhalb einer Mikrosekunde läuft die Quelle voraus und fällt auf die Datenbankuhr zurück, sobald
sie wieder größer ist; nach einem Rückwärtssprung um Δ beträgt der Vorlauf Δ, bis die Uhr die letzte
Grenze überschritten hat. Alle Schreiber der Historientabellen beziehen ihre Grenzen aus dieser einen
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

### 3. Die Monotonie sichert die Datenbank, installationsweit

Eine Sequenz hält die zuletzt vergebene Grenze aller Historientabellen, in Mikrosekunden seit der
Epoche; die Vergabe ist über einen Advisory-Lock serialisiert, der nur für diese eine Anweisung
gehalten wird, nicht für die Transaktion. Damit gilt die Zusage über alle Instanzen einer
Installation (ADR-0053 Entscheidung 9), und die globale Ordnung aus Entscheidung 2 bleibt erhalten.
Beim Einspielen startet die Sequenz über der größten schon gespeicherten Grenze. Der Wert wird
weiterhin in der JVM gebraucht (lückenlose Verkettung), deshalb holt `PermissionHistoryClock` ihn
mit einer eigenen Abfrage auf der Verbindung der laufenden Transaktion.

## Konsequenzen

- **Einfacher:** Ein Zustand, den ein Objekt innehatte, ist zu einem Stichtag rekonstruierbar,
  unabhängig von der Uhr des Wirtssystems und der Zahl der Instanzen. Deterministisch testbar, indem
  die Sequenz vor die Datenbankuhr gesetzt wird.
- **Teurer:** Eine zusätzliche, kurze Anweisung je vergebener Grenze.
- **Schwieriger / in Kauf genommen:** Ein Zeitstempel kann der Wanduhr vorauslaufen — normal um
  Mikrosekunden (Tests nehmen einen Stichtag „nach der Änderung" aus derselben Quelle), nach einem
  Rückwärtssprung um bis zu Δ: Die Historie sagt dann die Reihenfolge korrekt, den Zeitpunkt zu
  spät. Ein Betreiber hält Δ auf dem Datenbankserver mit NTP-`slew` klein.
- **Restrisiko:** Die Invariante der Tabellen selbst ist nicht durch ein Constraint abgesichert; ein
  Migrationsskript oder eine Handkorrektur per SQL, die eine Grenze an der Quelle vorbei schreibt,
  könnte sie verletzen. Die Absicherung je Objekt (#1517, `EXCLUDE`-Constraint mit `btree_gist`) ist
  als zu exotisch verworfen.

## Verworfene Alternativen

- `clock_timestamp()` für sich genommen — keine strenge Monotonie, Rückwärtssprünge möglich; „fast immer richtig" ist für eine nachweisrelevante Tabelle nicht genug.
- Prozesslokale Monotonie in der JVM — gilt nur für einen Prozess; zwei Instanzen erzeugen für dasselbe Objekt leere oder verschränkte Intervalle.
- Monotonie je Objekt aus der jeweiligen Historientabelle — gäbe die globale Ordnung über Objekte und Tabellen auf, auf der die Kombinationszustände beruhen (Entscheidung 2), und formulierte die Fachlogik je Tabelle ein zweites Mal.
- Sperre auf einer Zeile statt Advisory-Lock — hielte die Vergabe bis zum Ende jeder Transaktion an und machte langlaufende Abgleiche zum Engpass aller Rechteänderungen.
- Wert im `INSERT` erzeugen (Default oder Trigger) — die lückenlose Verkettung braucht den Wert in der JVM für das `UPDATE` der Vorgängerzeile.
- Grenze nur bei Gleichheit anheben — prüft gegen den gelesenen statt den vergebenen Wert, verteilt die Korrektheit über alle Aufrufstellen.
- Nichts tun („kürzer als ein Tick muss nicht rekonstruierbar sein") — eine Nachweiszusage, die von der Wirtsplattform abhängt, ist keine.

