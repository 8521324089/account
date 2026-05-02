# Account Management Application - REST API

## Project Overview
This is a Spring Boot REST API application for managing user accounts, persons (contacts), and financial transactions.

## Architecture

### Model Classes
- **User**: Represents a user with account details (email, phone, UPI ID, etc.)
- **Person**: Represents a contact/person associated with a user
- **Transaction**: Represents financial transactions between users and persons

### Database Schema
- Table: `users` - Stores user information
- Table: `persons` - Stores person/contact information with foreign key to users
- Table: `transactions` - Stores transaction records with foreign keys to users and persons

## API Endpoints

### User Management (`/api/v1/users`)

1. **Register User**
   - `POST /api/v1/users/register`
   - Request Body:
   ```json
   {
     "name": "John Doe",
     "email": "john@example.com",
     "password": "password123",
     "phone": "9876543210",
     "upiId": "john@upi"
   }
   ```

2. **Login User**
   - `POST /api/v1/users/login?email=john@example.com&password=password123`

3. **Get User by ID**
   - `GET /api/v1/users/{id}`

4. **Get All Users**
   - `GET /api/v1/users`

5. **Update User**
   - `PUT /api/v1/users/{id}`
   - Request Body: Partial user data to update

6. **Delete User**
   - `DELETE /api/v1/users/{id}`

### Person Management (`/api/v1/persons`)

1. **Create Person**
   - `POST /api/v1/persons`
   - Request Body:
   ```json
   {
     "name": "Jane Doe",
     "userId": 1
   }
   ```

2. **Get Person by ID**
   - `GET /api/v1/persons/{id}`

3. **Get All Persons**
   - `GET /api/v1/persons`

4. **Get Persons by User ID**
   - `GET /api/v1/persons/user/{userId}`

5. **Update Person**
   - `PUT /api/v1/persons/{id}`
   - Request Body: Partial person data to update

6. **Delete Person**
   - `DELETE /api/v1/persons/{id}`

### Transaction Management (`/api/v1/transactions`)

1. **Create Transaction**
   - `POST /api/v1/transactions`
   - Request Body:
   ```json
   {
     "userId": 1,
     "personId": 1,
     "amount": 500.00,
     "type": "GIVE",
     "note": "Lunch money",
     "billPhotoUrl": "https://example.com/bill.jpg"
   }
   ```

2. **Get Transaction by ID**
   - `GET /api/v1/transactions/{id}`

3. **Get All Transactions**
   - `GET /api/v1/transactions`

4. **Get Transactions by User ID**
   - `GET /api/v1/transactions/user/{userId}`

5. **Get Transactions by User ID and Type**
   - `GET /api/v1/transactions/user/{userId}/type/{type}`
   - Type: GIVE or RECEIVE

6. **Update Transaction**
   - `PUT /api/v1/transactions/{id}`
   - Request Body: Partial transaction data to update

7. **Delete Transaction**
   - `DELETE /api/v1/transactions/{id}`

## Technology Stack
- **Framework**: Spring Boot 4.0.6
- **Language**: Java 21
- **Database**: PostgreSQL
- **ORM**: JPA/Hibernate
- **Build Tool**: Maven
- **Lombok**: For code generation

## Database Configuration
Update `application.properties`:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/Ledger_db
spring.datasource.username=postgres
spring.datasource.password=root
```

## Running the Application

### Prerequisites
- Java 21
- PostgreSQL database running
- Maven

### Steps
1. Create database: `Ledger_db` in PostgreSQL
2. Run: `./mvnw spring-boot:run`
3. Server runs on `http://localhost:8080`

## Project Structure
```
src/
├── main/
│   ├── java/com/kb/account/
│   │   ├── AccountApplication.java      (Main entry point)
│   │   ├── controller/
│   │   │   ├── UserController.java
│   │   │   ├── PersonController.java
│   │   │   └── TransactionController.java
│   │   ├── service/
│   │   │   ├── UserService.java
│   │   │   ├── PersonService.java
│   │   │   ├── TransactionService.java (Interface)
│   │   │   └── Imp/
│   │   │       └── TransactionServiceImp.java
│   │   ├── repository/
│   │   │   ├── UserRepository.java
│   │   │   ├── PersonRepository.java
│   │   │   └── TransactionRepository.java
│   │   ├── model/
│   │   │   ├── User.java
│   │   │   ├── Person.java
│   │   │   └── Transaction.java
│   │   └── dto/
│   │       ├── UserDTO.java
│   │       ├── PersonDTO.java
│   │       └── TransactionDTO.java
│   └── resources/
│       └── application.properties
```

## Features
- User registration and authentication
- CRUD operations for users, persons, and transactions
- Transaction tracking with types (GIVE/RECEIVE)
- JPA Auditing for created timestamps
- PostgreSQL database integration
- RESTful API design
- Exception handling
- DTO pattern for clean API contracts

## Next Steps/Future Enhancements
1. Add Spring Security for authentication
2. Implement JWT tokens
3. Add request validation annotations (@Valid, @NotBlank, etc.)
4. Add global exception handler
5. Add API documentation (Swagger/OpenAPI)
6. Add unit and integration tests
7. Add caching
8. Add pagination for list endpoints

