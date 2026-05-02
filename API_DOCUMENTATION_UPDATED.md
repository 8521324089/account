# Updated API Documentation - With Google Drive Integration

## Project Overview
This is a Spring Boot REST API application for managing user accounts, persons (contacts), and financial transactions with **Google Drive integration for bill image uploads**.

---

## Architecture

### Model Classes
- **User**: Represents a user with account details (email, phone, UPI ID, etc.)
- **Person**: Represents a contact/person associated with a user
- **Transaction**: Represents financial transactions with Google Drive bill image support

### Database Schema
- Table: `users` - Stores user information
- Table: `persons` - Stores person/contact information
- Table: `transactions` - Stores transaction records with Google Drive file IDs

---

## API Endpoints

### User Management (`/api/v1/users`)

#### 1. Register User
- **Method**: `POST`
- **URL**: `/api/v1/users/register`
- **Request Body**:
```json
{
  "name": "John Doe",
  "email": "john@example.com",
  "password": "password123",
  "phone": "9876543210",
  "upiId": "john@upi"
}
```
- **Response**: `201 Created` with UserDTO

#### 2. Login User
- **Method**: `POST`
- **URL**: `/api/v1/users/login?email=john@example.com&password=password123`
- **Response**: `200 OK` with UserDTO

#### 3. Get All Users
- **Method**: `GET`
- **URL**: `/api/v1/users`
- **Response**: `200 OK` with list of UserDTO

#### 4. Get User by ID
- **Method**: `GET`
- **URL**: `/api/v1/users/{id}`
- **Response**: `200 OK` with UserDTO

#### 5. Update User
- **Method**: `PUT`
- **URL**: `/api/v1/users/{id}`
- **Request Body**: Partial user data
- **Response**: `200 OK` with updated UserDTO

#### 6. Delete User
- **Method**: `DELETE`
- **URL**: `/api/v1/users/{id}`
- **Response**: `204 No Content`

---

### Person Management (`/api/v1/persons`)

#### 1. Create Person
- **Method**: `POST`
- **URL**: `/api/v1/persons`
- **Request Body**:
```json
{
  "name": "Jane Doe",
  "userId": 1
}
```
- **Response**: `201 Created` with PersonDTO

#### 2. Get All Persons
- **Method**: `GET`
- **URL**: `/api/v1/persons`
- **Response**: `200 OK` with list of PersonDTO

#### 3. Get Person by ID
- **Method**: `GET`
- **URL**: `/api/v1/persons/{id}`
- **Response**: `200 OK` with PersonDTO

#### 4. Get Persons by User ID
- **Method**: `GET`
- **URL**: `/api/v1/persons/user/{userId}`
- **Response**: `200 OK` with list of PersonDTO

#### 5. Update Person
- **Method**: `PUT`
- **URL**: `/api/v1/persons/{id}`
- **Request Body**: Partial person data
- **Response**: `200 OK` with updated PersonDTO

#### 6. Delete Person
- **Method**: `DELETE`
- **URL**: `/api/v1/persons/{id}`
- **Response**: `204 No Content`

---

### Transaction Management (`/api/v1/transactions`)

#### 1. Create Transaction (Without Bill)
- **Method**: `POST`
- **URL**: `/api/v1/transactions`
- **Content-Type**: `application/json`
- **Request Body**:
```json
{
  "userId": 1,
  "personId": 1,
  "amount": 500.00,
  "type": "GIVE",
  "note": "Lunch money"
}
```
- **Response**: `201 Created` with TransactionDTO

#### 2. Create Transaction with Bill Image (NEW - Google Drive)
- **Method**: `POST`
- **URL**: `/api/v1/transactions/with-bill`
- **Content-Type**: `multipart/form-data`
- **Form Parameters**:
  - `transaction` (JSON): Transaction data
  - `billImage` (File): Image file to upload
- **Request Example (cURL)**:
```bash
curl -X POST http://localhost:8080/api/v1/transactions/with-bill \
  -F "transaction={\"userId\":1,\"personId\":1,\"amount\":500.00,\"type\":\"GIVE\",\"note\":\"Lunch bill\"};type=application/json" \
  -F "billImage=@bill.jpg"
```
- **Response**: `201 Created` with TransactionDTO including billFileId and billPhotoUrl

#### 3. Get All Transactions
- **Method**: `GET`
- **URL**: `/api/v1/transactions`
- **Response**: `200 OK` with list of TransactionDTO

#### 4. Get Transaction by ID
- **Method**: `GET`
- **URL**: `/api/v1/transactions/{id}`
- **Response**: `200 OK` with TransactionDTO

#### 5. Get Transactions by User ID
- **Method**: `GET`
- **URL**: `/api/v1/transactions/user/{userId}`
- **Response**: `200 OK` with list of TransactionDTO

#### 6. Get Transactions by User ID and Type
- **Method**: `GET`
- **URL**: `/api/v1/transactions/user/{userId}/type/{type}`
- **Path Parameters**:
  - `userId`: User ID
  - `type`: Transaction type (GIVE or RECEIVE)
- **Response**: `200 OK` with filtered list of TransactionDTO

#### 7. Update Transaction (Without Bill)
- **Method**: `PUT`
- **URL**: `/api/v1/transactions/{id}`
- **Content-Type**: `application/json`
- **Request Body**: Partial transaction data
- **Response**: `200 OK` with updated TransactionDTO

#### 8. Update Transaction with New Bill Image (NEW - Google Drive)
- **Method**: `PUT`
- **URL**: `/api/v1/transactions/{id}/with-bill`
- **Content-Type**: `multipart/form-data`
- **Form Parameters**:
  - `transaction` (JSON): Updated transaction data
  - `billImage` (File): New image file to upload
