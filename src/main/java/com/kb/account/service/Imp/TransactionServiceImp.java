package com.kb.account.service.Imp;

import com.kb.account.dto.TransactionDTO;
import com.kb.account.exception.BadRequestException;
import com.kb.account.exception.ResourceNotFoundException;
import com.kb.account.model.Person;
import com.kb.account.model.Transaction;
import com.kb.account.model.TransactionType;
import com.kb.account.model.User;
import com.kb.account.repository.PersonRepository;
import com.kb.account.repository.TransactionRepository;
import com.kb.account.repository.UserRepository;
import com.kb.account.service.GoogleDriveService;
import com.kb.account.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionServiceImp implements TransactionService {
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final PersonRepository personRepository;
    private final GoogleDriveService googleDriveService;

    @Override
    @Transactional
    public TransactionDTO createTransaction(TransactionDTO dto) {
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", dto.getUserId()));
        Person person = personRepository.findById(dto.getPersonId())
                .orElseThrow(() -> new ResourceNotFoundException("Person", dto.getPersonId()));
        validatePersonBelongsToUser(person, user);

        Transaction txn = new Transaction();
        txn.setUser(user);
        txn.setPerson(person);
        txn.setAmount(dto.getAmount());
        txn.setType(dto.getType());
        txn.setNote(dto.getNote());

        Transaction saved = transactionRepository.save(txn);
        log.info("Transaction created: id={}, type={}", saved.getId(), dto.getType());
        return convertToDTO(saved);
    }

    @Override
    @Transactional
    public TransactionDTO createTransactionWithBill(TransactionDTO dto, MultipartFile billImage) {
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", dto.getUserId()));
        Person person = personRepository.findById(dto.getPersonId())
                .orElseThrow(() -> new ResourceNotFoundException("Person", dto.getPersonId()));
        validatePersonBelongsToUser(person, user);

        String fileId = googleDriveService.uploadFileToDrive(user, billImage);
        String shareLink = googleDriveService.getFileShareLink(user, fileId);

        Transaction txn = new Transaction();
        txn.setUser(user);
        txn.setPerson(person);
        txn.setAmount(dto.getAmount());
        txn.setType(dto.getType());
        txn.setNote(dto.getNote());
        txn.setBillPhotoUrl(shareLink);
        txn.setBillFileId(fileId);

        Transaction saved = transactionRepository.save(txn);
        log.info("Transaction created with bill: id={}, fileId={}", saved.getId(), fileId);
        return convertToDTO(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public TransactionDTO getTransactionById(String id) {
        return transactionRepository.findById(id)
                .map(this::convertToDTO)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionDTO> getAllTransactions() {
        return transactionRepository.findAll().stream()
                .map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionDTO> getTransactionsByUserId(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return transactionRepository.findByUser(user).stream()
                .map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransactionDTO> getTransactionsByUserIdAndType(Long userId, String type) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        TransactionType txnType;
        try {
            txnType = TransactionType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid type: " + type + ". Must be GIVE or RECEIVE");
        }
        return transactionRepository.findByUserAndType(user, txnType).stream()
                .map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public TransactionDTO updateTransaction(String id, TransactionDTO dto) {
        Transaction txn = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
        if (dto.getAmount() != null) txn.setAmount(dto.getAmount());
        if (dto.getType() != null) txn.setType(dto.getType());
        if (dto.getNote() != null) txn.setNote(dto.getNote());
        Transaction updated = transactionRepository.save(txn);
        log.info("Transaction updated: id={}", id);
        return convertToDTO(updated);
    }

    @Override
    @Transactional
    public TransactionDTO updateTransactionWithBill(String id, TransactionDTO dto, MultipartFile billImage) {
        Transaction txn = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
        User user = txn.getUser();

        if (txn.getBillFileId() != null) {
            try { googleDriveService.deleteFileFromDrive(user, txn.getBillFileId()); }
            catch (Exception e) { log.warn("Failed to delete old bill: {}", e.getMessage()); }
        }

        String fileId = googleDriveService.uploadFileToDrive(user, billImage);
        String shareLink = googleDriveService.getFileShareLink(user, fileId);

        if (dto.getAmount() != null) txn.setAmount(dto.getAmount());
        if (dto.getType() != null) txn.setType(dto.getType());
        if (dto.getNote() != null) txn.setNote(dto.getNote());
        txn.setBillPhotoUrl(shareLink);
        txn.setBillFileId(fileId);

        Transaction updated = transactionRepository.save(txn);
        log.info("Transaction updated with bill: id={}, fileId={}", id, fileId);
        return convertToDTO(updated);
    }

    @Override
    @Transactional
    public void deleteTransaction(String id) {
        Transaction txn = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
        if (txn.getBillFileId() != null) {
            try { googleDriveService.deleteFileFromDrive(txn.getUser(), txn.getBillFileId()); }
            catch (Exception e) { log.warn("Failed to delete bill: {}", e.getMessage()); }
        }
        transactionRepository.deleteById(id);
        log.info("Transaction deleted: id={}", id);
    }

    private void validatePersonBelongsToUser(Person person, User user) {
        if (!person.getUser().getId().equals(user.getId())) {
            throw new BadRequestException("Person " + person.getId() + " does not belong to user " + user.getId());
        }
    }

    private TransactionDTO convertToDTO(Transaction t) {
        return TransactionDTO.builder()
                .id(t.getId())
                .userId(t.getUser() != null ? t.getUser().getId() : null)
                .personId(t.getPerson() != null ? t.getPerson().getId() : null)
                .amount(t.getAmount())
                .type(t.getType())
                .note(t.getNote())
                .billPhotoUrl(t.getBillPhotoUrl())
                .billFileId(t.getBillFileId())
                .createdAt(t.getCreatedAt())
                .build();
    }
}
