package com.servicehub.graphql;

import com.servicehub.exception.*;
import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Tag("unit")
class GraphQlErrorResolverTest {
    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(new AccessDeniedException("DO_NOT_DISCLOSE"), "FORBIDDEN"),
                Arguments.of(new BadCredentialsException("DO_NOT_DISCLOSE"), "UNAUTHORIZED"),
                Arguments.of(new ResourceNotFoundException("DO_NOT_DISCLOSE"), "NOT_FOUND"),
                Arguments.of(new BusinessConflictException("Solicitação já avaliada."), "CONFLICT"),
                Arguments.of(new DataIntegrityViolationException("SQL DO_NOT_DISCLOSE"), "CONFLICT"),
                Arguments.of(new CannotAcquireLockException("SQL DO_NOT_DISCLOSE"), "CONFLICT"),
                Arguments.of(new IllegalArgumentException("DO_NOT_DISCLOSE"), "BAD_REQUEST"),
                Arguments.of(new ConstraintViolationException(Set.of()), "BAD_REQUEST"),
                Arguments.of(new BindException(new Object(), "DO_NOT_DISCLOSE"), "BAD_REQUEST"));
    }
    @ParameterizedTest @MethodSource("errors")
    void classifiesKnownErrorsWithoutRevealingInternalMessages(Throwable exception, String code) {
        var environment = mock(DataFetchingEnvironment.class);
        when(environment.getField()).thenReturn(Field.newField("resource").sourceLocation(new SourceLocation(1,1)).build());
        when(environment.getExecutionStepInfo()).thenReturn(ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString).path(ResultPath.parse("/resource")).build());
        var errors = new GraphQlErrorResolver().resolveException(exception,environment).block();
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().getExtensions()).containsEntry("code",code);
        assertThat(errors.getFirst().getMessage()).doesNotContain("DO_NOT_DISCLOSE","SQL");
        assertThat(errors.getFirst().getPath()).containsExactly("resource");
    }
    @Test
    void leavesUnknownFailuresToSpringsOpaqueInternalErrorHandler() {
        var result = new GraphQlErrorResolver().resolveException(new IllegalStateException("SQL DO_NOT_DISCLOSE"),mock(DataFetchingEnvironment.class)).block();
        assertThat(result).isNull();
    }
}
