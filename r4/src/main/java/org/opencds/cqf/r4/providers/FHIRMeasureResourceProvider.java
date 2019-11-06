package org.opencds.cqf.r4.providers;

import java.util.ArrayList;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

import javax.servlet.http.HttpServletRequest;
import java.util.Random;
import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.Base;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.xhtml.XhtmlNode;
import org.opencds.cqf.cql.execution.LibraryLoader;
import org.opencds.cqf.qdm.providers.Qdm54DataProvider;
import org.opencds.cqf.r4.evaluation.MeasureEvaluation;
import org.opencds.cqf.r4.evaluation.MeasureEvaluationSeed;
import org.opencds.cqf.r4.helpers.LibraryHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import ca.uhn.fhir.context.BaseRuntimeChildDefinition;
import ca.uhn.fhir.jpa.dao.IFhirSystemDao;
import ca.uhn.fhir.jpa.rp.r4.MeasureResourceProvider;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.RequiredParam;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.TokenParam;
import ca.uhn.fhir.rest.param.TokenParamModifier;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import java.math.BigDecimal; 
import ca.uhn.fhir.rest.api.server.IBundleProvider;

public class FHIRMeasureResourceProvider extends MeasureResourceProvider {

    private JpaDataProvider provider;
    private IFhirSystemDao systemDao;

    private NarrativeProvider narrativeProvider;
    private HQMFProvider hqmfProvider;
    private DataRequirementsProvider dataRequirementsProvider;

    private LibraryResourceProvider libraryResourceProvider;

    private static final Logger logger = LoggerFactory.getLogger(FHIRMeasureResourceProvider.class);

    public FHIRMeasureResourceProvider(JpaDataProvider dataProvider, IFhirSystemDao systemDao,
            NarrativeProvider narrativeProvider, HQMFProvider hqmfProvider) {
        this.provider = dataProvider;
        this.systemDao = systemDao;

        this.libraryResourceProvider = (LibraryResourceProvider) dataProvider.resolveResourceProvider("Library");
        this.narrativeProvider = narrativeProvider;
        this.hqmfProvider = hqmfProvider;
        this.dataRequirementsProvider = new DataRequirementsProvider();
    }

    @Operation(name = "$hqmf", idempotent = true)
    public Parameters hqmf(@IdParam IdType theId) {
        Measure theResource = this.getDao().read(theId);
        String hqmf = this.generateHQMF(theResource);
        Parameters p = new Parameters();
        p.addParameter().setValue(new StringType(hqmf));
        return p;
    }

    @Operation(name = "$refresh-generated-content")
    public MethodOutcome refreshGeneratedContent(HttpServletRequest theRequest, RequestDetails theRequestDetails,
            @IdParam IdType theId) {
        Measure theResource = this.getDao().read(theId);
        CqfMeasure cqfMeasure = this.dataRequirementsProvider.createCqfMeasure(theResource, this.libraryResourceProvider);

        // Ensure All Related Artifacts for all referenced Libraries
        if (!cqfMeasure.getRelatedArtifact().isEmpty()) {
            for (RelatedArtifact relatedArtifact : cqfMeasure.getRelatedArtifact()) {
                boolean artifactExists = false;
                // logger.info("Related Artifact: " + relatedArtifact.getUrl());
                for (RelatedArtifact resourceArtifact : theResource.getRelatedArtifact()) {
                    if (resourceArtifact.equalsDeep(relatedArtifact)) {
                        // logger.info("Equals deep true");
                        artifactExists = true;
                        break;
                    }
                }
                if (!artifactExists) {
                    theResource.addRelatedArtifact(relatedArtifact.copy());
                }
            }
        }

        Narrative n = this.narrativeProvider.getNarrative(this.getContext(), cqfMeasure);
        theResource.setText(n.copy());
        // logger.info("Narrative: " + n.getDivAsString());
        return super.update(theRequest, theResource, theId,
                theRequestDetails.getConditionalUrl(RestOperationTypeEnum.UPDATE), theRequestDetails);
    }

