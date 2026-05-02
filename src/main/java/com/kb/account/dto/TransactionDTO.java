package com.kb.account.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionDTO {
    private String id;
    private Long personId;
    private Long userId;
    private Double amount;
    private String type; // GIVE / RECEIVE
    private String note;
    private String billPhotoUrl;
    private String billFileId; // Google Drive file ID
    private LocalDateTime createdAt;
}

