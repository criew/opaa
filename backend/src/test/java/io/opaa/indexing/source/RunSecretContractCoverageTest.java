package io.opaa.indexing.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.opaa.test.OpaaIntegrationTest;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
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
    Set<String> types = new HashSet<>();
    for (RunSecretContract contract : contracts()) {
      types.add(contract.type().key());
    }
    assertThat(types).containsAll(registered);
  }

  /** A connector whose code asks again after a rejection runs the contract's rejection case. */
  @Test
  void everyConnectorThatAsksAgainAfterARejectionRunsTheRejectionCase() throws Exception {
    JavaClasses classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.opaa.indexing.source");
    JavaClass runCredentials = classes.get(RunCredentials.class);
    Set<String> asking = new HashSet<>();
    for (JavaMethod method : runCredentials.getMethods()) {
      if (method.getName().contains("fterRejection")) {
        method.getCallsOfSelf().forEach(call -> asking.add(call.getOriginOwner().getPackageName()));
        method
            .getReferencesToSelf()
            .forEach(reference -> asking.add(reference.getOriginOwner().getPackageName()));
      }
    }
    asking.remove(RunCredentials.class.getPackageName());
    Set<String> declaring = new HashSet<>();
    for (RunSecretContract contract : contracts()) {
      if (contract.usesRejectionSeam()) {
        declaring.add(contract.getClass().getPackageName());
      }
    }

    assertThat(asking).isNotEmpty();
    assertThat(declaring).containsAll(asking);
  }

  private static List<RunSecretContract> contracts() throws Exception {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AssignableTypeFilter(RunSecretContract.class));
    List<RunSecretContract> contracts = new ArrayList<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("io.opaa.indexing.source")) {
      Constructor<?> constructor =
          Class.forName(candidate.getBeanClassName()).getDeclaredConstructor();
      constructor.setAccessible(true);
      contracts.add((RunSecretContract) constructor.newInstance());
    }
    return contracts;
  }
}