    @Operation(name = "$get-narrative", idempotent = true)
    public Parameters getNarrative(@IdParam IdType theId) {
        Measure theResource = this.getDao().read(theId);
        CqfMeasure cqfMeasure = this.dataRequirementsProvider.createCqfMeasure(theResource, this.libraryResourceProvider);
        Narrative n = this.narrativeProvider.getNarrative(this.getContext(), cqfMeasure);
        Parameters p = new Parameters();
        p.addParameter().setValue(new StringType(n.getDivAsString()));
        return p;
    }

    private String generateHQMF(Measure theResource) {
        CqfMeasure cqfMeasure = this.dataRequirementsProvider.createCqfMeasure(theResource, this.libraryResourceProvider);
        return this.hqmfProvider.generateHQMF(cqfMeasure);
    }

    /*
     *
     * NOTE that the source, user, and pass parameters are not standard parameters
     * for the FHIR $evaluate-measure operation
     *
     */
    @Operation(name = "$evaluate-measure", idempotent = true)
    public MeasureReport evaluateMeasure(@IdParam IdType theId, @RequiredParam(name = "periodStart") String periodStart,
            @RequiredParam(name = "periodEnd") String periodEnd, @OptionalParam(name = "measure") String measureRef,
            @OptionalParam(name = "reportType") String reportType, @OptionalParam(name = "patient") String patientRef,
            @OptionalParam(name = "productLine") String productLine,
            @OptionalParam(name = "practitioner") String practitionerRef,
            @OptionalParam(name = "lastReceivedOn") String lastReceivedOn,
            @OptionalParam(name = "source") String source, @OptionalParam(name = "user") String user,
            @OptionalParam(name = "pass") String pass) throws InternalErrorException, FHIRException {
        LibraryLoader libraryLoader = LibraryHelper.createLibraryLoader(this.libraryResourceProvider);
        MeasureEvaluationSeed seed = new MeasureEvaluationSeed(provider, libraryLoader, this.libraryResourceProvider);
        Measure measure = this.getDao().read(theId);

        if (measure == null) {
            throw new RuntimeException("Could not find Measure/" + theId.getIdPart());
        }

        seed.setup(measure, periodStart, periodEnd, productLine, source, user, pass);

        // resolve report type
        MeasureEvaluation evaluator = new MeasureEvaluation(seed.getDataProvider(), seed.getMeasurementPeriod());
        boolean isQdm = seed.getDataProvider() instanceof Qdm54DataProvider;
        if (reportType != null) {
            switch (reportType) {
            case "patient":
                return isQdm ? evaluator.evaluateQdmPatientMeasure(seed.getMeasure(), seed.getContext(), patientRef)
                        : evaluator.evaluatePatientMeasure(seed.getMeasure(), seed.getContext(), patientRef);
            case "patient-list":
                return evaluator.evaluateSubjectListMeasure(seed.getMeasure(), seed.getContext(), practitionerRef);
            case "population":
                return isQdm ? evaluator.evaluateQdmPopulationMeasure(seed.getMeasure(), seed.getContext())
                        : evaluator.evaluatePopulationMeasure(seed.getMeasure(), seed.getContext());
            default:
                throw new IllegalArgumentException("Invalid report type: " + reportType);
            }
        }

        // default report type is patient
        MeasureReport report = isQdm ? evaluator.evaluateQdmPatientMeasure(seed.getMeasure(), seed.getContext(), patientRef) : evaluator.evaluatePatientMeasure(seed.getMeasure(), seed.getContext(), patientRef);
        if (productLine != null)
        {
            Extension ext = new Extension();
            ext.setUrl("http://hl7.org/fhir/us/cqframework/cqfmeasures/StructureDefinition/cqfm-productLine");
            ext.setValue(new StringType(productLine));
            report.addExtension(ext);
        }

        return report;
    }

