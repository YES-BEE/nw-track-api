package com.yesbee.nwtrack.workflow;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlacementCommandValidationTest {
    @Test
    void rejectsMissingDestinationTypeBeforeWorkflowExecution() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var command = new PlacementService.PlacementCommand(null, null, null);
            var violations = factory.getValidator().validate(command);

            assertThat(violations).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("destinationType"));
        }
    }
}
