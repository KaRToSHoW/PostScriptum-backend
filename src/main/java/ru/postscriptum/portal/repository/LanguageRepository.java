package ru.postscriptum.portal.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.postscriptum.portal.model.Language;
import java.util.Optional;

public interface LanguageRepository extends JpaRepository<Language, Integer> {
    Optional<Language> findByCode(String code);
}
