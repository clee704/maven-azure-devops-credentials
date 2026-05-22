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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import java.time.OffsetDateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import reactor.core.publisher.Mono;

@RunWith(MockitoJUnitRunner.Silent.class)
public class TokenAcquisitionTest {

  @Mock private TokenCredential mockCredential;

  @Test
  public void blockForToken_allocatesFreshTokenRequestContextPerCall() {
    // N10 regression catcher: every blockForToken call must allocate a NEW
    // TokenRequestContext via newTokenRequest(). TokenRequestContext is mutable (addScopes /
    // setClaims / setTenantId / setCaeEnabled) — a future Azure Identity revision that
    // starts mutating the request inside getToken would corrupt a shared static constant
    // JVM-wide and silently issue wrong-scope tokens. Capture the per-call argument and
    // assert the two contexts are distinct instances.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("t1", OffsetDateTime.now().plusHours(1))));
    TokenAcquisition.blockForToken(mockCredential);
    TokenAcquisition.blockForToken(mockCredential);
    org.mockito.ArgumentCaptor<com.azure.core.credential.TokenRequestContext> captor =
        org.mockito.ArgumentCaptor.forClass(com.azure.core.credential.TokenRequestContext.class);
    verify(mockCredential, times(2)).getToken(captor.capture());
    java.util.List<com.azure.core.credential.TokenRequestContext> contexts = captor.getAllValues();
    assertNotSame(
        "Each blockForToken call must allocate a fresh TokenRequestContext — a shared static"
            + " constant would be JVM-wide-corruptible if a future SDK mutates the request",
        contexts.get(0),
        contexts.get(1));
  }

  @Test(timeout = 5000)
  public void blockForToken_throwsIllegalStateExceptionWhenCredentialStalls() {
    // F2 regression catcher: a future refactor that drops the Duration arg from .block()
    // — i.e., reverts blockForToken to .block() instead of .block(timeout) — would silently
    // re-introduce the indefinite-hang risk on a stuck credential (IMDS on a non-Azure VM,
    // wedged `az` CLI, blackholed login.microsoftonline.com). Feed Mono.never() with a tiny
    // test-only timeout and assert IllegalStateException fires within the deadline. The
    // production timeout is 2 minutes (TOKEN_ACQUISITION_TIMEOUT) — too slow to wait in a
    // unit test, hence the package-private 2-arg overload as the test seam.
    TokenCredential stuck = mock(TokenCredential.class);
    when(stuck.getToken(any())).thenReturn(Mono.never());
    try {
      TokenAcquisition.blockForToken(stuck, java.time.Duration.ofMillis(200));
      fail("blockForToken must throw IllegalStateException when the credential never completes");
    } catch (IllegalStateException expected) {
      // expected on .block(Duration) timeout
    }
  }

  // N41 system property tests — refreshThresholdSeconds() is the operator knob that lets a
  // user widen/narrow the cache near-expiry window without recompiling. Each test clears
  // the property in @After so the JVM-wide System property doesn't leak into sibling tests
  // (the AzureDevOpsCredentialsExtensionTest's `acquireToken_refreshesWithinExpiryWindow`
  // mock-time test in particular assumes the default 300s threshold).
  @org.junit.After
  public void clearRefreshThresholdProperty() {
    System.clearProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY);
  }

  @Test
  public void refreshThresholdSeconds_returnsDefaultWhenPropertyUnset() {
    System.clearProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY);
    assertEquals(
        "Unset property must yield the documented 5-minute (300s) default — operators who"
            + " never set the knob should see the same behavior the constant gave before N41.",
        300L,
        TokenAcquisition.refreshThresholdSeconds());
  }

  @Test
  public void refreshThresholdSeconds_acceptsValidNumericOverride() {
    System.setProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY, "600");
    assertEquals(
        "A valid numeric override must be honored exactly — operators tuning for long-running"
            + " daemon builds rely on this knob being literal seconds, not seconds-with-jitter.",
        600L,
        TokenAcquisition.refreshThresholdSeconds());
  }

  @Test
  public void refreshThresholdSeconds_acceptsZeroToDisableProactiveRefresh() {
    // 0 is a legitimate value — disables proactive refresh entirely, only refreshes on
    // actual wire-level expiry. Documented use case: an operator who wants to minimize
    // `az` subprocess churn at the cost of accepting occasional 401-and-retry on the
    // expiry boundary.
    System.setProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY, "0");
    assertEquals(0L, TokenAcquisition.refreshThresholdSeconds());
  }

  @Test
  public void refreshThresholdSeconds_acceptsVeryLargeOverrideForTestingForcedRefresh() {
    // Documented refresh-validation pattern: setting the threshold to a huge value forces
    // every isNearExpiry() check to return true, which routes every acquireToken() onto
    // the slow path. Required to make the live-refresh path observable end-to-end in a
    // sub-minute test (instead of waiting past the wire token's actual TTL).
    System.setProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY, "99999999");
    assertEquals(99999999L, TokenAcquisition.refreshThresholdSeconds());
  }

  @Test
  public void refreshThresholdSeconds_fallsBackToDefaultOnNegativeValue() {
    // Negative thresholds would treat already-acquired tokens as past expiry (since
    // expiresAt.isBefore(now + (-N seconds)) flips the comparison direction). Defaulting
    // is safer than honoring a value that can't possibly be intentional.
    System.setProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY, "-1");
    assertEquals(300L, TokenAcquisition.refreshThresholdSeconds());
  }

  @Test
  public void refreshThresholdSeconds_fallsBackToDefaultOnNonNumericValue() {
    System.setProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY, "notanumber");
    assertEquals(300L, TokenAcquisition.refreshThresholdSeconds());
  }

  @Test
  public void refreshThresholdSeconds_handlesWhitespaceAroundNumericValue() {
    // -D properties on the mvn command line sometimes pick up trailing whitespace from
    // shell substitution (e.g., "${SLEEP_SEC} " in a pom.xml). Be lenient on whitespace
    // rather than silently dropping to the default and confusing the operator.
    System.setProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY, "  450  ");
    assertEquals(450L, TokenAcquisition.refreshThresholdSeconds());
  }

  @Test
  public void isNearExpiry_honorsSystemPropertyOverride() {
    // End-to-end verify that isNearExpiry uses refreshThresholdSeconds() — a token that
    // expires in 10 minutes should NOT be near-expiry under the default 5-min threshold,
    // but IS near-expiry under a widened 15-min threshold. Catches a refactor that
    // accidentally bakes the threshold into a constant local instead of re-reading the
    // property each call.
    AccessToken tenMinAway = new AccessToken("t", OffsetDateTime.now().plusMinutes(10));
    // Default (300s = 5min): 10 min from expiry is well outside the 5-min window.
    System.clearProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY);
    assertFalse(
        "Default 5-min threshold must treat a 10-min-away token as fresh, otherwise we"
            + " thrash the cache on every request.",
        TokenAcquisition.isNearExpiry(tenMinAway));
    // Widened (900s = 15min): 10 min from expiry is INSIDE the 15-min window → near-expiry.
    System.setProperty(TokenAcquisition.REFRESH_THRESHOLD_SECONDS_PROPERTY, "900");
    assertTrue(
        "Widened 15-min threshold must treat a 10-min-away token as near-expiry — proves the"
            + " hot-path read picks up the latest property value, not a stale class-init copy.",
        TokenAcquisition.isNearExpiry(tenMinAway));
  }
}
