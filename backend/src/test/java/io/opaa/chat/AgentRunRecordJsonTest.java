package io.opaa.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** A record kept before the agent was named still reads; a new one keeps the agent's name. */
class AgentRunRecordJsonTest {

  private final ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void aRecordWithoutTheAgentReadsWithTheAgentUnnamed() {
    UUID runId = UUID.randomUUID();
    String kept = "{\"runId\":\"" + runId + "\",\"endState\":\"COMPLETED\",\"steps\":[]}";

    AgentRunRecord record = mapper.readValue(kept, AgentRunRecord.class);

    assertThat(record.runId()).isEqualTo(runId);
    assertThat(record.agentId()).isNull();
    assertThat(record.agentName()).isNull();
  }

  @Test
  void aRecordKeepsTheAgentsName() {
    AgentRunRecord record =
        new AgentRunRecord(
            UUID.randomUUID(),
            AgentRunRecord.EndState.COMPLETED,
            List.of(),
            UUID.randomUUID(),
            "Recherche");

    AgentRunRecord read = mapper.readValue(mapper.writeValueAsString(record), AgentRunRecord.class);

    assertThat(read).isEqualTo(record);
  }
}
