package com.kb.account.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.people.v1.PeopleService;
import com.google.api.services.people.v1.model.ListConnectionsResponse;
import com.google.api.services.people.v1.model.PhoneNumber;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.kb.account.dto.GoogleContactDTO;
import com.kb.account.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class GoogleContactsService {

    /**
     * Fetch the user's Google Contacts (name + phone).
     */
    public List<GoogleContactDTO> getContacts(User user) {
        List<GoogleContactDTO> contacts = new ArrayList<>();
        try {
            GoogleCredentials credentials = GoogleCredentials.create(new AccessToken(user.getAccessToken(), null));
            PeopleService peopleService = new PeopleService.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
            ).setApplicationName("Ledger App").build();

            ListConnectionsResponse response = peopleService.people().connections()
                    .list("people/me")
                    .setPersonFields("names,phoneNumbers")
                    .setPageSize(1000)
                    .execute();

            if (response.getConnections() != null) {
                for (var person : response.getConnections()) {
                    String name = null;
                    String phone = null;

                    if (person.getNames() != null && !person.getNames().isEmpty()) {
                        name = person.getNames().get(0).getDisplayName();
                    }
                    if (person.getPhoneNumbers() != null && !person.getPhoneNumbers().isEmpty()) {
                        PhoneNumber pn = person.getPhoneNumbers().get(0);
                        phone = pn.getCanonicalForm() != null ? pn.getCanonicalForm() : pn.getValue();
                    }

                    if (name != null) {
                        contacts.add(GoogleContactDTO.builder().name(name).phone(phone).build());
                    }
                }
            }
            log.info("Fetched {} Google contacts for user {}", contacts.size(), user.getId());
        } catch (GeneralSecurityException | IOException e) {
            log.error("Failed to fetch Google contacts: {}", e.getMessage());
            throw new RuntimeException("Failed to fetch Google contacts: " + e.getMessage(), e);
        }
        return contacts;
    }
}
