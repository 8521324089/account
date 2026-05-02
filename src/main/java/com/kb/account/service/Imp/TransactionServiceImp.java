package com.kb.account.service.Imp;

import com.kb.account.dto.TransactionDTO;
import com.kb.account.model.Person;
import com.kb.account.model.Transaction;
import com.kb.account.model.User;
import com.kb.account.repository.PersonRepository;
import com.kb.account.repository.TransactionRepository;
import com.kb.account.repository.UserRepository;
import com.kb.account.service.GoogleDriveService;
import com.kb.account.service.TransactionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
    public TransactionDTO createTransaction(TransactionDTO transactionDTO) {
        User user = userRepository.findById(transactionDTO.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Person person = personRepository.findById(transactionDTO.getPersonId())
                .orElseThrow(() -> new RuntimeException("Person not found"));
        
        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setPerson(person);
        transaction.setAmount(transactionDTO.getAmount());
        transaction.setType(transactionDTO.getType());
        transaction.setNote(transactionDTO.getNote());
        transaction.setBillPhotoUrl(transactionDTO.getBillPhotoUrl());
        
        Transaction savedTransaction = transactionRepository.save(transaction);
        return convertToDTO(savedTransaction);
    }

    @Override
    public TransactionDTO createTransactionWithBill(TransactionDTO transactionDTO, MultipartFile billImage) {
        User user = userRepository.findById(transactionDTO.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        Person person = personRepository.findById(transactionDTO.getPersonId())
                .orElseThrow(() -> new RuntimeException("Person not found"));
        
        // Upload file to Google Drive
        String fileId = googleDriveService.uploadFileToDrive(billImage);
        String shareLink = googleDriveService.getFileShareLink(fileId);
        
        Transaction transaction = new Transaction();
        transaction.setUser(user);
        transaction.setPerson(person);
        transaction.setAmount(transactionDTO.getAmount());
        transaction.setType(transactionDTO.getType());
        transaction.setNote(transactionDTO.getNote());
        transaction.setBillPhotoUrl(shareLink);
        transaction.setBillFileId(fileId); // Store the Google Drive file ID
        
        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction created with bill image. File ID: {}", fileId);
        return convertToDTO(savedTransaction);
    }

    @Override
    public TransactionDTO getTransactionById(String id) {
        return transactionRepository.findById(id)
                .map(this::convertToDTO)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
    }

    @Override
    public List<TransactionDTO> getAllTransactions() {
        return transactionRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<TransactionDTO> getTransactionsByUserId(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        return transactionRepository.findByUser(user).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public List<TransactionDTO> getTransactionsByUserIdAndType(Long userId, String type) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        return transactionRepository.findByUserAndType(user, type).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public TransactionDTO updateTransaction(String id, TransactionDTO transactionDTO) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        
        if (transactionDTO.getAmount() != null) transaction.setAmount(transactionDTO.getAmount());
        if (transactionDTO.getType() != null) transaction.setType(transactionDTO.getType());
        if (transactionDTO.getNote() != null) transaction.setNote(transactionDTO.getNote());
        if (transactionDTO.getBillPhotoUrl() != null) transaction.setBillPhotoUrl(transactionDTO.getBillPhotoUrl());
        
        Transaction updatedTransaction = transactionRepository.save(transaction);
        return convertToDTO(updatedTransaction);
    }

    @Override
    public TransactionDTO updateTransactionWithBill(String id, TransactionDTO transactionDTO, MultipartFile billImage) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        
        // Delete old file from Google Drive if it exists
        if (transaction.getBillFileId() != null) {
            try {
                googleDriveService.deleteFileFromDrive(transaction.getBillFileId());
                log.info("Old bill file deleted from Google Drive: {}", transaction.getBillFileId());
            } catch (Exception e) {
                log.warn("Failed to delete old bill file: {}", e.getMessage());
            }
        }
        
        // Upload new file to Google Drive
        String fileId = googleDriveService.uploadFileToDrive(billImage);
        String shareLink = googleDriveService.getFileShareLink(fileId);
        
        if (transactionDTO.getAmount() != null) transaction.setAmount(transactionDTO.getAmount());
        if (transactionDTO.getType() != null) transaction.setType(transactionDTO.getType());
        if (transactionDTO.getNote() != null) transaction.setNote(transactionDTO.getNote());
        
        transaction.setBillPhotoUrl(shareLink);
        transaction.setBillFileId(fileId);
        
        Transaction updatedTransaction = transactionRepository.save(transaction);
        log.info("Transaction updated with new bill image. File ID: {}", fileId);
        return convertToDTO(updatedTransaction);
    }

    @Override
    public void deleteTransaction(String id) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        
        // Delete bill file from Google Drive if it exists
        if (transaction.getBillFileId() != null) {
            try {
                googleDriveService.deleteFileFromDrive(transaction.getBillFileId());
                log.info("Bill file deleted from Google Drive: {}", transaction.getBillFileId());
            } catch (Exception e) {
                log.warn("Failed to delete bill file: {}", e.getMessage());
            }
        }
        
        transactionRepository.deleteById(id);
    }

    private TransactionDTO convertToDTO(Transaction transaction) {
        return TransactionDTO.builder()
                .id(transaction.getId())
                .userId(transaction.getUser() != null ? transaction.getUser().getId() : null)
                .personId(transaction.getPerson() != null ? transaction.getPerson().getId() : null)
                .amount(transaction.getAmount())
                .type(transaction.getType())
                .note(transaction.getNote())
                .billPhotoUrl(transaction.getBillPhotoUrl())
                .billFileId(transaction.getBillFileId())
                .createdAt(transaction.getCreatedAt())
                .build();
    }
}
