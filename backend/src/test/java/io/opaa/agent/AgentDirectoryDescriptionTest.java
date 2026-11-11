package io.opaa.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.InstanceDisclosure.DataSetDescription;
import io.opaa.compliance.InstanceDisclosure.QueryPath;
import org.junit.jupiter.api.Test;

/**
 * The extract for the staff council names the agent directory as a way to read the agents, and the
 * absence of usage figures as a promise.
 */
class AgentDirectoryDescriptionTest {

  @Test
  void theDirectoryIsAQueryPathWithoutUsage() {
    DataSetDescription description =
        new AgentDirectoryDescription().dataSetDescriptions().getFirst();

    assertThat(description.id()).isEqualTo("agent-directory");
    assertThat(description.queryPaths())
        .extracting(QueryPath::name)
        .containsExactly("Agentenverzeichnis");
    assertThat(description.absentQueryPaths()).anyMatch(path -> path.contains("Nutzung"));
  }
}
