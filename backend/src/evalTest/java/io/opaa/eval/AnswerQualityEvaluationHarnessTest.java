package io.opaa.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatCreation;
import io.opaa.chat.ChatService;
import io.opaa.indexing.job.IndexingJob;
import io.opaa.indexing.job.IndexingJobRepository;
import io.opaa.indexing.job.JobStatus;
import io.opaa.indexing.trigger.DocumentIndexingService;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.metadata.MetadataFilter;
import io.opaa.organization.Organization;
import io.opaa.query.QueryService;
import io.opaa.query.answer.DocumentBlock;
import io.opaa.retrieval.KnowledgeRetrieval;
import io.opaa.retrieval.context.NeighborChunkExpansion;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
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
 * Answer-quality domain (eval/corpus/answer-quality/SOURCE.md): asks every case through the
 * production retrieval and measures what reaches the prompt context - the chunks the answer prompt
 * is built from, widened by the neighbour expansion - and, unless {@code
 * -Dopaa.eval.answerQuality.skipAnswers=true}, asks the configured chat model through {@link
 * QueryService} and judges the answers by pattern.
 *
 * <p>The whole corpus is one library of one space, as in an installation whose documents share a
 * scope. The run measures and reports; it fails only on a broken precondition (manifest, indexing),
 * never on a rate. A declared context state that deviates is named in the report, not failed. Run
 * with {@code ./gradlew evaluateAnswerQuality}; needs Docker or {@code -Dopaa.eval.ollamaBaseUrl}.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers
class AnswerQualityEvaluationHarnessTest {

  private static final Logger log =
      LoggerFactory.getLogger(AnswerQualityEvaluationHarnessTest.class);

