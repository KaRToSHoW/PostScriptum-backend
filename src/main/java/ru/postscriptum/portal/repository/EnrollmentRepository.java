package ru.postscriptum.portal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.postscriptum.portal.model.Enrollment;

import java.util.List;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    List<Enrollment> findByStudentId(Long studentId);

    List<Enrollment> findByTeacherId(Long teacherId);

    boolean existsByStudentIdAndTeacherId(Long studentId, Long teacherId);
}