- **Request Example (cURL)**:
```bash
curl -X PUT http://localhost:8080/api/v1/transactions/{id}/with-bill \
  -F "transaction={\"amount\":600.00,\"note\":\"Updated bill\"};type=application/json" \
  -F "billImage=@new-bill.jpg"
```
- **Response**: `200 OK` with updated TransactionDTO (old file automatically deleted from Google Drive)

#### 9. Delete Transaction
- **Method**: `DELETE`
- **URL**: `/api/v1/transactions/{id}`
- **Response**: `204 No Content` (bill image automatically deleted from Google Drive)

---

## Response Models

### UserDTO
```json
{
  "id": 1,
  "name": "John Doe",
  "email": "john@example.com",
  "password": "password123",
  "phone": "9876543210",
  "upiId": "john@upi"
}
```

### PersonDTO
```json
{
  "id": 1,
  "name": "Jane Doe",
  "userId": 1
}
```

### TransactionDTO
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

---

## Technology Stack
- **Framework**: Spring Boot 4.0.6
- **Language**: Java 21
- **Database**: PostgreSQL
- **ORM**: JPA/Hibernate
- **Build Tool**: Maven
- **Lombok**: For code generation
- **Google Drive API**: For file storage

---

## Google Drive Features

### Automatic File Management
- ✅ Upload bill images to Google Drive (multipart/form-data)
- ✅ Get direct sharing links to view bills
- ✅ Automatic deletion when transaction is deleted
- ✅ Automatic replacement when bill is updated
- ✅ Store Google Drive file IDs for reference

### Setup Requirements
See `GOOGLE_DRIVE_SETUP.md` for detailed setup instructions

---

## Database Configuration
Update `application.properties`:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/Ledger_db
spring.datasource.username=postgres
spring.datasource.password=root

# Google Drive Configuration
google.drive.credentials-path=credentials.json
google.drive.folder-id=your_folder_id_here
```

---

## File Upload Limits
```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

---

## Running the Application

### Prerequisites
- Java 21
- PostgreSQL database running
- Maven
- Google Drive account with service account setup

### Steps
1. Create database: `Ledger_db` in PostgreSQL
2. Setup Google Drive integration (see GOOGLE_DRIVE_SETUP.md)
3. Place credentials.json in src/main/resources/
4. Run: `./mvnw spring-boot:run`
5. Server runs on `http://localhost:8080`

---

## Endpoint Summary Table

| Resource | Method | Endpoint | Description | Multipart |
|----------|--------|----------|-------------|-----------|
| Transaction | POST | /api/v1/transactions | Create transaction | No |
| Transaction | POST | /api/v1/transactions/with-bill | Create with bill image | Yes |
| Transaction | GET | /api/v1/transactions | Get all | No |
| Transaction | GET | /api/v1/transactions/{id} | Get by ID | No |
| Transaction | GET | /api/v1/transactions/user/{userId} | Get by user | No |
| Transaction | GET | /api/v1/transactions/user/{userId}/type/{type} | Get by type | No |
| Transaction | PUT | /api/v1/transactions/{id} | Update | No |
| Transaction | PUT | /api/v1/transactions/{id}/with-bill | Update with bill | Yes |
| Transaction | DELETE | /api/v1/transactions/{id} | Delete | No |
| User | POST | /api/v1/users/register | Register | No |
| User | POST | /api/v1/users/login | Login | No |
| User | GET | /api/v1/users | Get all | No |
| User | GET | /api/v1/users/{id} | Get by ID | No |
| User | PUT | /api/v1/users/{id} | Update | No |
| User | DELETE | /api/v1/users/{id} | Delete | No |
| Person | POST | /api/v1/persons | Create | No |
| Person | GET | /api/v1/persons | Get all | No |
| Person | GET | /api/v1/persons/{id} | Get by ID | No |
| Person | GET | /api/v1/persons/user/{userId} | Get by user | No |
| Person | PUT | /api/v1/persons/{id} | Update | No |
| Person | DELETE | /api/v1/persons/{id} | Delete | No |

---

## HTTP Status Codes

- **201 Created**: Successfully created resource
- **200 OK**: Successful GET, PUT operations
- **204 No Content**: Successful DELETE
- **400 Bad Request**: Invalid request data or file upload errors
- **404 Not Found**: Resource not found
- **500 Internal Server Error**: Server error or Google Drive connection error

---

## Error Handling

### File Upload Errors
```json
{
  "error": "Failed to upload file to Google Drive: File size exceeds limit"
}
```

### Resource Not Found
```json
{
  "error": "User not found"
}
```

### Invalid Request
```json
{
  "error": "Failed to upload file to Google Drive: Invalid file type"
}
```

---

## New Features & Enhancements

✨ **NEW: Google Drive Integration**
- Upload bill images directly to Google Drive
- Automatic file management (create, update, delete)
- Get direct sharing links for bill viewing
- Store file IDs for reference and tracking
- Automatic cleanup on transaction deletion

---

## Supported File Types for Upload
- JPEG (.jpg, .jpeg)
- PNG (.png)
- GIF (.gif)
- WebP (.webp)
- PDF (.pdf)
- TIFF (.tiff)

Maximum file size: **10 MB** (configurable)

---

## Security & Privacy

- Service account credentials stored securely
- Google Drive sharing controlled via service account permissions
- File IDs stored in database for tracking
- Automatic file deletion prevents orphaned files
- Support for environment variables for sensitive configuration

---

## Documentation

- **GOOGLE_DRIVE_SETUP.md** - Complete Google Drive setup guide
- **TESTING_GUIDE.md** - Testing examples and curl commands
- **PROJECT_COMPLETION_SUMMARY.md** - Project technical overview
- **QUICK_START.md** - Quick reference guide

All documentation is available in the project root directory.

