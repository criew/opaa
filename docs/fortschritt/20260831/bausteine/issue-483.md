# Issue #483 — feat(security): Zugangsdaten der Quellkonfiguration sicher verwahren
- Geschlossen: 2026-08-19 (completed)
- Labels: enhancement, backend, size:M, security
- PRs: #504 (2026-08-19)

**Laut Issue:** Zugangsdaten in der Quellkonfiguration sollten verschlüsselt statt im Klartext liegen (Schlüssel aus Umgebungsvariable), nie in API-Antworten oder Logs auftauchen, und der Wechsel sollte ohne Laufausfall möglich sein.

**Geliefert:** Wie gefordert. AES-256-GCM-Verschlüsselung über `CredentialsEncryptor` mit zufälligem IV je Wert, transparent über einen JPA-`AttributeConverter` (`SourceCredentialsConverter`) an der Persistenzgrenze. Schlüssel aus `OPAA_CREDENTIALS_ENCRYPTION_KEY`; lokale Profile nutzen einen als nicht-produktiv markierten Default. Fehlender/ungültiger Schlüssel führt zu 503 statt 500. Migration verbreitert die Spalte für die verschlüsselte Kodierung; Alt-Klartextwerte werden am fehlenden Präfix erkannt und beim nächsten Schreibvorgang verschlüsselt (keine Batch-Migration möglich, da nur die Anwendung den Schlüssel kennt).

**Verifikation:** `backend/src/main/java/io/opaa/library/SourceCredentialsConverter.java`, `backend/src/main/java/io/opaa/security/CredentialsEncryptor.java`, `CredentialsEncryptionKeyMissingException.java` und `CredentialsEncryptionProperties.java` existieren im heutigen Code. `docs/deployment.md` dokumentiert den Schlüssel weiterhin.

**Themen:** backend, sicherheit, spaces, retrieval, adr
