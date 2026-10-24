package io.opaa.instance;

import io.opaa.compliance.InstanceDisclosure.RunningInstance;
import io.opaa.compliance.InstanceStateSource;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the instance register tells the disclosure of the data collection: the running instances,
 * and the fingerprint keys whose digests differ between them. Both come from the register, not from
 * the answering process, so every instance answers alike.
 */
class InstanceRegisterState implements InstanceStateSource {

  private final InstanceRegistry registry;

  InstanceRegisterState(InstanceRegistry registry) {
    this.registry = registry;
  }

  @Override
  public Set<String> settingsDifferingBetweenInstances() {
    return differingKeys(registry.liveFingerprints().values());
  }

  @Override
  public List<RunningInstance> runningInstances() {
    return registry.instances().stream()
        .filter(RegisteredInstance::alive)
        .map(instance -> new RunningInstance(instance.version(), instance.startedAt()))
        .toList();
  }

  /** The keys at least two of {@code fingerprints} know with different digests, sorted. */
  static Set<String> differingKeys(Collection<ConfigurationFingerprint> fingerprints) {
    Map<String, Set<String>> digestsByKey = new HashMap<>();
    for (ConfigurationFingerprint fingerprint : fingerprints) {
      fingerprint
          .digests()
          .forEach(
              (key, digest) -> digestsByKey.computeIfAbsent(key, k -> new HashSet<>()).add(digest));
    }
    Set<String> differing = new TreeSet<>();
    digestsByKey.forEach(
        (key, digests) -> {
          if (digests.size() > 1) {
            differing.add(key);
          }
        });
    return differing;
  }
}
