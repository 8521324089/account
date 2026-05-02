package com.kb.account.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.api.services.drive.model.File;
import com.kb.account.dto.BackupDTO;
import com.kb.account.dto.PersonDTO;
import com.kb.account.dto.TransactionDTO;
import com.kb.account.model.User;
import com.kb.account.repository.PersonRepository;
import com.kb.account.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackupService {

    private final PersonRepository personRepository;
    private final TransactionRepository transactionRepository;
    private final GoogleDriveService googleDriveService;

    @Transactional(readOnly = true)
    public BackupDTO createBackup(User user) {
        try {
            // Gather all user data
            List<PersonDTO> persons = personRepository.findByUser(user).stream()
                    .map(p -> PersonDTO.builder()
                            .id(p.getId()).name(p.getName()).phone(p.getPhone()).userId(user.getId())
                            .build())
                    .collect(Collectors.toList());

            List<TransactionDTO> transactions = transactionRepository.findByUser(user).stream()
                    .map(t -> TransactionDTO.builder()
                            .id(t.getId())
                            .userId(user.getId())
                            .personId(t.getPerson() != null ? t.getPerson().getId() : null)
                            .amount(t.getAmount())
                            .type(t.getType())
                            .note(t.getNote())
                            .billPhotoUrl(t.getBillPhotoUrl())
                            .billFileId(t.getBillFileId())
                            .createdAt(t.getCreatedAt())
                            .build())
                    .collect(Collectors.toList());

            Map<String, Object> backup = new HashMap<>();
            backup.put("userId", user.getId());
            backup.put("email", user.getEmail());
            backup.put("name", user.getName());
            backup.put("exportedAt", java.time.LocalDateTime.now().toString());
            backup.put("persons", persons);
            backup.put("transactions", transactions);

            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(backup);

            // Upload to Drive (this also deletes old backups)
            File uploaded = googleDriveService.uploadBackup(user, json);

            log.info("Backup created for user {}: fileId={}", user.getId(), uploaded.getId());

            return BackupDTO.builder()
                    .status("SUCCESS")
                    .message("Backup created with " + persons.size() + " contacts and " + transactions.size() + " transactions")
                    .driveFileId(uploaded.getId())
                    .driveLink(uploaded.getWebViewLink())
                    .build();

        } catch (Exception e) {
            log.error("Backup failed for user {}: {}", user.getId(), e.getMessage());
            return BackupDTO.builder()
                    .status("FAILED")
                    .message("Backup failed: " + e.getMessage())
                    .build();
        }
    }
}
