package com.servicehub.security;

import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
class LocalAuthAdmissionStoreTest {
    private static class MutableClock extends Clock {
        final AtomicLong epoch = new AtomicLong(120);
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochSecond(epoch.get()); }
    }
    @Test void appliesAllQuotasAtomicallyAndRecoversAtTheNextWindow() {
        var clock = new MutableClock(); var store = new LocalAuthAdmissionStore(clock);
        var global = new AuthAdmissionStore.Quota("global", 2);
        var firstIp = new AuthAdmissionStore.Quota("ip-one", 1);
        store.consume(List.of(global, firstIp), 60);
        assertThatThrownBy(() -> store.consume(List.of(global, firstIp), 60)).isInstanceOf(AuthRateExceeded.class);
        store.consume(List.of(global, new AuthAdmissionStore.Quota("ip-two", 1)), 60);
        assertThatThrownBy(() -> store.consume(List.of(global), 60)).isInstanceOf(AuthRateExceeded.class);
        clock.epoch.set(180); store.consume(List.of(global, firstIp), 60);
    }
    @Test void concurrentAdmissionsRespectCapacityAndReleaseWithoutDoubleRelease() throws Exception {
        var store = new LocalAuthAdmissionStore(Clock.systemUTC());
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1); var attempted = new CountDownLatch(8); var release = new CountDownLatch(1);
            var accepted = new java.util.concurrent.atomic.AtomicInteger();
            var futures = new java.util.ArrayList<Future<?>>();
            for (int index = 0; index < 8; index++) futures.add(executor.submit(() -> {
                try {
                    start.await();
                    try (var permit = store.acquire(2)) { accepted.incrementAndGet(); attempted.countDown(); release.await(); permit.close(); }
                    catch (AuthRateExceeded expected) { attempted.countDown(); }
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new RuntimeException(exception); }
            }));
            start.countDown();
            try { assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue(); assertThat(accepted.get()).isEqualTo(2); }
            finally { release.countDown(); }
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            try (var first = store.acquire(2); var second = store.acquire(2)) {
                assertThatThrownBy(() -> store.acquire(2)).isInstanceOf(AuthRateExceeded.class);
            }
        }
    }
}
