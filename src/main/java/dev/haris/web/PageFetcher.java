package dev.haris.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Downloads one public web page and reduces it to readable text.
 *
 * <ul>
 *   <li>DNS answers are checked inside the resolver, and OkHttp connects to exactly the
 *       addresses that passed — a hostname cannot re-resolve to 192.168.x.x in between
 *       (DNS rebinding).</li>
 *   <li>Redirects are followed by hand, at most three, each target re-checked.</li>
 *   <li>Only HTML and plain text, at most 2 MB, 15 s in total; no cookies are kept or sent.</li>
 *   <li>Scripts, styles, forms and navigation are dropped; only visible text reaches the model.</li>
 * </ul>
 */
@Component
public class PageFetcher {

    public record Page(String url, String title, String text) {}

    /** A failure whose message Haris wrote itself, so it is safe to show the model as it is. */
    static final class FetchException extends IOException {
        FetchException(String message) {
            super(message);
        }
    }

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TEXT = 15_000;
    private static final int MAX_REDIRECTS = 3;

    /** Resolver that refuses any answer pointing into a private or local network. */
    static final Dns PUBLIC_ONLY = hostname -> {
        List<InetAddress> all = Arrays.asList(InetAddress.getAllByName(hostname));
        for (InetAddress a : all) {
            if (!NetGuard.isPublic(a)) {
                throw new UnknownHostException(hostname + " points into a private network; blocked");
            }
        }
        return all;
    };

    private final OkHttpClient http = new OkHttpClient.Builder()
            // No proxy: with one, the proxy would resolve names itself and bypass PUBLIC_ONLY.
            .proxy(java.net.Proxy.NO_PROXY)
            .dns(PUBLIC_ONLY)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(Duration.ofSeconds(6))
            .readTimeout(Duration.ofSeconds(10))
            .callTimeout(Duration.ofSeconds(15))
            .build();

    public Page fetch(String rawUrl) throws IOException, NetGuard.BlockedException {
        URI uri = NetGuard.checkUrl(rawUrl);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            Request request = new Request.Builder()
                    .url(uri.toString())
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Haris-reader")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9")
                    .header("Accept-Language", "en,ar;q=0.8")
                    .build();
            try (Response res = http.newCall(request).execute()) {
                if (res.isRedirect()) {
                    String location = res.header("Location");
                    if (location == null) {
                        throw new FetchException("Redirect without a target.");
                    }
                    uri = NetGuard.checkUrl(uri.resolve(location).toString());
                    continue;
                }
                if (!res.isSuccessful()) {
                    throw new FetchException("The site answered HTTP " + res.code() + ".");
                }
                ResponseBody body = res.body();
                MediaType type = body == null ? null : body.contentType();
                String subtype = type == null ? "" : (type.type() + "/" + type.subtype()).toLowerCase(Locale.ROOT);
                if (!subtype.equals("text/html") && !subtype.equals("application/xhtml+xml") && !subtype.equals("text/plain")) {
                    throw new FetchException("That link is not a web page (" + (subtype.isEmpty() ? "unknown type" : subtype) + ").");
                }
                byte[] bytes = readLimited(body.byteStream());
                Charset charset = type.charset(StandardCharsets.UTF_8);
                String content = new String(bytes, charset);
                return subtype.equals("text/plain")
                        ? new Page(uri.toString(), "", limit(content))
                        : readable(uri.toString(), content);
            }
        }
        throw new FetchException("Too many redirects.");
    }

    static Page readable(String url, String html) {
        Document doc = Jsoup.parse(html, url);
        doc.select("script, style, noscript, template, svg, canvas, iframe, form, nav, footer, header, aside, "
                + "[hidden], [aria-hidden=true], [style*=display:none], [style*=display: none]").remove();
        Element main = doc.selectFirst("article");
        if (main == null) main = doc.selectFirst("main");
        if (main == null) main = doc.body();
        String text = main == null ? "" : main.wholeText()
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n\\s*\\n+", "\n\n")
                .strip();
        return new Page(url, doc.title(), limit(text));
    }

    private static String limit(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "\n… (page truncated)";
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES) {
            return Arrays.copyOf(data, MAX_BYTES);   // read what fits; the rest is ignored
        }
        return data;
    }
}
