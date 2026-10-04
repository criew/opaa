package io.opaa.connection.profile;

import io.opaa.indexing.source.SourceConnector;
import io.opaa.indexing.source.SourceSettings;
import java.util.Objects;

/**
 * Where stored credentials may be sent: the origin of an address ({@link ServerAddress#sameOrigin})
 * and what the connector binds them to besides ({@link SourceConnector#credentialBinding}, such as
 * a file share or an imitated account). A secret follows a library only to the same target; {@link
 * #key} names the target it is issued for.
 */
public record SecretTarget(String address, String binding) {

  /** The target of {@code settings}, as {@code connector} binds credentials. */
  public static SecretTarget of(SourceConnector connector, SourceSettings settings) {
    return new SecretTarget(settings.sourceUrl(), connector.credentialBinding(settings));
  }

  /** Whether a secret held for this target also stands for {@code other}. */
  public boolean admits(SecretTarget other) {
    return ServerAddress.sameOrigin(address, other.address)
        && Objects.equals(binding, other.binding);
  }

  /**
   * The target as one value - origin, then binding; equal keys are exactly the targets {@link
   * #admits} accepts. {@code null} for an address without a readable origin.
   */
  public String key() {
    String origin = ServerAddress.originOf(address);
    if (origin == null) {
      return null;
    }
    return binding == null ? origin : origin + " " + binding;
  }
}