    @Operation(name = "$evaluate-measure-with-source", idempotent = true)
    public MeasureReport evaluateMeasure(@IdParam IdType theId,
            @OperationParam(name = "sourceData", min = 1, max = 1, type = Bundle.class) Bundle sourceData,
            @OperationParam(name = "periodStart", min = 1, max = 1) String periodStart,
            @OperationParam(name = "periodEnd", min = 1, max = 1) String periodEnd) {
        if (periodStart == null || periodEnd == null) {
            throw new IllegalArgumentException("periodStart and periodEnd are required for measure evaluation");
        }
        LibraryLoader libraryLoader = LibraryHelper.createLibraryLoader(this.libraryResourceProvider);
        MeasureEvaluationSeed seed = new MeasureEvaluationSeed(provider, libraryLoader, this.libraryResourceProvider);
        Measure measure = this.getDao().read(theId);

        if (measure == null) {
            throw new RuntimeException("Could not find Measure/" + theId.getIdPart());
        }

        seed.setup(measure, periodStart, periodEnd, null, null, null, null);
        BundleDataProviderR4 bundleProvider = new BundleDataProviderR4(sourceData);
        bundleProvider.setTerminologyProvider(provider.getTerminologyProvider());
        seed.getContext().registerDataProvider("http://hl7.org/fhir", bundleProvider);
        MeasureEvaluation evaluator = new MeasureEvaluation(bundleProvider, seed.getMeasurementPeriod());
        return evaluator.evaluatePatientMeasure(seed.getMeasure(), seed.getContext(), "");
    }

