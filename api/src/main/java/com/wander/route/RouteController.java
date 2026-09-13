package com.wander.route;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wander.common.UpstreamQuota;
import com.wander.route.dto.DayRoutePreview;
import com.wander.security.WanderUser;

/**
 * Sorting one day by route.
 *
 * A day is addressed by its date, like its note is: days are derived from the
 * trip's range and have no rows of their own.
 *
 * `POST` for something that writes nothing, which is worth defending: the
 * request reaches a third-party service on the instance's behalf and spends a
 * quota doing it, so it is neither cacheable nor safe to repeat idly, and a GET
 * is both of those by definition. Booking import posts a file it never stores
 * for the same reason.
 *
 * Named `previewDayRoute` because ng-openapi-gen exports every operation
 * unqualified into one barrel — and because "preview" is the honest word for
 * what comes back.
 */
@RestController
@RequestMapping("/api/trips/{tripId}/days/{date}/route")
public class RouteController {

    private final RouteService routes;
    private final UpstreamQuota quota;

    public RouteController(RouteService routes, UpstreamQuota quota) {
        this.routes = routes;
        this.quota = quota;
    }

    @PostMapping("/preview")
    public DayRoutePreview previewDayRoute(@AuthenticationPrincipal WanderUser principal,
            @PathVariable Long tripId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "WALKING") RouteProfile profile) {
        // Before the service, as the forecast does it: a caller already over
        // the line costs a map lookup rather than an outbound call.
        quota.route(principal.id());
        return routes.preview(principal.id(), tripId, date, profile);
    }
}
