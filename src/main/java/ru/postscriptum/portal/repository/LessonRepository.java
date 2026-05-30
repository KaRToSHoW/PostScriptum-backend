package ru.postscriptum.portal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.postscriptum.portal.model.Lesson;
import ru.postscriptum.portal.model.LessonStatus;

import java.util.List;
import java.util.Optional;

public interface LessonRepository extends JpaRepository<Lesson, Long> {

    List<Lesson> findByTeacherIdOrderByScheduledAtAsc(Long teacherId);

    List<Lesson> findByStudentIdOrderByScheduledAtAsc(Long studentId);

    Optional<Lesson> findFirstByTeacherIdAndStudentIdAndStatusOrderByScheduledAtAsc(
            Long teacherId, Long studentId, LessonStatus status);
}
