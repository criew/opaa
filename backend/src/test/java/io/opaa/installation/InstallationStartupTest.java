package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.account.OidcProviderSeedRunner;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.common.ConflictException;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.llm.LlmModelSeedRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import tools.jackson.databind.json.JsonMapper;

/**
 * The start-up decision of the installation (ADR-0060): before the web server starts, the {@code
 * dev} mode and the start values complete it, the answer file is applied, a pending installation
 * prints a generated token exactly once and runs no start-up seed.
 */
class InstallationStartupTest {

  private final InstallationService installation = mock(InstallationService.class);
  private final DatabaseInstallationStatus status = mock(DatabaseInstallationStatus.class);
  private final OidcProviderSeedRunner identitySeeds = mock(OidcProviderSeedRunner.class);
  private final LlmModelSeedRunner chatModelSeed = mock(LlmModelSeedRunner.class);
  private final OidcProviderRegistry registry = mock(OidcProviderRegistry.class);
  private ListAppender<ILoggingEvent> logs;

  @TempDir Path directory;

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(InstallationStartup.class)).addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    ((Logger) LoggerFactory.getLogger(InstallationStartup.class)).detachAppender(logs);
  }

  @Test
  void runsBeforeTheWebServerAndSeedsOnlyACompletedInstallation() {
    when(installation.completeAtStartupIfDue(true, false)).thenReturn(true);
    InstallationStartup startup = startup("dev", "", null, null);

    assertThat(startup).isInstanceOf(SmartInitializingSingleton.class);
    startup.afterSingletonsInstantiated();

    InOrder order = Mockito.inOrder(installation, status, identitySeeds, chatModelSeed);
    order.verify(installation).completeAtStartupIfDue(true, false);
    order.verify(status).reload();
    order.verify(identitySeeds).runStartupSeeds();
    order.verify(chatModelSeed).runStartupSeed();
    verify(installation, never()).ensureToken(any());
  }

  @Test
  void aPendingInstallationPrintsTheGeneratedTokenOnceAndSeedsNothing() {
    when(installation.ensureToken(null)).thenReturn("ErzeugtesTokenAbc123ErzeugtesToken");
    InstallationStartup startup = startup("oidc", "", null, null);

    startup.afterSingletonsInstantiated();

    verify(installation).completeAtStartupIfDue(false, false);
    verify(identitySeeds, never()).runStartupSeeds();
    verify(chatModelSeed, never()).runStartupSeed();
    verify(registry).refresh();
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .filteredOn(message -> message.contains("ErzeugtesTokenAbc123ErzeugtesToken"))
        .hasSize(1);
  }

  @Test
  void aConfiguredTokenIsStoredAndNeverPrinted() {
    String configured = "konfiguriertes-installations-token-0123456789";
    InstallationStartup startup = startup("oidc", "", configured, null);

    startup.afterSingletonsInstantiated();

    verify(installation).ensureToken(configured);
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .noneMatch(message -> message.contains(configured));
  }

  @Test
  void theStartValuesOfTheTransitionCompleteAPendingInstallation() {
    InstallationStartup startup = startup("oidc", "it@stadt.example", null, null);

    startup.decide();

    verify(installation).completeAtStartupIfDue(false, true);
  }

  @Test
  void anAnswerFileCompletesTheInstallationWithItsSecretsFromFiles() throws Exception {
    Path password = Files.writeString(directory.resolve("password"), "Kennwort-aus-Secret-2026\n");
    Path answers =
        Files.writeString(
            directory.resolve("installation.json"),
            "{\"firstAdmin\":{\"email\":\"it@stadt.example\",\"displayName\":\"IT\","
                + "\"passwordFile\":\""
                + password
                + "\"},\"signIn\":{\"mode\":\"LOCAL_ONLY\"},"
                + "\"chatModel\":{\"baseUrl\":\"http://ollama:11434/v1\",\"model\":\"qwen\"},"
                + "\"embeddingModel\":{\"model\":\"bge-m3\"}}");
    InstallationStartup startup = startup("dev", "it@stadt.example", null, answers.toString());

    assertThat(startup.decide()).isTrue();

    verify(installation).completeAtStartupIfDue(false, false);
    verify(installation)
        .applyAnswers(
            eq(
                List.of(
                    new StepInput.FirstAdmin("it@stadt.example", "IT", "Kennwort-aus-Secret-2026"),
                    new StepInput.SignIn(StepInput.SignInMode.LOCAL_ONLY, null),
                    new StepInput.ChatModel("http://ollama:11434/v1", "qwen", "", null, null),
                    new StepInput.EmbeddingModel(null, "bge-m3", "", null))),
            eq(CompletionSource.ANSWER_FILE_STARTUP));
    verify(installation, never()).ensureToken(any());
  }

  @Test
  void aRefusedAnswerFileLeavesTheInstallationPendingWithItsToken() throws Exception {
    Path answers = Files.writeString(directory.resolve("installation.json"), "{}");
    when(installation.applyAnswers(any(), any())).thenThrow(new ConflictException("nie"));
    InstallationStartup startup = startup("oidc", "", null, answers.toString());

    assertThat(startup.decide()).isFalse();

    verify(installation).ensureToken(null);
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(message -> message.contains("firstAdmin, signIn, chatModel und"));
  }

  @Test
  void anAnswerFileWithAPlainPasswordIsRefused() throws Exception {
    Path answers =
        Files.writeString(
            directory.resolve("installation.json"),
            "{\"firstAdmin\":{\"email\":\"it@stadt.example\",\"displayName\":\"IT\","
                + "\"password\":\"im-klartext\"},\"signIn\":{\"mode\":\"LOCAL_ONLY\"},"
                + "\"chatModel\":{\"baseUrl\":\"http://ollama:11434/v1\",\"model\":\"qwen\"},"
                + "\"embeddingModel\":{\"baseUrl\":\"http://ollama:11434/v1\",\"model\":\"bge-m3\"}}");
    InstallationStartup startup = startup("oidc", "", null, answers.toString());

    assertThat(startup.decide()).isFalse();

    verify(installation, never()).applyAnswers(any(), any());
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(message -> message.contains("nicht im Klartext"))
        .noneMatch(message -> message.contains("im-klartext"));
  }

  @Test
  void anAnswerFileIsIgnoredOnceTheInstallationIsCompleted() throws Exception {
    when(installation.completeAtStartupIfDue(anyBoolean(), anyBoolean())).thenReturn(true);
    InstallationStartup startup = startup("oidc", "", null, directory.resolve("x").toString());

    assertThat(startup.decide()).isTrue();

    verify(installation, never()).applyAnswers(any(), any());
  }

  private InstallationStartup startup(
      String mode, String initialAdminEmail, String token, String answerFile) {
    return new InstallationStartup(
        installation,
        new InstallationProperties(token, List.of(), answerFile),
        new AuthProperties(mode, null, null, initialAdminEmail),
        status,
        identitySeeds,
        chatModelSeed,
        registry,
        JsonMapper.builder().build());
  }
}
