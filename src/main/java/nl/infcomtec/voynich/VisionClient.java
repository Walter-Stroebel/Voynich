/*
 * Copyright (c) 2026 by Walter Stroebel and InfComTec.
 */
package nl.infcomtec.voynich;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.imageio.ImageIO;

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

    /**
     * Max dimension (either axis) sent to the vision pipeline. This is not a
     * tunable knob — it's a floor under what the pipeline can actually
     * accept, for reasons entirely outside this app's control: llama.cpp's
     * Gemma image preprocessing clamps to a fixed, much smaller pixel-count
     * range before tiling, and the vision encoder needs an entire image's
     * tokens to fit in one ubatch (non-causal attention). A 93MB,
     * 7925x7268px Voynich foldout reliably crashed {@code look_at_image}
     * with an {@code IOException} at full resolution — confirmed both
     * before and after predator's LM Studio-to-llama.cpp migration, so it's
     * a real ceiling in the model/server, not a wrapper bug. See
     * {@code memory/project_vision_resolution_floor_finding.md}. Every
     * upload through this class is downscaled to this size unconditionally
     * — "vision is not huge-capable" is a standing constraint of the
     * pipeline, not a per-call decision a caller should have to remember to
     * make.
     */
    public static final int MAX_DIMENSION = 2048;

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
     * Decodes {@code imageBytes}, downscales it to {@link #MAX_DIMENSION} on
     * its longer axis if it's larger (a no-op resize below that size — never
     * upscales), re-encodes as PNG, and uploads via {@link #uploadFile}. The
     * normal entry point for every real caller; {@link #uploadFile} itself
     * stays available for anything that's already guaranteed small (or
     * already downscaled) and wants to skip the decode/re-encode round trip.
     *
     * @param imageBytes the raw source image bytes, any format {@link ImageIO} reads.
     * @return the {@code file_id} to pass to {@link #askAboutImage}.
     */
    public String uploadImageDownscaled(byte[] imageBytes) throws IOException, InterruptedException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(imageBytes));
        if (null == src) {
            throw new IOException("Could not decode image (unsupported format or corrupt data)");
        }
        int w = src.getWidth(), h = src.getHeight();
        if (w <= MAX_DIMENSION && h <= MAX_DIMENSION) {
            return uploadFile(imageBytes);
        }
        double scale = MAX_DIMENSION / (double) Math.max(w, h);
        int scaledW = Math.max(1, (int) Math.round(w * scale));
        int scaledH = Math.max(1, (int) Math.round(h * scale));
        BufferedImage scaled = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_RGB);
        scaled.getGraphics().drawImage(src.getScaledInstance(scaledW, scaledH, Image.SCALE_SMOOTH), 0, 0, null);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", buf);
        return uploadFile(buf.toByteArray());
    }

    /**
     * Uploads raw image bytes via {@code PUT} to the file service, exactly
     * as given — no size check or downscaling. Prefer
     * {@link #uploadImageDownscaled} unless the caller already knows the
     * image is small.
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
        return stripCodeFence(choices.get(0).path("message").path("content").asText());
    }

    /**
     * The model routinely wraps a requested-JSON answer in a markdown code
     * fence (<code>```json ... ```</code> or bare <code>``` ... ```</code>)
     * despite being asked for JSON only, and does so inconsistently — some
     * answers in the same batch have it, some don't. A caller that parses
     * the answer as JSON (a triage script, say) shouldn't have to
     * special-case that; stripped here, once, for every caller, rather than
     * leaving it as a footgun every prompt-writer has to remember.
     */
    private static String stripCodeFence(String text) {
        String trimmed = text.strip();
        if (!trimmed.startsWith("```") || !trimmed.endsWith("```") || trimmed.length() < 6) {
            return text;
        }
        String inner = trimmed.substring(3, trimmed.length() - 3);
        int firstNewline = inner.indexOf('\n');
        if (firstNewline >= 0 && !inner.substring(0, firstNewline).isBlank()
                && !inner.substring(0, firstNewline).contains("{")) {
            // First line is a language tag (e.g. "json"), not content — drop it.
            inner = inner.substring(firstNewline + 1);
        }
        return inner.strip();
    }
}
