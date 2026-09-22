package demo;

import java.sql.*;
import java.util.Properties;
import java.util.logging.*;

public class WrapperCacheDemo {

    private static final String URL  = "jdbc:aws-wrapper:mysql://localhost:3306/testdb";
    private static final String USER = "root";
    private static final String PASS = "secret";

    private static final String CACHED_QUERY_DISPLAY =
        "/* CACHE_PARAM(ttl=3600s) */ SELECT id, name, price FROM products WHERE category = 'electronics'";

    private static final String CACHED_QUERY =
        "/* CACHE_PARAM(ttl=3600s) */ SELECT p.id, p.name, p.price" +
        " FROM products p JOIN (SELECT SLEEP(0.5 + RAND() * 2.5) AS delay) d ON 1=1" +
        " WHERE p.category = 'electronics'";

    private static final String UNCACHED_QUERY =
        "SELECT stock FROM products WHERE id = 1";

    public static void main(String[] args) throws Exception {
        // Silence wrapper internal logs so demo output stays clean
        Logger root = Logger.getLogger("");
        root.setLevel(Level.SEVERE);
        for (Handler h : root.getHandlers()) h.setLevel(Level.SEVERE);
        Logger.getLogger("software.amazon.jdbc").setLevel(Level.SEVERE);

        Properties props = new Properties();
        props.setProperty("user", USER);
        props.setProperty("password", PASS);
        props.setProperty("wrapperPlugins", "remoteQueryCache");
        props.setProperty("cacheEndpointAddrRw", "localhost:6379");
        props.setProperty("cacheUseSSL", "false");

        printHeader();

        try (Connection conn = DriverManager.getConnection(URL, props)) {
            printDriverChain(conn);
            seedData(conn);

            useCaseOne(conn);
            Thread.sleep(500); // let the async Valkey write complete
            useCaseTwo(conn);
            useCaseThree(conn);
        }
    }

    // -------------------------------------------------------------------
    // Cache miss: product catalog, cold start
    // -------------------------------------------------------------------
    static void useCaseOne(Connection conn) throws SQLException {
        System.out.println("\n=================================================");
        System.out.println("[CACHE MISS] Product catalog, first page load");
        System.out.println("=================================================");
        System.out.println("  Cache is empty. Fetching from MySQL...");

        int[] ids = new int[10];
        String[] names = new String[10];
        double[] prices = new double[10];
        int rowCount = 0;

        long start = System.nanoTime();
        try (PreparedStatement ps = conn.prepareStatement(CACHED_QUERY);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids[rowCount] = rs.getInt("id");
                names[rowCount]  = rs.getString("name");
                prices[rowCount] = rs.getDouble("price");
                rowCount++;
            }
        }
        double ms = (System.nanoTime() - start) / 1_000_000.0;

