package app.radiance.narrative;

import app.radiance.llm.FakeResponses;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;

/** Registers the offline render templates, so the {@code offline} profile writes readable prose. */
@Configuration(proxyBeanMethods = false)
class NarrativeConfig implements InitializingBean {

  private final FakeResponses fakes;

  NarrativeConfig(FakeResponses fakes) {
    this.fakes = fakes;
  }

  @Override
  public void afterPropertiesSet() {
    FakeRender.register(fakes);
  }
}
