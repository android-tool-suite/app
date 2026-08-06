package com.androidtoolsuite.app.migration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MigrationTransaction {
    private MigrationTransaction() {
    }

    public static void execute(List<Operation> operations) throws IOException {
        List<Operation> started = new ArrayList<>();
        try {
            for (Operation operation : operations) {
                started.add(operation);
                operation.apply();
            }
        } catch (Exception failure) {
            IOException transactionFailure = new IOException(
                    "迁移事务提交失败：" + failure.getMessage(),
                    failure
            );
            Collections.reverse(started);
            for (Operation operation : started) {
                try {
                    operation.rollback();
                } catch (Exception rollbackFailure) {
                    transactionFailure.addSuppressed(rollbackFailure);
                }
            }
            throw transactionFailure;
        }
    }

    public interface Operation {
        void apply() throws Exception;

        void rollback() throws Exception;
    }
}
