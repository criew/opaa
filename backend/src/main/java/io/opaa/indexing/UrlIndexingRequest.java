package io.opaa.indexing;

public record UrlIndexingRequest(
    String url, String proxy, String credentials, boolean insecureSsl) {}
