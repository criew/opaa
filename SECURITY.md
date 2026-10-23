# Sicherheitsrichtlinie

> **English summary.** Please do not report security vulnerabilities through public issues, pull
> requests or discussions. Report them privately via GitHub's
> [Private Vulnerability Reporting](https://github.com/criew/opaa/security/advisories/new)
> (*Security → Report a vulnerability*). This is the only reporting channel; there is no e-mail
> address. We handle reported vulnerabilities on a best-effort basis and do not commit to fixed response
> times for them.
> Security fixes and fixes for critical bugs go to `main` and to every supported release line
> until its end of support: two releases a year (around June and December), the June release being
> an LTS line supported until the end of free support of its Spring Boot version (usually June 30 of
> the following year, about 12 months); any other release is supported until one month after the
> next release. Until maintenance branches exist (#3017), only the newest release line receives
> patch releases; older lines get fixes only through a newer line. Fixed vulnerabilities are published as
> GitHub Security Advisories, with a CVE where applicable. Reports may be written in English or
> German.

Diese Richtlinie beschreibt, wie Sicherheitslücken in OPAA gemeldet und behandelt werden. Das
Projekt ordnet sich als Open-Source-Steward nach Art. 24 des Cyber Resilience Act
(VO (EU) 2024/2847) ein
([Rollenklärung](docs/behoerden/06-informationssicherheit/rollenklaerung-cra.md)); diese Datei ist
zugleich seine dokumentierte Cybersicherheitsstrategie.

## Meldeweg

Sicherheitslücken bitte **nicht** über öffentliche Issues, Pull Requests oder Diskussionen melden,
sondern vertraulich über das
[Private Vulnerability Reporting](https://github.com/criew/opaa/security/advisories/new) von GitHub
(Reiter *Security → Report a vulnerability*). Das ist der einzige Meldeweg; eine E-Mail-Adresse
gibt es nicht. Die Meldung ist nur für die Maintainer sichtbar und wird dort auch weiter besprochen.

Hilfreich in einer Meldung sind:

- betroffene Version (Release-Tag oder Image-Digest) oder Commit,
- betroffene Komponente (Backend, Frontend, Helm-Chart, Container-Image),
- Schritte oder ein Beispiel, mit dem sich die Lücke nachvollziehen lässt,
- die vermutete Auswirkung.

Meldungen dürfen auf Deutsch oder Englisch verfasst sein.

Bereits öffentlich bekannte CVEs in Abhängigkeiten und Basis-Images erfasst der nächtliche Scan
([docs/cve-scanning.md](docs/cve-scanning.md)). Wer annimmt, dass ein solcher Befund in OPAA
tatsächlich ausnutzbar ist, meldet das trotzdem vertraulich über den Meldeweg oben.

## Unterstützte Versionen

Sicherheitskorrekturen erhalten der bewegliche Stand **`main`** und jede **unterstützte
Release-Linie** bis zu ihrem Supportende, als Patch-Release ohne neue Funktion; dieselben
Patch-Releases bringen auch Behebungen
[kritischer Fehler](docs/releases.md#kritische-fehler). OPAA erscheint
zweimal im Jahr (etwa Juni und Dezember); das Juni-Release ist eine LTS-Linie
([ADR-0054](docs/decisions/0054-release-zyklus-und-supportlinien.md)). Takt, Supportende jeder
Linie und Roadmap stehen in [docs/releases.md](docs/releases.md#supportlinien), wie
Sicherheitsupdates als Patch-Release erscheinen, unter
[Sicherheitsupdates für Releases](docs/releases.md#sicherheitsupdates-für-releases).

| Version | Sicherheitskorrekturen |
|---|---|
| `main` | ja |
| LTS-Linie (Juni-Release) | bis zum Ende des kostenlosen Supports ihrer Spring-Boot-Version, im Regelfall 30.06. des Folgejahres, also rund 12 Monate |
| normale Linie (Dezember-Release, erstes finales Release) | bis einen Monat nach dem nächsten Funktionsrelease |
| Linie nach ihrem Supportende, Vorab-Releases | nein |

Eine Korrektur im Code erreicht eine ältere Linie per Backport auf ihren Wartungsbranch. Solange
das noch nicht gebaut ist ([#3017](https://github.com/criew/opaa/issues/3017)), bekommt nur die
jüngste Linie Patch-Releases: Sicherheitsupdates der Basis-Images als neues Tag auf dem Commit ihres
letzten Release, eine Korrektur im Code nur, solange `main` seit diesem Release keine neue Funktion
trägt. Eine ältere Linie bekommt bis dahin weder eine Codekorrektur noch ein Patch-Release; die
Korrektur kommt nur mit einer neueren Linie.

## Ablauf

Extern gemeldete Sicherheitslücken bearbeitet das Projekt **nach bestem Bemühen** (best effort);
für sie werden keine festen Antwort- oder Behebungsfristen zugesagt. Für Befunde des eigenen
CVE-Scans gelten dagegen die Reaktionszeiten nach Schwere aus
[docs/cve-scanning.md](docs/cve-scanning.md#triage-von-image-alerts) und
[docs/releases.md](docs/releases.md#sicherheitsupdates-für-releases).

1. **Eingangsbestätigung** — Ein Maintainer bestätigt den Eingang im Advisory-Entwurf.
2. **Bewertung** — Die Maintainer prüfen, ob die Lücke nachvollziehbar ist, welche Versionen sie
   betrifft und wie schwer sie wiegt. Rückfragen laufen im Advisory-Entwurf.
3. **Behebung** — Die Korrektur entsteht vertraulich, bei Bedarf in einem privaten Fork des
   Advisory. Für jede betroffene unterstützte Linie folgt ein Patch-Release nach
   [docs/releases.md](docs/releases.md#sicherheitsupdates-für-releases).
4. **Veröffentlichung** — Mit der Korrektur wird die Lücke als
   [GitHub Security Advisory](https://github.com/criew/opaa/security/advisories) veröffentlicht,
   wo sinnvoll mit einer über GitHub vergebenen CVE-Kennung. Meldende werden auf Wunsch genannt.

Bis zur Veröffentlichung bitten wir, Details der Lücke nicht öffentlich zu machen.

Schwachstellen in Abhängigkeiten und Basis-Images, die ohne Meldung durch den CVE-Scan auffallen,
folgen der Triage in [docs/cve-scanning.md](docs/cve-scanning.md#triage-von-image-alerts).

## Meldepflichten nach dem Cyber Resilience Act

Welche Pflichten aus Art. 14 CRA (Meldung aktiv ausgenutzter Schwachstellen und schwerwiegender
Sicherheitsvorfälle) das Projekt in seiner Rolle als Open-Source-Steward treffen, klärt die
[Rollenklärung zum Cyber Resilience Act](docs/behoerden/06-informationssicherheit/rollenklaerung-cra.md#meldepflichten-nach-art-14-für-das-projekt).
Soweit eine Meldung danach geboten ist, gibt der Maintainer sie ab; eine Meldung über den Meldeweg
oben löst diese Prüfung aus. Das Projekt arbeitet mit den Marktüberwachungsbehörden zusammen, wenn
diese es anfragen.

## Außerhalb dieser Richtlinie

Ein Bug-Bounty-Programm gibt es nicht.
