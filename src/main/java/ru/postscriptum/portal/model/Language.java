package ru.postscriptum.portal.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "languages")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Language {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(length = 2, nullable = false, unique = true)
    private String code;

    @Column(name = "name_ru", nullable = false)
    private String nameRu;

    @Column(name = "flag_emoji")
    private String flagEmoji;
}
