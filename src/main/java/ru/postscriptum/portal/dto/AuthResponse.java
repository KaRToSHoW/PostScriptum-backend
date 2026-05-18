package ru.postscriptum.portal.dto;

public record AuthResponse(
        String token,
        String role,
        String name,
        String initials,
        String subtitle
) {}
