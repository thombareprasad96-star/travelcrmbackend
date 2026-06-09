package com.crm.travelcrm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
      // <-- required for @CreatedDate / @LastModifiedDate to work
public class TravelcrmApplication {
	public static void main(String[] args) {
		// Run this once anywhere to get encoded password
		SpringApplication.run(TravelcrmApplication.class, args);
		System.out.println(new BCryptPasswordEncoder()
				.encode("Password@123"));
	}
}
