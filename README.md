# Stellar Grooves - Music Management Application by Stellar Ideas

Stellar Grooves is a cross-platform music management application designed to help users scan, analyze, and organize their rock and metal music collections. By leveraging historical genre definitions from the 1960s to the 2020s, it automatically categorizes your music into sub-genres like Classic Rock, Hard Rock, Hair Metal, Heavy Metal, and Thrash Metal.

---

## 🚀 Getting Started

### Prerequisites
- **Java 17 or higher** (JDK 17)
- **Maven 3.6+**
- **MongoDB**: A local or remote MongoDB instance is required.

### Database Setup
Ensure MongoDB is running locally on port `27017`. You can change the connection URI in `src/main/resources/application.properties`.

### Build and Run
1.  **Clone or Download** the project to your local machine.
2.  **Open a terminal** in the project root directory.
3.  **Build the application** using Maven:
    ```powershell
    mvn clean package
    ```
4.  **Run the application**:
    ```powershell
    java -jar target/stellar-grooves-0.0.1-SNAPSHOT.jar
    ```
    The application will start on `http://localhost:8080`.

### 🌐 Web Interface
Stellar Grooves now features a web-based dashboard accessible at `http://localhost:8080`.
- **Register**: Go to `/signup` to create a new account.
- **Login**: Use your credentials at `/login`.
- **Scan & Manage**: Once logged in, use the dashboard to scan your music directories and view your categorized library.

---

## 📖 How to Use (API and Advanced)

Stellar Grooves remains a multi-user application. Each user on your network can register and manage their own local music directory via the web interface or REST API.

### 1. Registration and Login
- **Web**: Access `http://localhost:8080/signup` and `http://localhost:8080/login`.
- **REST API**: 
  - **Register**: Send a `POST` request to `/api/auth/signup` with `username`, `email`, and `password`.
  - **Login**: Send a `POST` request to `/api/auth/signin` with your `username` and `password`.
  - **JWT Token**: You will receive a `token`. This token must be included in the `Authorization` header as a `Bearer <token>` for all subsequent API requests.

### 2. Scanning your Music
- **Web Dashboard**: Enter the path in the "Scan Directory" form (e.g., `C:\Users\YourName\Music\Rock`).
- **REST API**: Use the `/api/library/scan` endpoint. Provide the absolute path to your music folder in the request body:
  ```json
  { "path": "C:\\Users\\YourName\\Music\\Rock" }
  ```
- **Automatic Analysis**: Stellar Grooves will scan the directory for `.mp3`, `.flac`, and `.m4a` files. It extracts metadata and compares artists against its historical catalog to assign genres and decades.

### 3. Viewing and Organizing
- **Web Dashboard**: The table automatically refreshes with your categorized collection.
- **REST API**: Access `/api/library/files` to see your scanned collection, complete with categorized genres.
- **Multi-Genre Handling**: The system identifies bands that span multiple genres (e.g., Metallica, Led Zeppelin), allowing you to see all applicable tags for refined organization.

### 4. Administrative Tasks
- **Admin Access**: Users with the `ROLE_ADMIN` can access `/api/admin` endpoints to manage users or troubleshoot account issues.

---

## 📦 Packaging for Distribution

You can package Stellar Grooves as a native executable. Ensure MongoDB is available on the target system where the app will run.

### Windows (.exe)
```powershell
jpackage --type exe --input target/ --main-jar stellar-grooves-0.0.1-SNAPSHOT.jar --main-class com.stellarideas.grooves.StellarGroovesApplication --name "StellarGrooves" --win-shortcut --win-menu
```

### macOS (.app)
```bash
jpackage --type app-image --input target/ --main-jar stellar-grooves-0.0.1-SNAPSHOT.jar --main-class com.stellarideas.grooves.StellarGroovesApplication --name "StellarGrooves"
```

---

## 🛠 Tech Stack
- **Spring Boot 3.2**: Core framework.
- **Spring Data MongoDB**: Scalable NoSQL persistence.
- **Thymeleaf**: Server-side templating for the web interface.
- **Spring Security & JWT**: Multi-mode authentication (Form & Token).
- **JAudiotagger**: High-performance music metadata extraction.
