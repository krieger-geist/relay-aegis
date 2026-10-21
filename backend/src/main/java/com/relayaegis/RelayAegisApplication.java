package com.relayaegis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RelayAegisApplication {
    public static void main(String[] args) {
        SpringApplication.run(RelayAegisApplication.class, args);
    }
}
