package com.servicehub.security;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** H2/test only. PostgreSQL deployments always use the shared database store. */
final class LocalAuthAdmissionStore implements AuthAdmissionStore {
    private record Bucket(long window, int attempts) { }
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private final Clock clock;
    LocalAuthAdmissionStore(Clock clock) { this.clock = clock; }
    @Override public synchronized void consume(List<Quota> quotas, int seconds) {
        long now = clock.instant().getEpochSecond(), start = now / seconds * seconds;
        buckets.entrySet().removeIf(entry -> entry.getValue().window < start);
        if (buckets.size() + quotas.size() > 10000) throw new AuthRateExceeded(start + seconds - now, "frequency");
        for (var quota : quotas) {
            var bucket = buckets.get(quota.key());
            if (bucket != null && bucket.attempts >= quota.maximum()) throw new AuthRateExceeded(start + seconds - now, "frequency");
        }
        for (var quota : quotas) {
            var bucket = buckets.get(quota.key());
            buckets.put(quota.key(), new Bucket(start, bucket == null ? 1 : bucket.attempts + 1));
        }
    }
    @Override public Permit acquire(int maximum) {
        int value = active.incrementAndGet();
        if (value > maximum) { active.decrementAndGet(); throw new AuthRateExceeded(1, "concurrency"); }
        return new Permit() {
            private boolean closed;
            @Override public synchronized void close() { if (!closed) { closed = true; active.decrementAndGet(); } }
        };
    }
}
