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
import com.azure.core.credential.TokenRequestContext;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Pure token-acquisition utilities, shared by every code path that needs to talk to Azure Identity:
 * the boot fetch in {@link AzureDevOpsCredentialsExtension#afterProjectsRead}, the cache-miss slow
 * path in {@link AzureDevOpsAuthSelector}, and the per-request live path in {@link
 * LiveBearerHeadersMap#acquireToken}. Centralising here means changes to the SDK call shape
 * (telemetry, retry, scopes, timeout) apply uniformly to all three.
 *
 * <p>Stateless utility class — package-private, no instances.
 */
final class TokenAcquisition {

  // The Azure DevOps resource ID + /.default scope. AccessTokens issued with this scope are
  // accepted by every ADO Maven feed (pkgs.dev.azure.com or *.pkgs.visualstudio.com).
  static final String AZURE_DEVOPS_SCOPE = "499b84ac-1321-427f-aa17-267ca6975798/.default";

  // Pre-expiry refresh window: a cached token within this many minutes of expiry is treated
  // as stale and triggers a fresh fetch. Mirrors the Azure Identity SDK's own heuristic —
  // 5 minutes gives the resolver headroom for a single ~30-minute build phase to never see
  // expiry mid-flight, even if the token was already 70 minutes old when cached. Shared
  // across the live-path cache (LiveBearerHeadersMap.acquireToken) and the boot-path
  // selector cache (AzureDevOpsAuthSelector.getAuthentication) so both code paths apply
  // the same staleness criterion.
  private static final long TOKEN_REFRESH_BEFORE_EXPIRY_MINUTES = 5;

  // Hard ceiling on a single token acquisition. Without this, .block() (which has no
  // implicit timeout) can hang the entire Maven build forever if anything in the credential
  // chain stalls: ManagedIdentityCredential's IMDS roundtrip to 169.254.169.254 on a
  // misclassified non-Azure VM, a hung MSAL device-code prompt under AzureCliCredential,
  // a frozen secret-storage daemon, a blackholed login.microsoftonline.com behind a broken
  // corporate proxy. Pre-PR-1 a hang only happened at boot (Ctrl-C works, nothing has
  // happened yet); now that every Aether HTTP request potentially routes through here on
  // cache miss / refresh-window crossing, a hang would stop the build mid-resolve and is
  // much harder to diagnose. Single-flight gating concentrates the blast radius: every
  // waiting thread joins the same hung future. .block(Duration) throws IllegalStateException
  // on timeout (a RuntimeException), which routes through the existing failure handlers →
  // gated WARN → graceful fallback to a still-valid cached token if available.
  static final Duration TOKEN_ACQUISITION_TIMEOUT = Duration.ofMinutes(2);

  private TokenAcquisition() {}

  // Per-call TokenRequestContext factory. Builds a fresh request on every blockForToken
  // invocation so any future SDK that mutates the request in-place (TokenRequestContext IS
  // mutable: addScopes / setClaims / setTenantId / setCaeEnabled) corrupts only its own
  // request, not a JVM-wide shared constant. Allocation cost is sub-µs and scavenge-collected
  // — outweighed by the lockup-debugging cost of an SDK bump silently issuing wrong-scope
  // tokens against a tenant-scoped feed. AZURE_DEVOPS_SCOPE remains the single source of
  // truth for the scope spec.
  private static TokenRequestContext newTokenRequest() {
    return new TokenRequestContext().addScopes(AZURE_DEVOPS_SCOPE);
  }

  // Single point of credential.getToken() invocation. Both the boot path (getAccessToken,
  // for Settings.Server injection + AzureDevOpsAuthSelector cache) and the live path
  // (LiveBearerHeadersMap.acquireToken, called per outbound HTTP request from Aether) go
  // through this. Anything that needs to change about the SDK invocation — telemetry,
  // retry, scopes, timeout — applies to both automatically.
  static AccessToken blockForToken(TokenCredential credential) {
    return blockForToken(credential, TOKEN_ACQUISITION_TIMEOUT);
  }

  // Test seam: same as above but with a caller-supplied timeout, so the F2 regression test
  // (a future refactor that drops the Duration arg and re-introduces the unbounded-block
  // hang risk) can hit the timeout path within unit-test time budgets instead of waiting
  // the full 2-minute production ceiling.
  static AccessToken blockForToken(TokenCredential credential, Duration timeout) {
    return credential.getToken(newTokenRequest()).block(timeout);
  }

  static boolean isNearExpiry(AccessToken token) {
    // null expiry is treated as stale — if the SDK can't tell us when it expires, we have
    // no basis to trust the cached value across requests.
    return token.getExpiresAt() == null
        || token
            .getExpiresAt()
            .isBefore(OffsetDateTime.now().plusMinutes(TOKEN_REFRESH_BEFORE_EXPIRY_MINUTES));
  }

  // Graceful-degradation helper: when a refresh attempt fails AND we have a cached token
  // that's NEAR expiry (within the 5-min refresh window) but still has real validity left,
  // return its Bearer string so the caller can serve one more request instead of forcing a
  // 401. Mirrors the Azure Identity SDK's own "refresh proactively but keep serving the
  // previously-cached token until actual expiry if refresh fails" philosophy. Returns null
  // if the cached token is missing, has no expiry, or has truly expired.
  static String cachedTokenIfStillRealValid(AccessToken cached) {
    if (cached != null
        && cached.getExpiresAt() != null
        && cached.getExpiresAt().isAfter(OffsetDateTime.now())) {
      return cached.getToken();
    }
    return null;
  }
}
