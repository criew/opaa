# ADR-0054: Release-Zyklus und Supportlinien

## Status

Akzeptiert · zuletzt geändert 2026-10-23

## Kontext

Bisher entstehen Releases nur von Commits auf `main`, und nur das jüngste Release bekommt
Sicherheitskorrekturen. Ein Fix im Code erscheint deshalb zusammen mit allen neuen Funktionen seit
dem letzten Release, sobald `main` eine trägt. Eine Behörde kann so kein Sicherheitsupdate sofort
einspielen, ohne zugleich beteiligungspflichtige Änderungen zu übernehmen; neue Funktionen kommen
nicht standardmäßig ausgeschaltet (Beschluss 1 in [Behördenunterlagen](../features/behoerdenunterlagen.md#beschlüsse-des-maintainers-13102026)).
Die Muster-Dienstvereinbarung, Pflegeverträge nach EVB-IT und Markterkundungen brauchen feste
Supportzeiträume (Beschluss 2, #2575).

Technische Basis des Backends ist Spring Boot. Es erscheint zweimal im Jahr als Minor-Version (Mai
und November) und unterstützt jede Minor-Version kostenlos mindestens 12 Monate; längeren Support
gibt es nur kommerziell ([Spring Boot Wiki, Supported Versions](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions)).
Das Ende des kostenlosen Supports jeder Minor-Version nennt die
[Projektseite](https://spring.io/projects/spring-boot#support), für 4.1 etwa den 31.07.2027.
Minor- und Major-Anhebungen von Drittbibliotheken kommen bei Spring Boot nur mit einer neuen
Minor-Version, Patch-Versionen nur mit Patch-Anhebungen (ebenda). Das Team besteht aus einem
Maintainer und Agenten; jede zusätzlich gepflegte Linie kostet Backports, CI-Läufe und Scans.

## Entscheidung

### 1. Zwei Funktionsreleases im Jahr

OPAA erscheint zweimal im Jahr als finales `X.Y.0`, etwa im Juni und im Dezember, jeweils 4–6 Wochen
nach der Minor-Version von Spring Boot und auf ihr. Neue Funktionen erscheinen nur so; dazwischen
gibt es Patch-Releases der unterstützten Linien und Vorab-Releases. Wer neue Funktionen früher
braucht, folgt `main`. Der Abstand zu Spring Boot lässt Zeit für die Anhebung und die Fehler der
ersten Patch-Version, ohne den kostenlosen Support der Basis anzuknabbern.

### 2. Eine Supportlinie je Funktionsrelease

Jedes finale `X.Y.0` eröffnet die Linie `X.Y`. Ihre Patch-Releases `X.Y.Z` bringen nur
Sicherheitskorrekturen: im eigenen Code, in Abhängigkeiten innerhalb der Patch-Versionen und in den
Basis-Images. Keine neue Funktion, kein Bruch nach [Versionsnummern](../releases.md#versionsnummern),
keine neue Minor-Version von Spring Boot. Behebungen kritischer Fehler (Datenverlust, Start
scheitert) können folgen, ohne Zusage. Damit bleibt jedes Patch-Release ein Update, das eine
Behörde ohne Beteiligungsverfahren sofort einspielen kann.

### 3. Normales Release: bis einen Monat nach dem Nachfolger

Ein normales Release bekommt Sicherheitsupdates bis einen Monat nach dem nächsten finalen
Funktionsrelease. Der Monat ist die Übergangszeit für das Update auf den Nachfolger.

### 4. Das Juni-Release ist LTS

Das Juni-Release ist eine LTS-Linie. Ihr Supportende ist das Ende des kostenlosen Supports ihrer
Spring-Boot-Minor-Version, wie es die Projektseite bei Erscheinen nennt. Erscheint das Release im
Takt, sind das mindestens 12 Monate, und die Linie überlappt etwa einen Monat mit dem nächsten LTS.
Länger trägt keine LTS-Linie: Danach müsste das Projekt Fixes in Spring selbst zurückportieren oder
Spring Boot in der Linie anheben, was Entscheidung 2 bricht. Juni statt Dezember ist eine Festlegung
für die Planbarkeit; die Kopplung trüge beide.

### 5. Wechsel von LTS zu LTS

Wer nur LTS-Linien betreibt, aktualisiert einmal im Jahr auf das nächste Juni-Release. Das
Update-Paket eines LTS-Release enthält dafür zusätzlich das kumulierte Delta zum jüngsten
Patch-Release der vorigen LTS-Linie. Das Delta zum vorigen finalen Release bleibt für alle
Releases; ein Patch-Release vergleicht so mit dem vorigen Release seiner Linie.

### 6. Supportende angekündigt, nie vorgezogen

Das Supportende jeder Linie steht im Abschnitt [Roadmap](../releases.md#roadmap) von
`docs/releases.md` und in den Release-Notizen: bei einer LTS-Linie mit Erscheinen, bei einer
normalen Linie mit Erscheinen ihres Nachfolgers, also mindestens einen Monat vorher. Ein
angekündigtes Supportende wird nie vorgezogen. Die Roadmap nennt die geplanten Linien relativ zum
Takt von Spring Boot, ohne Funktionsversprechen.

### 7. Wartungsbranches `release/X.Y`

Eine Linie wird auf dem Branch `release/X.Y` gepflegt, abgezweigt vom Commit des Tags `vX.Y.0`.
Eine Korrektur landet zuerst auf `main` und kommt per PR mit `git cherry-pick -x` auf jeden
unterstützten Branch, den sie betrifft; nur wo `main` den Code nicht mehr hat, entsteht sie direkt
auf dem Branch. Patch-Tags liegen auf `release/X.Y`. Rulesets, CI, Renovate (nur Patch-Updates und
Basis-Images) und der nächtliche CVE-Scan behandeln jeden unterstützten Branch bzw. das jüngste
Patch-Release jeder unterstützten Linie wie `main` bzw. heute das jüngste Release. Ein Branch, dessen
Linie ausgelaufen ist, bleibt bestehen und wird nicht mehr gepflegt.

## Konsequenzen

**Einfacher:** Eine Behörde kann Sicherheitsupdates sofort einspielen und Funktionsupdates planen,
LTS-Betreiber einmal im Jahr. Pflegeverträge, Dienstvereinbarung und Markterkundung haben feste
Zeiträume. Die Laufzeit hängt an einem öffentlich dokumentierten Datum der Basis, nicht an einer
Zusage des Projekts über seine Kräfte.

**Schwieriger:** Meist laufen zwei Linien nebeneinander, im Übergangsmonat nach einem Juni-Release
drei. Jeder Sicherheitsfix im Code braucht je Linie einen Backport mit eigenem CI-Lauf; je Linie
kommen Renovate-PRs und vier Scans pro Nacht (zwei Images, zwei Plattformen) hinzu. Release-Workflow,
Rulesets, CVE-Scan, Renovate und Update-Paket brauchen Umbauten (#3017–#3020). Bis `release/X.Y`
gebaut ist (#3017),
erscheint ein Fix im Code für ein Release nur, solange `main` keine neue Funktion trägt.

**Restrisiken:** Abhängigkeiten außerhalb der Versionsverwaltung von Spring Boot (Spring AI, Tika,
Frontend-Bibliotheken, Basis-Images) haben eigene Supportzeiträume. Gibt es einen Fix nur mit
neuer Minor-Version, wird sie in der Linie angehoben, wenn das kein Bruch ist; sonst entscheidet der
Maintainer im Einzelfall und nennt das in den Release-Notizen. Verspätet sich ein Juni-Release,
verkürzt sich die Laufzeit dieser LTS-Linie und die Überlappung mit der vorigen, nicht die Grenze.

## Verworfene Alternativen

- Nur das jüngste Release pflegen — Sicherheitsfixes kämen mit neuen Funktionen; Beschluss 2 verlangt feste Zeiträume.
- LTS 24 Monate mit Anhebung von Spring Boot in der Linie — bringt Minor-Anhebungen von Spring und Drittbibliotheken, also Verhaltensänderungen, in eine Linie, die sie ausschließt.
- LTS 24 Monate auf derselben Spring-Boot-Version — verlangt nach 12 Monaten eigene Backports in Spring oder eine kommerzielle Subscription.
- Kommerzielle Verlängerung über den Spring-Support — Kosten und Vertragsbindung, die das Projekt nicht trägt; ein Dienstleister kann sie für seine Kunden abschließen.
- Jedes Release als LTS — doppelte Zahl paralleler Linien bei gleichem Team.

## Offen

- Ob GitHub-Meilensteine die Roadmap in `docs/releases.md` ergänzen (#3021).
