package io.opaa.llm;

import io.opaa.api.types.TextRecognitionConnectionType;
import io.opaa.instance.ScheduledRunLocks;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The text recognition (OCR) model role, built like {@link TranscriptionModelRole}: the one entry
 * point for "may a scanned PDF be read, by which service?" and "what state is the role in?".
 *
 * <p>Two levels must agree: the operation's switch {@link TextRecognitionProperties#enabled()} (off
 * by default) and the role's own switch in the model administration ({@link ModelRoleEndpoints}),
 * read on every call. While the operation's switch is off the role is neither evaluated nor
 * contacted - no probe, no call, whatever is stored.
 *
 * <p>The indexing asks {@link #active()} for the endpoint in force and reads documents through
 * {@link #convertWithDocling} or {@link #recognizePage}; the limits are {@link #limits()}. The last
 * known state is the installation's ({@link ModelRoleObservations}, ADR-0053, Entscheidung 12).
 */
@Service
public class TextRecognitionModelRole extends OptionalModelRole<TextRecognitionEndpoint>
    implements TextRecognitionRoleStatusProvider {

  private static final Logger log = LoggerFactory.getLogger(TextRecognitionModelRole.class);

  private static final long PROBE_INTERVAL_MILLIS = 60_000;

  /** The probe asks for health or the model list only; it never waits for a conversion. */
  static final Duration PROBE_TIMEOUT = Duration.ofSeconds(30);

  private final TextRecognitionProperties properties;
  private final TextRecognitionClient client;

  /** A role whose state lives in this process only - for a context without a database. */
  public TextRecognitionModelRole(
      TextRecognitionProperties properties,
      Supplier<TextRecognitionEndpoint> endpoints,
      TextRecognitionClient client) {
    this(properties, endpoints, client, new ProcessLocalModelRoleObservations(Clock.systemUTC()));
  }

  @Autowired
  public TextRecognitionModelRole(
      TextRecognitionProperties properties,
      ModelRoleEndpoints endpoints,
      TextRecognitionClient client,
      ModelRoleObservations observations) {
    this(properties, endpoints::textRecognition, client, observations);
  }

  public TextRecognitionModelRole(
      TextRecognitionProperties properties,
      Supplier<TextRecognitionEndpoint> endpoints,
      TextRecognitionClient client,
      ModelRoleObservations observations) {
    super(ModelRoleObservations.TEXT_RECOGNITION, "text recognition", endpoints, observations);
    this.properties = properties;
    this.client = client;
  }

  /**
   * The endpoint in force for the indexing.
   *
   * @param endpoint connection type, address, model and key; never logged as a whole
   * @param configurationId names the recipient of recognised text without the key ({@link
   *     TextRecognitionEndpoint#configurationId()}); changes with connection type, address or model
   */
  public record ActiveTextRecognition(TextRecognitionEndpoint endpoint, String configurationId) {

    public TextRecognitionConnectionType connectionType() {
      return endpoint.connectionType();
    }

    @Override
    public String toString() {
      return "ActiveTextRecognition[" + endpoint + ", configurationId=" + configurationId + "]";
    }
  }

  /** Whether the operation allows text recognition at all ({@code opaa.ocr.enabled}). */
  public boolean operationEnabled() {
    return properties.enabled();
  }

  @Override
  protected boolean operationAllows() {
    return properties.enabled();
  }

  /** The operation's limits; the indexing evaluates them. */
  public TextRecognitionProperties limits() {
    return properties;
  }

  /**
   * The endpoint documents go to, present only while both switches are on and an address (and for a
   * vision model, a model) without credentials is stored. Reachability is not part of it: a call to
   * an unreachable service fails as {@link
   * TextRecognitionClient.TextRecognitionUnavailableException}. Never probes.
   */
  public Optional<ActiveTextRecognition> active() {
    TextRecognitionEndpoint endpoint = endpoint();
    if (!callable(endpoint)) {
      return Optional.empty();
    }
    return Optional.of(new ActiveTextRecognition(endpoint, endpoint.configurationId()));
  }

  /**
   * Converts a whole PDF with docling-serve within {@link TextRecognitionProperties#callTimeout()}.
   * A call the role cannot make as configured (no answer, key refused, wrong address, server error)
   * moves the role to {@link OptionalRoleState#UNREACHABLE} with the reason; overload and a
   * document refusal leave the role as it is.
   *
   * @throws IllegalStateException for another connection type or while the operation's switch is
   *     off; nothing was sent
   */
  public TextRecognitionClient.DoclingConversion convertWithDocling(
      ActiveTextRecognition active, byte[] pdf) {
    return convertWithDocling(active, pdf, properties.callTimeout());
  }

  /**
   * As {@link #convertWithDocling(ActiveTextRecognition, byte[])}, within {@code timeout} where it
   * is shorter than the call timeout - what is left of the document's deadline.
   */
  public TextRecognitionClient.DoclingConversion convertWithDocling(
      ActiveTextRecognition active, byte[] pdf, Duration timeout) {
    requireCallable(active, TextRecognitionConnectionType.DOCLING_SERVE);
    Duration bound = shorter(timeout);
    return observed(
        active.endpoint(), () -> client.convertWithDocling(active.endpoint(), bound, pdf));
  }

  /**
   * Reads one page image with the vision model, as {@link #convertWithDocling}.
   *
   * @return the page as Markdown; empty for a page without text
   */
  public String recognizePage(ActiveTextRecognition active, byte[] pageImage, String mediaType) {
    return recognizePage(active, pageImage, mediaType, properties.callTimeout());
  }

  /** As {@link #recognizePage(ActiveTextRecognition, byte[], String)}, within {@code timeout}. */
  public String recognizePage(
      ActiveTextRecognition active, byte[] pageImage, String mediaType, Duration timeout) {
    requireCallable(active, TextRecognitionConnectionType.OPENAI_VISION);
    Duration bound = shorter(timeout);
    return observed(
        active.endpoint(),
        () -> client.recognizePageWithVision(active.endpoint(), bound, pageImage, mediaType));
  }

  /** The shorter of {@code timeout} and the call timeout, at least one millisecond. */
  private Duration shorter(Duration timeout) {
    Duration bound =
        timeout.compareTo(properties.callTimeout()) < 0 ? timeout : properties.callTimeout();
    return bound.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : bound;
  }

  @Scheduled(
      fixedDelay = PROBE_INTERVAL_MILLIS,
      initialDelay = PROBE_INTERVAL_MILLIS,
      scheduler = "textRecognitionProbeScheduler")
  @SchedulerLock(
      name = "text-recognition-role-probe",
      lockAtLeastFor = "PT50S",
      lockAtMostFor = ScheduledRunLocks.AT_MOST_FOR_SHORT)
  public void probePeriodically() {
    refresh();
  }

  @Override
  protected ProbeFailure probe(TextRecognitionEndpoint endpoint) {
    TextRecognitionClient.ProbeFailure failure = client.probe(endpoint, PROBE_TIMEOUT);
    if (failure == null) {
      return null;
    }
    return new ProbeFailure(
        failure.keyRejected()
            ? "the service refused the key (" + failure.message() + ")"
            : failure.message(),
        failure.timedOut());
  }

  private <T> T observed(TextRecognitionEndpoint endpoint, Supplier<T> call) {
    try {
      T result = call.get();
      record(endpoint, ready(endpoint), false);
      return result;
    } catch (TextRecognitionClient.TextRecognitionUnavailableException e) {
      if (e.roleUnusable()) {
        record(endpoint, unreachable(endpoint, e.getMessage(), e.timedOut()), false);
      }
      log.warn(
          "Text recognition call against {} failed: {}",
          endpoint.describeWithoutSecrets(),
          e.getMessage());
      throw e;
    }
  }

  private void requireCallable(
      ActiveTextRecognition active, TextRecognitionConnectionType connectionType) {
    if (!properties.enabled()) {
      throw new IllegalStateException("text recognition is switched off by the operation");
    }
    if (active.connectionType() != connectionType) {
      throw new IllegalStateException(
          "the text recognition role is connected as " + active.connectionType());
    }
  }
}
