package app.radiance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlScriptTests {

  @Test
  void ignoresSemicolonsInCommentsAndStrings() {
    var script = """
        -- header; with a semicolon
        CREATE TABLE a (s TEXT DEFAULT 'x;y', t TEXT DEFAULT 'it''s; fine'); -- trailing; comment
        /* block; comment */ CREATE TABLE "we;ird" (id INTEGER);

        ;
        """;
    assertThat(SqlScript.split(script))
        .hasSize(2)
        .satisfiesExactly(
            s -> assertThat(s).startsWith("CREATE TABLE a").contains("'x;y'", "'it''s; fine'"),
            s -> assertThat(s).isEqualTo("CREATE TABLE \"we;ird\" (id INTEGER)"));
  }
}
