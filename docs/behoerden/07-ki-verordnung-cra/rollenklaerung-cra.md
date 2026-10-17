---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Rollenklärung zum Cyber Resilience Act

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Der Cyber Resilience Act (VO (EU) 2024/2847, im Folgenden CRA) verpflichtet die Wirtschaftsakteure,
die Produkte mit digitalen Elementen bereitstellen. Diese Seite ordnet die drei Beteiligten beim
Einsatz von OPAA ein: das Projekt, einen Dienstleister, der OPAA bereitstellt, und die Behörde. Sie
nennt die Nachweise, die das Projekt heute liefert, und was eine Behörde bei der Beschaffung fragen
kann.

## Quellen und Prüfstand

Grundlage ist die Rechtsrecherche vom 13.10.2026 (erster Kommentar in Epic
[#2542](https://github.com/criew/opaa/issues/2542)) und der Beschluss des Maintainers vom 13.10.2026
zur Einordnung des Projekts. Artikelangaben beziehen sich auf den Normtext
([ELI 2024/2847](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/deu)); Aussagen, die nur aus einem
Fachbeitrag stammen, sind als solche gekennzeichnet.

## Fristen

| Datum | Was gilt | Fundstelle |
|---|---|---|
| 11.9.2026 | Meldepflichten für aktiv ausgenutzte Schwachstellen und schwerwiegende Sicherheitsvorfälle (Art. 14) | Art. 71 Abs. 2 CRA |
| 11.12.2027 | Der CRA gilt vollständig | Art. 71 Abs. 2 CRA |

## Die Rollen im Überblick

| Beteiligte | Rolle nach CRA | Begriff | Pflichten im Kern |
|---|---|---|---|
| **Projekt OPAA** | Open-Source-Steward | Art. 3 Nr. 14, Art. 24 | Cybersicherheitsstrategie, Zusammenarbeit mit den Marktüberwachungsbehörden, Meldepflichten nur im Umfang des Art. 24 Abs. 3 |
| **Dienstleister**, der OPAA kommerziell als Produkt bereitstellt (gepackt, als Appliance, als bezahlte Distribution) | Hersteller (Fachbeitrag: [OpenSSF](https://policy.openssf.org/CRA/stewards-one-pager.html)) | Art. 3 Nr. 13 | Pflichten des Herstellers, insbesondere Art. 13 und Art. 14; ab 11.12.2027 auch Konformitätsbewertung und CE-Kennzeichnung |
| **Dienstleister**, der OPAA nur als Dienst betreibt (reines SaaS) | grundsätzlich keine Rolle nach CRA (Fachbeitrag: [OpenSSF](https://policy.openssf.org/CRA/stewards-one-pager.html)) | — | — |
| **Behörde**, die OPAA für sich betreibt | Betreiberin; der CRA kennt diese Rolle nicht, seine Pflichten treffen Wirtschaftsakteure (Art. 3 Nr. 12) | — | für den Eigenbetrieb nennt die Recherche keine Pflichten; die Behörde profitiert von den Pflichten der anderen |

Nicht-kommerzielle Open-Source-Software fällt nach der Recherche nicht unter die Herstellerpflichten.
Gibt eine Behörde OPAA, verändert oder unverändert, an andere Stellen weiter, prüft sie ihre Rolle
gesondert; diese Seite deckt den Fall nicht ab.

## Das Projekt als Open-Source-Steward

Das Projekt ordnet sich als Open-Source-Steward nach Art. 24 CRA ein (Beschluss des Maintainers vom
13.10.2026). Die Einordnung selbst ist entschieden; ihre rechtliche Prüfung liegt außerhalb dieser
Vorlage.

**Hinweis:** Art. 3 Nr. 14 CRA setzt für den Open-Source-Steward eine juristische Person voraus. Wie
das Projekt diese Anforderung erfüllt, ist nicht Gegenstand dieser Vorlage. Behörden sollten das bei
der Vergabe erfragen.

| Pflicht des Stewards | Fundstelle | Umsetzung im Projekt |
|---|---|---|
| Dokumentierte Cybersicherheitsstrategie, die die Entwicklung eines sicheren Produkts und den wirksamen Umgang mit Schwachstellen fördert | Art. 24 Abs. 1 | [`SECURITY.md`](../../../SECURITY.md) ist die dokumentierte Strategie: Meldeweg, unterstützte Versionen, Ablauf bis zum Security Advisory |
| Zusammenarbeit mit den Marktüberwachungsbehörden auf deren Anfrage | Art. 24 Abs. 2 | zugesagt in [`SECURITY.md`, Abschnitt „Meldepflichten nach dem Cyber Resilience Act“](../../../SECURITY.md#meldepflichten-nach-dem-cyber-resilience-act) |
| Meldung aktiv ausgenutzter Schwachstellen (Art. 14 Abs. 1), soweit der Steward an der Entwicklung beteiligt ist | Art. 24 Abs. 3 | siehe unten |
| Meldung schwerwiegender Sicherheitsvorfälle (Art. 14 Abs. 3 und 8), soweit sie Netz- und Informationssysteme betreffen, die der Steward für die Entwicklung bereitstellt | Art. 24 Abs. 3 | siehe unten |

### Meldepflichten nach Art. 14 für das Projekt

Art. 24 Abs. 3 CRA wendet die Meldepflichten des Art. 14 nur in begrenztem Umfang auf Stewards an:

- **Aktiv ausgenutzte Schwachstellen (Art. 14 Abs. 1):** soweit das Projekt an der Entwicklung
  beteiligt ist. Das Projekt entwickelt OPAA selbst und plant die Meldepflicht für Schwachstellen in
  OPAA deshalb ein.
- **Schwerwiegende Sicherheitsvorfälle (Art. 14 Abs. 3 und 8):** nur, soweit ein Vorfall die
  Systeme betrifft, die das Projekt für die Entwicklung bereitstellt, etwa Repository, CI und
  Image-Registry.

Wie eine Meldung im Projekt ankommt und wer sie abgibt, regelt
[`SECURITY.md`](../../../SECURITY.md#meldeweg): Eine Meldung über den vertraulichen Meldeweg löst die
Prüfung aus, ob eine Meldung nach Art. 14 geboten ist; abgegeben wird sie vom Maintainer.

## Der Dienstleister als Hersteller

Wer OPAA im Rahmen einer Geschäftstätigkeit als Produkt bereitstellt, ist nach der Recherche
Hersteller (Fachbeitrag: [OpenSSF](https://policy.openssf.org/CRA/stewards-one-pager.html)). Für die Behörde als Auftraggeberin wichtig:

- **Meldepflichten seit 11.9.2026 (Art. 14):** Frühwarnung binnen 24 Stunden, Meldung binnen 72
  Stunden und Abschlussbericht für aktiv ausgenutzte Schwachstellen (Art. 14 Abs. 2) und
  schwerwiegende Sicherheitsvorfälle (Art. 14 Abs. 4). Der Hersteller informiert außerdem die
  betroffenen Nutzer (Art. 14 Abs. 8).
- **Schwachstellen in Komponenten (ab 11.12.2027):** Findet der Hersteller eine Schwachstelle in einer eingebauten
  Komponente, auch einer quelloffenen, meldet er sie dem, der die Komponente pflegt (Art. 13 Abs. 6).
  Für OPAA ist das der Meldeweg in [`SECURITY.md`](../../../SECURITY.md#meldeweg).
- **Ab 11.12.2027** gelten die übrigen Herstellerpflichten, darunter die grundlegenden
  Anforderungen nach Anhang I, die Konformitätsbewertung und die CE-Kennzeichnung.

Was die Behörde vom Dienstleister verlangt, legt sie in der Vergabe fest
([Anforderungskatalog](../03-vergabe/anforderungskatalog.md), Baustein L-08). Mögliche Fragen:

1. Stellen Sie OPAA als Produkt im Sinne des CRA bereit, und in welcher Rolle?
2. Wie erfüllen Sie die Meldepflichten nach Art. 14, und wie erfahren wir von einer Meldung?
3. Wie melden Sie Schwachstellen, die Sie in OPAA finden, an das Projekt (Art. 13 Abs. 6)?
4. Welche OPAA-Version liefern Sie aus, wie lange pflegen Sie sie, und wie verhält sich das zu den
   [unterstützten Versionen des Projekts](../../../SECURITY.md#unterstützte-versionen)?
5. Liefern Sie eine SBOM zu Ihrem Produkt, und wie verhält sie sich zu den Attestierungen des
   Projekts?

## Die Behörde als Betreiberin

Für den Eigenbetrieb nennt die Recherche keine Pflichten der Behörde aus dem CRA. Sie nutzt, was
Projekt und Hersteller liefern müssen, und bleibt für ihre Informationssicherheit nach den für sie
geltenden Regeln verantwortlich (Vorlagen dazu entstehen in
[#2567](https://github.com/criew/opaa/issues/2567)). Wichtig für den Betrieb:

- **Sicherheitslücken melden:** Findet die Behörde eine Schwachstelle in OPAA, meldet sie sie
  vertraulich über den [Meldeweg](../../../SECURITY.md#meldeweg), nicht in einem öffentlichen Issue.
- **Sicherheitskorrekturen erhalten** nur das jüngste Release und `main`
  ([unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen)); wie sie als Patch-Release
  erscheinen, steht in [Sicherheitsupdates für Releases](../../releases.md#sicherheitsupdates-für-releases).
- **Veröffentlichte Lücken** stehen als
  [GitHub Security Advisory](https://github.com/criew/opaa/security/advisories) bereit.

## Vorhandene Nachweise des Projekts

| Nachweis | Was er belegt | Fundort |
|---|---|---|
| Sicherheitsrichtlinie und Cybersicherheitsstrategie | Meldeweg, unterstützte Versionen, Ablauf, Haltung zu Art. 14 | [`SECURITY.md`](../../../SECURITY.md) |
| Software-Stückliste (SBOM) | Bestandteile jedes Release-Images als Attestierung, CycloneDX-SBOMs je Ökosystem | [docs/sbom.md](../../sbom.md) |
| Schwachstellenerkennung | Dependabot-Alerts, nächtlicher Image-Scan, Triage nach Schwere | [docs/cve-scanning.md](../../cve-scanning.md) |
| Release- und Patch-Verfahren | unveränderliche Release-Images, Patch-Releases für Sicherheitsupdates | [docs/releases.md](../../releases.md) |

Die Nachweise belegen das Verfahren des Projekts; sie sind keine Konformitätserklärung und keine
CE-Kennzeichnung.
