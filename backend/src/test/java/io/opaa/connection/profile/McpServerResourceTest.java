package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The canonical resource indicator of an MCP server (RFC 8707; MCP authorization). */
class McpServerResourceTest {

  @ParameterizedTest
  @CsvSource({
    "HTTPS://MCP.Example.org/mcp, https://mcp.example.org/mcp",
    "https://mcp.example.org:443/mcp/, https://mcp.example.org/mcp",
    "https://mcp.example.org, https://mcp.example.org",
    "https://mcp.example.org:8443/a/b, https://mcp.example.org:8443/a/b",
    "http://127.0.0.1:8080/mcp, http://127.0.0.1:8080/mcp",
    "http://localhost/mcp, http://localhost/mcp"
  })
  void canonicalFormKeepsThePath(String given, String canonical) {
    assertThat(McpServerResource.canonical(given)).isEqualTo(canonical);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "http://mcp.example.org/mcp",
        "https://mcp.example.org/mcp?tenant=a",
        "https://mcp.example.org/mcp#x",
        "https://nutzer@mcp.example.org/mcp",
        "stdio:/usr/bin/mcp",
        "mcp.example.org/mcp"
      })
  void refusesAnythingButAnHttpsEndpoint(String given) {
    assertThatThrownBy(() -> McpServerResource.canonical(given))
        .isInstanceOf(ValidationException.class);
  }
}
