# Reproduktionsnachweis: typische Ursachen

Ergänzung zur Regel [Reproduktionsnachweis](../../AGENTS.md#reproduktionsnachweis) der Wurzel-`AGENTS.md`. Ein Test, der den Fehler nicht fangen würde, ist wertlos — und das fällt am Ergebnis nicht auf, weil er ja grün ist.

Typische Ursachen dafür, dass ein Test den Fehler verfehlt:

- **Er prüft eine Bedingung, die vor und nach dem Fix gilt** — etwa „irgendwann wurde invalidiert" statt „zum richtigen Zeitpunkt".
- **Er läuft gegen ein anderes Schema als die Produktion.** Mit `ddl-auto=create-drop` erzeugt Hibernate keine Fremdschlüssel für einfache `UUID`-Spalten ohne `@ManyToOne`, die Flyway-Migrationen dagegen schon. Für alles FK-abhängige gehört `spring.flyway.enabled=true` und `ddl-auto=none` in den Test.
- **Er führt den geänderten Code gar nicht aus** — etwa eine Migration, die in den regulären Integrationstests nur auf leerer Datenbank läuft. Dafür gibt es das Muster in `backend/src/test/java/io/opaa/migration/`.
- **Er mockt genau die Stelle weg, um die es geht** — ein gemockter `PlatformTransactionManager` führt keine Propagation aus, ein gemockter API-Client validiert keinen Request-Body.
