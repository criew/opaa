# Handbuch: Anweisungen für Schreibende

Gilt für jede Änderung unter `docs/handbuch/`. Das Handbuch beschreibt den gebauten Ist-Stand für
alle, die OPAA benutzen, administrieren oder betreiben. Es ist kurz: Wer eine Aufgabe hat, findet
sie auf einer Seite und liest sie in einem Bildschirm.

## Aufbau

Vier Kapitel nach Zielgruppe. Wer in OPAA Spaces und Bibliotheken für andere einrichtet, ist keine
eigene Zielgruppe: Ab Werk darf das jedes Konto, deshalb steht es unter Benutzer. Administration
meint die Systemverwaltung der ganzen Installation und die Revision.

| Ordner | Für wen | Inhalt |
|---|---|---|
| `benutzer/` | jedes Konto | fragen, Belege, Chats, Spaces, Bibliotheken, Konnektoren (`benutzer/konnektoren/`), Gruppen, Zugriff |
| `administration/` | Systemverwaltung und Revision | Konten, Gruppen und Fähigkeiten, Modelle, Einstellungen, Protokolle |
| `betrieb/` | IT-Betrieb | Schnellstart, Installation, Compose, Kubernetes, Sicherung, Update, Überwachung |
| `referenz/` | alle | Rechte-Matrix, Konfiguration, Formate (`referenz/formate/`), Suche im Überblick, Glossar |

Jeder Ordner hat eine `README.md` mit der Liste seiner Seiten, `benutzer/` und `administration/`
gegliedert wie die Navigation. Die Reihenfolge der Website legt die Navigationsliste `nav` in
`mkdocs.yml` fest: Seiten in der Folge, in der man sie braucht, nicht alphabetisch. Eine neue Seite
bekommt in beiden ihren Platz; fehlt sie in `nav`, bricht der strikte Build ab. Die Startseite
`README.md` bleibt kurz: Zielgruppen, was OPAA ist, Aufbau, wenige Lesewege.

## Seiten in `benutzer/` und `administration/`

Eine Seite ist eine Aufgabe. Der Titel ist die Aufgabe als Verb ohne Artikel („Bibliothek teilen“,
nicht „Eine Bibliothek teilen“), nicht das Thema. Dasselbe gilt für Zwischenüberschriften und
Linktexte. Jede Seite hat diese Form:

```markdown
# Bibliothek teilen

**Ziel:** Andere Personen oder Gruppen lesen oder pflegen Ihre Bibliothek.
{: .kopf }

**Wer:** *Verwalter* oder *Eigentümer* der Bibliothek
{: .kopf }

1. Schritt mit den Beschriftungen der Oberfläche in „…“
2. Schritt
3. Schritt

**Gut zu wissen:** höchstens drei Punkte.
```

- **Seitenkopf:** „Ziel“ und „Wer“ stehen direkt unter dem Titel, je eine Zeile mit `{: .kopf }`
  darunter; die Website setzt sie klein und grau mit Linie ab. Danach folgt gleich, was man tut.
- **Ziel-Zeile:** das Ergebnis der Aufgabe, was man am Ende hat oder kann, kein Satz über die Seite.
  Pflicht auf jeder Seite in `benutzer/` und `administration/` außer den Konnektor-Seiten. Kein
  zweiter Einleitungssatz, der dasselbe sagt.
- **Wer-Zeile:** genau eine Zeile mit Rolle am Objekt, Systemrolle oder Fähigkeit, in der
  Administration mit Menüpfad. Pflicht in `administration/` und auf den Konnektor-Seiten; in
  `benutzer/` nur, wo die Aufgabe eine Rolle an Space, Bibliothek oder Gruppe braucht. Was jedes
  Konto kann, trägt keine; eine echte Voraussetzung steht als ein Satz im Text. Einzelheiten und
  die Auslieferung ab Werk stehen in der Rechte-Matrix (`referenz/rechte-matrix.md`).
