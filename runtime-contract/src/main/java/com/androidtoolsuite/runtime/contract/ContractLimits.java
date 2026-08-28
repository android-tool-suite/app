package com.androidtoolsuite.runtime.contract;

public final class ContractLimits {
    public static final int MAX_MANIFEST_BYTES = 256 * 1024;
    public static final int MAX_RPC_BYTES = 256 * 1024;
    public static final int MAX_JSON_DEPTH = 32;
    public static final int MAX_PACKAGE_ENTRIES = 4096;
    public static final long MAX_PACKAGE_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_PACKAGE_ENTRY_BYTES = 32L * 1024L * 1024L;
    public static final int MAX_PENDING_REQUESTS = 64;
    public static final int MAX_PRE_READY_MESSAGES = 32;
    public static final int DEFAULT_DEADLINE_MS = 30_000;
    public static final int MAX_DEADLINE_MS = 600_000;

    private ContractLimits() {
    }
}
