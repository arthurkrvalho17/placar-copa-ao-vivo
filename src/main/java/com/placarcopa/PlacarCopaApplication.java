package com.placarcopa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlacarCopaApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlacarCopaApplication.class, args);
    }
}
