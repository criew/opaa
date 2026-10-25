# ADR-0051: Architekturvertrag des Backends

## Status

Akzeptiert · zuletzt geändert 2026-10-25

## Kontext

Das Backend ist in logische Module geschnitten; ArchUnit erzwingt Modulkanten, Schichtung,
Zyklenfreiheit, Konnektor-Isolation und die Web-Schicht. Die übrigen Regeln (ein Weg je
Querschnittsaspekt, Umfang einer Erweiterung, Umgang mit Kopien, Dokumentation) stehen verteilt in
Tests, AGENTS.md-Dateien und in der Praxis. Nirgends steht, welche Regel von der CI geprüft wird und
welche nur Konvention ist, und nirgends steht, welche Abweichung von einer Regel bewusst in Kauf
genommen wird. Eine Prüfung der Architektur, ob durch einen Menschen oder eine Routine, misst
deshalb gegen ihr eigenes Urteil und meldet Bewusstes als Befund.

Dieser ADR ist das Soll: die Regeln, je Regel die Prüfinstanz, und die akzeptierten Abweichungen mit
der Bedingung, unter der sie wieder vorgelegt werden.

## Entscheidung

### 1. Eine Quelle für das Modulmodell

Module, erlaubte Kanten, Schichtung und Web-Schicht stehen genau einmal: in `backend/AGENTS.md`
(„Logische Module und Schichtung“) und in `ModularArchitecture` (`MODULES`, `LAYERS`,
`ALLOWED_MODULE_EDGES`, `INDEXING_CORE`, `CONNECTION_PACKAGES`). Modul-AGENTS.md wiederholen keine
Kanten. Eine neue Kante ist eine Entscheidung und wird im PR begründet; zuvor wird geprüft, ob eine
Schnittstelle im unteren Modul genügt. Eine Kante, die kein Code mehr nutzt, wird entfernt.
Geprüft durch `ModularArchitectureTest`, `SourceReferenceTest` (Importe und Javadoc im Quelltext)
und `ModuleAgentsFileTest`.

### 2. Ein Weg je Querschnittsaspekt

Jeder Querschnittsaspekt hat genau einen Mechanismus. Ein zweiter Weg ist ein Befund, auch wenn er
funktioniert.

| Aspekt | Der eine Weg | Geprüft durch |
|---|---|---|
| Aktueller Nutzer | `@Caller CurrentUser`; `SecurityContextHolder` nur in Filtern | Konvention |
| Admin-only | `@PreAuthorize("hasRole('SYSTEM_ADMIN')")` am Controller oder `CurrentUser#requireSystemAdmin` im Service, nie beides, keine Kopie | Konvention |
| Audit | `AuditLogService#record` | `AuditFunnelStructureTest` |
| Zeit | `Clock`-Bean aus `common.ClockConfiguration`; Entities stempeln sich selbst | Ratsche (Entscheidung 6) |
| Konfiguration | `@ConfigurationProperties`, je Modul registriert; `@Value` nur ohne Properties-Typ | Ratsche (Entscheidung 6) |
| Fehler | `common`-Exceptions, Abbildung nur in `GlobalExceptionHandler`; kein `ResponseStatusException` in Web-Klassen | `TransportStatusCodeSpecificationTest` (Statuscodes); Rest Konvention |
| Rate-Limit | `RateLimitConfiguration`, endpunktweise | `TransportStatusCodeSpecificationTest` (429) |
| Feature-Flags | `FeatureFlags` (ADR-0044) | Konvention |
| Geheimnisse | `CredentialsEncryptor`/`SettingsEncryptor` auf einem AES-GCM-Kern; Quellgeheimnisse nur über `SourceConnectionResolver` im Kern | `ModularArchitectureTest` (Konnektor-Paketregel, `LIBRARY_SECRET`, `SECRET_PORT`), `RunSecretContractCoverageTest` |
| Ausgehende Verbindungen | `sourceaccess`; S3 über `io.opaa.s3`, Microsoft Graph über `io.opaa.msgraph`; jeder ausgehende Client ist über `@DataFlow` angemeldet, Hüllklassen ohne eigenes Ziel tragen `@DataFlowCarrier` (ADR-0052, Entscheidung 8) | `SourceAccessDependencyStructureTest`, `ModularArchitectureTest` (`OUTBOUND_CLIENTS`, `OUTBOUND_BEANS`) |
| Datenbankschema | Flyway-Migrationen je Modul, SQL ohne Schemaqualifizierung (ADR-0034, ADR-0056) | `MigrationLayoutTest`, `MigrationModuleBoundaryTest`, `MigrationOrderTest` |
| Spring-Testkontexte | die Signaturen in `io.opaa.test` | `SpringContextSignatureTest` |

