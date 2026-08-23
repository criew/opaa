# Issue #617 — Zugangsdaten-Exfiltration über aufrufergesetzten Proxy/insecureSsl beim Verbindungstest und Indizierungslauf
- Geschlossen: 2026-08-21 (completed)
- Labels: backend, size:S, security
- PRs: #699 (2026-08-21)

**Laut Issue:** Der Origin-Check des Zugangsdaten-Fallbacks (aus #544/PR #615) sicherte nur das Ziel, nicht den Weg. Ein MANAGER konnte beim Verbindungstest mit `libraryId`-Fallback einen eigenen Proxy/`insecureSsl=true` setzen und das gespeicherte Basic-Auth-Credential über einen selbst kontrollierten Proxy mitlesen. Gefordert: gespeicherten Proxy/insecureSsl erzwingen oder Fallback bei aufrufergesetztem Proxy ablehnen; zusätzlich bewerten, ob der Indizierungspfad eine analoge Regel braucht.

**Geliefert:** Der PR bündelt drei Bausteine (#693, #267, #617) in einem gemeinsamen Härtungs-Strang. Für #617 konkret: `SourceConnectionTestService#withStoredCredentialsIfOmitted` erzwingt beim Zugangsdaten-Fallback jetzt den gespeicherten `sourceProxy`/`sourceInsecureSsl` der Bibliothek statt die Werte des Aufrufers zu übernehmen (Entscheidung „erzwingen" statt „ablehnen", wie im Issue als Alternative vorgesehen). Die geforderte Bewertung des Indizierungspfads wurde durchgeführt: laut PR-Body ist er strukturell nicht betroffen, da seit ADR-0018 `UrlIndexingExecutor`/`RssFeedIndexingExecutor` Proxy/Credentials/insecureSsl ausschließlich aus der persistierten Bibliothek lesen und der Trigger-Endpunkt keinen Request-Body mit solchen Feldern entgegennimmt. #693 und #267 sind vorgelagerte, im selben PR mitgelieferte Härtungen (Redirect-Origin-Fix bzw. SSRF-Zielprüfung), die nicht Gegenstand von #617 waren, aber denselben Strang teilen.

**Verifikation:** `SourceConnectionTestService.java` enthält `withStoredCredentialsIfOmitted` mit entsprechendem Javadoc-Verweis; Methode ist im Verbindungstest-Pfad eingebunden (Zeile ~182). Passt zur PR-Beschreibung.

**Themen:** security, retrieval, knowledge-sources, backend
