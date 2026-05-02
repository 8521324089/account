package com.kb.account.controller;

import com.kb.account.dto.TransactionDTO;
import com.kb.account.service.TransactionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final TransactionService transactionService;

    @PostMapping
    public ResponseEntity<TransactionDTO> createTransaction(@RequestBody TransactionDTO transactionDTO) {
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.createTransaction(transactionDTO));
    }

    @PostMapping("/with-bill")
    public ResponseEntity<TransactionDTO> createTransactionWithBill(
            @RequestPart("transaction") TransactionDTO transactionDTO,
            @RequestPart("billImage") MultipartFile billImage) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.createTransactionWithBill(transactionDTO, billImage));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionDTO> getTransactionById(@PathVariable String id) {
        return ResponseEntity.ok(transactionService.getTransactionById(id));
    }

    @GetMapping
    public ResponseEntity<List<TransactionDTO>> getAllTransactions() {
        return ResponseEntity.ok(transactionService.getAllTransactions());
    }

    @GetMapping("/user/{userId}")
    public ResponseEntity<List<TransactionDTO>> getTransactionsByUserId(@PathVariable Long userId) {
        return ResponseEntity.ok(transactionService.getTransactionsByUserId(userId));
    }

    @GetMapping("/user/{userId}/type/{type}")
    public ResponseEntity<List<TransactionDTO>> getTransactionsByUserIdAndType(
            @PathVariable Long userId,
            @PathVariable String type) {
        return ResponseEntity.ok(transactionService.getTransactionsByUserIdAndType(userId, type));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionDTO> updateTransaction(
            @PathVariable String id,
            @RequestBody TransactionDTO transactionDTO) {
        return ResponseEntity.ok(transactionService.updateTransaction(id, transactionDTO));
    }

    @PutMapping("/{id}/with-bill")
    public ResponseEntity<TransactionDTO> updateTransactionWithBill(
            @PathVariable String id,
            @RequestPart("transaction") TransactionDTO transactionDTO,
            @RequestPart("billImage") MultipartFile billImage) {
        return ResponseEntity.ok(transactionService.updateTransactionWithBill(id, transactionDTO, billImage));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable String id) {
        transactionService.deleteTransaction(id);
        return ResponseEntity.noContent().build();
    }
}
