package com.vidsage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class VidSageApplication {

    public static void main(String[] args) {
        SpringApplication.run(VidSageApplication.class, args);
    }
}
