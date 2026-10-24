# Regeln für die Vorlagen unter docs/behoerden

Diese Datei richtet sich an alle, die Vorlagen in diesem Ordner schreiben oder ändern, Menschen wie
Agenten. Was Lesende in einer Behörde wissen müssen, steht in der [README](README.md).

Jede Vorlage hier ist von Hand geschrieben (`erzeugung: redaktionell`). Was aus dem Code folgt, steht
nicht in den Vorlagen, sondern in den generierten Faktenblättern; die Vorlagen verweisen darauf.

## Faktenblätter

Die Faktenblätter entstehen aus dem Fakten-Manifest und beschreiben die Voreinstellung einer Version:

| Name | Inhalt |
|---|---|
| `datenkatalog` | alle gespeicherten Daten mit Zweck, Personenbezug, Speicherort, Frist, Sichtbarkeit, Zugriffsweg, Löschmechanismus, möglichen Auswertungen und Zuordnung zur Person (Weg oder Grund des Ausschlusses von der Selbstauskunft); Tabellen ohne Personenbezug |
| `protokollereignisse` | jedes Ereignis des revisionssicheren Protokolls mit Auslöser, Feldern des Protokollsatzes und Lesern |
| `rollensicht` | was jede Systemrolle und Fähigkeit über andere sieht, Matrix Rolle × Datenkategorie |
| `datenfluesse` | Datenflüsse nach außen, mit einem daraus erzeugten Übersichtsbild |
| `voreinstellungen` | Einstellungen mit Standardwert und Wirkung, System-Prompts mit Prüfsumme, KI-Funktionen |
| `fristen` | Fristen je Datum und Protokollereignis mit Konfigurationsschlüssel |

Dazu kommt `faktenblaetter.json` mit allen Inhalten für Werkzeuge. Was das Manifest noch nicht
beschreibt, steht im Blatt unter „Noch nicht erfasst“, statt zu fehlen.

