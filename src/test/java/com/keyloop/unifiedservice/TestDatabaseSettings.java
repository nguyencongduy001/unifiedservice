package com.keyloop.unifiedservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

/**
 * Connection settings for the test database, loaded once from
 * {@code src/test/resources/test-database.yaml}. Nothing about hosts,
 * credentials or URLs lives in Java: change the file, or override a single run
 * with {@code TEST_DB_URL} / {@code TEST_DB_USERNAME} / {@code TEST_DB_PASSWORD}.
 *
 * <p>Precedence per value: {@code TEST_DB_*} → this file → {@code DB_URL} /
 * {@code DB_USERNAME} / {@code DB_PASSWORD} → built-in default.
 */
final class TestDatabaseSettings {

	private static final Logger logger = LoggerFactory.getLogger(TestDatabaseSettings.class);

	private static final String RESOURCE = "test-database.yaml";

	/** Flattened YAML keys are prefixed, e.g. {@code test.database.postgres.url}. */
	private static final String PREFIX = "test.database.";

	/** Database the tests are allowed to drop and recreate. */
	static final String TEST_DATABASE = "unifiedservice_test";

	/** Last resort when the config file is missing or unreadable. */
	private static final String DEFAULT_H2_URL = "jdbc:h2:mem:unifiedservice-test;MODE=PostgreSQL;"
			+ "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

	private static final String DEFAULT_CONTAINER_IMAGE = "postgres:17-alpine";

	private static final Properties values = load();

	private TestDatabaseSettings() {
	}

	// ---------------------------------------------------------------- postgres

	/**
	 * Candidate URL of the shared server's test database, or {@code null} when
	 * none is configured (then only options 1 and 3 of the selection order can
	 * be used).
	 */
	static String postgresUrl() {
		String explicit = override("TEST_DB_URL");
		if (explicit != null) {
			return explicit;
		}
		String configured = fileValue("postgres.url");
		if (configured != null) {
			return configured;
		}
		return forceTestDatabase(firstNonBlank(
				System.getProperty("DB_URL"), System.getenv("DB_URL")));
	}

	static String postgresUsername() {
		return firstNonBlank(
				override("TEST_DB_USERNAME"), fileValue("postgres.username"),
				System.getProperty("DB_USERNAME"), System.getenv("DB_USERNAME"),
				"postgres");
	}

	static String postgresPassword() {
		return firstNonBlank(
				override("TEST_DB_PASSWORD"), fileValue("postgres.password"),
				System.getProperty("DB_PASSWORD"), System.getenv("DB_PASSWORD"),
				"postgres");
	}

	// --------------------------------------------------------------------- h2

	static String h2Url() {
		String configured = fileValue("h2.url");
		return configured != null ? configured : DEFAULT_H2_URL;
	}

	static String h2Username() {
		String configured = fileValue("h2.username");
		return configured != null ? configured : "sa";
	}

	static String h2Password() {
		String configured = fileValue("h2.password");
		return configured != null ? configured : "";
	}

	// ---------------------------------------------------------------- container

	static String containerImage() {
		String configured = fileValue("container.image");
		return configured != null ? configured : DEFAULT_CONTAINER_IMAGE;
	}

	// ----------------------------------------------------------------- helpers

	/** Reads a one-off override ({@code TEST_DB_*}) from -D property or env var. */
	private static String override(String name) {
		return firstNonBlank(System.getProperty(name), System.getenv(name));
	}

	/**
	 * Rewrites {@code jdbc:postgresql://host:5432/anything} to
	 * {@code jdbc:postgresql://host:5432/unifiedservice_test}, keeping any
	 * query string. Returns null for anything that is not a PostgreSQL URL.
	 */
	private static String forceTestDatabase(String jdbcUrl) {
		if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql:")) {
			return null;
		}
		int query = jdbcUrl.indexOf('?');
		String base = query >= 0 ? jdbcUrl.substring(0, query) : jdbcUrl;
		String suffix = query >= 0 ? jdbcUrl.substring(query) : "";
		int slash = base.lastIndexOf('/');
		if (slash < 0) {
			return null;
		}
		return base.substring(0, slash + 1) + TEST_DATABASE + suffix;
	}

	/** Reads a flattened key; blank values count as "not set". */
	private static String fileValue(String key) {
		return firstNonBlank(values.getProperty(PREFIX + key));
	}

	private static String firstNonBlank(String... candidates) {
		for (String candidate : candidates) {
			if (candidate != null && !candidate.isBlank()) {
				return candidate;
			}
		}
		return null;
	}

	private static Properties load() {
		Properties loaded = new Properties();
		ClassPathResource resource = new ClassPathResource(RESOURCE);
		if (!resource.exists()) {
			logger.warn("Test database config {} not found on the classpath - built-in defaults will be used", RESOURCE);
			return loaded;
		}
		try {
			YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
			for (PropertySource<?> propertySource : loader.load("test-database", resource)) {
				if (propertySource instanceof EnumerablePropertySource<?> enumerable) {
					for (String name : enumerable.getPropertyNames()) {
						loaded.setProperty(name, String.valueOf(enumerable.getProperty(name)));
					}
				}
			}
			logger.debug("Loaded {} test database settings from {}", loaded.size(), RESOURCE);
		}
		catch (Exception ex) {
			logger.warn("Could not read {} ({}: {}) - built-in defaults will be used",
					RESOURCE, ex.getClass().getSimpleName(), ex.getMessage());
		}
		return loaded;
	}

}
