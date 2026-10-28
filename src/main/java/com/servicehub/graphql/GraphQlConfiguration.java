package com.servicehub.graphql;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.schema.visibility.NoIntrospectionGraphqlFieldVisibility;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration(proxyBeanMethods = false)
public class GraphQlConfiguration {
    @Bean
    RuntimeWiringConfigurer scalarWiring(Environment environment) {
        boolean development = environment.acceptsProfiles(Profiles.of("(dev | test) & !prod"));
        return builder -> {
            builder.scalar(GraphQlScalars.decimal()).scalar(GraphQlScalars.localDateTime()).scalar(GraphQlScalars.longInteger());
            if (!development) builder.fieldVisibility(NoIntrospectionGraphqlFieldVisibility.NO_INTROSPECTION_FIELD_VISIBILITY);
        };
    }
    @Bean GraphQlLimits graphQlLimits() { return new GraphQlLimits(); }
    @Bean MaxQueryDepthInstrumentation queryDepth() {
        return new MaxQueryDepthInstrumentation(GraphQlLimits.MAX_DEPTH, info -> { throw GraphQlLimits.exceeded(); });
    }
    @Bean MaxQueryComplexityInstrumentation queryComplexity() {
        var pages = Set.of("services", "providers", "requests", "reviews");
        return new MaxQueryComplexityInstrumentation(GraphQlLimits.MAX_COMPLEXITY, (environment, children) -> {
            if (!pages.contains(environment.getFieldDefinition().getName())) return 1 + children;
            Object page = environment.getArguments().get("page");
            Object size = page instanceof java.util.Map<?, ?> values ? values.get("size") : null;
            int amount = size instanceof Number number ? Math.clamp(number.intValue(), 1, 100) : 20;
            return 10 + amount * Math.max(1, children);
        }, info -> { throw GraphQlLimits.exceeded(); });
    }
}
