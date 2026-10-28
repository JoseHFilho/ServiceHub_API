package com.servicehub.graphql;

import com.servicehub.exception.BusinessConflictException;
import com.servicehub.exception.ResourceNotFoundException;
import graphql.ErrorClassification;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindException;
import org.springframework.validation.method.MethodValidationException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GraphQlErrorResolver extends DataFetcherExceptionResolverAdapter {
    private enum Conflict implements ErrorClassification { CONFLICT }

    @Override
    protected GraphQLError resolveToSingleError(Throwable error, DataFetchingEnvironment environment) {
        while ((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        if (error instanceof AuthenticationException) return build(environment, ErrorType.UNAUTHORIZED, "UNAUTHORIZED", "Autenticação necessária.");
        if (error instanceof AccessDeniedException) return build(environment, ErrorType.FORBIDDEN, "FORBIDDEN", "Acesso negado.");
        if (error instanceof ResourceNotFoundException) return build(environment, ErrorType.NOT_FOUND, "NOT_FOUND", "Recurso indisponível.");
        if (error instanceof BusinessConflictException) return build(environment, Conflict.CONFLICT, "CONFLICT", error.getMessage());
        if (error instanceof DataIntegrityViolationException || error instanceof ConcurrencyFailureException) {
            return build(environment, Conflict.CONFLICT, "CONFLICT", "A operação conflita com dados existentes ou uma alteração concorrente.");
        }
        if (error instanceof ConstraintViolationException || error instanceof BindException
                || error instanceof MethodValidationException || error instanceof IllegalArgumentException) {
            return build(environment, ErrorType.BAD_REQUEST, "BAD_REQUEST", "Dados de entrada inválidos.");
        }
        // Spring's default INTERNAL_ERROR contains only a correlation ID, never the original exception.
        return null;
    }
    private GraphQLError build(DataFetchingEnvironment environment, ErrorClassification type, String code, String message) {
        return GraphqlErrorBuilder.newError(environment).errorType(type).message(message).extensions(Map.of("code", code)).build();
    }
}
