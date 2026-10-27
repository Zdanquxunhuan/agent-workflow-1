package com.yu.agent.workflow.api;

public class BusinessException extends RuntimeException {
    private final String code;

    private BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static BusinessException of(String code, String message) { return new BusinessException(code, message); }
    public String code() { return code; }
}
