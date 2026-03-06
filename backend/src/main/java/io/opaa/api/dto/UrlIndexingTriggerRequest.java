package io.opaa.api.dto;

public record UrlIndexingTriggerRequest(
    String url, String proxy, String credentials, boolean insecureSsl) {}