  private static final String EMBEDDING_MODEL = "nomic-embed-text:v1.5";
  private static final int EMBEDDING_DIMENSIONS = 768;
  // Same pin as the retrieval harnesses, see VerwaltungRetrievalEvaluationHarnessTest.
  private static final String EXPECTED_EMBEDDING_MODEL_DIGEST =
      "0a109f422b47e3a30ba2b10eca18548e944e8a23073ee3f3e947efcf3c45e59f";
  private static final String ALLOW_GPU_PROPERTY = "opaa.eval.allowGpu";
  private static final String OLLAMA_MODEL_VOLUME = "opaa-eval-ollama-models";
  private static final String OLLAMA_MODEL_VOLUME_PATH = "/root/.ollama";
  private static final String DOMAIN = "answer-quality";
  private static final String GOLDEN_FILE = "answer-quality.json";
  private static final String SKIP_ANSWERS_PROPERTY = "opaa.eval.answerQuality.skipAnswers";

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
    if (!skipAnswers() && EvalChatModel.external().isEmpty()) {
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
    // A context-only run has no chat model, so the sub-question decomposition cannot run.
    if (skipAnswers()) {
      registry.add("opaa.query.query-decomposition-enabled", () -> "false");
    }
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
  @Autowired private KnowledgeRetrieval knowledgeRetrieval;
  @Autowired private NeighborChunkExpansion neighborChunkExpansion;

  private static boolean skipAnswers() {
    return Boolean.getBoolean(SKIP_ANSWERS_PROPERTY);
  }

  @Test
  void measuresTheContextAndTheAnswersOfTheCases() throws Exception {
    Instant runStart = Instant.now();
    boolean skip = skipAnswers();
    if (!skip) {
      EvalChatModel.installAsSystemwideActiveModel(
          jdbcTemplate, ollamaEndpoint(), settingsEncryptor);
      EvalChatModel.requireUsable(activeChatModelResolver, log);
    }

    Path corpusDir = RepoPaths.evalDir().resolve("corpus").resolve(DOMAIN);
    Path manifestFile = corpusDir.resolve("MANIFEST.sha256");
    CorpusManifest.VerificationResult manifest = CorpusManifest.verify(corpusDir, manifestFile);
    assertThat(manifest.violations()).as("corpus must match MANIFEST.sha256").isEmpty();
    List<AnswerQualityCase> cases =
        AnswerQualityCase.load(RepoPaths.evalDir().resolve("golden").resolve(GOLDEN_FILE)).cases();

    CurrentUser caller = createCaller();
    UUID libraryId = indexLibrary(caller, "corpus", manifest.fileNames(), corpusDir);
    UUID spaceId = createSpace(caller, List.of(libraryId));
    Set<UUID> scope = Set.of(libraryId);

    List<AnswerQualityReport.ContextResult> context = new ArrayList<>();
    for (AnswerQualityCase c : cases) {
      List<String> questions = c.allQuestions();
      for (int i = 0; i < questions.size(); i++) {
        context.add(measureContext(c, questions.get(i), i > 0, scope));
      }
    }

    List<AnswerQualityAnswers.Result> answers = List.of();
    if (!skip) {
      answers =
          AnswerQualityAnswers.run(
              cases,
              c -> {
                UUID chatId =
                    chatService.createChat(spaceId, caller.id(), new ChatCreation()).getId();
                String answer =
                    queryService.query(c.question(), chatId, caller, true, List.of()).answer();
                log.info(
                    "{} ({}): {}", c.id(), c.klass(), answer == null ? "no answer" : "answered");
                return answer;
              });
    }

    AnswerQualityReport report =
        new AnswerQualityReport(
            new AnswerQualityReport.RunConfiguration(
                skip ? null : EvalChatModel.activeModelIdentifier(),
                skip
                    ? null
                    : EvalChatModel.endpointWithoutSecrets(
                        EvalChatModel.external()
                            .map(EvalChatModel.External::baseUrl)
                            .orElse(EvalOllamaEndpoint.isExternal() ? ollamaEndpoint() : null)),
                skip ? "–" : EvalChatModel.activeTemperature().toPlainString(),
                EMBEDDING_MODEL,
                runStart.toString(),
                Duration.between(runStart, Instant.now()).toMillis() / 1000.0,
                CorpusManifest.sha256Hex(manifestFile),
                cases.size(),
                skip),
            List.copyOf(context),
            AnswerQualityAnswers.summarize(answers),
            answers.stream().map(AnswerQualityReport::answerResult).toList(),
            AnswerQualityReport.contextDeviations(cases, context));
    Path json = EvalReportDirectory.resolve("answer-quality.json");
    Path markdown = EvalReportDirectory.resolve("answer-quality.md");
    report.writeJson(json);
    report.writeMarkdown(markdown);
    log.info(
        "Answer quality: {} of {} questions brought document and evidence into the context."
            + " Report: {}",
        context.stream().filter(AnswerQualityReport.ContextResult::reached).count(),
        context.size(),
        markdown.toAbsolutePath());
  }

  /**
   * The prompt context of one question: the pipeline's selection widened by the neighbour
   * expansion, each chunk rendered as the answer prompt renders it.
   */
  private AnswerQualityReport.ContextResult measureContext(
      AnswerQualityCase c, String question, boolean variant, Set<UUID> scope) {
    List<Document> selection =
        knowledgeRetrieval
            .retrieve(question, List.of(), List.of(), scope, MetadataFilter.NONE)
            .chunks();
    List<Document> inContext = neighborChunkExpansion.expand(selection, scope);
    String blockId = DocumentBlock.newId();
    List<String> passages =
        inContext.stream().map(chunk -> DocumentBlock.render(blockId, List.of(chunk))).toList();
    List<String> files =
        inContext.stream()
            .map(chunk -> chunk.getMetadata().get("file_name"))
            .filter(Objects::nonNull)
            .map(Object::toString)
            .distinct()
            .toList();
    return new AnswerQualityReport.ContextResult(
        c.id(),
        c.klass(),
        question,
        variant,
        files.containsAll(c.documents()),
        AnswerQualityCase.evidenceInOnePassage(passages, c.evidence()),
        files);
  }

  private CurrentUser createCaller() {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'eval-issuer', ?, 'Eval Harness User', now(), ?,"
            + " ?)",
        userId,
        "eval-answer-quality-" + userId,
        "eval-answer-quality-" + userId + "@example.com",
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
                "Antwortqualität " + name,
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

  private UUID createSpace(CurrentUser member, List<UUID> libraryIds) {
    UUID spaceId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO spaces (id, name, is_default, owner_id, organization_id, created_at,"
            + " updated_at) VALUES (?, ?, false, ?, ?, now(), now())",
        spaceId,
        "Antwortqualität",
        member.id(),
        member.organizationId());
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
