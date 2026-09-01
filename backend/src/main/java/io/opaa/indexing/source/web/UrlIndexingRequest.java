package io.opaa.indexing.source.web;

public record UrlIndexingRequest(
    String url, String proxy, String credentials, boolean insecureSsl) {}
