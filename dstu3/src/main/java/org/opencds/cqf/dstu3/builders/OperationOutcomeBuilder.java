package org.opencds.cqf.dstu3.builders;

import org.opencds.cqf.builders.BaseBuilder;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.OperationOutcome;

public class OperationOutcomeBuilder extends BaseBuilder<OperationOutcome>
{
    public OperationOutcomeBuilder()
    {
        super(new OperationOutcome());
    }

    public OperationOutcomeBuilder buildIssue(String severity, String code, String details)
    {
        complexProperty.addIssue(
                new OperationOutcome.OperationOutcomeIssueComponent()
                        .setSeverity(OperationOutcome.IssueSeverity.fromCode(severity))
                        .setCode(OperationOutcome.IssueType.fromCode(code))
                        .setDetails(new CodeableConcept().setText(details))
        );

        return this;
    }
}
