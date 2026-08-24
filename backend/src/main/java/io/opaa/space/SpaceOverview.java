package io.opaa.space;

/**
 * A space enriched with the overview card's figures (#682): how many libraries and how many of the
 * caller's own chats it holds. Domain counterpart of the generated {@code SpaceListResponse},
 * mapped by {@code io.opaa.api.SpaceResponseMapper}.
 */
public record SpaceOverview(Space space, int libraryCount, int chatCount) {}
