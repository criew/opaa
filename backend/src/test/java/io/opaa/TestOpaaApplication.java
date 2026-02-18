package io.opaa;

import org.springframework.boot.SpringApplication;

public class TestOpaaApplication {

	public static void main(String[] args) {
		SpringApplication.from(OpaaApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
