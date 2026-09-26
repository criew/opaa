package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The startup guards of {@link SourceConnectorRegistry} - a gap or an overlap in the connector
 * beans fails the application instead of a request - and how it hands out a type's optional
 * abilities.
 */
class SourceConnectorRegistryTest {

  private static final PushIntake INTAKE = new PushIntake("pushSecret", "Ein Geheimnis");

  @Test
  void aCompleteSetResolvesEveryTypeAndItsAbilities() {
    SourceConnectorRegistry registry = new SourceConnectorRegistry(complete());

    for (DocumentSourceType type : DocumentSourceType.values()) {
      assertThat(registry.descriptor(type).type()).isEqualTo(type);
    }
    assertThat(registry.pushIntakeHandler(DocumentSourceType.CONFLUENCE))
        .isSameAs(registry.connector(DocumentSourceType.CONFLUENCE));
    assertThat(registry.pushIntakeHandler(DocumentSourceType.S3))
        .isSameAs(registry.connector(DocumentSourceType.S3));
    assertThat(registry.browser(DocumentSourceType.CONFLUENCE))
        .isSameAs(registry.connector(DocumentSourceType.CONFLUENCE));
    assertThat(registry.browser(DocumentSourceType.S3))
        .isSameAs(registry.connector(DocumentSourceType.S3));
  }

  @Test
  void aMissingConnectorFailsStartup() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type() == DocumentSourceType.RSS_FEED);

    assertThatThrownBy(() -> new SourceConnectorRegistry(connectors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RSS_FEED");
  }

  @Test
  void aSecondConnectorForTheSameTypeFailsStartup() {
    List<SourceConnector> connectors = complete();
    connectors.add(plain(DocumentSourceType.FILESYSTEM, true));

    assertThatThrownBy(() -> new SourceConnectorRegistry(connectors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source type FILESYSTEM");
  }

  @Test
  void aRunThatDisagreesWithTheSourceTypeFailsStartup() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type() == DocumentSourceType.UPLOAD);
    connectors.add(plain(DocumentSourceType.UPLOAD, true));

    assertThatThrownBy(() -> new SourceConnectorRegistry(connectors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UPLOAD");
  }

  @Test
  void aPushIntakeWithoutHandlerOrAHandlerWithoutPushIntakeFailsStartup() {
    List<SourceConnector> withoutHandler = complete();
    withoutHandler.removeIf(c -> c.descriptor().type() == DocumentSourceType.RSS_FEED);
    withoutHandler.add(
        new Stub(new SourceConnectorDescriptor(DocumentSourceType.RSS_FEED, true, INTAKE, null)));
    List<SourceConnector> withoutIntake = complete();
    withoutIntake.removeIf(c -> c.descriptor().type() == DocumentSourceType.RSS_FEED);
    withoutIntake.add(
        new PushStub(SourceConnectorDescriptor.runBased(DocumentSourceType.RSS_FEED)));

    assertThatThrownBy(() -> new SourceConnectorRegistry(withoutHandler))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RSS_FEED must name a push intake exactly when it handles one");
    assertThatThrownBy(() -> new SourceConnectorRegistry(withoutIntake))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RSS_FEED must name a push intake exactly when it handles one");
  }

  @Test
  void theOriginalAccessOfATypeIsResolvedAndAMissingAbilityIsAWiringError() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type() == DocumentSourceType.FILESYSTEM);
    connectors.add(
        new OriginalStub(SourceConnectorDescriptor.runBased(DocumentSourceType.FILESYSTEM)));
    SourceConnectorRegistry registry = new SourceConnectorRegistry(connectors);

    assertThat(registry.originalAccess(DocumentSourceType.FILESYSTEM))
        .containsSame((OriginalAccess) registry.connector(DocumentSourceType.FILESYSTEM));
    assertThat(registry.originalAccess(DocumentSourceType.CONFLUENCE)).isEmpty();
    assertThatThrownBy(() -> registry.pushIntakeHandler(DocumentSourceType.RSS_FEED))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> registry.browser(DocumentSourceType.RSS_FEED))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theDefaultSettingsViewShowsTheStoredSettingsToAManagerOnly() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Wiki",
            null,
            UUID.randomUUID(),
            false,
            DocumentSourceType.HTTP_DIRECTORY,
            null,
            "https://example.org",
            null,
            null,
            false);
    library.updateSourceSettings("{\"root\": \"/intern/pfad\"}");
    SourceConnector connector = plain(DocumentSourceType.HTTP_DIRECTORY, true);

    assertThat(connector.settingsView(library, false)).isNull();
    assertThat(connector.settingsView(library, true).asMap()).containsEntry("root", "/intern/pfad");
  }

  /** One connector per source type, with the abilities the production connectors offer. */
  private List<SourceConnector> complete() {
    List<SourceConnector> connectors = new ArrayList<>();
    connectors.add(plain(DocumentSourceType.UPLOAD, false));
    connectors.add(plain(DocumentSourceType.FILESYSTEM, true));
    connectors.add(plain(DocumentSourceType.HTTP_DIRECTORY, true));
    connectors.add(plain(DocumentSourceType.RSS_FEED, true));
    connectors.add(
        new PushBrowsingStub(
            new SourceConnectorDescriptor(DocumentSourceType.CONFLUENCE, true, INTAKE, null)));
    connectors.add(
        new PushBrowsingStub(
            new SourceConnectorDescriptor(DocumentSourceType.S3, true, INTAKE, null)));
    return connectors;
  }

  private SourceConnector plain(DocumentSourceType type, boolean indexingRun) {
    return new Stub(new SourceConnectorDescriptor(type, indexingRun, null, null));
  }

  private static class Stub implements SourceConnector {

    private final SourceConnectorDescriptor descriptor;

    Stub(SourceConnectorDescriptor descriptor) {
      this.descriptor = descriptor;
    }

    @Override
    public SourceConnectorDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public SourceSettings validate(SourceSettings requested) {
      return requested;
    }

    @Override
    public SourceConnectionTestResult testConnection(
        SourceSettings settings, ConnectorData stored) {
      throw new UnsupportedOperationException();
    }
  }

  private static class PushStub extends Stub implements PushIntakeHandler {

    PushStub(SourceConnectorDescriptor descriptor) {
      super(descriptor);
    }

    @Override
    public void acceptNotification(UUID libraryId, byte[] body, UnaryOperator<String> header) {}
  }

  private static class OriginalStub extends Stub implements OriginalAccess {

    OriginalStub(SourceConnectorDescriptor descriptor) {
      super(descriptor);
    }

    @Override
    public Optional<DocumentContent> openOriginal(Document document, KnowledgeLibrary library) {
      return Optional.empty();
    }
  }

  private static class PushBrowsingStub extends PushStub implements SourceBrowser {

    PushBrowsingStub(SourceConnectorDescriptor descriptor) {
      super(descriptor);
    }

    @Override
    public String otherTypeMessage() {
      return "andere Bibliothek";
    }

    @Override
    public SourceListing browse(Query query) {
      throw new UnsupportedOperationException();
    }
  }
}
