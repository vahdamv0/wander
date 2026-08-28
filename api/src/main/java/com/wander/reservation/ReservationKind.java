package com.wander.reservation;

/**
 * What sort of booking it is. Only ever used to pick an icon and a label — no
 * behaviour hangs off it, which is why OTHER is enough of an escape hatch and
 * there is no "custom kind" to manage.
 */
public enum ReservationKind {
    FLIGHT,
    TRAIN,
    BUS,
    FERRY,
    CAR,
    HOTEL,
    RESTAURANT,
    ACTIVITY,
    OTHER
}
