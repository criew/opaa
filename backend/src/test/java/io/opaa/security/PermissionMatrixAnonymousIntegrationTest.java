package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.instance.InvalidationSubscriber;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.security.ApiOperations.Operation;
import io.opaa.security.PermissionMatrix.Outcome;
import io.opaa.security.PermissionMatrix.Role;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The anonymous column of {@link PermissionMatrix}: every operation of the specification, called
 * without any credential, answers as the matrix says - in the {@code oidc} mode, the only one in
 * which a request can arrive unauthenticated ({@code DevAuthFilter} authenticates every request
 * under {@code dev}). No fixture is needed: an anonymous caller must not get past the
 * authentication whatever the ids, so every id is one nobody uses.
 */
@OpaaLocalAuthMockMvcTest
class PermissionMatrixAnonymousIntegrationTest {

  private static final ApiOperations API = ApiOperations.bundled();
  private static final PermissionMatrix MATRIX = PermissionMatrix.load();

  @Autowired private MockMvc mockMvc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private List<InvalidationSubscriber> caches;

  @Test
  void everyOperationAnswersAnAnonymousCallerAsTheMatrixSays() throws IOException {
    ProbeRequests probes =
        new ProbeRequests(API, Map.of("assetType", KnowledgeLibrary.ASSET_TYPE.value()));

    Map<String, RolledBackRequests.Answer> answers =
        new RolledBackRequests(mockMvc, transactionManager, jdbc, caches)
            .answers(API.operations(), probes::build, rows -> {});

    List<String> findings = new ArrayList<>();
    StringBuilder observed = new StringBuilder();
    for (Operation operation : API.operations()) {
      int status = answers.get(operation.id()).status();
      Outcome outcome = Outcome.of(status);
      observed.append(operation.id()).append(": ").append(outcome.key).append(" # ");
      observed.append(PermissionMatrix.statusClass(status)).append(' ').append(status);
      observed.append('\n');
      String serverError = MATRIX.serverErrorOf(operation, status);
      if (serverError != null) {
        findings.add(serverError + ": " + answers.get(operation.id()).body());
      }
      PermissionMatrix.Entry entry = MATRIX.entry(operation.id());
      Outcome expected = entry == null ? null : entry.expected().get(Role.ANONYMOUS);
      if (expected != outcome) {
        findings.add(
            "%s: anonymous expects '%s', observed '%s' (status %d)"
                .formatted(
                    operation.label(), expected == null ? "-" : expected.key, outcome.key, status));
      }
    }
    Path directory = Path.of("build", "permission-matrix");
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("observed-anonymous.yaml"), observed);
    assertThat(findings)
        .as(
            "operations whose outcome for an anonymous caller departs from %s",
            PermissionMatrix.FILE)
        .isEmpty();
  }
}
