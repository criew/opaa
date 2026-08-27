# Issue #209 — Agent and prompt library assets with the knowledge share chain
- Geschlossen: 2026-08-24 (not planned)
- Labels: enhancement, backend, frontend, size:L
- PRs: keine

**Laut Issue:** Zweiter und dritter Asset-Typ (`Agent`, `PromptLibrary`) mit dem entscheidenden neuen Mechanismus: der Freigabekette beim Teilen eines Agenten — welche Bibliotheken er braucht, Anfrage nach Ko-Freigabe an fehlende Bibliothekseigentümer, Ergebnis-Report ("2 von 3 ko-freigegeben"). Ein Agent retrieved immer mit den Rechten des aufrufenden Nutzers, nie mit eigenen.

**Geliefert:** Nicht umgesetzt. Beim Schließen als "noch nicht umgesetzt, später" markiert (Ticket-Hygiene im Zuge der Epic-#198-Schließung). Vor dem Schließen gab es aber eine wichtige inhaltliche Aktualisierung (23.08.2026): Die im Issue vorgesehene `USER`-Rolle existiert nicht mehr — #330 hat `AssetRole.USER` gestrichen, niedrigste Rolle ist jetzt `VIEWER`; die fachliche Anforderung ("Agent nutzen ohne Konfiguration zu sehen") bleibt bestehen, braucht bei Umsetzung aber einen anderen Mechanismus. Außerdem läuft die Modellwahl inzwischen über die verwalteten Chat-Modelle aus Epic #755 (Modelle in der Datenbank, verwaltet unter `admin/models`) statt über einen freien Modellnamen.

**Verifikation:** Keine `Agent.java`/`PromptLibrary.java` als Asset-Klassen im Backend gefunden (`find` ohne Treffer) — bestätigt Nichtumsetzung.

**Themen:** agenten, spaces, rechteverwaltung, modellverwaltung
