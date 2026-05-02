package com.kb.account.controller;

import com.kb.account.dto.BackupDTO;
import com.kb.account.service.BackupService;
import com.kb.account.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/backup")
public class BackupController {

    private final BackupService backupService;
    private final UserService userService;

    @PostMapping
    public ResponseEntity<BackupDTO> createBackup(@AuthenticationPrincipal OAuth2User principal) {
        String googleId = principal.getAttribute("sub");
        var user = userService.getUserEntityByGoogleId(googleId);
        return ResponseEntity.ok(backupService.createBackup(user));
    }
}
