package com.wander.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(max = 80) String displayName,
        // Length is the only policy that matters at this size; a full policy
        // (character classes, breach-list check) belongs with invites later.
        @NotBlank @Size(min = 10, max = 200) String password) {
}
