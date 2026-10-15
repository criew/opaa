# Unterlagen für Behörden

Dieser Ordner enthält die Unterlagen, die eine Behörde für die Einführung und den Betrieb von OPAA
braucht. Sie folgen der Behördenreise: von der Entscheidung der Hausleitung über Markterkundung,
Teststellung und Vergabe bis zu Einführung, Betrieb und jedem späteren Update.

## Zweck und Abgrenzung

- **Das [Handbuch](../handbuch/README.md) beschreibt das Produkt**, `docs/behoerden/` die Verfahren
  drumherum: Vorlagen, Checklisten und Erläuterungen, mit denen eine Behörde ihre eigenen Unterlagen
  erstellt (Entscheidungsvorlage, Verzeichnis von Verarbeitungstätigkeiten, Dienstvereinbarung,
  Sicherheitskonzept und andere).
- **Zielgruppe sind Behörden.** Unternehmen (Betriebsrat, ISO 27001, NIS2 für Unternehmen) sind
  ausdrücklich nicht Zielgruppe; Unterlagen für sie folgen später.
- **OPAA liefert Fakten, die Behörde trifft die Wertungen.** Rechtliche Einstufungen in den Vorlagen
  sind begründete Hinweise, keine Rechtsberatung.

Hintergrund und Planung: Epic [#2542](https://github.com/criew/opaa/issues/2542), Vorlagen im Epic
[#2560](https://github.com/criew/opaa/issues/2560).

## Aufbau

Die Unterverzeichnisse folgen der Behördenreise. Verzeichnisnamen tragen eine zweistellige Nummer
für die Reihenfolge und keine Umlaute. Ein Verzeichnis entsteht mit seiner ersten Vorlage.

| Verzeichnis | Phase |
|---|---|
| `00-entscheidung/` | Entscheidung der Hausleitung |
| `01-markterkundung/` | Markterkundung |
| `02-pilot-teststellung/` | Pilot-Beteiligung und Teststellung |
| `03-vergabe/` | Vergabe |
| `04-datenschutz/` | Einführung: Datenschutz |
| `05-mitbestimmung/` | Einführung: Mitbestimmung |
| `06-informationssicherheit/` | Informationssicherheit, Betrieb, Notfall |
| `07-ki-verordnung-cra/` | KI-Verordnung und Cyber Resilience Act |
| `08-barrierefreiheit-nutzung/` | Barrierefreiheit und Nutzung |
| `09-betrieb-update/` | Betrieb und Update |

## Erzeugungsarten

| Art | Herkunft | Ablage |
|---|---|---|
| **generiert** | aus dem Fakten-Manifest im Code erzeugt (Faktenblätter, [#2550](https://github.com/criew/opaa/issues/2550)) | **nicht eingecheckt**; erscheint als CI-Artefakt jedes Laufs auf `main` und am GitHub-Release der Version ([#2556](https://github.com/criew/opaa/issues/2556)) |
| **redaktionell** | von Hand geschrieben | eingecheckt in diesem Ordner |

Jedes Dokument in diesem Ordner außer dieser README ist redaktionell. Generierte Faktenblätter tragen
ihren Stand (Version, Commit, Erzeugungsdatum, `erzeugung: generiert`) im eigenen Kopf.

## Kopfvermerk

Jedes redaktionelle Dokument beginnt mit einem YAML-Front-Matter-Block mit genau diesen Feldern:

| Feld | Inhalt |
|---|---|
| `stand` | Datum der letzten inhaltlichen Durchsicht (`JJJJ-MM-TT`) |
| `gilt-fuer` | OPAA-Version (`X.Y.Z`), zu der die Durchsicht erfolgte, oder `main` |
| `erzeugung` | immer `redaktionell` |
| `rechtsstand` | Datum, zu dem Normen und Quellen geprüft wurden (`JJJJ-MM-TT`) |
| `pruefvermerk` | fester Wert `nicht rechtlich geprüft` |
| `naechste-durchsicht` | Datum der nächsten geplanten Durchsicht (`JJJJ-MM-TT`) |

Ein überschrittenes `naechste-durchsicht` ist kein Fehler; die Rolle „Behördenkontakt“ meldet es
([#2573](https://github.com/criew/opaa/issues/2573)).

## Haftungshinweis

Direkt nach dem Kopfvermerk und dem Titel steht wörtlich und als Zitatblock:

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Es gibt keinen rechtlichen Prüfer (Beschluss des Maintainers vom 13.10.2026). Der Hinweis wird weder
gekürzt noch umformuliert.

## Muster eines Dokuments

```markdown
---
stand: 2026-10-15
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-15
---

# Verzeichnis von Verarbeitungstätigkeiten

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Die Speicherfristen stehen im {{faktenblatt:fristen}}, die personenbezogenen Daten im
{{faktenblatt:datenkatalog}}.
```

## Regeln

### Fakten verweisen, nicht abschreiben

Redaktionelle Vorlagen nennen keine Werte, die generiert werden: keine Fristen, Protokollereignisse,
Datenflüsse, Voreinstellungen, Konfigurationsschlüssel oder Rollenrechte. Sie verweisen stattdessen auf
das Faktenblatt der Version, für die die Behörde die Unterlage erstellt. So veralten die Vorlagen nicht
mit jedem Release.

Ein Verweis auf ein Faktenblatt hat immer diese Form:

```text
{{faktenblatt:<name>}}
{{faktenblatt:<name>#<anker>}}
```

- `<name>` ist eines der Faktenblätter: `datenkatalog`, `protokollereignisse`, `rollensicht`,
  `datenfluesse`, `voreinstellungen`, `fristen`.
- `<anker>` ist optional und verweist auf einen Abschnitt oder Eintrag im Faktenblatt; nur
  Kleinbuchstaben, Ziffern und Bindestriche.
- Der Platzhalter wird nicht als Link geschrieben, weil die Faktenblätter nicht eingecheckt sind. Er
  steht für das gleichnamige Faktenblatt der Version, die die Behörde einsetzt; bei einem Release liegt
  es am GitHub-Release, für `main` im CI-Artefakt des letzten Laufs.
- Ein neues Faktenblatt wird zuerst hier und in der Namensliste des Prüfskripts ergänzt.

### Amtliche Muster verlinken statt nachbauen

Wo ein amtliches oder verbreitetes Muster existiert (BFIT-Mustererklärung zur Barrierefreiheit,
DSFA-Muss-Listen, DSK-Orientierungshilfe „KI und Datenschutz“, WiBe, EVB-IT, Muster-Dienstvereinbarungen),
wird es verlinkt. Die Vorlage ergänzt nur, was für OPAA spezifisch ist.

### Rechtsquellen gegen den Normtext prüfen

Fundstellen aus Fachblogs, Kanzlei-Beiträgen oder Pressemitteilungen sind vor der Übernahme in eine
Vorlage gegen den Normtext zu prüfen. Das Datum dieser Prüfung steht im Feld `rechtsstand`.

## Automatische Prüfung

[`.github/scripts/check_behoerden_headers.sh`](../../.github/scripts/check_behoerden_headers.sh) läuft
in der CI bei jeder Änderung unter `docs/behoerden/`. Es erfasst jede Markdown-Datei (`.md`,
`.markdown`) in diesem Ordner und allen Unterverzeichnissen außer dieser README, ohne Dateiliste, und
lässt den Lauf scheitern bei:

- fehlendem oder unvollständigem Kopfvermerk, doppeltem Feld oder falschem Wert,
- fehlendem oder abgewandeltem Haftungshinweis,
- einem Faktenblatt-Platzhalter in anderer als der oben festgelegten Form.

Ein Konfigurationsschlüssel (`opaa.…`) oder eine Enum-Konstante (`GROSS_GESCHRIEBEN`) im Text ergibt
außerhalb von Codeblöcken nur eine Warnung: Beides deutet auf einen abgeschriebenen Faktenwert hin,
kann aber auch berechtigt sein. Bezeichnungen mit Ziffern nach dem Unterstrich (`ISO_27001`) bleiben
ohne Warnung. Ob ein Verweis inhaltlich stimmt, prüft das Skript nicht.

Lokal: `.github/scripts/check_behoerden_headers.sh`
