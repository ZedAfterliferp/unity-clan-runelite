package com.unityclan.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** HTTP adapter using RuneLite's injected OkHttpClient. */
public final class ClanHttpConnection {
    private static volatile OkHttpClient sharedClient;
    private final String url;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String method = "GET";
    private int connectTimeout = 10000;
    private int readTimeout = 15000;
    private ByteArrayOutputStream requestBytes;
    private int responseCode;
    private byte[] responseBytes;
    private Map<String, String> responseHeaders;
    private boolean executed;

    private ClanHttpConnection(String url) { this.url = url; }
    public static void initialize(OkHttpClient client) { sharedClient = java.util.Objects.requireNonNull(client); }
    public static ClanHttpConnection open(String url) { return new ClanHttpConnection(url); }
    public void setRequestMethod(String method) { this.method = method; }
    public void setRequestProperty(String key, String value) { headers.put(key, value); }
    public void setConnectTimeout(int milliseconds) { connectTimeout = milliseconds; }
    public void setReadTimeout(int milliseconds) { readTimeout = milliseconds; }
    public void setDoOutput(boolean enabled) { if (enabled && requestBytes == null) requestBytes = new ByteArrayOutputStream(); }
    public OutputStream getOutputStream() {
        if (executed) throw new IllegalStateException("Request already executed");
        if (requestBytes == null) requestBytes = new ByteArrayOutputStream();
        return requestBytes;
    }
    private synchronized void execute() throws IOException {
        if (executed) return;
        OkHttpClient client = sharedClient;
        if (client == null) throw new IOException("RuneLite HTTP client is not initialized");
        Request.Builder request = new Request.Builder().url(url);
        for (Map.Entry<String, String> header : headers.entrySet()) request.header(header.getKey(), header.getValue());
        if (!"GET".equals(method)) {
            byte[] bytes = requestBytes == null ? new byte[0] : requestBytes.toByteArray();
            request.method(method, RequestBody.create(MediaType.parse("application/json; charset=utf-8"), bytes));
        }
        OkHttpClient timed = client.newBuilder()
                .connectTimeout(connectTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeout, TimeUnit.MILLISECONDS).build();
        try (Response response = timed.newCall(request.build()).execute()) {
            responseCode = response.code();
            responseHeaders = new LinkedHashMap<>();
            for (String name : response.headers().names()) responseHeaders.put(name.toLowerCase(java.util.Locale.ROOT), response.header(name));
            if (response.body() == null) responseBytes = new byte[0];
            else {
                try (InputStream in = response.body().byteStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                    byte[] block = new byte[8192]; int count;
                    while ((count = in.read(block)) != -1) {
                        if (buffer.size() + count > 4 * 1024 * 1024) throw new IOException("HTTP response exceeds 4 MB");
                        buffer.write(block, 0, count);
                    }
                    responseBytes = buffer.toByteArray();
                }
            }
            executed = true;
        }
    }
    public int getResponseCode() throws IOException { execute(); return responseCode; }
    public String getHeaderField(String key) throws IOException { execute(); return responseHeaders.get(key.toLowerCase(java.util.Locale.ROOT)); }
    public InputStream getInputStream() throws IOException {
        execute();
        if (responseCode >= 400) throw new IOException("HTTP " + responseCode);
        return new ByteArrayInputStream(responseBytes);
    }
    public InputStream getErrorStream() throws IOException {
        execute(); return responseCode >= 400 ? new ByteArrayInputStream(responseBytes) : null;
    }
    public void disconnect() { /* The response body is already closed. */ }
}