**Wo sie liegen:** Für `main` als Artefakt `compliance-faktenblaetter` des letzten Laufs des
Workflows CI auf `main` (GitHub, Reiter „Actions“, Lauf öffnen, Abschnitt „Artifacts“), für ein
Release im Update-Paket `opaa-update-paket-X.Y.Z.tar.gz` am GitHub-Release der Version, unter
`faktenblaetter/` ([Update-Paket](../releases.md#update-paket)). Dort liegen auch das Delta zum
vorigen Release und die Vorlage
[Update-Vermerk](09-betrieb-update/update-vermerk.md). Lokal erzeugt sie `./gradlew complianceDocs`
(aus `backend/`) unter `backend/build/compliance/faktenblaetter/`.

**Kopf:** `gilt-fuer` (OPAA-Version, außerhalb eines Release `0.0.0-dev`), `commit`, `erzeugt-am`
(im Update-Paket das Datum des Commits)
und `erzeugung: generiert`; unter dem Titel der Hinweis „Fakten aus dem Code; rechtliche Bewertung
bleibt bei der Behörde.“

**Anker:** Jeder Eintrag hat einen Anker aus seiner Kennung in Kleinbuchstaben, jede Folge anderer
Zeichen als Buchstaben und Ziffern wird ein Bindestrich:

| Blatt | Anker | Beispiel |
|---|---|---|
| `datenkatalog` | Kennung des Datums | `{{faktenblatt:datenkatalog#audit-log}}` |
| `protokollereignisse` | Ereignis | `{{faktenblatt:protokollereignisse#asset-grant-granted}}` |
| `rollensicht` | Rolle oder Fähigkeit | `{{faktenblatt:rollensicht#auditor}}` |
| `datenfluesse` | Kennung des Datenflusses | — |
| `voreinstellungen` | Schlüssel; `prompt-`, `ki-funktion-` vor Prompt und KI-Funktion | `{{faktenblatt:voreinstellungen#prompt-answer-generation}}` |
| `fristen` | Schlüssel | `{{faktenblatt:fristen#audit-retention-settings-retention-months}}` |

Abschnitte haben eigene Anker, etwa `#matrix` in der Rollensicht und `#bereich-<bereich>` für einen
Bereich des Manifests. Alle gültigen Ziele eines Platzhalters stehen in
[`.github/fact-sheet-anchors.txt`](../../.github/fact-sheet-anchors.txt). Die Datei ist aus dem
Manifest erzeugt (`./gradlew complianceAnchors` aus `backend/`) und eingecheckt; ein Backend-Test
schlägt fehl, solange sie nicht zum Manifest passt.

Das Fakten-Manifest selbst liegt als Quelltext im Backend unter
[`backend/src/main/resources/compliance/manifest/`](../../backend/src/main/resources/compliance/manifest/),
eine YAML-Datei je Bereich; Schema und Regeln stehen in
[ADR-0052](../decisions/0052-compliance-manifest.md). Es wird wie Code geändert und geprüft, nicht in
diesem Ordner.

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
([Rollenvertrag](../../agents/roles/behoerdenkontakt.md)).

## Haftungshinweis

Direkt nach dem Kopfvermerk und dem Titel steht wörtlich und als Zitatblock:

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Es gibt keinen rechtlichen Prüfer. Der Hinweis wird weder
gekürzt noch umformuliert.

## Aufbau einer Vorlage

Jede Vorlage folgt demselben Aufbau, damit man sie schnell durcharbeiten kann. Muster ist die
[Datenschutz-Folgenabschätzung](04-datenschutz/datenschutz-folgenabschaetzung.md).

1. Kopfvermerk, Titel (nur der Fachbegriff, ohne „Baustein für“ oder „für OPAA“), Haftungshinweis.
2. **Gilt für:** eine Zeile, für wen die Vorlage passt.
3. **Einleitung** in zwei bis drei Sätzen: was das ist, wann man es braucht, wer verantwortlich ist.
   Fachbegriffe werden beim ersten Auftreten in einem Halbsatz erklärt.
4. **Was OPAA liefert:** Verweise auf Faktenblätter, Handbuch und andere Vorlagen.
5. **Aufgaben der Behörde:** eine kurze, nummerierte Liste, sachlich statt im Befehlston.
6. Je nach Vorlage ein Block zum Ausfüllen (**Ergebnis festhalten**) und **Gut zu wissen**.
7. **Zum Nachlesen:** zuerst Arbeitshilfen, dann die Rechtsgrundlagen als Tabelle.

Der Lesetext bis „Zum Nachlesen“ bleibt kurz, als Richtwert 300 bis 500 Wörter. Paragrafen stehen
nur im Abschnitt „Zum Nachlesen“, nicht im Lesetext. Vorlagen verweisen nicht auf Issues oder Epics.

Etablierte englische Fachbegriffe aus Betrieb und Technik (Health Check, Timeout, Log, Provenance,
RTO/RPO) werden nicht eingedeutscht, wie im Handbuch; deutsch bleiben Begriffe der Oberfläche und der
Verwaltung (Datenschutz, Mitbestimmung, Wiederherstellungsprobe).

Jedes Unterverzeichnis hat eine `README.md` mit einer Tabelle seiner Vorlagen (Dokument, worum es
geht). Eine neue oder umbenannte Vorlage wird dort im selben Zug eingetragen. Die READMEs tragen
keinen Kopfvermerk.

```markdown
---
stand: 2026-10-23
gilt-fuer: main
erzeugung: redaktionell
rechtsstand: 2026-10-13
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-23
---

# Verzeichnis von Verarbeitungstätigkeiten

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

**Gilt für:** alle Behörden.

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
  Kleinbuchstaben, Ziffern und Bindestriche, gebildet wie unter [Faktenblätter](#faktenblätter)
  beschrieben.
- Der Platzhalter wird nicht als Link geschrieben, weil die Faktenblätter nicht eingecheckt sind. Er
  steht für das gleichnamige Faktenblatt der Version, die die Behörde einsetzt; bei einem Release liegt
  es am GitHub-Release, für `main` im CI-Artefakt des letzten Laufs. Das Update-Paket enthält alle
  Dateien dieses Ordners außer `AGENTS.md` und `CLAUDE.md` unter `vorlagen/` und das Handbuch unter
  `handbuch/`, jede Datei auch als HTML; der Release-Lauf ersetzt jeden Platzhalter durch einen Link
  auf das Blatt unter `faktenblaetter/`, außer in Code-Spans und Codeblöcken. Links auf das Handbuch
  führen im Paket auf `handbuch/`, Links auf andere Dateien außerhalb dieses Ordners auf den Stand
  des Release-Tags. Ein relativer Link ohne Datei oder Anker lässt den Release-Lauf scheitern.
- Ein neues Faktenblatt entsteht im Generator (`io.opaa.compliance.FactSheet`); im selben PR werden
  die Liste der Platzhalterziele neu erzeugt und die Namensliste hier ergänzt.

### Gelesen im Update-Paket der eingesetzten Version

Die Vorlagen gehen davon aus, dass sie im Update-Paket der eingesetzten Version gelesen werden. Sie
erklären deshalb nicht, wo Faktenblätter, Handbuch oder Paket liegen, dass die Faktenblätter der
Version beizulegen sind oder wofür ein Platzhalter steht; ein Verweis lautet schlicht „steht im
{{faktenblatt:datenkatalog}}“. Woher das Paket kommt, erklärt nur die [README](README.md), oben mit
dem Hinweis, dass die Fassung im Repository der Entwicklungsstand ist.

### Amtliche Muster verlinken statt nachbauen

Wo ein amtliches oder verbreitetes Muster existiert (BFIT-Mustererklärung zur Barrierefreiheit,
DSFA-Muss-Listen, DSK-Orientierungshilfe „KI und Datenschutz“, WiBe, EVB-IT, Muster-Dienstvereinbarungen),
wird es verlinkt. Die Vorlage ergänzt nur, was für OPAA spezifisch ist.

### Rechtsquellen gegen den Normtext prüfen

Fundstellen aus Fachblogs, Kanzlei-Beiträgen oder Pressemitteilungen sind vor der Übernahme in eine
Vorlage gegen den Normtext zu prüfen. Das Datum dieser Prüfung steht im Feld `rechtsstand`.
Als Fundstelle stehen nur amtliche Quellen (Normtexte, Veröffentlichungen von Bund, Ländern und
EU); Fachbeiträge, Kanzlei- und Beraterseiten dienen höchstens der Recherche und werden nicht
verlinkt.

### Rechtsangaben mit Fundstelle

Eine Vorlage mit Rechtsangaben führt sie im Abschnitt `## Zum Nachlesen` als Tabelle mit den
Spalten „Worum es geht“ und „Fundstelle“, die Fundstelle möglichst mit Link. Eine Angabe, deren
Fundstelle nicht belegt ist, trägt „zu prüfen“, statt einen Paragrafen zu raten. Der Prüfstand aller
Angaben ist das Feld `rechtsstand` des Kopfvermerks; die Drift-Routine und die Rolle „Behördenkontakt“ finden über beides, was nachzuprüfen ist.

### Herstellerneutral in der Vergabe

Die Bausteine in `03-vergabe/`, die in eine Leistungsbeschreibung übernommen werden
(Anforderungskatalog, Zuschlagskriterien), nennen OPAA nicht. Wie OPAA sie erfüllt, steht getrennt in
der [Erfüllungsmatrix](03-vergabe/erfuellungsmatrix.md).

## Automatische Prüfung

[`.github/scripts/check_behoerden_headers.sh`](../../.github/scripts/check_behoerden_headers.sh) läuft
in der CI bei jedem Lauf, ohne Pfadfilter, weil Linkziele im ganzen Repository liegen. Es erfasst
jede Markdown-Datei (`.md`, `.markdown`) in diesem Ordner und allen Unterverzeichnissen, ohne
Dateiliste. Jede `README.md` sowie `AGENTS.md` und `CLAUDE.md` auf oberster Ebene sind von
Kopfvermerk und Haftungshinweis ausgenommen; Links prüft es in allen, Faktenblatt-Platzhalter auch in
den READMEs, weil sie ins Update-Paket gehen. Der Lauf scheitert bei:

- fehlendem oder unvollständigem Kopfvermerk, doppeltem Feld oder falschem Wert,
- fehlendem oder abgewandeltem Haftungshinweis,
- einem Faktenblatt-Platzhalter in anderer als der oben festgelegten Form oder mit einem Ziel, das
  `.github/fact-sheet-anchors.txt` nicht nennt, auch in einer README,
- einem relativen Link, auch in README.md und dieser Datei, dessen Datei fehlt oder dessen Anker keine
  Überschrift (in der Schreibweise von GitHub) und kein `<a id>` der Zieldatei trifft; eine
  umbenannte Überschrift im Handbuch fällt so auf,
- in `03-vergabe/`:
  - fehlendem Abschnitt `## Zum Nachlesen`, einer Tabelle darin ohne Spalte „Fundstelle“
    oder einer Zeile ohne Link und ohne „zu prüfen“ in dieser Spalte,
  - einem `§`, `§§` oder `Art.` im Text, das nicht die Form `§ 31 Abs. 6 VgV` hat (erlaubt sind
    zwischen Nummer und Gesetz `Abs.`, `Absatz`, `Satz`, `S.`, `Nr.`, `UAbs.`, `lit.` und `ff.`;
    ein Zitat darf über einen Zeilenumbruch gehen),
  - einem solchen Zitat, das keine Tabellenzeile des Quellenabschnitts nennt (verglichen als
    `§ 31 VgV` bzw. `Art. 28 DSGVO`),
  - einem `rechtsstand` nach `stand`,
  - „OPAA“ in beliebiger Schreibweise, auch in Links, in Anforderungskatalog oder Zuschlagskriterien,
  - einer Anforderungsnummer, die nur im Katalog oder nur in der Erfüllungsmatrix oder in einer der
    beiden doppelt steht.

Die Prüfung erkennt Rechtsangaben nur an diesen Zeichen. Ob eine Aussage ohne Paragrafen eine
Rechtsangabe ist und ob die Fundstelle sie trägt, prüft sie nicht; das bleibt der Durchsicht.

Das Skript braucht GNU sed, GNU realpath und die Locale C.UTF-8, wie auf den CI-Runnern.

Ein Konfigurationsschlüssel (`opaa.…`) oder eine Enum-Konstante (`GROSS_GESCHRIEBEN`) im Text ergibt
außerhalb von Codeblöcken nur eine Warnung: Beides deutet auf einen abgeschriebenen Faktenwert hin,
kann aber auch berechtigt sein. Bezeichnungen mit Ziffern nach dem Unterstrich (`ISO_27001`) bleiben
ohne Warnung. Ob ein Verweis inhaltlich stimmt, prüft das Skript nicht.

Gegen die erzeugte Liste der Platzhalterziele
([`.github/fact-sheet-anchors.txt`](../../.github/fact-sheet-anchors.txt)) prüft das Skript außerdem:
Jeder Platzhalter, auch ein Beispiel in dieser Datei, trifft ein Blatt und, mit Anker, einen Anker
darin, und die Namensliste oben nennt genau die Blätter der Liste. Ob die Liste zum Manifest passt,
prüft der Backend-Test `FactSheetReferenceTest`; eine Vorlagenänderung braucht deshalb keinen
Backend-Lauf.

Lokal: `.github/scripts/check_behoerden_headers.sh`
