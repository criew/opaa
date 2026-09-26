package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ConfluenceEdition;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.ConfluenceSpaceSelection;
import io.opaa.knowledge.KnowledgeLibrary;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The startup guards of {@link SourceConnectorRegistry} - a gap or an overlap in the connector
 * beans fails the application instead of a request - and the order in which it refuses fields a
 * connector does not own.
 */
class SourceConnectorRegistryTest {

  private static final Set<SourceSettingField> CONFLUENCE_FIELDS =
      Set.of(
          SourceSettingField.CONFLUENCE_EDITION,
          SourceSettingField.CONFLUENCE_SPACES,
          SourceSettingField.CONFLUENCE_FULL_SYNC_INTERVAL_DAYS);

  private final List<String> validated = new ArrayList<>();

  @Test
  void aCompleteSetResolvesEveryTypeIntakeAndBrowseKind() {
    SourceConnectorRegistry registry = new SourceConnectorRegistry(complete());

    for (DocumentSourceType type : DocumentSourceType.values()) {
      assertThat(registry.descriptor(type).type()).isEqualTo(type);
    }
    assertThat(registry.ownerOf(PushIntake.WEBHOOK_SECRET))
        .isEqualTo(DocumentSourceType.CONFLUENCE);
    assertThat(registry.ownerOf(PushIntake.EVENT_TOKEN)).isEqualTo(DocumentSourceType.S3);
    assertThat(registry.browser(SourceBrowser.Kind.SPACES).descriptor().type())
        .isEqualTo(DocumentSourceType.CONFLUENCE);
    assertThat(registry.browser(SourceBrowser.Kind.BUCKETS).descriptor().type())
        .isEqualTo(DocumentSourceType.S3);
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
  void aFieldWithTwoOwnersFailsStartup() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type() == DocumentSourceType.RSS_FEED);
    connectors.add(
        new Stub(
            new SourceConnectorDescriptor(
                DocumentSourceType.RSS_FEED,
                true,
                Set.of(SourceSettingField.S3_SETTINGS),
                null,
                null)));

    assertThatThrownBy(() -> new SourceConnectorRegistry(connectors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("setting field S3_SETTINGS");
  }

  @Test
  void aFieldWithoutOwnerFailsStartup() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type() == DocumentSourceType.S3);
    connectors.add(plain(DocumentSourceType.S3, true));

    assertThatThrownBy(() -> new SourceConnectorRegistry(connectors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("S3_SETTINGS");
  }

  @Test
  void aPushIntakeOfferedTwiceFailsStartup() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type() == DocumentSourceType.RSS_FEED);
    connectors.add(
        new PushStub(
            new SourceConnectorDescriptor(
                DocumentSourceType.RSS_FEED, true, Set.of(), PushIntake.EVENT_TOKEN, null)));

    assertThatThrownBy(() -> new SourceConnectorRegistry(connectors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("push intake EVENT_TOKEN");
  }

  @Test
  void aBrowseKindOfferedTwiceFailsStartup() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type() == DocumentSourceType.RSS_FEED);
    connectors.add(
        new BrowsingStub(
            SourceConnectorDescriptor.runBased(DocumentSourceType.RSS_FEED),
            SourceBrowser.Kind.BUCKETS));

