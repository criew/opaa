package io.opaa.indexing.format;

import io.opaa.test.ProductionDocumentFormats;
import java.util.Set;

/**
 * The registry-wide union of every registered format's {@link
 * DocumentFormat#passthroughMetadataKeys()} - read from a registry built over the same formats the
 * application registers, so it cannot claim to mirror {@link
 * DocumentFormatRegistry#allPassthroughMetadataKeys()} and quietly hold something else. For a
 * per-format unit test that constructs its format directly, without a registry.
 *
 * <p>{@code storeChunks} only ever copies a key from this union onto a persisted chunk (see {@code
 * DocumentIngestService#storeChunks}), so a format's own output guard only needs to check the keys
 * it produces that fall within this union - a key outside it (e.g. Tika parser metadata a
 * fallback-parsed chunk inherits) can never ride along regardless of any format's declaration.
 */
public final class PassthroughMetadataKeysTestSupport {

  public static final Set<String> REGISTRY_UNION =
      ProductionDocumentFormats.registry().allPassthroughMetadataKeys();

  private PassthroughMetadataKeysTestSupport() {}
}
