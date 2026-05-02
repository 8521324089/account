package com.kb.account.controller;

import com.kb.account.dto.GoogleContactDTO;
import com.kb.account.dto.PersonDTO;
import com.kb.account.service.GoogleContactsService;
import com.kb.account.service.PersonService;
import com.kb.account.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/persons")
public class PersonController {
    private final PersonService personService;
    private final UserService userService;
    private final GoogleContactsService googleContactsService;

    @PostMapping
    public ResponseEntity<PersonDTO> createPerson(
            @AuthenticationPrincipal OAuth2User principal,
            @Valid @RequestBody PersonDTO personDTO) {
        Long userId = getUserId(principal);
        personDTO.setUserId(userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(personService.createPerson(personDTO));
    }

    @GetMapping
    public ResponseEntity<List<PersonDTO>> getMyPersons(@AuthenticationPrincipal OAuth2User principal) {
        Long userId = getUserId(principal);
        return ResponseEntity.ok(personService.getPersonsByUserId(userId));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PersonDTO> updatePerson(@PathVariable Long id, @RequestBody PersonDTO personDTO) {
        return ResponseEntity.ok(personService.updatePerson(id, personDTO));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePerson(@PathVariable Long id) {
        personService.deletePerson(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Fetch Google Contacts for the current user.
     */
    @GetMapping("/google-contacts")
    public ResponseEntity<List<GoogleContactDTO>> getGoogleContacts(@AuthenticationPrincipal OAuth2User principal) {
        String googleId = principal.getAttribute("sub");
        var user = userService.getUserEntityByGoogleId(googleId);
        return ResponseEntity.ok(googleContactsService.getContacts(user));
    }

    private Long getUserId(OAuth2User principal) {
        String googleId = principal.getAttribute("sub");
        return userService.getUserByGoogleId(googleId).getId();
    }
}
