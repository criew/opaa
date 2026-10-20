package io.opaa.compliance;

import io.opaa.compliance.ComplianceManifest.SettingEntry;
import io.opaa.compliance.ComplianceManifest.SettingSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The configuration keys of the fingerprint every instance of an installation must share (ADR-0053,
 * Entscheidung 4): every {@code PROPERTY} setting of the compliance manifest (the keys with {@link
 * DataImpact}), every {@code opaa.*} key marked {@link InstallationWide}, and the technical values
 * outside {@code opaa.*} in {@link #EXTERNAL_KEYS}. A new key is classified where it is bound; the
 * structure test of the configuration fails until it is.
 */
public final class FingerprintKeys {

  /** Where the build writes the configuration metadata, {@code opaa.*} keys included. */
  public static final String METADATA = "META-INF/spring-configuration-metadata.json";

  /**
   * Installation-wide values outside {@code opaa.*} that no manifest entry carries: the vector
   * space of the embeddings. Embedding model and address are manifest settings.
   */
  public static final List<String> EXTERNAL_KEYS =
      List.of(
          "spring.ai.vectorstore.pgvector.dimensions",
          "spring.ai.vectorstore.pgvector.distance-type");

  private static final List<String> SECRET_SEGMENTS =
      List.of("secret", "password", "key", "token", "credential");

  private FingerprintKeys() {}

  /** Whether a key may differ between instances or must be the same on all of them. */
  public enum Scope {
    INSTALLATION,
    PER_INSTANCE
  }

  /**
   * A key of the fingerprint.
   *
   * @param secret whether only the name may ever be reported, never the value
   */
  public record FingerprintKey(String name, boolean secret) {}

  /** A key of the configuration metadata with the class that binds or reads it. */
  public record MetadataKey(String name, String sourceType) {}

  /** The fingerprint keys of this build, sorted by name. */
  public static List<FingerprintKey> delivered() {
    return of(ComplianceManifestLoader.load(), deliveredMetadata());
  }

  /** The fingerprint keys of {@code manifest} and {@code metadata}, sorted by name. */
  public static List<FingerprintKey> of(ComplianceManifest manifest, List<MetadataKey> metadata) {
    Map<String, FingerprintKey> keys = new TreeMap<>();
    for (SettingEntry entry : manifest.settings()) {
      if (entry.source() == SettingSource.PROPERTY) {
        keys.put(entry.key(), new FingerprintKey(entry.key(), false));
      }
    }
    for (MetadataKey key : metadata) {
      if (!key.name().startsWith("opaa.") || keys.containsKey(key.name())) {
        continue;
      }
      Optional<Marker> marker = markerOf(key, new ArrayList<>());
      if (marker.isPresent() && marker.get().scope() == Scope.INSTALLATION) {
        keys.put(key.name(), new FingerprintKey(key.name(), marker.get().secret()));
      }
    }
    for (String external : EXTERNAL_KEYS) {
      keys.putIfAbsent(external, new FingerprintKey(external, false));
    }
    return List.copyOf(keys.values());
  }

  /** The {@code opaa.*} and other keys of the configuration metadata this build ships. */
  public static List<MetadataKey> deliveredMetadata() {
    try (InputStream in = new ClassPathResource(METADATA).getInputStream()) {
      JsonNode root = JsonMapper.builder().build().readTree(in);
      List<MetadataKey> keys = new ArrayList<>();
      for (JsonNode property : root.path("properties")) {
        JsonNode sourceType = property.get("sourceType");
        keys.add(
            new MetadataKey(
                property.path("name").asString(),
                sourceType == null || sourceType.isNull() ? null : sourceType.asString()));
      }
      return keys;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The scope the markers give {@code key}: one on its component, else one on its source type or an
   * enclosing type. Empty, with the reason in {@code problems}, when none or two contradicting
   * markers decide it. {@link DataImpact} is not consulted here; its keys are in the manifest.
   */
  public static Optional<Scope> scopeOf(MetadataKey key, List<String> problems) {
    return markerOf(key, problems).map(Marker::scope);
  }

  /**
   * Whether a value must never be named: marked {@link InstallationWide#secret()}, or a last key
   * segment that names a secret, a password, a key, a token or a credential.
   */
  static boolean looksSecret(String name) {
    String last = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    return Arrays.stream(last.split("-")).anyMatch(SECRET_SEGMENTS::contains);
  }

  private record Marker(Scope scope, boolean secret) {}

  private static Optional<Marker> markerOf(MetadataKey key, List<String> problems) {
    if (key.sourceType() == null) {
      problems.add(key.name() + ": no sourceType in the configuration metadata");
      return Optional.empty();
    }
    Class<?> type;
    try {
      type = Class.forName(key.sourceType());
    } catch (ClassNotFoundException e) {
      problems.add(key.name() + ": sourceType " + key.sourceType() + " does not exist");
      return Optional.empty();
    }
    String component = camel(key.name().substring(key.name().lastIndexOf('.') + 1));
    List<AnnotatedElement> candidates = new ArrayList<>();
    component(type, component).ifPresent(candidates::add);
    for (Class<?> current = type; current != null; current = current.getEnclosingClass()) {
      candidates.add(current);
    }
    int known = problems.size();
    for (AnnotatedElement candidate : candidates) {
      Optional<Marker> marker = marker(key, candidate, problems);
      if (problems.size() > known) {
        return Optional.empty();
      }
      if (marker.isPresent()) {
        return marker;
      }
    }
    problems.add(
        key.name()
            + ": neither installation-wide nor per instance - annotate the component '"
            + component
            + "' of "
            + type.getName()
            + " with @InstallationWide or @PerInstance(\"<why it may differ>\"), or the type with"
            + " @PerInstance(\"<why all its keys may differ>\") (ADR-0053, Entscheidung 4)");
    return Optional.empty();
  }

  /** The marker on {@code element}; empty, with a problem, when it carries both. */
  private static Optional<Marker> marker(
      MetadataKey key, AnnotatedElement element, List<String> problems) {
    InstallationWide wide = element.getAnnotation(InstallationWide.class);
    boolean perInstance = element.isAnnotationPresent(PerInstance.class);
    if (wide != null && perInstance) {
      problems.add(key.name() + ": both @InstallationWide and @PerInstance on " + element);
      return Optional.empty();
    }
    if (wide != null) {
      return Optional.of(new Marker(Scope.INSTALLATION, wide.secret() || looksSecret(key.name())));
    }
    return perInstance ? Optional.of(new Marker(Scope.PER_INSTANCE, false)) : Optional.empty();
  }

  private static Optional<AnnotatedElement> component(Class<?> type, String name) {
    if (type.isRecord()) {
      return Arrays.stream(type.getRecordComponents())
          .filter(component -> component.getName().equals(name))
          .map(AnnotatedElement.class::cast)
          .findFirst();
    }
    return Arrays.stream(type.getDeclaredFields())
        .filter(field -> field.getName().equals(name))
        .map(AnnotatedElement.class::cast)
        .findFirst();
  }

  private static String camel(String kebab) {
    StringBuilder out = new StringBuilder();
    for (String part : kebab.split("-")) {
      out.append(
          out.isEmpty() || part.isEmpty()
              ? part
              : Character.toUpperCase(part.charAt(0)) + part.substring(1));
    }
    return out.toString();
  }
}
