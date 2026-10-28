package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opaa.account.LocalAdminReset;
import io.opaa.api.types.ColorScheme;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.OidcProviderRegistry;
import io.opaa.common.ConflictException;
import io.opaa.installation.InstallationState.CompletionSource;
import io.opaa.instance.CurrentInstance;
import io.opaa.llm.EmbeddingModelService;
import io.opaa.ratelimit.RateLimitProperties;
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
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.json.JsonMapper;

/**
 * The start-up decision of the installation (ADR-0060): before the web server starts, the {@code
 * dev} mode completes it, the answer file is applied, a pending installation prints a generated
 * token exactly once and runs no emergency reset.
 */
class InstallationStartupTest {

  private final InstallationService installation = mock(InstallationService.class);
  private final DatabaseInstallationStatus status = mock(DatabaseInstallationStatus.class);
  private final LocalAdminReset adminReset = mock(LocalAdminReset.class);
  private final EmbeddingModelService embeddingModels = mock(EmbeddingModelService.class);
  private final OidcProviderRegistry registry = mock(OidcProviderRegistry.class);
  private ListAppender<ILoggingEvent> logs;

  @TempDir Path directory;

  @BeforeEach
  void captureLogs() {
    when(status.isPending()).thenReturn(true);
    when(installation.completeAtStartupIfDue(anyBoolean()))
        .thenReturn(new InstallationService.StartupDecision(false, null));
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(InstallationStartup.class)).addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    ((Logger) LoggerFactory.getLogger(InstallationStartup.class)).detachAppender(logs);
  }

  @Test
  void runsBeforeTheWebServerAndResetsOnlyInACompletedInstallation() {
    when(installation.completeAtStartupIfDue(true))
        .thenReturn(new InstallationService.StartupDecision(true, CompletionSource.DEVELOPMENT));
    InstallationStartup startup = startup("dev", null, null);

    assertThat(startup).isInstanceOf(SmartLifecycle.class);
    assertThat(startup.getPhase()).isEqualTo(CurrentInstance.REGISTERED_PHASE);
    startup.start();

    InOrder order = Mockito.inOrder(installation, status, adminReset, embeddingModels, registry);
    order.verify(installation).completeAtStartupIfDue(true);
    order.verify(status).reload();
    order.verify(adminReset).resetIfForced();
    order.verify(embeddingModels).adoptIfMissing();
    order.verify(registry).refresh();
    verify(installation, never()).ensureToken(any());
  }

  @Test
  void aCompletedInstallationWithoutAnEmbeddingModelIsARegularStateWithoutAnError() {
    when(installation.completeAtStartupIfDue(false))
        .thenReturn(new InstallationService.StartupDecision(true, null));
    when(embeddingModels.missingAfterInstallation()).thenReturn(true);
    InstallationStartup startup = startup("oidc", null, null);

    startup.start();

    assertThat(logs.list)
        .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.ERROR)
        .isEmpty();
    assertThat(logs.list)
        .filteredOn(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains("model administration"));
  }

  @Test
  void aPendingInstallationPrintsTheGeneratedTokenOnceAndResetsNothing() {
    when(installation.ensureToken(null)).thenReturn("ErzeugtesTokenAbc123ErzeugtesToken");
    InstallationStartup startup = startup("oidc", null, null);

    startup.start();

    verify(installation).completeAtStartupIfDue(false);
    verify(adminReset, never()).resetIfForced();
    verify(embeddingModels, never()).adoptIfMissing();
    verify(registry).refresh();
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .filteredOn(message -> message.contains("ErzeugtesTokenAbc123ErzeugtesToken"))
        .hasSize(1);
  }

  @Test
  void aConfiguredTokenIsStoredAndNeverPrinted() {
    String configured = "konfiguriertes-installations-token-0123456789";
    InstallationStartup startup = startup("oidc", configured, null);

    startup.start();

    verify(installation).ensureToken(configured);
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .noneMatch(message -> message.contains(configured));
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
    InstallationStartup startup = startup("dev", null, answers.toString());

    assertThat(startup.decide()).isTrue();

    verify(installation).completeAtStartupIfDue(false);
    verify(installation)
        .applyAnswersAtStartup(
            eq(
                List.of(
                    new StepInput.FirstAdmin(
                        "it@stadt.example", "IT", "Kennwort-aus-Secret-2026", true),
                    new StepInput.SignIn(StepInput.SignInMode.LOCAL_ONLY, null),
                    new StepInput.ChatModel("http://ollama:11434/v1", "qwen", "", null, null),
                    new StepInput.EmbeddingModel(null, "bge-m3", "", null))));
    verify(installation, never()).ensureToken(any());
  }

  @Test
  void anAnswerFileWithBrandingHandsItOnAsTheLastStep() throws Exception {
    Path password = Files.writeString(directory.resolve("password"), "Kennwort-aus-Secret-2026\n");
    Path answers =
        Files.writeString(
            directory.resolve("installation.json"),
            "{\"firstAdmin\":{\"email\":\"it@stadt.example\",\"displayName\":\"IT\","
                + "\"passwordFile\":\""
                + password
                + "\"},\"signIn\":{\"mode\":\"LOCAL_ONLY\"},"
                + "\"branding\":{\"productName\":\"Landesamt-Assistent\","
                + "\"primaryColor\":\"#7A3E9D\",\"defaultColorScheme\":\"DARK\","
                + "\"loginBackgroundFile\":\"/etc/passwd\"}}");
    InstallationStartup startup = startup("oidc", null, answers.toString());

    startup.decide();

    verify(installation)
        .applyAnswersAtStartup(
            argThat(
                inputs ->
                    inputs.size() == 3
                        && inputs
                            .getLast()
                            .equals(
                                new StepInput.Branding(
                                    "Landesamt-Assistent",
                                    null,
                                    null,
                                    "#7A3E9D",
                                    ColorScheme.DARK))));
  }

  @Test
  void anAnswerFileKeepsThePasswordOnlyWhenItSaysSo() throws Exception {
    Path password = Files.writeString(directory.resolve("password"), "Kennwort-aus-Secret-2026\n");
    Path answers =
        Files.writeString(
            directory.resolve("installation.json"),
            "{\"firstAdmin\":{\"email\":\"it@stadt.example\",\"displayName\":\"IT\","
                + "\"requirePasswordChange\":false,\"passwordFile\":\""
                + password
                + "\"},\"signIn\":{\"mode\":\"LOCAL_ONLY\"},"
                + "\"chatModel\":{\"baseUrl\":\"http://ollama:11434/v1\",\"model\":\"qwen\"},"
                + "\"embeddingModel\":{\"model\":\"bge-m3\"}}");
    InstallationStartup startup = startup("oidc", null, answers.toString());

    startup.decide();

    verify(installation)
        .applyAnswersAtStartup(
            argThat(
                inputs ->
                    inputs.getFirst() instanceof StepInput.FirstAdmin admin
                        && !admin.requirePasswordChange()));
  }

  @Test
  void aRefusedAnswerFileLeavesTheInstallationPendingWithItsToken() throws Exception {
    Path answers = Files.writeString(directory.resolve("installation.json"), "{}");
    when(installation.applyAnswersAtStartup(any())).thenThrow(new ConflictException("nie"));
    InstallationStartup startup = startup("oidc", null, answers.toString());

    assertThat(startup.decide()).isFalse();

    verify(installation).ensureToken(null);
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(message -> message.contains("braucht die Angaben firstAdmin und signIn"));
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
    InstallationStartup startup = startup("oidc", null, answers.toString());

    assertThat(startup.decide()).isFalse();

    verify(installation, never()).applyAnswersAtStartup(any());
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(message -> message.contains("nicht im Klartext"))
        .noneMatch(message -> message.contains("im-klartext"));
  }

  @Test
  void anAnswerFileIsIgnoredOnceTheInstallationIsCompleted() throws Exception {
    when(installation.completeAtStartupIfDue(anyBoolean()))
        .thenReturn(new InstallationService.StartupDecision(true, null));
    InstallationStartup startup = startup("oidc", null, directory.resolve("x").toString());

    assertThat(startup.decide()).isTrue();

    verify(installation, never()).applyAnswersAtStartup(any());
  }

  private InstallationStartup startup(String mode, String token, String answerFile) {
    return new InstallationStartup(
        installation,
        new InstallationProperties(token, List.of(), answerFile, null),
        new AuthProperties(mode, null, null),
        status,
        adminReset,
        embeddingModels,
        registry,
        JsonMapper.builder().build(),
        new RateLimitProperties(true, List.of(), null, null, null, null, null, null, null, null));
  }

  @Test
  void theFormerStartValuesNoLongerCompleteAPendingInstallation() {
    InstallationStartup startup = startup("oidc", null, null);

    assertThat(startup.decide()).isFalse();

    verify(installation).completeAtStartupIfDue(false);
    verify(installation).ensureToken(null);
  }

  @Test
  void aNetworkRestrictionWithoutTrustedProxiesIsWarnedAbout() {
    InstallationStartup startup =
        new InstallationStartup(
            installation,
            new InstallationProperties(null, List.of("10.0.0.0/8"), null, null),
            new AuthProperties("oidc", null, null),
            status,
            adminReset,
            embeddingModels,
            registry,
            JsonMapper.builder().build(),
            new RateLimitProperties(
                true, List.of(), null, null, null, null, null, null, null, null));

    startup.start();

    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .anyMatch(message -> message.contains("OPAA_RATE_LIMIT_TRUSTED_PROXY_CIDRS"));
  }

  // regression guard: the losing instance does not report a pending installation another completed
  @Test
  void anInstallationAnotherInstanceCompletedIsNotReportedAsPending() throws Exception {
    Path answers = Files.writeString(directory.resolve("installation.json"), "{}");
    when(status.isPending()).thenReturn(false);
    InstallationStartup startup = startup("oidc", null, answers.toString());

    startup.start();

    verify(adminReset).resetIfForced();
    assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage))
        .noneMatch(
            message -> message.contains("stays pending") || message.contains("installation mode"));
  }
}
