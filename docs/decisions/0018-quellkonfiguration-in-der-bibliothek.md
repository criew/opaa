# ADR-0018: Quellkonfiguration in der Bibliothek

## Status

Akzeptiert · zuletzt geändert 2026-10-12 · ergänzt durch ADR-0023, ADR-0027, ADR-0036 (Entscheidung 6), ADR-0038, ADR-0039, ADR-0041

## Kontext

Nach ADR-0017 waren Adresse, Zugangsdaten und Zeitplan eines Laufs Teil des einzelnen Anstoß-Requests
und danach weg; der Dateisystempfad war eine globale Betriebseinstellung. Die Wissensbibliothek war
ein reiner Container ohne Quellenbezug, und die Oberfläche verteilte zusammengehörige Handgriffe auf
Bibliotheksverwaltung, Dokumentenseite und Admin-Drawer. Die Spezifikation sah ein Konnektormodell
(Konnektor → Quellen → Bibliotheken) mit gemischt gespeisten Bibliotheken vor, also mehrere Quellen
und Uploads in einem Topf; genau diese Mischung hatte ADR-0017 als wahrscheinlichsten
Umsetzungsfehler der Abwesenheitsprüfung benannt. Zu entscheiden war, wo die dauerhafte
Quellkonfiguration lebt.

## Entscheidung

### 1. Die Bibliothek ist die Quelle

