package io.opaa.search;

import io.opaa.query.SearchedLibraryRef;
import java.util.List;

/**
 * One search without generation: the hits, best first, and the libraries the search actually ran
 * against. There is deliberately no answer field - the generation happens in the calling tool, with
 * its own model.
 */
public record SearchOutcome(List<SearchHit> hits, List<SearchedLibraryRef> searchedLibraries) {}
