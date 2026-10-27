package app.radiance.kernel;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

/** Proves jqwik runs on the Boot-managed JUnit Platform (SPEC §3.4). */
class JqwikSmokeProperties {

  @Property(tries = 50)
  void additionCommutes(@ForAll int a, @ForAll int b) {
    assertThat(a + b).isEqualTo(b + a);
  }
}
