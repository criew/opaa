package io.opaa.search;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.TooManyRequestsException;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.indexing.metadata.MetadataFilterValidator;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.query.KnowledgeRetrieval;
import io.opaa.query.SearchScopeResolver;
import io.opaa.query.retrieval.RetrievalExplanation;
import io.opaa.query.retrieval.RetrievalPipelineResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * That the search actually asks quota and alert, and asks them <em>first</em>. The integration test
 * cannot show it: its only {@link SearchScopeSource} is the person's, whose requests carry no token
 * and for which both calls are no-ops - so removing either one would still leave a green suite
 * until #1718 wires the token in, which is exactly when the guard is needed.
 */
class SearchServiceQuotaWiringTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final UUID LIBRARY_ID = UUID.randomUUID();

  private KnowledgeRetrieval retrieval;
  private SearchScopeSource scopeSource;
  private AccessTokenQuota quota;
  private MassRetrievalAlarm alarm;
  private SearchService service;

  @BeforeEach
  void setUp() {
    retrieval = mock(KnowledgeRetrieval.class);
    scopeSource = mock(SearchScopeSource.class);
    quota = mock(AccessTokenQuota.class);
    alarm = mock(MassRetrievalAlarm.class);
    KnowledgeLibraryRepository libraries = mock(KnowledgeLibraryRepository.class);
    when(libraries.findAllById(any())).thenReturn(List.of());
    when(retrieval.retrieve(any(), any(), any(), any(), any()))
        .thenReturn(
            new RetrievalPipelineResult(
                List.of(), List.of(), new RetrievalExplanation(List.of()), true));
    service =
        new SearchService(
            retrieval,
            scopeSource,
            new SearchScopeResolver(mock(io.opaa.chat.ChatService.class)),
            mock(MetadataFilterValidator.class),
            mock(SearchHitAssembler.class),
            new SearchProperties(10, 50, 1500, 200_000, 1),
            quota,
            alarm,
            libraries);
  }

  private static CurrentUser caller() {
    return CurrentUser.of(UUID.randomUUID(), ORGANIZATION_ID, SystemRole.USER, null);
  }

  @Test
  void quotaAndAlertSeeTheTokenOfTheRequest() {
    UUID tokenId = UUID.randomUUID();
    when(scopeSource.scopeFor(any()))
        .thenReturn(new SearchRequestScope(Set.of(LIBRARY_ID), tokenId));

    service.search(caller(), "Frage?", List.of(), MetadataFilter.NONE, null);

    verify(quota).requireWithinQuota(tokenId);
    verify(alarm).record(ORGANIZATION_ID, tokenId);
  }

  @Test
  void anExhaustedQuotaStopsTheCallBeforeTheRetrievalRuns() {
    UUID tokenId = UUID.randomUUID();
    when(scopeSource.scopeFor(any()))
        .thenReturn(new SearchRequestScope(Set.of(LIBRARY_ID), tokenId));
    doThrow(new TooManyRequestsException(60)).when(quota).requireWithinQuota(tokenId);

    assertThatThrownBy(
            () -> service.search(caller(), "Frage?", List.of(), MetadataFilter.NONE, null))
        .isInstanceOf(TooManyRequestsException.class);

    verifyNoInteractions(retrieval);
  }

  @Test
  void theListingCountsAgainstTheSameQuota() {
    UUID tokenId = UUID.randomUUID();
    when(scopeSource.scopeFor(any()))
        .thenReturn(new SearchRequestScope(Set.of(LIBRARY_ID), tokenId));

    service.libraries(caller());

    verify(quota).requireWithinQuota(tokenId);
    verify(alarm).record(ORGANIZATION_ID, tokenId);
  }

  @Test
  void aSignedInPersonCarriesNoTokenIntoQuotaAndAlert() {
    when(scopeSource.scopeFor(any())).thenReturn(SearchRequestScope.ofPerson(Set.of(LIBRARY_ID)));

    service.search(caller(), "Frage?", List.of(), MetadataFilter.NONE, null);

    verify(quota).requireWithinQuota(null);
    verify(alarm).record(ORGANIZATION_ID, null);
    // The no-op is the components' own contract (see their unit tests); what matters here is that
    // the call happens at all and carries the absent token rather than being skipped.
    verify(retrieval).retrieve(any(), any(), any(), any(), any());
  }
}
