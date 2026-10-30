package com.moveinsync.config.loadtest;

import com.moveinsync.config.core.scope.ScopeLevel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// runs the benchmark's state and each case once without JMH, so a broken fixture fails the build, not the benchmark
class ReadLatencyBenchmarkTest {

    @Test
    void eachCaseReadsFromTheLevelItIsNamedAfter() {
        ReadLatencyBenchmark benchmark = new ReadLatencyBenchmark();
        benchmark.setup();

        assertThat(benchmark.snapshot.resolve(benchmark.tenant, benchmark.key, benchmark.shiftPath).source())
                .isEqualTo(ScopeLevel.SHIFT);
        assertThat(benchmark.snapshot.resolve(benchmark.tenant, benchmark.key, benchmark.officePath).source())
                .isEqualTo(ScopeLevel.OFFICE);
        assertThat(benchmark.snapshot.resolve(benchmark.tenant, benchmark.key, benchmark.fallbackPath).source())
                .isEqualTo(ScopeLevel.GLOBAL);

        assertThat(benchmark.shiftHit()).isEqualTo(ReadLatencyBenchmark.SHIFT_VALUE);
        assertThat(benchmark.officeHit()).isNotEqualTo(ReadLatencyBenchmark.DEFAULT_VALUE);
        assertThat(benchmark.globalFallback()).isEqualTo(ReadLatencyBenchmark.DEFAULT_VALUE);
    }

    @Test
    void snapshotHoldsFiftyThousandOfficeOverrides() {
        var snapshot = ReadLatencyBenchmark.buildSnapshot();

        int officeOverrides = 0;
        for (String tenant : snapshot.tenantIds()) {
            for (var scope : snapshot.tenantOverrides(tenant).entrySet()) {
                if (scope.getKey().level() == ScopeLevel.OFFICE) {
                    officeOverrides += scope.getValue().size();
                }
            }
        }
        assertThat(snapshot.tenantIds()).hasSize(50);
        assertThat(officeOverrides).isEqualTo(50 * 40 * 25);
    }
}
