package io.opaa.auth.dto;

public record LoginResponse(String accessToken, long expiresIn) {}
