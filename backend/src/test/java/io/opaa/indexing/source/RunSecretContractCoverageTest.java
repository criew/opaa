package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Constructor;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Every remote connector the application registers with a run keeps the {@link RunSecretContract}:
 * a new one fails here until its test double runs the contract too.
 */
@OpaaIntegrationTest
class RunSecretContractCoverageTest {

  /** Connectors that exist only in the test sources, to exercise the core. */
  private static final Set<String> TEST_ONLY_PACKAGES =
      Set.of("io.opaa.indexing.source.probe", "io.opaa.indexing.source.profileprobe");

  @Autowired private List<SourceConnector> connectors;

  @Test
  void everyRegisteredRemoteConnectorWithARunKeepsTheContract() throws Exception {
    Set<String> registered = new HashSet<>();
    for (SourceConnector connector : connectors) {
      SourceConnectorDescriptor descriptor = connector.descriptor();
      if (descriptor.remote()
          && descriptor.indexingRun()
          && !TEST_ONLY_PACKAGES.contains(connector.getClass().getPackageName())) {
        registered.add(descriptor.type().key());
      }
    }

    assertThat(registered).hasSizeGreaterThanOrEqualTo(7);
    assertThat(contractTypes()).containsAll(registered);
  }

  private static Set<String> contractTypes() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(RunSecretContract.class));
    Set<String> types = new HashSet<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("io.opaa.indexing.source")) {
      Constructor<?> constructor =
          Class.forName(candidate.getBeanClassName()).getDeclaredConstructor();
      constructor.setAccessible(true);
      types.add(((RunSecretContract) constructor.newInstance()).type().key());
    }
    return types;
  }
}
