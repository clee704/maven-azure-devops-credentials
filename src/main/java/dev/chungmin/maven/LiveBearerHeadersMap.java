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
import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;

/**
 * Live Map whose {@link #entrySet()} returns a fresh {@code Authorization: Bearer <token>} entry on
 * every iteration. Installed into the Aether session under {@code
 * aether.connector.http.headers.<repoId>}; the Aether HTTP transporter iterates this map's entry
 * set on every outgoing HTTP request, so each request picks up the current bearer token directly
 * from Azure Identity (with this extension's own short-TTL cache on top — see {@link
 * TokenAcquisition} — to avoid forking {@code az account get-access-token} on every request for
 * credentials that don't have an internal SDK cache).
 *
 * <p>This allows a single Maven invocation to keep authenticating against an Azure DevOps Maven
 * feed indefinitely, even past the original token's expiry — which would otherwise break builds
 * longer than the token lifetime (~60-75 minutes for Entra access tokens).
 *
 * <p><b>Load-bearing assumption:</b> this whole mechanism rests on the maven-resolver-transport
 * implementation re-iterating the configured {@code HTTP_HEADERS} Map's {@code entrySet()} on every
 * request, rather than snapshotting it at constructor time. This is true through maven-resolver 1.x
 * ({@code HttpTransporter.commonHeaders()}); if a future Aether version changes that contract the
 * feature will silently revert to boot-time-only auth (no exception, just 401s after token expiry).
 * When debugging "tokens aren't refreshing" reports, check {@code commonHeaders()} in the active
 * maven-resolver-transport-http first.
 */
class LiveBearerHeadersMap extends AbstractMap<String, String> {

  private final TokenCredential credential;
  private final Logger logger;
  // Rate-limits the per-request failure warning: {@code entrySet()} is called on EVERY
  // outbound HTTP request, so a sustained credential outage during a 1000-artifact resolve
  // would otherwise drown the log in 1000 identical warnings before the user sees the 401.
  // Log on transition into the failure state; silently reset on the next success. The gate
  // is shared across every per-repo instance constructed in afterProjectsRead so a single
  // outage warns at most once across all feeds, not once per feed.
  private final AtomicBoolean inFailureState;
  // Single-flight gate: shared across every per-repo instance constructed in
  // afterProjectsRead so a burst of concurrent entrySet() calls from Aether's resolver
  // pool coalesces into ONE credential.getToken() invocation. See acquireToken() for the
  // mechanics; see afterProjectsRead for the rationale (AzureCliCredential has no
  // built-in cache, so without coalescing N concurrent threads = N concurrent `az` forks).
  private final AtomicReference<CompletableFuture<AccessToken>> inFlightToken;
  // Short-TTL token cache: shared across every per-repo instance constructed in
  // afterProjectsRead. AzureCliCredential and a few other DefaultAzureCredentialBuilder
  // chain members have NO built-in token cache — every getToken() call shells out to
  // `az account get-access-token` (or the equivalent). Without this cache, a 1000-artifact
  // sequential resolve would fork ~1000 `az` subprocesses (3-8 min of pure overhead) on
  // top of the actual download time. With it, the first request fetches and the next
  // ~55-70 minutes of requests hit the cache instead. The 5-minute refresh window mirrors
  // the Azure Identity SDK's own pre-expiry refresh heuristic; tokens within that window
  // are treated as stale and trigger a fresh fetch.
  private final AtomicReference<AccessToken> cachedToken;

  // SOLE constructor — production callers in afterProjectsRead use this directly with the
  // extension's instance-field shared state (sharedFailureState, sharedInFlightToken,
  // sharedCachedToken); the LiveBearerHeadersMap.forTest(...) factories below wrap this
  // for unit tests that want subsets of the shared state with auto-allocated defaults.
  // Keeping one constructor + named factories rather than a 1/2/3/4/5-arg telescoping
  // chain makes the production-vs-test split visually unambiguous to a future maintainer.
  LiveBearerHeadersMap(
      TokenCredential credential,
      Logger logger,
      AtomicBoolean sharedFailureState,
      AtomicReference<CompletableFuture<AccessToken>> sharedInFlightToken,
      AtomicReference<AccessToken> sharedCachedToken) {
    this.credential = credential;
    this.logger = logger;
    this.inFailureState = sharedFailureState;
    this.inFlightToken = sharedInFlightToken;
    this.cachedToken = sharedCachedToken;
  }

