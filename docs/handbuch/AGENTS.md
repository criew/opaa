# Handbuch: Anweisungen für Schreibende

Gilt für jede Änderung unter `docs/handbuch/`. Leserinnen und Leser sind Betrieb und Verwaltung,
nicht Entwickler; das Handbuch beschreibt den gebauten Ist-Stand.

## Aufbau

- **Ein Baum.** Die Gliederung steht in `README.md`, Abschnitt „Aufbau des Handbuchs". Ein neues
  Kapitel bekommt dort seinen Platz, bevor es geschrieben wird.
- **Allgemeines oben, Besonderes unten.** Was für alle Arbeitsmittel gilt, steht in `katalog.md`
  und `berechtigungen.md`; was nur eine Art betrifft, in deren Kapitel. Was einen Konnektor oder ein
  Format betrifft, gehört in `konnektoren/<typ>.md` bzw. `formate/<format>.md`, nie in das
  Elternkapitel.
- **Übersichten statt Vollaufzählungen.** Alle Konnektoren bzw. Formate listen nur
  `konnektoren/README.md` und `formate/README.md`. Andere Kapitel nennen wenige Beispiele und
  verweisen dorthin, damit ein neuer Typ nur an einer Stelle nachzutragen ist.

## Verweise

1. Nach unten frei: ein Kapitel auf seine Kinder.
2. Nach oben einmal in der Einleitung, sonst nur, wo ein Begriff von dort gebraucht wird.
3. `berechtigungen.md` und die Variablenliste in `deployment.md` sind Nachschlagekapitel: Alle
   dürfen auf sie verweisen; sie selbst verweisen zurück nur in „Weiterführende Kapitel" und dort,
   wo eine Zeile ihre Bedeutung erklärt.
4. Geschwister nur bei echtem Bedarf.
5. „Weiterführende Kapitel" nennt nur Kinder und nächste Geschwister.
6. Lieber Kapitel und benannte Abschnitte als Abschnittsnummern; Nummern veralten bei jeder
   Umgliederung.

## Stil

- **Vom Zweck her erklären**, am besten mit einem Beispiel, bevor Einzelheiten kommen.
- **Konzept statt Umsetzung**: kein „eine Zeile in der Dokumenttabelle", keine Klassen- oder
  Tabellennamen im Fließtext.
- **Knapp.** Feld-für-Feld-Listen und Regeln für jeden Randfall gehören in Spezifikation und ADR.
  Statt Felder aufzuzählen, die typischen Anlässe beschreiben.
- **Sonderfälle nicht wiederholen**, etwa den Upload: einmal einführen, danach nur nennen, wo er
  sich unterscheidet.
- **Begriffe:** „Space", nie „Raum"; „Arbeitsmittel" für alles, was angelegt, geteilt und einem
  Space zugeordnet wird; Oberflächentexte wörtlich in „…".
- Tabellen und Abbildungen tragen weder Nummer noch Titel; Kapitel haben kein Verzeichnis davon.

## Weitere Regeln

- **Deutsch, Ist-Stand, keine Entstehungsgeschichte.** Warum etwas so gebaut ist, steht in
  `docs/features/` und `docs/decisions/`.
- **Verweise nur innerhalb des Handbuchs.** Ausnahme ist der Hinweis auf das Repository im README.
- **Ticketverweise nur für ausdrücklich noch nicht Gebautes**, etwa in „Was nicht gebaut ist".
- **Konfigurierbare Werte stehen nur in Konfigurationstabellen** der Kapitel und in der
  Variablenliste des Deployment-Kapitels; Fließtext nennt Parameternamen.
- **Grafiken sind Mermaid**, mit kurzen Knoten: höchstens etwa 15 Zeichen je Zeile, mit `<br/>`
  umbrechen. Sonst schneidet GitHub die Beschriftung ab. In den Knoten steht das Stichwort, die
  Erläuterung im Text darunter.
- **„Entwurf"** am Kapitelanfang: gegen den Code geprüft, vom Maintainer noch nicht abgenommen.
- **Kapitel werden mit dem Code gepflegt**, im selben Pull Request wie die Verhaltensänderung.
- **Verschieben oder Umbenennen** zieht alle Verweise im Repo nach, auch Code-Kommentare,
  `AGENTS.md`-Dateien und Leser wie `demo/generator/generate_corpus.py` (liest
  `formate/README.md`, Abschnitt „Zugelassene Formate").