- **Höchstens 80 Zeilen** in `benutzer/`, besser deutlich weniger. Was nicht passt, ist eine
  zweite Aufgabe und bekommt eine eigene Seite.
- `.github/scripts/check_handbuch.sh` prüft Seitenkopf und Länge in der CI. Ausgenommen sind die
  `README.md` der Ordner; die Seiten unter `benutzer/konnektoren/` und `administration/` haben
  keine feste Länge.

`administration/` folgt derselben Form, knapp gehalten. `betrieb/` und `referenz/` dürfen lang
sein, ohne Inhalte doppelt zu führen: Was Compose und
Kubernetes gemeinsam haben, steht einmal.

## Aufbau der Konnektor-Seiten

Jede Seite unter `benutzer/konnektoren/`
hat oben die Wer-Zeile (ohne Ziel-Zeile) und dann dieselben Abschnitte in dieser Reihenfolge, nummeriert
von 1 an. Ein Abschnitt, der nicht zutrifft, entfällt; die Nummern folgen lückenlos.

1. **Was der Konnektor ist** — welches System, über welche Schnittstelle (mit Version, wo bekannt),
   wie er sich anmeldet und ob ein Zugang nicht möglich, möglich oder Pflicht ist, was er liest und
   was nicht, woran ein Dokument hängt (Identität, Beleg-Link, „Original öffnen“) und dass Rechte
   nicht übernommen werden. **Pflicht ist ein Verbindungsbild** als Mermaid mit höchstens sechs
   Knoten (Wissensbibliothek → Zugang → Schnittstelle → System) und ein Satz, welchem Anmeldemuster
   der Seite über Zugänge er folgt. Das Muster wird nur verwiesen; ausgeführt wird nur das
   Konnektor-Eigene.
2. **Einrichtung** — was beim Anbieter und im Haus vorzubereiten ist, als kurze Schrittfolge.
3. **Quellkonfiguration** — die Angaben im Formular, mit den Beschriftungen der Oberfläche,
   Verbindungstest, Besonderheiten über einen Zugang.
4. **Betriebsarten und Änderungserkennung** — Laufarten, woran Unverändertes erkannt wird,
   Fortsetzung über mehrere Läufe, Hinweis zum Zeitplan.
5. **Benachrichtigungen** — nur, wo die Quelle Änderungen an OPAA melden kann.
6. **Löscherkennung**
7. **Ordner**
8. **Anhänge**
9. **Grenzen und Schutzmechanismen** — Zieladressprüfung, Größen- und Mengengrenzen, Drosselung,
   besondere Dateien, bekannte Einschränkungen. Konfigurierbare Grenzen nur mit Parameternamen.
10. **Protokoll und Fehlerbilder** — eine Tabelle Meldung, Bedeutung, Abhilfe.

Was mehrere Konnektoren teilen — Abgleichstand, Zieladressprüfung, der Abgleich der Dateiablagen —
steht nur in der Übersicht der Konnektoren; die Seite verweist und nennt nur ihre Abweichung.
**Keine API-Feldnamen** wie `sourceCredentials`: Gezeigt wird die Oberfläche. Parameternamen der
Konfiguration bleiben erlaubt, ihre Standardwerte stehen nur in der Konfigurationsreferenz.
Format-Seiten unter `referenz/formate/` folgen ihrem bisherigen gemeinsamen Aufbau.

## Verweise

- **Nur innerhalb des Handbuchs.** Kein Verweis auf ADRs, Spezifikationen, Issues oder Dateien
  im Repository: Das Handbuch erklärt das Verhalten selbst. Warum etwas so gebaut ist, gehört nicht
  hinein. `check_handbuch.sh` weist ADR-Nummern und Repository-Links ab.
- Lieber Seiten und benannte Abschnitte als Abschnittsnummern; Nummern veralten bei jeder
  Umgliederung.
