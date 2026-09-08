package io.opaa.indexing.format;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIndexingIntegrationTest;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * The open-closed promise of {@link DocumentFormat}, taken literally: a format this application
 * knows nothing about is admitted <b>and</b> routed purely because it is a bean, with no entry in
 * {@link SupportedDocumentFormats}, {@link DocumentFormatRegistry} or any other existing class.
 * Before the admission was derived, the same bean would have been registered and then never
 * reached, because {@code .opaa} was in no admission list.
 */
// Class-local @Import, and therefore a context of its own: the whole point is a DocumentFormat bean
// that must not exist in the application's own context (AGENTS.md, Spring-Testkontexte).
@OpaaIndexingIntegrationTest
@Import(NewFormatByBeanRegistrationIntegrationTest.InventedFormatConfiguration.class)
class NewFormatByBeanRegistrationIntegrationTest {

  private static final String INVENTED_MEDIA_TYPE = "application/x-opaa-invented";

  @Autowired private SupportedDocumentFormats supportedFormats;
  @Autowired private DocumentFormatRegistry registry;

  @TestConfiguration(proxyBeanMethods = false)
  static class InventedFormatConfiguration {

    /** The one and only change a new format costs: a class and this bean. */
    @Bean
    InventedFormat inventedFormat() {
      return new InventedFormat();
    }
  }

  /** A format for an entirely made-up file type, declaring its own admission and nothing else. */
  static final class InventedFormat implements DocumentFormat {

    @Override
    public String id() {
      return "invented";
    }

    @Override
    public short version() {
      return 1;
    }

    @Override
    public Set<FormatAdmission> admittedFormats() {
      return Set.of(FormatAdmission.detectedAs(".opaa", INVENTED_MEDIA_TYPE));
    }

    @Override
    public DocumentFormatResult run(DocumentFormatSource source) {
      return DocumentFormatResult.chunked(List.of(new Document("erfundenes Format")));
    }
  }

  @Test
  void aFormatRegisteredOnlyAsABeanIsAdmittedAndRouted() {
    assertThat(supportedFormats.extensions()).contains(".opaa");
    assertThat(supportedFormats.isSupported("bericht.opaa")).isTrue();
    assertThat(supportedFormats.contentTypeForExtension(".opaa")).isEqualTo(INVENTED_MEDIA_TYPE);

    SupportedDocumentFormats.ContentDecision decision =
        supportedFormats.decideForFileName("bericht.opaa", INVENTED_MEDIA_TYPE);
    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(".opaa");
    assertThat(decision.extensionMismatch()).isFalse();

    assertThat(registry.pipelineFor("bericht.opaa", INVENTED_MEDIA_TYPE).id())
        .isEqualTo("invented");
  }

  @Test
  void theRegisteredFormatsKeepTheirOwnAdmissionAndRouting() {
    // The other half of open-closed: a new format changes nothing for the formats it does not
    // declare.
    assertThat(registry.pipelineFor("satzung.pdf", "application/pdf").id()).isEqualTo("pdf");
    assertThat(registry.pipelineFor("notiz.txt", "text/plain").id()).isEqualTo("tika-fallback");
    assertThat(supportedFormats.extensions())
        .contains(".pdf", ".doc", ".txt", ".md", ".csv", ".eml", ".msg", ".html");
  }
}
