package com.keyloop.unifiedservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Supplies the database the tests run against, in this order:
 *
 * <ol>
 *   <li><b>Docker available</b> → a PostgreSQL container started by
 *       Testcontainers and wired in with {@code @ServiceConnection}. The default
 *       on machines/CI that have Docker.</li>
 *   <li><b>No Docker, but the shared PostgreSQL server is reachable</b> → that
 *       server, always on its dedicated test database. The database name is
 *       forced: the tests run {@code ddl-auto=create-drop} (they drop everything
 *       they create), so they must never land in the {@code postgres} database
 *       the application uses.</li>
 *   <li><b>Neither</b> → H2 in PostgreSQL compatibility mode, so
 *       {@code mvnw test} still works with neither Docker nor a PostgreSQL
 *       server.</li>
 * </ol>
 *
 * Exactly one of the three beans below is registered per run; which one, and
 * why, is logged at the start of the test output.
 *
 * <p>Hosts, credentials and URLs live in
 * {@code src/test/resources/test-database.yaml} (see {@link TestDatabaseSettings}
 * for the precedence rules) — not in this class.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	@Conditional(DatabaseEnvironment.UsingContainer.class)
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(TestDatabaseSettings.containerImage()));
	}

	/**
	 * Connection details for the shared PostgreSQL server, on the dedicated
	 * test database only.
	 */
	@Bean
	@Conditional(DatabaseEnvironment.UsingPostgres.class)
	JdbcConnectionDetails postgresTestDatabase() {
		return new ServerConnectionDetails();
	}

	/**
	 * In-memory fallback (see {@code test.database.h2} in the config file for
	 * what its URL tunes).
	 */
	@Bean
	@Conditional(DatabaseEnvironment.UsingH2.class)
	JdbcConnectionDetails h2ConnectionDetails() {
		return new EmbeddedH2ConnectionDetails();
	}

	/**
	 * Probes once per JVM whether a usable database exists for options 2 and 3,
	 * so a failing run is explainable from the test output alone.
	 */
	static final class DatabaseEnvironment {

		private static final Logger logger = LoggerFactory.getLogger(DatabaseEnvironment.class);

		private static Boolean dockerAvailable;

		/** {@code null} when no usable PostgreSQL server is reachable. */
		private static String postgresUrl;
		private static boolean postgresDecided;

		private DatabaseEnvironment() {
		}

		/** True when a Docker daemon exists (probed once per JVM). */
		static synchronized boolean dockerAvailable() {
			if (dockerAvailable == null) {
				boolean available;
				try {
					available = DockerClientFactory.instance().isDockerAvailable();
				}
				catch (Throwable ex) {
					available = false;
				}
				logger.info(available
						? "Docker available - tests will run against a PostgreSQL container"
						: "No Docker environment found - tests will try the shared PostgreSQL server, then embedded H2");
				dockerAvailable = available;
			}
			return dockerAvailable;
		}

		/**
		 * The shared server's test database, or {@code null} when there is no
		 * candidate or it does not answer. The probe adds a short connect
		 * timeout so an unreachable host falls through to H2 quickly instead of
		 * hanging.
		 */
		static synchronized String postgresUrl() {
			if (!postgresDecided) {
				postgresDecided = true;
				String candidate = TestDatabaseSettings.postgresUrl();
				postgresUrl = candidate != null ? probe(candidate) : noCandidate();
			}
			return postgresUrl;
		}

		private static String noCandidate() {
			logger.info("No PostgreSQL test database configured - tests will run against embedded H2");
			return null;
		}

		private static String probe(String candidate) {
			String probeUrl = candidate + (candidate.contains("?") ? "&" : "?") + "connectTimeout=3";
			try (Connection connection = DriverManager.getConnection(probeUrl,
					TestDatabaseSettings.postgresUsername(), TestDatabaseSettings.postgresPassword())) {
				if (connection.isValid(5)) {
					logger.info("PostgreSQL test database available - tests will run against {}", candidate);
					return candidate;
				}
				logger.info("PostgreSQL test candidate {} did not validate - tests will run against embedded H2", candidate);
			}
			catch (Exception ex) {
				logger.info("PostgreSQL test candidate {} unreachable ({}: {}) - tests will run against embedded H2",
						candidate, ex.getClass().getSimpleName(), rootMessage(ex));
			}
			return null;
		}

		private static String rootMessage(Throwable ex) {
			Throwable current = ex;
			while (current.getCause() != null) {
				current = current.getCause();
			}
			String message = current.getMessage();
			return message != null ? message.lines().findFirst().orElse(current.toString()) : current.toString();
		}

		static final class UsingContainer implements Condition {
			@Override
			public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
				return dockerAvailable();
			}
		}

		static final class UsingPostgres implements Condition {
			@Override
			public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
				return !dockerAvailable() && postgresUrl() != null;
			}
		}

		static final class UsingH2 implements Condition {
			@Override
			public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
				return !dockerAvailable() && postgresUrl() == null;
			}
		}
	}

	private static final class ServerConnectionDetails implements JdbcConnectionDetails {

		@Override
		public String getJdbcUrl() {
			return DatabaseEnvironment.postgresUrl();
		}

		@Override
		public String getUsername() {
			return TestDatabaseSettings.postgresUsername();
		}

		@Override
		public String getPassword() {
			return TestDatabaseSettings.postgresPassword();
		}
	}

	private static final class EmbeddedH2ConnectionDetails implements JdbcConnectionDetails {

		@Override
		public String getJdbcUrl() {
			return TestDatabaseSettings.h2Url();
		}

		@Override
		public String getUsername() {
			return TestDatabaseSettings.h2Username();
		}

		@Override
		public String getPassword() {
			return TestDatabaseSettings.h2Password();
		}
	}

}
