# ADR-0052: Compliance-Manifest

## Status

Akzeptiert · zuletzt geändert 2026-10-24

## Kontext

Die Spezifikation [Behördenunterlagen](../features/behoerdenunterlagen.md#das-faktenmodell) verlangt
ein Fakten-Manifest als einzige Quelle für Faktenblätter, Update-Delta und Instanzexport: welche Daten
OPAA speichert, welche Protokollereignisse entstehen, was Rollen über andere sehen, welche Fristen,
Voreinstellungen, System-Prompts, KI-Funktionen und ausgehenden Datenflüsse es gibt. Ein Teil dieser
Fakten steht bereits im Code (Enums, Liquibase, Konfiguration), ein Teil nur in Spezifikationen
(Zweck, Sichtbarkeit, Löschweg, mögliche Auswertungen). Das Manifest muss zur Laufzeit im Klassenpfad
liegen, weil der Instanzexport es liest, und es darf bei parallelen PRs nicht in einer Datei
kollidieren. Jede zusätzliche Pflegestelle ist Folgeaufwand, und eine relevante Änderung darf nicht
unbemerkt bleiben.

## Entscheidung

### 1. Ablage im Klassenpfad des Backends

Das Manifest liegt als YAML-Datei je Bereich unter `backend/src/main/resources/compliance/manifest/`;
der Dateiname ist der Bereich. Lader und Schema liegen im Paket `io.opaa.compliance` des Moduls
foundation, das kein höheres Paket importiert; Fundstellen im Code nennt es nur als Namen. Ein eigenes
Gradle-Modul nach dem Muster von `opaa-api` gibt es nicht: Alles, wogegen das Manifest geprüft wird
(Liquibase, Prompt-Klassen, Konfiguration, ausgehende Clients), liegt im Backend, und eine geänderte
Ressource übersetzt keinen Code neu.

### 2. Die Records sind das Schema

`ComplianceManifest` und seine verschachtelten Records beschreiben jede Eintragsart; Bean-Validation-
Annotationen markieren die Pflichtfelder. `ComplianceManifestLoader` weist das Manifest als Ganzes ab,
mit allen Verstößen: fehlendes oder leeres Pflichtfeld, unbekannter Schlüssel, doppelte Kennung,
Fristverweis ohne Einstellungseintrag, KI-Funktion mit unbeschriebenem Prompt, doppelter Schlüssel in
einer Zuordnung, Prompt ohne oder mit zwei Fundstellen. Schlüssel sind
englisch und Code-Bezeichner, Werte deutsch für die Behörde.

### 3. Nur, was nicht aus dem Code folgt

Ein Eintrag wird über einen Code-Bezeichner gefunden (Enum-Konstante, Tabelle und Spalte,
Konfigurationsschlüssel, Klassenname) und trägt nur, was der Code nicht sagt: Zweck, Personenbezug,
Sichtbarkeit, Zugriffsweg, Löschweg, mögliche Auswertungen, Auslöser, Hinweise. Bewusst doppelt
stehen nur Stolperdrähte: Standardwerte von Einstellungen und Prüfsummen von Prompts, damit eine
Änderung am Code ohne Nachpflege auffällt. Rechtliche Einstufungen stehen nie im Manifest, Hinweise
darauf nur in `notes`.

Der Zugriffsweg nennt neben `UI`, `API` und `EXPORT` den direkten Zugriff auf den Speicher: `DATABASE`
für die Anwendungsdatenbank, `STORAGE` für eine Ablage des Betriebs außerhalb davon (Dateisystem,
Objektspeicher, Log). Ein Datum ohne Tabelle trägt nie `DATABASE`; das prüft `ComplianceManifestTest`.
TOM und Sicherheitskonzept schützen den Speicher, in dem das Datum tatsächlich liegt.

### 4. Voreinstellungen je Datei

Ein Bereich kann unter `defaults` je Abschnitt gemeinsame Felder setzen; ein Eintrag überschreibt sie
Schlüssel für Schlüssel, die Pflichtfeldprüfung läuft auf dem Ergebnis. Ein neues Protokollereignis
braucht damit nur Kennung und Auslöser.

### 5. Fristen sind Einstellungen

Eine Frist ist ein Eintrag unter `settings` mit Quelle `PROPERTY`, `DATABASE` (`tabelle.spalte`) oder
`CONSTANT` (`Klasse#FELD`). Daten und Protokollereignisse verweisen in `retention.settings` darauf; ein
Verweis ohne Eintrag scheitert. Ein Datum mit festem Ende verweist auf nichts und sagt das in
`retention.rule`.

### 6. Prüfsumme eines Prompts

Ein Prompt liegt entweder in Klassen (`classes`) oder als Default einer Spalte (`databaseDefault`,
`tabelle.spalte`), nie beides. Im Code ist die Prüfsumme SHA-256 über alle Zeichenkettenkonstanten
und alle Zeichenkettenwerte von Annotationen an Klasse, Feldern, Methoden und Parametern
(`@Tool(description = …)`) der genannten Klassen, gelesen aus ihren Klassendateien, sortiert
(`PromptChecksum`). Sie erfasst Textblöcke, zusammengesetzte Konstanten und Vorlagen der
Laufzeitverkettung, auch wenn ein Prompt im Code verteilt aufgebaut wird; eine geänderte Log- oder
Fehlermeldung derselben Klasse ändert sie ebenfalls. In der Datenbank ist sie SHA-256 über den letzten
`DEFAULT`, den die Migrationen der Spalte geben. Maßgeblich ist die mit javac übersetzte Klassendatei der
Gradle-Toolchain; ein anderer Compiler (ECJ in Eclipse oder VS Code) kann einen abweichenden
Konstantenbestand und damit eine andere Prüfsumme erzeugen.

### 7. Vollständigkeitswächter ohne Datenbank

`ComplianceManifestTest` gleicht das Manifest in beiden Richtungen ab: Werte von `AuditEventType`,
`SystemRole` und `Capability`, Prompt-Prüfsummen, den Weg zur Person je Tabelle (Entscheidung 12)
sowie jede Tabelle und jede Spalte des Schemas. Jede
Spalte steht genau einmal, als `personalColumns` oder `nonPersonalColumns` ihrer Tabelle oder in der
Spaltenliste unter `nonPersonalTables`; so fällt auch eine neue Spalte einer Tabelle ohne
Personenbezug auf. Das Schema liest `MigrationSchema` aus den SQL-Skripten der Migrationen
(ADR-0034, ADR-0056); jede Tabellen-DDL, die es nicht vollständig liest, auch in Prozedurrümpfen außer der
Partitionspflege, lässt es scheitern. Ein Test gegen eine Testcontainers-Datenbank würde ohne
Docker still übersprungen; der Abgleich des Lesers und der festen Spalten von `vector_store` mit dem
Katalog einer migrierten Datenbank (`MigrationSchemaParityTest`) läuft deshalb getrennt in der CI. Jede
Meldung nennt Datei und einzufügendes YAML; die Konvention steht in `backend/docs/compliance-manifest.md`.

### 8. Datenflüsse: Anker im Code

Jede Klasse, die Daten aus der Installation sendet, trägt `@DataFlow` mit den Kennungen ihrer
Datenflüsse; eine Hüllklasse, die einen Client nur für ihre Aufrufer baut oder kapselt, trägt
`@DataFlowCarrier` (beide in `io.opaa.compliance`). Ziel, Daten, Anlass, Abschaltbarkeit,
Einstellungen, Gruppe und Weg über den Ausgangsproxy stehen nur im Manifest unter `dataFlows` in
`outbound.yaml`, die Annotation trägt nur die Kennung. `ComplianceManifestTest` gleicht Kennungen
und Einträge in beiden Richtungen ab; `ModularArchitectureTest` lässt keinen Client aus
`OUTBOUND_CLIENTS` und keine Abhängigkeit von `OUTBOUND_BEANS` ohne Annotation zu. Ein neuer Client kostet eine Annotationszeile und einen
YAML-Eintrag; eine neue Kennung ist ein neuer externer Datenfluss. Die Registry beschreibt und sperrt
nichts: Weg und Zielprüfung bleiben beim Ausgangsproxy (ADR-0050) und bei `TargetAddressValidator`.

Eine Liste allein veraltet unbemerkt, ein Anker am Code fällt beim Bauen auf. Der Fachtext bleibt im
Manifest, damit Faktenblätter ihn lesen, ohne den Code zu kennen.

Ein Abruf, den eine eingebundene Bibliothek von sich aus aufbaut, wird abgeschaltet. Braucht ihn eine
Funktion, bekommt er einen Eintrag in `outbound.yaml` und eine Kennzeichnung über die Hüllklasse
(`@DataFlowCarrier`) und ihre Aufrufer (`@DataFlow`).

### 9. Konfigurationsschlüssel und Voreinstellungen

Der Build liefert `META-INF/spring-configuration-metadata.json`: `spring-boot-configuration-processor`
in einem eigenen Lauf über alle Quellen, ergänzt um `additional-spring-configuration-metadata.json`
für Schlüssel ohne `@ConfigurationProperties`-Typ und um den Standardwert aus dem Basisdokument von
`application.yml`. Jeder `opaa.*`-Schlüssel trägt `@DataImpact` oder `@NoDataImpact(Grund)` an seiner
Komponente oder pauschal an seinem Typ; Schalter, Modelle, Adressen, Fristen, Abstände und jede
Zeitspanne (`Duration`, `Period`) entscheidet die Komponente selbst. Datenwirksam ist ein
Standardwert, der bestimmt, was über Personen anfällt, wie lange es bleibt, wer es sieht, was sich auswerten lässt oder wohin Daten gehen; Schutzgrenzen,
Zeitlimits, Suchgüte und Geheimnisse sind es nicht. Ein datenwirksamer Schlüssel steht mit seinem
Standardwert unter `settings`, ebenso Modell und Adresse jeder Modellrolle (Standardmodelle). Setzt
das Helm-Chart den Schlüssel, nennt der Eintrag Wert und Voreinstellung aus `values.yaml` (`helm`).

`ConfigurationSettingsTest` prüft alles davon. Die Markierung steht dort, wo ein neuer Schlüssel
entsteht, damit sie niemand in einer zweiten Liste nachtragen muss. Der Prozessor läuft getrennt,
weil Gradle beim inkrementellen Übersetzen unveränderte Typen ohne Javadoc nachverarbeitet.

### 10. Delta zwischen zwei Versionen

`./gradlew complianceDelta` vergleicht die Manifeste zweier Versionen direkt (`ManifestDelta`),
nicht als Kette von Einzeldeltas. Eine Version kommt aus Git (Tag oder Commit), aus der JSON-Fassung
der Faktenblätter oder aus dem Arbeitsstand, ohne Netz. `ManifestSnapshot` liest das Manifest
schemafrei: Einträge je Abschnitt über ihre Kennung, Felder als Pfad und Wert, `defaults` gemergt,
nicht validiert; Listen gelten ohne Reihenfolge, ein Feld, das nur eine Version kennt, ist eine
Änderung, eine Version ohne Manifest ist leer. Git ruft nur `ManifestSnapshot` auf, jeder Wert von
außen nach `--end-of-options`; `ModularArchitectureTest` lässt sonst keinen Prozessstart zu. Den Stufenvorschlag je Änderung gibt `StageRules`, die einzige Stelle der Stufenregeln im
Code. Markdown für die Behörde und JSON für Werkzeuge tragen kein Erzeugungsdatum; gleiche Eingaben
ergeben gleiche Dateien.

Die Records ändern sich mit dem Schema; der aktuelle Lader wiese ein älteres Manifest ab, dem ein
inzwischen verlangtes Feld fehlt. Ein Release hält sein Manifest im Repository, nicht als Anhang;
Git-Stände gibt es für jedes Versionspaar.

### 11. Auskunft über die Datenerhebung einer Instanz

Eine laufende Instanz gibt das Manifest ihrer Version mit ihren wirksamen Werten heraus
(`InstanceDisclosureAssembler`, als JSON mit dem Manifest in der Form von `faktenblaetter.json` und
als Markdown). Werte gibt es nur für Schlüssel unter `settings` (Positivliste); jeder Wert läuft
durch `InstanceDisclosureAssembler#exportable`, Adressen verlieren Zugangsdaten, Abfrageteil und
Anker. Werte aus der Datenbank, Datenflüsse, Konnektornutzung und Modellanbieter meldet jedes Modul
über den Port `InstanceStateSource` in `io.opaa.compliance`; private Bibliotheken zählen nicht.
Abrufen darf nur `SYSTEM_ADMIN`. Jeder Abruf schreibt `DATA_COLLECTION_DISCLOSURE_EXPORTED`, auch
der abgewiesene (`DENIED`) und der gescheiterte (`FAILURE`); deshalb prüft der Service die Rolle.
Das Ereignis nennt Format und Berichtszeitraum. Der Berichtszeitraum besteht aus ganzen,
abgeschlossenen Kalendermonaten (ein Ende im laufenden Monat wird auf den Vormonat gekappt) und gilt für die Zahl der anlassbezogenen Klärungen je Anlass (beantragt,
freigegeben, abgelaufen), die das Modul des Protokolls aus einer Monatszählung ohne Personen liefert;
Zahlen unter 3 gehen nur als „weniger als 3“ hinaus.

Eine Negativliste geheimer Schlüssel veraltet unbemerkt, das Manifest führt ohnehin jeden
datenwirksamen Schlüssel. Der Port hält foundation frei von Abhängigkeiten nach oben und legt jede
Auskunft in das Modul, dem die Daten gehören.

### 12. Weg zur Person je Tabelle

Jede Tabelle unter `tables` eines Datums nennt unter `person`, wie ein Eintrag einer Person
zugeordnet wird: über eigene personenbezogene Spalten (`columns`), über eine Tabelle desselben oder
eines anderen Datums mit eigenem Weg (`via`) oder beides, oder sie ist mit Grund von der
Selbstauskunft ausgeschlossen (`excluded`). Bei einem Weg ist jede personenbezogene Spalte Teil der
Auskunft, außer sie steht mit Grund unter `excludedColumns`; eine neue Spalte zählt damit
automatisch zur Auskunft. Ein Datum ohne Tabelle nennt den Grund in `personExcluded`. Ein Datum mit
mindestens einer Tabelle mit Weg ist eine Kategorie der Selbstauskunft unter seiner `id`.

Die Pflicht prüft `ComplianceManifestTest`, nicht der Lader: So nennt auch diese Lücke Datei und
einzufügendes YAML (Entscheidung 7). Das Faktenblatt Datenkatalog zeigt die Angabe je Datum, das
Delta jede geänderte Angabe als eigene Änderung.

Jede Kategorie liefert genau ein Exporteur des Ports `SelfDisclosureExporter` im Modul ihrer Daten,
mit einer Positivliste von Feldern und den Spalten, aus denen sie stammen. `SelfDisclosureCoverageTest`
hält beide Seiten ohne Datenbank gleich: Jede Spalte der Auskunft ist von einem Feld abgedeckt (die
Spalten des Wegs deckt die Auswahl selbst ab), kein Feld nennt eine unbekannte oder ausgeschlossene
Spalte, außer es sagt über sie nur ja/nein oder eine feste Bezeichnung, und ein Datum mit Exporteur
führt `EXPORT` unter `accessPaths`.

Der Weg steht an der Tabelle, weil die Tabelle die Einheit ist, an der eine neue Spalte oder Tabelle
auffällt; eine Liste der Kategorien neben dem Manifest wüchse nicht mit.

## Konsequenzen

Einfacher: Ein Bereich wächst ohne Konflikt in seiner eigenen Datei. Faktenblätter und
Instanzexport lesen typisierte Records statt YAML; das Delta liest schemafrei und vergleicht so auch
Versionen mit verschiedenem Schema. Ein Tippfehler im Schlüssel scheitert, statt still
zu verschwinden.

Schwieriger: Eine Prompt-Klasse mit vielen Meldungen erzeugt Fehlalarme; die Nachpflege ist eine
neue Prüfsumme. Jede neue Spalte braucht eine Zeile im Manifest, auch ohne Personenbezug.
Jede neue Konfigurationsklasse braucht eine Markierung, jeder geänderte Standardwert eines
datenwirksamen Schlüssels einen nachgezogenen Eintrag.

Restrisiken: Das Manifest belegt Vollständigkeit, nicht Richtigkeit; die sichern Review und
Drift-Routine.
Ein ausgehender Client eines Typs, der weder in `OUTBOUND_CLIENTS` noch in `OUTBOUND_BEANS` steht, oder
ein Zugriff über Reflection entgeht dem Strukturtest (Entscheidung 8); die Liste wächst mit jedem neuen
Client-Typ, und das Review prüft neue Abhängigkeiten darauf. Ebenso entgeht ihm jede Verbindung, die
eine eingebundene Bibliothek in ihrem eigenen Code aufbaut, etwa der Abruf einer Konfiguration beim
Hersteller; solche Abrufe erkennt nur der Lauf ohne Internetzugang (`e2e/offline/`), und nur in den
Funktionen, die er durchläuft. Bewusst nicht erkannt wird eine neue
Klasse, die über eine bereits annotierte Klasse (etwa `RerankClient`) Daten an ein bestehendes Ziel
schickt; das fangen die Kennzeichnung der Datenwirkung am PR und die Drift-Routine auf.
Das Delta erkennt eine rein redaktionelle Textänderung nur an Leerzeichen; jede andere geänderte
Beschreibung schlägt es mit offener Zuordnung als Zustimmung vor (Entscheidung 10).

## Verworfene Alternativen

- JSON Schema neben dem Lader — zweite Quelle des Schemas, ohne zusätzliche Bibliothek nicht prüfbar.
- Eigenes Gradle-Modul — sieht die geprüften Code-Stellen nicht und spart keinen Build.
- Prüfsumme über die Quelldatei — jede Formatierung oder Javadoc-Änderung schlüge an.
- Prüfsumme über den gerenderten Prompt — braucht je Prompt eigene Eingaben und Aufrufcode.
- Personenbezogene Spalten allein nennen — eine neue Spalte bliebe unbemerkt.
- Schema aus der Testcontainers-Datenbank — ohne Docker übersprungen, nicht in jedem Testlauf.
- Liste der datenwirksamen Schlüssel im Test — ein neuer Schlüssel fiele dort nicht auf.
- Jeder Schlüssel ohne Datenwirkung einzeln markiert — rund 300 Markierungen ohne Erkenntnis.
- Eigene Eintragsart für Standardmodelle — Modell und Adresse sind gewöhnliche Voreinstellungen.
- Prozessor im regulären `compileJava` — verliert beim inkrementellen Übersetzen die Beschreibungen.
- Datenflüsse als Java-Enum mit allen Angaben — Fachtext im Code, zweite Quelle neben dem Manifest.
- Delta über die Records des aktuellen Laders — weist ältere Manifeste mit fehlendem Pflichtfeld ab.
- Negativliste geheimer Schlüssel im Export — ein neues Geheimnis ginge unbemerkt hinaus.
- Ein Export in einem oberen Modul, das alle Fachpakete liest — neue Kanten über alle Module.
- Delta nur aus zwei JSON-Fassungen — ältere Releases haben keine als Anhang.
- Neue personenbezogene Spalten ausgeschlossen, bis sie freigegeben sind — eine vergessene Spalte
  fehlte still in der Auskunft.