Es gibt keine Quellen-Tabelle. Jede Bibliothek trägt genau einen Quellentyp (`source_type`, offener
Schlüssel seit ADR-0038, `UPLOAD` für hochgeladene Bestände), gewählt bei der Anlage und danach
unveränderlich; wer den Typ ändern will, legt eine neue Bibliothek an. Was typübergreifend Regeln hat,
steht als Spalte an `knowledge_libraries` (Adresse, Zugangsdaten, Zeitplan); was dem Konnektor
gehört, steht als JSON-Objekt in `source_settings`, das der Konnektor selbst validiert (ADR-0038
Entscheidung 2). Proxy und TLS-Schalter je Bibliothek sind entfallen: Jede Verbindung nimmt den
hausweiten Ausgangsproxy (ADR-0041, Nachtrag #2496), und das Zertifikat einer Quelle wird immer
geprüft (ADR-0041, Nachtrag #2537). Gemischt gespeiste Bibliotheken gibt es nicht; Mehrfachverwendung eines Bestands ist ein
Rechteproblem, gelöst über Freigabe derselben Bibliothek. Grund: Jede reale Quelle hätte genau eine
Bibliothek, und ohne Mischung ist die Abwesenheitsprüfung je Bibliothek adressierbar.

### 2. Der Anstoß verweist auf die Bibliothek

Ein Lauf wird als „indiziere diese Bibliothek“ angestoßen; Typ und Konfiguration kommen aus der
Bibliothek, den Executor liefert die Registry (ADR-0017 Entscheidung 3). Auslösen darf, wer an der
Bibliothek mindestens `EDITOR` ist (`requireEditableLibrary`), ohne zusätzliche
`SYSTEM_ADMIN`-Schranke; ein Anstoß für eine `UPLOAD`-Bibliothek wird abgelehnt. Das löst ADR-0017
Entscheidung 4 samt `url`-Fallback ab.

### 3. Die Oberfläche bündelt sich an der Bibliothek

Typauswahl als erster Schritt der Anlage (Kachelauswahl im generischen „Neu“ des Katalogs, ADR-0039),
dann die typspezifischen Felder; die Detailansicht zeigt je Typ Uploadfeld und Dokumentverwaltung
oder Konfiguration, Anstoß-Knopf und Stand des letzten Laufs. Eine eigene Bibliotheksliste gibt es
nicht mehr, Bibliotheken erscheinen als Kachel mit Umfang und Stand im Katalog neben den übrigen
Asset-Typen.

### 4. Zugangsdaten sind Daten

Zugangsdaten erscheinen in keiner API-Antwort und keinem Log; die API behandelt sie als
Nur-Schreiben-Feld, die Oberfläche zeigt höchstens, dass welche hinterlegt sind. Sie liegen
verschlüsselt in `source_credentials` (`CredentialsEncryptor`, Schlüssel aus der Umgebung) und nie in
`source_settings` (ADR-0038 Entscheidung 3).

### 5. Löschregel je Typ

Eine `UPLOAD`-Bibliothek lässt sich nicht löschen, solange sie Dokumente enthält; die Sperre schützt
einen kuratierten Bestand. Eine lauf-basierte Bibliothek nimmt beim Löschen ihren Bestand mit
(Dokumentzeilen, Chunks, Ordner), nach ausdrücklicher Bestätigung und mit Protokolleintrag, weil eine
Einzellöschung dort wirkungslos ist, solange der Ausschluss-Mechanismus aus
[knowledge-sources.md](../features/knowledge-sources.md#lebenszyklus-der-dokumente) nicht gebaut
ist: Der nächste Lauf nähme das Dokument wieder auf.

### 6. Anlage über Fähigkeiten; Allowlist und Zieladressprüfung sichern unabhängig davon

Wer eine Bibliothek anlegen darf, bestimmt seit #1810 eine vergebbare Fähigkeit (`CREATE_LIBRARY`,
`CREATE_CONNECTOR_LIBRARY`; ADR-0036 Entscheidung 5, umgesetzt in `CapabilityService`), ausgeliefert
an „Alle Konten“, sodass sich für Bestandsinstallationen nichts ändert. Die Obergrenze der Freigabe
konnektorgespeister Bibliotheken regelt ADR-0037. Unabhängig davon, wer anlegt, sichern zwei
Mechanismen jeden Serverpfad und jede Zieladresse: die Pfad-Allowlist
(`opaa.indexing.filesystem.allowlist`; ohne Eintrag ist der Typ Dateisystem abgeschaltet, die
Prüfung läuft bei Anlage und erneut bei jedem Lauf) und die Zieladressprüfung
(`TargetAddressValidator`, `opaa.indexing.target-validation`, lehnt aufgelöste Adressen im Loopback-,
Link-Local-, privaten oder nicht routbaren Bereich ab, siehe
[betrieb.md](../handbuch/betrieb.md#zielprüfung-abrufe-des-servers)). Die Fähigkeit ersetzt keine
der beiden Sicherungen und macht keine entbehrlich.

### 7. Zeitplan je Bibliothek

Konnektorbibliotheken haben einen an- und abschaltbaren Zeitplan in festen Stufen (stündlich,
täglich um HH:MM, wöchentlich am Wochentag um HH:MM), intern als Cron-Ausdruck in
`knowledge_libraries.schedule_cron`. `LibraryIndexingScheduler` prüft minütlich die fälligen
Bibliotheken und löst den gewöhnlichen Anstoß aus; einen Doppelstart verhindert die Datenbanksperre
`uk_indexing_jobs_library_running`, ein Tick auf eine laufende Bibliothek wird als
`SCHEDULE_SKIPPED` im Laufprotokoll vermerkt. Das Fälligkeitsfenster reicht vom Ende des vorigen Ticks
bis jetzt (`lastTickAt`); nach einem Prozessneustart blickt der erste Tick nur ein Intervall zurück,
verpasste Termine werden ausgelassen, nicht gebündelt nachgeholt. Fehlgeschlagene geplante Läufe
deaktivieren den Zeitplan nicht; zwei aufeinanderfolgende Fehlschläge zeigt die Detailansicht
(`lastScheduledRunsFailed`). Vorrangregeln und Leader-Election gibt es nicht (ADR-0021).

## Konsequenzen

**Einfacher:** Ein Ort statt drei; die Abwesenheitsprüfung läuft je Bibliothek; Konfiguration
überlebt den Lauf; ein neuer Quellentyp erscheint als Template, sobald sein Konnektor registriert ist.

**Schwieriger:** Zugangsdaten brauchen Verschlüsselung, Maskierung und einen Wechselweg. Die
Ein-Typ-Regel ist eine Produktfestlegung: Ein Bestand mit anderem Typ wird in eine neue Bibliothek
neu indiziert. Ein Lauf je Bibliothek statt global einer.

## Verworfene Alternativen

- Eigene Quellen-Tabelle mit Zuordnung zur Bibliothek — zwei Verwaltungsobjekte, wo eines genügt;
  lässt sich bei echtem Bedarf später herausziehen.
- Gemischt gespeiste Bibliotheken mit persistierter Konfiguration — erzeugt die Sonderregeln, vor
  denen ADR-0017 warnt, und nimmt der Oberfläche die Antwort „woher kommt dieser Bestand?“.
- Konfiguration weiterhin im Anstoß-Request — kein Ort für Zeitpläne, keine Wiederholbarkeit.

## Offen

- Mehrere Quellen desselben Typs je Bibliothek; der Preis wäre eine Abwesenheitsprüfung je
  Quelladresse statt je Bibliothek.
- Duplikaterkennung über Bibliotheksgrenzen, wenn derselbe Bestand als Upload und als Konnektor
  existiert.
