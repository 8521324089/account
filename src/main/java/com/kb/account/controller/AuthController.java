package com.kb.account.controller;

import com.kb.account.dto.UserDTO;
import com.kb.account.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;

    /**
     * Returns the currently authenticated user's profile.
     * Called by the frontend after OAuth redirect.
     */
    @GetMapping("/me")
    public ResponseEntity<UserDTO> getCurrentUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        String googleId = principal.getAttribute("sub");
        UserDTO user = userService.getUserByGoogleId(googleId);
        return ResponseEntity.ok(user);
    }

    /**
     * Check if user is authenticated (used by frontend to detect session).
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> authStatus(@AuthenticationPrincipal OAuth2User principal) {
        return ResponseEntity.ok(Map.of("authenticated", principal != null));
    }
}
