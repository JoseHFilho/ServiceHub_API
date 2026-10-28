package com.servicehub.graphql;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.Tag("unit")
class GraphQlBodyLimitFilterTest {
    @ParameterizedTest @ValueSource(ints={1,65536})
    void preservesTheExactBodyWithinTheByteLimit(int size) throws Exception {
        var request = new MockHttpServletRequest("POST","/graphql");
        request.setContent("x".repeat(size).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        var read = new AtomicInteger();
        new GraphQlBodyLimitFilter().doFilter(request,response,(wrapped,result) -> read.set(wrapped.getInputStream().readAllBytes().length));
        assertThat(response.getStatus()).isEqualTo(200); assertThat(read.get()).isEqualTo(size);
    }
    @Test
    void alsoBoundsChunkedBodiesAndCountsUtf8BytesInsteadOfCharacters() throws Exception {
        var request = new MockHttpServletRequest("POST","/graphql") {
            @Override public int getContentLength() { return -1; }
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent("ç".repeat(40000).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        new GraphQlBodyLimitFilter().doFilter(request,response,(wrapped,result) -> { throw new AssertionError("O corpo não deveria atingir o handler."); });
        assertThat(response.getStatus()).isEqualTo(413); assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
    }
    @Test
    void doesNotApplyGraphQlLimitsToRestRequests() throws Exception {
        var request = new MockHttpServletRequest("POST","/api/services"); request.setContent(new byte[70000]);
        var count = new AtomicInteger();
        new GraphQlBodyLimitFilter().doFilter(request,new MockHttpServletResponse(),(wrapped,result) -> count.set(wrapped.getInputStream().readAllBytes().length));
        assertThat(count.get()).isEqualTo(70000);
    }
}
