---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Reifegrad und Referenzen

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden, die in der Markterkundung einschätzen, wie weit OPAA für den eigenen
Einsatz ist.

Reifegrad meint hier, wie belastbar die Software für einen Einsatz ist: welche Versionen es gibt,
was laufend geprüft wird und was noch fehlt. Diese Seite nennt nur, was sich im Projekt belegen
lässt. Wie die Behörde den Reifegrad für ihr Vorhaben bewertet, entscheidet sie selbst.

## Was OPAA liefert

| Merkmal | Beleg |
|---|---|
| Versionen | Vorab-Releases und finale Releases mit Versionsnummer, unveränderlich gebaut; solange die Hauptversion `0` ist, kann ein Funktionsrelease Handarbeit verlangen ([Versionen](../../handbuch/betrieb/releases-und-nachweise.md#versionen), [Release-Seite](https://github.com/criew/opaa/releases)) |
| Tests | automatische Tests von Backend und Frontend an jedem Pull Request, dazu browserbasierte End-to-End-Tests nach jeder Übernahme in den Hauptstand und nächtlich ([Build und CI](../../entwicklung/build-und-ci.md)) |
| Suchqualität | gemessen gegen einen festen Testkorpus mit erwarteten Fundstellen; das Update-Paket nennt die Kennzahlen beider Versionen mit Messdatum ([Update-Paket](../../handbuch/betrieb/nachvollziehbarkeit.md#das-update-paket-eines-release), [Messverfahren](../../features/search-quality-evaluation.md)) |
| Wiederherstellung | nächtliche automatisierte Probe auf einem Testcluster ([Wiederherstellungsprobe](../09-betrieb-update/wiederherstellungsprobe.md#automatisierte-probe-im-projekt)) |
| Barrierefreiheit | Prüfstand mit bekannten Einschränkungen ([Prüfstand](../08-nutzung/erklaerung-barrierefreiheit.md#prüfstand)) |
| Nachvollziehbarkeit | Faktenblätter und Delta je Version, durch Prüfungen im Build mit dem Code gleich gehalten ([Was bei jeder Änderung geprüft wird](../../handbuch/betrieb/nachvollziehbarkeit.md#was-bei-jeder-änderung-geprüft-wird)) |
| Lücken | die Handbuchkapitel nennen unter „Was nicht gebaut ist“ oder „Was es hier nicht gibt“, was fehlt, etwa in [Releases und Nachweise](../../handbuch/betrieb/releases-und-nachweise.md#was-nicht-gebaut-ist) |
| Eindruck ohne eigene Daten | Demo-Instanz „Stadt Rheinfurt“ mit fiktivem Verwaltungsbestand, lokal installierbar ([Demo](../../../demo/README.md)) |

**Referenzen:** Das Projekt nennt keine Behörden oder Unternehmen, die OPAA einsetzen, und keine
Zitate von Anwenderinnen und Anwendern.

## Aufgaben der Behörde

1. **Muss-Anforderungen gegen die Lücken halten:** „Was nicht gebaut ist“ in den betroffenen
   Kapiteln lesen.
2. **Reifegrad selbst prüfen** in einer Teststellung mit eigenen Dokumenten und vorab festgelegten
   Kriterien.
3. **Referenzen bei Dienstleistern erfragen,** die OPAA anbieten, und im Austausch mit anderen
   Behörden.
4. **Version festhalten,** auf die sich die Einschätzung bezieht.

## Gut zu wissen

- Die Kennzahlen der Suchqualität stammen aus dem Testkorpus des Projekts. Für die eigenen Bestände
  sagen sie wenig; dafür ist die Teststellung da.
- Die Demo zeigt Bedienung und Belege, nicht die Antwortqualität mit eigenen Dokumenten.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [Erfüllungsmatrix](../03-vergabe/erfuellungsmatrix.md): wo OPAA eine Anforderung nur teilweise
  erfüllt.
- [Handbuch](../../handbuch/README.md): Ist-Stand der Software, je Kapitel mit den Lücken.
