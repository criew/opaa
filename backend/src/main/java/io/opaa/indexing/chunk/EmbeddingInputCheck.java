package io.opaa.indexing.chunk;

import java.util.List;
import java.util.Map;

/**
 * What {@link VectorChunkStore#checkInputs} found for the chunks whose embedding input could reach
 * the configured input limit; every other chunk is in none of the three.
 *
 * @param vectors the vector of every checked input the endpoint embedded whole or without saying,
 *     keyed by the input text, for reuse instead of a second call
 * @param overlong the chunks the endpoint proved too long, in chunk order
 * @param unverified positions of chunks embedded without a token count from the endpoint, so a
 *     silent cut can neither be shown nor ruled out
 */
public record EmbeddingInputCheck(
    Map<String, float[]> vectors, List<Overlong> overlong, List<Integer> unverified) {

  /**
   * One chunk the endpoint proved too long.
   *
   * @param position the chunk's position in the checked list
   * @param reportedTokens the token count the endpoint reported for it, or {@code null} when it
   *     refused the input instead
   */
  public record Overlong(int position, Integer reportedTokens) {}
}
