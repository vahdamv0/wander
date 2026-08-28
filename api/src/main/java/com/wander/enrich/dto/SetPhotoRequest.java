package com.wander.enrich.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Keeping one of the candidates as the place's photo.
 *
 * The credit is part of the request and required, not looked up afterwards: it is
 * what makes displaying the picture permissible, and a request that cannot say
 * who took it is one the server should refuse rather than complete. The server
 * still checks the URL against the candidates it offered, so this is a
 * confirmation rather than a way to store arbitrary text.
 */
public record SetPhotoRequest(
        /** Null clears the photo — "actually, no picture". */
        @Size(max = 500) String url,
        @Size(max = 500) String thumbUrl,
        @Size(max = 300) String author,
        @Size(max = 120) String licence,
        @Size(max = 500) String sourceUrl) {

    public boolean isClearing() {
        return url == null || url.isBlank();
    }
}
