package io.opaa.api.dto;

public record SourceReference(
    String fileName, double relevanceScore, String excerpt, boolean cited) {}
