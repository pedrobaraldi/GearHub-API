package com.gearhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GearhubApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(GearhubApiApplication.class, args);
	}

}
