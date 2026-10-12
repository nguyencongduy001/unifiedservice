package com.keyloop.unifiedservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		// Tests start against an empty database: create the schema namespace
		// and tables instead of relying on schema.sql (which production runs
		// once, where spring.jpa.hibernate.ddl-auto=update then finds them).
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true"
})
class UnifiedserviceApplicationTests {

	@Test
	void contextLoads() {
	}

}
