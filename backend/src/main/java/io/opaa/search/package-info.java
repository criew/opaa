/**
 * The reading path without generation (#1720, ADR-0035, docs/features/external-access.md, "Was ein
 * Fremdzugang erreicht"): {@link io.opaa.search.SearchService} answers a question with the
 * retrieved passages instead of an answer, {@link io.opaa.search.PassageFetchService} hands out the
 * text behind one of them - the passage with its neighbours by default, the whole document only on
 * explicit request and only up to a character cap.
 *
 * <p>Neither of them ranks anything itself: both go through {@code
 * io.opaa.query.KnowledgeRetrieval}, the same entrance {@code POST /api/v1/query} uses, so there is
 * exactly one way to the data and it checks rights. The libraries a request may see come from
 * {@link io.opaa.search.SearchScopeSource}, whose only implementation today is the signed-in
 * person's readable set; the token-aware one arrives with #1718.
 */
package io.opaa.search;
