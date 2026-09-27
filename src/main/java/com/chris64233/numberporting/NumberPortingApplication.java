package com.chris64233.numberporting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NumberPortingApplication {
    public static void main(String[] args) {
        SpringApplication.run(NumberPortingApplication.class, args);
    }
}
