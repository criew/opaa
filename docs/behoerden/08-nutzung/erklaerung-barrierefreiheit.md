---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Erklärung zur Barrierefreiheit

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** öffentliche Stellen nach dem Behindertengleichstellungsrecht des Bundes bzw. des
Landes, auch für Angebote, die nur Beschäftigten im Intranet offenstehen.

Die Erklärung zur Barrierefreiheit sagt öffentlich, wie weit ein Angebot für Menschen mit
Behinderungen nutzbar ist, wo es Lücken hat und wie man Barrieren meldet. Die Behörde schreibt sie
nach dem amtlichen Muster und verantwortet sie. Diese Vorlage liefert die Angaben zum Produkt: was
geprüft ist, womit, auf welchem Stand und mit welchen Lücken.

## Was OPAA liefert

- **Prüfmaßstab:** BITV 2.0 bzw. WCAG 2.1, Stufe AA (die verbindlichen Anforderungen an barrierefreie
  Software), im hellen und im dunklen Farbschema.
- **Laufende Prüfungen:** automatische Regeln für barrierefreies Markup an jeder Änderung; ein
  automatischer Test mit dem Werkzeug axe-core nach jeder Übernahme in den Hauptstand und nächtlich;
  Tastatur-Durchgang von Hand bei jeder Änderung der Oberfläche; Screenreader-Stichproben bei neuen
  Bedienmustern.

### Prüfstand

Der letzte vollständige Prüfdurchgang ist das Audit vom 28.08.2026, eine interne, werkzeuggestützte
Prüfung des Projekts, kein BITV-Test einer unabhängigen Prüfstelle. Es fand vier Befunde, keinen
kritischen; alle sind behoben. Das Audit prüfte einen Entwicklungsstand vor dem ersten Release und
trägt deshalb **nicht als Prüfstand einer Version**. Seither geänderte Bereiche, etwa Katalog,
Benutzerverwaltung und Chat-Archiv, sind nur automatisch geprüft. Ein Audit einer veröffentlichten
Version steht aus.

### Bekannte Einschränkungen

- kein Hörtest mit einem echten Screenreader wie NVDA oder VoiceOver,
- die Anmeldeseite mit echtem Keycloak nicht von Hand mit der Tastatur geprüft,
- 200 % Zoom über eine gleich breite Fensteransicht geprüft, nicht über den Browser-Zoom,
- Änderungen seit dem Audit nur automatisch geprüft.

## Aufgaben der Behörde

1. **Amtliches Muster holen:** für den Bund von der Überwachungsstelle BFIT-Bund, für Landesbehörden
   von der Überwachungsstelle des Landes.
2. **Prüfstand und Einschränkungen übernehmen**, mit dem Textbaustein unten.
3. **Offene Befunde prüfen:** die Liste offener Befunde zur Barrierefreiheit im Repository gegen die
   eingesetzte Version halten.
4. **Stand der Vereinbarkeit bewerten:** „vollständig vereinbar“ lässt sich ohne Prüfstand für die
   eingesetzte Version nicht belegen.
5. **Eigene Teile ergänzen:** eigene Dokumente (sie öffnen sich im Originalformat, OPAA ändert ihre
   Barrierefreiheit nicht), Branding (geprüft ist die Warnung bei kontrastschwacher Akzentfarbe, nicht
   jede Farbe), die Anmeldeseite eines eigenen Anmeldedienstes, Kontakt für Rückmeldungen,
   Schlichtungsverfahren und Datum.
6. **Weitere Pflichten klären,** etwa Informationen in Leichter Sprache oder Gebärdensprache; OPAA
   bringt sie nicht mit.

## Ergebnis festhalten

Für den Abschnitt zur Prüfmethode im Muster, sinngemäß:

```text
Grundlage ist eine Prüfung des Herstellers bzw. Projekts nach BITV 2.0 / WCAG 2.1 AA vom
[Datum des Audits] an [Version oder Commit], ergänzt um automatisierte Prüfungen an jeder Änderung
und am Hauptstand.
Nicht barrierefrei bzw. nicht geprüft: [bekannte Einschränkungen, eigene Inhalte, Branding].
Eigene Prüfung der Behörde für ihre Installation: [ja, Datum / nein]
```

## Gut zu wissen

- Automatische Werkzeuge finden nur einen Teil der Barrieren. Ob sich OPAA mit Tastatur und
  Screenreader bedienen lässt, belegen nur Prüfungen von Hand.
- Eine eigene Prüfung der Installation macht die Erklärung belastbarer, vor allem mit eigenem
  Branding.

## Zum Nachlesen

**Arbeitshilfen** für die Projektleitung:

- [BFIT-Bund](https://www.bfit-bund.de/): Mustererklärung und Erläuterungen für Bundesbehörden.
- [Läufe der E2E-Suite](https://github.com/criew/opaa/actions/workflows/e2e.yml) mit
  automatischem axe-Test: laufender Nachweis seit dem Audit.
- [Offene Befunde zur Barrierefreiheit](https://github.com/criew/opaa/issues?q=is%3Aissue+is%3Aopen+Barrierefreiheit):
  vor der Abgabe gegen die eingesetzte Version prüfen.
- [Handbuch, Branding](../../handbuch/betrieb/branding.md): was die Behörde an Farben und Logos festlegt.

**Rechtsgrundlagen** für die Projektleitung und das Justiziariat:

| Worum es geht | Fundstelle |
|---|---|
| Barrierefreiheit auch für Intranetangebote für Beschäftigte (Bund) | § 12a Abs. 1 [BGG](https://www.gesetze-im-internet.de/bgg/__12a.html) |
| Inhalt und Aufbau der Mustererklärung | [Durchführungsbeschluss (EU) 2018/1523](https://eur-lex.europa.eu/eli/dec_impl/2018/1523/oj) |
| Anforderungen an barrierefreie Informationstechnik (Bund) | [BITV 2.0](https://www.gesetze-im-internet.de/bitv_2_0/) |
| Landesbehörden | Landesgesetze zur Barrierefreiheit, zu prüfen |
