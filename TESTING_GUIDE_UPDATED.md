# Updated API Testing Guide - Google Drive Integration

## Base URL
```
http://localhost:8080
```

---

## NEW: Transaction with Google Drive Bill Upload

### 1. Create Transaction with Bill Image (Google Drive)
```bash
# Using cURL with file upload to Google Drive
curl -X POST http://localhost:8080/api/v1/transactions/with-bill \
  -F "transaction={\"userId\":1,\"personId\":1,\"amount\":500.00,\"type\":\"GIVE\",\"note\":\"Restaurant bill\"};type=application/json" \
  -F "billImage=@path/to/bill.jpg"
```

**PowerShell Example:**
```powershell
$transactionJson = @{
    userId = 1
    personId = 1
    amount = 500.00
    type = "GIVE"
    note = "Restaurant bill"
} | ConvertTo-Json

$form = @{
    transaction = $transactionJson
    billImage = Get-Item -Path "C:\Users\YourName\Pictures\bill.jpg"
}

Invoke-WebRequest -Uri "http://localhost:8080/api/v1/transactions/with-bill" `
    -Method POST `
    -Form $form
```

**Response (201 Created):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "userId": 1,
  "personId": 1,
  "amount": 500.00,
  "type": "GIVE",
  "note": "Restaurant bill",
  "billPhotoUrl": "https://drive.google.com/file/d/1a2b3c4d5e6f7g8h9i0j/view",
  "billFileId": "1a2b3c4d5e6f7g8h9i0j",
  "createdAt": "2026-05-02T10:30:00"
}
```

---

### 2. Update Transaction with New Bill Image (Google Drive)
```bash
# Update existing transaction and upload new bill
curl -X PUT http://localhost:8080/api/v1/transactions/{transaction-id}/with-bill \
  -F "transaction={\"amount\":550.00,\"note\":\"Updated restaurant bill\"};type=application/json" \
  -F "billImage=@path/to/new-bill.jpg"
```

**PowerShell Example:**
```powershell
$transactionJson = @{
    amount = 550.00
    note = "Updated restaurant bill"
} | ConvertTo-Json

$form = @{
    transaction = $transactionJson
    billImage = Get-Item -Path "C:\Users\YourName\Pictures\new-bill.jpg"
}

Invoke-WebRequest -Uri "http://localhost:8080/api/v1/transactions/{transaction-id}/with-bill" `
    -Method PUT `
    -Form $form
```

---

## Existing Transaction APIs (Without Bill Upload)

### 1. Create Transaction (Without Bill)
```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "personId": 1,
    "amount": 500.00,
    "type": "GIVE",
    "note": "Loan without bill"
  }'
```

### 2. Get All Transactions
```bash
curl -X GET http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json"
```

### 3. Get Transaction by ID
```bash
curl -X GET "http://localhost:8080/api/v1/transactions/{transaction-id}" \
  -H "Content-Type: application/json"
```

### 4. Get User's Transactions
```bash
curl -X GET http://localhost:8080/api/v1/transactions/user/1 \
  -H "Content-Type: application/json"
```

### 5. Get User's GIVE Transactions
```bash
curl -X GET http://localhost:8080/api/v1/transactions/user/1/type/GIVE \
  -H "Content-Type: application/json"
```

### 6. Get User's RECEIVE Transactions
```bash
curl -X GET http://localhost:8080/api/v1/transactions/user/1/type/RECEIVE \
  -H "Content-Type: application/json"
```

### 7. Update Transaction (Without Bill)
```bash
curl -X PUT http://localhost:8080/api/v1/transactions/{transaction-id} \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 600.00,
    "note": "Updated transaction"
  }'
```

### 8. Delete Transaction
```bash
curl -X DELETE http://localhost:8080/api/v1/transactions/{transaction-id} \
  -H "Content-Type: application/json"
```

---

## User APIs

### 1. Register a User
```bash
curl -X POST http://localhost:8080/api/v1/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Alice",
    "email": "alice@test.com",
    "password": "pass123",
    "phone": "9999999999",
    "upiId": "alice@upi"
  }'
