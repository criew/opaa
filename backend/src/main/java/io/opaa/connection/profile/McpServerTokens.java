package io.opaa.connection.profile;

import io.opaa.connection.token.ConnectionSecrets;
import io.opaa.connection.token.SecretOwner.PersonOwned;
import io.opaa.connection.token.SecretRefusedException;
import io.opaa.indexing.source.Secret;
import io.opaa.indexing.source.SourceBlock.Reason;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The one way an MCP client gets a person's token for an MCP server: the token together with the
 * only address it may be sent to. The token is handed out only for the resource it was issued for
 * (RFC 8707) - a token of server A never reaches server B, whatever the caller asks - and only
 * while the profile is not locked and the person's account is usable.
 */
@Component
public class McpServerTokens {

  private final ConnectionProfileRepository profiles;
  private final ConnectionSecrets secrets;

  McpServerTokens(ConnectionProfileRepository profiles, ConnectionSecrets secrets) {
    this.profiles = profiles;
    this.secrets = secrets;
  }

  /**
   * A token and the server it is for; {@link #toString} shows no token.
   *
   * @param resource the MCP server's address, the only one {@code token} may be sent to
   */
  public record Access(String resource, Secret token) {

    @Override
    public String toString() {
      return "Access[resource=" + resource + ", token=***]";
    }
  }

  /**
   * The access token of person {@code userId} for the MCP server profile {@code profileId}, renewed
   * when due.
   *
   * @throws SecretRefusedException with {@code ACCESS_REMOVED} for no such MCP server, {@code
   *     PROFILE_LOCKED} for a locked one, else the reason the store refuses
   */
  public Access accessFor(UUID userId, UUID profileId) {
    ConnectionProfile profile =
        profiles
            .findById(profileId)
            .filter(ConnectionProfile::isMcpServer)
            .orElseThrow(() -> new SecretRefusedException(Reason.ACCESS_REMOVED));
    if (profile.isLocked()) {
      throw new SecretRefusedException(Reason.PROFILE_LOCKED);
    }
    String resource = McpServerResource.of(profile);
    return new Access(
        resource, secrets.current(new PersonOwned(profile.getId(), userId), resource));
  }
}
