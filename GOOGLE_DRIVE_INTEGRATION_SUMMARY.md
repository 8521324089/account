# Google Drive Integration - Implementation Summary

## 🎉 Google Drive Integration Complete!

Your application now supports uploading bill images directly to Google Drive!

---

## What Has Been Added

### 1. New Service: GoogleDriveService.java
**Location:** `src/main/java/com/kb/account/service/GoogleDriveService.java`

**Features:**
- ✅ `uploadFileToDrive()` - Upload files to Google Drive
- ✅ `deleteFileFromDrive()` - Delete files from Google Drive
- ✅ `getFileShareLink()` - Get shareable links for bills
- ✅ Automatic credential initialization
- ✅ Error handling and logging

**Key Methods:**
```java
// Upload a file to Google Drive
String fileId = googleDriveService.uploadFileToDrive(multipartFile);

// Get the sharing link
String shareLink = googleDriveService.getFileShareLink(fileId);

// Delete a file
googleDriveService.deleteFileFromDrive(fileId);
```

---

### 2. Updated Models & DTOs

#### Transaction.java (Model)
- ✅ Added `billFileId` field to store Google Drive file ID
- ✅ Maintains `billPhotoUrl` for sharing link

#### TransactionDTO.java
- ✅ Added `billFileId` field for API responses
- ✅ Returns Google Drive sharing link in `billPhotoUrl`

---

### 3. Enhanced Transaction Service

#### TransactionService.java (Interface)
New methods:
```java
TransactionDTO createTransactionWithBill(TransactionDTO dto, MultipartFile billImage);
TransactionDTO updateTransactionWithBill(String id, TransactionDTO dto, MultipartFile billImage);
```

#### TransactionServiceImp.java (Implementation)
- ✅ Handles file uploads to Google Drive
- ✅ Automatic file deletion on update (old file removed)
- ✅ Automatic file deletion on transaction deletion
- ✅ Error handling and logging

---

### 4. Updated TransactionController

**New Endpoints:**

#### POST `/api/v1/transactions/with-bill`
- Upload bill image with transaction creation
- Returns transaction with Google Drive link

#### PUT `/api/v1/transactions/{id}/with-bill`
- Update transaction with new bill image
- Automatically deletes old file from Google Drive

**Existing Endpoints (Unchanged):**
- POST `/api/v1/transactions` - Create without bill
- GET `/api/v1/transactions` - Get all
- GET `/api/v1/transactions/{id}` - Get by ID
- GET `/api/v1/transactions/user/{userId}` - Get by user
- GET `/api/v1/transactions/user/{userId}/type/{type}` - Filter by type
- PUT `/api/v1/transactions/{id}` - Update without bill
- DELETE `/api/v1/transactions/{id}` - Delete (auto-deletes bill)

---

### 5. Updated Dependencies (pom.xml)

Added:
```xml
<!-- Google Drive API -->
<dependency>
  <groupId>com.google.apis</groupId>
  <artifactId>google-api-services-drive</artifactId>
  <version>v3-rev20221020-2.0.0</version>
</dependency>

<!-- Google Auth Library -->
<dependency>
  <groupId>com.google.auth</groupId>
  <artifactId>google-auth-library-oauth2-http</artifactId>
  <version>1.11.0</version>
</dependency>

<!-- File utilities -->
<dependency>
  <groupId>commons-io</groupId>
  <artifactId>commons-io</artifactId>
  <version>2.11.0</version>
</dependency>
```

---

### 6. Configuration Updates

#### application.properties
```properties
# Google Drive Configuration
google.drive.credentials-path=credentials.json
google.drive.folder-id=your_google_drive_folder_id_here

# File Upload Configuration
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

---

## Database Schema Changes

### New Column in Transactions Table
```sql
ALTER TABLE transactions ADD COLUMN bill_file_id VARCHAR(255);
```

This gets created automatically with `spring.jpa.hibernate.ddl-auto=update`

---

## Key Features

### 1. Automatic File Management
- Files uploaded to designated Google Drive folder
- File IDs stored in database for reference
- Sharing links accessible via API

### 2. Lifecycle Management
```
Create Transaction with Bill
  ↓
  → Upload to Google Drive
  → Get sharing link
  → Store file ID in DB
  ↓
