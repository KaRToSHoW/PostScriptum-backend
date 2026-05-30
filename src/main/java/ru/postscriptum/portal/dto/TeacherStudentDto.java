package ru.postscriptum.portal.dto;

public record TeacherStudentDto(
        Long id,
        String name,
        String initials,
        String language,
        String lang,
        String level,
        int progress,
        String status,
        String nextLesson
) {}
