package ru.postscriptum.portal.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.postscriptum.portal.dto.EnrollRequest;
import ru.postscriptum.portal.dto.TeacherDto;
import ru.postscriptum.portal.dto.TeacherStudentDto;
import ru.postscriptum.portal.model.*;
import ru.postscriptum.portal.repository.EnrollmentRepository;
import ru.postscriptum.portal.repository.LessonRepository;
import ru.postscriptum.portal.repository.TeacherProfileRepository;
import ru.postscriptum.portal.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
// Map used for LANG_NAME lookup

@Service
@RequiredArgsConstructor
public class TeacherService {

    private final UserRepository userRepository;
    private final TeacherProfileRepository teacherProfileRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;

    private static final Map<String, String> LANG_NAME = Map.of(
            "fr", "Французский",
            "en", "Английский",
            "de", "Немецкий",
            "es", "Испанский",
            "it", "Итальянский",
            "pt", "Португальский"
    );

    private static final String[] RU_DAY_ABBR = {"Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};

    // ----------------------------------------------------------------
    // Format a LocalDateTime into a human-readable "next lesson" string
    // ----------------------------------------------------------------
    private String formatNextLesson(LocalDateTime dt) {
        if (dt == null) return null;
        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm");
        String time = dt.format(timeFmt);
        LocalDate today = LocalDate.now();
        LocalDate lessonDate = dt.toLocalDate();
        if (lessonDate.equals(today)) {
            return "Сегодня · " + time;
        } else if (lessonDate.equals(today.plusDays(1))) {
            return "Завтра · " + time;
        } else {
            // 1=Monday … 7=Sunday in DayOfWeek; map to our RU_DAY_ABBR (Sun=0 index)
            int dow = dt.getDayOfWeek().getValue(); // 1=Mon, 7=Sun
            int idx = dow % 7; // Mon→1, …, Sat→6, Sun→0
            return RU_DAY_ABBR[idx] + " · " + time;
        }
    }

    // ----------------------------------------------------------------
    // List all teachers visible to the caller
    // ----------------------------------------------------------------
    public List<TeacherDto> listTeachers(String currentUserEmail) {
        List<User> teachers = userRepository.findAll().stream()
                .filter(u -> u.getRole() == UserRole.TEACHER)
                .toList();

        // Build profile map: userId → profile
        Map<Long, TeacherProfile> profileMap = teacherProfileRepository.findAll().stream()
                .collect(Collectors.toMap(p -> p.getUser().getId(), Function.identity()));

        // Determine which teachers the current user is already enrolled with
        User currentUser = null;
        List<Long> enrolledTeacherIds = Collections.emptyList();
        if (currentUserEmail != null) {
            Optional<User> maybeUser = userRepository.findByEmail(currentUserEmail);
            if (maybeUser.isPresent() && maybeUser.get().getRole() == UserRole.STUDENT) {
                currentUser = maybeUser.get();
                enrolledTeacherIds = enrollmentRepository.findByStudentId(currentUser.getId())
                        .stream()
                        .map(e -> e.getTeacher().getId())
                        .toList();
            }
        }

        final User finalCurrentUser = currentUser;
        final List<Long> finalEnrolledTeacherIds = enrolledTeacherIds;

        return teachers.stream().map(teacher -> {
            TeacherProfile profile = profileMap.get(teacher.getId());

            String flag = profile != null && profile.getPrimaryLang() != null ? profile.getPrimaryLang() : "fr";
            boolean nativeSpeaker = profile != null && Boolean.TRUE.equals(profile.getNativeSpeaker());
            List<String> langs = profile != null && profile.getLanguages() != null
                    ? Arrays.asList(profile.getLanguages().split(","))
                    : Collections.emptyList();
            double rating = profile != null && profile.getRating() != null ? profile.getRating() : 0.0;
            int reviews = profile != null && profile.getReviewCount() != null ? profile.getReviewCount() : 0;
            String experience = profile != null ? profile.getExperience() : null;
            String bio = profile != null ? profile.getBio() : null;
            List<String> tags = profile != null && profile.getTags() != null
                    ? Arrays.asList(profile.getTags().split(","))
                    : Collections.emptyList();

            int students = enrollmentRepository.findByTeacherId(teacher.getId()).size();

            boolean myTeacher = finalEnrolledTeacherIds.contains(teacher.getId());

            // next lesson for this student with this teacher
            String next = null;
            if (finalCurrentUser != null && myTeacher) {
                next = lessonRepository.findFirstByTeacherIdAndStudentIdAndStatusOrderByScheduledAtAsc(
                        teacher.getId(), finalCurrentUser.getId(), LessonStatus.PLANNED)
                        .map(l -> formatNextLesson(l.getScheduledAt()))
                        .orElse(null);
            }

            String role = teacher.getSubtitle() != null ? teacher.getSubtitle() : "Преподаватель";

            return new TeacherDto(
                    teacher.getId(),
                    teacher.getName(),
                    teacher.getInitials(),
                    role,
                    flag,
                    nativeSpeaker,
                    langs,
                    rating,
                    reviews,
                    students,
                    experience,
                    bio,
                    next,
                    tags,
                    myTeacher
            );
        }).toList();
    }

    // ----------------------------------------------------------------
    // Single teacher by id
    // ----------------------------------------------------------------
    public TeacherDto getTeacher(Long teacherId, String currentUserEmail) {
        return listTeachers(currentUserEmail).stream()
                .filter(t -> t.id().equals(teacherId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + teacherId));
    }

    // ----------------------------------------------------------------
    // Students enrolled with the given teacher
    // ----------------------------------------------------------------
    public List<TeacherStudentDto> getMyStudents(String teacherEmail) {
        User teacher = userRepository.findByEmail(teacherEmail)
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + teacherEmail));

        List<Enrollment> enrollments = enrollmentRepository.findByTeacherId(teacher.getId());

        return enrollments.stream().map(enrollment -> {
            User student = enrollment.getStudent();

            String next = lessonRepository
                    .findFirstByTeacherIdAndStudentIdAndStatusOrderByScheduledAtAsc(
                            teacher.getId(), student.getId(), LessonStatus.PLANNED)
                    .map(l -> formatNextLesson(l.getScheduledAt()))
                    .orElse(null);

            String langCode = enrollment.getLanguage();
            String langName = LANG_NAME.getOrDefault(langCode, langCode);

            return new TeacherStudentDto(
                    student.getId(),
                    student.getName(),
                    student.getInitials(),
                    langName,
                    langCode,
                    enrollment.getLevel(),
                    enrollment.getProgress() != null ? enrollment.getProgress() : 0,
                    enrollment.getStatus().name(),
                    next
            );
        }).toList();
    }

    // ----------------------------------------------------------------
    // Enroll a student with a teacher
    // ----------------------------------------------------------------
    public void enroll(String studentEmail, EnrollRequest req) {
        User student = userRepository.findByEmail(studentEmail)
                .orElseThrow(() -> new RuntimeException("Student not found: " + studentEmail));
        User teacher = userRepository.findById(req.teacherId())
                .orElseThrow(() -> new RuntimeException("Teacher not found: " + req.teacherId()));

        if (!enrollmentRepository.existsByStudentIdAndTeacherId(student.getId(), teacher.getId())) {
            enrollmentRepository.save(Enrollment.builder()
                    .student(student)
                    .teacher(teacher)
                    .language(req.language())
                    .level(req.level())
                    .status(EnrollmentStatus.ACTIVE)
                    .startDate(LocalDate.now())
                    .progress(0)
                    .build());
        }
    }
}
