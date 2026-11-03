package app.radiance.media;

import java.io.IOException;
import java.util.Optional;

/** Where media bytes live (SPEC §5.13, ADR 0021). Keys are ours, never from a request. */
public interface MediaStore {

  void put(String key, byte[] bytes) throws IOException, InterruptedException;

  Optional<byte[]> get(String key) throws IOException, InterruptedException;
}
