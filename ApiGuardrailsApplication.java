package com.grid07;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ApiGuardrailsApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGuardrailsApplication.class, args);
    }
}