„Konvention“ heißt: Die Regel gilt, aber nur das Review hält sie.

### 3. Änderungsumfang einer Erweiterung

Eine Erweiterung der Fachlichkeit darf nur ihr eigenes Paket anfassen. Die Tabelle nennt je
Erweiterungsart, wie viele Dateien außerhalb des neuen Pakets sich ändern dürfen; mehr ist ein
Befund gegen die Erweiterungsstelle, nicht gegen die Erweiterung.

| Erweiterung | Dateien außerhalb des eigenen Pakets | Geprüft durch |
|---|---|---|
| Neuer Quell-Konnektor | 0; er nutzt nur `indexing.spi`, `indexing.run`, `indexing.filesync`, `indexing.document`, `indexing.job`, `indexing.attachment`, `sourceaccess`, `format`, `knowledge`-Entities, `api.types` (ADR-0038, ADR-0045); seinen Datenfluss für die Auskunft nennt er im eigenen `SourceConnectorDescriptor` (ADR-0052, Entscheidung 11) | `ModularArchitectureTest` (`CONNECTOR_IMPORTS`; Konnektoren kennen einander nicht), `ConnectorDataFlowsIntegrationTest` |
| Neues Dokumentformat | 1 (Registrierung) | Konvention, am PR nachgemessen |
| Weiterer OIDC-Anbieter | 0 (ADR-0025) | Konvention |
| Neue Capability | 0 Switch-Kaskaden (ADR-0036) | Konvention |
| Zweiter Verzeichnistyp | erst dann entsteht ein Verzeichnis-SPI nach dem Muster von ADR-0038 | Entscheidung 5 |
| Zweiter Nutzer des Tool-Katalogs | erst dann entsteht ein Tool-SPI | Entscheidung 5 |

Nachgemessen wird am PR mit `git diff --stat`.

### 4. Kopien, Kommentare, Dokumentation

- Ein Muster, das zum dritten Mal kopiert wird, geht in den Kern. Geprüft im Review.
- Kommentare beschreiben den Vertrag (AGENTS.md „Code-Kommentare“). Geprüft durch
  `check_code_comments.sh`.
- ADRs halten den Endstand ohne Nachträge und ohne Änderungsverlauf, mit Status und Datum
  (`docs/decisions/README.md`). Geprüft durch `check_adr_style.sh`. Jede Modul-AGENTS.md nennt
  ihre maßgeblichen ADRs; geprüft durch `ModuleAgentsFileTest`.
- Feature-Spezifikationen tragen einen Statuskopf. Konvention.

### 5. Akzeptierte Abweichungen

Diese Tabelle ist die Baseline. Eine Prüfung meldet nichts daraus. Jede Zeile nennt die Bedingung,
unter der sie wieder vorgelegt wird; tritt sie ein, wird die Zeile zum Issue und verschwindet hier.

| Abweichung | Warum akzeptiert | Wiedervorlage, wenn |
|---|---|---|
| Repositories werden über Modulgrenzen injiziert (`DocumentRepository`, `UserRepository` in vielen Paketen) | knowledge und identity liegen unter allen Nutzern; die Grenze liegt auf Methodenebene (`DOCUMENT_DELETERS`, `UNFILTERED_LIBRARY_FINDERS`, `KnowledgeLibraryReachWriterTest`); eine Lese-Facade wäre Code ohne Abbau | Plugins Dritter Code laden dürfen |
| `io.opaa.msgraph` liegt in foundation und hat einen Nutzer (SharePoint) | Konnektoren kennen einander nicht; ein zweiter Graph-Konnektor (Exchange) könnte den Client sonst nur kopieren; das Paket kennt nur `sourceaccess`, `outbound`, `security` und hat einen eigenen Strukturtest (ADR-0047, Entscheidung 1) | der Exchange-Konnektor verworfen wird; dann zurück in den SharePoint-Konnektor |
| `diagnosticaccess` liegt im Modul workspace, ohne Bezug zu `space` und `revision` | Es braucht knowledge und läge fachlich bei rights; rights liegt unter knowledge, ein Umzug erzeugte einen Zyklus. workspace ist das niedrigste Modul über knowledge | ein zweites Paket mit demselben Zuschnitt (Diagnose über Rechte und Bestand) entsteht; dann eigenes Modul |
| Der Verzeichnis-Konnektor ist ein Keycloak-Sonderfall (`requireKeycloak`, geschlossenes Enum, Keycloak-Spalten) | Ein SPI ohne zweite Implementierung ist Konzept ohne Nutzen (ADR-0036, Entscheidung 3) | ein zweiter Verzeichnistyp beauftragt wird |
| Zwei Tool-Kataloge (`McpToolCatalog` und der Tool-Loop-Spike) beschreiben dieselbe Suche | Der Spike liegt hinter einem Flag; sein Schicksal ist nicht entschieden | über den Spike entschieden ist; dann löschen oder Tool-SPI |
| Entities stempeln `createdAt`/`updatedAt` mit `Instant.now()` statt über die `Clock` | Entities haben keine Injektion; die Stempel sind keine fachlichen Fristen (`backend/AGENTS.md`, „Zeit“) | ein Test einen deterministischen Entity-Stempel braucht |
| Flache Wurzelpakete mit vielen Klassen (account, metadata, permission, auth, library, chat, llm) | Kein Sicherheitsproblem; dort gibt es kein zweites Anliegen, das eine Paketgrenze bräuchte | ein großer Umbau in einem dieser Pakete ansteht oder ein zweites Anliegen eine Paketregel braucht (Muster: `indexing.spi`/`run`) |
| Anonyme `TransactionSynchronization` („nach Commit, sonst sofort“) an zehn Stellen | Zwei Semantiken (afterCommit, afterCompletion); zwei Helfer lohnen erst mit dem nächsten Vorkommen | die nächste Stelle entsteht |
| `interval(Object)`-Parser in drei Dateiablage-Konnektoren | der nächste Dateiablage-Konnektor nimmt ihn in den Kern | der nächste Dateiablage-Konnektor entsteht |

