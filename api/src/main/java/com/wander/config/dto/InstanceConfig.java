package com.wander.config.dto;

import jakarta.validation.constraints.NotNull;

/**
 * What the client needs to know about this instance before it draws anything.
 *
 * It exists because wander is self-hosted: the tile server, and whether place
 * search works at all, are the operator's decisions, and a client that guessed
 * at them would be wrong on somebody's instance. Everything here is a setting,
 * never user data.
 */
public record InstanceConfig(
        /** False on an instance with no outbound network — the client hides its search box. */
        @NotNull boolean searchEnabled,
        /** Preselected in the new-trip form. ISO 4217. */
        @NotNull String defaultCurrency,
        /**
         * False means the trip page never asks for a forecast. Saves a request
         * per trip page on an instance with weather off — and the endpoint would
         * answer "unavailable" anyway, so this is the client not asking a question
         * it already knows the answer to.
         */
        @NotNull boolean weatherEnabled,
        @NotNull MapConfig map,
        /**
         * What is running. Shown in the account menu, so somebody reporting a
         * problem can say which version they are on — the one question that is
         * always asked and that nobody can answer from a self-hosted instance
         * without SSH.
         */
        @NotNull String version,
        /**
         * The commit this image was built from, or empty when it was not built
         * by CI. Empty is shown as nothing: a chip reading "unknown" is worse
         * than a chip that simply stops after the version.
         */
        @NotNull String buildRef,
        /**
         * Where this instance's source lives — see {@code WanderProperties}. It
         * rides along here as well as on the public sign-in config because the
         * account menu is where somebody already signed in goes looking, and it
         * sits beside the version chip: "what am I running" and "where is it"
         * are one question asked twice.
         *
         * Empty when the operator cleared it, and empty draws nothing.
         */
        @NotNull String sourceUrl,
        /**
         * How often the shared demo account's own trips are swept, in minutes,
         * or **0** on an instance with the demo off.
         *
         * Here because it is a setting, unlike {@code SessionUser.demoAccount},
         * which is a fact about the account — the pair is what lets the trips
         * page say "deleted every 45 minutes" to the one account it happens to
         * and to nobody else. It has to travel rather than be a constant in the
         * client for the usual reason: the interval is the operator's choice,
         * and a page promising 45 minutes on an instance configured for 10 is
         * worse than saying nothing, because somebody would believe it.
         */
        @NotNull int demoSweepMinutes) {
}
