package com.servicehub.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.function.Function;

final class GraphQlScalars {
    private GraphQlScalars() { }

    static GraphQLScalarType decimal() {
        return text("Decimal", BigDecimal.class, text -> {
            if (!text.matches("-?[0-9]{1,32}(\\.[0-9]{1,16})?")) throw new IllegalArgumentException();
            return new BigDecimal(text);
        }, BigDecimal::toPlainString);
    }
    static GraphQLScalarType localDateTime() {
        return text("LocalDateTime", LocalDateTime.class, LocalDateTime::parse, LocalDateTime::toString);
    }
    private static <T> GraphQLScalarType text(String name, Class<T> type, Function<String, T> parse, Function<T, String> format) {
        return GraphQLScalarType.newScalar().name(name).coercing(new Coercing<T, String>() {
            private T convert(Object input) {
                if (!(input instanceof String value) || value.length() > 64) throw new IllegalArgumentException();
                return parse.apply(value);
            }
            @Override public String serialize(Object input, GraphQLContext context, Locale locale) {
                if (!type.isInstance(input)) throw new CoercingSerializeException("Valor interno indisponível.");
                return format.apply(type.cast(input));
            }
            @Override public T parseValue(Object input, GraphQLContext context, Locale locale) {
                try { return convert(input); }
                catch (RuntimeException invalid) { throw new CoercingParseValueException("Formato inválido para " + name + ". Use uma string."); }
            }
            @Override public T parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
                try { return convert(input instanceof StringValue value ? value.getValue() : null); }
                catch (RuntimeException invalid) { throw new CoercingParseLiteralException("Formato inválido para " + name + ". Use uma string."); }
            }
        }).build();
    }
    static GraphQLScalarType longInteger() {
        return GraphQLScalarType.newScalar().name("Long").coercing(new Coercing<Long, Long>() {
            @Override public Long serialize(Object input, GraphQLContext context, Locale locale) {
                if (input instanceof Long value) return value;
                throw new CoercingSerializeException("Contagem indisponível.");
            }
            @Override public Long parseValue(Object input, GraphQLContext context, Locale locale) {
                try {
                    if (input instanceof Long value) return value;
                    if (input instanceof Integer value) return value.longValue();
                    if (input instanceof String value && value.matches("-?[0-9]{1,19}")) return Long.parseLong(value);
                } catch (NumberFormatException invalid) { /* handled below without disclosing input */ }
                throw new CoercingParseValueException("Inteiro de 64 bits inválido.");
            }
            @Override public Long parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext context, Locale locale) {
                if (input instanceof graphql.language.IntValue value) {
                    try { return value.getValue().longValueExact(); } catch (ArithmeticException invalid) { /* handled below */ }
                }
                throw new CoercingParseLiteralException("Inteiro de 64 bits inválido.");
            }
        }).build();
    }
}
