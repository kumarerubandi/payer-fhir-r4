package org.opencds.cqf.qdm.fivepoint4.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import javax.persistence.AttributeOverride;
import javax.persistence.AttributeOverrides;
import javax.persistence.Column;
import javax.persistence.MappedSuperclass;
import java.io.Serializable;

@MappedSuperclass
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public abstract class MedicationDispensed extends BaseType implements Serializable
{
    private String authorDatetime;

    @AttributeOverrides({
            @AttributeOverride(name = "start", column = @Column(name = "relevant_period_start")),
            @AttributeOverride(name = "end", column = @Column(name = "relevant_period_end"))
    })
    private DateTimeInterval relevantPeriod;

    private Integer refills;

    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "dosage_value")),
            @AttributeOverride(name = "unit", column = @Column(name = "dosage_unit"))
    })
    private Quantity dosage;

    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "supply_value")),
            @AttributeOverride(name = "unit", column = @Column(name = "supply_unit"))
    })
    private Quantity supply;

    @AttributeOverrides({
            @AttributeOverride(name = "code", column = @Column(name = "frequency_code")),
            @AttributeOverride(name = "display", column = @Column(name = "frequency_display")),
            @AttributeOverride(name = "system", column = @Column(name = "frequency_system")),
            @AttributeOverride(name = "version", column = @Column(name = "frequency_version"))
    })
    private Code frequency;

    private Integer daysSupplied;

    @AttributeOverrides({
            @AttributeOverride(name = "code", column = @Column(name = "route_code")),
            @AttributeOverride(name = "display", column = @Column(name = "route_display")),
            @AttributeOverride(name = "system", column = @Column(name = "route_system")),
            @AttributeOverride(name = "version", column = @Column(name = "route_version"))
    })
    private Code route;

    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "prescriber_id_value")),
            @AttributeOverride(name = "namingSystem", column = @Column(name = "prescriber_id_system"))
    })
    private Id prescriberId;

    @AttributeOverrides({
            @AttributeOverride(name = "value", column = @Column(name = "dispenser_id_value")),
            @AttributeOverride(name = "namingSystem", column = @Column(name = "dispenser_id_system"))
    })
    private Id dispenserId;

    @AttributeOverrides({
            @AttributeOverride(name = "code", column = @Column(name = "negation_rationale_code")),
            @AttributeOverride(name = "display", column = @Column(name = "negation_rationale_display")),
            @AttributeOverride(name = "system", column = @Column(name = "negation_rationale_system")),
            @AttributeOverride(name = "version", column = @Column(name = "negation_rationale_version"))
    })
    private Code negationRationale;

    @Override
    public void copy(BaseType other)
    {
        if (other instanceof MedicationDispensed)
        {
            MedicationDispensed medicationDispensed = (MedicationDispensed) other;
            super.copy(medicationDispensed);
            setAuthorDatetime(medicationDispensed.getAuthorDatetime());
            setRelevantPeriod(medicationDispensed.getRelevantPeriod());
            setRefills(medicationDispensed.getRefills());
            setDosage(medicationDispensed.getDosage());
            setSupply(medicationDispensed.getSupply());
            setFrequency(medicationDispensed.getFrequency());
            setDaysSupplied(medicationDispensed.getDaysSupplied());
            setRoute(medicationDispensed.getRoute());
            setPrescriberId(medicationDispensed.getPrescriberId());
            setDispenserId(medicationDispensed.getDispenserId());
            setNegationRationale(medicationDispensed.getNegationRationale());
        }
        else
        {
            throw new IllegalArgumentException(
                    String.format("Cannot copy QDM types %s and %s", this.getClass().getName(), other.getClass().getName())
            );
        }
    }
}
