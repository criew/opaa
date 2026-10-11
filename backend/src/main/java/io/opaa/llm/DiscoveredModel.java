package io.opaa.llm;

import java.util.List;

/**
 * One model an endpoint offers. Everything but {@code identifier}, {@code capabilities} and {@code
 * chatSuitable} is {@code null} when the endpoint does not report it; {@code capabilities} is then
 * empty.
 */
public record DiscoveredModel(
    String identifier,
    String family,
    String parameterSize,
    String quantization,
    Long sizeBytes,
    Integer contextLength,
    List<String> capabilities,
    boolean chatSuitable) {

  public DiscoveredModel {
    capabilities = List.copyOf(capabilities);
  }
}
