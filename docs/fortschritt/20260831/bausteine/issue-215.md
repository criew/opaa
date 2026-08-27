# Issue #215 — Asset catalog: visibility, listed flag and space directory
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:M
- PRs: keine

**Laut Issue:** Ein Katalog mit `visibility` (`PRIVATE`/`SHARED`/`ORGANIZATION`) und `listed`-Flag (Default `false`), Verteilungsstufen über die Grant-Subjekte, Katalogsuche über zugängliche plus gelistete Assets, sowie ein Space-Verzeichnis mit Sichtbarkeitsstufen (`PRIVATE`/`DISCOVERABLE`/`OPEN`) samt Beitrittsanfrage und Ein-Klick-Selbstbeitritt.

**Geliefert:** Teilweise als Datenmodell, der eigentliche Kern fehlt. Laut Aktualisierungskommentar (23.08.2026) bereits vorhanden aus #202/#333: `LibraryVisibility` (`PRIVATE`/`SHARED`/`ORGANIZATION`) und `listed`-Flag an der Wissensbibliothek, `SpaceVisibility` (`PRIVATE`/`DISCOVERABLE`/`OPEN`), Bibliothekseigentümer als Nutzer oder Gruppe (`LibraryOwnerType`). **Noch offen:** das Space-Verzeichnis mit Beitrittsanfrage/Selbstbeitritt — es gibt keine Join-Endpunkte, die Sichtbarkeitsstufen sind reine Datenhaltung — und die Katalogsuche, die erst mit den Asset-Typen aus #209 sinnvoll wird. Die Abhängigkeit #208 (Stewards) ist mit #330 entfallen (Annahme eines Grants durch eine Gruppe ist gestrichen). Beim Schließen im Zuge von Epic #198 als "noch nicht umgesetzt" bestätigt.

**Verifikation:** Existenz von `LibraryVisibility`/`SpaceVisibility` als Datenmodell plausibel (durch Kommentar belegt, nicht separat gegrept); keine Join-/Katalog-Endpunkte erwartet und nicht gefunden.

**Themen:** spaces, wissensbibliotheken, rechteverwaltung
