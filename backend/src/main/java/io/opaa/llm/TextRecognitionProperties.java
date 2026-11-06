package io.opaa.llm;

import static io.opaa.common.PropertyDefaults.positiveOr;

import io.opaa.compliance.DataImpact;
import io.opaa.compliance.NoDataImpact;
import io.opaa.compliance.PerInstance;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The operation's switch and limits of text recognition (OCR) of scanned PDFs, {@code opaa.ocr.*}.
 * Connection type, address, model and key are the administration's ({@link
 * TextRecognitionEndpoint}). Off by default, unlike a feature flag (ADR-0044): switched off, the
 * role is neither evaluated nor contacted, and what is stored stays.
 *
 * @param enabled whether this installation may send scanned PDFs to a text recognition service at
 *     all; the role in the model administration must be switched on as well
 * @param maxPagesPerDocument the largest scan sent; a longer one is not attempted
 * @param documentTimeout budget of all calls for one document
 * @param maxConcurrent documents recognised at the same time by this instance
 * @param maxPagesPerRun pages one indexing run sends at most; the rest waits for the next run
 * @param callTimeout budget of one call to the service for a document; the connection test has its
 *     own budget of three minutes in total, the periodic probe 30 seconds
 */
@PerInstance("limits and timeouts of this process")
@NoDataImpact("limits and timeouts of text recognition")
@ConfigurationProperties(prefix = "opaa.ocr")
public record TextRecognitionProperties(
    @DefaultValue("false") @DataImpact boolean enabled,
    @DefaultValue("" + DEFAULT_MAX_PAGES_PER_DOCUMENT) int maxPagesPerDocument,
    @DefaultValue(DEFAULT_DOCUMENT_TIMEOUT_MINUTES + "m") @NoDataImpact("a timeout of one document")
        Duration documentTimeout,
    @DefaultValue("" + DEFAULT_MAX_CONCURRENT) int maxConcurrent,
    @DefaultValue("" + DEFAULT_MAX_PAGES_PER_RUN) int maxPagesPerRun,
    @DefaultValue(DEFAULT_CALL_TIMEOUT_MINUTES + "m") @NoDataImpact("a timeout of one request")
        Duration callTimeout) {

  static final int DEFAULT_MAX_PAGES_PER_DOCUMENT = 100;
  static final int DEFAULT_DOCUMENT_TIMEOUT_MINUTES = 45;
  static final int DEFAULT_MAX_CONCURRENT = 1;
  static final int DEFAULT_MAX_PAGES_PER_RUN = 500;
  static final int DEFAULT_CALL_TIMEOUT_MINUTES = 45;

  public TextRecognitionProperties {
    maxPagesPerDocument = positiveOr(maxPagesPerDocument, DEFAULT_MAX_PAGES_PER_DOCUMENT);
    documentTimeout =
        positiveOr(documentTimeout, Duration.ofMinutes(DEFAULT_DOCUMENT_TIMEOUT_MINUTES));
    maxConcurrent = positiveOr(maxConcurrent, DEFAULT_MAX_CONCURRENT);
    maxPagesPerRun = positiveOr(maxPagesPerRun, DEFAULT_MAX_PAGES_PER_RUN);
    callTimeout = positiveOr(callTimeout, Duration.ofMinutes(DEFAULT_CALL_TIMEOUT_MINUTES));
  }

  /** Text recognition switched off by the operation, with the default limits. */
  public static TextRecognitionProperties disabled() {
    return new TextRecognitionProperties(false, 0, null, 0, 0, null);
  }
}
