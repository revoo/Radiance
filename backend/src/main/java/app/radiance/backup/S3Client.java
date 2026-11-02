package app.radiance.backup;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/**
 * Just enough S3 for backups, on {@code java.net.http} (ADR 0019): SigV4-signed PUT, GET, HEAD, LIST and
 * DELETE in path style, which is what R2 speaks. No SDK. Plain Java: restore runs before Spring starts.
 */
public final class S3Client {

  public record Settings(URI endpoint, String bucket, String accessKey, String secretKey, String region) {}

  public record Entry(String key, long size) {}

  /** A storage request that failed, with the status and the start of what the store said. */
  public static final class S3Exception extends IOException {
    S3Exception(String what, int status, String body) {
      super(what + " answered " + status + (body == null || body.isBlank() ? "" : ": "
          + body.substring(0, Math.min(200, body.length()))));
    }
  }

  private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  private final Settings s;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

  public S3Client(Settings settings) {
    this.s = settings;
  }

  public void put(String key, Path file) throws IOException, InterruptedException {
    var req = request("PUT", key, Map.of(), sha256(file), HttpRequest.BodyPublishers.ofFile(file));
    var res = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() / 100 != 2) throw new S3Exception("PUT " + key, res.statusCode(), res.body());
  }

  /** The object's size, or empty if it isn't there. */
  public Optional<Long> head(String key) throws IOException, InterruptedException {
    var req = request("HEAD", key, Map.of(), EMPTY_SHA256, HttpRequest.BodyPublishers.noBody());
    var res = http.send(req, HttpResponse.BodyHandlers.discarding());
    if (res.statusCode() == 404) return Optional.empty();
    if (res.statusCode() / 100 != 2) throw new S3Exception("HEAD " + key, res.statusCode(), null);
    return res.headers().firstValueAsLong("Content-Length").stream().boxed().findFirst();
  }

  public void get(String key, Path target) throws IOException, InterruptedException {
    var req = request("GET", key, Map.of(), EMPTY_SHA256, HttpRequest.BodyPublishers.noBody());
    var res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream in = res.body()) {
      if (res.statusCode() / 100 != 2) {
        throw new S3Exception("GET " + key, res.statusCode(), new String(in.readNBytes(400), StandardCharsets.UTF_8));
      }
      Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }

  public void delete(String key) throws IOException, InterruptedException {
    var req = request("DELETE", key, Map.of(), EMPTY_SHA256, HttpRequest.BodyPublishers.noBody());
    var res = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (res.statusCode() / 100 != 2) throw new S3Exception("DELETE " + key, res.statusCode(), res.body());
  }

  public List<Entry> list(String prefix) throws IOException, InterruptedException {
    var out = new ArrayList<Entry>();
    String token = null;
    do {
      var query = new TreeMap<String, String>();
      query.put("list-type", "2");
      query.put("prefix", prefix);
      if (token != null) query.put("continuation-token", token);
      var req = request("GET", "", query, EMPTY_SHA256, HttpRequest.BodyPublishers.noBody());
      var res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
      if (res.statusCode() / 100 != 2) {
        throw new S3Exception("LIST " + prefix, res.statusCode(), new String(res.body(), StandardCharsets.UTF_8));
      }
      token = parseList(res.body(), out);
    } while (token != null);
    return out;
  }

  /** Adds the listing's objects to {@code out}; returns the continuation token, if it's truncated. */
  static String parseList(byte[] xml, List<Entry> out) throws IOException {
    try {
      var f = DocumentBuilderFactory.newInstance();
      f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      var doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
      var items = doc.getElementsByTagName("Contents");
      for (int i = 0; i < items.getLength(); i++) {
        var e = (Element) items.item(i);
        out.add(new Entry(text(e, "Key"), Long.parseLong(text(e, "Size"))));
      }
      var next = doc.getElementsByTagName("NextContinuationToken");
      return next.getLength() > 0 ? next.item(0).getTextContent() : null;
    } catch (org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException e) {
      throw new IOException("unreadable listing", e);
    }
  }

  private static String text(Element e, String tag) {
    return e.getElementsByTagName(tag).item(0).getTextContent();
  }

  private HttpRequest request(String method, String key, Map<String, String> query, String payloadHash,
      HttpRequest.BodyPublisher body) {
    String path = "/" + s.bucket() + (key.isEmpty() ? "/" : "/" + key);
    String canonicalUri = encodePath(path);
    String canonicalQuery = canonicalQuery(query);
    URI uri = URI.create(s.endpoint().toString().replaceAll("/$", "") + canonicalUri
        + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery));
    String amzDate = AMZ_DATE.format(Instant.now());
    var signed = new TreeMap<String, String>();
    signed.put("host", uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost());
    signed.put("x-amz-content-sha256", payloadHash);
    signed.put("x-amz-date", amzDate);
    String auth = authorization(method, canonicalUri, canonicalQuery, signed, payloadHash, amzDate, s.region(), s.accessKey(),
        s.secretKey());
    return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).header("x-amz-content-sha256", payloadHash)
        .header("x-amz-date", amzDate).header("Authorization", auth).method(method, body).build();
  }

  /** The SigV4 {@code Authorization} header value. {@code headers} are lower-case names, sorted. */
  static String authorization(String method, String canonicalUri, String canonicalQuery, TreeMap<String, String> headers,
      String payloadHash, String amzDate, String region, String accessKey, String secretKey) {
    var canonicalHeaders = new StringBuilder();
    headers.forEach((k, v) -> canonicalHeaders.append(k).append(':').append(v.strip()).append('\n'));
    String signedHeaders = String.join(";", headers.keySet());
    String canonical = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
        + payloadHash;
    String day = amzDate.substring(0, 8);
    String scope = day + "/" + region + "/s3/aws4_request";
    String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
    key = hmac(key, region);
    key = hmac(key, "s3");
    key = hmac(key, "aws4_request");
    return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ",SignedHeaders=" + signedHeaders + ",Signature="
        + hex(hmac(key, toSign));
  }

  static String canonicalQuery(Map<String, String> query) {
    var parts = new ArrayList<String>();
    new TreeMap<>(query).forEach((k, v) -> parts.add(encode(k) + "=" + encode(v)));
    return String.join("&", parts);
  }

  private static String encodePath(String path) {
    var parts = new ArrayList<String>();
    for (String p : path.split("/", -1)) parts.add(encode(p));
    return String.join("/", parts);
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
  }

  private static byte[] hmac(byte[] key, String data) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] sha256(byte[] data) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String sha256(Path file) throws IOException {
    try (InputStream in = Files.newInputStream(file)) {
      var md = MessageDigest.getInstance("SHA-256");
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
      return hex(md.digest());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String hex(byte[] b) {
    return HexFormat.of().formatHex(b);
  }
}