```

### 2. Login User
```bash
curl -X POST "http://localhost:8080/api/v1/users/login?email=alice@test.com&password=pass123" \
  -H "Content-Type: application/json"
```

### 3. Get All Users
```bash
curl -X GET http://localhost:8080/api/v1/users \
  -H "Content-Type: application/json"
```

### 4. Get User by ID
```bash
curl -X GET http://localhost:8080/api/v1/users/1 \
  -H "Content-Type: application/json"
```

### 5. Update User
```bash
curl -X PUT http://localhost:8080/api/v1/users/1 \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Alice Smith",
    "phone": "9999999998"
  }'
```

### 6. Delete User
```bash
curl -X DELETE http://localhost:8080/api/v1/users/1 \
  -H "Content-Type: application/json"
```

---

## Person APIs (Contacts)

### 1. Create Person
```bash
curl -X POST http://localhost:8080/api/v1/persons \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bob",
    "userId": 1
  }'
```

### 2. Get All Persons
```bash
curl -X GET http://localhost:8080/api/v1/persons \
  -H "Content-Type: application/json"
```

### 3. Get Person by ID
```bash
curl -X GET http://localhost:8080/api/v1/persons/1 \
  -H "Content-Type: application/json"
```

### 4. Get Persons by User ID
```bash
curl -X GET http://localhost:8080/api/v1/persons/user/1 \
  -H "Content-Type: application/json"
```

### 5. Update Person
```bash
curl -X PUT http://localhost:8080/api/v1/persons/1 \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bob Smith"
  }'
```

### 6. Delete Person
```bash
curl -X DELETE http://localhost:8080/api/v1/persons/1 \
  -H "Content-Type: application/json"
```

---

## Complete Test Workflow Example

### Step 1: Create Two Users
```bash
# User 1 - Alice
curl -X POST http://localhost:8080/api/v1/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Alice",
    "email": "alice@test.com",
    "password": "pass123",
    "phone": "9999999999",
    "upiId": "alice@upi"
  }'

# User 2 - Bob
curl -X POST http://localhost:8080/api/v1/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bob",
    "email": "bob@test.com",
    "password": "pass123",
    "phone": "8888888888",
    "upiId": "bob@upi"
  }'
```

### Step 2: Create a Person (Contact)
```bash
curl -X POST http://localhost:8080/api/v1/persons \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bob",
    "userId": 1
  }'
```

### Step 3: Create Transaction WITH Bill (NEW - Google Drive)
```bash
curl -X POST http://localhost:8080/api/v1/transactions/with-bill \
  -F "transaction={\"userId\":1,\"personId\":1,\"amount\":500,\"type\":\"GIVE\",\"note\":\"Restaurant\"};type=application/json" \
  -F "billImage=@/path/to/bill.jpg"
```

### Step 4: Get Transaction Details
```bash
curl -X GET "http://localhost:8080/api/v1/transactions/{transaction-id}" \
  -H "Content-Type: application/json"
```

### Step 5: View Bill Image
- Open the `billPhotoUrl` from step 4 response in your browser
- Or access the Google Drive file directly

### Step 6: Update Transaction with New Bill
```bash
curl -X PUT http://localhost:8080/api/v1/transactions/{transaction-id}/with-bill \
  -F "transaction={\"amount\":550,\"note\":\"Updated restaurant bill\"};type=application/json" \
  -F "billImage=@/path/to/new-bill.jpg"
```

### Step 7: Get All Transactions for User
```bash
curl -X GET http://localhost:8080/api/v1/transactions/user/1 \
  -H "Content-Type: application/json"
```

### Step 8: Delete Transaction (Auto-deletes bill from Google Drive)
```bash
curl -X DELETE "http://localhost:8080/api/v1/transactions/{transaction-id}" \
  -H "Content-Type: application/json"
