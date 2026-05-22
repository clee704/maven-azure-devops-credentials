// Copyright (C) 2024 the original author or authors.
// See the LICENSE.txt file distributed with this work for additional
// information regarding copyright ownership.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package dev.chungmin.maven;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Install/verify helpers for the Aether session's config-property map. Works around the read-only
 * lock that Maven applies to {@link DefaultRepositorySystemSession} before {@code
 * afterProjectsRead} fires by trying the public API first and falling back to a reflective write
 * into the underlying {@code HashMap} — the {@code Collections.unmodifiableMap} view Aether exposes
 * is a live view over the same map, so consumers see the new entry either way.
 *
 * <p>Stateless utility class (the failure-rate-limit gates below are JVM-static, scoped to the
 * extension's classloader). Per-build reset is provided by {@link #resetFailureGates()} for tests
 * and for mvnd reuse — see {@link AzureDevOpsCredentialsExtension#afterSessionStart}.
 */
final class SessionConfigInstaller {

  private static final Logger log = LoggerFactory.getLogger(SessionConfigInstaller.class);

  // Per-JVM gates: a failure in installSessionConfig's reflective fallback (or in the
  // verifyConfigInstalled post-check) will repeat identically for every repo in a workspace's
  // afterProjectsRead loop, so we only log the first occurrence. Mirrors the same
  // log-on-transition pattern as LiveBearerHeadersMap.inFailureState. Per-JVM is safe within
  // a single `mvn` invocation; under Maven Daemon (mvnd) the extension class is reused across
  // builds, so we reset the gates at afterSessionStart to keep the at-most-once-per-build
  // contract intact.
  private static final AtomicBoolean reflectionFailureLogged = new AtomicBoolean(false);
  private static final AtomicBoolean verificationFailureLogged = new AtomicBoolean(false);

  private SessionConfigInstaller() {}

  // Per-build reset hook. Called from afterSessionStart (production: mvnd reuse) and from
  // JUnit @Before (tests: static state must not bleed across test methods or coverage drops).
  static void resetFailureGates() {
    reflectionFailureLogged.set(false);
    verificationFailureLogged.set(false);
  }

  /**
   * Install a key/value into the Aether session's config properties, working around the read-only
   * lock that Maven applies to the session before {@code afterProjectsRead} fires. We try the
   * public API first; if that throws {@link IllegalStateException}, we mutate the underlying {@code
   * HashMap} directly via reflection — the {@code Collections.unmodifiableMap} view that Aether
   * exposes is a live view over the same map, so consumers see the new entry.
   */
  static void installSessionConfig(
      DefaultRepositorySystemSession repoSession, String key, Object value) {
    installSessionConfig(repoSession, key, value, DefaultRepositorySystemSession.class);
  }

  @SuppressWarnings("unchecked")
  static void installSessionConfig(
      DefaultRepositorySystemSession repoSession, String key, Object value, Class<?> targetClass) {
    if (reflectionFailureLogged.get()) {
      // A prior install attempt in this build already failed the reflective fallback. Every
      // subsequent feed would hit the same setConfigProperty IllegalStateException and the
      // same getDeclaredField failure (the environment hasn't changed mid-build). Skip the
      // wasted exception cycle; the first feed's log.error already informed the user.
      return;
    }
    try {
      repoSession.setConfigProperty(key, value);
      // S1: also verify on the public-API success path. The post-install check was added
      // for the reflective-fallback branch (where Aether's live-view contract is the
      // load-bearing assumption), but the same silent-no-op failure mode applies here: a
      // future Maven/Aether or a custom DefaultRepositorySystemSession subclass (mvnd,
      // an outer extension that wraps the session, a ProfiledRepositorySystemSession
      // decorator) could make setConfigProperty return normally without the value actually
      // landing in getConfigProperties(). Mirror the rest of the file's symmetric defensive
      // posture: one check covers every install code path.
      verifyConfigInstalled(repoSession.getConfigProperties(), key, value, "setConfigProperty");
      return;
    } catch (IllegalStateException ignored) {
      // Maven 3.x marks the RepositorySystemSession read-only by the time afterProjectsRead
      // fires; fall through to the reflective write.
    }
    try {
      java.lang.reflect.Field f = targetClass.getDeclaredField("configProperties");
      f.setAccessible(true);
      ((Map<String, Object>) f.get(repoSession)).put(key, value);
    } catch (ReflectiveOperationException | RuntimeException e) {
      // Broadened from just ReflectiveOperationException to also catch the runtime exceptions
      // setAccessible(true) and Map.put can throw — InaccessibleObjectException (Java 9+ JPMS),
      // SecurityException (custom SecurityManager), IllegalArgumentException (target object
      // isn't an instance of the field's declaring class), UnsupportedOperationException
      // (future Aether changing configProperties to an immutable map). All of those should
      // route through the same graceful-degradation path as a NoSuchFieldException — log
      // once, fall back to the boot-time settings injection, build keeps working.
      if (reflectionFailureLogged.compareAndSet(false, true)) {
        // Trailing `e` (in addition to the `{}` Cause placeholder filled by e.toString())
        // attaches the stack trace via SLF4J's parameterized API — SLF4J treats the last
        // arg as a Throwable when there are more args than placeholders. Without it the
        // user troubleshooting this rare path (JPMS, SecurityManager, immutable Aether
        // Map) gets the message but no JDK frame pointing at the rejection.
        log.error(
            "Could not install live Authorization header for '{}'; mid-build token refresh is"
                + " disabled and `mvn` invocations longer than the Entra token TTL"
                + " (~60-75 minutes) will fail with HTTP 401. Subsequent feeds in this build"
                + " will fail identically; suppressing further error logs. Cause: {}",
            key,
            e.toString(),
            e);
      }
      return;
    }
    // Defensive verification: confirm the reflective write is visible through Aether's public
    // config-properties view. If a future Aether version changes the live-view contract (e.g.
    // snapshots configProperties at session-construction time), our reflective write would
    // silently no-op and the build would 401 ~75 min later with no actionable signal.
    verifyConfigInstalled(repoSession.getConfigProperties(), key, value, "reflective put");
  }

  static void verifyConfigInstalled(
      Map<String, Object> configPropertiesView, String key, Object value, String installPath) {
    // Reference equality is what we actually care about: did the same LiveBearerHeadersMap
    // instance we wrote show up in the view? Using Objects.equals here would dispatch to
    // AbstractMap.equals on a mismatch, which calls size() -> entrySet() -> credential.getToken()
    // — an unwanted side effect during what is supposed to be a passive diagnostic.
    if (configPropertiesView.get(key) != value
        && verificationFailureLogged.compareAndSet(false, true)) {
      // N39: include `installPath` in the message so a user reading the diagnostic knows
      // which install code path was responsible. Pre-N39 this message was hard-coded to
      // "Reflective install of '{}' completed..." — accurate when the post-install check
      // only ran on the reflective-fallback branch, but misleading after S1 mirrored the
      // check onto the public-API success path. A future custom DefaultRepositorySystemSession
      // subclass (mvnd, an outer extension, a profile-decorator) that silent-no-ops
      // setConfigProperty would have shown "Reflective install..." to a user who never
      // executed any reflection — chasing the wrong root cause. The discriminator string
      // ("setConfigProperty" or "reflective put") matches what a maintainer would grep for
      // when triaging.
      log.error(
          "Install of '{}' via {} completed but value is not visible via getConfigProperties();"
              + " mid-build token refresh may not take effect. Subsequent feeds in this build"
              + " will fail identically; suppressing further error logs.",
          key,
          installPath);
    }
  }
}
