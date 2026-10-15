# Behördenunterlagen und Update-Paket

> **Status: Entwurf — Zielbild, in Teilen gebaut.** Übernommen aus dem Konzept
> „Beschaffungs- und Einführungsunterstützung für Behörden“ v2 (13.10.2026) mit den Beschlüssen des
> Maintainers vom selben Tag. Umgesetzt wird es im übergeordneten Epic
> [#2542](https://github.com/criew/opaa/issues/2542) mit den Epics #2543 (Fakten aus dem Code),
> #2552 (Update-Paket), #2560 (Behördenunterlagen), #2570 (Agentenorganisation) und dem Einzel-Issue
> #2575 (Release-Zyklus). Vorhanden sind die Bausteine, auf die es aufsetzt: Protokoll-Enums,
> Liquibase-Changelogs, SBOM und Attestierung ([sbom.md](../sbom.md)), CVE-Prozess
> ([cve-scanning.md](../cve-scanning.md)) und die Upgrade-Hinweise je Release
> ([releases.md](../releases.md#vorbereitung-für-bestandsinstallationen)). **Gebaut** sind die
> wöchentliche Drift-Routine ([Schicht 3](#schicht-3-wöchentliche-drift-routine)), die Kennzeichnung
> am PR ([Schicht 2](#schicht-2-kennzeichnung-datenrelevanter-änderungen-am-pr), #2553), das
> [Manifest](#das-manifest) mit Schema, Lader und Erstbefüllung (ohne Konfigurationsschlüssel und
> Datenflüsse) und der Abschnitt „Wirkung auf Behördenunterlagen“ in Spezifikationsvorlage und
> Produktmanager-Rolle (#2571). Alles Übrige ist Zielbild.

> **Abgrenzung:** Wie das Protokoll, die Exporte und die Mitbestimmungsfähigkeit des Produkts selbst
> aussehen, steht in [Sicherheit, Nachweis & Prüfbarkeit](./security-and-compliance.md); die
> Verwaltungs-Spezifika (Barrierefreiheit, Revisionssicherheit) in
> [Verwaltungs-Spezifika](./public-sector.md); das Release-Verfahren in
> [releases.md](../releases.md). Dieses Dokument beschreibt, wie aus diesen Eigenschaften **Unterlagen
> für eine Behörde** werden, die mit jedem Release stimmen. Es ist kein Rechtsrat; alle Fundstellen
> sind Hinweise.

## Motivation

Eine Behörde, die OPAA einsetzen will, durchläuft feste Verfahren: Entscheidung der Hausleitung,
Markterkundung, Pilot und Teststellung, Vergabe, Einführung mit Datenschutz, Mitbestimmung,
Informationssicherheit, KI-Verordnung und Barrierefreiheit, danach den Betrieb und **jedes spätere
Update**. In jedem Schritt fragt eine andere Stelle — Hausleitung, Vergabestelle, Datenschutzbeauftragte,
Personalrat, Informationssicherheitsbeauftragte (ISB) — nach denselben Fakten: welche Daten anfallen,
wo sie liegen, wie lange, wer sie sieht, was das System verlässt und was voreingestellt ist.

Heute stehen diese Fakten verstreut in Spezifikationen, Enums, Liquibase-Changelogs und rund 50
Konfigurationsklassen. Eine Behörde müsste sie selbst zusammensuchen, und niemand bemerkt, wenn sie
veralten. Besonders schwer wiegt das bei Updates: Weil neue Funktionen nicht ausgeschaltet ankommen
(Beschluss 1), muss jede Behörde bei jedem Update beurteilen, ob sich an Daten, Sichtbarkeit oder
Auswertung etwas geändert hat — ohne selbst Diffs lesen zu können.

Vergleichbare Projekte pflegen ihre Unterlagen von Hand mit Standdatum (EVB-IT-Vertrag, AVV, TOM,
Barrierefreiheitserklärung). Versionsgebundene, aus dem Code erzeugte Unterlagen mit Delta zwischen
Releases und öffentliche Unterlagen zu Mitbestimmung und KI-Verordnung bietet nach der Recherche vom
13.10.2026 kein Projekt. Diese Lücke kann OPAA besetzen.

---

## Überblick

1. **OPAA liefert geprüfte Fakten, die Behörde trifft die Wertungen.** Generiert wird, was aus dem
   Code folgt: Daten, Speicherorte, Fristen, Protokollereignisse, Rollen, Datenflüsse,
   Voreinstellungen, System-Prompts und Standardmodelle. Rechtliche Einstufungen sind begründete
   Hinweise, nie Feststellungen.
2. **Ein Fakten-Manifest ist die einzige Quelle.** Faktenblätter, Update-Delta, Instanzexport und die
   statischen Anlagen der Dienstvereinbarung lesen daraus.
3. **Drei Schichten sichern das Mitziehen ab:** deterministische CI-Wächter, eine Erkennung
   datenrelevanter Änderungen am PR und eine wöchentliche, nur lesende Drift-Routine
   ([Automatisierung und Drift-Erkennung](#automatisierung-und-drift-erkennung)).
4. **Jedes Dokument trägt seinen Stand:** Version, Commit, Erzeugungsart (generiert / redaktionell)
   und bei redaktionellen Vorlagen den Prüfvermerk „nicht rechtlich geprüft“.
5. **Updates sind der Kern, nicht der Anhang.** Zu jedem Release gibt es ein attestiertes
   Update-Paket; für jedes Versionspaar lässt sich ein kumuliertes Delta erzeugen, mit Stufenvorschlag
   nach dem Modell der Dienstvereinbarung.
6. **Amtliche Muster verlinken statt nachbauen** (BFIT-Mustererklärung, DSFA-Muss-Listen,
   DSK-Orientierungshilfe KI, WiBe, EVB-IT, Muster-Dienstvereinbarungen).
7. **Redaktionelle Vorlagen verweisen auf Fakten, statt sie abzuschreiben**, und veralten deshalb nicht
   mit jedem Release.
8. **Die Agentenorganisation trägt die Pflege**, nicht der gute Wille Einzelner: Spezifikation,
   Entwicklung, Review und Release fragen nach der Wirkung auf die Unterlagen.

---

## Beschlüsse des Maintainers (13.10.2026)

Die Beschlüsse binden diese Spezifikation. Die Nummern 1–7 entsprechen den Verweisen in den Issues
der Epics; Beschluss 8 steht im Body von #2542 als erster von zwei Punkten mit der Nummer 5 und wird hier am
Ende angefügt, damit die bestehenden Verweise gültig bleiben.

| # | Beschluss | Wirkung in dieser Spezifikation |
|---|---|---|
| 1 | **Kein „standardmäßig aus“** für neue Funktionen. Änderungen mit Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen werden stattdessen in den Release-Notizen und im Update-Paket sichtbar | [Update-Paket](#das-update-paket), [Automatisierung](#automatisierung-und-drift-erkennung); der Vorschlag R1 steht unter [Offene Fragen](#offene-fragen--zukünftige-erweiterungen) als abgelehnt |
| 2 | **Sicherheitsupdates für bestimmte Versionen mit festem Supportzeitraum** sind gewollt; die Ausgestaltung prüft #2575 | [Dienstvereinbarung](#das-modell-der-dienstvereinbarung), Abschnitt Sicherheitsupdates |
| 3 | **Kein rechtlicher Prüfer.** Redaktionelle Vorlagen tragen den Hinweis „Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.“ | [Kopfvermerk und Haftungshinweis](#kopfvermerk-und-haftungshinweis) |
| 4 | **Keine Aussage zur Codeentstehung durch KI-Agenten** in den Markterkundungsunterlagen. Auf ausdrückliche Nachfrage lautet die Antwort: hauptsächlich KI-generiert und menschlich kuratiert | [Behördenreise](#die-behördenreise), Phase 1 |
| 5 | **Ablage als Markdown in `docs/behoerden/`**, nicht im Handbuch, kein eigener Branch (Ordner und Konventionen: #2545) | [Erzeugungsarten und Ablage](#erzeugungsarten-und-ablage) |
| 6 | **Zunächst nur Behörden.** Unternehmen (BetrVG, ISO 27001, NIS2 für Unternehmen) folgen später | [Offene Fragen](#offene-fragen--zukünftige-erweiterungen) als zurückgestellt |
| 7 | **Automatisch mitziehen:** generieren, was generierbar ist; CI-Wächter, Release-Workflow und Agentenorganisation sichern das ab | [Faktenmodell](#das-faktenmodell), [Automatisierung](#automatisierung-und-drift-erkennung), [Agentenorganisation](#verankerung-in-der-agentenorganisation) |
| 8 | **Kein Dienstleisterverzeichnis und keine Geschäftsmodell-Themen** (Partner, Trägerfirma) in diesem Vorhaben | [Offene Fragen](#offene-fragen--zukünftige-erweiterungen) als abgelehnt |

---

## Die Behördenreise

Die Unterlagen folgen dem Entscheidungsweg einer Behörde, nicht der Struktur des Produkts. Die
Reihenfolge ist nach der Stakeholder-Prüfung (Betrieb, Personalrat, Skeptiker, Referatsleitung)
bewusst so gewählt: Die Entscheidung der Hausleitung steht vorn, und die Pilot-Beteiligung kommt vor
der Teststellung, weil auch eine Teststellung mit echten Beschäftigten oder Daten
mitbestimmungspflichtig sein kann.

| Phase | Schritt | Vorlagen (redaktionell) | Liest aus den Fakten | Issue |
|---|---|---|---|---|
| 0 | Entscheidung der Hausleitung | Entscheidungsvorlage mit Nutzen, Kostenspanne (Hardware/GPU, Betrieb, Personal; Annahmen offengelegt), Risiken, nächsten Schritten; Verweis auf WiBe | Lizenzübersicht | #2562 |
| 1 | Markterkundung | Produktdatenblatt, Antworten auf typische Fragebögen, Projektstatus (Trägerschaft, Fortbestand, Release-Zyklus), Lizenz einschließlich Abhängigkeiten, Reifegrad (nur Belegbares), Meldestelle für Sicherheitslücken | Lizenzübersicht, Faktenblätter | #2562, #2561 |
| 2 | Pilot-Beteiligung | Pilot-Vereinbarung mit statischer Anlage, Kurzprüfung je Stelle (DSB, ISB, Personalrat) | Datenkatalog der eingesetzten Version | #2563 |
| 3 | Teststellung | Szenario mit **eigenen Dokumenten der Behörde**, Erfolgs- und Abbruchkriterien vor Beginn, Evaluierungswerte mit Version, Teststellungsvereinbarung, Löschnachweis | Faktenblätter, Antwortqualität | #2563 |
| 4 | Vergabe | herstellerneutrale Anforderungsbausteine, Hinweise zur Zuordnung in den EVB-IT (Fassung 2026) | SBOM, Lizenzübersicht | #2564 |
| 5 | Einführung: Datenschutz | VVT-, DSFA-Baustein, TOM des Produktanteils, Löschkonzept, Informationspflichten, Betroffenenrechte, AVV-Entscheidungsmatrix | Datenkatalog, Fristen, Datenflüsse, Voreinstellungen | #2565 |
| 5 | Einführung: Mitbestimmung | Muster-Dienstvereinbarung, Informationsmappe für den Personalrat | alle Faktenblätter, Konfigurationsexport | #2566 |
| 5–6 | Informationssicherheit, Betrieb, Notfall | Grundschutz-Modellierung, C5:2026-Zuordnung nur für Cloud-Betreiber, NIS2-Hinweis, Notfallkapitel mit RTO/RPO-Annahmen und Wiederherstellungsprobe | Protokollereignisse, Datenflüsse | #2567 |
| 5 | KI-Verordnung, Cyber Resilience Act | Einstufungshilfe KI-VO, Rollenklärung CRA | Voreinstellungen, Prompts, Standardmodelle | #2568 |
| 5 | Barrierefreiheit, Nutzung | Erklärung zur Barrierefreiheit, Muster-Nutzungsrichtlinie | vorhandene Prüfbelege | #2569 |
| 7 | Update | Update-Paket mit Vorlage Update-Vermerk | Delta zwischen zwei Versionen | #2552 |

**Markterkundung und Codeentstehung (Beschluss 4):** Die Markterkundungsunterlagen enthalten keine
Aussage dazu, wie der Code entstanden ist. Fragt eine Behörde ausdrücklich danach, lautet die Antwort:
hauptsächlich KI-generiert und menschlich kuratiert. Diese Antwort steht in keiner Vorlage als eigene
Aussage.

**Projektstatus nach Beschluss 8:** Der Projektstatus nennt Trägerschaft, Maintainer, Fortbestand und
Release-Zyklus. Ein Verzeichnis von Dienstleistern, Partnerprogramme und Fragen einer Trägerfirma
gehören nicht dazu.

---

## Erzeugungsarten und Ablage

Die Unterlagen liegen als Markdown in `docs/behoerden/` (Beschluss 5). Der Ordner gliedert sich nach
der Behördenreise, Verzeichnisnamen ohne Umlaute (Vorschlag aus #2545: `00-entscheidung/` bis
`09-betrieb-update/`). Das Handbuch beschreibt das Produkt; `docs/behoerden/` beschreibt die Verfahren
darum herum.

| Erzeugungsart | Was | Wo | Eingecheckt |
|---|---|---|---|
| **generiert** | Faktenblätter, JSON-Fassung, Update-Delta, Lizenzübersicht | CI-Artefakt jedes `main`-Laufs, Anhang am GitHub-Release | nein — vermeidet Konflikte zwischen parallelen PRs |
| **redaktionell** | Vorlagen entlang der Behördenreise | `docs/behoerden/` | ja |

Das Manifest selbst ist Quelltext, keine Dokumentation: Es liegt im Klassenpfad des Backends
(`backend/src/main/resources/compliance/manifest/`), weil der Instanzexport es zur Laufzeit liest, und
läuft durch dasselbe Review wie Code. Ablage, Schema und Prüfsummen regelt
[ADR-0052](../decisions/0052-compliance-manifest.md).

### Kopfvermerk und Haftungshinweis

Jedes redaktionelle Dokument beginnt mit einem maschinenlesbaren Kopfvermerk (YAML-Front-Matter, Felder
aus #2545):

```yaml
stand: 2026-10-13               # letzte inhaltliche Durchsicht
gilt-fuer: main                 # oder eine OPAA-Version
erzeugung: redaktionell
rechtsstand: 2026-10-13         # Datum, zu dem Normen und Quellen geprüft wurden
pruefvermerk: nicht rechtlich geprüft
naechste-durchsicht: 2027-04-13
```

Darunter steht wörtlich der Haftungshinweis (Beschluss 3):

> Nicht rechtlich geprüft. Kann als Vorlage dienen; die Verantwortung bleibt bei der Behörde.

Eine redaktionelle Vorlage ohne Prüfstand wird **nicht** mit der aktuellen Version gestempelt: `gilt-fuer`
nennt den Stand, gegen den sie zuletzt durchgesehen wurde. Generierte Dokumente tragen stattdessen
OPAA-Version, Commit, Erzeugungsdatum, `erzeugung: generiert` und den Hinweis „Fakten aus dem Code;
rechtliche Bewertung bleibt bei der Behörde“.

### Zwei Regeln für Vorlagen

- **Fakten verweisen, nicht abschreiben.** Eine Vorlage nennt keine Frist, kein Ereignis und keinen
  Datenfluss als Wert, sondern verweist auf das Faktenblatt der jeweiligen Version.
- **Amtliche Muster verlinken statt nachbauen.** Wo eine Aufsichtsbehörde, das BSI, der IT-Planungsrat
  oder die Bundesfachstelle ein Muster veröffentlicht, verlinkt die Vorlage darauf.

Rechtsquellen aus Fachblogs werden vor der Übernahme in eine Vorlage gegen den Normtext geprüft; der
Kopfvermerk hält das Datum dieser Prüfung als `rechtsstand` fest.

---

## Das Faktenmodell

### Quellen im Code

| Baustein | Quelle | Stand heute |
|---|---|---|
| Protokollereignisse, Rollen, Fähigkeiten | Enums der geteilten API-Typen (`AuditEventType` mit rund 130 Werten, `SystemRole`) | maschinenlesbar |
| Tabellen und Spalten | Liquibase-Changelogs | maschinenlesbar |
| Fristen | Aufbewahrungs-Konfiguration, Teile in der Datenbank | Datenbankteil nur über den Instanzexport |
| Konfiguration und Voreinstellungen | rund 50 Konfigurationsklassen, Helm-Schema | **Lücke:** keine Konfigurationsmetadaten |
| System-Prompts, Standardmodelle | Quellcode der Antwort- und Suchstrecke, Anwendungskonfiguration | nicht erfasst |
| Ausgehende Datenflüsse | `@DataFlow` an den sendenden Klassen, Fachtext im Manifest (`outbound.yaml`, ADR-0052, Entscheidung 8) | maschinenlesbar; per Strukturtest vollständig für die Typen in `OUTBOUND_CLIENTS`/`OUTBOUND_BEANS` |
| Lizenzen | implizit im CycloneDX-SBOM | **Lücke:** keine Lizenzprüfung |

### Das Manifest

Das Fakten-Manifest beschreibt in einer Datei je Bereich (Protokoll, Konten und Rollen, Chats,
Bibliotheken und Indexierung, Verbindungen, Konfiguration, Prompts, Datenflüsse), welche Daten OPAA
erhebt und verarbeitet. Jeder Eintrag hat feste Pflichtfelder (#2546):

| Eintragsart | Pflichtfelder |
|---|---|
| Datum (Tabelle, Spalte oder Datenkategorie) | Zweck, Personenbezug (nein / personenbeziehbar / Beschäftigtendaten), Speicherort, Frist mit Konfigurationsschlüssel, Sichtbarkeit (Rollen), Zugriffsweg (Oberfläche / API / Export / Datenbank), Löschmechanismus, mögliche Auswertungen |
| Protokollereignis | wie Datum, zusätzlich Auslöser, Felder des Protokollsatzes und wer zu welchem Zweck liest |
| Rolle / Fähigkeit | was die Rolle über andere Personen sieht („was sieht wer über mich“) |
| Voreinstellung | Schlüssel, Standardwert, Wirkung, Bezug zu Daten, Sichtbarkeit, Auswertung oder Datenfluss |
| System-Prompt / Standardmodell | Fundstelle, Zweck, Prüfsumme des Inhalts |
| Ausgehender Datenfluss | Ziel, übermittelte Daten, Anlass, Abschaltbarkeit, zuständiger Konfigurationsschlüssel |
| KI-Funktion | Zweck, Art der Ausgabe (Text / Audio / Bild), direkte Interaktion mit Personen, Bewertung oder Profiling von Personen, vorhandene Kennzeichnung nach Art. 50 KI-VO, gesendete System-Prompts; die Einstufung selbst ist Wertung der Behörde und steht nicht im Manifest |

Optional trägt ein Eintrag **Hinweise** auf mögliche rechtliche Relevanz, etwa „Beschäftigtendaten,
§ 80 Abs. 1 Nr. 21 BPersVG prüfen“ — ausdrücklich als Hinweis, nicht als Einstufung. Die Pflichtfelder
je Protokollereignis werden mit dem Ereignisvertrag aus dem Audit-Trail-Review (#1296) abgeglichen.

Konfigurationsschlüssel ohne Bezug zu Daten, Sichtbarkeit, Auswertung oder Datenfluss dürfen pauschal
als „ohne Datenwirkung“ markiert werden. Spalten ohne Personenbezug tragen keine eigenen Angaben: Sie
stehen nur mit Namen in `nonPersonalColumns` ihrer Tabelle oder, bei einer Tabelle ganz ohne
Personenbezug, in deren Spaltenliste unter `nonPersonalTables`. Das hält das Manifest klein und lässt
trotzdem jede neue Spalte auffallen.

### Der Wächter und seine Grenze

Ein Vollständigkeitswächter lässt den Build scheitern, wenn eine Seite ohne Gegenstück ist:

- ein Wert von `AuditEventType`, `SystemRole` oder den Fähigkeiten ohne Manifest-Eintrag (#2547)
- eine Tabelle oder Spalte des Liquibase-Stands ohne Eintrag (#2547)
- ein System-Prompt, dessen Prüfsumme nicht zum Manifest passt (#2547)
- ein Konfigurationsschlüssel ohne Eintrag oder Pauschalmarkierung, oder ein geänderter Standardwert
  eines datenwirksamen Schlüssels ohne angepassten Eintrag (#2548)
- ein ausgehender Client ohne Registry-Zuordnung, ein Registry-Eintrag ohne Manifest-Eintrag (#2549)
- umgekehrt ein Manifest-Eintrag ohne Gegenstück im Code (verwaister Eintrag)

**(gebaut für #2547)** `ComplianceManifestTest` prüft Enums, Tabellen, Spalten und Prompt-Prüfsummen in
beiden Richtungen. Er liest den Liquibase-Stand ohne Datenbank aus den Changelogs und läuft deshalb in
jedem Testlauf, auch lokal ohne Docker; DDL, die er nicht sicher lesen kann, lässt ihn scheitern statt
sie zu überspringen. Seine Meldung nennt Datei und einzufügendes YAML; die Konvention steht in
`backend/docs/compliance-manifest.md`.

**Die Grenze:** Der Wächter prüft, **dass** ein Eintrag existiert, nicht, **ob er stimmt**. Ein
Platzhalter wie „Zweck: siehe Code“ macht den Build grün. Ob Zweck, mögliche Auswertungen, Sichtbarkeit
und Stufenvorschlag stimmen, sichern nur das Code-Review (#2572), die Drift-Routine
([Schicht 3](#schicht-3-wöchentliche-drift-routine)) und die Rolle „Behördenkontakt“ (#2573). Wer
die Unterlagen nach außen vertritt, sagt das genauso: Die Vollständigkeit ist maschinell belegt, die
Richtigkeit durch Prüfung.

### Faktenblätter

Aus dem Manifest entstehen bei jedem Lauf auf `main` (#2550):

- **Datenkatalog** — alle Daten mit Personenbezug und ihren Pflichtfeldern
- **Protokollereignisse** — je Ereignis Frist, Speicherort, Zugriffsweg, Löschmechanismus, Leser und Zweck
- **Rollensicht** — Matrix Rolle × Datenkategorie, „was sieht wer über mich“
- **Datenflüsse** nach außen
- **Voreinstellungen** mit Standardwert und Wirkung, einschließlich Standardmodelle und Prompt-Prüfsummen
- **Fristen** mit Konfigurationsschlüssel
- eine **JSON-Fassung** aller Inhalte für Werkzeuge und für das Delta

Die Faktenblätter beschreiben die Voreinstellung einer Version. Was eine konkrete Instanz tatsächlich
tut, beschreibt erst der [Instanzbezug](#instanzbezug).

---

## Automatisierung und Drift-Erkennung

Ziel ist, dass eine relevante Änderung — vor allem an Datenflüssen, Protokollereignissen, Fristen,
Voreinstellungen, System-Prompts und Standardmodellen — **garantiert an mindestens einer Stelle
auftaucht**, und dass der Folgeaufwand je Änderung klein bleibt. Keine einzelne Schicht leistet beides:
Ein Wächter erzwingt Existenz, aber nicht Richtigkeit; eine Pfadliste erzwingt eine Entscheidung über
die Kennzeichnung, kennt aber nur die Pfade, die auf ihr stehen; eine Prüfroutine liest den Inhalt,
kommt aber erst nach dem Merge. Drei Schichten ergänzen sich deshalb:

| Schicht | Zeitpunkt | Art | Erzwingt | Liefert |
|---|---|---|---|---|
| 1 · CI-Wächter | jeder Build | deterministisch, blockierend | Existenz eines Manifest-Eintrags | #2547 (gebaut), #2548, #2549 (Zielbild) |
| 2 · Kennzeichnung am PR | jeder PR | deterministisch, blockierend | Label `data-impact` oder Angabe „Keine Wirkung“ | #2553 (PR #2820) |
| 3 · Drift-Routine | wöchentlich | Agent, nur lesend | inhaltliche Richtigkeit, Verhaltensänderungen ohne Schemaänderung, Lücken der Pfadliste | eingerichtete Claude-Routine (gebaut) |

Der Release-Lauf führt Schicht 1 zusätzlich vor jeder Veröffentlichung aus und bricht bei einem
unvollständigen Manifest ab, **bevor** Images erscheinen (#2556).

### Schicht 1: deterministische CI-Wächter

Der [Wächter](#der-wächter-und-seine-grenze) läuft im regulären Testlauf eines CI-Shards. Er ist auf
das beschränkt, was sich ohne Wertung entscheiden lässt: Gibt es zu jedem Gegenstand im Code einen
Eintrag und umgekehrt? Seine Fehlermeldung nennt den fehlenden Eintrag, die Datei des Bereichs und die
Konvention, damit die Nachpflege ohne Suchen gelingt.

Eine neue Tabelle oder Spalte trifft Schicht 1 und 2 zugleich, ohne dass sie dasselbe prüfen: Der
Wächter verlangt den Manifest-Eintrag, die Pfadliste (`db/changelog/**`, `compliance/manifest/**`) die
Entscheidung über das Label. Ob der Eintrag stimmt, etwa eine Spalte zu Recht ohne Personenbezug steht,
bleibt Schicht 3.

Für Prompts und Standardmodelle ist die Prüfsumme der Hebel: Eine Änderung am Prompt-Text scheitert,
bis die Prüfsumme bewusst nachgezogen ist, und taucht damit im Delta als Verhaltensänderung auf — auch
ohne Schemaänderung.

### Schicht 2: Kennzeichnung datenrelevanter Änderungen am PR

Jeder PR mit Datenwirkung trägt das Label `data-impact` und einen Satz in Alltagssprache für die
Release-Notizen; die Release-Notizen führen solche PRs unter „Datenrelevante Änderungen“. Ein eigener
Workflow (`.github/workflows/data-impact.yml`, #2553) prüft das an jedem PR. Er läuft auch bei
`labeled`, `unlabeled` und `edited`, sodass ein nachträglich gesetztes Label oder eine nachgetragene
Angabe sofort wirkt.

Grundlage ist eine **statische Pfadliste** (`.github/data-impact-paths.txt`) mit den Stellen, an denen
Datenwirkung typischerweise entsteht: Liquibase-Changelogs, die Enums der Protokollereignisse, Rollen
und Fähigkeiten, Konfigurationsklassen und Anwendungskonfiguration einschließlich der Standardmodelle,
ausgehende Clients, die Fundstellen der System-Prompts. Ändert ein PR eine gelistete Datei und trägt
weder das Label `data-impact` noch die Angabe „Keine Wirkung“, **schlägt die Prüfung fehl**. Die
Entscheidung, ob eine Wirkung vorliegt, bleibt beim Autor und im Review; die Prüfung erzwingt nur, dass
sie getroffen wird.

**Pflege der Liste:** Ein Selbsttest prüft die Liste gegen veraltete Muster, die auf keine Datei mehr
passen. Lücken — datenrelevanter Code außerhalb der Liste — findet die Drift-Routine (Prüffrage d) und
schlägt eine neue Zeile vor. So bleibt die Liste klein und von Hand lesbar, ohne still zu veralten.

### Schicht 3: wöchentliche Drift-Routine

**(gebaut)** Die geplante Claude-Routine „Compliance-Drift-Prüfroutine (Behördenunterlagen)“ läuft
sonntags um 08:37 Uhr (Europe/Berlin), nach der Architektur-Prüfroutine aus
[ADR-0051, Entscheidung 7](../decisions/0051-architekturvertrag-des-backends.md#7-prüfroutine), deren
Muster sie folgt. Sie liest die Änderungen auf `main` der letzten sieben Tage und hält sie gegen
Manifest, Pfadliste und Vorlagen:

- **(a)** Ein PR mit Datenwirkung trägt das Label `data-impact` nicht.
- **(b)** Ein Manifest-Eintrag ist sachlich falsch (Zweck, mögliche Auswertungen, Sichtbarkeit, Frist)
  — nicht nur, ob er existiert.
- **(c)** Eine Verhaltensänderung ohne Schemaänderung: neue Auswertung bestehender Daten in der
  Oberfläche, geänderte Sortierung oder Filterung mit Personenbezug, geänderter Standardwert, Prompt
  oder Standardmodell.
- **(d)** Datenrelevanter Code liegt außerhalb der Pfadliste aus Schicht 2; die Routine schlägt die
  neue Zeile vor.
- **(e)** Eine redaktionelle Vorlage schreibt einen Faktenwert ab, der sich geändert hat.

Jeder Kandidat wird von einem zweiten Prüfer nachgestellt, bevor er gemeldet wird. Die **Ausgabe** ist
ein Bericht mit zwei Listen: „Neu“ (abgeglichen mit den offenen Issues, damit nichts Bekanntes erneut
erscheint) und „Unverändert offen“ (offene Issues mit Label `data-impact` bzw. aus Epic #2542). Eine
Liste „Behoben seit letztem Lauf“ gibt es nicht, weil die Routine keinen Bericht speichert. Der Bericht
geht dem Maintainer als Push-Benachrichtigung zu.

**Grenzen:** Die Routine ist nur lesend. Sie ändert keinen Code, kein Manifest und keine Vorlage, legt
keine Issues an und setzt keine Labels. Anders als die Architektur-Prüfroutine verzichtet sie bewusst
auf eigene Issues: Ihre Befunde betreffen oft die Bewertung einer Datenwirkung, und die soll ein Mensch
treffen, bevor sie als Arbeitsauftrag erscheint.

Den in #2573 als „Außerhalb des Umfangs“ genannten zeitgesteuerten Lauf deckt diese Routine ab; die
Rolle „Behördenkontakt“ läuft anlassbezogen vor jedem Release und nutzt die Berichte der Routine mit.

---

## Das Update-Paket

Zu jedem Release entsteht ein Update-Paket, mit dem eine Behörde ein Update gegenüber Hausleitung,
Datenschutzbeauftragten, Personalrat und ISB verantworten kann, ohne Diffs zu lesen. Es ist an Tag
und Commit gebunden, attestiert wie Images und Chart und als Offline-Bündel erhältlich.

### Inhalt

| # | Teil | Issue |
|---|---|---|
| 1 | **Manifest-Delta** je Datum, Ereignis, Rolle, Datenfluss, Frist und Voreinstellung, vorher/nachher, in Alltagssprache | #2554 |
| 2 | **Verhaltensänderungen ohne Schemaänderung:** Standardmodell, System-Prompts (Prüfsummenwechsel), geänderte Voreinstellungen, neue Auswertungen bestehender Daten | #2554 |
| 3 | **Betriebsfolgen:** Bruch ja/nein mit Vorbereitungsschritten, Neuindizierung mit Dauerschätzung, Schemamigration (nur vorwärts, Sicherung vorher), neue Ports oder Egress-Ziele | #2555 |
| 4 | **Antwortqualität:** Regressionswerte der Evaluierung vor und nach dem Update, mit Version und Messdatum; fehlt ein Wert, steht das ausdrücklich da | #2555 |
| 5 | **Hinweise mit Begründung:** Stufenvorschlag, Bezug zu VVT, DSFA und Sicherheitskonzept | #2554 |
| 6 | **Weitere gekennzeichnete Änderungen:** PRs mit `data-impact`, deren Wirkung nicht im Manifest steht | #2553, #2554 |
| 7 | **Instanzbezug:** welche Änderungen eine konkrete Instanz betreffen | #2558, #2559 |
| 8 | **Vorlage Update-Vermerk:** Version, Delta, Bewertung je Stelle, Entscheidung, Name, Datum | #2556 |
| 9 | Faktenblätter der neuen Version und Lizenzübersicht | #2550, #2551 |

Das Paket hängt als Archiv mit Prüfsummendatei am GitHub-Release und trägt eine
Provenance-Attestierung (#2556). Das Offline-Bündel enthält zusätzlich Images beider Architekturen,
Chart, SBOMs und Attestierungen für Netze ohne Internetzugang (#2557).

### Das kumulierte Delta

Behörden spielen selten jedes Release ein. Das Delta lässt sich deshalb für **jedes Versionspaar**
erzeugen (etwa 0.12 → 0.17), lokal und im Release-Lauf. Es vergleicht die Manifeste beider Stände
direkt, nicht die Kette der Einzeldeltas: Ein Eintrag, der zwischendurch hinzukommt und wieder entfällt,
erscheint nicht; ein Wert, der sich mehrmals ändert, erscheint einmal mit Anfangs- und Endstand. Das
Delta über drei Versionen ist damit gleich der Zusammenfassung der Einzeldeltas.

Am Release wird das Delta zum vorigen finalen Release erzeugt, bei Vorab-Releases ebenso. Welches
Release bei mehreren Supportlinien als Vorgänger gilt, folgt aus der Entscheidung in #2575; bis dahin
ist es das letzte finale Tag auf `main`.

### Die Stufenregeln

Jede Änderung im Delta erhält einen **Stufenvorschlag** nach dem Stufenmodell gängiger
IT-Rahmendienstvereinbarungen (Bagatelle / Anzeige / Zustimmung). Diese Regeln sind die **gemeinsame
Quelle** für das Delta-Werkzeug (#2554) und die Zustimmungsklassen der Muster-Dienstvereinbarung
(#2566); beide übernehmen sie wörtlich und verweisen hierher.

**Zustimmung** — wenn die Änderung mindestens eine dieser Klassen erfüllt:

| Klasse | Änderung |
|---|---|
| Z1 | neue personenbezogene Daten oder neue Protokollereignisse |
| Z2 | neue Sichtbarkeit: eine Rolle sieht mehr über andere Personen als vorher |
| Z3 | neue Auswertung oder neuer Export |
| Z4 | längere Frist |
| Z5 | neuer externer Datenfluss oder neues Ziel eines bestehenden |
| Z6 | geänderte KI-Einstufung |
| Z7 | geänderte Voreinstellung mit Datenwirkung, einschließlich eines Standardmodells, das einen Datenfluss ändert |

**Anzeige** — Änderungen ohne neue Datenwirkung, die Beschäftigte dennoch wahrnehmen: kürzere Frist,
entfallene Daten, Felder, Ereignisse oder Datenflüsse, eingeschränkte Sichtbarkeit, geänderter
System-Prompt oder geändertes Standardmodell ohne Klasse Z1–Z7.

**Bagatelle** — nur nach abschließender Liste:

- redaktionelle Präzisierung eines Manifest-Eintrags ohne inhaltliche Änderung
- neuer Konfigurationsschlüssel mit der Markierung „ohne Datenwirkung“
- neue Spalte oder Tabelle ohne Personenbezug

**Im Zweifel Zustimmung.** Eine Änderung, die keiner Liste eindeutig zugeordnet werden kann, erhält
den Vorschlag „Zustimmung“ mit dem Vermerk, warum die Zuordnung offen ist.

Je Änderung nennt das Delta zusätzlich, ob das VVT anzupassen (Änderung an Zweck, Datenkategorie,
Empfänger oder Frist), die DSFA zu überprüfen und das Sicherheitskonzept fortzuschreiben ist (neue
Datenflüsse, Ports, Egress-Ziele, Rollen oder Protokollereignisse). Rechtsgrundlage der
DSFA-Überprüfung ist Art. 35 Abs. 11 DSGVO, der an eine Änderung des Risikos anknüpft; dass das Delta
sie bei jeder Änderung der Stufe Zustimmung vorschlägt, ist eine **Heuristik**, keine Ableitung aus der
Norm. Der Kopf des Deltas sagt: Stufen und Bezüge sind Vorschläge, die Bewertung trifft die Behörde.

### Instanzbezug

Eine geänderte Voreinstellung betrifft nur Instanzen, die sie nicht überschrieben haben; ein neuer
Konnektor nur, wer ihn einschaltet. Umgekehrt ist eine von der Dienststelle verlängerte Frist ebenso
beteiligungsrelevant wie eine Produktänderung. Deshalb:

- Eine Instanz erzeugt die **Auskunft über die Datenerhebung** samt Konfigurationsexport: das Manifest
  der eingesetzten Version, verbunden mit den wirksamen Werten der Instanz, ohne Geheimnisse und ohne
  personenbezogene Daten; jeder Abruf wird protokolliert (#2558). Das ist der in
  [security-and-compliance.md](./security-and-compliance.md#export-und-auskunft) beschriebene, bisher
  nicht gebaute zweite Export.
- Ein Werkzeug gleicht das Delta gegen diesen Export ab und kennzeichnet je Eintrag „betrifft diese
  Instanz“ oder „betrifft sie nicht“; der Vergleich zweier Exporte zeigt zusätzlich die „Änderungen der
  Dienststelle“ mit denselben Stufenregeln. Es läuft offline auf Dateien, auch beim
  Datenschutzbeauftragten (#2559).

---

## Das Modell der Dienstvereinbarung

Die Muster-Dienstvereinbarung (#2566) soll nicht bei jedem Release neu verhandelt werden müssen und
den Überwachungsumfang trotzdem nicht unbemerkt wachsen lassen. Sie hat drei Teile.

**Haupttext.** Zweck; Verbot der Leistungs- und Verhaltenskontrolle mit Verwertungsverbot;
Auswertungsverbot einschließlich Datenbank- und Protokollzugriff, mit benannten Rollen und
Vier-Augen-Prinzip; Negativliste; Kontrollrechte des Personalrats; Kündigung und Nachwirkung.
Auswertungsverbot und Negativliste stehen im Haupttext, nicht in den Anlagen, damit eine geänderte
Anlage sie nicht aushebeln kann.

**Statische Anlagen.** Datenkatalog, Rollensicht, Datenflüsse, Voreinstellungen und Konfigurationsexport
der Instanz — jeweils **auf eine Version festgelegt**. Die Formulierung „in der jeweils gültigen
Fassung“ ist verworfen: Sie würde jede Erweiterung des Datenumfangs ohne Beteiligung in die
Vereinbarung holen.

**Stufenmodell für Änderungen.** Abschließende Bagatell-Liste; Anzeige mit Widerspruchsfrist
(Zustimmungsfiktion); Zustimmung bei den festgeschriebenen Klassen Z1–Z7. Die Klassen sind dieselben
wie in den [Stufenregeln](#die-stufenregeln); das Update-Paket liefert damit zu jedem Update den
passenden Vorschlag, und die neue Fassung der Anlagen ersetzt die alte erst nach Abschluss der Stufe.

**Sicherheitsupdates (Beschluss 2).** Kritische Sicherheitsupdates werden sofort eingespielt, mit
nachträglicher Unterrichtung des Personalrats. Das trägt nur, wenn sie keine neuen Funktionen
enthalten — deshalb verweist die Vereinbarung auf die Supportlinien mit festem Zeitraum aus #2575. Heute entsteht ein Patch-Release mit
Codeänderung nur, solange `main` keine neue Funktion trägt ([releases.md](../releases.md#versionsnummern)).

**Informationsmappe für den Personalrat.** Rollensicht, Beispiele möglicher und ausgeschlossener
Auswertungen, Datenfluss zum Modellanbieter, Wirkung des Abschaltens einzelner Funktionen, Glossar,
Hinweis auf eine Testinstanz.

---

## Verankerung in der Agentenorganisation

Beschluss 7 verlangt, dass Prozesse das Mitziehen sichern, nicht guter Wille. Die Pflege hängt deshalb
an jedem Schritt des Workflows aus [AGENT-ORGANIZATION.md](../AGENT-ORGANIZATION.md):

| Schritt | Rolle | Pflicht | Issue |
|---|---|---|---|
| Spezifikation | Product Manager | Abschnitt „Wirkung auf Behördenunterlagen“ in jeder Spezifikation (Daten, Sichtbarkeit, Auswertung, Fristen, Datenflüsse, Voreinstellungen, KI-Einstufung, voraussichtliche Stufe, betroffene Vorlagen); bei Stufe Zustimmung Stakeholder-Review mit Personalrat und Betrieb vor dem Schneiden der Issues; **kein** Punkt „standardmäßig aus“ | #2571 |
| Umsetzung | Developer | Manifest im selben PR inhaltlich pflegen, PR mit `data-impact` kennzeichnen | #2572 |
| Review | Code Reviewer | Manifest gegen den Code halten, mögliche Auswertungen vollständig, Stufenvorschlag plausibel; fehlender oder falscher Eintrag und fehlendes Label bei Datenwirkung sind wichtige Befunde | #2572 |
| Release | Behördenkontakt | vor jedem Release Manifest und Update-Paket aus Behördensicht lesen, Hinweise entwerfen, bei Stufe Zustimmung ein Stakeholder-Review anstoßen, überschrittene `naechste-durchsicht` melden; **keine** verbindliche Rechtsbeobachtung, keine Rechtsberatung | #2573 |
| Release | Maintainer | Delta zum vorigen Release vor dem Tag erzeugen, Befunde des Behördenkontakts klären oder als bekannt in die Release-Notizen aufnehmen | #2574 |

---

## Rechtlicher Rahmen (Hinweise)

Die Recherche vom 13.10.2026 liefert die Fundstellen, an denen sich die Vorlagen orientieren. Sie sind
**Hinweise, kein Rechtsrat**; Fundstellen aus Fachblogs werden vor Übernahme in eine Vorlage gegen den
Normtext geprüft. Die vollständige Tabelle mit Quellen steht im ersten Kommentar von #2542.

| Thema | Hinweis | Vorlage |
|---|---|---|
| Markterkundung, Produktneutralität | § 28 VgV oberhalb der EU-Schwellenwerte, darunter § 20 UVgO bzw. Landesrecht; § 31 Abs. 6 VgV („oder gleichwertig“) | #2562, #2564 |
| Wirtschaftlichkeit | § 7 BHO/LHO, in der Praxis WiBe | #2562 |
| Datenschutz | Art. 13, 25, 28, 30, 32, 35 (einschließlich Abs. 11) DSGVO; § 26 BDSG bzw. Landesrecht; DSK-Orientierungshilfe „KI und Datenschutz“ | #2565 |
| Mitbestimmung | § 80 Abs. 1 Nr. 19–21 BPersVG (nur Bund, Länder eigene Gesetze); objektive Eignung zur Überwachung genügt (BVerwG 5 P 16.21); erneute Beteiligung bei wesentlicher Änderung | #2566 |
| Informationssicherheit | BSI-Standard 200-2, Bausteine OPS.1.1.3 und CON.8; NIS2UmsuCG für die Bundesverwaltung; C5:2026 testiert Cloud-Anbieter, nicht Software | #2567 |
| KI-Verordnung | Art. 4 und 50 in der Fassung des Digital-Omnibus; Anhang III ab 2.12.2027; Rolle als Anbieter beim Betrieb unter eigenem Namen | #2568 |
| Cyber Resilience Act | Meldepflichten seit 11.9.2026; Rolle des Projekts als Steward, kommerzielle Bereitstellung als Hersteller | #2561, #2568 |
| Barrierefreiheit | BITV 2.0 auch im Intranet (§ 12a BGG); Länder mit eigenen Gesetzen | #2569 |
| Vergabe | EVB-IT in der Fassung 2026 (Open Source als Regelfall, SBOM und Programmstände in Pflege S) | #2564 |
| VS-NfD | braucht ein zugelassenes Produkt; OPAA hat keine Zulassung | #2567 |

---

## Integrationspunkte

- **[security-and-compliance.md](./security-and-compliance.md)** — der
  [Auszug für die Personalvertretung](./security-and-compliance.md#der-auszug-für-die-personalvertretung)
  und die [Auskunft über die Datenerhebung](./security-and-compliance.md#export-und-auskunft) werden
  aus dem Manifest gespeist; die Mitbestimmungsfähigkeit ist der Inhalt, den die Dienstvereinbarung
  festschreibt; die C5-Fähigkeit trägt die Vorlage zur Informationssicherheit
- **[public-sector.md](./public-sector.md)** — Barrierefreiheit (Erklärung zur Barrierefreiheit,
  #2569) und Revisionssicherheit (Anbietungspflicht an das Archiv im Löschkonzept, #2565)
- **[releases.md](../releases.md)** — Release-Workflow, Attestierung, Upgrade-Hinweise als Quelle der
  Betriebsfolgen, Supportlinien nach #2575
- **[sbom.md](../sbom.md)**, **[cve-scanning.md](../cve-scanning.md)** — Nachweise, auf die
  Vergabe- und Sicherheitsvorlagen verweisen
- **[search-quality-evaluation.md](./search-quality-evaluation.md)** — Quelle der Antwortqualität im
  Update-Paket und der Evaluierungswerte der Teststellung
- **[ADR-0051](../decisions/0051-architekturvertrag-des-backends.md)** — Vorbild der Drift-Routine
- **[AGENT-ORGANIZATION.md](../AGENT-ORGANIZATION.md)** — Rollen und Stakeholder-Review

---

## Offene Fragen / Zukünftige Erweiterungen

**Abgelehnt oder zurückgestellt — festgehalten, nicht gestrichen:**

- **R1 „standardmäßig aus“** (abgelehnt, Beschluss 1): Neue Funktionen mit Datenwirkung sollten nach
  dem ersten Konzeptentwurf ausgeschaltet ausgeliefert werden. Stattdessen werden sie in
  Release-Notizen und Update-Paket sichtbar. Offen bleibt die Folge für die Dienstvereinbarung: Reicht
  die Abschaltbarkeit in der Konfiguration, wenn ein Release eine beteiligungspflichtige Funktion
  sofort aktiv mitbringt, oder braucht es dafür die Supportlinien aus #2575? Klärung gemeinsam mit
  #2566 und #2575.
- **Unternehmen** (zurückgestellt, Beschluss 6): Unterlagen für Betriebsrat (§ 87 Abs. 1 Nr. 6
  BetrVG), ISO 27001, NIS2 für Unternehmen und die CRA-Herstellerrolle folgen in einem eigenen
  Vorhaben.
- **Formatkonvertierung** (zurückgestellt): PDF, ODT oder DOCX erst bei Bedarf aus der Praxis;
  zunächst nur Markdown.
- **Dienstleisterverzeichnis und Geschäftsmodell** (abgelehnt, Beschluss 8).
- **Rechtliche Prüfung** (abgelehnt, Beschluss 3): Gibt es eine Partnerbehörde, die einzelne Vorlagen
  probeweise durchgeht? Das wäre der einzige realistische Weg zu einem Prüfvermerk über „nicht
  rechtlich geprüft“ hinaus.
- **Zertifizierungen** (C5, VS-NfD-Zulassung) bleiben außerhalb.

**Offen:**

- Ist die Bagatell-Liste der [Stufenregeln](#die-stufenregeln) vollständig und eng genug? Sie wird mit
  #2554 und #2566 endgültig festgelegt und danach nur noch mit Begründung geändert.
- Gehört ein geänderter System-Prompt grundsätzlich in die Stufe Anzeige, oder kann er eine neue
  Auswertung bewirken (etwa ein Prompt, der Personen zusammenfasst) und damit Z3 erfüllen? Die
  Zuordnung je Prompt trifft das Manifest; die Regel braucht Erfahrung aus den ersten Deltas.
- Wie oft muss ein PR an der Pfadliste aus Schicht 2 „Keine Wirkung“ angeben, obwohl er nichts
  Datenrelevantes ändert? Häufen sich solche Fälle, ist die Liste enger zu fassen.
- Wie werden landesrechtliche Unterschiede (Personalvertretung, Barrierefreiheit, Archivrecht)
  dargestellt — als Übersicht je Land mit Verweis, nicht als Volltexte?

---

## Erfolgs-Metriken

- Zu jedem Release hängt ein attestiertes Update-Paket mit generierten Fakten und dem Delta zum
  Vorgänger am GitHub-Release.
- Für jedes Versionspaar lässt sich ein kumuliertes Delta erzeugen.
- Eine neue Tabelle, ein neues Protokollereignis, ein neuer Konfigurationsschlüssel oder ein neuer
  ausgehender Datenfluss ohne Manifest-Eintrag lässt die CI scheitern.
- Kein PR mit Datenwirkung bleibt länger als eine Woche unbemerkt: Entweder er trägt `data-impact`,
  oder die Drift-Routine meldet ihn.
- `docs/behoerden/` enthält für jede Phase der Behördenreise eine Vorlage mit Kopfvermerk und
  Haftungshinweis.
- Eine Behörde kann ein Update gegenüber Personalrat und Datenschutzbeauftragten begründen, ohne einen
  Diff zu lesen — gemessen an den Rückfragen, die das Update-Paket nicht schon beantwortet.
