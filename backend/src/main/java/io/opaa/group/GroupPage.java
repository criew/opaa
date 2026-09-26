package io.opaa.group;

import java.util.List;

/** One page of the administration's group list and the number of matches over all pages. */
public record GroupPage(List<GroupOverview> items, long total, int page, int size) {}
