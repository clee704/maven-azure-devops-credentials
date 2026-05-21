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

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.eclipse.aether.repository.Authentication;
import org.eclipse.aether.repository.AuthenticationSelector;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;

/**
 * Aether {@link AuthenticationSelector} that surfaces a Basic Authorization header for Azure DevOps
 * Maven feeds via the resolver's selector API. Fallback path: the live {@code HTTP_HEADERS}
 * mechanism in {@link LiveBearerHeadersMap} takes precedence for modern Aether HTTP transport, but
 * transports that bypass {@code HttpTransporter} (Wagon- based plugins) still flow through here.
 *
 * <p>Takes its shared state (cache, credential supplier, token acquirer) via constructor so it's
 * decoupled from {@link AzureDevOpsCredentialsExtension} — no implicit outer-class capture,
 * dependencies are visible at the call site.
 */
final class AzureDevOpsAuthSelector implements AuthenticationSelector {

  private final AuthenticationSelector delegate;
  private final Supplier<TokenCredential> credentialSupplier;
  private final AtomicReference<AccessToken> sharedCachedToken;
  // Slow-path token acquirer: takes (credential, cacheRef) and returns the Bearer string
  // (or null on failure). Bound to AzureDevOpsCredentialsExtension::getAccessToken at
  // construction time so this class stays decoupled from the extension type — the only
  // shape contract is the BiFunction signature.
  private final BiFunction<TokenCredential, AtomicReference<AccessToken>, String> tokenAcquirer;

  AzureDevOpsAuthSelector(
      AuthenticationSelector delegate,
      Supplier<TokenCredential> credentialSupplier,
      AtomicReference<AccessToken> sharedCachedToken,
      BiFunction<TokenCredential, AtomicReference<AccessToken>, String> tokenAcquirer) {
    this.delegate = delegate;
    this.credentialSupplier = credentialSupplier;
    this.sharedCachedToken = sharedCachedToken;
    this.tokenAcquirer = tokenAcquirer;
  }

  @Override
  public Authentication getAuthentication(RemoteRepository repository) {
    if (delegate != null) {
      Authentication existing = delegate.getAuthentication(repository);
      if (existing != null) {
        return existing;
      }
    }
    if (!AzureDevOpsCredentialsExtension.isAzureDevOpsUrl(repository.getUrl())) {
      return null;
    }
    // Fast path: the cache may already be populated by either the boot fetch in
    // afterProjectsRead OR by a sibling repository's slow-path fetch below. In both cases
    // we avoid a second `az` subprocess fork for AzureCliCredential — matches the N3/M1
    // cache hit pattern on the live-headers path. The DCL slow path below also goes
    // through tokenAcquirer (extension's getAccessToken 2-arg form), which populates
    // sharedCachedToken on success so a later live-path entrySet() call hits the cache too.
    AccessToken cached = sharedCachedToken.get();
    if (cached != null && !TokenAcquisition.isNearExpiry(cached)) {
      return buildAuth(cached.getToken());
    }
    // Slow path: cache miss or near-expiry. Aether can call selectors from multiple
    // resolver threads concurrently, so DCL keeps the per-instance fetch attempt
    // coalesced; the cache itself acts as the populated-marker for re-entry, so the
    // separate tokenAttempted flag this used to keep is no longer needed.
    synchronized (this) {
      cached = sharedCachedToken.get();
      if (cached != null && !TokenAcquisition.isNearExpiry(cached)) {
        return buildAuth(cached.getToken());
      }
      // F1 inversion + N6 fallback now live inside getAccessToken itself: on refresh
      // failure it tries the still-real-valid cached token before warning, so a non-null
      // return here is either a fresh token OR a cached fallback (both acceptable to
      // serve), and a null return means refresh failed AND fallback was unavailable.
      String token = tokenAcquirer.apply(credentialSupplier.get(), sharedCachedToken);
      if (token == null) {
        return null;
      }
      return buildAuth(token);
    }
  }

  private Authentication buildAuth(String token) {
    return new AuthenticationBuilder().addUsername("azure").addPassword(token).build();
  }
}
