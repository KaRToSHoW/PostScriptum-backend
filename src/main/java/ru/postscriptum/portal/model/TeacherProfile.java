package ru.postscriptum.portal.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "teacher_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeacherProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    @Column(columnDefinition = "TEXT")
    private String bio;

    private String experience;

    private Double rating;

    private Integer reviewCount;

    private Boolean nativeSpeaker;

    @Column(length = 500)
    private String languages;

    @Column(length = 500)
    private String tags;

    @Column(length = 10)
    private String primaryLang;
}
