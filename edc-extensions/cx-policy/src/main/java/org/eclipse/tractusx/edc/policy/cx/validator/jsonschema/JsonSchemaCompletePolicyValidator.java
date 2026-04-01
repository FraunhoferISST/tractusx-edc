package org.eclipse.tractusx.edc.policy.cx.validator.jsonschema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import jakarta.json.JsonObject;
import org.eclipse.edc.jsonld.util.JacksonJsonLd;
import org.eclipse.edc.validator.spi.ValidationResult;
import org.eclipse.edc.validator.spi.Violation;

import java.util.HashMap;
import java.util.Map;

public class JsonSchemaCompletePolicyValidator implements JsonSchemaPolicyValidator {

    private static final String CX_POLICY_SCHEMA_PREFIX = "https://w3id.org/catenax/2025/9/policy";
    private static final String CX_POLICY_SCHEMA_LOCATION = "classpath:schema/cx-policy";

    private static final String DSPACE_2025_SCHEMA_PREFIX = "https://w3id.org/dspace/2025/1/negotiation";
    private static final String DSPACE_2025_SCHEMA_LOCATION = "classpath:schema/dspace";

    private static final String CX_POLICY_SCHEMA = "https://w3id.org/catenax/2025/9/policy/schema/policy-schema.json";

    private final SchemaRegistry schemaRegistry;
    private final ObjectMapper objectMapper;

    private final Map<String, String> prefixMappings = new HashMap<>() {
        {
            put(CX_POLICY_SCHEMA_PREFIX + "/schema", CX_POLICY_SCHEMA_LOCATION);
            put(CX_POLICY_SCHEMA_PREFIX, CX_POLICY_SCHEMA_LOCATION);
            put(DSPACE_2025_SCHEMA_PREFIX, DSPACE_2025_SCHEMA_LOCATION);
        }
    };

    public JsonSchemaCompletePolicyValidator() {
        this.objectMapper = JacksonJsonLd.createObjectMapper();
        this.schemaRegistry = SchemaRegistry.withDialect(Dialects.getDraft201909(), builder ->
                builder.schemaIdResolvers(resolvers -> prefixMappings.forEach(resolvers::mapPrefix)));
    }

    @Override
    public ValidationResult validate(JsonObject input) {
        var schemaValidator = schemaRegistry.getSchema(SchemaLocation.of(CX_POLICY_SCHEMA));
        var node = objectMapper.convertValue(input, JsonNode.class);
        var response = schemaValidator.validate(node);
        if (response.isEmpty()) {
            return ValidationResult.success();
        }

        var violations = response.stream()
                .map(error -> Violation.violation(error.getMessage(), error.getInstanceLocation().toString()))
                .toList();

        return ValidationResult.failure(violations);
    }
}
