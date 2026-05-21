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

import static org.junit.Assert.*;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.junit.Before;
import org.junit.Test;

public class SessionConfigInstallerTest {

  @Before
  public void setUp() {
    // Reset the JVM-static failure gates: the rate-limit-once-per-build invariant the
    // gates enforce in production must not bleed across tests as a flaky "already flipped"
    // condition.
    SessionConfigInstaller.resetFailureGates();
  }

  // Helper for the N5 test: has a `configProperties` field that getDeclaredField finds, but
  // Field.get(repoSession) then throws IllegalArgumentException because the live
  // DefaultRepositorySystemSession isn't an instance of UnrelatedConfigOwner. Triggers the
  // newly-broadened RuntimeException catch in installSessionConfig.
  @SuppressWarnings("unused")
  static class UnrelatedConfigOwner {
    java.util.Map<String, Object> configProperties = new java.util.HashMap<>();
  }

  @Test
  public void installSessionConfig_writableSession() {
    DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
    SessionConfigInstaller.installSessionConfig(s, "k1", "v1");
    assertEquals("v1", s.getConfigProperties().get("k1"));
  }

  @Test
  public void installSessionConfig_readOnlySession_usesReflectionFallback() {
    DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
    s.setReadOnly();
    try {
      s.setConfigProperty("rejected", "value");
      fail("Expected IllegalStateException on read-only session");
    } catch (IllegalStateException expected) {
      /* expected */
    }
    SessionConfigInstaller.installSessionConfig(s, "k2", "v2");
    assertEquals("v2", s.getConfigProperties().get("k2"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void verifyConfigInstalled_logsOnMismatchOnceAndSuppressesRepeats() throws Exception {
    // N23: previously this test invoked verifyConfigInstalled on both branches but asserted
    // nothing — a regression that dropped the log.error or the verificationFailureLogged
    // compareAndSet gate would have passed silently. Mirror the sibling
    // installSessionConfig_reflectionFailure_swallowsAndLogs pattern: assert the
    // verificationFailureLogged gate flips on the first mismatch, stays flipped on a second
    // mismatch (rate-limit gate holds), and doesn't flip from a match call. The gate is a
    // private static AtomicBoolean — accessed via reflection (same justification as N21's
    // sharedCachedToken inspection; @Before's resetFailureGates() ensures clean state).
    java.lang.reflect.Field gateField =
        SessionConfigInstaller.class.getDeclaredField("verificationFailureLogged");
    gateField.setAccessible(true);
    java.util.concurrent.atomic.AtomicBoolean gate =
        (java.util.concurrent.atomic.AtomicBoolean) gateField.get(null);
    assertFalse("Pre-condition: @Before resetFailureGates left gate clear", gate.get());

    // Match path (value visible): no-op — gate must stay clear.
    SessionConfigInstaller.verifyConfigInstalled(
        java.util.Collections.singletonMap("k3", (Object) "v3"), "k3", "v3");
    assertFalse("Match path must not trip the gate", gate.get());

    // First mismatch: gate flips (and the log.error fires; we don't intercept stderr here
    // because the JaCoCo coverage check + the gate transition together pin the behavior).
    SessionConfigInstaller.verifyConfigInstalled(java.util.Collections.emptyMap(), "k3", "v3");
    assertTrue("First mismatch must trip the rate-limit gate", gate.get());

    // Second mismatch: gate stays flipped (compareAndSet(false, true) returns false, log
    // call is skipped). A regression that removed the gate would silently re-log here.
    SessionConfigInstaller.verifyConfigInstalled(java.util.Collections.emptyMap(), "k4", "v4");
    assertTrue("Gate must stay flipped on second mismatch (rate-limit invariant)", gate.get());
  }

  @Test
  public void installSessionConfig_swallowsRuntimeExceptionFromReflectiveFallback() {
    // N5: setAccessible(true) + Field.get() + Map.put() can throw RuntimeExceptions outside
    // the ReflectiveOperationException hierarchy — InaccessibleObjectException (JPMS),
    // SecurityException, IllegalArgumentException (target object isn't an instance of the
    // declaring class), UnsupportedOperationException (immutable Map). The catch must cover
    // them all so the graceful degradation path runs instead of bubbling up as a
    // MavenExecutionException that aborts the build. We exercise the IllegalArgumentException
    // branch here by passing a targetClass whose `configProperties` field exists but lives on
    // a class the session isn't an instance of — Field.get(repoSession) then throws IAE.
    DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
    s.setReadOnly();
    SessionConfigInstaller.installSessionConfig(s, "k", "v", UnrelatedConfigOwner.class);
    assertFalse(s.getConfigProperties().containsKey("k"));
  }

  @Test
  public void installSessionConfig_reflectionFailure_swallowsAndLogs() {
    DefaultRepositorySystemSession s = new DefaultRepositorySystemSession();
    s.setReadOnly();
    // Object.class has no "configProperties" field -> NoSuchFieldException -> caught and logged.
    SessionConfigInstaller.installSessionConfig(s, "k3", "v3", Object.class);
    assertFalse(s.getConfigProperties().containsKey("k3"));
  }

  @Test
  public void installSessionConfig_skipsWorkAfterPriorReflectionFailureInSameBuild() {
    // L3: once the reflectionFailureLogged gate has tripped (a prior repo's reflective
    // install failed), every subsequent installSessionConfig call should early-return —
    // both the wasted setConfigProperty IllegalStateException and the wasted
    // getDeclaredField failure are avoided. Verifies the gate-check at the top of the
    // 4-arg overload.
    DefaultRepositorySystemSession failingSession = new DefaultRepositorySystemSession();
    failingSession.setReadOnly();
    // First call: trip the gate.
    SessionConfigInstaller.installSessionConfig(failingSession, "k", "v", Object.class);
    // Second call: now hits the early-return at the top — won't even attempt setConfigProperty,
    // which means a WRITABLE session WOULDN'T get the value installed either.
    DefaultRepositorySystemSession writableSession = new DefaultRepositorySystemSession();
    SessionConfigInstaller.installSessionConfig(writableSession, "k2", "v2");
    assertFalse(
        "Second call should early-return after prior reflective failure tripped the gate",
        writableSession.getConfigProperties().containsKey("k2"));
  }
}
