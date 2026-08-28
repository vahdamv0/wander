package com.wander.packing.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Ticking one item.
 *
 * Its own endpoint and its own one-field body, because ticking is by far the
 * commonest thing anybody does here and sending the whole item to flip a boolean
 * would let a tick quietly undo a rename that arrived in between.
 */
public record PackedRequest(@NotNull Boolean packed) {
}
