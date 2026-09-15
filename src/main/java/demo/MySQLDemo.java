package demo;

import java.sql.*;

public class MySQLDemo {

    private static final String URL  = "jdbc:mysql://localhost:3306/testdb";
    private static final String USER = "root";
    private static final String PASS = "secret";

    public static void main(String[] args) throws Exception {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
            System.out.println("Connected to MySQL: " + conn.getMetaData().getDatabaseProductVersion());

            // Create table
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id   INT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(50) NOT NULL
                )
            """);

            // Insert rows
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name) VALUES (?)")) {
                for (String name : new String[]{"Alice", "Bob", "Carol"}) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                }
            }

            // Query and print
            try (ResultSet rs = conn.createStatement().executeQuery("SELECT id, name FROM users")) {
                System.out.println("id | name");
                System.out.println("---|-----");
                while (rs.next()) {
                    System.out.printf("%d  | %s%n", rs.getInt("id"), rs.getString("name"));
                }
            }

            // Cleanup
            conn.createStatement().execute("DROP TABLE users");
        }
    }
}
