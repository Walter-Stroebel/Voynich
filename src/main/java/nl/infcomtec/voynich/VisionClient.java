/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Talks to the {@code mcp-service-catalog} sibling project's vision pipeline
 * running on predator (see {@code CLAUDE.md}'s "Vision Pipeline (MCP)"
 * section) — the same {@code look_at_image} tool a Claude Code session
 * reaches via its own MCP client, called here directly over plain HTTP
 * instead, since this app is not an MCP client and doesn't need to become
 * one just to make two calls. MCP stays the wire *contract* only (a stable,
 * self-describing "here are the tools and their arguments" definition that
 * the sibling project can extend with more tools for free) — image bytes
 * never travel through it: they go over a separate plain-HTTP file upload
 * first, exactly like the workaround MCP's own protocol lacks for "client
 * has big data" and every non-file-aware MCP client (this one included) has
 * to build for itself.
 * <p>
 * Two calls: {@link #uploadFile} does a plain {@code PUT} to the file
 * service and gets back a {@code file_id}; {@link #askAboutImage} then
 * {@code POST}s a JSON-RPC 2.0 {@code tools/call} envelope at
 * {@code /mcp} naming {@code look_at_image} with that {@code file_id}. The
 * MCP transport is stateless per call for this server (confirmed live
 * 2026-08-14 — no session handshake needed before {@code tools/call}), so a
 * fresh request each time is fine; no connection/session state is kept
 * between calls on this client either.
 * <p>
 * The response is double-JSON: the MCP envelope's {@code result.content[0].text}
 * is itself a JSON string (not a nested object) holding an OpenAI-style chat
 * completion, and the actual model answer is
 * {@code .choices[0].message.content} inside <i>that</i>. Both layers are
 * unwrapped here so every caller (GUI and CLI alike) just gets the answer
 * text.
 */
public class VisionClient {

    private final String host;
    private final int filePort;
    private final int mcpPort;
    private final HttpClient http;

    public VisionClient(Config cfg) {
        this.host = cfg.visionHost;
        this.filePort = cfg.visionFilePort;
        this.mcpPort = cfg.visionMcpPort;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Uploads raw image bytes via {@code PUT} to the file service.
     *
     * @param imageBytes the raw file bytes (whatever {@code imageExt} says they are).
     * @return the {@code file_id} to pass to {@link #askAboutImage}.
     */
    public String uploadFile(byte[] imageBytes) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + filePort + "/files"))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(imageBytes))
                .timeout(Duration.ofMinutes(2))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (200 != resp.statusCode()) {
            throw new IOException("Upload failed: HTTP " + resp.statusCode() + " " + resp.body());
        }
        JsonNode node = JSON.getMapper().readTree(resp.body());
        JsonNode fileId = node.get("file_id");
        if (null == fileId) {
            throw new IOException("Upload response had no file_id: " + resp.body());
        }
        return fileId.asText();
    }

    /**
     * Asks the vision model a free-text question about a previously uploaded
     * image, via the {@code look_at_image} MCP tool.
     *
     * @param fileId as returned by {@link #uploadFile}.
     * @param imageExt file extension without the dot, e.g. "png".
     * @param question free-text question for the model.
     * @return the model's answer text.
     */
    public String askAboutImage(String fileId, String imageExt, String question)
            throws IOException, InterruptedException {
        String argsJson = String.format(
                "{\"file_id\":%s,\"image_ext\":%s,\"question\":%s}",
                JSON.writeValueAsString(fileId), JSON.writeValueAsString(imageExt), JSON.writeValueAsString(question));
        String rpcBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"look_at_image\",\"arguments\":" + argsJson + "}}";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + mcpPort + "/mcp"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(rpcBody))
                .timeout(Duration.ofMinutes(3))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (200 != resp.statusCode()) {
            throw new IOException("look_at_image failed: HTTP " + resp.statusCode() + " " + resp.body());
        }

        JsonNode rpc = JSON.getMapper().readTree(resp.body());
        JsonNode error = rpc.get("error");
        if (null != error) {
            throw new IOException("look_at_image error: " + error.toString());
        }
        JsonNode contentArr = rpc.path("result").path("content");
        if (!contentArr.isArray() || 0 == contentArr.size()) {
            throw new IOException("look_at_image returned no content: " + resp.body());
        }
        String innerJson = contentArr.get(0).path("text").asText();
        JsonNode inner = JSON.getMapper().readTree(innerJson);
        JsonNode choices = inner.path("choices");
        if (!choices.isArray() || 0 == choices.size()) {
            throw new IOException("look_at_image inner response had no choices: " + innerJson);
        }
        return choices.get(0).path("message").path("content").asText();
    }
}
