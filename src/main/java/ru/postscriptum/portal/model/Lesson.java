package ru.postscriptum.portal.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;
import java.time.OffsetDateTime;

@Entity
@Table(name = "lessons")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private Enrollment enrollment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id", nullable = false)
    private User teacher;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "language_id", nullable = false)
    private Language language;

    @Column(name = "room_id")
    private Integer roomId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false)
    private LessonFormat format = LessonFormat.INDIVIDUAL;

    private String title;
    private String topic;

    @Column(name = "scheduled_at", nullable = false)
    private OffsetDateTime scheduledAt;

    @Column(name = "duration_min", nullable = false)
    private short durationMin = 60;

    @Column(name = "zoom_url")
    private String zoomUrl;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(nullable = false)
    private LessonStatus status = LessonStatus.PLANNED;

    @Column(name = "teacher_note")
    private String teacherNote;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "reschedule_count", nullable = false)
    private short rescheduleCount = 0;

    @Column(name = "original_date")
    private OffsetDateTime originalDate;

    @Column(name = "last_rescheduled_at")
    private OffsetDateTime lastRescheduledAt;

    @Column(name = "teacher_timezone")
    private String teacherTimezone;

    @Column(name = "student_timezone")
    private String studentTimezone;

    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}
