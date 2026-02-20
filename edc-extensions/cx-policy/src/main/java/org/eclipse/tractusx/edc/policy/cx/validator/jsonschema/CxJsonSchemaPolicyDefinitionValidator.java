package org.eclipse.tractusx.edc.policy.cx.validator.jsonschema;

import jakarta.json.JsonObject;
import org.eclipse.edc.validator.spi.ValidationResult;
import org.eclipse.edc.validator.spi.Validator;

public class CxJsonSchemaPolicyDefinitionValidator implements Validator<JsonObject> {

    private static final String POLICY_ATTRIBUTE_NAME = "policy";

    private final JsonSchemaPolicyValidator policyValidator;

    public CxJsonSchemaPolicyDefinitionValidator() {
        this.policyValidator = new JsonSchemaCompletePolicyValidator();
    }

    @Override
    public ValidationResult validate(JsonObject input) {
        var policy = input.getJsonObject(POLICY_ATTRIBUTE_NAME);
        return policyValidator.validate(policy);
    }
}
