package com.servicehub.model.dto;

/** Public provider projection: never loads or exposes account credentials. */
public record ProviderResponse(Long id, String fullName, String avatarUrl) { }
