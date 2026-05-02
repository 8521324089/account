package com.kb.account.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Collections;

@Slf4j
@Service
public class GoogleDriveService {

    @Value("${google.drive.credentials-path}")
    private String credentialsPath;

    @Value("${google.drive.folder-id}")
    private String driveFolderId;

    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String APPLICATION_NAME = "Account Management App";

    /**
     * Upload a file to Google Drive
     * @param file the multipart file to upload
     * @return the file ID of the uploaded file
     */
    public String uploadFileToDrive(MultipartFile file) {
        try {
            Drive driveService = getDriveService();
            
            File fileMetadata = new File();
            fileMetadata.setName(file.getOriginalFilename());
            fileMetadata.setParents(Collections.singletonList(driveFolderId));
            
            try (InputStream inputStream = file.getInputStream()) {
                com.google.api.client.http.FileContent mediaContent =
                        new com.google.api.client.http.InputStreamContent(
                                file.getContentType(),
                                inputStream,
                                file.getSize()
                        );
                
                File uploadedFile = driveService.files().create(fileMetadata, mediaContent)
                        .setFields("id, webViewLink, webContentLink")
                        .execute();
                
                log.info("File uploaded successfully: {} with ID: {}", 
                        file.getOriginalFilename(), uploadedFile.getId());
                return uploadedFile.getId();
            }
        } catch (GeneralSecurityException | IOException e) {
            log.error("Error uploading file to Google Drive: {}", e.getMessage());
            throw new RuntimeException("Failed to upload file to Google Drive: " + e.getMessage(), e);
        }
    }

    /**
     * Delete a file from Google Drive
     * @param fileId the ID of the file to delete
     */
    public void deleteFileFromDrive(String fileId) {
        try {
            Drive driveService = getDriveService();
            driveService.files().delete(fileId).execute();
            log.info("File deleted successfully from Google Drive: {}", fileId);
        } catch (GeneralSecurityException | IOException e) {
            log.error("Error deleting file from Google Drive: {}", e.getMessage());
            throw new RuntimeException("Failed to delete file from Google Drive: " + e.getMessage(), e);
        }
    }

    /**
     * Get the public sharing link of a file
     * @param fileId the ID of the file
     * @return the sharing link
     */
    public String getFileShareLink(String fileId) {
        try {
            Drive driveService = getDriveService();
            File file = driveService.files().get(fileId)
                    .setFields("webViewLink, webContentLink")
                    .execute();
            
            return file.getWebViewLink();
        } catch (GeneralSecurityException | IOException e) {
            log.error("Error getting file share link: {}", e.getMessage());
            throw new RuntimeException("Failed to get file share link: " + e.getMessage(), e);
        }
    }

    /**
     * Initialize and return Drive service
     */
    private Drive getDriveService() throws GeneralSecurityException, IOException {
        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                getCredentials()
        )
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /**
     * Get Google credentials from service account JSON file
     */
    private HttpRequestInitializer getCredentials() throws GeneralSecurityException, IOException {
        GoogleCredential credential = GoogleCredential.fromStream(
                getClass().getResourceAsStream("/" + credentialsPath)
        ).createScoped(Collections.singleton(DriveScopes.DRIVE));
        
        return credential;
    }
}