  // Named production builder — readers of afterProjectsRead see `production(...)` and know
  // exactly which call site is the live, shipped path (vs. the test factories below).
  static LiveBearerHeadersMap production(
      TokenCredential credential,
      Logger logger,
      AtomicBoolean sharedFailureState,
      AtomicReference<CompletableFuture<AccessToken>> sharedInFlightToken,
      AtomicReference<AccessToken> sharedCachedToken) {
    return new LiveBearerHeadersMap(
        credential, logger, sharedFailureState, sharedInFlightToken, sharedCachedToken);
  }

  // Test factories — explicit `forTest` naming so production code grep doesn't surface
  // them as ambiguous candidates. Each overload inlines its defaults directly into the
  // 5-arg LiveBearerHeadersMap constructor call so a reader can see at the call site
  // what shared-state defaults a given test gets without chasing `this(...)` / `forTest(
  // ...)` delegation hops (the N27 collapse — avoids re-introducing the telescoping
  // pattern at the factory level that N7 collapsed at the constructor level).
  static LiveBearerHeadersMap forTest(TokenCredential credential) {
    return new LiveBearerHeadersMap(
        credential,
        AzureDevOpsCredentialsExtension.log,
        new AtomicBoolean(false),
        new AtomicReference<>(),
        new AtomicReference<>());
  }

  static LiveBearerHeadersMap forTest(TokenCredential credential, Logger logger) {
    return new LiveBearerHeadersMap(
        credential,
        logger,
        new AtomicBoolean(false),
        new AtomicReference<>(),
        new AtomicReference<>());
  }

  // Full-fidelity test factory: same shape as production(...) but named with the test
  // prefix so a future reader greping "forTest" surfaces it as the unit-test seam.
  static LiveBearerHeadersMap forTest(
      TokenCredential credential,
      Logger logger,
      AtomicBoolean sharedFailureState,
      AtomicReference<CompletableFuture<AccessToken>> sharedInFlightToken,
      AtomicReference<AccessToken> sharedCachedToken) {
    return new LiveBearerHeadersMap(
        credential, logger, sharedFailureState, sharedInFlightToken, sharedCachedToken);
  }

  @Override
  public Set<Map.Entry<String, String>> entrySet() {
    String token = acquireToken();
    // Always return exactly one entry so size() and entrySet().size() agree (Map contract).
    // On token-acquisition failure the value is null; Aether's HttpTransporter.commonHeaders()
    // calls request.removeHeaders(key) when the value isn't a String, so the request goes out
    // WITHOUT an Authorization header at all — same wire behavior as the original "return
    // emptySet on failure" path, but now size() and entrySet().size() agree.
    //
    // Load-bearing assumption (verified against maven-resolver-transport-http 1.x): the
    // commonHeaders() loop dispatches on `entry.getValue() instanceof String` — true →
    // setHeader, false (including null) → removeHeaders. A hypothetical future Aether that
    // switched to String.valueOf(value) would silently send `Authorization: null` and
    // produce confusing 401s. If you're chasing a "null Authorization in wire trace" bug
    // after a maven-resolver bump, check the value-type dispatch in commonHeaders() first.
    String value = token != null ? "Bearer " + token : null;
    return Collections.singleton(new AbstractMap.SimpleImmutableEntry<>("Authorization", value));
  }

