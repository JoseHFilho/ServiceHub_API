package com.servicehub.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.server.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Validation errors must not echo arbitrary client input or internal scalar conversion details. */
@Component
public class GraphQlResponseInterceptor implements WebGraphQlInterceptor {
    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        return chain.next(request).map(response -> {
            List<GraphQLError> errors = response.getExecutionResult().getErrors().stream().map(error -> {
                if (error.getPath() != null || (error.getExtensions() != null
                        && "QUERY_LIMIT_EXCEEDED".equals(error.getExtensions().get("code")))) return error;
                return (GraphQLError) GraphqlErrorBuilder.newError().errorType(ErrorType.BAD_REQUEST)
                        .message("Consulta ou variáveis GraphQL inválidas.")
                        .locations(error.getLocations()).extensions(Map.of("code", "BAD_REQUEST")).build();
            }).toList();
            return errors.isEmpty() ? response : response.transform(builder -> builder.errors(errors));
        });
    }
}