- Jeder relative Link und jeder Anker muss treffen. Die Website baut mit MkDocs (`mkdocs.yml` im
  Repository-Wurzelverzeichnis) im strikten Modus; ein toter Link oder Anker lässt die CI rot
  werden. Dasselbe prüft `update_package.py links` für das Update-Paket, auch die Verweise aus
  `docs/behoerden/`. Eine umbenannte Überschrift zieht deshalb alle Verweise auf sie nach.
- Spitze Klammern im Fließtext (`<Titel>`) stehen in Backticks, sonst verschluckt sie die
  HTML-Fassung.

## Stil

- **Vom Zweck her erklären**, am besten mit einem Beispiel, bevor Einzelheiten kommen.
- **Bedienung statt Umsetzung**: keine Klassen-, Tabellen- oder API-Namen im Fließtext.
- **Knapp.** Keine Feld-für-Feld-Listen, keine Regeln für jeden Randfall, keine Zeitwerte und
  Grenzen, die nur die Konfiguration betreffen; die typischen Anlässe beschreiben. Lieber einen
  Satz weniger.
- **Keine Abschnitte** „Nicht gebaut“, „Was es hier nicht gibt“, „Weiterführende Kapitel“, „API“
  und keine eigenen „Konfiguration“-Abschnitte: Ziele stehen in der Roadmap, Variablen in der
  Konfigurationsreferenz, eine Seite nennt nur den Parameternamen.
- **Begriffe:** „Space“, nie „Raum“; „Arbeitsmittel“ für alles, was angelegt, geteilt und einem
  Space zugeordnet wird; Oberflächentexte wörtlich in „…“.
- **Etablierte englische Fachbegriffe** aus Betrieb und Technik werden nicht eingedeutscht:
  Readiness und Liveness, Graceful Shutdown, Read-only Filesystem, Health Check, Load Balancer,
  Rolling Update, Connection Pool, Timeout, Redirect, OOM-Kill, Provenance, Log, Egress. Deutsch
  bleibt, was die Oberfläche deutsch nennt („Ausgangsproxy“, „Zielprüfung“, „Netzbereiche“), und
  eingeführte Begriffe der Verwaltung wie Datenschutz oder Mitbestimmung.
- Tabellen und Abbildungen tragen weder Nummer noch Titel.
- **Grafiken sind Mermaid**, mit kurzen Knoten: höchstens etwa 15 Zeichen je Zeile, mit `<br/>`
  umbrechen. In den Knoten steht das Stichwort, die Erläuterung im Text darunter.

## Weitere Regeln

- **Deutsch, Ist-Stand, keine Entstehungsgeschichte.**
- **Konfigurierbare Werte stehen nur in der Konfigurationsreferenz**; Fließtext nennt
  Parameternamen.
- **Seiten werden mit dem Code gepflegt**, im selben Pull Request wie die Verhaltensänderung.
- **Verschieben oder Umbenennen** zieht alle Verweise im Repo nach, auch Code-Kommentare,
  `AGENTS.md`-Dateien und Leser wie `demo/generator/generate_corpus.py` (liest die Übersicht der
  Formate, Abschnitt „Zugelassene Formate“) und die Zuordnung Ansicht → Seite hinter dem „?“ der
  App (`frontend/src/app/shell/handbookLink.ts`, ihr Test prüft jede genannte Seite).
- **Website:** `mkdocs.yml` legt Theme und Erweiterungen fest; ohne Navigationsliste folgt die
  Navigation den Ordnern. Lokal bauen: `pip install --require-hashes -r .github/requirements-docs.txt`,
  dann `mkdocs serve` im Repository-Wurzelverzeichnis. Veröffentlicht wird über
  `.github/workflows/landing-page.yml` unter `https://opaa.io/handbuch/` (Stand von `main`) und
  `https://opaa.io/handbuch/v<Version>/` (je Release-Tag); das „?“ der App verlinkt die Fassung
  ihrer Version.
