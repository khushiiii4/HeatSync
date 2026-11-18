package com.thermogrid.simulator;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Run in a separate JVM by SingleInstanceLockTest: tries to open the given H2 file database.
 * Exit 0 = opened, 3 = refused because another process holds it, 1 = any other error.
 */
public final class H2OpenProbe {

    private H2OpenProbe() {
    }

    public static void main(String[] args) {
        try (Connection c = DriverManager.getConnection(args[0], "sa", "")) {
            System.out.println("OPENED");
            System.exit(0);
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            System.out.println(m);
            System.exit(m.contains("may be already in use") ? 3 : 1);
        }
    }
}
