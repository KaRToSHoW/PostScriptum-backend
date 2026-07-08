package ru.postscriptum.portal.dto;

import java.util.List;

public record TeacherStudentDto(
        Long id,
        String name,
        String initials,
        String email,
        List<String> langs,       // ["Французский", "Английский"]
        List<String> langCodes,   // ["fr", "en"]
        String status,
        String nextLesson,
        Integer lessonsLeft,
        String avatarUrl
) {}
