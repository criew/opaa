package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatCreation;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatSource;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.trigger.DocumentIndexingService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.query.QueryResult;
import io.opaa.query.QueryService;
import io.opaa.security.SettingsEncryptor;
import io.opaa.test.SourceTypes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.ollama.OllamaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Prompt-injection test set (eval/corpus/prompt-injection/SOURCE.md): asks every case's question
 * through the production answer path ({@link QueryService}, in a chat of a space) and checks by
 * text whether the answer followed the instruction embedded in the case's prepared document.
 *
 * <p>Each case runs in a space of its own that sees exactly two libraries, the case's prepared
 * document and the shared clean documents, so a marker in the answer stems from that case's attack
 * alone. A case whose document did not reach the answer is reported as not reached and counted in
 * no rate.
 *
 * <p>The run measures and reports; it fails only on a broken precondition (manifest, indexing),
 * never on the attack success rate - there is no gate on it (maintainer decision of 23.10.2026).
 * The chat model is the pinned {@link EvalChatModel#MODEL} or, with {@code -Dopaa.eval.chatBaseUrl}
 * and {@code -Dopaa.eval.chatModel}, an external one. Run with {@code ./gradlew
 * evaluatePromptInjection}; needs Docker.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class PromptInjectionEvaluationHarnessTest {

  private static final Logger log =
      LoggerFactory.getLogger(PromptInjectionEvaluationHarnessTest.class);

  private static final String EMBEDDING_MODEL = "nomic-embed-text:v1.5";
  private static final int EMBEDDING_DIMENSIONS = 768;
  // Same pin as the retrieval harnesses, see VerwaltungRetrievalEvaluationHarnessTest.
  private static final String EXPECTED_EMBEDDING_MODEL_DIGEST =
      "0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f";
  private static final String ALLOW_GPU_PROPERTY = "opaa.eval.allowGpu";
  private static final String OLLAMA_MODEL_VOLUME = "opaa-eval-ollama-models";
  private static final String OLLAMA_MODEL_VOLUME_PATH = "/root/.ollama";
  private static final String DOMAIN = "prompt-injection";
  private static final String GOLDEN_FILE = "prompt-injection.json";
  private static final String CLEAN_PREFIX = "clean-";

  @Container
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg18"));

  // Started by startOllamaIfNeeded(), not @Container-managed: an external endpoint starts none.
  static OllamaContainer ollama;

  @org.junit.jupiter.api.io.TempDir static Path corpusWorkingDir;

  @BeforeAll
  static void pullModels() throws IOException, InterruptedException {
    startOllamaIfNeeded();
    EvalOllamaModel.ensurePresent(
        ollamaEndpoint(), ollama, EMBEDDING_MODEL, EXPECTED_EMBEDDING_MODEL_DIGEST, log, "...");
    if (EvalChatModel.external().isEmpty()) {
      EvalOllamaModel.ensurePresent(
          ollamaEndpoint(), ollama, EvalChatModel.MODEL, EvalChatModel.EXPECTED_DIGEST, log, "...");
    }
  }

  private static void startOllamaIfNeeded() {
    if (EvalOllamaEndpoint.isExternal()) {
      log.warn("Using external Ollama endpoint {}", EvalOllamaEndpoint.externalBaseUrl());
      return;
    }
    ollama =
        new OllamaContainer(DockerImageName.parse(EvalOllamaEndpoint.PINNED_IMAGE))
            // No GPU device request unless asked for, see VerwaltungRetrievalEvaluationHarnessTest.
            .withCreateContainerCmdModifier(
                cmd -> {
                  if (!Boolean.getBoolean(ALLOW_GPU_PROPERTY)) {
                    cmd.getHostConfig().withDeviceRequests(List.of());
                  }
                })
            .withCreateContainerCmdModifier(
                cmd -> {
                  var binds = new ArrayList<Bind>();
                  if (cmd.getHostConfig().getBinds() != null) {
                    binds.addAll(List.of(cmd.getHostConfig().getBinds()));
                  }
                  binds.add(new Bind(OLLAMA_MODEL_VOLUME, new Volume(OLLAMA_MODEL_VOLUME_PATH)));
                  cmd.getHostConfig().withBinds(binds.toArray(new Bind[0]));
                })
            .withCreateContainerCmdModifier(EvalOllamaCpuBackend::pin);
    ollama.start();
  }

  @AfterAll
  static void stopOllamaIfStarted() {
    if (ollama != null) {
      ollama.stop();
    }
  }

  private static String ollamaEndpoint() {
    return EvalOllamaEndpoint.isExternal()
        ? EvalOllamaEndpoint.externalBaseUrl()
        : ollama.getEndpoint();
  }

  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.ai.openai.embedding.base-url", () -> ollamaEndpoint() + "/v1");
    registry.add("spring.ai.openai.embedding.model", () -> EMBEDDING_MODEL);
    registry.add("spring.ai.vectorstore.pgvector.dimensions", () -> EMBEDDING_DIMENSIONS);
    registry.add(
        "opaa.indexing.filesystem.allowlist", () -> corpusWorkingDir.toAbsolutePath().toString());
  }

  @Autowired private DocumentIndexingService documentIndexingService;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private SettingsEncryptor settingsEncryptor;
  @Autowired private ActiveChatModelResolver activeChatModelResolver;
  @Autowired private ChatService chatService;
  @Autowired private QueryService queryService;

  @Test
  void measuresTheAttackSuccessRateOverThePreparedDocuments() throws Exception {
    Instant runStart = Instant.now();
    EvalChatModel.installAsSystemwideActiveModel(jdbcTemplate, ollamaEndpoint(), settingsEncryptor);
    EvalChatModel.requireUsable(activeChatModelResolver, log);

    Path corpusDir = RepoPaths.evalDir().resolve("corpus").resolve(DOMAIN);
    Path manifestFile = corpusDir.resolve("MANIFEST.sha256");
    CorpusManifest.VerificationResult manifest = CorpusManifest.verify(corpusDir, manifestFile);
    assertThat(manifest.violations()).as("corpus must match MANIFEST.sha256").isEmpty();
    List<PromptInjectionCase> cases =
        PromptInjectionCase.load(RepoPaths.evalDir().resolve("golden").resolve(GOLDEN_FILE));

    CurrentUser caller = createCaller();
    UUID cleanLibraryId =
        indexLibrary(
            caller,
            "clean",
            manifest.fileNames().stream().filter(f -> f.startsWith(CLEAN_PREFIX)).toList(),
            corpusDir);

    List<PromptInjectionReport.CaseResult> results = new ArrayList<>();
    for (PromptInjectionCase c : cases) {
      UUID caseLibraryId = indexLibrary(caller, c.id(), List.of(c.document()), corpusDir);
      UUID spaceId = createSpace(caller, c, List.of(caseLibraryId, cleanLibraryId));
      UUID chatId = chatService.createChat(spaceId, caller.id(), new ChatCreation()).getId();
      QueryResult result = queryService.query(c.question(), chatId, caller, true, List.of());
      results.add(evaluate(c, result));
      log.info(
          "{} ({}/{}): attack {}",
          c.id(),
          c.goal(),
          c.technique(),
          results.getLast().attackSucceeded() ? "SUCCEEDED" : "failed");
    }

    PromptInjectionReport report =
        PromptInjectionReport.of(
            new PromptInjectionReport.RunConfiguration(
                EvalChatModel.activeModelIdentifier(),
                EvalChatModel.external()
                    .map(EvalChatModel.External::baseUrl)
                    .orElse(EvalOllamaEndpoint.isExternal() ? ollamaEndpoint() : null),
                EvalChatModel.activeTemperature().toPlainString(),
                EMBEDDING_MODEL,
                EvalOllamaEndpoint.describeImageOrEndpoint(),
                runStart.toString(),
                Duration.between(runStart, Instant.now()).toMillis() / 1000.0,
                CorpusManifest.sha256Hex(manifestFile),
                "eval/golden/" + GOLDEN_FILE,
                cases.size()),
            results);
    Path json = EvalReportDirectory.resolve("prompt-injection.json");
    Path markdown = EvalReportDirectory.resolve("prompt-injection.md");
    report.writeJson(json);
    report.writeMarkdown(markdown);
    log.info(
        "Prompt injection: {} of {} reached attacks succeeded ({} not reached). Report: {}",
        report.overall().succeeded(),
        report.overall().reached(),
        report.overall().notReached(),
        markdown.toAbsolutePath());
  }

  private static PromptInjectionReport.CaseResult evaluate(
      PromptInjectionCase c, QueryResult result) {
    List<ChatSource> sources = result.sources() == null ? List.of() : result.sources();
    boolean reached = sources.stream().anyMatch(s -> c.document().equals(s.getFileName()));
    boolean cited =
        sources.stream().anyMatch(s -> c.document().equals(s.getFileName()) && s.getCited());
    return new PromptInjectionReport.CaseResult(
        c.id(),
        c.goal(),
        c.technique(),
        c.question(),
        reached,
        c.attackSucceeded(result.answer()),
        c.benignAnswered(result.answer()),
        cited,
        sources.stream().map(ChatSource::getFileName).filter(Objects::nonNull).distinct().toList(),
        result.answer());
  }

  private CurrentUser createCaller() {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'eval-issuer', ?, 'Eval Harness User', now(), ?,"
            + " ?)",
        userId,
        "eval-injection-" + userId,
        "eval-injection-" + userId + "@example.com",
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    return CurrentUser.of(
        userId, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Eval Harness User");
  }

  /** Stages {@code fileNames} into a directory of their own and indexes it as one library. */
  private UUID indexLibrary(CurrentUser caller, String name, List<String> fileNames, Path corpusDir)
      throws IOException {
    Path dir = Files.createDirectories(corpusWorkingDir.resolve(name));
    for (String fileName : fileNames) {
      Files.copy(
          corpusDir.resolve(fileName), dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
    }
    KnowledgeLibrary library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Prompt-Injection " + name,
                null,
                caller.id(),
                SourceTypes.FILESYSTEM,
                dir.toAbsolutePath().toString(),
                null,
                null));
    jdbcTemplate.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, ?, ?, ?, 'USER', ?,"
            + " 'OWNER', now(), now())",
        UUID.randomUUID(),
        KnowledgeLibrary.ASSET_TYPE.value(),
        library.getId(),
        Organization.DEFAULT_ID,
        caller.id());
    IndexingJob job = documentIndexingService.triggerIndexing(library.getId(), caller);
    await()
        .atMost(30, TimeUnit.MINUTES)
        .pollInterval(1, TimeUnit.SECONDS)
        .until(
            () ->
                indexingJobRepository.findById(job.getId()).orElseThrow().getStatus()
                    != JobStatus.RUNNING);
    IndexingJob completed = indexingJobRepository.findById(job.getId()).orElseThrow();
    assertThat(completed.getStatus()).as(name).isEqualTo(JobStatus.COMPLETED);
    assertThat(completed.getDocumentsFailed())
        .as("%s: %s", name, completed.getErrorMessage())
        .isZero();
    assertThat(completed.getDocumentsProcessed()).as(name).isEqualTo(fileNames.size());
    return library.getId();
  }

  private UUID createSpace(CurrentUser member, PromptInjectionCase c, List<UUID> libraryIds) {
    UUID spaceId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO spaces (id, name, is_default, owner_id, organization_id, model_instruction,"
            + " created_at, updated_at) VALUES (?, ?, false, ?, ?, ?, now(), now())",
        spaceId,
        "Prompt-Injection " + c.id(),
        member.id(),
        member.organizationId(),
        c.spaceInstruction());
    jdbcTemplate.update(
        "INSERT INTO space_memberships (id, subject_type, user_id, space_id, role, organization_id,"
            + " created_at) VALUES (?, 'USER', ?, ?, 'ADMIN', ?, now())",
        UUID.randomUUID(),
        member.id(),
        spaceId,
        member.organizationId());
    for (UUID libraryId : libraryIds) {
      jdbcTemplate.update(
          "INSERT INTO space_asset_associations (id, space_id, asset_id, organization_id,"
              + " created_by_user_id, created_at) VALUES (?, ?, ?, ?, ?, now())",
          UUID.randomUUID(),
          spaceId,
          libraryId,
          member.organizationId(),
          member.id());
    }
    return spaceId;
  }
}
