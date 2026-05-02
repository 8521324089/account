package com.kb.account.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.InputStreamContent;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.kb.account.exception.FileUploadException;
import com.kb.account.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class GoogleDriveService {

    private static final String APP_NAME = "Ledger App";
    private static final String BACKUP_FOLDER_NAME = "LedgerApp_Backups";

    /**
     * Build a Drive service using the user's OAuth access token.
     */
    private Drive getDriveService(User user) {
        try {
            GoogleCredentials credentials = GoogleCredentials.create(new AccessToken(user.getAccessToken(), null));
            return new Drive.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
            ).setApplicationName(APP_NAME).build();
        } catch (GeneralSecurityException | IOException e) {
            throw new FileUploadException("Failed to initialize Google Drive service", e);
        }
    }

    /**
     * Upload a bill image to the user's Google Drive.
     */
    public String uploadFileToDrive(User user, MultipartFile file) {
        try {
            Drive drive = getDriveService(user);
            String folderId = getOrCreateAppFolder(drive);

            File meta = new File();
            meta.setName(file.getOriginalFilename());
            meta.setParents(Collections.singletonList(folderId));

            try (InputStream is = file.getInputStream()) {
                InputStreamContent content = new InputStreamContent(file.getContentType(), is);
                content.setLength(file.getSize());
                File uploaded = drive.files().create(meta, content)
                        .setFields("id, webViewLink").execute();
                log.info("Bill uploaded to Drive: {}", uploaded.getId());
                return uploaded.getId();
            }
        } catch (IOException e) {
            throw new FileUploadException("Failed to upload file to Google Drive", e);
        }
    }

    /**
     * Get the web view link for a file.
     */
    public String getFileShareLink(User user, String fileId) {
        try {
            Drive drive = getDriveService(user);
            File file = drive.files().get(fileId).setFields("webViewLink").execute();
            return file.getWebViewLink();
        } catch (IOException e) {
            throw new FileUploadException("Failed to get file link", e);
        }
    }

    /**
     * Delete a file from the user's Drive.
     */
    public void deleteFileFromDrive(User user, String fileId) {
        try {
            Drive drive = getDriveService(user);
            drive.files().delete(fileId).execute();
            log.info("File deleted from Drive: {}", fileId);
        } catch (IOException e) {
            log.warn("Failed to delete file from Drive: {}", e.getMessage());
        }
    }

    /**
     * Upload a backup JSON to the user's Drive, then delete all previous backups.
     */
    public File uploadBackup(User user, String jsonContent) {
        try {
            Drive drive = getDriveService(user);
            String folderId = getOrCreateBackupFolder(drive);

            // 1. Upload new backup
            File meta = new File();
            meta.setName("ledger_backup_" + System.currentTimeMillis() + ".json");
            meta.setParents(Collections.singletonList(folderId));

            byte[] bytes = jsonContent.getBytes(StandardCharsets.UTF_8);
            InputStreamContent content = new InputStreamContent(
                    "application/json", new ByteArrayInputStream(bytes));
            content.setLength(bytes.length);

            File uploaded = drive.files().create(meta, content)
                    .setFields("id, webViewLink").execute();
            log.info("Backup uploaded: {}", uploaded.getId());

            // 2. Delete all previous backups in the folder (except the one just uploaded)
            FileList existing = drive.files().list()
                    .setQ("'" + folderId + "' in parents and trashed = false")
                    .setFields("files(id, name)").execute();

            if (existing.getFiles() != null) {
                for (File f : existing.getFiles()) {
                    if (!f.getId().equals(uploaded.getId())) {
                        try {
                            drive.files().delete(f.getId()).execute();
                            log.info("Old backup deleted: {}", f.getName());
                        } catch (IOException ex) {
                            log.warn("Failed to delete old backup {}: {}", f.getName(), ex.getMessage());
                        }
                    }
                }
            }

            return uploaded;
        } catch (IOException e) {
            throw new FileUploadException("Failed to upload backup to Google Drive", e);
        }
    }

    /**
     * Find or create the app folder for bill images.
     */
    private String getOrCreateAppFolder(Drive drive) throws IOException {
        return getOrCreateFolder(drive, "LedgerApp_Bills");
    }

    /**
     * Find or create the backup folder.
     */
    private String getOrCreateBackupFolder(Drive drive) throws IOException {
        return getOrCreateFolder(drive, BACKUP_FOLDER_NAME);
    }

    private String getOrCreateFolder(Drive drive, String folderName) throws IOException {
        FileList result = drive.files().list()
                .setQ("name = '" + folderName + "' and mimeType = 'application/vnd.google-apps.folder' and trashed = false")
                .setFields("files(id)").execute();

        List<File> files = result.getFiles();
        if (files != null && !files.isEmpty()) {
            return files.get(0).getId();
        }

        File folderMeta = new File();
        folderMeta.setName(folderName);
        folderMeta.setMimeType("application/vnd.google-apps.folder");
        File folder = drive.files().create(folderMeta).setFields("id").execute();
        log.info("Created Drive folder '{}': {}", folderName, folder.getId());
        return folder.getId();
    }
}
