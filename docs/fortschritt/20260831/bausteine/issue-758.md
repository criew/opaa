# Issue #758 — feat(models): Laufzeitauflösung des aktiven Chat-Modells statt fest gebundener Autoconfiguration
- Geschlossen: 2026-08-22 (completed)
- Labels: enhancement, backend, size:M
- PRs: #767 (2026-08-22)

**Laut Issue:** Antwortgenerierung, Titelgenerierung und Health-Anzeige sollten das aktive Chat-Modell zur Laufzeit aus der Datenbank auflösen statt einen beim Start gebauten `ChatClient` zu verwenden — sonst wirkt eine Modelländerung erst nach Neustart. Gefordert: Zwischenspeicherung des gebauten Clients mit Invalidierung bei Aktivierung/Änderung/Löschung, verständliche deutsche Fehlermeldung ohne aktives Modell, kein stillschweigendes Ausweichen bei einem nicht erreichbaren aktiven Modell.

**Geliefert:** `io.opaa.llm.ActiveChatModelResolver` baut `ChatClient`/`OpenAiChatModel` programmatisch aus dem aktiven `LlmModel` und cached das Ergebnis; `LlmModelService` veröffentlicht ein `ActiveChatModelChangedEvent` nach Commit (`@TransactionalEventListener(AFTER_COMMIT)`), auf das der Resolver hört. `NoActiveChatModelException` (503, deutsche Meldung) ersetzt die NPE-Kaskade. `ChatHealthIndicator` liest jetzt Basis-Adresse/Modell-Kennung aus dem Resolver. Die alte `OpenAiChatAutoConfiguration` ist in `application.yml` ausgeschlossen; die Embedding-Seite blieb unangetastet. Löschen des aktiven Modells löst laut PR bewusst keine eigene Invalidierung aus, weil `deleteModel` das ohnehin mit 409 blockiert (Annahme, keine Abweichung vom Issue). Deckt sich mit den Abnahmekriterien des Issues.

**Verifikation:** `ActiveChatModelResolver.java`, `NoActiveChatModelException.java` und `ActiveChatModelChangedEvent.java` existieren im Worktree unter `backend/src/main/java/io/opaa/llm/`. `ChatHealthIndicator.java` vorhanden.

**Themen:** modellverwaltung, backend, laufzeitauflösung
