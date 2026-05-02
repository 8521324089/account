package com.kb.account.service;

import com.kb.account.dto.TransactionDTO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface TransactionService {
    TransactionDTO createTransaction(TransactionDTO transactionDTO);
    TransactionDTO createTransactionWithBill(TransactionDTO transactionDTO, MultipartFile billImage);
    TransactionDTO getTransactionById(String id);
    List<TransactionDTO> getAllTransactions();
    List<TransactionDTO> getTransactionsByUserId(Long userId);
    List<TransactionDTO> getTransactionsByUserIdAndType(Long userId, String type);
    TransactionDTO updateTransaction(String id, TransactionDTO transactionDTO);
    TransactionDTO updateTransactionWithBill(String id, TransactionDTO transactionDTO, MultipartFile billImage);
    void deleteTransaction(String id);
}
