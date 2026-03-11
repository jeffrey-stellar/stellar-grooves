# How to Use Stellar Grooves

Stellar Grooves is a powerful music management tool. This guide will walk you through setting up and using the application to organize your music collection.

## 1. Initial Setup
1. Follow the **Build and Run** instructions in the main `README.md`.
2. Ensure the application is running at `http://localhost:8080`.

## 2. User Authentication
All actions in Stellar Grooves require an account.

- **Create an Account**: 
  - Send a `POST` request to `http://localhost:8080/api/auth/signup`.
  - Body: `{ "username": "yourname", "email": "email@example.com", "password": "yourpassword" }`.
- **Log In**: 
  - Send a `POST` request to `http://localhost:8080/api/auth/signin`.
  - Body: `{ "username": "yourname", "password": "yourpassword" }`.
  - **Save the Token**: The response will contain a JWT token.

## 3. Organizing your Music
Once logged in, you can start scanning your music library.

- **Scanning**:
  - Request: `POST` to `http://localhost:8080/api/library/scan`.
  - Header: `Authorization: Bearer YOUR_TOKEN`.
  - Body: `{ "path": "/path/to/your/music" }`.
  - Stellar Grooves will process the files and categorize them based on the artist's historical genre (Classic Rock, Hard Rock, Heavy Metal, etc.).

- **Viewing Results**:
  - Request: `GET` to `http://localhost:8080/api/library/files`.
  - Header: `Authorization: Bearer YOUR_TOKEN`.
  - You will receive a list of all scanned files with their identified genres and decades.

## 4. Multi-Genre Handling
If a band belongs to multiple genres (e.g., Led Zeppelin is categorized as both Classic Rock and Hard Rock), the application will tag them with all applicable genres. You can then use this information to decide which playlist or folder they fit into best.

## 5. Administration
If you need to manage other users or troubleshoot:
- Access the Admin endpoints at `/api/admin/users` (Requires `ROLE_ADMIN`).
- Access the H2 Database console at `http://localhost:8080/h2-console` to inspect the data directly.
  - **JDBC URL**: `jdbc:h2:file:./data/groovesdb`
  - **User**: `sa`
  - **Password**: `[leave blank]`
