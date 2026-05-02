# API Testing Guide - cURL Examples

## Base URL
```
http://localhost:8080
```

## User APIs

### 1. Register a User
```bash
curl -X POST http://localhost:8080/api/v1/users/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "John Doe",
    "email": "john@example.com",
    "password": "password123",
    "phone": "9876543210",
    "upiId": "john@upi"
  }'
```

### 2. Login User
```bash
curl -X POST "http://localhost:8080/api/v1/users/login?email=john@example.com&password=password123" \
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
    "name": "John Smith",
    "phone": "9876543211"
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
    "name": "Jane Doe",
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
    "name": "Jane Smith"
  }'
```

### 6. Delete Person
```bash
curl -X DELETE http://localhost:8080/api/v1/persons/1 \
  -H "Content-Type: application/json"
```

---

## Transaction APIs

### 1. Create Transaction
```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "personId": 1,
    "amount": 500.00,
    "type": "GIVE",
    "note": "Lunch money",
    "billPhotoUrl": "https://example.com/bill.jpg"
  }'
```

### 2. Create Receive Transaction
```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "userId": 1,
    "personId": 2,
    "amount": 1000.00,
    "type": "RECEIVE",
    "note": "Loan repayment",
    "billPhotoUrl": null
  }'
```

### 3. Get All Transactions
```bash
curl -X GET http://localhost:8080/api/v1/transactions \
  -H "Content-Type: application/json"
```

### 4. Get Transaction by ID
```bash
curl -X GET http://localhost:8080/api/v1/transactions/{transaction-id} \
  -H "Content-Type: application/json"
```

### 5. Get User's Transactions
```bash
curl -X GET http://localhost:8080/api/v1/transactions/user/1 \
  -H "Content-Type: application/json"
```

### 6. Get User's GIVE Transactions
```bash
curl -X GET http://localhost:8080/api/v1/transactions/user/1/type/GIVE \
  -H "Content-Type: application/json"
```

### 7. Get User's RECEIVE Transactions
```bash
curl -X GET http://localhost:8080/api/v1/transactions/user/1/type/RECEIVE \
  -H "Content-Type: application/json"
```

### 8. Update Transaction
```bash
curl -X PUT http://localhost:8080/api/v1/transactions/{transaction-id} \
  -H "Content-Type: application/json" \
  -d '{
    "amount": 600.00,
    "note": "Updated lunch money"
  }'
```

### 9. Delete Transaction
```bash
curl -X DELETE http://localhost:8080/api/v1/transactions/{transaction-id} \
  -H "Content-Type: application/json"
```

---

## Test Workflow Example

1. **Create a User**
   ```bash
   # Register User 1
   curl -X POST http://localhost:8080/api/v1/users/register \
     -H "Content-Type: application/json" \
     -d '{"name": "Alice", "email": "alice@test.com", "password": "pass123", "phone": "9999999999", "upiId": "alice@upi"}'
   # Returns: {"id": 1, "name": "Alice", ...}
   
   # Register User 2
   curl -X POST http://localhost:8080/api/v1/users/register \
     -H "Content-Type: application/json" \
     -d '{"name": "Bob", "email": "bob@test.com", "password": "pass123", "phone": "8888888888", "upiId": "bob@upi"}'
   # Returns: {"id": 2, "name": "Bob", ...}
   ```

2. **Create a Person (Contact)**
   ```bash
   curl -X POST http://localhost:8080/api/v1/persons \
     -H "Content-Type: application/json" \
     -d '{"name": "Bob", "userId": 1}'
   # Returns: {"id": 1, "name": "Bob", "userId": 1}
   ```

3. **Create a Transaction**
   ```bash
   curl -X POST http://localhost:8080/api/v1/transactions \
     -H "Content-Type: application/json" \
     -d '{"userId": 1, "personId": 1, "amount": 500, "type": "GIVE", "note": "Dinner"}'
   # Returns: {"id": "uuid", "userId": 1, "personId": 1, "amount": 500, "type": "GIVE", ...}
   ```

4. **View All Transactions**
   ```bash
   curl -X GET http://localhost:8080/api/v1/transactions/user/1
   # Returns: [{"id": "uuid", "userId": 1, ...}]
   ```

---

## Response Codes

- **201 Created**: Successfully created resource
- **200 OK**: Successful GET, PUT operations
- **204 No Content**: Successful DELETE
- **400 Bad Request**: Invalid request data
- **404 Not Found**: Resource not found
- **500 Internal Server Error**: Server error

---

## Notes

- All timestamps are in ISO 8601 format
- Transaction IDs are auto-generated UUIDs
- User IDs and Person IDs are auto-generated Long values
- Password should be encrypted in production (implement BCrypt or similar)
- Add request validation and error handling for production use

