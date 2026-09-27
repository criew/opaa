package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.DocumentContent;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.SourceType;
import io.opaa.test.SourceTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The startup guards of {@link SourceConnectorRegistry} - an overlap or a contradiction in the
 * connector beans fails the application instead of a request, a type no bean serves is merely
 * unknown - and how it hands out a type's optional abilities.
 */
class SourceConnectorRegistryTest {

  private static final PushIntake INTAKE = new PushIntake("pushSecret");

  @Test
  void aSetResolvesEveryTypeItServesAndItsAbilities() {
    SourceConnectorRegistry registry = new SourceConnectorRegistry(complete());

    assertThat(registry.descriptors())
        .extracting(SourceConnectorDescriptor::type)
        .containsExactly(
            SourceTypes.CONFLUENCE,
            SourceTypes.FILESYSTEM,
            SourceTypes.HTTP_DIRECTORY,
            SourceTypes.RSS_FEED,
            SourceTypes.S3,
            SourceType.UPLOAD);
    assertThat(registry.pushIntakeHandler(SourceTypes.CONFLUENCE))
        .containsSame((PushIntakeHandler) registry.connector(SourceTypes.CONFLUENCE));
    assertThat(registry.browser(SourceTypes.S3))
        .containsSame((SourceBrowser) registry.connector(SourceTypes.S3));
    assertThat(registry.pushIntakeHandler(SourceTypes.RSS_FEED)).isEmpty();
    assertThat(registry.browser(SourceTypes.RSS_FEED)).isEmpty();
    assertThat(registry.pushIntakeHandlers())
        .containsExactly(
            (PushIntakeHandler) registry.connector(SourceTypes.CONFLUENCE),
            (PushIntakeHandler) registry.connector(SourceTypes.S3));
  }