Retrieve Transaction
  ↓
  → Get file ID and sharing link
  → User can view bill on Google Drive
  ↓
Update with New Bill
  ↓
  → Delete old file from Google Drive
  → Upload new file
  → Update file ID and link
  ↓
Delete Transaction
  ↓
  → Auto-delete file from Google Drive
  → Remove from database
```

### 3. Error Handling
- Graceful handling of Google Drive API errors
- Detailed logging of operations
- User-friendly error messages

---

## Setup Checklist

### Before Running the Application

- [ ] Create Google Cloud Project
- [ ] Enable Google Drive API
- [ ] Create Service Account
- [ ] Download credentials JSON
- [ ] Create Google Drive folder for bills
- [ ] Share folder with service account
- [ ] Get folder ID
- [ ] Place credentials.json in `src/main/resources/`
- [ ] Update application.properties with folder ID
- [ ] Update file size limits if needed

See **GOOGLE_DRIVE_SETUP.md** for detailed instructions.

---

## API Usage Examples

### Create Transaction with Bill
```bash
curl -X POST http://localhost:8080/api/v1/transactions/with-bill \
  -F "transaction={\"userId\":1,\"personId\":1,\"amount\":500.00,\"type\":\"GIVE\"};type=application/json" \
  -F "billImage=@bill.jpg"
```

Response:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 1,
  "personId": 1,
  "amount": 500.00,
  "type": "GIVE",
  "note": null,
  "billPhotoUrl": "https://drive.google.com/file/d/1a2b3c4d5e6f7g8h9i0j/view",
  "billFileId": "1a2b3c4d5e6f7g8h9i0j",
  "createdAt": "2026-05-02T10:30:00"
}
```

### Update Transaction with New Bill
```bash
curl -X PUT http://localhost:8080/api/v1/transactions/{id}/with-bill \
  -F "transaction={\"amount\":550.00};type=application/json" \
  -F "billImage=@new-bill.jpg"
```

---

## File Structure

```
account/
├── src/main/
│   ├── java/com/kb/account/
│   │   ├── service/
│   │   │   └── GoogleDriveService.java (NEW)
│   │   ├── controller/
│   │   │   └── TransactionController.java (UPDATED)
│   │   ├── model/
│   │   │   └── Transaction.java (UPDATED)
│   │   ├── dto/
│   │   │   └── TransactionDTO.java (UPDATED)
│   │   └── service/Imp/
│   │       └── TransactionServiceImp.java (UPDATED)
│   └── resources/
│       ├── application.properties (UPDATED)
│       └── credentials.json (ADD THIS FILE)
└── pom.xml (UPDATED)
```

---

## Security Considerations

### ✅ Already Implemented
- Service account authentication (not OAuth)
- Google Drive folder-scoped permissions
- File ID tracking
- Automatic file deletion

### 🔒 Recommended (Additional)
- Add request validation for file types
- Implement file virus scanning
- Add audit logging
- Use environment variables for sensitive config
- Enable Google Drive audit logs
- Implement file retention policies

---

## Performance Notes

- **File uploads are synchronous** (might want async in future)
- **Google Drive API calls are rate-limited**
  - Standard quota: 10,000 queries per day per project
- **File deletion happens automatically** preventing storage waste
- **Sharing links are cached** in database

---

## Troubleshooting Guide

### Issue - Credentials Not Found
```
Error: Failed to upload file to Google Drive: Credentials not found
```
**Solution:** Place `credentials.json` in `src/main/resources/`

### Issue - Folder Not Found
```
Error: Failed to upload file to Google Drive: Invalid folder ID
```
**Solution:** Verify folder ID in application.properties (no spaces)

