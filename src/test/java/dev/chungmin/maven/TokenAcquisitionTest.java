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
}
