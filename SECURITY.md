# Sicherheitsrichtlinie

> **English summary.** Please do not report security vulnerabilities through public issues, pull
> requests or discussions. Report them privately via GitHub's
> [Private Vulnerability Reporting](https://github.com/criew/opaa/security/advisories/new)
> (*Security → Report a vulnerability*). This is the only reporting channel; there is no e-mail
> address. We handle reports on a best-effort basis and do not commit to fixed response times.
> Only the latest release and `main` receive security fixes. Fixed vulnerabilities are published as
> GitHub Security Advisories, with a CVE where applicable. Reports may be written in English or
> German.

Diese Richtlinie beschreibt, wie Sicherheitslücken in OPAA gemeldet und behandelt werden. Das
Projekt ordnet sich als Open-Source-Steward nach Art. 24 des Cyber Resilience Act
(VO (EU) 2024/2847) ein ([#2568](https://github.com/criew/opaa/issues/2568)); diese Datei ist zugleich seine dokumentierte
Cybersicherheitsstrategie.

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

Sicherheitskorrekturen erhalten nur das **jüngste Release** und der bewegliche Stand **`main`**.
Eine ältere Release-Linie wird nicht nachgepflegt; wie Sicherheitsupdates als Patch-Release
erscheinen, steht in [docs/releases.md](docs/releases.md#sicherheitsupdates-für-releases).

| Version | Sicherheitskorrekturen |
|---|---|
| jüngstes Release `X.Y.Z` | ja |
| `main` | ja |
| ältere Releases | nein |

## Ablauf

Das Projekt bearbeitet Meldungen **nach bestem Bemühen** (best effort). Feste Antwort- oder
Behebungsfristen werden nicht zugesagt.

1. **Eingangsbestätigung** — Ein Maintainer bestätigt den Eingang im Advisory-Entwurf.
2. **Bewertung** — Die Maintainer prüfen, ob die Lücke nachvollziehbar ist, welche Versionen sie
   betrifft und wie schwer sie wiegt. Rückfragen laufen im Advisory-Entwurf.
3. **Behebung** — Die Korrektur entsteht vertraulich, bei Bedarf in einem privaten Fork des
   Advisory. Für das jüngste Release folgt ein Patch-Release nach
   [docs/releases.md](docs/releases.md#sicherheitsupdates-für-releases).
4. **Veröffentlichung** — Mit der Korrektur wird die Lücke als
   [GitHub Security Advisory](https://github.com/criew/opaa/security/advisories) veröffentlicht,
   wo sinnvoll mit einer über GitHub vergebenen CVE-Kennung. Meldende werden auf Wunsch genannt.

Bis zur Veröffentlichung bitten wir, Details der Lücke nicht öffentlich zu machen.

Schwachstellen in Abhängigkeiten und Basis-Images, die ohne Meldung durch den CVE-Scan auffallen,
folgen der Triage in [docs/cve-scanning.md](docs/cve-scanning.md#triage-von-image-alerts).

## Meldepflichten nach dem Cyber Resilience Act

Welche Pflichten aus Art. 14 CRA (Meldung aktiv ausgenutzter Schwachstellen und schwerwiegender
Sicherheitsvorfälle) das Projekt in seiner Rolle als Open-Source-Steward treffen, hält [#2568](https://github.com/criew/opaa/issues/2568) fest.
Soweit eine Meldung danach geboten ist, gibt der Maintainer sie ab; eine Meldung über den Meldeweg
oben löst diese Prüfung aus. Das Projekt arbeitet mit den Marktüberwachungsbehörden zusammen, wenn
diese es anfragen.

## Außerhalb dieser Richtlinie

Ein Bug-Bounty-Programm gibt es nicht.
