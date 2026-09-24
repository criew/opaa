package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import io.opaa.api.types.AssetVisibility;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.asset.Asset;
import io.opaa.common.ConflictException;
import java.util.UUID;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.junit.jupiter.api.Test;

/** The share cap holds however the library reaches the check - never skipped silently. */
class KnowledgeLibraryAssetTypeTest {

  private final KnowledgeLibraryAssetType definition = new KnowledgeLibraryAssetType();

  @Test
  void aProxyOfTheShellIsCheckedAgainstTheLibraryBehindIt() {
    KnowledgeLibrary library = cappedConnectorLibrary();
    Asset proxy = mock(Asset.class, withSettings().extraInterfaces(HibernateProxy.class));
    LazyInitializer initializer = mock(LazyInitializer.class);
    when(((HibernateProxy) proxy).asHibernateProxy()).thenReturn((HibernateProxy) proxy);
    when(((HibernateProxy) proxy).getHibernateLazyInitializer()).thenReturn(initializer);
    when(initializer.getImplementation()).thenReturn(library);

    assertThatThrownBy(
            () -> definition.requireReachWithinLimits(proxy, AssetVisibility.ORGANIZATION, false))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void anAssetOfThisTypeThatIsNoLibraryIsRefused() {
    Asset shellOnly = new Asset() {};

    assertThatThrownBy(
            () ->
                definition.requireReachWithinLimits(shellOnly, AssetVisibility.ORGANIZATION, false))
        .isInstanceOf(IllegalStateException.class);
  }

  private static KnowledgeLibrary cappedConnectorLibrary() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            UUID.randomUUID(),
            "Bibliothek",
            null,
            UUID.randomUUID(),
            AssetVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            "/data/dokumente",
            null,
            null,
            null,
            false);
    library.updateShareCap(AssetVisibility.SHARED, false);
    return library;
  }
}
