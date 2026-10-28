package com.servicehub.service;

import com.servicehub.model.ServiceRequestStatus;
import com.servicehub.exception.BusinessConflictException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.Tag("unit")
class RequestTransitionsTest {
    static Stream<Arguments> edges() {
        return Stream.of(ServiceRequestStatus.values()).flatMap(from -> Stream.of(ServiceRequestStatus.values()).map(to -> Arguments.of(from, to)));
    }
    @ParameterizedTest
    @MethodSource("edges")
    void enforcesEveryEdgeIncludingFinalStates(ServiceRequestStatus from, ServiceRequestStatus to) {
        var valid = Set.of("PENDING:ACCEPTED", "ACCEPTED:IN_PROGRESS", "IN_PROGRESS:COMPLETED",
                "PENDING:CANCELLED", "ACCEPTED:CANCELLED", "IN_PROGRESS:CANCELLED");
        if (valid.contains(from + ":" + to)) assertThatCode(() -> RequestTransitions.require(from, to)).doesNotThrowAnyException();
        else assertThatThrownBy(() -> RequestTransitions.require(from, to)).isInstanceOf(BusinessConflictException.class);
    }
}
