package app.radiance.media;

import app.radiance.backup.R2Settings;
import app.radiance.kernel.WallClock;
import app.radiance.persistence.Sql;
import app.radiance.persistence.WriterLane;
import java.net.URI;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Media storage and generation (SPEC §5.13, ADR 0021). With no keys it still works: local files and placeholders. */
@Configuration(proxyBeanMethods = false)
class MediaConfig {

  private static final Logger log = LoggerFactory.getLogger(MediaConfig.class);

  @Bean
  MediaStore mediaStore(@Value("${radiance.media.store:local}") String store, @Value("${radiance.media.dir:data/media}") Path dir,
      @Value("${radiance.backup.r2.endpoint:}") String endpoint, @Value("${radiance.backup.r2.account-id:}") String account,
      @Value("${radiance.backup.r2.access-key-id:}") String key, @Value("${radiance.backup.r2.secret-access-key:}") String secret,
      @Value("${radiance.backup.r2.bucket:}") String bucket, @Value("${radiance.backup.r2.region:auto}") String region) {
    var local = new LocalFileMediaStore(dir);
    if (!"r2".equals(store)) return local;
    var client = new R2Settings(endpoint, account, key, secret, bucket, region).client();
    if (client.isEmpty()) {
      log.warn("radiance.media.store is r2, but the R2 keys are missing: media stays on this disk only");
      return local;
    }
    return new R2BackedMediaStore(local, client.get());
  }

  @Bean
  ImageGenerator imageGenerator(@Value("${radiance.media.generator:fake}") String generator,
      @Value("${radiance.media.image-model:@cf/black-forest-labs/flux-2-klein-4b}") String model,
      @Value("${radiance.backup.r2.account-id:}") String account, @Value("${radiance.media.api-token:}") String token,
      @Value("${radiance.media.api-base:https://api.cloudflare.com}") URI base) {
    if (!"workers-ai".equals(generator)) return new FakeImageGenerator();
    if (account.isBlank() || token.isBlank()) {
      log.warn("No CF_ACCOUNT_ID or CF_AI_API_TOKEN: generated photos are placeholders");
      return new FakeImageGenerator();
    }
    return new WorkersAiImageGenerator(base, account, model, token);
  }

  @Bean
  PremiumImages premiumImages(@Value("${radiance.media.generator:fake}") String generator,
      @Value("${radiance.media.premium-model:}") String model, @Value("${radiance.media.premium-per-run:6}") int perRun,
      @Value("${radiance.backup.r2.account-id:}") String account, @Value("${radiance.media.premium-api-token:}") String token,
      @Value("${radiance.media.api-base:https://api.cloudflare.com}") URI base) {
    if (!"workers-ai".equals(generator) || model.isBlank() || account.isBlank() || token.isBlank()) return new PremiumImages(null, 0);
    return new PremiumImages(new PartnerImageGenerator(base, account, model, token), perRun);
  }

  @Bean
  Uploads uploads(Sql sql, WriterLane lane, MediaStore store, WallClock clock) {
    return new Uploads(sql, lane, store, clock);
  }

}
