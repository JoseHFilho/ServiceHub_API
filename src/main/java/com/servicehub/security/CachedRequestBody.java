package com.servicehub.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class CachedRequestBody extends HttpServletRequestWrapper {
    private final byte[] body;
    CachedRequestBody(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
    byte[] bytes() { return body; }
    @Override public int getContentLength() { return body.length; }
    @Override public long getContentLengthLong() { return body.length; }
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    @Override public ServletInputStream getInputStream() {
        var input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) {
                try { if (!isFinished()) listener.onDataAvailable(); if (isFinished()) listener.onAllDataRead(); }
                catch (IOException exception) { listener.onError(exception); }
            }
        };
    }
}