  // Scoped override defense: every "implicit / passive-introspection" method on
  // AbstractMap — toString, equals, hashCode, size — delegates to entrySet().iterator(),
  // which for our live map would (a) trigger a synchronous credential.getToken() (wrong
  // for any "is this object harmless to log/inspect" caller) and (b) in equals/toString,
  // materialize a Bearer JWT into a String that lands wherever the caller is dumping
  // (Maven -X debug, a future framework that toString'd its config, an exception that
  // quotes its arguments). The four overrides below return identity / fixed-string
  // semantics so these "I didn't ask for the value" callers see nothing sensitive.
  //
  // Explicitly NOT overridden: get(Object), containsKey(Object), containsValue(Object),
  // keySet(), values(). These are explicit Map-API calls — a caller invoking them HAS
  // asked for the value, so the inherited AbstractMap defaults (which DO route through
  // entrySet()) are intentional: they'll trigger the fetch and return the real Bearer
  // string. Overriding them to identity/empty would either lie (Map contract violation:
  // get returns null while entrySet has the entry) or force a content-vs-key
  // inconsistency (size=1 but values=emptyList). Aether's HttpTransporter.commonHeaders()
  // only iterates entrySet() today, so neither inherited path is hot in production; if a
  // future Aether bump or a custom extension starts calling get()/values()/keySet() on
  // this map, the AbstractMap defaults will Do The Right Thing — return the live token —
  // because the caller asked for it.
  //
  // isEmpty() is also inherited (not overridden) but is SAFE for passive callers:
  // AbstractMap.isEmpty() returns `size() == 0`, and our size() override returns 1 — so
  // isEmpty() returns false without touching entrySet() or triggering a credential call.
  @Override
  public int size() {
    return 1;
  }