```

---

## Testing with Postman

### Setup
1. Download Postman from [postman.com](https://www.postman.com)
2. Create a new collection "Account Management"

### Creating Transaction with Bill Image

**Step 1:** Create new POST request to `http://localhost:8080/api/v1/transactions/with-bill`

**Step 2:** Go to "Body" tab and select "form-data"

**Step 3:** Add the following keys:
- Key: `transaction` (Text)
  - Value: 
  ```json
  {
    "userId": 1,
    "personId": 1,
    "amount": 500.00,
    "type": "GIVE",
    "note": "Lunch bill"
  }
  ```

- Key: `billImage` (File)
  - Select your image file

**Step 4:** Click "Send"

**Step 5:** Check the response for `billFileId` and `billPhotoUrl`

---

## Response Codes Explained

| Code | Meaning | Example Scenario |
|------|---------|------------------|
| 201 | Created | Transaction created successfully |
| 200 | OK | GET request successful |
| 204 | No Content | DELETE successful |
| 400 | Bad Request | Invalid JSON or file too large |
| 404 | Not Found | User or transaction doesn't exist |
| 500 | Server Error | Google Drive connection failed |

---

## Troubleshooting Test Requests

### Issue: "Failed to upload file to Google Drive"
- ✓ Verify credentials.json exists in src/main/resources/
- ✓ Verify folder ID is correct in application.properties
- ✓ Ensure service account has access to the folder

### Issue: "File size exceeds limit"
- ✓ Default limit is 10MB
- ✓ Change in application.properties: `spring.servlet.multipart.max-file-size=50MB`
- ✓ Restart the application after changing

### Issue: "Person not found" when creating transaction
- ✓ Create a person first before creating transaction
- ✓ Verify personId is correct

### Issue: "User not found"
- ✓ Register a user first
- ✓ Verify userId in request matches an existing user

---

## Tips for Testing

1. **Use JSON Formatter**
   - Validate your JSON before sending
   - Use an online JSON validator

2. **Save Response Body**
   - Copy transaction IDs from responses for following requests
   - Keep file IDs for reference

3. **Test File Sizes**
   - Start with small images (< 1MB)
   - Test with larger images to verify upload limits

4. **Check Google Drive**
   - After upload, check your Google Drive folder
   - Verify bills folder contains the uploaded image

5. **Monitor Logs**
   - Watch application logs for upload progress
   - Monitor Google Drive service initialization

---

## Automated Testing Script (Bash)

```bash
#!/bin/bash

BASE_URL="http://localhost:8080"

# Create Users
echo "Creating User 1..."
USER1=$(curl -s -X POST $BASE_URL/api/v1/users/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@test.com","password":"pass","phone":"9999999999","upiId":"alice@upi"}' | grep -o '"id":[0-9]*' | cut -d':' -f2)
echo "User 1 ID: $USER1"

# Create Person
echo "Creating Person..."
PERSON=$(curl -s -X POST $BASE_URL/api/v1/persons \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Bob\",\"userId\":$USER1}" | grep -o '"id":[0-9]*' | cut -d':' -f2)
echo "Person ID: $PERSON"

# Create Transaction with Bill
echo "Creating Transaction with Bill..."
curl -X POST $BASE_URL/api/v1/transactions/with-bill \
  -F "transaction={\"userId\":$USER1,\"personId\":$PERSON,\"amount\":500,\"type\":\"GIVE\",\"note\":\"Test\"};type=application/json" \
  -F "billImage=@test.jpg"

echo "Test Complete!"
```

---

## Performance Testing

### Load Testing Command (using Apache Bench)
```bash
# Test 100 requests with 10 concurrent
ab -n 100 -c 10 http://localhost:8080/api/v1/transactions

# Test with GET request
ab -n 100 -c 10 http://localhost:8080/api/v1/transactions/user/1
```

---

## Documentation

- See `GOOGLE_DRIVE_SETUP.md` for Google Drive configuration
- See `API_DOCUMENTATION_UPDATED.md` for complete API reference
- See `PROJECT_COMPLETION_SUMMARY.md` for technical details

**Happy Testing!** 🚀

