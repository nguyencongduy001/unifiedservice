package com.keyloop.unifiedservice;

import org.springframework.boot.SpringApplication;

public class TestUnifiedserviceApplication {

	public static void main(String[] args) {
		SpringApplication.from(UnifiedserviceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