  @Override
  public boolean equals(Object o) {
    return o == this;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public String toString() {
    return "AzureDevOpsLiveAuthHeaders{keys=[Authorization]}";
  }

  private String acquireToken() {
    // Fast path: a previously cached token that's still well clear of expiry.
    AccessToken cached = cachedToken.get();
    if (cached != null && !TokenAcquisition.isNearExpiry(cached)) {
      // Gated reset: the steady-state value here is already false (the success path at
      // the bottom of this method already cleared it on the prior slow-path acquire),
      // so an unconditional set would do a redundant volatile store on the hot path —
      // once per outbound HTTP request, per feed, across every resolver thread in an
      // `mvn -T 1C` run. Cheap individually; meaningful in aggregate.
      if (inFailureState.get()) {
        inFailureState.set(false);
      }
      return cached.getToken();
    }
    AccessToken token;
    try {
      token = acquireTokenSingleFlight();
    } catch (RuntimeException e) {
      // F1: invert fallback-before-warn order. The cached token may still have real
      // validity (we entered the slow path because it's WITHIN the 5-min refresh window,
      // not because it's truly expired). If fallback serves the request, the user is
      // actually authenticated — a WARN saying "request will go out unauthenticated"
      // would be a lie. Only warn when fallback genuinely can't save us.
      //
      // S4: re-read cachedToken.get() here instead of using the local `cached` from the
      // fast-path miss. A concurrent selector slow path / boot fetch may have populated
      // a fresher token between our fast-path read and this catch — using the freshest
      // available cached value matches the boot path's useFallbackOrWarnUnauthenticated
      // which re-reads cacheRef.get() fresh inside the helper.
      String fallback = graceful401AvoidanceFallback(cachedToken.get());
      if (fallback != null) {
        return fallback;
      }
      noteFailure("Failed to refresh Azure access token mid-build", e);
      return null;
    }
    if (token == null) {
      // Mono.empty() — SDK returned no token without throwing. Same F1 ordering as the
      // catch path; same S4 re-read for the freshest available cached fallback.
      String fallback = graceful401AvoidanceFallback(cachedToken.get());
      if (fallback != null) {
        return fallback;
      }
      noteFailure("Azure credential returned no token (Mono.empty())", null);
      return null;
    }
    // Slow-path success: only flip the gate if it was actually tripped. Same gating
    // motivation as the fast path, less hot but kept symmetric for clarity.
    if (inFailureState.get()) {
      inFailureState.set(false);
    }
    return token.getToken();
  }

  private String graceful401AvoidanceFallback(AccessToken cached) {
    String fallback = TokenAcquisition.cachedTokenIfStillRealValid(cached);
    if (fallback != null) {
      logger.debug(
          "Token refresh failed; serving cached token still valid until {}. Will retry"
              + " refresh on the next request.",
          cached.getExpiresAt());
    }
    return fallback;
  }

  // Single-flight via AtomicReference<CompletableFuture>: only runs when acquireToken's
  // fast-path cache check missed (cache empty OR within the 5-minute refresh window).
  // The FIRST thread into a burst installs its own future as the in-flight marker, then
  // calls credential.getToken(); every later thread sees the existing future and joins it
  // — at most one `az` subprocess fork per concurrent burst even under mvn -T 1C resolver
  // pressure. The leader populates `cachedToken` BEFORE completing the future and clearing
  // the in-flight reference (see L815-820), so any waiter that re-enters acquireToken
  // after returning hits the fast-path cache on its next call and never reaches here.
  // The retry loop handles the rare race where the leader clears the reference between
  // peekInFlight() and tryClaimLeadership().
  //
  // Expiry tracking is the extension's own — TokenAcquisition.isNearExpiry() + the 5-min
  // TOKEN_REFRESH_BEFORE_EXPIRY_MINUTES window — NOT the SDK's. The headline credential
  // (AzureCliCredential) has no internal token cache and shells out `az account
  // get-access-token` per call (the M1 rationale captured at the cachedToken field's
  // javadoc); the local AccessToken cache is what makes the per-request entrySet()
  // invocation cheap.
  //
  // The AtomicReference operations below go through two overridable seams
  // (peekInFlight / tryClaimLeadership) rather than direct method calls on inFlightToken.
  // AtomicReference.get() and compareAndSet() are final and can't be mocked under our
  // test infrastructure (mockito-core, no inline mock maker — keeps the Java 21
  // byte-buddy-agent attachment problem at bay), so this is the only practical way to
  // deterministically exercise the lost-CAS loop branch from a unit test without adding
  // flaky concurrent timing dependencies.
  private AccessToken acquireTokenSingleFlight() {
    while (true) {
      CompletableFuture<AccessToken> existing = peekInFlight();
      if (existing != null) {
        return joinUnwrapped(existing);
      }
      CompletableFuture<AccessToken> myFuture = new CompletableFuture<>();
      if (tryClaimLeadership(myFuture)) {
        try {
          // N24: re-check the cache after winning the CAS. A prior leader may have
          // populated cachedToken between our outer fast-path read (in acquireToken)
          // and our CAS win here — e.g. boot fetch / selector slow path / a previous
          // burst's leader all share this same cachedToken via afterProjectsRead. Using
          // the cached value here saves a redundant blockForToken (and for
          // AzureCliCredential, a redundant `az` subprocess fork) at the cost of one
          // atomic load. Same TokenAcquisition.isNearExpiry criterion as the outer fast
          // path.
          AccessToken justCached = cachedToken.get();
          if (justCached != null && !TokenAcquisition.isNearExpiry(justCached)) {
            myFuture.complete(justCached);
            return justCached;
          }
          AccessToken token = TokenAcquisition.blockForToken(credential);
          if (token != null && token.getExpiresAt() != null) {
            // Populate the cache BEFORE completing the future so waiters that just joined
            // see a populated cache on their next entry into acquireToken(); without this,
            // the leader and waiters would all return the same token (correctly) but the
            // NEXT request would re-enter the slow path with an empty cache, defeating the
            // whole point of caching the freshly-acquired token.
            //
            // N15 guard: skip the cache.set when getExpiresAt() is null.
            // TokenAcquisition.isNearExpiry() and cachedTokenIfStillRealValid() both treat
            // null-expiry as always-stale, so caching such a token would force every
            // subsequent request back through this slow path anyway (defeating M1) AND
            // leave a useless entry in the cache that violates the "if cachedToken is
            // non-null, it has a usable expiry" invariant the rest of the file's
            // null-expiry guards quietly rely on. The current request still gets served
            // the token; the next one re-fetches from a clean (null) cache, which has the
            // same wire cost but a sane invariant.
            cachedToken.set(token);
          }
          myFuture.complete(token);
          return token;
        } catch (RuntimeException e) {
          // Critical: complete the future exceptionally so waiters joined on it unblock
          // with the same failure instead of hanging forever. The finally below clears
          // the in-flight reference; until that runs, every late-arriving waiter still
          // joins THIS future and sees the same exception.
          myFuture.completeExceptionally(e);
          throw e;
        } catch (Error e) {
          // Same single-flight visibility guarantee as the RuntimeException branch — if
          // the SDK or a transitive dependency throws OOM/StackOverflowError, propagate
          // it to every waiter so the build fails fast and consistently instead of one
          // thread crashing while the others hang on the never-completed future.
          myFuture.completeExceptionally(e);
          throw e;
        } finally {
          // S3 defense-in-depth: compareAndSet(myFuture, null) instead of an unconditional
          // set(null). The current single-flight invariant guarantees only the leader holds
          // inFlightToken so an unconditional set is correct today, but a future refactor
          // that lets a stale reference leak past this finally (an exception path that
          // bypasses it, a misuse of the test seams) would have CAS catch the mismatch
          // instead of silently nulling out an unrelated leader's future and breaking
          // single-flight invisibly. Uncontended CAS is single-digit ns on modern x86 —
          // same defensive-completeness reasoning as the broadened catch in
          // installSessionConfig (N5) and the per-call TokenRequestContext factory (N10).
          inFlightToken.compareAndSet(myFuture, null);
        }
      }
      // Lost the CAS; another thread just installed its own future. Loop to join it.
    }
  }

  // Package-private test seams for the peek + try-claim AtomicReference operations only;
  // production calls flow straight through. Release-leadership is inlined into the leader's
  // finally block (see S3 above) — it must run before peek/tryClaim sees the next attempt,
  // so a test override would either be racy or just duplicate the inline AtomicReference
  // call. The lost-CAS coverage test (singleFlightLostCasLoopsAndJoinsWinner) overrides
  // peekInFlight + tryClaimLeadership; the cache-recheck-after-CAS coverage test
  // (singleFlightReChecksCacheAfterWinningCAS) overrides tryClaimLeadership only.
  CompletableFuture<AccessToken> peekInFlight() {
    return inFlightToken.get();
  }

  boolean tryClaimLeadership(CompletableFuture<AccessToken> myFuture) {
    return inFlightToken.compareAndSet(null, myFuture);
  }

  private static AccessToken joinUnwrapped(CompletableFuture<AccessToken> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      // The SDK only emits RuntimeException via Mono.error(), but the leader's catch above
      // also propagates Error via completeExceptionally(). Re-throw the original cause so
      // the type the caller sees matches what they'd have seen calling getToken() directly.
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      if (e.getCause() instanceof Error) {
        throw (Error) e.getCause();
      }
      // Defensive: only reachable if someone hand-completes the future with a checked
      // Throwable (Mono.error() can't). Wrap so acquireToken's catch handler can recover.
      throw new RuntimeException("Single-flight token acquisition failed", e.getCause());
    }
  }

  // At-most-once-per-outage warn gate. Steady-state guarantee. Under concurrent
  // failure<->success thrash (rare and non-load-bearing) we may double-fire, because the
  // success-path set(false) and the failure-path compareAndSet(false,true) are not atomic
  // with each other — tolerable for a log rate-limiter.
  private void noteFailure(String reason, Throwable cause) {
    if (inFailureState.compareAndSet(false, true)) {
      // Pass the cause as the trailing argument so SLF4J formats with parameterized
      // placeholders (lazy concat) AND preserves the stacktrace when a logging backend
      // is bound that prints it. Suffix matches the boot/selector-path WARN so a user
      // hitting this mid-build (the headline scenario this PR addresses) gets the same
      // actionable remediation hints as a user hitting the boot/selector failure.
      if (cause == null) {
        logger.warn(
            "{}. Request will go out unauthenticated. "
                + AzureDevOpsCredentialsExtension.CREDENTIAL_FAILURE_REMEDIATION,
            reason);
      } else {
        logger.warn(
            "{}. Request will go out unauthenticated. "
                + AzureDevOpsCredentialsExtension.CREDENTIAL_FAILURE_REMEDIATION,
            reason,
            cause);
      }
    }
  }
}
