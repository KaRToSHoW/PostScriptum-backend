package ru.postscriptum.portal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.postscriptum.portal.model.TeacherProfile;

import java.util.List;
import java.util.Optional;

public interface TeacherProfileRepository extends JpaRepository<TeacherProfile, Long> {

    Optional<TeacherProfile> findByUserId(Long userId);

    List<TeacherProfile> findAll();
}
