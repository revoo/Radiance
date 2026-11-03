package app.radiance.api;

import app.radiance.live.LivePlay;
import app.radiance.media.FakeImageGenerator;
import app.radiance.media.MediaRepository;
import app.radiance.media.MediaStore;
import app.radiance.media.Uploads;
import app.radiance.persistence.Sql;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Photos (SPEC §5.13, ADR 0021): the browser sends both sizes here, and they're served back through the login. */
@RestController
@RequestMapping("/api/media")
@ConditionalOnWebApplication
class MediaController {

  record NewUpload(String mime, Long bytes, Integer width, Integer height) {}

  record Started(String mediaId, String putUrl, String thumbPutUrl) {}

  private final Uploads uploads;
  private final MediaStore store;
  private final Sql sql;
  private final LivePlay live;

  MediaController(Uploads uploads, MediaStore store, Sql sql, LivePlay live) {
    this.uploads = uploads;
    this.store = store;
    this.sql = sql;
    this.live = live;
  }

  @PostMapping("/uploads")
  ResponseEntity<Started> start(@RequestBody NewUpload body) {
    try {
      var row = uploads.start(body.mime(), body.bytes() == null ? 0 : body.bytes(), body.width(), body.height());
      String base = "/api/media/" + row.id() + "/upload?size=";
      return ResponseEntity.status(HttpStatus.CREATED).body(new Started(row.id(), base + "full", base + "thumb"));
    } catch (Uploads.Rejected e) {
      throw Problems.bad(e.getMessage());
    }
  }

  @PutMapping("/{id}/upload")
  ResponseEntity<Void> receive(@PathVariable String id, @RequestParam(defaultValue = "full") String size, HttpServletRequest request)
      throws IOException, InterruptedException {
    boolean thumb = "thumb".equals(size);
    int cap = thumb ? Uploads.MAX_THUMB : Uploads.MAX_FULL;
    if (request.getContentLengthLong() > cap) throw Problems.bad("That photo is too large");
    byte[] bytes = request.getInputStream().readNBytes(cap + 1);
    try {
      uploads.receive(id, thumb, bytes);
    } catch (Uploads.Rejected e) {
      throw Problems.bad(e.getMessage());
    }
    return ResponseEntity.noContent().build();
  }

  /** Both sizes are in. The vision appraisal runs here, so a post made with this photo knows what's in it. */
  @PostMapping("/{id}/complete")
  Views.Media complete(@PathVariable String id) throws IOException, InterruptedException {
    try {
      var ready = uploads.complete(id);
      var thumb = store.get(ready.thumbKey()).orElseThrow();
      var described = live.describePhoto(ready, thumb);
      return Reads.mediaView(described);
    } catch (Uploads.Rejected e) {
      throw Problems.bad(e.getMessage());
    }
  }

  @GetMapping("/{id}")
  ResponseEntity<byte[]> get(@PathVariable String id, @RequestParam(defaultValue = "full") String size)
      throws IOException, InterruptedException {
    var row = new MediaRepository(sql).byId(id).filter(m -> "READY".equals(m.status())).orElseThrow(() -> Problems.notFound("photo"));
    String key = "thumb".equals(size) && row.thumbKey() != null ? row.thumbKey() : row.storageKey();
    var bytes = store.get(key).orElseThrow(() -> Problems.notFound("photo"));
    var response = ResponseEntity.ok().contentType(MediaType.parseMediaType(row.mime()))
        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable());
    if (FakeImageGenerator.SVG.equals(row.mime())) {
      // only ever our own placeholder; if it's ever opened as a page, it can't do anything
      response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; sandbox");
    }
    return response.body(bytes);
  }
}