    @Operation(name = "$care-gaps", idempotent = true)
    public Bundle careGapsReport(@RequiredParam(name = "periodStart") String periodStart,
            @RequiredParam(name = "periodEnd") String periodEnd, @RequiredParam(name = "topic") String topic,
            @RequiredParam(name = "patient") String patientRef) {
        List<IBaseResource> measures = getDao().search(new SearchParameterMap().add("topic",
                new TokenParam().setModifier(TokenParamModifier.TEXT).setValue(topic))).getResources(0, 1000);
        Bundle careGapReport = new Bundle();
        careGapReport.setType(Bundle.BundleType.DOCUMENT);

        Composition composition = new Composition();
        // TODO - this is a placeholder code for now ... replace with preferred code
        // once identified
        CodeableConcept typeCode = new CodeableConcept()
                .addCoding(new Coding().setSystem("http://loinc.org").setCode("57024-2"));
        composition.setStatus(Composition.CompositionStatus.FINAL).setType(typeCode)
                .setSubject(new Reference(patientRef.startsWith("Patient/") ? patientRef : "Patient/" + patientRef))
                .setTitle(topic + " Care Gap Report");

        List<MeasureReport> reports = new ArrayList<>();
        MeasureReport report;
        for (IBaseResource resource : measures) {
            Composition.SectionComponent section = new Composition.SectionComponent();

            Measure measure = (Measure) resource;
            section.addEntry(
                    new Reference(measure.getIdElement().getResourceType() + "/" + measure.getIdElement().getIdPart()));
            if (measure.hasTitle()) {
                section.setTitle(measure.getTitle());
            }
            CodeableConcept improvementNotation = new CodeableConcept().addCoding(new Coding().setCode("increase").setSystem("http://terminology.hl7.org/CodeSystem/measure-improvement-notation")); // defaulting to "increase"
            if (measure.hasImprovementNotation()) {
                improvementNotation = measure.getImprovementNotation();
                section.setText(new Narrative().setStatus(Narrative.NarrativeStatus.GENERATED)
                        .setDiv(new XhtmlNode().setValue(improvementNotation.getCodingFirstRep().getCode())));
            }

            LibraryLoader libraryLoader = LibraryHelper.createLibraryLoader(this.libraryResourceProvider);
            MeasureEvaluationSeed seed = new MeasureEvaluationSeed(provider, libraryLoader, this.libraryResourceProvider);
            seed.setup(measure, periodStart, periodEnd, null, null, null, null);
            MeasureEvaluation evaluator = new MeasureEvaluation(seed.getDataProvider(), seed.getMeasurementPeriod());
            // TODO - this is configured for patient-level evaluation only
            report = evaluator.evaluatePatientMeasure(seed.getMeasure(), seed.getContext(), patientRef);

            if (report.hasGroup() && measure.hasScoring()) {
                int numerator = 0;
                int denominator = 0;
                for (MeasureReport.MeasureReportGroupComponent group : report.getGroup()) {
                    if (group.hasPopulation()) {
                        for (MeasureReport.MeasureReportGroupPopulationComponent population : group.getPopulation()) {
                            // TODO - currently configured for measures with only 1 numerator and 1
                            // denominator
                            if (population.hasCode()) {
                                if (population.getCode().hasCoding()) {
                                    for (Coding coding : population.getCode().getCoding()) {
                                        if (coding.hasCode()) {
                                            if (coding.getCode().equals("numerator") && population.hasCount()) {
                                                numerator = population.getCount();
                                            } else if (coding.getCode().equals("denominator")
                                                    && population.hasCount()) {
                                                denominator = population.getCount();
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                double proportion = 0.0;
                if (measure.getScoring().hasCoding() && denominator != 0) {
                    for (Coding coding : measure.getScoring().getCoding()) {
                        if (coding.hasCode() && coding.getCode().equals("proportion")) {
                            proportion = numerator / denominator;
                        }
                    }
                }

                // TODO - this is super hacky ... change once improvementNotation is specified
                // as a code
                if (improvementNotation.getCodingFirstRep().getCode().toLowerCase().equals("increase")) {
                    if (proportion < 1.0) {
                        composition.addSection(section);
                        reports.add(report);
                    }
                } else if (improvementNotation.getCodingFirstRep().getCode().toLowerCase().equals("decrease")) {
                    if (proportion > 0.0) {
                        composition.addSection(section);
                        reports.add(report);
                    }
                }

                // TODO - add other types of improvement notation cases
            }
        }

        careGapReport.addEntry(new Bundle.BundleEntryComponent().setResource(composition));

        for (MeasureReport rep : reports) {
            careGapReport.addEntry(new Bundle.BundleEntryComponent().setResource(rep));
        }

        return careGapReport;
    }

    @Operation(name = "$collect-data", idempotent = true)
    public Parameters collectData(@IdParam IdType theId, @RequiredParam(name = "periodStart") String periodStart,
            @RequiredParam(name = "periodEnd") String periodEnd, @OptionalParam(name = "patient") String patientRef,
            @OptionalParam(name = "practitioner") String practitionerRef,
            @OptionalParam(name = "lastReceivedOn") String lastReceivedOn) throws FHIRException {
        // TODO: Spec says that the periods are not required, but I am not sure what to
        // do when they aren't supplied so I made them required
        MeasureReport report = evaluateMeasure(theId, periodStart, periodEnd, null, null, null, patientRef,
                practitionerRef, lastReceivedOn, null, null, null);
        report.setGroup(null);

        Parameters parameters = new Parameters();

        parameters.addParameter(
                new Parameters.ParametersParameterComponent().setName("measurereport").setResource(report));

        if (report.hasContained()) {
            for (Resource contained : report.getContained()) {
                if (contained instanceof Bundle) {
                    addEvaluatedResourcesToParameters((Bundle) contained, parameters);
                }
            }
        }

        // TODO: need a way to resolve referenced resources within the evaluated
        // resources
        // Should be able to use _include search with * wildcard, but HAPI doesn't
        // support that

        return parameters;
    }

    private void addEvaluatedResourcesToParameters(Bundle contained, Parameters parameters) {
        Map<String, Resource> resourceMap = new HashMap<>();
        if (contained.hasEntry()) {
            for (Bundle.BundleEntryComponent entry : contained.getEntry()) {
                if (entry.hasResource() && !(entry.getResource() instanceof ListResource)) {
                    if (!resourceMap.containsKey(entry.getResource().getIdElement().getValue())) {
                        parameters.addParameter(new Parameters.ParametersParameterComponent().setName("resource")
                                .setResource(entry.getResource()));

                        resourceMap.put(entry.getResource().getIdElement().getValue(), entry.getResource());

                        resolveReferences(entry.getResource(), parameters, resourceMap);
                    }
                }
            }
        }
    }

    private void resolveReferences(Resource resource, Parameters parameters, Map<String, Resource> resourceMap) {
        List<IBase> values;
        for (BaseRuntimeChildDefinition child : getContext().getResourceDefinition(resource).getChildren()) {
            values = child.getAccessor().getValues(resource);
            if (values == null || values.isEmpty()) {
                continue;
            }

            else if (values.get(0) instanceof Reference
                    && ((Reference) values.get(0)).getReferenceElement().hasResourceType()
                    && ((Reference) values.get(0)).getReferenceElement().hasIdPart()) {
                Resource fetchedResource = (Resource) provider
                        .resolveResourceProvider(((Reference) values.get(0)).getReferenceElement().getResourceType())
                        .getDao().read(new IdType(((Reference) values.get(0)).getReferenceElement().getIdPart()));

                if (!resourceMap.containsKey(fetchedResource.getIdElement().getValue())) {
                    parameters.addParameter(new Parameters.ParametersParameterComponent().setName("resource")
                            .setResource(fetchedResource));

                    resourceMap.put(fetchedResource.getIdElement().getValue(), fetchedResource);
                }
            }
        }
    }

    // TODO - this needs a lot of work
    @Operation(name = "$data-requirements", idempotent = true)
    public org.hl7.fhir.r4.model.Library dataRequirements(@IdParam IdType theId,
            @RequiredParam(name = "startPeriod") String startPeriod,
            @RequiredParam(name = "endPeriod") String endPeriod) throws InternalErrorException, FHIRException {
        
        Measure measure = this.getDao().read(theId);
        return this.dataRequirementsProvider.getDataRequirements(measure, this.libraryResourceProvider);
    }
    
//    @Operation(name = "$calculate-score", idempotent = true)
//    public  calculateScore(@IdParam IdType theId,@ResourceParam String theRawBody,
//            @RequiredParam(name = "startPeriod") String startPeriod,
//            @RequiredParam(name = "endPeriod") String endPeriod) throws InternalErrorException, FHIRException {
//        
//        Measure measure = this.getDao().read(theId);
//        return 72;
//    }
    public static int getRandom(int[] array) {
        int rnd = new Random().nextInt(array.length);
        System.out.println("RANNNNDDd"+rnd);
        return array[rnd];
    }
    
    @Operation(name = "$calculate-score", idempotent = true)
    public MeasureReport  calculateScore(@ResourceParam String theRawBody ) throws InternalErrorException, FHIRException {
//        System.out.println("JSONN Body");
//        System.out.println(theRawBody);
    	
    	MeasureReport resMeasureReport = new MeasureReport();
    	String errMsg = "";
        try {
	        JSONObject inputJson = new JSONObject(theRawBody);
	        Iterator<String> jsonKeys = inputJson.keys();
	        double weightedScore = 0;
//	        Parameters parameters = new Parameters();
	        while(jsonKeys.hasNext()) {
	        	System.out.println("===========================");
	            String jsonKey = jsonKeys.next();
	            if( !(jsonKey.equals("resourceType") || jsonKey.equals("savedToCloud"))) {
	            	JSONObject jsonObj = new JSONObject(inputJson.getString(jsonKey));
	            	double totalScore = 0;
		        	System.out.println("Key: "+jsonKey);
//		            System.out.println(jsonObj);
		            if(jsonObj.has("measureList")) {
		            	
		            	JSONArray measureList = new JSONArray(jsonObj.getString("measureList"));
//		            	System.out.println(measureList);
		            	if(jsonKey.equals("qualityImprovement")) {
		            		if(measureList.length() < 6) {
		            			throw new RuntimeException("minimum 6 measures !!");
		            		}
		            	}
		            	double bonusPoints = 0;
		            	for(int i = 0; i < measureList.length(); i++)
		            	{
		            		  System.out.println("--------------------------");
		            		  MeasureReport measureReport = new MeasureReport();
		            	      JSONObject measureObj = measureList.getJSONObject(i);
//		            	      System.out.println("MEASUREE OBJBJJ");
		            	      String measureIdStr = measureObj.getString("measureId");
		            	      if(measureIdStr.equals("")) {
//		            	    	  System.out.println("---------------");
		            	    	  continue;
		            	      }
		            	      System.out.println(measureIdStr +" measureObj");
		            	     /**
		            	      SearchParameterMap paramMap = new SearchParameterMap();
		            	      paramMap.add("identifier", new ReferenceParam(measureIdStr));
		            	      IBundleProvider measureBundle=this.getDao().search(paramMap);
		            	      if(measureBundle.size() == 0) {
		            	    	  System.out.println("MEASURE With given identifier not found");
		            	      }
		            	      List<IBaseResource> matchedMeasures= measureBundle.getResources(0,measureBundle.size());
		            	      System.out.println("MEASUREE Id"+matchedMeasures.toString());
		            	      IdType measureId = new IdType(matchedMeasures.get(0).getIdElement().getIdPart());
		            	      Measure measure = this.getDao().read(measureId);
//		            	      pids = 
		            	      
		            	      */
		            	      //{4967,5772,10381,10384,10386}
		            	      int[] msrArray = new int[] {4967,5772,10381,10384,10386};
		            	      IdType measureId = new IdType(getRandom(msrArray)+"");
		            	      
		            	      Measure measure = this.getDao().read(measureId);
		            	      System.out.println("random Msr ID "+ measure.getIdElement().getIdPart());
		            	      measureReport= evaluateMeasure(measureId, "2018-06-19", "2020-09-25", null, null, null, null,
		            	                null, null, null, null, null);
		            	      
//		            	      List<MeasureReportGroupComponent> groups = measureReport.getGroup();
		            	      double measureScore= measureReport.getGroup().get(0).getMeasureScore().getValue().doubleValue();
		            	      double maxPoints = 10 ; 
		            	      String measureName =  measure.getName();
		            	      System.out.println("MEASURE NAME: " +measureName);
		            	      
		            	      if(jsonKey.equals("qualityImprovement")) {
		            	    	  boolean priority = measureObj.getBoolean("highPriority") ;
		            	    	  if(priority) {
			            	    	  bonusPoints = bonusPoints + 5 ; 
			            	    	  System.out.println("Priority: " + priority);
		            	    	  }
		            	      }
		            	      
		            	      switch(measureName) {
		            	      	case "Verify Opioid Treatment Agreement":
		            	      	case "Query of the Prescription Drug Monitoring Program (PDMP)":{
		            	      		bonusPoints = bonusPoints + 5 ; 
		            	      		System.out.println("+5 Bonus for Measure : " +measureName);
		            	      		break;
		            	      	}
		            	      	case "Support Electronic Referral Loops By Receiving and Incorporating Health Information Exclusion":
		            	      	case "Support Electronic Referral Loops By Sending Health Information":{
		            	      		maxPoints = 20 ; 
		            	      		System.out.println("MAx Points 20 for measure : " +measureName);
		            	      		break;
		            	      	}
		            	      	case "Provide Patients Electronic Access to Their Health Information":{
		            	      		maxPoints = 40 ; 
		            	      		System.out.println("MAx Points 40 for measure : " +measureName);
		            	      		break;
		            	      	}
		            	      	case "Security Risk Analysis":{
		            	      		maxPoints = 0 ; 
		            	      		System.out.println("MAx Points 0 for measure : " +measureName);
		            	      		break;
		            	      	}
		            	      
		            	      }
		            	      
		            	      //System.out.println("Measure Score: "+measureScore.toString());
		            	      measureScore = measureScore*10 ; 
		            	      if(measureScore > maxPoints) {
		            	    	  measureScore = maxPoints ; 
		            	      }
		            	      totalScore =  totalScore+ measureScore;
//		            	      System.out.println(measureReport.getGroup().get(0).getMeasureScore().getValue());
//		            	      System.out.println("---------------");
		            	}
		            	double avgScore = totalScore;
		            	System.out.println("Total score before rounding : " +avgScore);
		            	if(totalScore > 100) {
		            		avgScore = 100;
		            	} 
		            	System.out.println("Total score after rounding : " +avgScore);
		            	avgScore = avgScore+bonusPoints;
		            	System.out.println("Total bonus : " +bonusPoints);
		            	System.out.println("Total score after bonus : " +avgScore);
		            	
		            	jsonObj.put("totalScore",avgScore);
			            switch(jsonKey) {
			            	case "qualityImprovement":{
			            		weightedScore = weightedScore  + avgScore * 0.45;
			            		break;
			            	}
			            	case "promotingInteroperability":{
			            		weightedScore = weightedScore + avgScore * 0.25;
			            		break;
			            	}
			            	case "improvementActivity":
			            	case "costMeasures":{
			            		weightedScore = weightedScore + avgScore * 0.15;
			            		break;
			            	}
			            
			            }
			            
		            }
		            
		            
	            }
	            
	        }
	        MeasureReport.MeasureReportGroupComponent measureReportGroup = new MeasureReport.MeasureReportGroupComponent();
	        measureReportGroup.setMeasureScore(new Quantity(weightedScore));
	        resMeasureReport.addGroup(measureReportGroup);
	        
        }
        catch(JSONException json_ex) {
          errMsg = json_ex.getMessage();
      	  json_ex.printStackTrace();
        }
        
        catch(RuntimeException rex) {
        	errMsg = rex.getMessage();
        	throw new RuntimeException(errMsg);
        }
        catch(Exception ex) {
            errMsg = ex.getMessage();
            ex.printStackTrace();
          }
//        if(!errMsg.equals("")) {
//        	throw new RuntimeException(errMsg);
//        }
        return resMeasureReport;
    }
    
    @Operation(name = "$submit-data", idempotent = true)
    public Resource submitData(RequestDetails details, @IdParam IdType theId,
            @OperationParam(name = "measure-report", min = 1, max = 1, type = MeasureReport.class) MeasureReport report,
            @OperationParam(name = "resource") List<IAnyResource> resources) {
        Bundle transactionBundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);

        /*
         * TODO - resource validation using $data-requirements operation (params are the
         * provided id and the measurement period from the MeasureReport)
         * 
         * TODO - profile validation ... not sure how that would work ... (get
         * StructureDefinition from URL or must it be stored in Ruler?)
         */

        transactionBundle.addEntry(createTransactionEntry(report));

        for (IAnyResource resource : resources) {
            Resource res = (Resource) resource;
            if (res instanceof Bundle) {
                for (Bundle.BundleEntryComponent entry : createTransactionBundle((Bundle) res).getEntry()) {
                    transactionBundle.addEntry(entry);
                }
            } else {
                // Build transaction bundle
                transactionBundle.addEntry(createTransactionEntry(res));
            }
        }

        return (Resource) systemDao.transaction(details, transactionBundle);
    }

    private Bundle createTransactionBundle(Bundle bundle) {
        Bundle transactionBundle;
        if (bundle != null) {
            if (bundle.hasType() && bundle.getType() == Bundle.BundleType.TRANSACTION) {
                transactionBundle = bundle;
            } else {
                transactionBundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
                if (bundle.hasEntry()) {
                    for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
                        if (entry.hasResource()) {
                            transactionBundle.addEntry(createTransactionEntry(entry.getResource()));
                        }
                    }
                }
            }
        } else {
            transactionBundle = new Bundle().setType(Bundle.BundleType.TRANSACTION).setEntry(new ArrayList<>());
        }

        return transactionBundle;
    }

    private Bundle.BundleEntryComponent createTransactionEntry(Resource resource) {
        Bundle.BundleEntryComponent transactionEntry = new Bundle.BundleEntryComponent().setResource(resource);
        if (resource.hasId()) {
            transactionEntry.setRequest(
                    new Bundle.BundleEntryRequestComponent().setMethod(Bundle.HTTPVerb.PUT).setUrl(resource.getId()));
        } else {
            transactionEntry.setRequest(new Bundle.BundleEntryRequestComponent().setMethod(Bundle.HTTPVerb.POST)
                    .setUrl(resource.fhirType()));
        }
        return transactionEntry;
    }
}
