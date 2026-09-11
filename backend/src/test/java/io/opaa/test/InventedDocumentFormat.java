package io.opaa.test;

import io.opaa.indexing.format.DocumentFormat;
import io.opaa.indexing.format.DocumentFormatResult;
import io.opaa.indexing.format.DocumentFormatSource;
import io.opaa.indexing.format.FormatAdmission;
import java.util.List;
import java.util.Set;
import org.springframework.ai.document.Document;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * A format for an entirely made-up file type, declaring its own admission and nothing else - the
 * fixture behind {@code NewFormatByBeanRegistrationIntegrationTest}'s open-closed proof: a format
 * this application knows nothing about must be admitted and routed purely because it is a bean.
 *
 * <p>Registered only for {@link OpaaMockedDocumentServiceIntegrationTest}. It must stay out of the
 * canonical context: {@code DocumentFormatRegistryRoutingIntegrationTest} pins the wired format set
 * exactly against {@link ProductionDocumentFormats}, and an extra bean there would be a false
 * positive for "a format was added to the application and forgotten in the helper".
 */
public final class InventedDocumentFormat implements DocumentFormat {

  public static final String ID = "invented";
  public static final String EXTENSION = ".opaa";
  public static final String MEDIA_TYPE = "application/x-opaa-invented";

  /** The one and only change a new format costs: a class and this bean. */
  @TestConfiguration(proxyBeanMethods = false)
  public static class Registration {

    @Bean
    InventedDocumentFormat inventedFormat() {
      return new InventedDocumentFormat();
    }
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public short version() {
    return 1;
  }

  @Override
  public Set<FormatAdmission> admittedFormats() {
    return Set.of(FormatAdmission.detectedAs(EXTENSION, MEDIA_TYPE));
  }

  @Override
  public DocumentFormatResult run(DocumentFormatSource source) {
    return DocumentFormatResult.chunked(List.of(new Document("erfundenes Format")));
  }
}
