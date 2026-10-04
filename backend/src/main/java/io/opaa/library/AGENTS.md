# Modul library

Pakete (`io.opaa.*`): library. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Die Verwaltung der Wissensbibliothek, des ersten Asset-Typs: anlegen, Quelle konfigurieren und
testen, Zeitplan, Push-Eingang, Freigabe für Fremdzugänge, Aufräumen verwaister Originale. Der
Bestand selbst (Bibliothek, Ordner, Dokumente) liegt in `io.opaa.knowledge`. library hängt nur von
foundation, identity, rights, knowledge und connections ab (Zuordnung einer Bibliothek zu einem
Zugang, Prüfung ihrer Adresse) und besitzt keine eigene Tabelle.

## Invarianten und Stolpersteine

- **Das Recht zu löschen und zu übertragen liegt immer bei einer Person.** Beim Anlegen erhält der
  Ersteller ausdrücklich `OWNER`; bei einer Gruppenbibliothek bekommt die Gruppe `MANAGER`, nie
  `OWNER`.
- **Die Freigabegrenze einer Konnektorbibliothek:** Eine Anfrage über der Grenze ist `409`, nicht
  `403` — die Rolle steht nicht in Frage. Eine Upload-Bibliothek trägt nie eine engere Grenze
  (`KnowledgeLibraryAssetType`).
- **Gespeicherte Zugangsdaten gelten nur für denselben Ursprung** (Schema, Host, Port) der
  `sourceUrl` (`ServerAddress#sameOrigin`) und, wo der Konnektor enger bindet, dasselbe Ziel darin
  (`SourceConnector#keepsCredentials`, etwa die SMB-Freigabe). Eine fehlende oder unlesbare URL
  zählt als anderer Ursprung, die Zugangsdaten werden dann neu verlangt.
- **Anlegen, Test und Auflistung sind Entwürfe** (`EffectiveSourceSettings#ofDraft`), Ändern geht
  über `ofChange`/`ownPart`: library setzt keine Einstellungen selbst zusammen.
- **Eine neue Konnektorbibliothek** (Anlegen, Zugang zuordnen, Test und Auflistung vor dem
  Anlegen) fragt `ConnectorReleaseService`, nie `CapabilityService` mit `CREATE_CONNECTOR_LIBRARY`.
- **library kennt keinen Konnektor.** Beschreibung, Validierung, Verbindungstest und Fähigkeiten
  eines Konnektors kommen nur über `SourceConnectorRegistry`.
- **Fachpakete kennen einander nicht:** library kennt weder `prompt` noch `space` noch `group`; den
  Lebenszyklus erreicht es nur über die Ports von `io.opaa.permission`. Das gilt auch für
  `library.web`: Den Nachfolgezustand der Einzelansicht trägt `LibraryDetail`, wie `LibrarySummary`
  den der Liste.

## Verweise

- ADRs (`docs/decisions/`): 0018, 0030, 0036, 0037, 0038
- Handbuch: `docs/handbuch/bibliotheken-und-berechtigungen.md`, Abschnitt 4;
  `docs/handbuch/indexierung.md`, Abschnitt 2; `docs/handbuch/fremdzugaenge.md`, Abschnitt 4
- Strukturtests: `PermissionPackageBoundaryTest`, `ModularArchitectureTest`

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.library.*' --tests 'io.opaa.architecture.*'
```

Die Endpunkte der Bibliothek, der Dokumente, der Quelltypen, des Push-Eingangs und der verwaisten
Originale liegen samt Tests in `io.opaa.library.web`; `library.*` deckt sie mit ab.
