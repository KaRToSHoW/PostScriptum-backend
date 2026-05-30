package ru.postscriptum.portal.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "teacher_profiles")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class TeacherProfile {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "capacity_hours", nullable = false)
    private Short capacityHours = 20;

    @Column(name = "is_native", nullable = false)
    private boolean native_ = false;

    private BigDecimal rating;

    @Column(name = "workload_chip")
    private String workloadChip = "orange";
}
