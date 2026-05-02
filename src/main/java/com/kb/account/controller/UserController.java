package com.kb.account.controller;

import com.kb.account.dto.UserDTO;
import com.kb.account.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService userService;

    /**
     * Get the current user's own profile.
     */
    @GetMapping("/me")
    public ResponseEntity<UserDTO> getMyProfile(@AuthenticationPrincipal OAuth2User principal) {
        String googleId = principal.getAttribute("sub");
        return ResponseEntity.ok(userService.getUserByGoogleId(googleId));
    }

    /**
     * Update the current user's profile (phone, upiId).
     */
    @PutMapping("/me")
    public ResponseEntity<UserDTO> updateMyProfile(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestBody UserDTO userDTO) {
        String googleId = principal.getAttribute("sub");
        UserDTO current = userService.getUserByGoogleId(googleId);
        return ResponseEntity.ok(userService.updateUser(current.getId(), userDTO));
    }
}
