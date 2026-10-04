package io.opaa.indexing.source;

import io.opaa.api.types.ConnectionProfileSupport;
import io.opaa.common.ValidationException;
import io.opaa.knowledge.Document;
import io.opaa.knowledge.SourceType;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every {@link SourceConnector} by the source type it serves - the one way the administration
 * reaches a connector, and the only list of source types there is (ADR-0038). Built from the
 * connector beans; startup fails when two connectors serve the same type, when no connector serves
 * {@link SourceType#UPLOAD}, when a connector other than that one accepts uploads (the upload
 * store, its folders and originals are keyed to {@code UPLOAD}), when a connector names a push
 * intake without handling one or the other way round, or when its profile declaration breaks the
 * rules of ADR-0038.
 */
public class SourceConnectorRegistry {

  private final Map<SourceType, SourceConnector> connectors = new HashMap<>();

  public SourceConnectorRegistry(List<SourceConnector> connectorBeans) {
    for (SourceConnector connector : connectorBeans) {
      SourceConnectorDescriptor descriptor = connector.descriptor();
      SourceType type = descriptor.type();
      if (connectors.put(type, connector) != null) {
        throw new IllegalStateException("More than one SourceConnector serves source type " + type);
      }
      if ((descriptor.pushIntake() != null) != (connector instanceof PushIntakeHandler)) {
        throw new IllegalStateException(
            "SourceConnector for " + type + " must name a push intake exactly when it handles one");
      }
      requireProfileDeclarationFits(connector);
      if (descriptor.uploads() != SourceType.UPLOAD.equals(type)) {
        throw new IllegalStateException(
            "SourceConnector for " + type + " must accept uploads exactly when it serves UPLOAD");
      }
    }
    if (!connectors.containsKey(SourceType.UPLOAD)) {
      throw new IllegalStateException("No SourceConnector serves UPLOAD");
    }
  }

  /**
   * Profiles are forbidden exactly for a connector filling its library by uploads or reading
   * nothing remote; they are required exactly when the connector offers a sign-in whose app
   * registration only a profile holds; a profile default names a settings key of the connector. A
   * connector signing in with a service account key admits no profile yet: the core signs only for
   * a library's own key (ADR-0040) - the one remote connector without profiles.
   */
  private static void requireProfileDeclarationFits(SourceConnector connector) {
    SourceConnectorDescriptor descriptor = connector.descriptor();
    ProfileDeclaration declaration = descriptor.profileDeclaration();
    String subject = "SourceConnector for " + descriptor.type();
    boolean reachesRemote = !descriptor.uploads() && descriptor.remote();
    if (!reachesRemote && declaration.admitsProfiles()) {
      throw new IllegalStateException(
          subject
              + " fills its library by uploads or reads nothing remote and may not admit"
              + " profiles");
    }
    boolean registrationOnProfile =
        declaration.signIns().stream().anyMatch(signIn -> signIn.method().requiresProfile());
    if (registrationOnProfile != (declaration.support() == ConnectionProfileSupport.REQUIRED)) {
      throw new IllegalStateException(
          subject + " must require profiles exactly when it offers OAuth or client credentials");
    }
    for (DefaultKey key : declaration.defaults().keys()) {
      if (!connector.settingsKeys().contains(key.key())) {
        throw new IllegalStateException(
            subject + " declares profile default " + key.key() + ", which is no settings key");
      }
    }
    if (declaration.requirementGap() != null
        && declaration.support() != ConnectionProfileSupport.OPTIONAL) {
      throw new IllegalStateException(
          subject + " names what a profile requirement leaves open but cannot be switched to one");
    }
    if (declaration.serviceAccountKey() != null && declaration.admitsProfiles()) {
      throw new IllegalStateException(
          subject + " signs in with a service account key and may not admit profiles yet");
    }
    if (reachesRemote && !declaration.admitsProfiles() && declaration.serviceAccountKey() == null) {
      throw new IllegalStateException(subject + " reads a remote source and must admit profiles");
    }
  }

  /** The connector serving {@code type}, empty for a type no connector serves. */
  public Optional<SourceConnector> find(SourceType type) {
    return Optional.ofNullable(type == null ? null : connectors.get(type));
  }

  /**
   * The connector serving {@code type}.
   *
   * @throws ValidationException (German 400) for a type no connector serves
   */
  public SourceConnector connector(SourceType type) {
    return find(type)
        .orElseThrow(() -> new ValidationException("sourceType " + type + " ist unbekannt"));
  }

  public SourceConnectorDescriptor descriptor(SourceType type) {
    return connector(type).descriptor();
  }

  /** Every registered connector's descriptor, ordered by type key. */
  public List<SourceConnectorDescriptor> descriptors() {
    return connectors.values().stream()
        .map(SourceConnector::descriptor)
        .sorted(Comparator.comparing(SourceConnectorDescriptor::type))
        .toList();
  }

  /** The handler behind the push intake of {@code type}, empty when that type offers none. */
  public Optional<PushIntakeHandler> pushIntakeHandler(SourceType type) {
    return find(type)
        .filter(PushIntakeHandler.class::isInstance)
        .map(PushIntakeHandler.class::cast);
  }

  /** Every registered push intake handler, in a stable order. */
  public List<PushIntakeHandler> pushIntakeHandlers() {
    return descriptors().stream()
        .map(descriptor -> connectors.get(descriptor.type()))
        .filter(PushIntakeHandler.class::isInstance)
        .map(PushIntakeHandler.class::cast)
        .toList();
  }

  /** How originals of {@code type} are served; empty for a type without an original to serve. */
  public Optional<OriginalAccess> originalAccess(SourceType type) {
    return find(type).filter(OriginalAccess.class::isInstance).map(OriginalAccess.class::cast);
  }

  /** The listing {@code type} offers before its configuration is saved, empty for none. */
  public Optional<SourceBrowser> browser(SourceType type) {
    return find(type).filter(SourceBrowser.class::isInstance).map(SourceBrowser.class::cast);
  }

  /**
   * Whether {@code document}'s {@code filePath} names a remote the connector run alone can read
   * again. A document of a type no connector serves any more counts as remote: nothing on this
   * machine may be read in its name.
   */
  public boolean isRemote(Document document) {
    if (document.getSourceType() == null) {
      return false;
    }
    return find(document.getSourceType()).map(c -> c.descriptor().remote()).orElse(true);
  }

  /**
   * The address a reader may open for {@code document}, {@code null} where its {@code filePath} is
   * a server-local path or an identity that opens nowhere. Deliberately visible to every reader: it
   * names one document's origin, not the library's source configuration.
   */
  public String deepLink(Document document) {
    boolean linkable =
        find(document.getSourceType()).map(c -> c.descriptor().deepLink()).orElse(false);
    return linkable ? document.getFilePath() : null;
  }
}