  @Test
  void aTypeNoConnectorServesIsUnknownNotAStartupFailure() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type().equals(SourceTypes.RSS_FEED));
    SourceConnectorRegistry registry = new SourceConnectorRegistry(connectors);

    assertThat(registry.find(SourceTypes.RSS_FEED)).isEmpty();
    assertThat(registry.pushIntakeHandler(SourceTypes.RSS_FEED)).isEmpty();
    assertThatThrownBy(() -> registry.connector(SourceTypes.RSS_FEED))
        .isInstanceOf(ValidationException.class)
        .hasMessage("sourceType RSS_FEED ist unbekannt");
  }

  @Test
  void aSecondConnectorForTheSameTypeFailsStartup() {
    List<SourceConnector> connectors = complete();
    connectors.add(plain(SourceConnectorDescriptor.localRun(SourceTypes.FILESYSTEM, "Zweites")));

    assertThatThrownBy(() -> new SourceConnectorRegistry(connectors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("source type FILESYSTEM");
  }

  @Test
  void anUploadWithARunOrNoUploadAtAllFailsStartup() {
    List<SourceConnector> withRun = complete();
    withRun.removeIf(c -> c.descriptor().type().equals(SourceType.UPLOAD));
    List<SourceConnector> without = new ArrayList<>(withRun);
    withRun.add(plain(SourceConnectorDescriptor.localRun(SourceType.UPLOAD, "Upload")));

    assertThatThrownBy(() -> new SourceConnectorRegistry(withRun))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UPLOAD");
    assertThatThrownBy(() -> new SourceConnectorRegistry(without))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UPLOAD");
  }

  @Test
  void onlyUploadAcceptsUploads() {
    List<SourceConnector> connectors = complete();
    connectors.add(
        plain(SourceConnectorDescriptor.acceptingUploads(SourceType.of("ABLAGE"), "Ablage")));

    assertThatThrownBy(() -> new SourceConnectorRegistry(connectors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ABLAGE must accept uploads exactly when it serves UPLOAD");
    assertThatThrownBy(
            () ->
                new SourceConnectorDescriptor(
                    SourceType.UPLOAD, "Upload", true, false, false, true, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aPushIntakeWithoutHandlerOrAHandlerWithoutPushIntakeFailsStartup() {
    List<SourceConnector> withoutHandler = complete();
    withoutHandler.removeIf(c -> c.descriptor().type().equals(SourceTypes.RSS_FEED));
    withoutHandler.add(
        plain(
            SourceConnectorDescriptor.remoteRun(SourceTypes.RSS_FEED, "RSS")
                .withPushIntake(INTAKE)));
    List<SourceConnector> withoutIntake = complete();
    withoutIntake.removeIf(c -> c.descriptor().type().equals(SourceTypes.RSS_FEED));
    withoutIntake.add(
        new PushStub(SourceConnectorDescriptor.remoteRun(SourceTypes.RSS_FEED, "RSS")));

    assertThatThrownBy(() -> new SourceConnectorRegistry(withoutHandler))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RSS_FEED must name a push intake exactly when it handles one");
    assertThatThrownBy(() -> new SourceConnectorRegistry(withoutIntake))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RSS_FEED must name a push intake exactly when it handles one");
  }

  @Test
  void aDeepLinkNeedsARemoteSource() {
    assertThatThrownBy(
            () ->
                new SourceConnectorDescriptor(
                    SourceTypes.FILESYSTEM, "Dateisystem", true, false, true, false, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theOriginalAccessOfATypeIsResolvedAndAMissingAbilityIsEmpty() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type().equals(SourceTypes.FILESYSTEM));
    connectors.add(
        new OriginalStub(
            SourceConnectorDescriptor.localRun(SourceTypes.FILESYSTEM, "Dateisystem")));
    SourceConnectorRegistry registry = new SourceConnectorRegistry(connectors);

    assertThat(registry.originalAccess(SourceTypes.FILESYSTEM))
        .containsSame((OriginalAccess) registry.connector(SourceTypes.FILESYSTEM));
    assertThat(registry.originalAccess(SourceTypes.CONFLUENCE)).isEmpty();
  }

  @Test
  void aConnectorWithoutSettingsRefusesEveryFieldButAcceptsNone() {
    SourceConnector connector =
        plain(SourceConnectorDescriptor.remoteRun(SourceTypes.HTTP_DIRECTORY, "Webverzeichnis"));

    assertThat(connector.readSettings(ConnectorData.of(Map.of()))).isNull();
    assertThatThrownBy(() -> connector.readSettings(ConnectorData.of(Map.of("spaces", List.of()))))
        .isInstanceOf(ValidationException.class)
        .hasMessage("sourceSettings: das Feld spaces ist nicht vorgesehen");
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
            SourceTypes.HTTP_DIRECTORY,
            null,
            "https://example.org",
            null,
            null,
            false);
    library.updateSourceSettings("{\"root\": \"/intern/pfad\"}");
    SourceConnector connector =
        plain(SourceConnectorDescriptor.remoteRun(SourceTypes.HTTP_DIRECTORY, "Webverzeichnis"));

    assertThat(connector.settingsView(library, false)).isNull();
    assertThat(connector.settingsView(library, true).asMap()).containsEntry("root", "/intern/pfad");
  }

  /** One connector per built-in source type, with the abilities the production ones offer. */
  private List<SourceConnector> complete() {
    List<SourceConnector> connectors = new ArrayList<>();
    connectors.add(plain(SourceConnectorDescriptor.acceptingUploads(SourceType.UPLOAD, "Upload")));
    connectors.add(
        plain(SourceConnectorDescriptor.localRun(SourceTypes.FILESYSTEM, "Dateisystem")));
    connectors.add(
        plain(SourceConnectorDescriptor.remoteRun(SourceTypes.HTTP_DIRECTORY, "Webverzeichnis")));
    connectors.add(plain(SourceConnectorDescriptor.remoteRun(SourceTypes.RSS_FEED, "RSS-Feed")));
    connectors.add(
        new PushBrowsingStub(
            SourceConnectorDescriptor.remoteRun(SourceTypes.CONFLUENCE, "Confluence")
                .withPushIntake(INTAKE)));
    connectors.add(
        new PushBrowsingStub(
            SourceConnectorDescriptor.remoteRun(SourceTypes.S3, "S3")
                .withoutDeepLink()
                .withPushIntake(INTAKE)));
    return connectors;
  }

  private static SourceConnector plain(SourceConnectorDescriptor descriptor) {
    return new Stub(descriptor);
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

    @Override
    public void rejectForeign(byte[] body, UnaryOperator<String> header) {}
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
