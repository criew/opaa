package io.opaa.mcp;

import io.opaa.auth.ExternalAccessPathAllowlist;
import io.opaa.auth.ExternalAccessPathContribution;
import java.util.List;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/**
 * The MCP endpoint on the channel's positive list - and, unlike the REST paths, a path the channel
 * <b>owns</b>: the Spring AI starter registers it unauthenticated, so a missing chain rule would
 * not be a configuration mistake but an open door (ADR-0035, Entscheidung 1).
 *
 * <p>Authorised for {@code POST}, which is the only method the transport serves; owned for every
 * method, so not even the {@code 405} of a {@code GET} is answered to an unauthenticated caller.
 */
@Component
class McpPathContribution implements ExternalAccessPathContribution {

  private final List<RequestMatcher> authorised;
  private final List<RequestMatcher> owned;

  McpPathContribution(McpEndpoint endpoint) {
    this.authorised = List.of(ExternalAccessPathAllowlist.post(endpoint.path()));
    this.owned = List.of(PathPatternRequestMatcher.withDefaults().matcher(endpoint.path()));
  }

  @Override
  public List<RequestMatcher> authorisedPaths() {
    return authorised;
  }

  @Override
  public List<RequestMatcher> ownedPaths() {
    return owned;
  }
}
