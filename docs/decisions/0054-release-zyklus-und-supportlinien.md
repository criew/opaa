# ADR-0054: Release-Zyklus und Supportlinien

## Status

Akzeptiert · zuletzt geändert 2026-10-24

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
[Projektseite](https://spring.io/projects/spring-boot#support): für eine im Mai erschienene Version
den 30.06. des Folgejahres (3.1, 3.3, 3.5), für das erst im Juni erschienene 4.1 den 31.07.2027.
Minor- und Major-Anhebungen von Drittbibliotheken kommen bei Spring Boot nur mit einer neuen
Minor-Version, Patch-Versionen nur mit Patch-Anhebungen (ebenda). Das Team besteht aus einem
Maintainer und Agenten; jede zusätzlich gepflegte Linie kostet Backports, CI-Läufe und Scans.

## Entscheidung

### 1. Zwei Funktionsreleases im Jahr

OPAA erscheint zweimal im Jahr als finales `X.Y.0`, etwa im Juni und im Dezember, jeweils 4–6 Wochen
nach der Minor-Version von Spring Boot und auf ihr; das Juni-Release spätestens zum Supportende der
laufenden LTS-Linie (Entscheidung 4). Neue Funktionen erscheinen nur so; dazwischen
gibt es Patch-Releases der unterstützten Linien und Vorab-Releases. Wer neue Funktionen früher
braucht, folgt `main`. Der Abstand zu Spring Boot lässt Zeit für die Anhebung und die Fehler der
ersten Patch-Version, ohne den kostenlosen Support der Basis anzuknabbern.

### 2. Eine Supportlinie je Funktionsrelease

Jedes finale `X.Y.0` eröffnet die Linie `X.Y`. Ihre Patch-Releases `X.Y.Z` bringen
Sicherheitskorrekturen (im eigenen Code, in Abhängigkeiten innerhalb der Patch-Versionen und in den
Basis-Images) und Behebungen kritischer Fehler, sonst nichts: keine neue Funktion, kein Bruch nach
[Versionsnummern](../releases.md#versionsnummern), keine neue Minor-Version von Spring Boot.
Kritisch ist ein Fehler, der ohne zumutbaren Workaround zu Datenverlust oder verfälschten Daten,
zum Ausfall von OPAA oder einer Kernfunktion (Anmeldung, Suche, Chat, Indizierung) oder zu einer
falschen Auswertung von Rechten führt. Ob ein Fehler kritisch ist, entscheidet der Maintainer und
hält es am Issue fest. Damit bleibt jedes Patch-Release ein Update, das eine Behörde ohne
Beteiligungsverfahren sofort einspielen kann.

### 3. Normales Release: bis einen Monat nach dem Nachfolger

Ein normales Release bekommt Patch-Releases nach Entscheidung 2 bis einen Monat nach dem nächsten finalen
Funktionsrelease. Der Monat ist die Übergangszeit für das Update auf den Nachfolger.

### 4. Das Juni-Release ist LTS

Das Juni-Release ist eine LTS-Linie. Ihr Supportende ist das Ende des kostenlosen Supports ihrer
Spring-Boot-Minor-Version, wie es die Projektseite bei Erscheinen nennt. Im Regelfall ist das der
30.06. des Folgejahres, bei einem Release im Takt also rund 12 Monate. Das nächste LTS erscheint
spätestens zu diesem Supportende, auch wenn die 4–6 Wochen nach Spring Boot dann noch nicht um sind;
so überlappen die LTS-Linien immer, wenn auch höchstens wenige Wochen. Machbar ist das, weil der
Release-Kandidat spätestens zwei Wochen nach Spring Boot erscheint (Entscheidung 5). Das Datum
steht bei Erscheinen fest und liegt nie hinter dem Spring-Support. Länger trägt keine LTS-Linie:
Danach müsste das Projekt Fixes in Spring selbst zurückportieren oder Spring Boot in der Linie
anheben, was Entscheidung 2 bricht. Juni statt Dezember ist eine Festlegung für die Planbarkeit;
die Kopplung trüge beide.

### 5. Wechsel von LTS zu LTS

Wer nur LTS-Linien betreibt, aktualisiert einmal im Jahr auf das nächste Juni-Release. Weil die
Linien sich nur knapp überlappen, erscheint das nächste LTS so früh wie möglich als
Release-Kandidat: sobald OPAA auf der neuen Spring-Boot-Minor-Version läuft, spätestens zwei Wochen
nach deren Erscheinen. Der Kandidat trägt das vollständige Update-Paket; auf seiner Grundlage
beginnt die Behörde Beteiligung und Vorbereitung, das finale Release holt sie nur noch nach. Vom
Kandidaten bis zum finalen Release kommen nur Korrekturen hinzu. Das Update-Paket eines
LTS-Release und seiner Kandidaten enthält zusätzlich das kumulierte Delta zum jüngsten
Patch-Release der laufenden LTS-Linie. Das Delta zum vorigen finalen Release bleibt für alle
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

**Schwieriger:** Meist laufen zwei Linien nebeneinander, in den Wochen nach einem Juni-Release
drei. Der Wechsel von LTS zu LTS hat kaum Übergangszeit; eine Behörde muss ihn anhand des
Release-Kandidaten vorbereiten, damit sie das finale Release vor dem Supportende einspielen kann. Jeder Sicherheitsfix und jede Behebung eines kritischen Fehlers im Code braucht je Linie einen Backport mit eigenem CI-Lauf; je Linie
kommen Renovate-PRs und vier Scans pro Nacht (zwei Images, zwei Plattformen) hinzu. CVE-Scan
braucht einen Umbau (#3018). Renovate pflegt den Wartungsbranch einer Linie
bis zu ihrem Supportende, das in einer Liste im Repository steht. Der Release-Workflow nimmt Tags von
`main` und vom Wartungsbranch ihrer Linie an; ein Patch-Release einer älteren Linie wird auf GitHub
nicht „Latest“, damit die jüngste Linie ihre Markierung behält.

**Restrisiken:** Abhängigkeiten außerhalb der Versionsverwaltung von Spring Boot (Spring AI, Tika,
Frontend-Bibliotheken, Basis-Images) haben eigene Supportzeiträume. Gibt es einen Fix nur mit
neuer Minor-Version, wird sie in der Linie angehoben, wenn das kein Bruch ist; sonst entscheidet der
Maintainer im Einzelfall und nennt das in den Release-Notizen. Erscheint Spring Boot spät im Mai,
bleiben bis zum Supportende der laufenden LTS-Linie nur rund vier Wochen für das finale LTS und
kaum Überlappung.

## Verworfene Alternativen

- Nur das jüngste Release pflegen — Sicherheitsfixes kämen mit neuen Funktionen; Beschluss 2 verlangt feste Zeiträume.
- LTS 24 Monate mit Anhebung von Spring Boot in der Linie — bringt Minor-Anhebungen von Spring und Drittbibliotheken, also Verhaltensänderungen, in eine Linie, die sie ausschließt.
- LTS 24 Monate auf derselben Spring-Boot-Version — verlangt nach 12 Monaten eigene Backports in Spring oder eine kommerzielle Subscription.
- LTS 15 oder 18 Monate mit Anhebung von Spring Boot gegen Ende der Linie — schafft Übergangszeit, bringt aber dieselben Verhaltensänderungen in die Linie; die Übergangszeit entsteht stattdessen durch den frühen Release-Kandidaten.
- Kommerzielle Verlängerung über den Spring-Support — Kosten und Vertragsbindung, die das Projekt nicht trägt; ein Dienstleister kann sie für seine Kunden abschließen.
- Jedes Release als LTS — doppelte Zahl paralleler Linien bei gleichem Team.

## Offen

- Ob GitHub-Meilensteine die Roadmap in `docs/releases.md` ergänzen (#3021).
