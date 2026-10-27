package app.radiance.graph;

import app.radiance.content.ContentLibrary;
import app.radiance.llm.FakeResponses;
import app.radiance.llm.LlmGateway;
import app.radiance.persistence.Sql;
import app.radiance.persistence.WriterLane;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class GraphConfig {

  @Bean
  Onboarding onboarding(Sql sql, WriterLane lane, LlmGateway gateway, ContentLibrary lib, FakeResponses fakes,
      @Value("${radiance.social.crush-propensity:1.0}") double crushPropensity) {
    Onboarding.registerFake(fakes);
    return new Onboarding(sql, lane, gateway, lib, crushPropensity);
  }
}
