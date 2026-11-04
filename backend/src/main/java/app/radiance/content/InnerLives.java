package app.radiance.content;

import java.util.Map;

/** What's going on under what each resident says, by handle: {@code cast/inner-lives.yaml} (SPEC section 1). */
public record InnerLives(Map<String, InnerLife> residents) {

  public record InnerLife(String insecureAbout, String whenTouched, String wantsFromPeople) {}
}
