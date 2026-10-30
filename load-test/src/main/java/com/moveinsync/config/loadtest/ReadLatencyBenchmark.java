package com.moveinsync.config.loadtest;

import com.moveinsync.config.core.definition.Rules;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.definition.SettingTypes;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.scope.ScopePath;
import com.moveinsync.config.core.snapshot.Snapshot;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// one read is a map walk on an immutable snapshot; this measures it at the size of a large deployment
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(1)
public class ReadLatencyBenchmark {

    static final int TENANTS = 50;
    static final int OFFICES = 40;
    static final int KEYS = 25;

    static final Duration DEFAULT_VALUE = Duration.ofMinutes(60);
    static final Duration SHIFT_VALUE = Duration.ofMinutes(15);

    Snapshot snapshot;
    String tenant;
    String key;
    ScopePath shiftPath;
    ScopePath officePath;
    ScopePath fallbackPath;

    @Setup
    public void setup() {
        snapshot = buildSnapshot();
        // a tenant and office from the middle, so nothing about the first or last entry is special
        tenant = tenantId(TENANTS / 2);
        key = key(KEYS / 2);
        String office = officeId(OFFICES / 2);
        shiftPath = new ScopePath(tenant, office, nightShift(office));
        officePath = new ScopePath(tenant, office, "day");
        // an office with no overrides: the walk misses SHIFT, OFFICE and CLIENT before taking the default
        fallbackPath = new ScopePath(tenant, "office-without-overrides", "day");
    }

    @Benchmark
    public Duration shiftHit() {
        return snapshot.get(tenant, key, shiftPath, Duration.class);
    }

    @Benchmark
    public Duration officeHit() {
        return snapshot.get(tenant, key, officePath, Duration.class);
    }

    @Benchmark
    public Duration globalFallback() {
        return snapshot.get(tenant, key, fallbackPath, Duration.class);
    }

    // 50 tenants x 40 offices x 25 keys = 50,000 OFFICE overrides, plus a night shift in every office for one key
    static Snapshot buildSnapshot() {
        Map<String, SettingDefinition> definitions = new HashMap<>();
        for (int k = 0; k < KEYS; k++) {
            definitions.put(key(k), new SettingDefinition(key(k), SettingTypes.DURATION, Rules.NONE, DEFAULT_VALUE,
                    "benchmark setting " + k));
        }

        Map<String, Map<ScopeKey, Map<String, Object>>> overrides = new HashMap<>();
        for (int t = 0; t < TENANTS; t++) {
            Map<ScopeKey, Map<String, Object>> scopes = new HashMap<>();
            for (int o = 0; o < OFFICES; o++) {
                Map<String, Object> values = new HashMap<>();
                for (int k = 0; k < KEYS; k++) {
                    values.put(key(k), Duration.ofMinutes(20 + (o + k) % 40));
                }
                scopes.put(ScopeKey.office(officeId(o)), values);
                scopes.put(ScopeKey.shift(nightShift(officeId(o))), Map.of(key(KEYS / 2), SHIFT_VALUE));
            }
            overrides.put(tenantId(t), scopes);
        }
        return Snapshot.of(1, definitions, overrides);
    }

    static String tenantId(int t) {
        return "tenant-" + t;
    }

    static String officeId(int o) {
        return "office-" + o;
    }

    static String key(int k) {
        return "bench.setting" + k;
    }

    static String nightShift(String officeId) {
        return officeId + "-night";
    }

    public static void main(String[] args) throws RunnerException {
        new Runner(new OptionsBuilder().include(ReadLatencyBenchmark.class.getName()).build()).run();
    }
}
