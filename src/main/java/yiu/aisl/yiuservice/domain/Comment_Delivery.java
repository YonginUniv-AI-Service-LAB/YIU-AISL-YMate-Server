package yiu.aisl.yiuservice.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import yiu.aisl.yiuservice.domain.state.ApplyState;
import yiu.aisl.yiuservice.domain.state.PostState;

import java.time.LocalDateTime;

@Entity
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment_Delivery {
    @Id // pk
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(unique = true)
    private Long dcId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "d_id", nullable = false)
    private Delivery delivery;

    @ManyToOne
    @JoinColumn(name = "student_id", nullable = false)
    private User user;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String contents;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false)
    private ApplyState state;

    @CreationTimestamp
    @Column
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column
    private LocalDateTime updatedAt;
}
