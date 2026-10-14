# Register: Prozesslokaler Zustand (ADR-0021)

OPAA läuft als genau ein Backend-Prozess je Installation
([ADR-0021](../../docs/decisions/0021-single-instance-betrieb.md)). Dieses Register führt jede Stelle,
die diese Annahme trägt. Wer eine neue einführt (Cache, `@Scheduled`, Executor, Semaphore, Zähler,
Start-Aktion), trägt sie hier ein und nennt „(ADR-0021)“ in der Javadoc (Regel in
[backend/AGENTS.md](../AGENTS.md#prozesslokaler-zustand-adr-0021)). Was bereits über die
Datenbank koordiniert (Advisory-Locks in `UserRepository`, `GroupRepository`, `AssetGrantRepository`
und `DirectorySyncRunLock`; `uk_indexing_jobs_library_running`; `SELECT … FOR UPDATE` der
Aufbewahrungs- und Löschläufe; Seed-Marker; OAuth-`state` in `connection_authorizations`), steht
hier nicht: Es trüge auch über Instanzen.

| Art | Fundstelle | Bei mehreren Instanzen |
|---|---|---|
| Rechte-Cache, TTL + Invalidierung nach Commit | `GroupMembershipResolver`, `AssetAccessService`, `MetadataFilterOptionsCache` | Rechteänderung wirkt auf anderer Instanz erst nach TTL: Rechtelücke |
| Cache, nur ereignisgesteuert, kein TTL | `ActiveChatModelResolver`, `OidcProviderRegistry` (auch lokaler Issuer), `MailSenderProvider`/`MailSettingsService`, `OutboundProxySelector` | Modellwechsel, Mail-Einstellungen, deaktivierter Anbieter, Proxy wirken erst nach Neustart |
| Additiver oder Lese-Cache | `SpaceService.personalSpaceProvisioned`, `CaffeineChatMemoryRepository` (vor `chat_messages`), `PermissionTransferService.shownPreviews`, `ProfileSignIn.held`/`ServiceAccountTokens`, `RerankClient.dialect` | Harmlos oder ein Fehlgriff; ein gehaltenes Token läuft bis zum Ablauf weiter |
| Negativ-Cache vor einer Tabelle | `LocalTokenRevocationService` (Denylist vor `local_revoked_tokens`) | Widerruf auf A ist auf B bis zum Cache-Miss unsichtbar; müsste entfallen |
| Zähler und Fenster im Speicher | `RateLimitService.requestLog`, `LocalAuthRateLimiter` (Buckets der lokalen Anmeldung; die Fehlversuch-Sperre liegt in `local_credentials`), `ExternalAccessQuota.windows`, `ExternalAccessMassRetrievalAlarm` | N-faches Kontingent; Alarm erst, wenn eine Instanz die Schwelle allein sieht |
| Prozessweiter Zeitgeber und Zustand | `PermissionHistoryClock` (ADR-0032, #1517), `EmbeddingIndexState`, `LibraryStorageQuotaService#holdIntake` | Monotonie nur je Prozess; Spaltenwahl des Vektorindex und Kontingentsperre je Prozess |
| `@Scheduled`-Löschläufe, idempotent | `AuditRetentionScheduler` und `DiagnosticContextRetentionScheduler` (`FOR UPDATE` auf der Einstellungszeile), `PrivateLibraryDeletionSchedule` (`FOR UPDATE` je Bibliothekszeile), ohne Sperre nach Zeitstempel oder Frist: `ConnectionLogRetention`, `PermissionHistoryRetentionScheduler`, `SuccessionScheduler` (monatliche Löschung), `LocalTokenCleanupScheduler`, `ChatAutoCleanupScheduler`, `ExternalAccessAlertRetention` | Doppelte Läufe ohne Schaden; doppelte Erinnerungsmails |
| `@Scheduled`-Abgleiche, idempotent, `FOR UPDATE` | `LibraryExternalAccessScheduler`, `ConnectionLifecycleReconciler` (täglich und Start), `ConnectionExpiryWatch`, `PendingConsentSweep` | Doppelte Läufe ohne Schaden; Verlierer am `@Version` holt beim nächsten Lauf nach |
| `@Scheduled`-Erkennung ohne Sperre | `SuccessionScheduler` (stündlich) über `SuccessionDetectionService` | Zwei Läufe je Organisation gleichzeitig; keine Sperre über die eigene Transaktion hinaus |
| Minütliche Ticks | `DirectorySyncScheduler`, `LibraryIndexingScheduler` (`lastTickAt`) | Doppelstart durch Datenbank verhindert; Rückschaufenster je Prozess |
| Recovery beim Start und per Heartbeat | `IndexingJobRecoveryScheduler` (`recoverOnStartup`, `recoverStaleRunningJobs`), `UploadPendingRecoveryRunner` (+ `sweepTempDirectory`), `EmbeddingReindexService` (setzt `RUNNING` fort) | Ein Start failt die laufenden Jobs der anderen Instanz, räumt fremde Arbeitsdateien weg, bettet doppelt ein |
| Sondierung auf eigenem Scheduler | `RerankModelRole` (Abstand `opaa.rerank.probe-interval`), `TranscriptionModelRole` (`lastKnown`, `degradedCalls`) | N-fache Sondierungslast, Ergebnis je Prozess |
| Task-Executor-Warteschlangen | `IndexingConfiguration` (`indexingTaskExecutor`, `embeddingTaskExecutor`, `modelExtractionTaskExecutor`, `uploadTaskExecutor`), `ChatConfiguration` (`chatTitleTaskExecutor`, `chatNoteTaskExecutor`), `GrantRevocations` | Eine `RUNNING`-Zeile heißt „läuft auf mir“; wartende Widerrufe gehen mit dem Prozess verloren |
| Entprellung im Prozess | `SourceEventIntake.pending` auf `sourceEventScheduler` | Mehrere Entprellfenster; ein verworfener Stapel geht gegen einen fremden Lauf verloren |
| Parallelitätsbudget als Semaphore oder Pool | `AttachmentExtractionLimiter`, `TranscriptionModelRole.slots`, `SearchStatusService.probeExecutor`, `FileSync.downloadPool`, `LlmModelDiscoverer` | Budget gilt N-fach (`TranscriptionClient` und `RerankClient` nutzen unbegrenzte virtuelle Threads, kein Budget) |
| Wartungsläufe ohne Sperre | `MetadataBackfillService`, `ContextPrefixRerunService`, `PipelineReindexService`, `LibraryMetadataSchemaChangeService` (`lastSkippedByLibrary`) | Zwei gleichzeitige Läufe kosten doppelte Modellaufrufe; Werte je Prozess |
| Schätzwerte und Metriken | `EmbeddingRateEstimator`, Micrometer-Zähler und -Gauges | Je Prozess; eine Oberfläche zeigt einen Teilwert |
| Knotenlokale Dateiablage | `FilesystemUploadedOriginalStore` (`opaa.upload.storage-path`), `FilesystemPathAllowlist` (Betreiber-Mount) | Upload auf A ist auf B nicht lesbar; der S3-Adapter (ADR-0030) wäre geteilt |
