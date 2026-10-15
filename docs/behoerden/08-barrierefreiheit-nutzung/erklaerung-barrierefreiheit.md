---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Erklärung zur Barrierefreiheit: Grundlage für OPAA

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Wer OPAA betreibt, gibt eine Erklärung zur Barrierefreiheit ab, auch für ein Angebot, das nur
Beschäftigten offensteht: § 12a Abs. 1 BGG nennt die für Beschäftigte bestimmten Angebote im Intranet
ausdrücklich ([Normtext](https://www.gesetze-im-internet.de/bgg/__12a.html)). Für Landesbehörden
gelten die Landesgesetze zur Barrierefreiheit.

Diese Unterlage ist **keine Erklärung**, sondern die Zuarbeit des Produkts dazu: was über OPAA geprüft
ist, womit, auf welchem Stand und mit welchen Lücken. Die Erklärung selbst schreibt die Behörde nach dem
amtlichen Muster.

## Das amtliche Muster

- **Bund:** Muster und Erläuterungen der Überwachungsstelle des Bundes für Barrierefreiheit von
  Informationstechnik ([BFIT-Bund](https://www.bfit-bund.de/)). Inhalt und Aufbau gehen auf die
  Mustererklärung der EU zurück
  ([Durchführungsbeschluss (EU) 2018/1523](https://eur-lex.europa.eu/eli/dec_impl/2018/1523/oj)).
- **Länder:** das Muster der jeweiligen Überwachungsstelle des Landes.

Das Muster wird hier nicht nachgebaut. Die folgenden Abschnitte liefern die produktseitigen Angaben,
die in das Muster eingehen.

## Was die Behörde selbst beiträgt

Diese Teile der Erklärung kann OPAA nicht liefern:

- **Stand der Vereinbarkeit** („vollständig“, „teilweise“, „nicht vereinbar“): eine Bewertung der
  Behörde für ihr Angebot. Auf Grundlage dieser Unterlage lässt sich „vollständig vereinbar“ nicht
  belegen, solange kein Prüfstand für die eingesetzte Version vorliegt (siehe [Prüfstand](#prüfstand)).
- **Eigene Inhalte:** Dokumente in den Wissensbibliotheken, Prompt-Bibliotheken und Texte, die die
  Behörde hinterlegt. OPAA verändert die Barrierefreiheit von Originaldokumenten nicht; ein
  Originaldokument öffnet sich in seinem eigenen Format.
- **Branding:** Akzentfarbe, Logos und Hintergrundbild der Anmeldeseite legt die Behörde fest
  ([Kapitel Branding](../../handbuch/branding.md)). Das Audit hat das Warnverhalten bei einer
  kontrastschwachen Akzentfarbe geprüft, nicht jede denkbare Farbe.
- **Anmeldung über den eigenen Identitätsanbieter:** Meldet sich die Behörde über einen eigenen
  Anmeldedienst an (etwa Keycloak), ist dessen Anmeldeseite nicht Teil von OPAA.
- **Feedback-Mechanismus und Kontakt**, **Schlichtungsverfahren** und **Datum der Erklärung**.
- **Weitere Angebote:** Ob für das Intranetangebot weitere Pflichten gelten, etwa Informationen in
  Leichter Sprache oder Deutscher Gebärdensprache, prüft die Behörde. OPAA bringt solche Inhalte nicht
  mit.

## Prüfmaßstab

Zielniveau ist **BITV 2.0 / WCAG 2.1, Konformitätsstufe AA**, über EN 301 549, in beiden Farbschemata.
Maßstab, Prüfliste und Prüfverfahren stehen in der
[Barrierefreiheits-Richtlinie](../../design/accessibility.md); sie ist für jedes UI-Issue und jeden
Frontend-Pull-Request verbindlich.

## Prüfverfahren im Entwicklungsprozess

Die Richtlinie unterscheidet drei Stufen ([Abschnitt „Prüfverfahren“](../../design/accessibility.md#3--prüfverfahren)).
Für die Erklärung zählt, was davon wie oft läuft:

| Prüfung | Wann | Beleg |
|---|---|---|
| Lint-Regeln für barrierefreies Markup | jeder Pull Request (Frontend-Lint der CI) | Lint-Konfiguration im Frontend |
| axe-core gegen WCAG 2.1 A/AA in der E2E-Suite; Verstöße „serious“ und „critical“ lassen die Suite scheitern | nach jedem Merge auf `main`, nächtlich und auf Anforderung, **nicht** als Bedingung eines Pull Requests; ein Fehlschlag öffnet ein Alarm-Issue | [`e2e/tests/accessibility.spec.ts`](../../../e2e/tests/accessibility.spec.ts), [Läufe der E2E-Suite](https://github.com/criew/opaa/actions/workflows/e2e.yml) |
| axe-core für die Seiten der lokalen Anmeldung | nächtlich und auf Anforderung, an einem Pull Request nur per Label | [`e2e/local-auth/tests/07-barrierefreiheit.spec.ts`](../../../e2e/local-auth/tests/07-barrierefreiheit.spec.ts) |
| Tastatur-Durchgang | jeder Pull Request mit UI-Änderung, von Hand | Nachweis im jeweiligen Pull Request |
| Screenreader-Stichprobe | bei neuen Interaktionsmustern und Abschluss-Audits, von Hand | Nachweis im jeweiligen Pull Request oder Audit |

Welche Ansichten die axe-Suite abdeckt und ob es begründete Ausnahmen gibt, steht in der Testdatei und
in der Richtlinie. Diese Unterlage zählt beides bewusst nicht auf, weil es sich mit dem Code ändert.

**Grenze der automatisierten Prüfung:** axe-core findet nur einen Teil der Barrieren, die WCAG 2.1 AA
beschreibt. Bedienbarkeit mit Tastatur und Screenreader belegen nur die Prüfungen von Hand.

## Prüfstand

Der letzte vollständige Prüfdurchgang ist das
[Barrierefreiheits-Audit vom 28.08.2026](../../design/accessibility-audit-2026-08.md):

| Angabe | Wert |
|---|---|
| Datum | 28.08.2026 |
| Prüfgegenstand | Stand `main` @ `a2f3fa9e`, keine veröffentlichte Version |
| Art | interne Prüfung durch das Projekt, werkzeuggestützt (axe-core, skriptgesteuerte Tastatur-Durchgänge, Accessibility-Tree); kein BITV-Test einer unabhängigen Prüfstelle |
| Ergebnis | vier Befunde, keiner „critical“; alle vier behoben ([#956](https://github.com/criew/opaa/issues/956), [#957](https://github.com/criew/opaa/issues/957), [#958](https://github.com/criew/opaa/issues/958), [#959](https://github.com/criew/opaa/issues/959)) |

**Das Audit trägt nicht als Prüfstand einer Version.** Es prüfte einen Entwicklungsstand vor dem ersten
Release. Seither hat sich die Oberfläche in Bereichen geändert, die das Audit geprüft hat, unter anderem
Katalog, Space-Anlage, Benutzerverwaltung, Chat-Archiv und Zugänge. Für diese Teile belegt seit dem Audit
nur noch die laufende axe-Suite den Stand, nicht eine Prüfung von Hand. Ein Audit gegen eine
veröffentlichte Version ist als Folgeaufgabe erfasst
([#2828](https://github.com/criew/opaa/issues/2828)). Bis dahin nennt die Erklärung das Audit mit
Datum und Commit und die axe-Suite als laufenden Nachweis.

## Bekannte Einschränkungen

Stand der Durchsicht: siehe Kopfvermerk. Maßgeblich sind das Audit und die offenen Issues.

- **Kein Hörtest mit nativem Screenreader.** Das Audit prüfte über den Accessibility-Tree; eine
  Sitzung mit VoiceOver oder NVDA steht aus (Audit, Abschnitt 1, Einschränkung b). Ebenso offen ist die
  Screenreader-Probe der Gesprächsnotiz im Chat
  ([#1575](https://github.com/criew/opaa/issues/1575)).
- **Anmeldeseite mit echtem Keycloak** ist nicht von Hand mit der Tastatur geprüft, nur durch die
  axe-Suite gegen einen Stub (Audit, Einschränkung a).
- **200 % Zoom** ist über eine gleichwertige Fensterbreite geprüft, nicht über den Browser-Zoom
  (Audit, Einschränkung c).
- **Änderungen seit dem Audit** sind nur automatisiert geprüft (siehe [Prüfstand](#prüfstand)).

Weitere offene Befunde zur Barrierefreiheit stehen als Issues im Repository
([Suche nach offenen Issues](https://github.com/criew/opaa/issues?q=is%3Aissue+is%3Aopen+Barrierefreiheit)).
Vor der Abgabe der Erklärung ist diese Liste gegen den Stand der eingesetzten Version zu prüfen.

## Was in die Erklärung übernommen werden kann

Für den Abschnitt zur Prüfmethode im Muster, sinngemäß:

- Grundlage der Aussagen ist eine Prüfung des Herstellers bzw. Projekts nach BITV 2.0 / WCAG 2.1 AA
  vom **[Datum des Audits]** an **[Version oder Commit]**, ergänzt um automatisierte Prüfungen an jedem
  Pull Request (Lint) und nach jeder Änderung am Hauptstand (axe-core).
- Nicht barrierefrei bzw. nicht geprüft sind: **[Liste aus „Bekannte Einschränkungen“, ergänzt um eigene
  Inhalte und Branding der Behörde]**.
- Eine eigene Prüfung der Behörde für ihre Installation: **[ja, Datum / nein]**.

## Weitere Unterlagen

- [Barrierefreiheits-Richtlinie](../../design/accessibility.md), Abschnitt 4 „Nachweis im PR“
- [Muster-Nutzungsrichtlinie für Beschäftigte](./nutzungsrichtlinie.md)
- Spezifikation [Verwaltungs-Spezifika, Abschnitt Barrierefreiheit](../../features/public-sector.md#barrierefreiheit)
