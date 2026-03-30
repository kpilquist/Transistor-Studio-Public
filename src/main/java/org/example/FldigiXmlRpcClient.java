package org.example;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Minimal XML-RPC client tailored for FLDIGI's XML-RPC interface.
 *
 * No external dependencies; uses Java 11+ HttpClient and JAXP for XML.
 *
 * Default FLDIGI server: http://127.0.0.1:7362/RPC2
 */
public class FldigiXmlRpcClient {

    private final HttpClient http;
    private final URI endpoint;

    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> rxTask;
    private volatile int lastRxLength = 0;

    public FldigiXmlRpcClient() {
        this("127.0.0.1", 7362, "/RPC2");
    }

    public FldigiXmlRpcClient(String host, int port, String path) {
        Objects.requireNonNull(host, "host");
        if (path == null || path.isEmpty()) path = "/RPC2";
        this.endpoint = URI.create("http://" + host + ":" + port + path);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    // ======================= High-level helpers =======================

    public String getVersion() throws IOException {
        Object v = call("fldigi.version");
        return v != null ? v.toString() : null;
    }

    public String getStatus() throws IOException {
        Object v = call("main.get_status");
        return v != null ? v.toString() : null;
    }

    public String getModemName() throws IOException {
        Object v = call("modem.get_name");
        return v != null ? v.toString() : null;
    }

    public boolean setModemByName(String name) throws IOException {
        Object v = call("modem.set_by_name", name);
        return asBoolean(v);
    }

    public Double getRigFrequency() throws IOException {
        Object v = call("rig.get_frequency");
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v != null ? Double.parseDouble(v.toString()) : null; } catch (Exception e) { return null; }
    }

    public boolean setRigFrequency(double hz) throws IOException {
        Object v = call("rig.set_frequency", hz);
        return asBoolean(v);
    }

    public boolean ptt(boolean tx) throws IOException {
        Object v = call(tx ? "main.tx" : "main.rx");
        // Many FLDIGI methods return boolean true on success
        return v == null || asBoolean(v);
    }

    public boolean sendTxText(String text) throws IOException {
        if (text == null) return false;
        Object v = call("text.add_tx", text);
        return v == null || asBoolean(v);
    }

    public String getRxText() throws IOException {
        Object v = call("text.get_rx");
        return v != null ? v.toString() : "";
    }

    public boolean clearRx() throws IOException {
        Object v = call("text.clear_rx");
        return v == null || asBoolean(v);
    }

    /**
     * Returns only the newly appended portion of RX buffer since last call.
     */
    public String getNewRxText() throws IOException {
        String all = getRxText();
        if (all == null) all = "";
        if (lastRxLength <= 0) {
            lastRxLength = all.length();
            return "";
        }
        if (all.length() <= lastRxLength) {
            lastRxLength = all.length();
            return "";
        }
        String delta = all.substring(lastRxLength);
        lastRxLength = all.length();
        return delta;
    }

