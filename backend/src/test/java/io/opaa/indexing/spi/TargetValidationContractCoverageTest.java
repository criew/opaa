package io.opaa.indexing.spi;

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
 * Every connector with an outbound connection the application registers keeps the {@link
 * TargetValidationContract}: a new one fails here until a test runs the contract on it.
 */
@OpaaIntegrationTest
class TargetValidationContractCoverageTest {

  /** Connectors that exist only in the test sources, to exercise the core. */
  private static final Set<String> TEST_ONLY_PACKAGES =
      Set.of(
          "io.opaa.indexing.source.probe",
          "io.opaa.indexing.source.profileprobe",
          "io.opaa.indexing.source.oauthprobe",
          "io.opaa.indexing.source.consentprobe");

  @Autowired private List<SourceConnector> connectors;

  // regression guard for #3383: a connector with an outbound connection cannot skip the SSRF suite
  @Test
  void everyRegisteredRemoteConnectorKeepsTheContract() throws Exception {
    Set<String> registered = new HashSet<>();
    for (SourceConnector connector : connectors) {
      if (connector.descriptor().remote()
          && !TEST_ONLY_PACKAGES.contains(connector.getClass().getPackageName())) {
        registered.add(connector.descriptor().type().key());
      }
    }

    assertThat(registered).hasSizeGreaterThanOrEqualTo(10);
    assertThat(contractTypes()).containsAll(registered);
  }

  private static Set<String> contractTypes() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(TargetValidationContract.class));
    Set<String> types = new HashSet<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("io.opaa.indexing.source")) {
      Constructor<?> constructor =
          Class.forName(candidate.getBeanClassName()).getDeclaredConstructor();
      constructor.setAccessible(true);
      types.add(((TargetValidationContract) constructor.newInstance()).type().key());
    }
    return types;
  }
}
