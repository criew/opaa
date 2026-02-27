package io.opaa.api.dto;

import java.util.List;

public record QueryResponse(
    String answer, List<SourceReference> sources, QueryMetadata metadata, String conversationId) {}
