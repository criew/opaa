package io.opaa.indexing.pipeline;

import io.opaa.indexing.ChunkingService;
import java.util.Set;

/**
 * The registry-wide union of every registered pipeline's {@link
 * DocumentPipeline#passthroughMetadataKeys()} - mirrors {@link
 * DocumentPipelineRegistry#allPassthroughMetadataKeys()} for a per-pipeline unit test that
 * constructs its pipeline directly, without a registry. {@code storeChunks} only ever copies a key
 * from this union onto a persisted chunk (see {@code FileProcessingService#storeChunks}), so a
 * pipeline's own output guard only needs to check the keys it produces that fall within this union
 * - a key outside it (e.g. Tika parser metadata a fallback-parsed chunk inherits) can never ride
 * along regardless of any pipeline's declaration.
 */
public final class PassthroughMetadataKeysTestSupport {

  public static final Set<String> REGISTRY_UNION = Set.of(ChunkingService.LOCATION_METADATA_KEY);

  private PassthroughMetadataKeysTestSupport() {}
}
