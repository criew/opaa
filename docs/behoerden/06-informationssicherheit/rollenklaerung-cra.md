---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Cyber Resilience Act

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden, die OPAA selbst betreiben oder von einem Dienstleister beziehen. Wer
OPAA an andere Stellen weitergibt, verändert oder unverändert, prüft seine Rolle gesondert.

Der Cyber Resilience Act (CRA) ist eine EU-Verordnung über die Sicherheit von Software und Geräten.
Er verpflichtet *Wirtschaftsakteure*, also vor allem Hersteller, die solche Produkte auf den Markt
bringen; eine Behörde, die OPAA für sich betreibt, trifft er nach der Rechtsrecherche des Projekts
nicht. Für sie zählt, welche Pflichten das Projekt und ein Dienstleister haben und was sie in der
Vergabe fragt. Die Meldepflichten gelten seit dem 11.9.2026, der ganze CRA ab dem 11.12.2027.

> **Beispiel.** Ein Landratsamt beauftragt einen Dienstleister, der OPAA als gepacktes Produkt
> liefert. In der Vergabe fragt die Projektleitung, wie er die Meldepflichten eines Herstellers
> erfüllt und wie das Amt von einer Meldung erfährt. Eine Lücke, die die eigene IT später findet,
> meldet das Amt vertraulich an das Projekt.

## Die Rollen im Überblick

| Beteiligte | Rolle nach dem CRA | Pflichten im Kern |
|---|---|---|
| **Projekt OPAA** | Open-Source-Steward: eine Stelle, die quelloffene Software dauerhaft pflegt, ohne sie als Hersteller zu verkaufen | Cybersicherheitsstrategie, Zusammenarbeit mit den Marktüberwachungsbehörden, begrenzte Meldepflichten |
| **Dienstleister**, der OPAA kommerziell als Produkt bereitstellt (gepackt, als Appliance, als bezahlte Distribution) | Hersteller | volle Herstellerpflichten, darunter Meldepflichten; ab 11.12.2027 auch Konformitätsbewertung und CE-Kennzeichnung |
| **Dienstleister**, der OPAA nur als Dienst betreibt (reines SaaS) | grundsätzlich keine | — |
| **Behörde**, die OPAA für sich betreibt | Betreiberin; der CRA kennt diese Rolle nicht | nach der Recherche keine Pflichten aus dem CRA; sie profitiert von den Pflichten der anderen |

## Was OPAA liefert

- **Cybersicherheitsstrategie:** [`SECURITY.md`](../../../SECURITY.md) mit Meldeweg, unterstützten
  Versionen und Ablauf bis zum Security Advisory; dort ist auch die Zusammenarbeit mit den
  Marktüberwachungsbehörden zugesagt.
- **Nachweise des Verfahrens:** Software-Stückliste, Schwachstellenerkennung und Patch-Releases
  ([Sicherheitsnachweise](sicherheitsnachweise.md)). Sie sind keine Konformitätserklärung und keine
  CE-Kennzeichnung.

<a id="meldepflichten-nach-art-14-für-das-projekt"></a>

### Meldepflichten des Projekts

- **Aktiv ausgenutzte Schwachstellen:** Das Projekt entwickelt OPAA selbst und meldet solche
  Schwachstellen in OPAA deshalb.
- **Schwerwiegende Sicherheitsvorfälle:** nur, soweit sie die Systeme betreffen, die das Projekt für
  die Entwicklung bereitstellt, etwa Repository, CI und Image-Registry.

Eine Meldung über den vertraulichen [Meldeweg](../../../SECURITY.md#meldeweg) löst die Prüfung aus,
ob eine Meldung nach dem CRA geboten ist; abgegeben wird sie vom Maintainer.

## Was die Behörde tun muss

1. **Rolle des Dienstleisters klären** und die Anforderungen in der Vergabe festlegen
   ([Anforderungskatalog](../03-vergabe/anforderungskatalog.md), Baustein L-08). Mögliche Fragen:
   - Stellen Sie OPAA als Produkt im Sinne des CRA bereit, und in welcher Rolle?
   - Wie erfüllen Sie die Meldepflichten (Frühwarnung binnen 24 Stunden, Meldung binnen 72
     Stunden), und wie erfahren wir von einer Meldung?
   - Welche OPAA-Version liefern Sie, wie lange pflegen Sie sie, und melden Sie gefundene
     Schwachstellen an das Projekt?
2. **Lücken vertraulich melden** über den [Meldeweg](../../../SECURITY.md#meldeweg), nicht in einem
   öffentlichen Issue.
3. **Updates einspielen und [Security Advisories](https://github.com/criew/opaa/security/advisories)
   verfolgen:** Sicherheitskorrekturen erhalten nur das jüngste Release und `main`
   ([unterstützte Versionen](../../../SECURITY.md#unterstützte-versionen)).

## Gut zu wissen

- Die Einordnung des Projekts als Open-Source-Steward hat der Maintainer am 13.10.2026 beschlossen;
  rechtlich geprüft ist sie nicht. Der CRA setzt dafür eine juristische Person voraus; wie das
  Projekt das erfüllt, sollte die Behörde in der Vergabe erfragen.
- Die Einordnung der Dienstleister stützt sich auf einen Fachbeitrag, nicht auf den Normtext.
- Für ihre eigene Informationssicherheit bleibt die Behörde verantwortlich
  ([IT-Grundschutz](grundschutz-modellierung.md)).

## Zum Nachlesen

**Arbeitshilfen:**

- [OpenSSF: CRA und Open-Source-Stewards](https://policy.openssf.org/CRA/stewards-one-pager.html):
  kurze Übersicht, wer nach dem CRA welche Rolle hat (englisch).

**Rechtsgrundlagen** ([VO (EU) 2024/2847](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/deu)):

| Worum es geht | Fundstelle |
|---|---|
| Meldepflichten ab 11.9.2026, vollständige Geltung ab 11.12.2027 | Art. 71 Abs. 2 [CRA](https://eur-lex.europa.eu/eli/reg/2024/2847/oj/deu) |
| Wirtschaftsakteur, Hersteller, Open-Source-Steward | Art. 3 Nr. 12, 13 und 14 CRA |
| Pflichten des Open-Source-Stewards, Meldepflichten nur im begrenzten Umfang | Art. 24 Abs. 1 bis 3 CRA |
| Meldepflichten des Herstellers: Fristen, Information der Nutzer | Art. 14 Abs. 1 bis 4 und 8 CRA |
| Hersteller meldet Schwachstellen in Komponenten an deren Pfleger | Art. 13 Abs. 6 CRA |
| Grundlegende Anforderungen an Produkte | Anhang I CRA |
| Rolle von SaaS-Betreiber und kommerziellem Anbieter | Fachbeitrag [OpenSSF](https://policy.openssf.org/CRA/stewards-one-pager.html) |
