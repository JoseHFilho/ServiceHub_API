package com.servicehub.graphql;

import graphql.ExecutionResult;
import graphql.GraphqlErrorBuilder;
import graphql.execution.AbortExecutionException;
import graphql.execution.instrumentation.*;
import graphql.execution.instrumentation.parameters.*;
import graphql.language.*;
import java.util.Map;
import java.util.List;
import org.springframework.graphql.execution.ErrorType;

/** Executed for every operation, including documents served from a parsed-document cache. */
public final class GraphQlLimits extends SimplePerformantInstrumentation {
    public static final int MAX_QUERY_CHARACTERS = 16_384;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_COMPLEXITY = 2_000;
    public static final int MAX_ALIASES = 10;
    public static final int MAX_ROOT_FIELDS = 10;
    public static final int MAX_FIELDS = 200;

    static AbortExecutionException exceeded() {
        var error = GraphqlErrorBuilder.newError().errorType(ErrorType.BAD_REQUEST)
                .message("A consulta excede os limites GraphQL permitidos.")
                .extensions(Map.of("code", "QUERY_LIMIT_EXCEEDED")).build();
        return new AbortExecutionException(List.of(error));
    }

    @Override
    public InstrumentationContext<ExecutionResult> beginExecution(InstrumentationExecutionParameters parameters, InstrumentationState state) {
        if (parameters.getQuery().length() > MAX_QUERY_CHARACTERS) throw exceeded();
        return null;
    }
    @Override
    public InstrumentationContext<ExecutionResult> beginExecuteOperation(InstrumentationExecuteOperationParameters parameters, InstrumentationState state) {
        var context = parameters.getExecutionContext();
        if (context.getDocument().getDefinitionsOfType(OperationDefinition.class).size() != 1) throw exceeded();
        var operation = context.getOperationDefinition();
        int maximumRoots = operation.getOperation() == OperationDefinition.Operation.MUTATION ? 1 : MAX_ROOT_FIELDS;
        var counts = new int[3]; // total fields, aliases, root fields
        visit(operation.getSelectionSet(), context.getFragmentsByName(), 0, counts, maximumRoots);
        return null;
    }
    private void visit(SelectionSet selection, Map<String, FragmentDefinition> fragments, int depth, int[] counts, int roots) {
        if (selection == null) return;
        if (depth > MAX_DEPTH) throw exceeded();
        for (Selection<?> node : selection.getSelections()) {
            if (++counts[0] > MAX_FIELDS) throw exceeded();
            if (node instanceof Field field) {
                if ((field.getAlias() != null && ++counts[1] > MAX_ALIASES)
                        || (depth == 0 && ++counts[2] > roots)) throw exceeded();
                visit(field.getSelectionSet(), fragments, depth + 1, counts, roots);
            } else if (node instanceof InlineFragment inline) {
                visit(inline.getSelectionSet(), fragments, depth, counts, roots);
            } else if (node instanceof FragmentSpread spread) {
                // Fragment cycles are rejected by GraphQL validation before this callback.
                visit(fragments.get(spread.getName()).getSelectionSet(), fragments, depth, counts, roots);
            }
        }
    }
}
