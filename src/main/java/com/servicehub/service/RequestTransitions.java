package com.servicehub.service;

import com.servicehub.exception.BusinessConflictException;
import com.servicehub.model.ServiceRequestStatus;

public final class RequestTransitions {
    private RequestTransitions() { }
    public static boolean allowed(ServiceRequestStatus from, ServiceRequestStatus to) {
        if (from == null || to == null || !ServiceRequestStatus.openStatuses().contains(from)) return false;
        if (to == ServiceRequestStatus.CANCELLED) return true;
        return switch (from) {
            case PENDING -> to == ServiceRequestStatus.ACCEPTED;
            case ACCEPTED -> to == ServiceRequestStatus.IN_PROGRESS;
            case IN_PROGRESS -> to == ServiceRequestStatus.COMPLETED;
            default -> false;
        };
    }
    public static void require(ServiceRequestStatus from, ServiceRequestStatus to) {
        if (to == null) throw new IllegalArgumentException("Informe o estado de destino.");
        if (!allowed(from, to)) throw new BusinessConflictException("Transição de estado inválida ou solicitação já finalizada.");
    }
}
