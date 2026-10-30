package com.moveinsync.config.server.rollback;

import com.moveinsync.config.core.change.OpKind;
import com.moveinsync.config.core.change.Operation;
import com.moveinsync.config.core.definition.Rules;
import com.moveinsync.config.core.definition.SettingDefinition;
import com.moveinsync.config.core.definition.SettingTypes;
import com.moveinsync.config.core.scope.ScopeKey;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiffTest {

    private static final ScopeKey BLR = ScopeKey.office("blr-ecospace");
    private static final Map<String, SettingDefinition> DEFINITIONS = Map.of("booking.maxActive",
            new SettingDefinition("booking.maxActive", SettingTypes.NUMBER, Rules.NONE, BigDecimal.ONE, ""));

    @Test
    void sameNumberWithADifferentScaleIsNoChange() {
        Map<ScopeKey, Map<String, Object>> inMemory = Map.of(BLR, Map.of("booking.maxActive", new BigDecimal("1E+1")));
        Map<ScopeKey, Map<String, Object>> replayed = Map.of(BLR, Map.of("booking.maxActive", new BigDecimal("10")));

        assertThat(Diff.between(inMemory, replayed, DEFINITIONS)).isEmpty();
    }

    @Test
    void differentNumberIsStillASet() {
        Map<ScopeKey, Map<String, Object>> current = Map.of(BLR, Map.of("booking.maxActive", new BigDecimal("10")));
        Map<ScopeKey, Map<String, Object>> target = Map.of(BLR, Map.of("booking.maxActive", new BigDecimal("4")));

        List<Operation> diff = Diff.between(current, target, DEFINITIONS);

        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).op()).isEqualTo(OpKind.SET);
        assertThat(diff.get(0).value().decimalValue()).isEqualByComparingTo("4");
    }
}
