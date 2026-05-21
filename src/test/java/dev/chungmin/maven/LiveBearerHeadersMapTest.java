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
import java.util.ArrayList;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import reactor.core.publisher.Mono;

@RunWith(MockitoJUnitRunner.Silent.class)
public class LiveBearerHeadersMapTest {

  @Mock private TokenCredential mockCredential;

  @Before
  public void setUp() {
    // Reset the JVM-static failure gates so any prior test's reflective-install or
    // verify-config failures don't bleed into these tests' rate-limit assertions.
    SessionConfigInstaller.resetFailureGates();
  }

  @Test
  public void liveBearerHeadersMap_returnsAuthorizationHeader() {
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("token-A", OffsetDateTime.now().plusHours(1))));

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    assertEquals(1, map.entrySet().size());
    java.util.Map.Entry<String, String> entry = map.entrySet().iterator().next();
    assertEquals("Authorization", entry.getKey());
    assertEquals("Bearer token-A", entry.getValue());
  }

  @Test
  public void liveBearerHeadersMap_returnsCurrentTokenFromEachEntrySetCall() {
    // Core property: HttpTransporter.commonHeaders() iterates entrySet() on every outgoing
    // request, and we MUST return the CURRENT bearer token each time, not one baked at
    // constructor time. After M1's local cache (~tenth-review fix), "current" means "the
    // latest token the cache has seen" — sequential same-cache-window calls return the
    // cached value; expiry-window crossing triggers a refresh.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("token-1", OffsetDateTime.now().plusHours(1))));

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    // First call populates cache; subsequent calls within the TTL hit the cache.
    assertEquals("Bearer token-1", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer token-1", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer token-1", map.entrySet().iterator().next().getValue());
    // Aether ALWAYS sees the current token via entrySet(); the cache short-circuits the
    // SDK call but does NOT short-circuit the entrySet() callback contract.
  }

  @Test
  public void liveBearerHeadersMap_cachesAccessTokenWithinTTL() {
    // M1: AzureCliCredential has no built-in cache; without our local cache every Aether
    // HTTP request would fork `az account get-access-token`. This test pins the cache hit
    // behavior: 3 sequential calls with a long-TTL token result in exactly ONE getToken().
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("token-1", OffsetDateTime.now().plusHours(1))));

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    map.entrySet().iterator().next().getValue();
    map.entrySet().iterator().next().getValue();
    map.entrySet().iterator().next().getValue();
    verify(mockCredential, times(1)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_fastPathResetsTrippedInFailureStateOnCacheHit() {
    // N (perf-nit): the fast-path's gated `if (inFailureState.get()) inFailureState.set(false)`
    // only flips the gate when it's actually tripped (avoids a redundant volatile store on
    // every per-request hot-path hit). This test pre-trips the gate AND pre-populates the
    // cache via the 5-arg forTest factory so the next entrySet() exercises the fast path
    // with a tripped gate — exercising the inFailureState.set(false) line that's otherwise
    // unreachable under normal flow (the slow-path success path clears the gate before any
    // subsequent fast-path read).
    java.util.concurrent.atomic.AtomicBoolean preTrippedGate =
        new java.util.concurrent.atomic.AtomicBoolean(true);
    java.util.concurrent.atomic.AtomicReference<AccessToken> preCachedToken =
        new java.util.concurrent.atomic.AtomicReference<>(
            new AccessToken("pre-cached", OffsetDateTime.now().plusHours(1)));
    LiveBearerHeadersMap map =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            preTrippedGate,
            new java.util.concurrent.atomic.AtomicReference<>(),
            preCachedToken);

    assertEquals("Bearer pre-cached", map.entrySet().iterator().next().getValue());
    assertFalse(
        "Fast-path hit must reset the inFailureState gate when it was already tripped",
        preTrippedGate.get());
    // And no SDK call — the cache hit short-circuited.
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_refreshesAccessTokenWhenWithinExpiryWindow() {
    // M1 refresh boundary: a cached token within 5 min of expiry is treated as stale and
    // triggers a fresh fetch on the next acquire. Set up the first token at expiry+2min
    // (stale on second check) and the second at expiry+1h (fresh).
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("near-expiry", OffsetDateTime.now().plusMinutes(2))))
        .thenReturn(Mono.just(new AccessToken("fresh-token", OffsetDateTime.now().plusHours(1))))
        .thenReturn(Mono.just(new AccessToken("fresh-token-2", OffsetDateTime.now().plusHours(1))));

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    // First call: cache empty, fetch near-expiry, cache it, return it.
    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
    // Second call: cache has near-expiry token, isNearExpiry returns true, refetch.
    assertEquals("Bearer fresh-token", map.entrySet().iterator().next().getValue());
    // Third call: cache has fresh-token (1h expiry), isNearExpiry returns false, cache hit.
    assertEquals("Bearer fresh-token", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(2)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_fallsBackToStillRealValidCachedTokenOnRefreshException() {
    // N6: when a cached token is in the refresh window (near-expiry) and the slow-path
    // fetch throws, the cached token still has real validity left (just within the 5-min
    // proactive-refresh window, not actually expired). Serve it instead of returning null
    // and forcing a 401 during a transient credential blip. Mirrors the Azure Identity
    // SDK's own "refresh proactively but keep serving the previously-cached token until
    // actual expiry if refresh fails" philosophy.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("near-expiry", OffsetDateTime.now().plusMinutes(2))))
        .thenThrow(new RuntimeException("transient-az-blip"))
        .thenReturn(Mono.just(new AccessToken("fresh-token", OffsetDateTime.now().plusHours(1))));

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    // First call: cache empty, fetch near-expiry, return it. (1 getToken)
    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
    // Second call: cache has near-expiry, refetch THROWS — fallback returns cached. (2 getToken)
    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
    // Third call: cache still has near-expiry, refetch succeeds, return fresh-token. (3 getToken)
    assertEquals("Bearer fresh-token", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_fallsBackToStillRealValidCachedTokenOnRefreshNullResult() {
    // N6 (Mono.empty path): same fallback semantics as the exception path.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("near-expiry", OffsetDateTime.now().plusMinutes(2))))
        .thenReturn(Mono.empty());

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer near-expiry", map.entrySet().iterator().next().getValue());
  }

  @Test
  public void liveBearerHeadersMap_doesNotFallBackToTrulyExpiredCachedToken() {
    // N6 boundary: a cached token whose REAL expiry is in the past must NOT be served as a
    // fallback. The graceful-degradation only applies within the 5-min proactive-refresh
    // window, not after actual expiry.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("expired", OffsetDateTime.now().minusMinutes(1))))
        .thenThrow(new RuntimeException("transient-az-blip"));

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    // First call: cache empty, fetch returns already-expired token (cache it anyway).
    assertEquals("Bearer expired", map.entrySet().iterator().next().getValue());
    // Second call: cache has expired token, refetch THROWS — fallback CANNOT use the cached
    // value because it's truly expired (expiresAt < now). Returns null.
    assertNull(map.entrySet().iterator().next().getValue());
  }

  @Test
  public void liveBearerHeadersMap_refreshesAccessTokenWhenExpiryIsNull() {
    // Defensive: an SDK that returns AccessToken with a null expiresAt — we can't trust the
    // cached value across requests (no basis to know freshness), so treat null-expiry as
    // always-stale and refetch every time.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("no-expiry-1", null)))
        .thenReturn(Mono.just(new AccessToken("no-expiry-2", null)));

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    assertEquals("Bearer no-expiry-1", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer no-expiry-2", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(2)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_doesNotPoisonCacheWithNullExpiryToken() {
    // N15 invariant: a token with null getExpiresAt() must NOT land in the cache. Caching
    // it would force every future request back through the slow path (isNearExpiry returns
    // true for null expiry) AND violate the "if cachedToken is non-null, it has a usable
    // expiry" invariant the rest of the file's null-expiry guards rely on. Use the 5-arg
    // forTest factory so the test can inspect the externally-owned cache reference after
    // the entrySet() call.
    when(mockCredential.getToken(any())).thenReturn(Mono.just(new AccessToken("no-expiry", null)));
    java.util.concurrent.atomic.AtomicReference<AccessToken> sharedCache =
        new java.util.concurrent.atomic.AtomicReference<>();
    LiveBearerHeadersMap map =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            sharedCache);

    // Current request still gets served the token (it's the only one available).
    assertEquals("Bearer no-expiry", map.entrySet().iterator().next().getValue());
    // But the cache stays empty — invariant intact for the next caller.
    assertNull(
        "Null-expiry AccessToken must not poison the cache (it would always be stale anyway)",
        sharedCache.get());
  }

  @Test
  public void liveBearerHeadersMap_cacheIsSharedAcrossInstances() {
    // afterProjectsRead creates one sharedCachedToken AtomicReference and passes it to every
    // per-repo LiveBearerHeadersMap. A workspace with N ADO feeds + 1 fetched token should
    // result in 1 getToken() call total, not N (one per feed).
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("shared", OffsetDateTime.now().plusHours(1))));
    java.util.concurrent.atomic.AtomicReference<AccessToken> sharedCache =
        new java.util.concurrent.atomic.AtomicReference<>();
    LiveBearerHeadersMap feedA =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            sharedCache);
    LiveBearerHeadersMap feedB =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            sharedCache);

    assertEquals("Bearer shared", feedA.entrySet().iterator().next().getValue());
    assertEquals("Bearer shared", feedB.entrySet().iterator().next().getValue());
    // feedB's first call hit the cache populated by feedA → only one SDK round-trip.
    verify(mockCredential, times(1)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_returnsNullValueOnNullToken() {
    when(mockCredential.getToken(any())).thenReturn(Mono.empty());

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    // H1 contract: size() and entrySet().size() must agree. On failure we still emit one
    // entry; the value is null so Aether's HttpTransporter.commonHeaders() calls
    // request.removeHeaders(key) — request goes out with NO Authorization header at all.
    assertEquals(1, map.entrySet().size());
    assertNull(map.entrySet().iterator().next().getValue());
  }

  @Test
  public void liveBearerHeadersMap_returnsNullValueOnException() {
    when(mockCredential.getToken(any())).thenThrow(new RuntimeException("auth failed"));

    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    assertEquals(1, map.entrySet().size());
    assertNull(map.entrySet().iterator().next().getValue());
  }

  @Test
  public void liveBearerHeadersMap_warnsOnceWithinAFailureRun() {
    // RuntimeException on every call; sustained credential outage.
    when(mockCredential.getToken(any()))
        .thenThrow(new RuntimeException("auth failed"))
        .thenThrow(new RuntimeException("auth failed"))
        .thenThrow(new RuntimeException("auth failed"));
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential, mockLog);
    // All three calls return one entry with empty value (H1: size/entrySet consistency).
    assertNull(map.entrySet().iterator().next().getValue());
    assertNull(map.entrySet().iterator().next().getValue());
    assertNull(map.entrySet().iterator().next().getValue());
    // The credential was still consulted on each call; the rate-limit is on logging only.
    verify(mockCredential, times(3)).getToken(any());
    // Exactly ONE warn for the entire sustained-failure run.
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
    verify(mockLog, never()).warn(anyString(), anyString());
  }

  @Test
  public void liveBearerHeadersMap_warnsOnceWithinANullTokenRun() {
    // Mono.empty() on every call: SDK returned no token without throwing. The rate-limiter
    // must treat this the same as the exception path (the resulting 401 has the same blast
    // radius).
    when(mockCredential.getToken(any())).thenReturn(Mono.empty());
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential, mockLog);
    assertNull(map.entrySet().iterator().next().getValue());
    assertNull(map.entrySet().iterator().next().getValue());
    assertNull(map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
    // Null-token path does NOT carry a Throwable cause; warn fires once with the (msg, arg)
    // overload only.
    verify(mockLog, times(1)).warn(anyString(), anyString());
    verify(mockLog, never()).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_fallbackSuppressesRewarnWhenStillRealValid() {
    // F1: when a refresh fails AND the cached token is still real-valid, the user actually
    // IS authenticated via the fallback — so the rate-limited WARN must not fire. Sequence:
    //   - Call 1: cache empty, fetch fails, fallback null → WARN (1, the only one).
    //   - Call 2: cache empty, fetch succeeds with near-expiry token → gate reset, cached.
    //   - Call 3: cache near-expiry, fetch fails, fallback returns cached → NO warn.
    // The "rewarn after recovery" property (gate re-arms on success) is still verified by
    // the gate reset on call 2; a second warn WOULD fire IF call 3's fallback had been null
    // (see rewarnAfterRecoveryWhenFallbackUnavailable below for that path).
    when(mockCredential.getToken(any()))
        .thenThrow(new RuntimeException("fail-1"))
        .thenReturn(Mono.just(new AccessToken("recovered", OffsetDateTime.now().plusMinutes(2))))
        .thenThrow(new RuntimeException("fail-2"));
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential, mockLog);
    assertNull(map.entrySet().iterator().next().getValue());
    assertEquals("Bearer recovered", map.entrySet().iterator().next().getValue());
    // Third call: refresh fails but cached "recovered" still has real validity. F1: serve it,
    // do NOT warn (the warn would lie about going unauthenticated).
    assertEquals("Bearer recovered", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
    // Exactly ONE warn — only call 1 had no fallback.
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_rewarnAfterRecoveryWhenFallbackUnavailable() {
    // F1 corollary: after a successful refresh resets the gate, a subsequent failure with
    // NO real-valid cached fallback (cache has a truly-expired token) DOES re-fire the
    // WARN. Simulates the edge case where the recovered token was already past expiry by
    // the time it was returned — fallback's `isAfter(now)` check correctly rejects it, and
    // the gate re-fires.
    when(mockCredential.getToken(any()))
        .thenThrow(new RuntimeException("fail-1"))
        .thenReturn(
            Mono.just(new AccessToken("already-expired", OffsetDateTime.now().minusSeconds(10))))
        .thenThrow(new RuntimeException("fail-2"));
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential, mockLog);
    assertNull(map.entrySet().iterator().next().getValue());
    // Recovery — cache populated with the already-expired token. The gate is reset.
    assertEquals("Bearer already-expired", map.entrySet().iterator().next().getValue());
    // Third call: cache has truly-expired token, fetch fails, fallback NULL → WARN re-fires.
    assertNull(map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(3)).getToken(any());
    // Two warns total: one per fail edge, gate re-armed between them by the (technically
    // successful but immediately stale) recovery in the middle.
    verify(mockLog, times(2)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_sharedFailureStateRateLimitsAcrossInstances() {
    // S1: a workspace with N ADO feeds shares one AtomicBoolean across all per-repo maps so a
    // single credential outage warns at most once total, not once per feed.
    when(mockCredential.getToken(any())).thenThrow(new RuntimeException("auth failed"));
    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    java.util.concurrent.atomic.AtomicBoolean shared =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    LiveBearerHeadersMap feedA =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mockLog,
            shared,
            new java.util.concurrent.atomic.AtomicReference<>(),
            new java.util.concurrent.atomic.AtomicReference<>());
    LiveBearerHeadersMap feedB =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mockLog,
            shared,
            new java.util.concurrent.atomic.AtomicReference<>(),
            new java.util.concurrent.atomic.AtomicReference<>());
    assertNull(feedA.entrySet().iterator().next().getValue());
    assertNull(feedB.entrySet().iterator().next().getValue());
    assertNull(feedA.entrySet().iterator().next().getValue());
    // ONE warn total across both instances, not two.
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_passiveIntrospectionDoesNotTriggerToken() {
    // M1: AbstractMap defaults for size(), equals(Object), hashCode() all delegate to
    // entrySet().iterator(). Without overrides, casual introspection (Maven -X dumping
    // session config, an exception toString quoting its arguments, equals() comparison
    // against another map) would (a) round-trip to Entra and (b) in the case of equals(),
    // materialize the JWT into a String. Identity overrides eliminate both hazards.
    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);
    LiveBearerHeadersMap other = LiveBearerHeadersMap.forTest(mockCredential);
    assertEquals(1, map.size());
    assertTrue("identity: map equals itself", map.equals(map));
    assertFalse("identity: map does not equal a peer instance", map.equals(other));
    assertEquals(System.identityHashCode(map), map.hashCode());
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_toStringDoesNotLeakBearerToken() {
    // Override AbstractMap.toString(): the default iterates entrySet() (-> credential call
    // + Bearer JWT in the string). A regression that removed our override would expose
    // bearer tokens via any framework logger that dumps the headers map.
    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);
    String s = map.toString();
    assertFalse("toString must not contain a Bearer token", s.contains("Bearer "));
    // The label intentionally surfaces the header *names* (keys=[Authorization]) for
    // debugging; it must NOT include the values.
    assertFalse("toString must not contain JWT-shaped payload", s.contains("eyJ"));
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_singleFlightCoalescesConcurrentAcquisitions() throws Exception {
    // H2: under burst load (mvn -T 1C resolver pool, every thread hitting entrySet() in the
    // same window), AzureCliCredential has NO built-in cache and would otherwise fork one `az`
    // subprocess per thread. The shared in-flight gate must coalesce concurrent acquisitions
    // into ONE credential.getToken() invocation. This test simulates N=8 threads racing into
    // entrySet() while the credential is stalled in mid-getToken; only one fetch must complete
    // by the time all eight return.
    int threadCount = 8;
    java.util.concurrent.CountDownLatch workersReady =
        new java.util.concurrent.CountDownLatch(threadCount);
    java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch firstCallReached =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseFirstCall =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicInteger getTokenCallCount =
        new java.util.concurrent.atomic.AtomicInteger(0);

    // The credential blocks the first thread inside getToken() so subsequent threads pile up
    // on the in-flight future; once we release, the leader completes and the waiters return
    // with the same AccessToken without doing their own getToken() calls.
    when(mockCredential.getToken(any()))
        .thenAnswer(
            invocation -> {
              getTokenCallCount.incrementAndGet();
              firstCallReached.countDown();
              releaseFirstCall.await();
              return Mono.just(new AccessToken("shared-token", OffsetDateTime.now().plusHours(1)));
            });

    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    LiveBearerHeadersMap map =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    try {
      java.util.List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            pool.submit(
                () -> {
                  // Deterministic barrier: each worker signals "I'm parked at the gate"
                  // before awaiting the release. The main thread waits for all N workers
                  // to signal before releasing, eliminating the wall-clock race where a
                  // late-scheduled worker would otherwise miss the in-flight future and
                  // become a second leader (defeating the test's purpose).
                  workersReady.countDown();
                  startLatch.await();
                  return map.entrySet().iterator().next().getValue();
                }));
      }
      // All 8 workers parked at startLatch.await() — now safe to release.
      assertTrue(
          "All " + threadCount + " workers should park within 5s",
          workersReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
      // Release all 8 threads to race into acquireToken() simultaneously.
      startLatch.countDown();
      // Wait for the leader to reach getToken() and block inside the answer. After CAS the
      // leader's future is visible in inFlightToken; the remaining 7 workers will each
      // observe it on their first peek and join() it. No need for a wall-clock settle —
      // there's no time-bounded path that could let a waiter become a second leader while
      // the original leader is parked in the answer.
      assertTrue(
          "Leader thread should reach getToken() within 5s",
          firstCallReached.await(5, java.util.concurrent.TimeUnit.SECONDS));
      // Release the leader.
      releaseFirstCall.countDown();
      // All N threads should return the same Bearer value.
      for (java.util.concurrent.Future<String> f : futures) {
        assertEquals("Bearer shared-token", f.get(5, java.util.concurrent.TimeUnit.SECONDS));
      }
    } finally {
      pool.shutdown();
    }

    // The whole point of single-flight: exactly ONE credential.getToken() call, regardless of
    // how many threads raced into entrySet().
    assertEquals(
        "Single-flight should coalesce N concurrent acquisitions to 1 getToken() call",
        1,
        getTokenCallCount.get());
  }

  @Test
  public void liveBearerHeadersMap_singleFlightStartsFreshAfterPriorCompletion() {
    // Single-flight intentionally clears the in-flight reference immediately after each
    // acquisition completes, so the NEXT call kicks off a fresh fetch when needed. M1's
    // local cache short-circuits sequential calls within the token's TTL, so this test
    // uses a near-expiry first token to force the second call back into the slow path
    // and re-exercise the single-flight cleanup.
    when(mockCredential.getToken(any()))
        .thenReturn(Mono.just(new AccessToken("t1", OffsetDateTime.now().plusMinutes(2))))
        .thenReturn(Mono.just(new AccessToken("t2", OffsetDateTime.now().plusHours(1))));
    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);
    assertEquals("Bearer t1", map.entrySet().iterator().next().getValue());
    assertEquals("Bearer t2", map.entrySet().iterator().next().getValue());
    verify(mockCredential, times(2)).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_singleFlightUnwrapsRuntimeExceptionFromPriorLeader() {
    // Pre-populate the in-flight reference with an already-failed future whose cause is a
    // RuntimeException — simulates the state seen by a waiter thread that joins the in-flight
    // gate AFTER the leader's getToken() failed but BEFORE the leader's finally block cleared
    // the reference. joinUnwrapped must unwrap CompletionException to the original
    // RuntimeException so acquireToken's RuntimeException catch sees the same cause it would
    // have seen calling credential.getToken() directly.
    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CompletableFuture<AccessToken> failedFuture =
        new java.util.concurrent.CompletableFuture<>();
    failedFuture.completeExceptionally(new RuntimeException("leader-failure"));
    sharedInFlight.set(failedFuture);

    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    LiveBearerHeadersMap map =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mockLog,
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    // entrySet returns 1 entry with empty value (failure handled by acquireToken's catch).
    assertNull(map.entrySet().iterator().next().getValue());
    // The waiter path was taken — credential.getToken was NOT called (the future was already
    // populated; we joined it instead).
    verify(mockCredential, never()).getToken(any());
    // noteFailure() fired with the unwrapped cause (Throwable arg present).
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_singleFlightWrapsCheckedCauseFromPriorLeader() {
    // Defensive branch: if some future hand-completes the in-flight future with a checked
    // Throwable (Mono.error() can't actually do this, but a misuse or future SDK change
    // could), joinUnwrapped wraps in RuntimeException rather than crashing the build.
    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CompletableFuture<AccessToken> failedFuture =
        new java.util.concurrent.CompletableFuture<>();
    failedFuture.completeExceptionally(new java.io.IOException("checked-cause-from-future"));
    sharedInFlight.set(failedFuture);

    org.slf4j.Logger mockLog = mock(org.slf4j.Logger.class);
    LiveBearerHeadersMap map =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mockLog,
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    // Same user-facing behavior: failure handled, null Bearer (header stripped), build can
    // continue.
    assertNull(map.entrySet().iterator().next().getValue());
    verify(mockLog, times(1)).warn(anyString(), anyString(), any(Throwable.class));
  }

  @Test
  public void liveBearerHeadersMap_singleFlightPropagatesErrorFromPriorLeader() {
    // joinUnwrapped must re-throw Error causes directly (not wrap in RuntimeException) so
    // unrecoverable conditions like OutOfMemoryError fail the build fast across every
    // waiter, not just the leader thread that originally hit them.
    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.CompletableFuture<AccessToken> failedFuture =
        new java.util.concurrent.CompletableFuture<>();
    failedFuture.completeExceptionally(new Error("simulated-jvm-error"));
    sharedInFlight.set(failedFuture);

    LiveBearerHeadersMap map =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    try {
      map.entrySet();
      fail("Error should propagate from the in-flight future, not be wrapped");
    } catch (Error e) {
      assertEquals("simulated-jvm-error", e.getMessage());
    }
  }

  @Test
  public void liveBearerHeadersMap_leaderErrorCompletesFutureExceptionallyAndPropagates() {
    // The leader's catch (Error) branch must completeExceptionally(error) BEFORE rethrowing
    // — otherwise waiters joined on the in-flight future would hang forever. Single-threaded
    // test exercises the catch-Error + finally-clear path on the leader side; the concurrent
    // waiter visibility is covered by leaderErrorPropagatesToConcurrentWaiters below.
    when(mockCredential.getToken(any()))
        .thenAnswer(
            invocation -> {
              throw new Error("leader-jvm-error");
            });
    LiveBearerHeadersMap map = LiveBearerHeadersMap.forTest(mockCredential);

    try {
      map.entrySet();
      fail("Error from leader should propagate, not be swallowed");
    } catch (Error e) {
      assertEquals("leader-jvm-error", e.getMessage());
    }
  }

  @Test
  public void liveBearerHeadersMap_leaderErrorPropagatesToConcurrentWaiters() throws Exception {
    // L2: regression catcher for a future refactor that drops `myFuture.completeExceptionally
    // (e)` from the leader's catch (Error) block. Without that call, the 7 waiters joined on
    // the leader's in-flight future would hang on future.join() FOREVER instead of seeing the
    // leader's Error. The 5-second per-thread timeout below is the regression catcher.
    int threadCount = 8;
    java.util.concurrent.CountDownLatch workersReady =
        new java.util.concurrent.CountDownLatch(threadCount);
    java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch leaderInGetToken =
        new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch releaseLeader = new java.util.concurrent.CountDownLatch(1);

    when(mockCredential.getToken(any()))
        .thenAnswer(
            invocation -> {
              leaderInGetToken.countDown();
              releaseLeader.await();
              throw new Error("leader-jvm-error");
            });

    java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<AccessToken>>
        sharedInFlight = new java.util.concurrent.atomic.AtomicReference<>();
    LiveBearerHeadersMap map =
        LiveBearerHeadersMap.forTest(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            sharedInFlight,
            new java.util.concurrent.atomic.AtomicReference<>());

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(threadCount);
    try {
      java.util.List<java.util.concurrent.Future<Throwable>> futures = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        futures.add(
            pool.submit(
                () -> {
                  workersReady.countDown();
                  startLatch.await();
                  try {
                    map.entrySet();
                    return null;
                  } catch (Error e) {
                    return e;
                  }
                }));
      }
      assertTrue(
          "All " + threadCount + " workers should park within 5s",
          workersReady.await(5, java.util.concurrent.TimeUnit.SECONDS));
      startLatch.countDown();
      assertTrue(
          "Leader should reach getToken() within 5s",
          leaderInGetToken.await(5, java.util.concurrent.TimeUnit.SECONDS));
      // Release the leader — its answer throws Error. If completeExceptionally(Error) is
      // correctly called from the leader's catch (Error) block, all 7 waiters unblock with
      // the same Error within the per-thread timeout. If a regression removed it, the
      // waiters hang and f.get(5, SECONDS) below times out.
      releaseLeader.countDown();

      for (java.util.concurrent.Future<Throwable> f : futures) {
        Throwable t = f.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull("Every thread should see the leader's Error, not hang", t);
        assertTrue("Error type preserved", t instanceof Error);
        assertEquals("leader-jvm-error", t.getMessage());
      }
    } finally {
      pool.shutdown();
    }
  }

  @Test
  public void liveBearerHeadersMap_singleFlightLostCasLoopsAndJoinsWinner() {
    // The "lost the CAS" branch in acquireTokenSingleFlight is only reachable under a TOCTOU
    // race: peekInFlight() returns null, then between peek and tryClaimLeadership another
    // thread installs its own future, then our tryClaimLeadership returns false. We loop
    // back, find the winning future via the next peek, and joinUnwrapped() it without
    // calling credential.getToken() ourselves. Real concurrent threads can't reliably
    // exercise this branch — simulate the race deterministically by subclassing
    // LiveBearerHeadersMap and overriding the package-private peek/tryClaim seams.
    //
    // Without this test, JaCoCo INSTRUCTION coverage drops to 99% on Java 8/11 because the
    // closing brace of the while(true) loop emits a back-edge GOTO that older javac/JaCoCo
    // tracks as a distinct instruction; Java 17+ bytecode elides it.
    AccessToken winningToken = new AccessToken("winner-token", OffsetDateTime.now().plusHours(1));
    java.util.concurrent.CompletableFuture<AccessToken> winningFuture =
        java.util.concurrent.CompletableFuture.completedFuture(winningToken);
    java.util.concurrent.atomic.AtomicInteger peekCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    LiveBearerHeadersMap map =
        new LiveBearerHeadersMap(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            new java.util.concurrent.atomic.AtomicReference<>()) {
          @Override
          java.util.concurrent.CompletableFuture<AccessToken> peekInFlight() {
            // First call (entering the loop): looks empty, no in-flight token.
            // Subsequent call (after losing CAS, looping back): winner is installed.
            return peekCalls.getAndIncrement() == 0 ? null : winningFuture;
          }

          @Override
          boolean tryClaimLeadership(java.util.concurrent.CompletableFuture<AccessToken> f) {
            // Always lose — somebody else won between our peek and CAS.
            return false;
          }
        };

    assertEquals("Bearer winner-token", map.entrySet().iterator().next().getValue());
    // Critical: we joined the winner's future instead of forking our own token request.
    verify(mockCredential, never()).getToken(any());
  }

  @Test
  public void liveBearerHeadersMap_singleFlightReChecksCacheAfterWinningCAS() {
    // N24: simulate the race where a prior leader populates cachedToken between our
    // outer fast-path read (in acquireToken) and our CAS win inside acquireTokenSingleFlight.
    // Use the tryClaimLeadership test seam to inject the cache-population side effect at
    // the exact moment between peek and CAS win. The N24 re-check inside the leader's try
    // block should observe the populated cache and short-circuit the blockForToken call.
    AccessToken priorLeaderToken =
        new AccessToken("by-prior-leader", OffsetDateTime.now().plusHours(1));
    java.util.concurrent.atomic.AtomicReference<AccessToken> sharedCache =
        new java.util.concurrent.atomic.AtomicReference<>();

    LiveBearerHeadersMap map =
        new LiveBearerHeadersMap(
            mockCredential,
            mock(org.slf4j.Logger.class),
            new java.util.concurrent.atomic.AtomicBoolean(false),
            new java.util.concurrent.atomic.AtomicReference<>(),
            sharedCache) {
          @Override
          boolean tryClaimLeadership(java.util.concurrent.CompletableFuture<AccessToken> f) {
            // Simulate a prior leader populating the cache AFTER our acquireToken
            // fast-path missed (cache was empty at that read) but BEFORE our CAS resolves.
            sharedCache.set(priorLeaderToken);
            return super.tryClaimLeadership(f);
          }
        };

    assertEquals("Bearer by-prior-leader", map.entrySet().iterator().next().getValue());
    // N24 invariant: the cache-recheck-after-CAS branch must avoid the redundant
    // blockForToken (i.e., for AzureCliCredential, the redundant `az` subprocess fork).
    verify(mockCredential, never()).getToken(any());
  }
}
