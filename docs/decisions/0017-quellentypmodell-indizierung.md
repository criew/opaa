# ADR-0017: Quellentypmodell der Indizierung

## Status

Akzeptiert · zuletzt geändert 2026-09-26 · Entscheidung 1 ersetzt durch ADR-0038 Entscheidung 1, Entscheidung 4 ersetzt durch ADR-0018 Entscheidung 2 · ergänzt durch ADR-0023 (Entscheidung 4) und ADR-0027 (Entscheidung 3)

## Kontext

Mit zwei lauf-basierten Quellentypen (Dateisystem, HTTP-Verzeichnisliste) war der Weg eines
Indizierungslaufs eine einzige Fallunterscheidung: War eine URL gesetzt, lief der eine Executor, sonst
der andere; beide teilten weder Interface noch Oberklasse und duplizierten Zähl- und Meldelogik. Mit
RSS-Feeds kam ein dritter Typ, für den „URL gesetzt“ nichts mehr unterschied und der sich
grundlegender von einer Dateiliste unterschied: Sein `<link>` zeigt auf eine HTML-Detailseite, aus
der Artikeltext und Anlagen erst gewonnen werden müssen. Ohne Entscheidung hätte jeder weitere Typ
eine weitere verschachtelte Bedingung und eine weitere Kopie der Kette bekommen, und die Frage, ob
ein aus der Quelle verschwundenes Dokument aus dem Index zu nehmen ist, wäre je Typ implizit aus dem
Verzeichnisverhalten übernommen worden.

## Entscheidung

### 1. Der Quellentyp wird ausdrücklich übergeben

Entfallen mit #1977, siehe ADR-0038 Entscheidung 1: Der Typ ist ein offener Schlüssel (`SourceType`),
den nur die Konnektor-Registry kennt; der Anstoß nennt die Bibliothek (ADR-0018 Entscheidung 2).

### 2. Grenze zwischen Quellentyp und gemeinsamer Kette

Ein Konnektor ist dafür zuständig, aus seinem Ursprung eine Liste von Inhaltselementen zu erzeugen,
jedes mit Name, Bezug zum Ursprung (Pfad, URL, Schlüssel) und Änderungsmerkmal (Zeitstempel, Version,
Prüfsumme), und am Ende die Bytes einer Datei zu liefern. Beim Dateisystem liegt die Datei vor, bei
der Verzeichnisliste ist es ein Download, beim Feed ein zweistufiger Weg über die Detailseite; was
ursprungsspezifisch ist, gehört in den Konnektor. Alles danach (Formatprüfung, Extraktion, Zerlegung,
Einbettung, Prüfsummenvergleich, Ablage; `DocumentIngestService`) ist typunabhängig, und kein
Konnektor bekommt davon eine Kopie. Die Schnittstelle trägt das Zweistufenmuster und den gemeinsamen
Anhangsweg (ADR-0022), nicht nur „Liste von Datei-URLs“.

### 3. Registrierung statt Fallunterscheidung

Jeder Lauftyp implementiert `SourceIndexingExecutor`; `IndexingSourceExecutorRegistry` löst den
Executor zum Typ der Bibliothek auf, gespeist aus den Spring-Beans der Konnektorpakete. Kern,
Verwaltung und API erreichen einen Konnektor nur über `SourceConnectorRegistry` (ADR-0038); die
Modulgrenze erzwingt `ModularArchitectureTest`. Ein neuer Typ bringt Konnektor, Executor und
Beschreibung mit und ändert keinen Aufrufer.

### 4. Typspezifische Konfiguration im Anstoß-Request

Entfallen mit #487, siehe ADR-0018 Entscheidung 2: Die Konfiguration lebt an der Bibliothek.

### 5. Löschung durch Abwesenheit je Betriebsart

Jeder Executor erklärt je unterstützter Betriebsart (`IndexingRunMode`) eine `VanishedDocumentPolicy`,
ohne impliziten Standardwert: `REMOVE_ON_ABSENCE` für einen Lauf, der den vollständigen Bestand
auflistet (Dateisystem, Verzeichnisliste, Vollabgleiche), `KEEP_ON_ABSENCE` für einen Lauf, der nur
einen Ausschnitt sieht (RSS-Feed, inkrementelle Abgleiche, Ereignisläufe). Fehlt ein zuvor indiziertes
Dokument in einem vollständig auflistenden Lauf, wird es aus dem Index genommen; in einem ergänzenden
Lauf ist Abwesenheit keine Aussage, Löschung braucht dort einen positiven Befund der Quelle, den der
jeweilige Konnektor-ADR benennt (ADR-0023 Entscheidung 4, ADR-0027 Entscheidung 3). Hochgeladene
Dokumente entstehen außerhalb jedes Laufs und werden nie durch Abwesenheit gelöscht. Der Vergleich
läuft je Quelle, nie bibliotheksweit; da eine Bibliothek genau eine Quelle hat (ADR-0018), fällt
beides zusammen. Die Zielsemantik steht in
[knowledge-sources.md](../features/knowledge-sources.md#selbst-aktualisierende-wissensblöcke).

## Konsequenzen

**Einfacher:** Ein neuer Lauftyp ergänzt sich ohne Anfassen einer Fallunterscheidung; die
Löschsemantik ist einmal entschieden statt je Typ implizit übernommen; Feed-Parsing ist ohne Netz und
Datenbank prüfbar.

**Schwieriger:** Die Schnittstelle muss das Zweistufenmuster tragen; ein Konnektor mit mehreren
Betriebsarten erklärt für jede eine Policy.

## Verworfene Alternativen

- Ein drittes `if` im Controller — schreibt das Problem fort, der vierte Typ träfe auf drei Bedingungen.
- Universelle Löschung durch Abwesenheit, bibliotheksweit — für Feeds sachlich falsch, bei gemischten
  Beständen für jeden Typ falsch.

## Offen

Nichts.

## Änderungsverlauf

| Datum | Issue/PR | Änderung (eine Zeile) | Entscheidungen |
|---|---|---|---|
| 2026-08-18 | #472 | Erstfassung mit fünf Entscheidungen | 1–5 |
| 2026-08-18 | #487 | Konfiguration an der Bibliothek, Entscheidung 4 abgelöst (ADR-0018) | 4 |
| 2026-08-25 | #900 | Löschung durch Abwesenheit ist Produktivverhalten | 5 |
| 2026-09-03 | #1131 | Kategorie je Betriebsart statt je Quellentyp (ADR-0023) | 5 |
| 2026-09-06 | #1373 | Anwendung auf S3: Vollabgleich und Ereignislauf (ADR-0027) | 5 |
| 2026-09-26 | #1976, #1977 | Konnektor-Schnittstelle und offener Typschlüssel (ADR-0038) | 1, 3 |