    /**
     * Start a lightweight polling task that checks for new RX text and notifies the consumer.
     * Safe to call multiple times; calling again will replace the listener/task.
     */
    public synchronized void startRxListener(Consumer<String> onNewText, int intervalMs) {
        stopRxListener();
        if (onNewText == null) return;
        if (intervalMs < 50) intervalMs = 200; // be gentle
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Fldigi-RX-Poller");
            t.setDaemon(true);
            return t;
        });
        lastRxLength = 0; // reset tracking
        rxTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                String delta = getNewRxText();
                if (delta != null && !delta.isEmpty()) {
                    onNewText.accept(delta);
                }
            } catch (IOException ignored) {
                // swallow to keep polling running
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void stopRxListener() {
        if (rxTask != null) {
            rxTask.cancel(true);
            rxTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    // ======================= Core XML-RPC call =======================

    public Object call(String methodName, Object... params) throws IOException {
        String xml = buildRequestXml(methodName, params);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "text/xml")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IOException("FLDIGI XML-RPC HTTP " + resp.statusCode());
            }
            return parseResponse(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    public CompletableFuture<Object> callAsync(String methodName, Object... params) {
        String xml = buildRequestXml(methodName, params);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "text/xml")
                .POST(HttpRequest.BodyPublishers.ofString(xml, StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    if (resp.statusCode() != 200) {
                        throw new RuntimeException("FLDIGI XML-RPC HTTP " + resp.statusCode());
                    }
                    try {
                        return parseResponse(resp.body());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private String buildRequestXml(String methodName, Object... params) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\"?>");
        sb.append("<methodCall>");
        sb.append("<methodName").append(">").append(escapeXml(methodName)).append("</methodName>");
        sb.append("<params>");
        if (params != null) {
            for (Object p : params) {
                sb.append("<param><value>");
                appendValueXml(sb, p);
                sb.append("</value></param>");
            }
        }
        sb.append("</params>");
        sb.append("</methodCall>");
        return sb.toString();
    }

    private void appendValueXml(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("<nil/>");
            return;
        }
        if (v instanceof Boolean) {
            sb.append("<boolean>").append(((Boolean) v) ? 1 : 0).append("</boolean>");
        } else if (v instanceof Integer || v instanceof Long) {
            sb.append("<int>").append(v.toString()).append("</int>");
        } else if (v instanceof Float || v instanceof Double) {
            sb.append("<double>").append(v.toString()).append("</double>");
        } else if (v instanceof byte[]) {
            String base64 = java.util.Base64.getEncoder().encodeToString((byte[]) v);
            sb.append("<base64>").append(base64).append("</base64>");
        } else if (v instanceof Object[]) {
            sb.append("<array><data>");
            for (Object item : (Object[]) v) {
                sb.append("<value>");
                appendValueXml(sb, item);
                sb.append("</value>");
            }
            sb.append("</data></array>");
        } else if (v instanceof List<?>) {
            sb.append("<array><data>");
            for (Object item : (List<?>) v) {
                sb.append("<value>");
                appendValueXml(sb, item);
                sb.append("</value>");
            }
            sb.append("</data></array>");
        } else {
            sb.append("<string>").append(escapeXml(v.toString())).append("</string>");
        }
    }

    private Object parseResponse(String xml) throws IOException {
        Document doc = parseXml(xml);
        Element root = doc.getDocumentElement();
        if (!"methodResponse".equals(root.getNodeName())) {
            throw new IOException("Invalid XML-RPC response root: " + root.getNodeName());
        }
        NodeList fault = root.getElementsByTagName("fault");
        if (fault.getLength() > 0) {
            Object val = parseValue(findFirstByTagName((Element) fault.item(0), "value"));
            throw new IOException("XML-RPC fault: " + val);
        }
        Element params = findFirstByTagName(root, "params");
        if (params == null) return null;
        Element param = findFirstByTagName(params, "param");
        if (param == null) return null;
        Element value = findFirstByTagName(param, "value");
        return parseValue(value);
    }

    private Document parseXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setIgnoringComments(true);
            f.setIgnoringElementContentWhitespace(true);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Failed to parse XML-RPC response", e);
        }
    }

    private static Element findFirstByTagName(Element parent, String tag) {
        NodeList nl = parent.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        return (Element) nl.item(0);
    }

    private Object parseValue(Element valueEl) {
        if (valueEl == null) return null;
        // The first child element under <value> indicates type
        for (Node n = valueEl.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = n.getNodeName();
            String text = textContentDeep(n);
            switch (name) {
                case "string":
                    return text;
                case "int":
                case "i4":
                    try { return Integer.parseInt(text.trim()); } catch (Exception e) { return 0; }
                case "double":
                    try { return Double.parseDouble(text.trim()); } catch (Exception e) { return 0.0; }
                case "boolean":
                    return ("1".equals(text.trim()) || "true".equalsIgnoreCase(text.trim()));
                case "base64":
                    try { return java.util.Base64.getDecoder().decode(text.trim()); } catch (Exception e) { return new byte[0]; }
                case "array":
                    return parseArray((Element) n);
                case "nil":
                    return null;
                default:
                    // fallback to raw text
                    return text;
            }
        }
        // If no child type, use value's own text
        return textContentDeep(valueEl);
    }

    private List<Object> parseArray(Element arrayEl) {
        List<Object> out = new ArrayList<>();
        Element data = findFirstByTagName(arrayEl, "data");
        if (data == null) return out;
        NodeList values = data.getElementsByTagName("value");
        for (int i = 0; i < values.getLength(); i++) {
            out.add(parseValue((Element) values.item(i)));
        }
        return out;
    }

    private static String textContentDeep(Node node) {
        StringBuilder sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString();
    }

    private static void collectText(Node node, StringBuilder sb) {
        if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
            sb.append(node.getNodeValue());
        }
        for (Node n = node.getFirstChild(); n != null; n = n.getNextSibling()) {
            collectText(n, sb);
        }
    }

    private static String escapeXml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean asBoolean(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return v != null && ("1".equals(v.toString()) || Boolean.parseBoolean(v.toString()));
    }
}
