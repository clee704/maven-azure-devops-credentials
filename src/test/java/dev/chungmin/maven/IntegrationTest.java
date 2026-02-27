package dev.chungmin.maven;

/**
 * Marker interface for integration tests that require Azure CLI authentication.
 * These tests are excluded from the default build and can be run with:
 * <pre>
 * mvn test -DincludeIntegrationTests
 * </pre>
 */
public interface IntegrationTest {
}
