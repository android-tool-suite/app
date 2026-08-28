package com.androidtoolsuite.runtime.contract;

import java.util.Collection;

public final class ProtocolVersion implements Comparable<ProtocolVersion> {
    public static final ProtocolVersion CURRENT = new ProtocolVersion(2, 0);

    public final int major;
    public final int minor;

    public ProtocolVersion(int major, int minor) {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Protocol version cannot be negative");
        }
        this.major = major;
        this.minor = minor;
    }

    public static ProtocolVersion parse(String raw) throws ContractException {
        String value = raw == null ? "" : raw.trim();
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) {
            throw new ContractException("protocol 必须是 major.minor");
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            if (major < 0 || minor < 0) {
                throw new NumberFormatException("negative");
            }
            return new ProtocolVersion(major, minor);
        } catch (NumberFormatException error) {
            throw new ContractException("protocol 必须是非负整数 major.minor", error);
        }
    }

    public static ProtocolVersion negotiate(
            Collection<ProtocolVersion> client,
            Collection<ProtocolVersion> host
    ) throws ContractException {
        ProtocolVersion selected = null;
        for (ProtocolVersion left : client) {
            for (ProtocolVersion right : host) {
                if (left.major == right.major) {
                    ProtocolVersion candidate = new ProtocolVersion(left.major, Math.min(left.minor, right.minor));
                    if (selected == null || candidate.compareTo(selected) > 0) {
                        selected = candidate;
                    }
                }
            }
        }
        if (selected == null) {
            throw new ContractException("没有兼容的 RPC protocol major");
        }
        return selected;
    }

    @Override
    public int compareTo(ProtocolVersion other) {
        int majorComparison = Integer.compare(major, other.major);
        return majorComparison != 0 ? majorComparison : Integer.compare(minor, other.minor);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProtocolVersion && major == ((ProtocolVersion) other).major
                && minor == ((ProtocolVersion) other).minor;
    }

    @Override
    public int hashCode() {
        return 31 * major + minor;
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