### 6. Ratschen für Zählbares

Eine Ratsche (engl. ratchet) ist eine CI-Prüfung, die einen Zählwert kennt und nur in eine Richtung
lässt: Der Wert darf nicht steigen; die Obergrenze steht im Skript und wird nur per PR gesenkt. So
muss ein Bestand nicht sofort abgebaut werden, aber er wächst garantiert nicht. Skripte liegen unter
`.github/scripts` (Muster `check_action_pins.sh`, `check_code_comments.sh`). Ratschen:
`Instant.now()` ohne `Clock` in Spring-Beans (`check_instant_now.sh`), `@Value` außerhalb von
`*Properties` (`check_value_annotations.sh`). Keine Ratsche für das, was ArchUnit hart prüft.

### 7. Prüfroutine

Eine regelmäßige Prüfung misst gegen diesen ADR:

1. Eingabe: dieser ADR, `backend/AGENTS.md`, offene Issues mit Label `architecture`, der letzte
   Bericht, die Änderungen seit dem letzten Lauf.
2. Tief gelesen werden nur die seit dem letzten Lauf geänderten Pakete; der Rest über die Zahlen aus
   Entscheidung 6.
3. Fragen je geändertem Paket: neue Modulkante begründet? Änderungsumfang (Entscheidung 3)
   eingehalten? Muster zum dritten Mal kopiert? Querschnittsaspekt auf zweitem Weg? ADR
   fortgeschrieben, wenn die Änderung einen berührt?
4. Jeder Kandidat wird von einem zweiten Prüfer gegen Entscheidung 5 nachgestellt, bevor er
   gemeldet wird.
5. Ausgabe: drei Listen (neu, behoben seit letztem Lauf, unverändert offen). Für Neues ein Issue mit
   Label `architecture` und Beleg, keine Fixes. Bei „nichts Neues“ eine Zeile.
6. Die Prüfung ändert keinen Code, meldet nichts aus Entscheidung 5 und nichts, was ein Issue hat,
   und schlägt keine Abstraktion vor, die netto Code hinzufügt, ohne das zu begründen.

## Konsequenzen

Einfacher: Eine Prüfung hat ein Soll und eine Baseline; „bewusst so“ steht geschrieben. Eine Regel
ohne Prüfinstanz ist als Konvention sichtbar.

Schwieriger: Die Tabellen in Entscheidung 2, 3 und 5 werden mit jedem einschlägigen PR
fortgeschrieben; ein PR, der eine Abweichung beseitigt, streicht ihre Zeile.

Restrisiken: Konventionen hängen am Review. Eine eingetretene Wiedervorlage-Bedingung braucht eine
Entscheidung, die Prüfung kann sie nur melden.

## Verworfene Alternativen

- Alle Regeln in `backend/AGENTS.md` — die Datei ist Arbeitsanweisung, kein Soll mit Prüfinstanz und
  Baseline.
- Eine Fitness-Function-Bibliothek statt eigener Skripte — ArchUnit deckt den strukturellen Teil,
  der Rest sind Zähler in wenigen Zeilen Shell.

## Offen

- Prüfroutine aus Entscheidung 7 einrichten.
