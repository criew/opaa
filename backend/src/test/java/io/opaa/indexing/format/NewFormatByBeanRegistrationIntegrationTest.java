package io.opaa.indexing.format;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.InventedDocumentFormat;
import io.opaa.test.OpaaMockedDocumentServiceIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The open-closed promise of {@link DocumentFormat}, taken literally: a format this application
 * knows nothing about is admitted <b>and</b> routed purely because it is a bean, with no entry in
 * {@link SupportedDocumentFormats}, {@link DocumentFormatRegistry} or any other existing class.
 * Before the admission was derived, the same bean would have been registered and then never
 * reached, because {@code .opaa} was in no admission list.
 *
 * <p>The bean itself is {@link InventedDocumentFormat}, registered by the signature below - not in
 * the canonical context, where {@link DocumentFormatRegistryRoutingIntegrationTest} pins the wired
 * format set exactly.
 */
@OpaaMockedDocumentServiceIntegrationTest
class NewFormatByBeanRegistrationIntegrationTest {

  @Autowired private SupportedDocumentFormats supportedFormats;
  @Autowired private DocumentFormatRegistry registry;

  @Test
  void aFormatRegisteredOnlyAsABeanIsAdmittedAndRouted() {
    assertThat(supportedFormats.extensions()).contains(InventedDocumentFormat.EXTENSION);
    assertThat(supportedFormats.isSupported("bericht.opaa")).isTrue();
    assertThat(supportedFormats.contentTypeForExtension(InventedDocumentFormat.EXTENSION))
        .isEqualTo(InventedDocumentFormat.MEDIA_TYPE);

    SupportedDocumentFormats.ContentDecision decision =
        supportedFormats.decideForFileName("bericht.opaa", InventedDocumentFormat.MEDIA_TYPE);
    assertThat(decision.supported()).isTrue();
    assertThat(decision.detectedExtension()).isEqualTo(InventedDocumentFormat.EXTENSION);
    assertThat(decision.extensionMismatch()).isFalse();

    assertThat(registry.pipelineFor("bericht.opaa", InventedDocumentFormat.MEDIA_TYPE).id())
        .isEqualTo(InventedDocumentFormat.ID);
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
