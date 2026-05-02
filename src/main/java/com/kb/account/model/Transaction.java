package com.kb.account.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "transactions")
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @ManyToOne
    private Person person; // the other party

    @ManyToOne
    private User user; // owner of ledger

    private Double amount;

    private String type; // GIVE / RECEIVE

    private String note;
    private String billPhotoUrl;
    private String billFileId; // Google Drive file ID

    @CreatedDate
    private LocalDateTime createdAt;
}
