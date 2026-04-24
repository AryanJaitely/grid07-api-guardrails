package com.grid07.dto;

// thrown when any of the redis guardrails block a request
// gets mapped to 429 in the exception handler
public class GuardrailException extends RuntimeException {

    public GuardrailException(String message) {
        super(message);
    }
}
