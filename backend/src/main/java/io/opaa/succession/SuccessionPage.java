package io.opaa.succession;

import java.util.List;

/** One page of one tab of the operational list, in the fixed order "oldest first". */
public record SuccessionPage(
    List<SuccessionEntry> entries, int page, int size, int totalElements, int totalPages) {}