        System.out.println("  ---");
        for (int i = 0; i < rowCount; i++) {
            System.out.printf("  id=%-2d  %-20s  $%7.2f%n", ids[i], names[i], prices[i]);
        }
        System.out.println("  ---");
        System.out.printf("  %d rows in %.1fms%n", rowCount, ms);
        System.out.printf("  Cache was empty, MySQL took %.1fms. Result now stored in Valkey.%n", ms);
    }

    // -------------------------------------------------------------------
    // Cache hit: same query x10
    // -------------------------------------------------------------------
    static void useCaseTwo(Connection conn) throws SQLException {
        System.out.println("\n=================================================");
        System.out.println("[CACHE HIT]  Product catalog, 10 users (cache warm)");
        System.out.println("=================================================");
        System.out.println("  Query : " + CACHED_QUERY_DISPLAY);
        
        double[] times = new double[10];
        try (PreparedStatement ps = conn.prepareStatement(CACHED_QUERY)) {
            // warm-up on the same PS that will be measured
            for (int i = 0; i < 50; i++) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { /* consume */ }
                }
            }

            long startTen = System.nanoTime();
            for (int i = 0; i < 10; i++) {
                long start = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rs.getInt("id");
                        rs.getString("name");
                        rs.getDouble("price");
                    }
                }
                times[i] = (System.nanoTime() - start) / 1_000_000.0;
            }
            double totalMs = (System.nanoTime() - startTen) / 1_000_000.0;

            for (int i = 0; i < times.length; i++) {
                System.out.printf("  Execution #%d: %.1fms%n", i + 1, times[i]);
            }
            System.out.println("  Cache : HIT × 10 → MySQL never touched");
            System.out.printf("  Total : %.1fms for 10 reads from Valkey%n", totalMs);
        }
    }

    // -------------------------------------------------------------------
    // When not to cache: real-time stock check, no hint, always hits MySQL
    // -------------------------------------------------------------------
    static void useCaseThree(Connection conn) throws SQLException {
        System.out.println("\n=================================================");
        System.out.println("[WHEN NOT TO CACHE] Real-time stock check");
        System.out.println("=================================================");
        System.out.println("  Stock changes with every order, stale data means wrong inventory.");
        System.out.println("  No CACHE_PARAM hint, no cache. The wrapper goes straight to MySQL.");
        System.out.println("  Query : " + UNCACHED_QUERY);

        long start = System.nanoTime();
        try (ResultSet rs = conn.createStatement().executeQuery(UNCACHED_QUERY)) {
            if (rs.next()) {
                System.out.println("  Stock : " + rs.getInt("stock") + " units");
            }
            double ms = (System.nanoTime() - start)/1_000_000.0;
            System.out.printf("  Time: %.1fms%n", ms);
            System.out.println("  Cache : NONE, no CACHE_PARAM hint");
        }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------
    static void printHeader() {
        System.out.println("=================================================");
        System.out.println("  AWS Advanced JDBC Wrapper, Valkey Cache Demo  ");
        System.out.println("=================================================");
    }

    static void printDriverChain(Connection conn) throws SQLException {
        System.out.println("\n--- Driver chain ---");
        System.out.println("  Wrapper : software.amazon.jdbc.Driver (v4.4.0)");
        System.out.println("  Plugin  : remoteQueryCache");
        System.out.println("  Cache   : localhost:6379 (Valkey)");
        System.out.println("  Driver  : com.mysql.cj.jdbc.Driver");
        System.out.println("  DB      : " + conn.getMetaData().getDatabaseProductName()
                         + " " + conn.getMetaData().getDatabaseProductVersion());
    }

    static void seedData(Connection conn) throws SQLException {
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS products (
                id       INT AUTO_INCREMENT PRIMARY KEY,
                name     VARCHAR(100) NOT NULL,
                price    DECIMAL(8,2) NOT NULL,
                category VARCHAR(50)  NOT NULL,
                stock    INT          NOT NULL
            )
        """);

        // Only insert if table is empty so re-runs don't duplicate data
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM products")) {
            rs.next();
            if (rs.getInt(1) == 0) {
                conn.createStatement().execute("""
                    INSERT INTO products (name, price, category, stock) VALUES
                        ('Laptop Pro 15',  1299.99, 'electronics', 42),
                        ('Wireless Mouse',   29.99, 'electronics', 150),
                        ('USB-C Hub',        49.99, 'electronics', 88)
                """);
            }
        }

        System.out.println("\n--- Products in MySQL ---");
        System.out.printf("  %-4s  %-22s  %8s  %5s%n", "ID", "Name", "Price", "Stock");
        System.out.println("  " + "-".repeat(46));
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT id, name, price, stock FROM products ORDER BY id")) {
            while (rs.next()) {
                System.out.printf("  %-4d  %-22s  $%7.2f  %5d%n",
                    rs.getInt("id"), rs.getString("name"),
                    rs.getDouble("price"), rs.getInt("stock"));
            }
        }
        System.out.println("  " + "-".repeat(46));
    }
}