package com.moveinsync.config.core.change;

import com.moveinsync.config.core.Seeds;
import com.moveinsync.config.core.error.ValidationError;
import com.moveinsync.config.core.error.ValidationException;
import com.moveinsync.config.core.scope.ScopeKey;
import com.moveinsync.config.core.snapshot.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.moveinsync.config.core.Seeds.changeSet;
import static com.moveinsync.config.core.Seeds.set;
import static com.moveinsync.config.core.Seeds.unset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.assertj.core.groups.Tuple.tuple;

class ChangeSetValidatorTest {

    private static final ScopeKey BLR = ScopeKey.office("blr-ecospace");

    private final Snapshot seeded = Seeds.snapshot();
    private final ChangeSetValidator validator = new ChangeSetValidator(seeded);

    @Test
    void collectsAllErrorsInOnePass() {
        List<ValidationError> errors = errorsOf(changeSet("acme",
                set(BLR, "booking.colour", "\"red\""),
                set(BLR, "booking.cutoff", "\"-PT5M\""),
                set(BLR, "office.hours", "{\"start\":\"20:00\",\"end\":\"08:00\"}"),
                set(BLR, "booking.maxActive", "4")));

        assertThat(errors).extracting(ValidationError::operationIndex, ValidationError::code).containsExactly(
                tuple(0, "UNKNOWN_KEY"),
                tuple(1, "NEGATIVE_DURATION"),
                tuple(2, "WINDOW_END_BEFORE_START"));
    }

    @Test
    void globalScopeIsRejected() {
        List<ValidationError> errors = errorsOf(changeSet("acme", set(ScopeKey.GLOBAL, "booking.cutoff", "\"PT30M\"")));

        assertThat(errors).extracting(ValidationError::code).containsExactly("GLOBAL_SCOPE_NOT_ALLOWED");
    }

    @Test
    void lessThanIsCheckedWithinTheChangeSet() {
        List<ValidationError> errors = errorsOf(changeSet("acme",
                set(BLR, "booking.cutoff", "\"PT4H\""),
                set(BLR, "booking.maxAdvance", "\"PT2H\"")));

        assertThat(errors).extracting(ValidationError::operationIndex, ValidationError::code)
                .containsExactly(tuple(0, "CROSS_FIELD_ORDER"));
    }

    @Test
    void lessThanIsCheckedAgainstTheSnapshot() {
        // PT200H is also above booking.cutoff's max, so both rules report
        List<ValidationError> errors = errorsOf(changeSet("acme", set(BLR, "booking.cutoff", "\"PT200H\"")));

        assertThat(errors).extracting(ValidationError::code)
                .containsExactlyInAnyOrder("OUT_OF_RANGE", "CROSS_FIELD_ORDER");
    }

    @Test
    void lessThanSeesAnOverrideFromTheSnapshotAtThatScope() {
        Snapshot current = seeded.apply(seeded.version() + 1,
                changeSet("acme", set(BLR, "booking.maxAdvance", "\"PT2H\"")));

        List<ValidationError> errors = errorsOf(new ChangeSetValidator(current),
                changeSet("acme", set(BLR, "booking.cutoff", "\"PT3H\"")));

        assertThat(errors).extracting(ValidationError::code).containsExactly("CROSS_FIELD_ORDER");
    }

    @Test
    void raisingBothKeysTogetherPassesWhereOneAloneWouldFail() {
        Snapshot current = seeded.apply(seeded.version() + 1,
                changeSet("acme", set(BLR, "booking.maxAdvance", "\"PT2H\"")));

        List<Operation> parsed = new ChangeSetValidator(current).validate(changeSet("acme",
                set(BLR, "booking.cutoff", "\"PT3H\""),
                set(BLR, "booking.maxAdvance", "\"PT4H\"")));

        assertThat(parsed).hasSize(2);
    }

    @Test
    void returnsTheOperationsWithCanonicalValues() {
        List<Operation> parsed = validator.validate(changeSet("acme",
                set(BLR, "booking.cutoff", "\"PT60M\""),
                unset(ScopeKey.client("acme"), "cab.types")));

        assertThat(parsed).containsExactly(
                new Operation(OpKind.SET, BLR, "booking.cutoff", Seeds.json("\"PT1H\"")),
                new Operation(OpKind.UNSET, ScopeKey.client("acme"), "cab.types", null));
    }

    @Test
    void messagesNeverEchoTheSubmittedValue() {
        List<ValidationError> errors = errorsOf(changeSet("acme",
                set(BLR, "escort.required", "\"secret-maybe\""),
                set(BLR, "booking.cutoff", "\"PT23H59M\""),
                set(BLR, "booking.maxAdvance", "\"PT2H\"")));

        assertThat(errors).hasSize(2);
        assertThat(errors).allSatisfy(error -> {
            assertThat(error.message()).doesNotContain("secret-maybe");
            assertThat(error.message()).doesNotContain("PT23H59M");
        });
    }

    private List<ValidationError> errorsOf(ChangeSet changeSet) {
        return errorsOf(validator, changeSet);
    }

    private static List<ValidationError> errorsOf(ChangeSetValidator validator, ChangeSet changeSet) {
        try {
            validator.validate(changeSet);
        } catch (ValidationException e) {
            return e.errors();
        }
        return fail("expected the change set to be rejected");
    }
}
