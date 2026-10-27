package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetShellService;
import io.opaa.indexing.chunk.EmbeddingIndexReconciler;
import io.opaa.knowledge.ErasureCause;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.SourceType;
import io.opaa.llm.InstalledEmbeddingModel;
import io.opaa.organization.Organization;
import io.opaa.test.EmbeddingModelRemoval;
import io.opaa.test.OpaaPropertyVariantIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Libraries of an installation without an embedding model (ADR-0060, Entscheidung 10): there is no
 * vector table, so every path that reads or deletes chunks finds none instead of failing - the
 * erasure of a private library, the deletion run that continues a marked one, the status page of
 * the search and the pipeline versions. Runs in the property-variant signature, whose neighbours
 * expect {@code vector_store} to be recreated; {@link EmbeddingModelRemoval} puts it back.
 */
@OpaaPropertyVariantIntegrationTest
class LibrariesWithoutVectorTableIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetShellService shellService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private OwnLibraryFixtures ownLibraryFixtures;
  @Autowired private PrivateLibraryErasure erasure;
  @Autowired private PrivateLibraryDeletionRun deletionRun;
  @Autowired private InstalledEmbeddingModel installedModel;
  @Autowired private EmbeddingIndexReconciler reconciler;

  private final List<UUID> libraries = new ArrayList<>();
  private EmbeddingModelRemoval removal;
  private UUID owner;

  @BeforeEach
  void anInstallationWithoutEmbeddingModel() {
    removal = new EmbeddingModelRemoval(jdbc, installedModel, reconciler);
    removal.remove();
    owner = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Ohne Modell', now(), ?, ?)",
        owner,
        "without-vector-table-" + owner,
        "without-vector-table-" + owner + "@example.com",
        SystemRole.USER.name(),
        Organization.DEFAULT_ID);
  }

  @AfterEach
  void restore() {
    removal.restore();
    ownLibraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraries.clear();
    jdbc.update("DELETE FROM audit_log WHERE actor_ref = ?", owner.toString());
    jdbc.update("DELETE FROM asset_grant_history WHERE subject_user_id = ?", owner);
    jdbc.update("DELETE FROM asset_ownership_history WHERE owner_user_id = ?", owner);
    jdbc.update("DELETE FROM users WHERE id = ?", owner);
  }

  // regression guard: without a vector table the erasure failed after its marker was committed
  @Test
  void aPrivateLibraryIsErasedAtOnce() {
    KnowledgeLibrary library = privateLibrary();

    PrivateLibraryErasure.Outcome outcome =
        erasure.erase(library.getId(), ErasureCause.OWNER_REQUEST, owner);

    assertThat(outcome).isEqualTo(PrivateLibraryErasure.Outcome.ERASED);
    assertThat(libraryRepository.findById(library.getId())).isEmpty();
  }

  // regression guard: a marked erasure failed on every run of the deletion run
  @Test
  void theDeletionRunContinuesAMarkedErasure() {
    KnowledgeLibrary library = privateLibrary();
    transactionTemplate.executeWithoutResult(
        status ->
            libraryRepository.requestErasure(
                library.getId(), Instant.now(), ErasureCause.DELETION_PERIOD_EXPIRED.name()));

    PrivateLibraryDeletionRun.Result result = deletionRun.runOnce();

    assertThat(result.failed()).isZero();
    assertThat(libraryRepository.findById(library.getId())).isEmpty();
  }

  // regression guard: the status page and the pipeline versions answered 500 with a library
  @Test
  void theSearchStatusAndThePipelineVersionsAnswerWithASharedLibrary() throws Exception {
    sharedLibrary();

    mockMvc.perform(get("/api/v1/admin/search/status")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/admin/indexing/pipeline-versions")).andExpect(status().isOk());
  }

  private KnowledgeLibrary privateLibrary() {
    return create(
        KnowledgeLibrary.ownerOnly(
            Organization.DEFAULT_ID,
            "Privat ohne Modell " + UUID.randomUUID(),
            null,
            owner,
            SourceType.UPLOAD,
            null,
            null,
            null));
  }

  private KnowledgeLibrary sharedLibrary() {
    return create(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID, "Geteilt ohne Modell " + UUID.randomUUID(), null, owner));
  }

  private KnowledgeLibrary create(KnowledgeLibrary library) {
    KnowledgeLibrary saved =
        transactionTemplate.execute(
            status -> {
              KnowledgeLibrary stored = libraryRepository.save(library);
              shellService.registerCreated(
                  stored, owner, Map.of("name", stored.getName(), "sourceType", "UPLOAD"));
              return stored;
            });
    libraries.add(saved.getId());
    return saved;
  }
}
