package com.androidtoolsuite.app.plugin.runtime;

/** Pure scheduler decisions kept separate from WorkManager so lifecycle rules are JVM-testable. */
final class SchedulerPolicy {
    static final long MIN_PERIODIC_MINUTES = 15L;
    static final long LEASE_FLOOR_MILLIS = 15L * 60_000L;

    private SchedulerPolicy() {
    }

    static boolean canSchedule(boolean pluginEnabled, boolean permissionGranted) {
        return pluginEnabled && permissionGranted;
    }

    static long scheduledIntervalMinutes(String triggerType, long declaredIntervalMinutes) {
        if ("periodic".equals(triggerType)) {
            return Math.max(MIN_PERIODIC_MINUTES, declaredIntervalMinutes);
        }
        if ("network-available".equals(triggerType) || "charging".equals(triggerType)) {
            return MIN_PERIODIC_MINUTES;
        }
        return 0L;
    }

    static String effectiveNetwork(String declaredNetwork, String triggerType) {
        if ("network-available".equals(triggerType) && "none".equals(declaredNetwork)) {
            return "connected";
        }
        return declaredNetwork;
    }

    static boolean requiresCharging(boolean declaredCharging, String triggerType) {
        return declaredCharging || "charging".equals(triggerType);
    }

    static boolean isActiveStatus(String status) {
        return "queued".equals(status) || "running".equals(status) || "retrying".equals(status);
    }

    static boolean matchesProviderEvent(
            String triggerCapability,
            String triggerEvent,
            String capability,
            String event
    ) {
        return triggerCapability.equals(capability) && triggerEvent.equals(event);
    }

    static boolean shouldRetry(boolean retryable, int attempt, int maxAttempts, boolean stopped) {
        return retryable && attempt < maxAttempts && !stopped;
    }

    static long leaseStaleAfterMillis(long timeoutMillis) {
        return Math.max(LEASE_FLOOR_MILLIS, timeoutMillis + 60_000L);
    }
}
