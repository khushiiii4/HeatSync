package com.thermogrid.topology.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class SeedOnStartup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedOnStartup.class);

    private final TopologySeeder seeder;

    public SeedOnStartup(TopologySeeder seeder) {
        this.seeder = seeder;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seeder.seed();
            log.info("Topology seed checked");
        } catch (DataIntegrityViolationException e) {
            // Another instance seeded the same rows concurrently: the data exists, which is the goal.
            log.warn("Topology seed raced with another instance; data already present");
        }
    }
}