/**
 * The MCP server of the external-access channel (#1721, ADR-0035): Streamable HTTP in the
 * <b>stateless</b> mode, under one path, with the three tools {@code search}, {@code fetch} and
 * {@code list_libraries}.
 *
 * <p>A translation layer and nothing else. Every tool call resolves the caller's effective view
 * through {@code io.opaa.search} - the same domain services {@code POST /api/v1/search} and {@code
 * POST /api/v1/query} use - and this package holds no index, no permission logic and no access to
 * {@code vector_store} of its own. {@code McpDependencyStructureTest} keeps that structural.
 *
 * <p>There is no session: every JSON-RPC request is its own HTTP request with its own bearer value,
 * so switch, token validity and effective view are evaluated per call by construction rather than
 * by a rule someone has to remember.
 *
 * <p>Three things the Spring AI starter does not do and this package therefore does: it registers
 * the endpoint <b>unauthenticated</b> (the channel's filter chain claims the path), it answers
 * {@code tools/list} from a server-wide list (an own handler builds it per request), and it accepts
 * an unknown protocol version silently (an own check refuses it).
 */
package io.opaa.mcp;
