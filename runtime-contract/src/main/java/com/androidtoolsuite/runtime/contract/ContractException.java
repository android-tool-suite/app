package com.androidtoolsuite.runtime.contract;

public final class ContractException extends Exception {
    public ContractException(String message) {
        super(message);
    }

    public ContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
