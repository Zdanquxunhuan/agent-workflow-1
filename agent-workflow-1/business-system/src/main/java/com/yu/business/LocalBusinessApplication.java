package com.yu.business;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LocalBusinessApplication {
    public static void main(String[] args) { SpringApplication.run(LocalBusinessApplication.class, args); }
}
