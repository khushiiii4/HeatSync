package com.thermogrid.cooling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Singleton by design: two controllers would fight over the same outputs (see SingleInstanceGuard). */
@SpringBootApplication
public class CoolingControllerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoolingControllerApplication.class, args);
    }
}
