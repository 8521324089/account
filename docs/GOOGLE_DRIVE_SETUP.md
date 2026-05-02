# Google Drive Integration Guide

## Overview
Your application now supports uploading bill images to Google Drive with dedicated endpoints. This guide will help you set up Google Drive integration.

---

## Step 1: Create a Google Cloud Project

### 1.1 Go to Google Cloud Console
- Visit [Google Cloud Console](https://console.cloud.google.com/)
- Sign in with your Google account

### 1.2 Create a New Project
1. Click on the project dropdown at the top
2. Click "NEW PROJECT"
3. Enter a project name (e.g., "Account Management App")
4. Click "CREATE"

### 1.3 Enable Google Drive API
1. In the left sidebar, click "APIs & Services" > "Library"
2. Search for "Google Drive API"
3. Click on it and select "ENABLE"

---

## Step 2: Create Service Account Credentials

### 2.1 Create a Service Account
1. Go to "APIs & Services" > "Credentials"
2. Click "Create Credentials" at the top
3. Select "Service Account"
4. Fill in the details:
   - Service account name: `account-app-service`
   - Service account ID: Auto-generated
   - Click "CREATE AND CONTINUE"
5. Skip the optional steps and click "DONE"

### 2.2 Create JSON Key
1. In the Service Accounts list, click on the service account you just created
2. Go to the "KEYS" tab
3. Click "Add Key" > "Create new key"
4. Select "JSON" format
5. Click "CREATE"
6. A JSON file will be downloaded - **save this securely!**

---

## Step 3: Set Up Google Drive Folder

### 3.1 Create a Folder in Google Drive
1. Go to [Google Drive](https://drive.google.com)
2. Create a new folder named "Bill Images" (or any name)
3. Right-click the folder and select "Share"

### 3.2 Share Folder with Service Account
1. Copy the service account email from the downloaded JSON file (looks like: `xxx@xxx.iam.gserviceaccount.com`)
2. Paste it in the share dialog
3. Give it "Editor" permissions
4. Click "Share"

### 3.3 Get the Folder ID
1. Open the folder you created
2. Look at the URL in your browser
3. The folder ID is the string after `/folders/` and before the next `/`
   - Example URL: `https://drive.google.com/drive/folders/1a2b3c4d5e6f7g8h9i0j`
   - Folder ID: `1a2b3c4d5e6f7g8h9i0j`

---

## Step 4: Configure Your Application

### 4.1 Place Credentials File
1. Copy the downloaded JSON credentials file to:
   ```
   src/main/resources/credentials.json
   ```

### 4.2 Update application.properties
Edit `src/main/resources/application.properties` and update:
```properties
google.drive.credentials-path=credentials.json
google.drive.folder-id=YOUR_FOLDER_ID_HERE
```

Replace `YOUR_FOLDER_ID_HERE` with the folder ID you obtained in Step 3.3

### 4.3 Verify File Upload Limits (Optional)
If you need to change the maximum file size, update:
```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

---

## API Endpoints for Bill Upload

### Create Transaction with Bill Image
**Endpoint:** `POST /api/v1/transactions/with-bill`

**Content-Type:** `multipart/form-data`

**Request Example (using cURL):**
```bash
curl -X POST http://localhost:8080/api/v1/transactions/with-bill \
  -F "transaction={\"userId\":1,\"personId\":1,\"amount\":500.00,\"type\":\"GIVE\",\"note\":\"Lunch bill\"};type=application/json" \
  -F "billImage=@/path/to/bill.jpg"
```

**PowerShell Example:**
```powershell
$transactionData = @{
    userId = 1
    personId = 1
    amount = 500.00
    type = "GIVE"
    note = "Lunch bill"
} | ConvertTo-Json

$form = @{
    transaction = $transactionData
    billImage = Get-Item "C:\path\to\bill.jpg"
}

Invoke-WebRequest -Uri "http://localhost:8080/api/v1/transactions/with-bill" `
    -Method POST `
    -Form $form
```

### Update Transaction with New Bill Image
**Endpoint:** `PUT /api/v1/transactions/{id}/with-bill`

**Content-Type:** `multipart/form-data`

**Request Example (using cURL):**
```bash
curl -X PUT http://localhost:8080/api/v1/transactions/{transaction-id}/with-bill \
  -F "transaction={\"amount\":600.00,\"note\":\"Updated bill\"};type=application/json" \
  -F "billImage=@/path/to/new-bill.jpg"
```

### Create Transaction Without Bill
**Endpoint:** `POST /api/v1/transactions`

**Request Example (using cURL):**
```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "personId": 1,
    "amount": 500.00,
    "type": "GIVE",
    "note": "Transaction without bill"
  }'
```

---

## Response Format

### Successful Upload Response (201 Created)
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 1,
  "personId": 1,
  "amount": 500.00,
  "type": "GIVE",
  "note": "Lunch bill",
  "billPhotoUrl": "https://drive.google.com/file/d/1a2b3c4d5e6f7g8h9i0j/view",
  "billFileId": "1a2b3c4d5e6f7g8h9i0j",
  "createdAt": "2026-05-02T10:30:00"
}
```

### Error Response (400 Bad Request)
```json
{
  "error": "Failed to upload file to Google Drive: File size exceeds limit"
}
```

---

## Key Features & Benefits

✅ **Automatic Upload** - Files are automatically uploaded to Google Drive
✅ **File Management** - Old files are automatically deleted when updating
✅ **Google Drive Link** - Direct link to access the bill image
✅ **File ID Storage** - File ID stored for future reference
✅ **Secure Sharing** - Controlled through Google Drive permissions
✅ **Scalable** - No server storage needed, uses Google Drive storage
✅ **Accessible** - Access bills from anywhere via Google Drive link

---

## Supported File Types

Recommended image formats:
- JPEG (.jpg, .jpeg)
- PNG (.png)
- GIF (.gif)
- WebP (.webp)
- PDF (.pdf)
- TIFF (.tiff)

Maximum file size: **10 MB** (configurable in application.properties)

---

## Database Schema Update

The Transaction table now has an additional column:
```sql
ALTER TABLE transactions ADD COLUMN bill_file_id VARCHAR(255);
```

This happens automatically with `spring.jpa.hibernate.ddl-auto=update`

---

## Troubleshooting

### Issue: "Credentials not found"
- Ensure `credentials.json` is placed in `src/main/resources/`
- Verify the file is not in .gitignore (or use environment variables instead)

### Issue: "Folder not found"
- Verify the folder ID is correct (no extra spaces)
- Ensure the service account has access to the folder

### Issue: "File size exceeds limit"
- Increase the file size limit in application.properties:
  ```properties
  spring.servlet.multipart.max-file-size=50MB
  ```

### Issue: "Permission denied"
- Check that the service account email is shared with the Google Drive folder
- Ensure the service account has "Editor" permissions

---

## Security Best Practices

1. **Never commit credentials.json to Git**
   - Add to .gitignore: `src/main/resources/credentials.json`

2. **Use Environment Variables**
   ```properties
   google.drive.credentials-path=${GOOGLE_CREDENTIALS_PATH}
   google.drive.folder-id=${GOOGLE_DRIVE_FOLDER_ID}
   ```

3. **Restrict Service Account Permissions**
   - Only give it access to the necessary folder
   - Use appropriate Google Drive sharing settings

4. **Validate File Types**
   - Add file type validation in the application
   - Scan uploaded files for malware

5. **Enable Audit Logging**
   - Enable Google Drive audit logs
   - Monitor file access and modifications

---

## Advanced Configuration

### Environment Variables Setup

Instead of hardcoding credentials in properties, use environment variables:

**Windows (PowerShell):**
```powershell
$env:GOOGLE_CREDENTIALS_PATH = "credentials.json"
$env:GOOGLE_DRIVE_FOLDER_ID = "your_folder_id_here"
```

**Linux/Mac:**
```bash
export GOOGLE_CREDENTIALS_PATH=credentials.json
export GOOGLE_DRIVE_FOLDER_ID=your_folder_id_here
```

**application.properties:**
```properties
google.drive.credentials-path=${GOOGLE_CREDENTIALS_PATH}
google.drive.folder-id=${GOOGLE_DRIVE_FOLDER_ID}
```

---

## Testing the Integration

### Using Postman
1. Set request type to POST
2. URL: `http://localhost:8080/api/v1/transactions/with-bill`
3. Go to "Body" tab
4. Select "form-data"
5. Add key "transaction" (type: Text)
6. Add key "billImage" (type: File)
7. Upload and test!

### Using REST Client (VS Code Extension)
```http
POST http://localhost:8080/api/v1/transactions/with-bill HTTP/1.1
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary

------WebKitFormBoundary
Content-Disposition: form-data; name="transaction"

{"userId":1,"personId":1,"amount":500,"type":"GIVE","note":"Test"}
------WebKitFormBoundary
Content-Disposition: form-data; name="billImage"; filename="test.jpg"

< ./test.jpg
------WebKitFormBoundary--
```

---

## Additional Resources

- [Google Drive API Documentation](https://developers.google.com/drive/api/guides/about-sdk)
- [Google Cloud Console](https://console.cloud.google.com/)
- [Service Account Setup Guide](https://cloud.google.com/docs/authentication/getting-started)

---

## Support

For issues or questions:
1. Check the Troubleshooting section above
2. Review Google Drive API documentation
3. Verify service account permissions are correct
4. Ensure network connectivity to Google Drive

Your bill upload feature is now ready to use! 🎉

