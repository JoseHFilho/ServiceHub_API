package com.servicehub.graphql;

import graphql.*;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.language.*;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.idl.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.Tag("unit")
class GraphQlLimitsAndScalarsTest {
    private static final String SDL = """
            input PageInput { size:Int! = 20 }
            type Node { value:String a:Int b:Int c:Int d:Int e:Int child:Node }
            type ResultPage { content:[Node] }
            type Query { node:Node services(page:PageInput):ResultPage }
            type Mutation { create:String }
            """;
    private final AtomicInteger fetches = new AtomicInteger();

    private GraphQL graphQl() {
        var node = new HashMap<String,Object>(Map.of("value","ok","a",1,"b",2,"c",3,"d",4,"e",5));
        node.put("child",node);
        var runtime = RuntimeWiring.newRuntimeWiring()
                .type("Query", wiring -> wiring.dataFetcher("node", environment -> { fetches.incrementAndGet(); return node; })
                        .dataFetcher("services", environment -> { fetches.incrementAndGet(); return Map.of("content",List.of(node)); }))
                .type("Mutation", wiring -> wiring.dataFetcher("create", environment -> { fetches.incrementAndGet(); return "created"; }))
                .build();
        var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(SDL),runtime);
        var configuration = new GraphQlConfiguration();
        var cache = new HashMap<String,graphql.execution.preparsed.PreparsedDocumentEntry>();
        return GraphQL.newGraphQL(schema)
                .instrumentation(new ChainedInstrumentation(List.of(new GraphQlLimits(),configuration.queryDepth(),configuration.queryComplexity())))
                .preparsedDocumentProvider((input, parse) -> CompletableFuture.completedFuture(cache.computeIfAbsent(input.getQuery(), query -> parse.apply(input))))
                .build();
    }

    @Test
    void acceptsNormalQueriesAndRejectsExcessiveDepthBeforeFetching() {
        var graph = graphQl();
        assertThat(graph.execute("{node{child{value}}}").getErrors()).isEmpty();
        fetches.set(0);
        String deep = "{node{" + "child{".repeat(10) + "value" + "}".repeat(10) + "}}";
        limited(graph.execute(deep)); assertThat(fetches.get()).isZero();
    }

    @Test
    void weightsPageSizeFromVariablesBeforeAnyDatabaseCall() {
        var graph = graphQl();
        String query = """
                query($size:Int!){
                 a:services(page:{size:$size}){content{value a b c d e}}
                 b:services(page:{size:$size}){content{value a b c d e}}
                 c:services(page:{size:$size}){content{value a b c d e}}
                }
                """;
        limited(graph.execute(ExecutionInput.newExecutionInput(query).variables(Map.of("size",100)).build()));
        assertThat(fetches.get()).isZero();
        assertThat(graph.execute(ExecutionInput.newExecutionInput(query).variables(Map.of("size",1)).build()).getErrors()).isEmpty();
        assertThat(fetches.get()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ a:node{value} b:node{value} c:node{value} d:node{value} e:node{value} f:node{value} g:node{value} h:node{value} i:node{value} j:node{value} k:node{value} }",
            "{...A ...B} fragment A on Query{a:node{value} b:node{value} c:node{value} d:node{value} e:node{value} f:node{value}} fragment B on Query{g:node{value} h:node{value} i:node{value} j:node{value} k:node{value}}",
            "mutation{a:create b:create}"
    })
    void rejectsAliasesAndWriteBatchingEvenOnCachedDocuments(String document) {
        var graph = graphQl();
        limited(graph.execute(document)); limited(graph.execute(document));
        assertThat(fetches.get()).isZero();
    }

    @Test
    void rejectsMultipleOperationsAndLargeDocumentsBeforeFetching() {
        var graph = graphQl();
        limited(graph.execute(ExecutionInput.newExecutionInput("query A{node{value}} query B{node{value}}").operationName("A").build()));
        limited(graph.execute("{node{value}}" + " ".repeat(GraphQlLimits.MAX_QUERY_CHARACTERS)));
        assertThat(fetches.get()).isZero();
    }

    @Test
    void countsExpandedSelectionsEvenWhenGraphQlWouldMergeRepeatedFields() {
        var graph = graphQl();
        limited(graph.execute("{node{" + "value ".repeat(201) + "}}"));
        assertThat(fetches.get()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"150.00", "0.01", "-1.00", "99999999.99"})
    void preservesDecimalTextExactly(String value) {
        var coercing = GraphQlScalars.decimal().getCoercing();
        var parsed = coercing.parseValue(value, GraphQLContext.getDefault(), Locale.ROOT);
        assertThat(parsed).isEqualTo(new BigDecimal(value));
        assertThat(coercing.serialize(parsed, GraphQLContext.getDefault(), Locale.ROOT)).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "1E1000000000", "1,20", "", "<script>"})
    void rejectsUnsafeDecimalFormatsWithoutEchoingTheValue(String input) {
        assertThatThrownBy(() -> GraphQlScalars.decimal().getCoercing().parseValue(input,GraphQLContext.getDefault(),Locale.ROOT))
                .isInstanceOf(CoercingParseValueException.class).hasMessage("Formato inválido para Decimal. Use uma string.");
    }

    @Test
    void requiresStringDecimalsAndRejectsInvalidLiterals() {
        var coercing = GraphQlScalars.decimal().getCoercing();
        assertThatThrownBy(() -> coercing.parseValue(1.5, GraphQLContext.getDefault(), Locale.ROOT)).isInstanceOf(CoercingParseValueException.class);
        assertThatThrownBy(() -> coercing.parseLiteral(IntValue.of(1), graphql.execution.CoercedVariables.emptyVariables(), GraphQLContext.getDefault(), Locale.ROOT))
                .isInstanceOf(CoercingParseLiteralException.class);
        assertThat(coercing.parseLiteral(StringValue.of("150.00"),graphql.execution.CoercedVariables.emptyVariables(),GraphQLContext.getDefault(),Locale.ROOT))
                .isEqualTo(new BigDecimal("150.00"));
    }

    @Test
    void localDateTimeUsesIsoLocalFormatAndLongCountsRetainTheirRange() {
        var scalar = GraphQlScalars.localDateTime().getCoercing();
        String value = "2027-01-10T09:00:00";
        assertThat(scalar.parseValue(value,GraphQLContext.getDefault(),Locale.ROOT)).isEqualTo(LocalDateTime.parse(value));
        assertThatThrownBy(() -> scalar.parseValue("2027-13-99T99:99:99",GraphQLContext.getDefault(),Locale.ROOT))
                .isInstanceOf(CoercingParseValueException.class);
        assertThat(GraphQlScalars.longInteger().getCoercing().serialize(Long.MAX_VALUE,GraphQLContext.getDefault(),Locale.ROOT)).isEqualTo(Long.MAX_VALUE);
    }

    private void limited(ExecutionResult result) {
        assertThat(result.getErrors()).isNotEmpty().anySatisfy(error ->
                assertThat(error.getExtensions()).containsEntry("code","QUERY_LIMIT_EXCEEDED"));
    }
}
