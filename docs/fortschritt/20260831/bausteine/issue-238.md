# Issue #238 — Historisierung von Rechten und Gruppenmitgliedschaften
- Geschlossen: 2026-08-17 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #427 (2026-08-17)

**Laut Issue:** Die Rechtemenge eines Nutzers ist berechnet und ändert sich u. a. per Verzeichnissynchronisation. Gefordert war die Historisierung von Grants und Gruppenmitgliedschaften (gültig von/bis, auslösender Vorgang), die Rekonstruktion der Rechtemenge zu einem Stichtag und die belegbare Beantwortung der Negativfrage ("hatte X am Datum Y KEINEN Zugriff"). Ausdrücklich nicht gefordert: Mitschreiben der vollständigen Rechtemenge je Abfrage (Datensparsamkeit). Ein Bericht "abgelehnte Zugriffe" sollte aus der Spezifikation entfernt werden.

**Geliefert:** Drei neue Historientabellen (Migration `018-permission-history.yaml`): `asset_grant_history`, `group_membership_history`, `library_visibility_history`, jeweils als halboffene Intervalle mit Ursache-Enum. `PermissionHistoryService` schreibt diese Intervalle und rekonstruiert die Rechtemenge über `readableLibraryIdsAsOf(userId, organizationId, Instant)`. Backfill des kompletten Altbestands als eigenes changeSet. `QueryService` gleicht den je Abfrage angewandten Suchbereich automatisiert gegen die Rechtehistorie ab und protokolliert nur bei Abweichung — keine dauerhafte Protokollzeile der vollständigen Rechtemenge. Löschschicksal der Historie als eigene Architekturentscheidung dokumentiert (ADR-0016): Fachobjekt-Spalten ohne FK (Historie überlebt Bibliotheks-/Gruppenlöschung), Subjektspalten `RESTRICT`, `actor_user_id` `SET NULL`. Kein neuer REST-Endpunkt für die Stichtag-Abfrage — bewusste Abgrenzung, da die Abnahmekriterien nur Rekonstruierbarkeit verlangten, keine API. Im Review wurden mehrere Bugs behoben (Flush-Reihenfolge, fehlender Backfill, nicht historisierte `ensurePersonalLibrary`-Provisionierung, ursprünglich `CASCADE` statt lösch-überlebender Historie).

**Verifikation:** `backend/src/main/java/io/opaa/library/PermissionHistoryService.java`, die Migration `018-permission-history.yaml` und `docs/decisions/0016-loeschschicksal-rechtehistorie.md` existieren im heutigen Code unverändert vom PR-Umfang.

**Themen:** auth, security, spaces, retrieval, doku
