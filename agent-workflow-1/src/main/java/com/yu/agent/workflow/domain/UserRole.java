package com.yu.agent.workflow.domain;

public enum UserRole {
    AGENT,
    CUSTOMER_SERVICE,
    SUPERVISOR,
    ADMIN;

    public static UserRole parse(String value) {
        if (value == null || value.isBlank()) {
            return CUSTOMER_SERVICE;
        }
        try {
            return valueOf(value.trim().toUpperCase().replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported role: " + value);
        }
    }
}