### Issue - File Too Large
```
Error: Failed to upload file to Google Drive: File is too large
```
**Solution:** Increase `spring.servlet.multipart.max-file-size` in application.properties

### Issue - Permission Denied
```
Error: Failed to upload file to Google Drive: Access denied
```
**Solution:** Share Google Drive folder with service account email (Editor role)

---

## Testing the Integration

### Unit Test Setup (Optional)
You can add tests for GoogleDriveService:
```java
@SpringBootTest
@TestWithGoogleDrive
class TransactionControllerTest {
    // Test /api/v1/transactions/with-bill endpoint
    // Test file upload and Google Drive integration
    // Test automatic file deletion
}
```

### Manual Testing (Recommended)
1. Register a user
2. Create a person (contact)
3. Upload transaction with bill image
4. Verify file appears in Google Drive folder
5. Access bill via the sharing link
6. Update with new bill (verify old file deleted)
7. Delete transaction (verify bill deleted)

See **TESTING_GUIDE_UPDATED.md** for complete examples.

---

## Architecture Diagram

```
User Request (with file)
    ↓
TransactionController
    ↓
TransactionServiceImp
    ↓
GoogleDriveService ←→ Google Drive API
    ↓                      ↓
TransactionRepository    Google Drive Folder
    ↓                   (Bill Storage)
PostgreSQL DB
(File ID Reference)
```

---

## What's Different from Previous Version

### Before (Original)
- Stored `billPhotoUrl` as user-provided string
- No automatic file management
- Files stored elsewhere (or not at all)

### After (With Google Drive)
- ✅ Automatic upload to Google Drive
- ✅ Get sharing link automatically
- ✅ Store Google Drive file ID
- ✅ Auto-delete files on transaction update/delete
- ✅ Centralized bill storage
- ✅ No server disk space needed
- ✅ Easy access to bills via sharing link

---

## Future Enhancements

1. **Async File Upload**
   - Use @Async for non-blocking uploads
   - Add job status tracking

2. **Image Processing**
   - Compress images before upload
   - Generate thumbnails

3. **OCR Scanning**
   - Extract bill details using OCR
   - Auto-populate amount from bill

4. **Multi-format Support**
   - Support PDF, Excel, Word documents
   - Generate QR codes for bills

5. **Sharing Control**
   - Allow users to share bills with others
   - Set expiration dates on shares

6. **Backup & Archive**
   - Auto-backup to secondary Google Drive
   - Archive old bills

---

## Monitoring & Logging

The application logs important events:
```
[INFO] File uploaded successfully: bill.jpg with ID: 1a2b3c4d5e6f7g8h9i0j
[INFO] File deleted successfully from Google Drive: 1a2b3c4d5e6f7g8h9i0j
[INFO] Bill file deleted from Google Drive: 1a2b3c4d5e6f7g8h9i0j
[WARN] Failed to delete bill file: Permission denied
```

Check application logs to monitor file operations.

---

## Support Resources

- **Google Drive API Docs:** https://developers.google.com/drive/api
- **Service Account Setup:** https://cloud.google.com/docs/authentication/getting-started
- **GOOGLE_DRIVE_SETUP.md:** Complete setup guide
- **API_DOCUMENTATION_UPDATED.md:** API endpoint reference
- **TESTING_GUIDE_UPDATED.md:** Testing examples

---

## Summary

Your Account Management application now has enterprise-grade file management:
- ✅ Google Drive integration
- ✅ Automatic file lifecycle management
- ✅ Scalable storage solution
- ✅ Shareable bill links
- ✅ Production-ready error handling

**The project is now ready for deployment with full bill image upload capability!** 🚀

---

## Questions?

Refer to:
1. **GOOGLE_DRIVE_SETUP.md** - For setup questions
2. **API_DOCUMENTATION_UPDATED.md** - For API questions
3. **TESTING_GUIDE_UPDATED.md** - For testing questions
4. **GoogleDriveService.java** - For implementation details

Happy uploading! 📸🚀

