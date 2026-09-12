package com.crewpocket.helper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small read-only webpage fetcher for Notebook enrichment.
 *
 * No JS, cookies, authentication, file URLs, localhost, or private/link-local
 * destinations. Redirects are validated one hop at a time.
 */
final class SafeWebPageReader {
    private static final int CONNECT_TIMEOUT_MS = 7000;
    private static final int READ_TIMEOUT_MS = 9000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_BYTES = 700_000;
    private static final int MAX_TEXT_CHARS = 30_000;

    private SafeWebPageReader() {}

    static JSONObject read(String rawUrl) {
        JSONObject out = new JSONObject();
        try {
            URL url = normalizeAndValidate(rawUrl);
            String original = url.toString();

            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                HttpURLConnection connection =
                        (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod("GET");
                connection.setRequestProperty(
                        "User-Agent",
                        "CrewHelper/1.0 (+local notebook reader)");
                connection.setRequestProperty(
                        "Accept",
                        "text/html,text/plain;q=0.9,*/*;q=0.1");

                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    if (location == null || location.trim().isEmpty()) {
                        return error("WEB_REDIRECT_WITHOUT_LOCATION");
                    }
                    if (redirect >= MAX_REDIRECTS) {
                        return error("WEB_TOO_MANY_REDIRECTS");
                    }
                    url = normalizeAndValidate(
                            new URL(url, location).toString());
                    continue;
                }

                if (code < 200 || code >= 300) {
                    connection.disconnect();
                    return error("WEB_HTTP_" + code);
                }

                String contentType = connection.getContentType();
                String lowerType = contentType == null
                        ? ""
                        : contentType.toLowerCase(Locale.ROOT);
                if (!lowerType.isEmpty()
                        && !lowerType.contains("text/html")
                        && !lowerType.contains("text/plain")
                        && !lowerType.contains("application/xhtml")) {
                    connection.disconnect();
                    return error("WEB_UNSUPPORTED_CONTENT_TYPE");
                }

                InputStream input = connection.getInputStream();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int n;
                while ((n = input.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        input.close();
                        connection.disconnect();
                        return error("WEB_PAGE_TOO_LARGE");
                    }
                    bytes.write(buffer, 0, n);
                }
                input.close();

                Charset charset = charsetFromContentType(contentType);
                String body = new String(bytes.toByteArray(), charset);
                String title = extractTitle(body);
                String text = lowerType.contains("text/plain")
                        ? body
                        : htmlToText(body);

                if (text.length() > MAX_TEXT_CHARS) {
                    text = text.substring(0, MAX_TEXT_CHARS);
                }

                out.put("success", true);
                out.put("requestedUrl", original);
                out.put("finalUrl", url.toString());
                out.put("title", title);
                out.put("text", text);
                out.put("truncated", text.length() >= MAX_TEXT_CHARS);
                connection.disconnect();
                return out;
            }
        } catch (SecurityException blocked) {
            return error(
                    blocked.getMessage() == null
                            ? "WEB_URL_BLOCKED"
                            : blocked.getMessage());
        } catch (Exception error) {
            return error(
                    "WEB_READ_FAILED: "
                            + (error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage()));
        }
        return error("WEB_READ_FAILED");
    }

    private static URL normalizeAndValidate(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) {
            throw new SecurityException("WEB_URL_REQUIRED");
        }

        String value = raw.trim();
        URI uri = new URI(value);
        String scheme = uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);

        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new SecurityException("WEB_SCHEME_BLOCKED");
        }
        if (uri.getUserInfo() != null) {
            throw new SecurityException("WEB_USERINFO_BLOCKED");
        }

        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new SecurityException("WEB_HOST_REQUIRED");
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalizedHost)
                || normalizedHost.endsWith(".localhost")
                || normalizedHost.endsWith(".local")) {
            throw new SecurityException("WEB_LOCAL_HOST_BLOCKED");
        }

        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) {
            throw new SecurityException("WEB_DNS_EMPTY");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new SecurityException("WEB_PRIVATE_ADDRESS_BLOCKED");
            }
        }

        return uri.toURL();
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address == null) return true;
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            int a = bytes[0] & 0xff;
            int b = bytes[1] & 0xff;

            // 0/8, carrier-grade NAT 100.64/10, benchmarking 198.18/15,
            // documentation/reserved ranges are not valid Notebook fetch targets.
            if (a == 0) return true;
            if (a == 100 && b >= 64 && b <= 127) return true;
            if (a == 198 && (b == 18 || b == 19)) return true;
            if (a >= 224) return true;
        }

        if (address instanceof Inet6Address && bytes.length == 16) {
            int first = bytes[0] & 0xff;
            // fc00::/7 unique-local.
            if ((first & 0xfe) == 0xfc) return true;
        }

        return false;
    }

    private static Charset charsetFromContentType(String contentType) {
        if (contentType != null) {
            Matcher matcher = Pattern.compile(
                    "charset\\s*=\\s*['\\\"]?([^;'\\\"\\s]+)",
                    Pattern.CASE_INSENSITIVE)
                    .matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1).trim());
                } catch (Exception ignored) {}
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String extractTitle(String html) {
        if (html == null) return "";
        Matcher matcher = Pattern.compile(
                "(?is)<title[^>]*>(.*?)</title>")
                .matcher(html);
        if (!matcher.find()) return "";
        return decodeEntities(
                matcher.group(1).replaceAll("(?is)<[^>]+>", " "))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String htmlToText(String html) {
        if (html == null) return "";
        String cleaned = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("(?is)<svg[^>]*>.*?</svg>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("(?i)</div\\s*>", "\n")
                .replaceAll("(?i)</li\\s*>", "\n")
                .replaceAll("(?is)<[^>]+>", " ");

        cleaned = decodeEntities(cleaned)
                .replace("\r", "")
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n")
                .trim();
        return cleaned;
    }

    private static String decodeEntities(String value) {
        if (value == null) return "";
        return value
                .replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    private static JSONObject error(String code) {
        JSONObject out = new JSONObject();
        try {
            out.put("success", false);
            out.put("error", code);
        } catch (Exception ignored) {}
        return out;
    }
}
