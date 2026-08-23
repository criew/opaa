# Issue #353 — Standardposition der Modellanbieter auf lokal-first umstellen
- Geschlossen: 2026-08-14 (completed)
- Labels: documentation, size:S
- PRs: #384 (2026-08-14)

**Laut Issue:** Teil von #344. Heute sei ein Cloud-Anbieter erster Bürger, Konfiguration und Standardwerte setzten ihn stillschweigend voraus. Zu klären: welche Standardwerte in `application*.yml` das taten und wie eine Konfiguration aussieht, die ohne Zutun lokal bleibt und Cloud-Nutzung zu einer bewussten Handlung macht. Ergebnis sollte eine Entscheidungsvorlage sein.

**Geliefert:** Über die reine Vorlage hinaus wurde direkt Code geändert (PR als `feat`, nicht nur `docs`). `spring.ai.openai.base-url` und abgeleitete Werte haben keine feste Voreinstellung mehr; neuer `OpenAiBaseUrlGuard` bricht den Start laut ab, wenn `openai` als Anbieter gewählt ist, aber keine Adresse gesetzt wurde (Muster von `AuthProfileGuard`, ADR-0005). Keine technische Sperre gegen externe Ziele wurde gebaut — das ist ausdrücklich dokumentiert als Konfigurationszusage ohne Durchsetzung. `.env.example` wechselt die Voreinstellung von `openai` auf `ollama`. Neuer Test `OpenAiBaseUrlGuardTest` (6 Fälle) und Anpassungen in `MixedProviderConfigurationTest`/`ProviderConfigurationTest`. Damit liefert der PR mehr als die im Issue verlangte reine Entscheidungsvorlage — die Entscheidung wurde direkt umgesetzt.

**Verifikation:** `OpenAiBaseUrlGuard.java` existiert im Worktree unter `backend/src/main/java/io/opaa/config/`.

**Themen:** modellanbieter, konfiguration, lokal-first, produktausrichtung, backend
