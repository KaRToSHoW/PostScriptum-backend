package ru.postscriptum.portal.dto;

import java.util.List;

public record TeacherDto(
        Long id,
        String name,
        String initials,
        String role,
        String flag,
        boolean nativeSpeaker,
        List<String> langs,
        double rating,
        int reviews,
        int students,
        String experience,
        String bio,
        String next,
        List<String> tags,
        boolean myTeacher
) {}
