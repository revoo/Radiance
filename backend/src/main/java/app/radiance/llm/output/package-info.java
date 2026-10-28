/**
 * Output schemas and the parser that validates model-shaped JSON. Exposed so prose authored outside
 * the gateway (genesis, ADR 0017) gets exactly the checks a model's reply gets.
 */
@org.springframework.modulith.NamedInterface("output")
package app.radiance.llm.output;
