package app.radiance.api;

import app.radiance.live.LivePlay;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/stream} (SPEC §7): live play's events as server-sent events, one named event per
 * {@link LivePlay.LiveEvent}. A comment every 20 seconds keeps proxies from closing a quiet stream.
 */
@ConditionalOnWebApplication
@RestController
class StreamHub {

  private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
  private final ScheduledExecutorService pings = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());

  StreamHub(LivePlay live) {
    live.addListener(this::send);
    pings.scheduleAtFixedRate(this::ping, 20, 20, TimeUnit.SECONDS);
  }

  @GetMapping(path = "/api/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter stream() {
    var emitter = new SseEmitter(0L);
    emitter.onCompletion(() -> emitters.remove(emitter));
    emitter.onTimeout(() -> emitters.remove(emitter));
    emitter.onError(e -> emitters.remove(emitter));
    emitters.add(emitter);
    try {
      emitter.send(SseEmitter.event().name("hello").data(java.util.Map.of("ok", true)));
    } catch (IOException e) {
      emitters.remove(emitter);
    }
    return emitter;
  }

  int subscribers() {
    return emitters.size();
  }

  private void send(LivePlay.LiveEvent e) {
    for (var emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name(e.name()).data(e.data(), MediaType.APPLICATION_JSON));
      } catch (IOException | IllegalStateException ex) {
        emitters.remove(emitter);
      }
    }
  }

  private void ping() {
    for (var emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().comment("ping"));
      } catch (IOException | IllegalStateException ex) {
        emitters.remove(emitter);
      }
    }
  }

  @jakarta.annotation.PreDestroy
  void close() {
    pings.shutdownNow();
    emitters.forEach(SseEmitter::complete);
  }

  @Component
  @ConditionalOnWebApplication
  static final class Touch implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    private final LivePlay live;

    Touch(LivePlay live) {
      this.live = live;
    }

    /** The first request of a session airs everything due before anything is read (SPEC §5.1, §7). */
    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
      registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
        @Override
        public boolean preHandle(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res,
            Object handler) {
          try {
            live.touch();
          } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(StreamHub.class).warn("Session touch failed", e);
          }
          return true;
        }
      }).addPathPatterns("/api/**").excludePathPatterns("/api/auth/**", "/api/me");
    }
  }
}
