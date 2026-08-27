package com.wander.auth;

public class RegistrationDisabledException extends RuntimeException {

    public RegistrationDisabledException() {
        super("Registration is disabled on this instance");
    }
}
