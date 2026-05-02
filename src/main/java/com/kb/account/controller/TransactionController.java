package com.kb.account.controller;

import com.kb.account.dto.TransactionDTO;
import com.kb.account.service.TransactionService;
import com.kb.account.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final TransactionService transactionService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<TransactionDTO> createTransaction(
            @AuthenticationPrincipal OAuth2User principal,
            @Valid @RequestBody TransactionDTO dto) {
        dto.setUserId(getUserId(principal));
        return ResponseEntity.status(HttpStatus.CREATED).body(transactionService.createTransaction(dto));
    }

    @PostMapping("/with-bill")
    public ResponseEntity<TransactionDTO> createTransactionWithBill(
            @AuthenticationPrincipal OAuth2User principal,
            @Valid @RequestPart("transaction") TransactionDTO dto,
            @RequestPart("billImage") MultipartFile billImage) {
        dto.setUserId(getUserId(principal));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(transactionService.createTransactionWithBill(dto, billImage));
    }

    @GetMapping
    public ResponseEntity<List<TransactionDTO>> getMyTransactions(@AuthenticationPrincipal OAuth2User principal) {
        return ResponseEntity.ok(transactionService.getTransactionsByUserId(getUserId(principal)));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<TransactionDTO>> getMyTransactionsByType(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable String type) {
        return ResponseEntity.ok(transactionService.getTransactionsByUserIdAndType(getUserId(principal), type));
    }

    @GetMapping("/{id}")
    public ResponseEntity<TransactionDTO> getTransactionById(@PathVariable String id) {
        return ResponseEntity.ok(transactionService.getTransactionById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TransactionDTO> updateTransaction(
            @PathVariable String id, @RequestBody TransactionDTO dto) {
        return ResponseEntity.ok(transactionService.updateTransaction(id, dto));
    }

    @PutMapping("/{id}/with-bill")
    public ResponseEntity<TransactionDTO> updateTransactionWithBill(
            @PathVariable String id,
            @RequestPart("transaction") TransactionDTO dto,
            @RequestPart("billImage") MultipartFile billImage) {
        return ResponseEntity.ok(transactionService.updateTransactionWithBill(id, dto, billImage));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTransaction(@PathVariable String id) {
        transactionService.deleteTransaction(id);
        return ResponseEntity.noContent().build();
    }

    private Long getUserId(OAuth2User principal) {
        String googleId = principal.getAttribute("sub");
        return userService.getUserByGoogleId(googleId).getId();
    }
}
