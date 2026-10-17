package io.opaa.compliance;

import io.opaa.compliance.ComplianceManifest.DataFlowEntry;
import io.opaa.compliance.ComplianceManifest.SettingEntry;
import io.opaa.compliance.InstanceDisclosure.ConnectorUsage;
import io.opaa.compliance.InstanceDisclosure.DataFlowState;
import io.opaa.compliance.InstanceDisclosure.ModelProvider;
import io.opaa.compliance.InstanceDisclosure.Protocol;
import io.opaa.compliance.InstanceDisclosure.SettingValue;
import io.opaa.compliance.InstanceDisclosure.StoredValue;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Assembles the {@link InstanceDisclosure} of this instance from the manifest of its version, the
 * environment and every {@link InstanceStateSource}.
 *
 * <p><b>Allowlist, not denylist.</b> A configuration value is read only for a key the manifest
 * lists under {@code settings}; a value a source reports for any other key is dropped. The manifest
 * lists only keys with data impact, and a secret without default is never one (ADR-0052,
 * Entscheidung 9). Every value that leaves additionally passes {@link #exportable(String)}, so an
 * address loses user information, query and fragment wherever it comes from.
 *
 * <p>The manifest is read on every call, not held: the export is rare, and a held copy would be
 * process-local state without need.
 */
@NoDataImpact("the commit the image was built from, named in the disclosure")
@Component
public class InstanceDisclosureAssembler {

  /** The commit shown when the image was built without one. */
  public static final String UNKNOWN_COMMIT = "unbekannt";

  /** The version shown outside a release build. */
  public static final String DEVELOPMENT_VERSION = "0.0.0-dev";

  /** Shown instead of an address that cannot be read, so nothing of it leaves. */
  public static final String UNREADABLE_ADDRESS = "(Adresse nicht lesbar)";

  /** The property the image sets to the commit it was built from. */
  static final String COMMIT_PROPERTY = "opaa.build.commit";

  private final Environment environment;
  private final String version;
  private final List<InstanceStateSource> sources;
  private final Clock clock;

  @Autowired
  public InstanceDisclosureAssembler(
      Environment environment,
      ObjectProvider<BuildProperties> buildProperties,
      List<InstanceStateSource> sources,
      Clock clock) {
    this(
        environment,
        buildProperties.stream()
            .map(BuildProperties::getVersion)
            .findFirst()
            .orElse(DEVELOPMENT_VERSION),
        sources,
        clock);
  }

  InstanceDisclosureAssembler(
      Environment environment, String version, List<InstanceStateSource> sources, Clock clock) {
    this.environment = environment;
    this.version = version == null || version.isBlank() ? DEVELOPMENT_VERSION : version;
    this.sources = List.copyOf(sources);
    this.clock = clock;
  }

  /**
   * The disclosure of this instance with the manifest delivered with this build; what belongs to an
   * organization is counted for {@code organizationId}.
   */
  public InstanceDisclosure assemble(Protocol protocol, UUID organizationId) {
    return assemble(ComplianceManifestLoader.load(), protocol, organizationId);
  }

  InstanceDisclosure assemble(ComplianceManifest manifest, Protocol protocol, UUID organizationId) {
    String commit = environment.getProperty(COMMIT_PROPERTY, "").trim();
    return new InstanceDisclosure(
        version,
        commit.isEmpty() ? UNKNOWN_COMMIT : exportable(commit),
        Instant.now(clock),
        manifest,
        settings(manifest),
        dataFlows(manifest, organizationId),
        connectorUsage(organizationId),
        modelProviders(),
        protocol);
  }

  /**
   * {@code value} as it may leave the installation: every comma-separated part that is an address
   * ({@code scheme://…}) keeps only scheme, host, port and path. When one part cannot be read, or a
   * part without a scheme holds an {@code @} (a comma inside user information splits it), the whole
   * value becomes {@link #UNREADABLE_ADDRESS}, so no piece of it leaves. Anything else is returned
   * unchanged.
   */
  public static String exportable(String value) {
    if (value == null || !value.contains("://")) {
      return value;
    }
    List<String> parts = new ArrayList<>();
    for (String part : value.split(",", -1)) {
      if (!part.contains("://")) {
        if (part.contains("@")) {
          return UNREADABLE_ADDRESS;
        }
        parts.add(part);
        continue;
      }
      String address = address(part.trim());
      if (address.equals(UNREADABLE_ADDRESS)) {
        return UNREADABLE_ADDRESS;
      }
      parts.add(address);
    }
    return String.join(",", parts);
  }

  private static String address(String value) {
    try {
      URI uri = new URI(value);
      if (uri.getScheme() == null || uri.getHost() == null) {
        return UNREADABLE_ADDRESS;
      }
      return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null)
          .toString();
    } catch (URISyntaxException e) {
      return UNREADABLE_ADDRESS;
    }
  }

  private List<SettingValue> settings(ComplianceManifest manifest) {
    Map<String, StoredValue> stored = new HashMap<>();
    for (InstanceStateSource source : sources) {
      stored.putAll(source.storedSettings());
    }
    List<SettingValue> values = new ArrayList<>();
    for (SettingEntry entry : manifest.settings()) {
      String effective;
      Instant changedAt = null;
      switch (entry.source()) {
        case PROPERTY -> effective = property(entry.key());
        case DATABASE -> {
          StoredValue value = stored.get(entry.key());
          effective = value == null ? null : value.value();
          changedAt = value == null ? null : value.changedAt();
        }
        case CONSTANT -> effective = entry.defaultValue();
        default -> throw new IllegalStateException("unknown setting source " + entry.source());
      }
      String exported = exportable(effective);
      values.add(
          new SettingValue(
              entry.area(),
              entry.key(),
              entry.source(),
              entry.defaultValue(),
              exported,
              exported != null && !sameValue(exported, exportable(entry.defaultValue())),
              changedAt,
              entry.range(),
              entry.effect()));
    }
    return values;
  }

  /** The value of {@code key}, also when it is bound as an indexed list; {@code null} if unset. */
  private String property(String key) {
    String value = environment.getProperty(key);
    if (value != null) {
      return value;
    }
    return Binder.get(environment)
        .bind(key, Bindable.listOf(String.class))
        .map(list -> String.join(",", list))
        .orElse(null);
  }

  private static boolean sameValue(String effective, String delivered) {
    String a = effective.trim();
    String b = delivered.trim();
    if (isBoolean(a) && isBoolean(b)) {
      return a.equalsIgnoreCase(b);
    }
    return a.equals(b);
  }

  private static boolean isBoolean(String value) {
    String lower = value.toLowerCase(Locale.ROOT);
    return lower.equals("true") || lower.equals("false");
  }

  private List<DataFlowState> dataFlows(ComplianceManifest manifest, UUID organizationId) {
    Map<String, Boolean> active = new HashMap<>();
    for (InstanceStateSource source : sources) {
      source
          .dataFlows(organizationId)
          .forEach((id, on) -> active.merge(id, on, Boolean::logicalOr));
    }
    List<DataFlowState> states = new ArrayList<>();
    for (DataFlowEntry entry : manifest.dataFlows()) {
      states.add(
          new DataFlowState(entry.id(), entry.group(), entry.target(), active.get(entry.id())));
    }
    return states;
  }

  private List<ConnectorUsage> connectorUsage(UUID organizationId) {
    Map<String, ConnectorUsage> byType = new TreeMap<>();
    for (InstanceStateSource source : sources) {
      for (ConnectorUsage usage : source.connectorUsage(organizationId)) {
        byType.merge(
            usage.type(),
            usage,
            (a, b) ->
                new ConnectorUsage(
                    a.type(),
                    a.libraries() + b.libraries(),
                    a.connectionProfiles() + b.connectionProfiles()));
      }
    }
    return List.copyOf(byType.values());
  }

  private List<ModelProvider> modelProviders() {
    return sources.stream()
        .flatMap(source -> source.modelProviders().stream())
        .map(
            provider ->
                new ModelProvider(
                    provider.role(),
                    provider.name(),
                    exportable(provider.baseUrl()),
                    provider.model(),
                    provider.active()))
        .sorted(
            Comparator.comparing(ModelProvider::role)
                .thenComparing(provider -> provider.name() == null ? "" : provider.name()))
        .collect(Collectors.toList());
  }
}
