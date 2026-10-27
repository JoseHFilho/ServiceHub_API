package com.servicehub.model;

import java.util.Set;

public enum ServiceRequestStatus {
    PENDING,
    ACCEPTED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    public static Set<ServiceRequestStatus> openStatuses() {
        return Set.of(PENDING, ACCEPTED, IN_PROGRESS);
    }
}
