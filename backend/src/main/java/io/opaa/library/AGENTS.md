# Modul library

Pakete (`io.opaa.*`): library. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Die Verwaltung der Wissensbibliothek, des ersten Asset-Typs: anlegen, Quelle konfigurieren und
testen, Zeitplan, Push-Eingang, Freigabe für Fremdzugänge, Aufräumen verwaister Originale. Der
Bestand selbst (Bibliothek, Ordner, Dokumente) liegt in `io.opaa.knowledge`. library hängt nur von
foundation, identity, rights und knowledge ab und besitzt keine eigene Tabelle.

## Invarianten und Stolpersteine

- **Das Recht zu löschen und zu übertragen liegt immer bei einer Person.** Beim Anlegen erhält der
  Ersteller ausdrücklich `OWNER`; bei einer Gruppenbibliothek bekommt die Gruppe `MANAGER`, nie
  `OWNER`.
- **Die Freigabegrenze einer Konnektorbibliothek:** Eine Anfrage über der Grenze ist `409`, nicht
  `403` — die Rolle steht nicht in Frage. Eine Upload-Bibliothek trägt nie eine engere Grenze
  (`KnowledgeLibraryAssetType`).
- **Gespeicherte Zugangsdaten gelten nur für denselben Ursprung** (Schema, Host, Port) der
  `sourceUrl` (`SourceOriginMatcher`). Eine fehlende oder unlesbare URL zählt als anderer Ursprung,
  die Zugangsdaten werden dann neu verlangt.
- **library kennt keinen Konnektor.** Beschreibung, Validierung, Verbindungstest und Fähigkeiten
  eines Konnektors kommen nur über `SourceConnectorRegistry`.
- **Fachpakete kennen einander nicht:** library kennt weder `prompt` noch `space` noch `group`; den
  Lebenszyklus erreicht es nur über die Ports von `io.opaa.permission`.

## Verweise

- ADRs (`docs/decisions/`): 0018, 0030, 0036, 0037, 0038
- Handbuch: `docs/handbuch/bibliotheken-und-berechtigungen.md`, Abschnitt 4;
  `docs/handbuch/indexierung.md`, Abschnitt 2; `docs/handbuch/fremdzugaenge.md`, Abschnitt 4
- Strukturtests: `PermissionPackageBoundaryTest`, `ModularArchitectureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.library.*' --tests 'io.opaa.architecture.*'
```

Die Endpunkte der Bibliothek prüfen die Klassen in `io.opaa.api` mit; bei Änderungen an Verträgen
zusätzlich die berührten `io.opaa.api`-Testklassen.
