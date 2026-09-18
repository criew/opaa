package io.opaa.mcp;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The MCP protocol revisions this installation speaks (ADR-0035, Entscheidung 6a). Declared here
 * and not read off the library on purpose: "welche Fassung spricht diese Installation?" must be
 * answerable from the configuration and the handbook, without a look into a dependency list.
 *
 * <p>The delivered values are the handshake-based revisions the shipped {@code mcp-core} actually
 * serves. {@code 2024-11-05} is deliberately absent although the constant exists there - the
 * shipped transport does not offer it, and announcing a revision that is not served would be the
 * silent mismatch Entscheidung 6b rules out. The per-request negotiation of {@code 2026-07-28} is
 * not implemented in the library at all; a client asking for it is refused, never served silently
 * under another revision.
 *
 * <p>The two values below shape what {@code search} hands a foreign model (#1766). They belong to
 * this channel, not to {@code opaa.search}: {@code POST /api/v1/search} answers a program that asks
 * for exactly what it wants, while an assistant pays for every returned character out of its
 * context window and fetches the full passage anyway once it has chosen one.
 *
 * @param protocolVersions oldest to newest; the last one is what a client without a usable request
 *     is offered.
 * @param defaultMaxHits documents a {@code search} without its own {@code maxHits} receives.
 *     Deliberately below {@code opaa.search.default-max-hits}: here a hit is a stop on the way to a
 *     {@code fetch}, not the answer.
 * @param excerptCharacters the length of the excerpt around the found place, per hit.
 */
@ConfigurationProperties(prefix = "opaa.mcp")
public record McpProperties(
    @DefaultValue({"2025-03-26", "2025-06-18", "2025-11-25"}) List<String> protocolVersions,
    @DefaultValue("5") int defaultMaxHits,
    @DefaultValue("500") int excerptCharacters) {

  public McpProperties {
    if (protocolVersions == null || protocolVersions.isEmpty()) {
      throw new IllegalArgumentException("opaa.mcp.protocol-versions must name at least one");
    }
    if (defaultMaxHits <= 0) {
      throw new IllegalArgumentException(
          "opaa.mcp.default-max-hits must be positive, got " + defaultMaxHits);
    }
    if (excerptCharacters < 100) {
      throw new IllegalArgumentException(
          "opaa.mcp.excerpt-characters must be at least 100, got " + excerptCharacters);
    }
    protocolVersions = List.copyOf(protocolVersions);
  }
}
