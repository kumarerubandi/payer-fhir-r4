package org.opencds.cqf.r4.providers;

import ca.uhn.fhir.jpa.rp.r4.ClaimResponseResourceProvider;

public class FHIRClaimResponseProvider extends ClaimResponseResourceProvider {

    private JpaDataProvider provider;
    public FHIRClaimResponseProvider(JpaDataProvider provider) {
        this.provider = provider;
    }
}