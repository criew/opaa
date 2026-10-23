---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-23
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Lizenz

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden, die OPAA einsetzen, anpassen lassen oder anderen bereitstellen.

Die Lizenz regelt, was die Behörde mit der Software tun darf und welche Pflichten daraus folgen.
OPAA steht unter der GNU Affero General Public License v3.0 (AGPL-3.0), einer Lizenz für freie
Software. Die enthaltenen Bibliotheken anderer Projekte, die Abhängigkeiten, haben eigene Lizenzen.
Ob die Bedingungen zum eigenen Vorhaben passen, prüft die Rechtsabteilung der Behörde.

## Was OPAA liefert

| Thema | Stand | Nachweis |
|---|---|---|
| Lizenz von OPAA | AGPL-3.0, kein Lizenzentgelt, vollständiger Quellcode öffentlich | [`LICENSE`](../../../LICENSE) |
| Nutzen und Ändern | erlaubt, auch ohne Rückfrage beim Projekt | [`LICENSE`](../../../LICENSE) |
| Pflicht bei Bereitstellung über ein Netz | wer eine veränderte Fassung anderen über ein Netz bereitstellt, bietet deren Quellcode an | [Lizenzen](../../handbuch/betrieb/releases-und-nachweise.md#lizenzen) |
| Lizenzen der Abhängigkeiten | nur mit der GPLv3 verträgliche Lizenzen oder begründete Ausnahmen; die CI prüft das bei jeder Änderung | [Was bei jeder Änderung geprüft wird](../../handbuch/betrieb/nachvollziehbarkeit.md#was-bei-jeder-änderung-geprüft-wird) |
| Lizenzübersicht je Version | im Update-Paket unter `lizenzen/` | [Das Update-Paket](../../handbuch/betrieb/releases-und-nachweise.md#das-update-paket) |
| Software-Stückliste (SBOM) | je Image und je Ökosystem, mit Provenance | [Provenance und Software-Stückliste](../../handbuch/betrieb/releases-und-nachweise.md#provenance-und-software-stückliste) |
| Beiträge ins Projekt | nach unterzeichnetem Contributor License Agreement (CLA) | [`CLA.md`](../../../CLA.md) |

## Aufgaben der Behörde

1. **Einsatz beschreiben:** unverändert, angepasst durch einen Dienstleister, oder bereitgestellt
   für andere Stellen, etwa als Betreiberin für mehrere Behörden.
2. **Quellcode-Angebot regeln,** wenn eine veränderte Fassung über ein Netz bereitgestellt wird; im
   Vertrag mit dem Dienstleister oder im eigenen Betrieb.
3. **Lizenzübersicht der eingesetzten Version prüfen lassen,** besonders die Ausnahmen.
4. **Anpassungen planen:** ins Projekt einbringen (mit CLA) oder als eigene Abweichung bei jedem
   Update selbst nachziehen.

## Gut zu wissen

- Die EVB-IT behandeln Open Source seit 2026 als Regelfall; eine Überlassung der Software ist bei
  OPAA meist unnötig ([EVB-IT](../03-vergabe/evb-it-hinweise.md)).
- Die Lizenzprüfung belegt das Verfahren des Projekts, keine rechtliche Bewertung jeder Lizenz.

## Zum Nachlesen

**Arbeitshilfen** für die Rechtsabteilung:

- [GNU Affero General Public License v3.0](https://www.gnu.org/licenses/agpl-3.0.html): maßgeblicher
  englischer Lizenztext bei der Free Software Foundation.

**Rechtsgrundlagen:**

| Worum es geht | Fundstelle |
|---|---|
| Urheberrechtlicher Schutz von Computerprogrammen | § 69a [UrhG](https://www.gesetze-im-internet.de/urhg/__69a.html) |
| Handlungen, die der Zustimmung des Rechteinhabers bedürfen | § 69c [UrhG](https://www.gesetze-im-internet.de/urhg/__69c.html) |
| Unentgeltliches einfaches Nutzungsrecht für jedermann | § 32 Abs. 3 Satz 3 [UrhG](https://www.gesetze-im-internet.de/urhg/__32.html) |
