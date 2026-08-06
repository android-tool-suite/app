package com.androidtoolsuite.app.migration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class MigrationTransactionTest {
    @Test
    public void rollsBackStartedOperationsInReverseOrder() {
        List<String> state = new ArrayList<>();
        List<String> events = new ArrayList<>();
        List<MigrationTransaction.Operation> operations = new ArrayList<>();
        operations.add(operation(state, events, "plugin-a", false));
        operations.add(operation(state, events, "plugin-b", true));

        try {
            MigrationTransaction.execute(operations);
        } catch (IOException expected) {
            assertTrue(state.isEmpty());
            assertEquals(
                    List.of("apply:plugin-a", "apply:plugin-b", "rollback:plugin-b", "rollback:plugin-a"),
                    events
            );
            return;
        }
        throw new AssertionError("Expected the transaction to fail");
    }

    private MigrationTransaction.Operation operation(
            List<String> state,
            List<String> events,
            String value,
            boolean failAfterApply
    ) {
        return new MigrationTransaction.Operation() {
            @Override
            public void apply() throws IOException {
                events.add("apply:" + value);
                state.add(value);
                if (failAfterApply) {
                    throw new IOException("simulated failure");
                }
            }

            @Override
            public void rollback() {
                events.add("rollback:" + value);
                state.remove(value);
            }
        };
    }
}
