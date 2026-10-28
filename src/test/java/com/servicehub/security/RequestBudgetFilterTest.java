package com.servicehub.security;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@Tag("unit")
class RequestBudgetFilterTest {
    private final RequestBudgetFilter filter = new RequestBudgetFilter(JsonMapper.builder().build(), 8192, 65536);
    @ParameterizedTest @ValueSource(strings={"/api/auth/login", "/api/users"})
    void preservesExactPublicBodiesWithinBudget(String route) throws Exception {
        var request = new MockHttpServletRequest("POST", route); request.setContent(new byte[8192]);
        var read = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> read.set(wrapped.getInputStream().readAllBytes().length));
        assertThat(read.get()).isEqualTo(8192);
    }
    @Test void rejectsDeclaredExcessBeforeReadingTheBody() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/users") {
            @Override public jakarta.servlet.ServletInputStream getInputStream() { throw new AssertionError("Body must not be read"); }
        };
        request.setContent(new byte[8193]); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (wrapped, result) -> { throw new AssertionError("Handler must not run"); });
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void boundsUnknownLengthUtf8BodiesWithoutForwardingThem() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/users") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        request.setContent("ç".repeat(5000).getBytes(StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (wrapped, result) -> { throw new AssertionError("Handler must not run"); });
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).doesNotContain("çç");
    }
    @Test void givesPrivateRestItsOwnLargerBudgetAndPreservesReaderAndLength() throws Exception {
        var request = new MockHttpServletRequest("PATCH", "/app/api/services/1"); request.setContextPath("/app");
        request.setContent("x".repeat(20000).getBytes(StandardCharsets.UTF_8));
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> {
            assertThat(wrapped.getContentLengthLong()).isEqualTo(20000);
            assertThat(wrapped.getReader().readLine()).hasSize(20000);
        });
    }
    @Test void leavesGraphQlToItsExistingIndependentFilter() throws Exception {
        var request = new MockHttpServletRequest("POST", "/graphql"); request.setContent(new byte[70000]);
        var read = new AtomicInteger();
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, response) -> read.set(wrapped.getInputStream().readAllBytes().length));
        assertThat(read.get()).isEqualTo(70000);
    }
    @Test void rejectsUnsafeConfigurationAtStartup() {
        assertThatThrownBy(() -> new RequestBudgetFilter(JsonMapper.builder().build(), 0, 65536)).isInstanceOf(IllegalStateException.class);
    }
}
