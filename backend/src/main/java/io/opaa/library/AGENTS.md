# Modul library

Pakete (`io.opaa.*`): library. Ergänzt `backend/AGENTS.md`.

## Zweck und Grenze

Die Verwaltung der Wissensbibliothek, des ersten Asset-Typs: anlegen, Quelle konfigurieren und testen, Zeitplan,
Push-Eingang, Freigabe für Fremdzugänge, Aufräumen verwaister Originale. Der Bestand (Bibliothek, Ordner, Dokumente)
liegt in `io.opaa.knowledge`, Zuordnung und Adressprüfung in connections; eine eigene Tabelle hat library nicht.

## Invarianten und Stolpersteine

- **Das Recht zu löschen und zu übertragen liegt immer bei einer Person.** Beim Anlegen erhält der
  Ersteller ausdrücklich `OWNER`; bei einer Gruppenbibliothek bekommt die Gruppe `MANAGER`, nie `OWNER`.
- **Eine private Bibliothek** (`KnowledgeLibrary#isOwnerOnly`, Merkmal der Asset-Schale) hat nur ihre Besitzerin.
  Fremdzugang und Freigabe-Obergrenze fragen `OwnerOnlyRule#requireShareable`; für alle anderen ist sie unbekannt
  (`404`, gleiche Antwort wie ohne sie). Angelegt wird sie nur in `PrivateLibraryCreation`, an Zugang, Konto und
  Ziel gebunden über `PrivateLibraryConnections`; Protokolle nennen sie `auditName()`. Ein zweites Merkmal an
  `knowledge_libraries` gibt es nicht. Gelöscht wird sie nur über `PrivateLibraryErasure` (Marker, eine Transaktion,
  Nachzählen; Verweise außerhalb über `ErasedLibraryReferences`), auch vom `PrivateLibraryDeletionRun`. Jede neue
  Operation mit Bibliotheks-, Asset-, Dokument- oder Chunk-Kennung braucht einen Eintrag in
  `PrivateLibraryInvisibilityIntegrationTest`.
- **Die Freigabegrenze einer Konnektorbibliothek:** Eine Anfrage über der Grenze ist `409`, nicht `403` — die
  Rolle steht nicht in Frage. Eine Upload-Bibliothek trägt nie eine engere Grenze (`KnowledgeLibraryAssetType`).
- **Gespeicherte Zugangsdaten gelten nur für dasselbe Ziel** (`SecretTarget`): Ursprung (Schema, Host, Port) der
  `sourceUrl` und die Bindung des Konnektors (`credentialBinding`, etwa SMB-Freigabe oder imitiertes Konto). Eine
  neue Bindung bei gleichem Ursprung verlangt neue Zugangsdaten (`400`).
- **Zuordnen und Lösen** gehen durch `LibraryConnectionService` (Konnektor, Audit-Felder); ein
  Anlegen über einen Zugang hängt nur die Zuordnung an (`attachNew`).
- **Anlegen, Test und Auflistung sind Entwürfe** (`EffectiveSourceSettings#ofDraft`), Ändern geht
  über `ofChange`/`ownPart`: library setzt keine Einstellungen selbst zusammen.
- **Eine neue Konnektorbibliothek** (Anlegen, Zugang zuordnen, Test und Auflistung vor dem
  Anlegen) fragt `ConnectorReleaseService`, nie `CapabilityService` mit `CREATE_CONNECTOR_LIBRARY`.
- **Das Kontingent je Bibliothek** liest `SharedLibraryQuota` (knowledge) bei jeder Prüfung aus der Datenbank,
  begrenzt durch `opaa.library.quota-bytes`; den hausweiten Wert ändert nur `SharedLibraryQuotaAdministration`.
  Geprüft wird nur Zuwachs: Eine Bibliothek über dem Kontingent behält alles und nimmt nichts mehr auf.
- **Fachpakete kennen einander nicht** (`PermissionPackageBoundaryTest`): library kennt weder `prompt` noch `space`
  noch `group`; den Lebenszyklus erreicht es nur über die Ports von `io.opaa.permission`, auch in `library.web`:
  Den Nachfolgezustand der Einzelansicht trägt `LibraryDetail`, wie `LibrarySummary` den der Liste.

## Maßgebliche ADRs

- [ADR-0018](../../../../../../../docs/decisions/0018-quellkonfiguration-in-der-bibliothek.md): Quellkonfiguration in der Bibliothek
- [ADR-0030](../../../../../../../docs/decisions/0030-originalablage-der-uploads.md) (Entscheidung 11): Aufräumen verwaister Originale
- [ADR-0035](../../../../../../../docs/decisions/0035-fremdzugaenge-mcp-server-und-zugangstokens.md) (Entscheidung 4): Freigabe für Fremdzugänge
- [ADR-0036](../../../../../../../docs/decisions/0036-berechtigungsmodell-gruppen-und-faehigkeiten.md) (Entscheidung 5): Anlegen über Fähigkeiten
- [ADR-0037](../../../../../../../docs/decisions/0037-reichweite-als-freigabe-an-alle.md) (Entscheidung 5): Freigabe-Obergrenze
- [ADR-0038](../../../../../../../docs/decisions/0038-steckbare-konnektoren.md): Konnektoren nur über Registry
- [ADR-0049](../../../../../../../docs/decisions/0049-private-bibliotheken-und-kontingente.md) (Entscheidungen 1, 2, 4): private Bibliotheken, Kontingent
- [ADR-0060](../../../../../../../docs/decisions/0060-schlanke-konfiguration-und-einrichtungsassistent.md) (Entscheidung 2): Kontingent unter der Obergrenze des Betriebs

## Verweise

- Handbuch (`docs/handbuch/`): `berechtigungen.md` §4, `private-bibliotheken.md`, `wissensbibliotheken.md` §3–4,
  `fremdzugaenge.md` §4

## Tests bei Änderungen

```bash
./gradlew test --tests 'io.opaa.library.*' --tests 'io.opaa.architecture.*'
```

Die Endpunkte der Bibliothek, der Dokumente, der Quelltypen, des Push-Eingangs und der verwaisten
Originale liegen samt Tests in `io.opaa.library.web`; `library.*` deckt sie mit ab.
