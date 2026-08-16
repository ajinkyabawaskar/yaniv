# MySQL Localhost Connection & Verification Guide

Quick reference for connecting to and checking a local MySQL database instance on `localhost:3306` with default user `root` and password `root`.

---

## 1. Credentials Summary

| Property | Value |
| :--- | :--- |
| **Host** | `localhost` or `127.0.0.1` |
| **Port** | `3306` |
| **Username** | `root` |
| **Password** | `root` |

---

## 2. Command Line Interface (CLI) Commands

### Connect via MySQL CLI
```bash
# Connect using localhost
mysql -h localhost -P 3306 -u root -proot

# Connect using IP (Forces TCP/IP connection over 3306)
mysql -h 127.0.0.1 -P 3306 -u root -proot

```

---

## 3. SQL Verification & Health Checks

Once connected, run these statements to verify the instance and connection status:

```sql
-- 1. Check Server Version, User, and Current Time
SELECT VERSION() AS server_version, USER() AS current_user, NOW() AS current_time;

-- 2. List all Databases
SHOW DATABASES;

-- 3. Check Active Connections
SHOW PROCESSLIST;

-- 4. Check Server Uptime & Status
SHOW STATUS LIKE 'Uptime';
SHOW STATUS LIKE 'Threads_connected';

```

---

## 4. Programming Code Snippets


### Java (JDBC)

```java
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class MySQLConnectionTest {
    public static void main(String[] args) {
        String url = "jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true";
        String user = "root";
        String password = "root";

        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement stmt = conn.createStatement()) {

            if (conn != null) {
                System.out.println("[SUCCESS] Connected to MySQL Server!");
                ResultSet rs = stmt.executeQuery("SELECT VERSION(), USER(), NOW()");
                while (rs.next()) {
                    System.out.printf("Version: %s | User: %s | Time: %s%n",
                            rs.getString(1), rs.getString(2), rs.getString(3));
                }
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Connection failed: " + e.getMessage());
        }
    }
}

```

---

## 5. Troubleshooting Common Issues

| Issue / Error | Cause | Solution |
| --- | --- | --- |
| `ERROR 2003 (HY000): Can't connect to MySQL server` | Server service is not running. | Run `sudo service mysql start` (Linux) or `net start MySQL` (Windows). |
| `ERROR 1045 (28000): Access denied for user 'root'@'localhost'` | Incorrect password or plugin mismatch. | Reset password or check auth plugin using `ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'root';`. |
| `ERROR 2002 (HY000): Can't connect through socket` | Socket file missing/bypassed. | Explicitly specify `-h 127.0.0.1` to force TCP connection over port `3306`. |
| """ |  |  |

with open("mysql.md", "w") as f:
f.write(content)

```