    assertThatThrownBy(() -> new SourceConnectorRegistry(connectors))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("browse kind BUCKETS");
  }

  @Test
  void aPushIntakeWithoutHandlerOrAHandlerWithoutPushIntakeFailsStartup() {
    List<SourceConnector> withoutHandler = complete();
    withoutHandler.removeIf(c -> c.descriptor().type() == DocumentSourceType.RSS_FEED);
    withoutHandler.add(
        new Stub(
            new SourceConnectorDescriptor(
                DocumentSourceType.RSS_FEED, true, Set.of(), PushIntake.EVENT_TOKEN, null)));
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
  void theHandlerOfAPushIntakeAndTheOriginalAccessOfATypeAreResolved() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type() == DocumentSourceType.FILESYSTEM);
    connectors.add(
        new OriginalStub(SourceConnectorDescriptor.runBased(DocumentSourceType.FILESYSTEM)));
    SourceConnectorRegistry registry = new SourceConnectorRegistry(connectors);

    assertThat(registry.pushIntakeHandler(PushIntake.EVENT_TOKEN))
        .isSameAs(registry.connector(DocumentSourceType.S3));
    assertThat(registry.originalAccess(DocumentSourceType.FILESYSTEM))
        .containsSame((OriginalAccess) registry.connector(DocumentSourceType.FILESYSTEM));
    assertThat(registry.originalAccess(DocumentSourceType.CONFLUENCE)).isEmpty();
  }

  @Test
  void anIntakeOrBrowseKindNobodyOffersIsAWiringError() {
    List<SourceConnector> connectors = complete();
    connectors.removeIf(c -> c.descriptor().type() == DocumentSourceType.S3);
    connectors.add(
        new Stub(
            new SourceConnectorDescriptor(
                DocumentSourceType.S3, true, Set.of(SourceSettingField.S3_SETTINGS), null, null)));
    SourceConnectorRegistry registry = new SourceConnectorRegistry(connectors);

    assertThatThrownBy(() -> registry.ownerOf(PushIntake.EVENT_TOKEN))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> registry.browser(SourceBrowser.Kind.BUCKETS))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void theFirstForeignFieldInTheGivenOrderIsRefusedNamingItsOwner() {
    SourceConnectorRegistry registry = new SourceConnectorRegistry(complete());
    SourceSettings settings =
        settings(ConfluenceEdition.CLOUD, List.of(new ConfluenceSpaceSelection("ENG", null)), 7);

    assertThatThrownBy(
            () ->
                registry.rejectForeignSettings(
                    DocumentSourceType.HTTP_DIRECTORY,
                    settings,
                    SourceSettingField.CONFLUENCE_SPACES,
                    SourceSettingField.CONFLUENCE_EDITION))
        .isInstanceOf(ValidationException.class)
        .hasMessage("confluenceSpaces sind nur für sourceType CONFLUENCE zulässig");
    // an owned field is never refused
    registry.rejectForeignSettings(
        DocumentSourceType.CONFLUENCE, settings, SourceSettingField.values());
  }

  @Test
  void anOwnedFieldPassesAndEveryRemainingForeignFieldIsCaughtAfterTheConnector() {
    SourceConnectorRegistry registry = new SourceConnectorRegistry(complete());

    assertThatThrownBy(
            () -> registry.validateNew(DocumentSourceType.RSS_FEED, settings(null, null, 7)))
        .isInstanceOf(ValidationException.class)
        .hasMessage("confluenceFullSyncIntervalDays ist nur für sourceType CONFLUENCE zulässig");
    assertThat(validated).containsExactly("RSS_FEED");

    registry.validateNew(DocumentSourceType.CONFLUENCE, settings(ConfluenceEdition.CLOUD, null, 7));
    assertThat(validated).containsExactly("RSS_FEED", "CONFLUENCE");
  }

  @Test
  void aChangeRefusesAForeignEditionBeforeTheConnectorIsAsked() {
    SourceConnectorRegistry registry = new SourceConnectorRegistry(complete());
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Feed",
            null,
            UUID.randomUUID(),
            false,
            DocumentSourceType.RSS_FEED,
            null,
            "https://example.org/feed.xml",
            null,
            null,
            false);

    assertThatThrownBy(
            () ->
                registry.validateChange(
                    library, settings(ConfluenceEdition.CLOUD, null, null), false))
        .isInstanceOf(ValidationException.class)
        .hasMessage("confluenceEdition ist nur für sourceType CONFLUENCE zulässig");
    assertThat(validated).isEmpty();
  }

  private static SourceSettings settings(
      ConfluenceEdition edition, List<ConfluenceSpaceSelection> spaces, Integer intervalDays) {
    return new SourceSettings(null, null, null, null, false, edition, spaces, intervalDays, null);
  }

  /** One connector per source type, with the ownership the production connectors declare. */
  private List<SourceConnector> complete() {
    List<SourceConnector> connectors = new ArrayList<>();
    connectors.add(plain(DocumentSourceType.UPLOAD, false));
    connectors.add(plain(DocumentSourceType.FILESYSTEM, true));
    connectors.add(plain(DocumentSourceType.HTTP_DIRECTORY, true));
    connectors.add(plain(DocumentSourceType.RSS_FEED, true));
    connectors.add(
        new PushBrowsingStub(
            new SourceConnectorDescriptor(
                DocumentSourceType.CONFLUENCE,
                true,
                CONFLUENCE_FIELDS,
                PushIntake.WEBHOOK_SECRET,
                null),
            SourceBrowser.Kind.SPACES));
    connectors.add(
        new PushBrowsingStub(
            new SourceConnectorDescriptor(
                DocumentSourceType.S3,
                true,
                Set.of(SourceSettingField.S3_SETTINGS),
                PushIntake.EVENT_TOKEN,
                null),
            SourceBrowser.Kind.BUCKETS));
    return connectors;
  }

  private SourceConnector plain(DocumentSourceType type, boolean indexingRun) {
    return new Stub(new SourceConnectorDescriptor(type, indexingRun, Set.of(), null, null));
  }

  private class Stub implements SourceConnector {

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
      validated.add(descriptor.type().name());
      return requested;
    }

    @Override
    public SourceConnectionTestResult testConnection(SourceSettings settings) {
      throw new UnsupportedOperationException();
    }
  }

  private class PushStub extends Stub implements PushIntakeHandler {

    PushStub(SourceConnectorDescriptor descriptor) {
      super(descriptor);
    }

    @Override
    public void acceptNotification(
        UUID libraryId, byte[] body, java.util.function.UnaryOperator<String> header) {}
  }

  private class OriginalStub extends Stub implements OriginalAccess {

    OriginalStub(SourceConnectorDescriptor descriptor) {
      super(descriptor);
    }

    @Override
    public java.util.Optional<io.opaa.knowledge.DocumentContent> openOriginal(
        io.opaa.knowledge.Document document, KnowledgeLibrary library) {
      return java.util.Optional.empty();
    }
  }

  private class PushBrowsingStub extends BrowsingStub implements PushIntakeHandler {

    PushBrowsingStub(SourceConnectorDescriptor descriptor, Kind kind) {
      super(descriptor, kind);
    }

    @Override
    public void acceptNotification(
        UUID libraryId, byte[] body, java.util.function.UnaryOperator<String> header) {}
  }

  private class BrowsingStub extends Stub implements SourceBrowser {

    private final Kind kind;

    BrowsingStub(SourceConnectorDescriptor descriptor, Kind kind) {
      super(descriptor);
      this.kind = kind;
    }

    @Override
    public Kind browseKind() {
      return kind;
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
