package org.opencds.cqf.r4.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

import javax.servlet.http.HttpServletRequest;
import java.util.Random;
import java.util.UUID;

import org.hl7.fhir.r4.model.*;
import org.hl7.fhir.r4.model.MeasureReport.MeasureReportGroupComponent;
import org.hl7.fhir.exceptions.FHIRException;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBase;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.xhtml.XhtmlNode;
import org.opencds.cqf.cql.execution.LibraryLoader;
import org.opencds.cqf.qdm.providers.Qdm54DataProvider;
import org.opencds.cqf.r4.evaluation.MeasureEvaluation;
import org.opencds.cqf.r4.evaluation.MeasureEvaluationSeed;
import org.opencds.cqf.r4.helpers.FhirMeasureBundler;
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
import ca.uhn.fhir.rest.param.StringParam;
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
		CqfMeasure cqfMeasure = this.dataRequirementsProvider.createCqfMeasure(theResource,
				this.libraryResourceProvider);

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
		CqfMeasure cqfMeasure = this.dataRequirementsProvider.createCqfMeasure(theResource,
				this.libraryResourceProvider);
		Narrative n = this.narrativeProvider.getNarrative(this.getContext(), cqfMeasure);
		Parameters p = new Parameters();
		p.addParameter().setValue(new StringType(n.getDivAsString()));
		return p;
	}

	private String generateHQMF(Measure theResource) {
		CqfMeasure cqfMeasure = this.dataRequirementsProvider.createCqfMeasure(theResource,
				this.libraryResourceProvider);
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
		MeasureReport report = isQdm
				? evaluator.evaluateQdmPatientMeasure(seed.getMeasure(), seed.getContext(), patientRef)
				: evaluator.evaluatePatientMeasure(seed.getMeasure(), seed.getContext(), patientRef);
		if (productLine != null) {
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
			CodeableConcept improvementNotation = new CodeableConcept().addCoding(new Coding().setCode("increase")
					.setSystem("http://terminology.hl7.org/CodeSystem/measure-improvement-notation")); // defaulting to
																										// "increase"
			if (measure.hasImprovementNotation()) {
				improvementNotation = measure.getImprovementNotation();
				section.setText(new Narrative().setStatus(Narrative.NarrativeStatus.GENERATED)
						.setDiv(new XhtmlNode().setValue(improvementNotation.getCodingFirstRep().getCode())));
			}

			LibraryLoader libraryLoader = LibraryHelper.createLibraryLoader(this.libraryResourceProvider);
			MeasureEvaluationSeed seed = new MeasureEvaluationSeed(provider, libraryLoader,
					this.libraryResourceProvider);
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

	@Operation(name = "$data-requirements-by-identfier", idempotent = true)
	public org.hl7.fhir.r4.model.Library dataRequirementsByIdentifier(@RequiredParam(name = "identifier") String theId,
			@RequiredParam(name = "startPeriod") String startPeriod,
			@RequiredParam(name = "endPeriod") String endPeriod) throws InternalErrorException, FHIRException {
		SearchParameterMap map = new SearchParameterMap();
		map.add("identifier", new TokenParam(theId));
		System.out.println(theId);
		IBundleProvider measuresFound = this.getDao().search(map);
		if(measuresFound.size() > 0) {
			List<IBaseResource> matchedMeasures= measuresFound.getResources(0,measuresFound.size());
			IdType measureId = new IdType(matchedMeasures.get(0).getIdElement().getIdPart());
			System.out.println(measuresFound.size());
			return dataRequirements(measureId,startPeriod,endPeriod);
		}
		
//		Measure measure = this.getDao().read(theId);
		Library libRes = new Library();
		return libRes;
//		return this.dataRequirementsProvider.getDataRequirements(measure, this.libraryResourceProvider);
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
		System.out.println("RANNNNDDd" + rnd);
		return array[rnd];
	}
	
	public int genRandomNum(int min,int max) {
		Random rand = new Random();

		// nextInt as provided by Random is exclusive of the top value so you need to add 1 

		int randomNum =  rand.nextInt((max - min) + 1) + min;
		return randomNum;
	}
	
	public JSONObject getMeasureScore(JSONObject measureObj,boolean fixedScore) {
		double measureScore = 0;
		JSONObject response = new JSONObject();
		
		try {
			response.put("measureScore", measureScore)	;
//			System.out.println("--------------------------");
			MeasureReport measureReport = new MeasureReport();
    	   
			String measureIdStr = measureObj.getString("measureId");
			System.out.println("---MEASUREE ID : "+ measureIdStr);
			SearchParameterMap map = new SearchParameterMap();
			map.add("identifier", new TokenParam(measureIdStr));
			System.out.println(measureIdStr);
			IBundleProvider measuresFound = this.getDao().search(map);
			if(measuresFound.size() > 0) {
				List<IBaseResource> matchedMeasures= measuresFound.getResources(0,measuresFound.size());
				IdType measureId = new IdType(matchedMeasures.get(0).getIdElement().getIdPart());
				
				
//				if (measureIdStr.equals("")) {
//	//    	    	  System.out.println("---------------");
//					continue;
//				}
				System.out.println(measureIdStr + " measureObj");
				
				// {4967,5772,10381,10384,10386}
		
				
	//			int[] msrArray = new int[] { 4967, 5772 };
//				IdType measureId = new IdType(measureIdStr);
	
				Measure measure = this.getDao().read(measureId);
				System.out.println("random Msr ID " + measure.getIdElement().getIdPart());
				measureReport = evaluateMeasure(measure.getIdElement(), "2018-06-19", "2020-09-25", null, null, null,
						null, null, null, null, null, null);
	
	//    	      List<MeasureReportGroupComponent> groups = measureReport.getGroup();
				for(MeasureReportGroupComponent group: measureReport.getGroup()) {
					int initialPopulation = genRandomNum(7,10);
					int denominator = genRandomNum(5,7);
					int numerator = genRandomNum(1,5);
					int denominatorExclusion = 0;
					int denominatorException = 0;
					if(fixedScore) {
						denominator =5;
						numerator = 5;
					}
					System.out.println("-->initialPopulation , numerator ,denominator "+initialPopulation +" " +numerator+ " "+ denominator);
					for(MeasureReport.MeasureReportGroupPopulationComponent populationObj : group.getPopulation()) {
						System.out.println("Population : "+populationObj.getCode().getCoding().get(0).getCode());
						String code = populationObj.getCode().getCoding().get(0).getCode();
						
						
						switch(code) {
							case "initial-population":{
								populationObj.setCount(initialPopulation);
								break;
							}
							case "denominator":{
								populationObj.setCount(denominator);
								break;
							}
							case "numerator":{
								populationObj.setCount(numerator);
								break;
							}
							case "denominator-exclusion":{
								populationObj.setCount(denominatorExclusion);
								break;
							}
							case "denominator-exception":{
								populationObj.setCount(denominatorException);
								break;
							}
						}
						
					}
					Quantity qty = new Quantity();
					measureScore =((double)numerator/(double)denominator);
					qty.setValue(measureScore);
					group.setMeasureScore(qty);
					
//					System.out.println("Group"+ group.getPopulation());
				}
				System.out.println("-->Group 0"+  measureReport.getGroup().get(0).getMeasureScore().getValue());
				
				/**
				BigDecimal score = measureReport.getGroup().get(0).getMeasureScore().getValue();
				
				if(score!=null) {
					measureScore = measureReport.getGroup().get(0).getMeasureScore().getValue().doubleValue();
				}
				**/
				
				response.put("report", measureReport);
				response.put("measureScore", measureScore)	;
				return response ;
			}
//			response.put("measureScore", measureScore)	;
		}
		catch(Exception ex) {
			ex.printStackTrace();
			
		}
		
		return response;
	}
	
	public void addToReports(Resource rep,HashMap<String, Resource> reports) {
		rep.setId(UUID.randomUUID().toString());
		reports.put(rep.getId(),rep);
	}
	
	public double getQIScore(JSONObject jsonObj,double weightage,HashMap<String, Resource> reports) throws RuntimeException {
		double score = 0;
		JSONObject response = new JSONObject();
		try {
			response.put("score", score);
			System.out.println("-------IN QI--------");
//			System.out.println(jsonObj);
			String collectionType = "";
			String  measureType= "";
			String  practice= "";
			String  reporting= "";
			String  specialtyMeasureSet= "";
			String  submissionType= "";
			if(jsonObj.has("collectionType")) {
				collectionType = jsonObj.getString("collectionType");
			}
			if(jsonObj.has("measureType")) {
				measureType = jsonObj.getString("measureType");
			}
			if(jsonObj.has("practice")) {
				practice = jsonObj.getString("practice");
			}
			if(jsonObj.has("reporting")) {
				reporting = jsonObj.getString("reporting");
			}
			if(jsonObj.has("specialtyMeasureSet")) {
				specialtyMeasureSet = jsonObj.getString("specialtyMeasureSet");
			}
			if(jsonObj.has("submissionType")) {
				submissionType = jsonObj.getString("submissionType");
			}
//			if(jsonObj.has("weightage")) {
//				weightage = jsonObj.getString("weightage");
//			}
			
			if (jsonObj.has("measureList")) {
				JSONArray measureList = new JSONArray(jsonObj.getString("measureList"));
				double maxScore = 60;
				/* TEMP COMMENT
				if(submissionType.equals("cms")) {
					if(measureList.length() < 10) {
						throw new RuntimeException("Minimum 10 measures are to be submitted for CMS Web type submission!!");
					}
					maxScore = 100;
				}
				else {
					if(measureList.length() < 6) {
						throw new RuntimeException("Minimum 6 measures are to be submitted from Quality Improvement category!!");
					}
				}
				*/
				boolean hasOutComeMeasure = false;
				boolean hasHighPriorityMeasure = false;
				boolean hasPatientExpMeasure = false;
				double totalBonus = 0;
				double totalScore = 0;
				boolean updateMaxScore = false;
				for(int i = 0; i < measureList.length(); i++) {
					double measureBonus = 0;
					JSONObject measureObj = measureList.getJSONObject(i);
					if(measureObj.has("measuretype")) {
						if(measureObj.getString("measuretype").equals("Outcome")){
							if(hasOutComeMeasure) { // bonus will be added from 2nd Outcome measure,if exists
								measureBonus = measureBonus+2;
								System.out.println("measure bonus for Outcome +2: "+measureBonus);
							}
							hasOutComeMeasure = true;
							
						}
						if(measureObj.getString("measuretype").equals("Patient Engagement Experience")){
//							hasHighPriorityMeasure = true;
							if(hasPatientExpMeasure) {
								measureBonus = measureBonus+2;
								System.out.println("measure bonus for hasPatientExpMeasure +2: "+measureBonus);
							}
							hasPatientExpMeasure = true;
						}
					}
					if(measureObj.has("collectionType")) {
						if(measureObj.getString("collectionType").equals("CAHPS for MIPS survey")) {
							measureBonus = measureBonus+2;
							System.out.println("measure bonus for CAHPS for MIPS survey +2: "+measureBonus);
						}
					}
					if(measureObj.has("highpriority")) {
						if(measureObj.getString("highpriority").equals("true")){					
							if(hasHighPriorityMeasure) {
								measureBonus = measureBonus+1;
								System.out.println("measure bonus for highPriority +1: "+measureBonus);

							}
							hasHighPriorityMeasure = true;
						}
					}
					int total = 0;
					System.out.println("Checking Data...");
					if(measureObj.has("data")) {
						JSONObject measuredata = new JSONObject();
						
						try{
							measuredata= measureObj.getJSONObject("data");
							System.out.println("Data: "+ measuredata);
							total= measuredata.getInt("total");
						}
						catch (JSONException e) {
							System.out.println("Not a valid JSON Data");
        						continue;
    						}
//						JSONObject dataBundle = measuredata.getJSONObject("dataBundle");
						
//						JSONArray entryList = new JSONArray(measuredata.getString("entry"));
//						
//						int msrLength = entryList.length();
//						msrLength = 21;
						System.out.println("Size of Entry List: "+total);
						if( total< 20) {
							continue;
						}
						else if (total >= 200 && practice == "large") {
							updateMaxScore = true;
						}
					}
					else {
						System.out.println("No msrData");
						/* TEMP COMMENT
						continue;
						*/
					}
					
					JSONObject scoreResponse = getMeasureScore(measureObj,false);
					System.out.println("scoreResponse "+scoreResponse);
					if(scoreResponse.has("report")) {
						addToReports((Resource) scoreResponse.get("report"),reports);
					}
					
					double measureScore = scoreResponse.getDouble("measureScore");
					
					double multiplier = 10;
					double maxPoints = 7;
					
					//CAHS for MIPS
					//60% Score
					//
					//
					System.out.println("measure Score bfore multiplication: "+measureScore);
					measureScore =measureScore*multiplier ;
					if (measureScore > maxPoints) {
						measureScore = maxPoints;
					}
					System.out.println("measure Score after multiplication: "+measureScore);

					System.out.println("measure bonux : "+maxScore+" , "+measureBonus);
					totalBonus = totalBonus + measureBonus;
					totalScore = totalScore + measureScore;
					
				}
				if(updateMaxScore) {
					maxScore = maxScore+10;
				}
				System.out.println("max Score and bonux : "+maxScore+" , "+totalBonus);
				if(practice.equals("small")) {
					totalBonus = totalBonus +2;
				}
				
				if(totalBonus > maxScore*0.10) {
					totalBonus =  maxScore*0.10;
				}
				System.out.println("Updated bonux :  "+totalBonus);
				System.out.println("QI Score before rounding  : "+totalScore);
				if(totalScore > maxScore) {
					totalScore = maxScore;
				}
				score = totalScore * weightage;
//				response.put("score", score);
				System.out.println("QI Score  : "+score);
			}
			
			
		}
		catch(RuntimeException rex) {
			rex.printStackTrace();
			throw rex;
		}
		catch(Exception ex) {
			ex.printStackTrace();
			
		}
			
	
		
		return score;
	}
	public  JSONObject getPIScore(JSONObject jsonObj,HashMap<String, Resource> reports) {
		JSONObject response = new JSONObject();
		double score = 0;
		
		try {
			response.put("score", score);
			boolean q1 = false;
			boolean q2= false;
			boolean q3= false;
			boolean q4= false;
			boolean q5= false;
			boolean q6= false;
			boolean q7= false;
			boolean q8= false;
			if(jsonObj.has("q1")) {
				q1 = (boolean) jsonObj.get("q1");
			}
			if(jsonObj.has("q2")) {
				q2 = (boolean) jsonObj.get("q2");
			}
			if(jsonObj.has("q3")) {
				q3 = (boolean) jsonObj.get("q3");
			}
			if(jsonObj.has("q4")) {
				q4 = (boolean) jsonObj.get("q4");
			}
			if(jsonObj.has("q5")) {
				q5 = (boolean) jsonObj.get("q5");
			}
			if(jsonObj.has("q6")) {
				q6 = (boolean) jsonObj.get("q6");
			}
			if(jsonObj.has("q7")) {
				q7 = (boolean) jsonObj.get("q7");
			}
			if(jsonObj.has("q8")) {
				q8 = (boolean) jsonObj.get("q8");
			}
			response.put("addWeightToQI",false);
			if(q1 || q2 || q3 || q4 || q5 || q6 || q7 || q8) {
				response.put("score",0);
				response.put("addWeightToQI",true);
				return response;
			}
			
			if (jsonObj.has("measureList")) {
				JSONArray measureList = new JSONArray(jsonObj.getString("measureList"));
				double totalScore = 0;
				List<JSONObject> EPMeasures =new ArrayList<JSONObject>(); 
				List<JSONObject> HIEMeasures=new ArrayList<JSONObject>(); 
				List<JSONObject> PTPMeasures=new ArrayList<JSONObject>(); 
				List<JSONObject> PHDEMeasures=new ArrayList<JSONObject>();
				for (int i = 0; i < measureList.length(); i++) {
					JSONObject measureObj = measureList.getJSONObject(i);
					if(measureObj.has("objectivename")) {
						String measureObjective = measureObj.getString("objectivename");
						switch(measureObjective) {
							case "Electronic Prescribing":{
								EPMeasures.add(measureObj);
								break;
							}
							case "Health Information Exchange":{
								HIEMeasures.add(measureObj);
								break;
							}
							case "Provider To Patient Exchange":{
								PTPMeasures.add(measureObj);
								break;
							}
							case "Public Health And Clinical Data Exchange":{
								PHDEMeasures.add(measureObj);
								break;
							}
						}
					}
					
				}
				double multiplier = 10;
				double maxPoints = 10;
				
				int EPSize = EPMeasures.size();
				for(int j=0;j<EPSize; j++) {
					JSONObject measureObj =EPMeasures.get(j);
					JSONObject scoreResponse = getMeasureScore(measureObj,true);
					if(scoreResponse.has("report")) {
						addToReports((Resource) scoreResponse.get("report"),reports);
					
					}
					double measureScore = scoreResponse.getDouble("measureScore");
					String measureIdStr =measureObj.getString("measureId");
					
					System.out.println("Measure Score : " + measureScore);
					measureScore =measureScore*multiplier;
					
					
					System.out.println("multiplier : " + multiplier );
					System.out.println("Measure Score * multiplier : " + measureScore);
//					measureIdStr = "PI_PHCDRR_5";
					if(measureIdStr.equals("PI_PHCDRR_5") || measureIdStr.equals("PI_EP_2")) {
						measureScore = measureScore+5;
					}
					if (measureScore > maxPoints) {
						measureScore = maxPoints;
					}
					totalScore = totalScore + measureScore;
					
				}
				int HIESize = HIEMeasures.size();
				for(int j=0;j<HIESize; j++) {
					JSONObject measureObj =HIEMeasures.get(j);
					
					if(HIESize == 1) {
						multiplier = 40;
						if(EPSize==0) {
							multiplier = 50;
						}
					}
					else if(HIESize == 2) {
						if(EPSize==0) {
							multiplier = 25;
						}
						else {
							multiplier = 20;
						}
						
					}
					
					JSONObject scoreResponse = getMeasureScore(measureObj,true);
					if(scoreResponse.has("report")) {
						addToReports((Resource) scoreResponse.get("report"),reports);
					}
					double measureScore = scoreResponse.getDouble("measureScore");
					System.out.println("Measure Score : " + measureScore);
					measureScore =measureScore*multiplier;
//					if (measureScore > maxPoints) {
//						measureScore = maxPoints;
//					}
					System.out.println("multiplier : " + multiplier );
					System.out.println("Measure Score * multiplier : " + measureScore);
					totalScore = totalScore + measureScore;
					
				}
				int PTPSize = PTPMeasures.size();
				int PHDESize = PHDEMeasures.size();
				System.out.println("PTPSize : " + PTPSize);
				System.out.println("PHDESize : " + PHDESize);
				for(int i=0;i<PTPSize; i++) {
					JSONObject measureObj =PTPMeasures.get(i);
					if(PTPSize == 1) {
						multiplier = 40;
						if(PHDESize == 0) {
							multiplier = 50;
						}
						
					}
					JSONObject scoreResponse = getMeasureScore(measureObj,true);
					if(scoreResponse.has("report")) {
						addToReports((Resource) scoreResponse.get("report"),reports);
					}
					double measureScore = scoreResponse.getDouble("measureScore");
					measureScore =measureScore*multiplier;
//					if (measureScore > maxPoints) {
//						measureScore = maxPoints;
//					}
					
					System.out.println("multiplier : " + multiplier );
					System.out.println("Measure Score * multiplier : " + measureScore);
					totalScore = totalScore + measureScore;	
				}
				for(int i=0;i<PHDESize; i++) {
					JSONObject measureObj =PHDEMeasures.get(i);
					if(PHDESize == 1) {
						multiplier = 10;
						
					}
					else if(PHDESize == 2) {
						multiplier = 5;
						
					}
					JSONObject scoreResponse = getMeasureScore(measureObj,true);
					if(scoreResponse.has("report")) {
						addToReports((Resource) scoreResponse.get("report"),reports);
					}
					double measureScore = scoreResponse.getDouble("measureScore");
					measureScore =measureScore*multiplier;
//					if (measureScore > maxPoints) {
//						measureScore = maxPoints;
//					}
					
					System.out.println("---multiplier : " + multiplier );
					System.out.println("---Measure Score * multiplier : " + measureScore);
					totalScore = totalScore + measureScore;	
				}
				
				System.out.println("\nPI---totalScore before rounding: " + totalScore);
				score =  totalScore * 0.25;
				System.out.println("---totalScore after rounding: " + totalScore);
				response.put("score",score);
				response.put("addWeightToQI",false);
				return response;
			}
			
			
		}
		catch(JSONException ex){
			ex.printStackTrace();
		}
		
		return response;
		
	}
	public double getIAScore(JSONObject jsonObj,HashMap<String, Resource> reports) {
		double score = 0;
		try {
			
			boolean hpsa = false;
			boolean tin= false;
			boolean practice= false;
			boolean npf= false;
			boolean doublePoints= false;
			if(jsonObj.has("hpsa")) {
				hpsa = (boolean) jsonObj.get("hpsa");
				
			}
			if(jsonObj.has("tin")) {
				tin = (boolean) jsonObj.get("tin");
			}
			if(jsonObj.has("practice")) {
				practice = (boolean) jsonObj.get("practice");
			}
			if(jsonObj.has("npf")) {
				npf = (boolean) jsonObj.get("npf");
				
			}
			if(hpsa || practice || npf) {
				doublePoints=true;
			}

			if(tin) {
				score = 15;
				return score;
			}
			System.out.println("tin doublePoints npf: "+ tin + doublePoints + npf );

			if (jsonObj.has("measureList")) {
				JSONArray measureList = new JSONArray(jsonObj.getString("measureList"));
				double totalScore = 0;
				for (int i = 0; i < measureList.length(); i++) {
					JSONObject measureObj = measureList.getJSONObject(i);
//					JSONObject scoreResponse = getMeasureScore(measureObj);

//		TEMP			double measureScore = scoreResponse.getDouble("measureScore");
					double measureScore = 1;
					double multiplier = 10;
					double maxPoints = 40;
					if(!measureObj.has("activityWeight") || !measureObj.has("measureName")) {
						continue;
					}
					String measureWeight = measureObj.getString("activityWeight");
					String measureName = measureObj.getString("measureName");
					System.out.println("MEASURE NAME: " + measureName);
					System.out.println("Measure Score: " + measureScore);
					if(measureWeight.equals("high")) {
						multiplier = 20;
					}
					if(measureWeight.equals("medium")) {
						multiplier = 10;
					}
					if(doublePoints) {
						multiplier = multiplier*2;
					}
					measureScore = measureScore * multiplier;
					if (measureScore > maxPoints) {
						measureScore = maxPoints;
					}
					System.out.println("multiplier : " + multiplier + " && msrWght : "+measureWeight);
					System.out.println("Measure Score * multiplier : " + measureScore);
					totalScore = totalScore + measureScore;
//            	      System.out.println(measureReport.getGroup().get(0).getMeasureScore().getValue());
//            	      System.out.println("---------------");
				}
				score =  totalScore * 0.15;
				
			}
		}
		catch(JSONException ex) {
			ex.printStackTrace();
		}
		return score;
		
	}
	
	
	
	public double getCMScore(JSONObject jsonObj,HashMap<String, Resource> reports) {
		System.out.println("-------IN CMS--------");
		System.out.println(jsonObj);
		double score = 0;
		try {
			if (jsonObj.has("measureList")) {
				JSONArray measureList = new JSONArray(jsonObj.getString("measureList"));
				double totalScore = 0;
				for (int i = 0; i < measureList.length(); i++) {
					JSONObject measureObj = measureList.getJSONObject(i);
//		TEMP			JSONObject scoreResponse = getMeasureScore(measureObj);
//					if(scoreResponse.has("report")) {
//						addToReports((Resource) scoreResponse.get("report"),reports);
//					}
//		TEMP			double measureScore = scoreResponse.getDouble("measureScore");

					double measureScore = 1;
					double multiplier = 10;
					double maxPoints = 10;
					String measureName = measureObj.getString("measureName");
					System.out.println("MEASURE NAME: " + measureName);
					System.out.println("Measure Score: " + measureScore);
					
					
					measureScore = measureScore * multiplier;
					if (measureScore > maxPoints) {
						measureScore = maxPoints;
					}
					System.out.println("multiplier : " + multiplier );
					System.out.println("Measure Score * multiplier : " + measureScore);
					totalScore = totalScore + measureScore;
//            	      System.out.println(measureReport.getGroup().get(0).getMeasureScore().getValue());
//            	      System.out.println("---------------");
				}
				
				score =  totalScore * 0.15;
				return score;
			}
		}
		catch (JSONException json_ex) {
//			errMsg = json_ex.getMessage();
			json_ex.printStackTrace();
		}

		catch (RuntimeException rex) {
//			errMsg = rex.getMessage();
			rex.printStackTrace();
			throw rex;
		} catch (Exception ex) {
//			errMsg = ex.getMessage();
			ex.printStackTrace();
		}
		return score;
		
	}

	@Operation(name = "$calculate-score", idempotent = true)
	public MeasureReport calculateScore(@ResourceParam String theRawBody) throws InternalErrorException, FHIRException {
//        System.out.println("JSONN Body");
//        System.out.println(theRawBody);

		MeasureReport resMeasureReport = new MeasureReport();
		String errMsg = "";
		Bundle reportsBundle = new Bundle().setType(Bundle.BundleType.COLLECTION);
		HashMap<String,Resource> reports = new HashMap();
		try {
			JSONObject inputJson = new JSONObject(theRawBody);
			Iterator<String> jsonKeys = inputJson.keys();
			double weightedScore = 0;
//	        Parameters parameters = new Parameters();
			double sum = 0;
//			JSONObject jsonObj = new JSONObject();
			JSONObject PIResponse = new JSONObject();
			boolean updateQIWeightFromPI = false;
			
			if(inputJson.has("promotingInteroperability")) {
				System.out.println("-------PI START--------");
				JSONObject PIObj= new JSONObject(inputJson.getString("promotingInteroperability"));
				PIResponse = getPIScore(PIObj,reports);
				if(PIResponse.getBoolean("addWeightToQI")) {
					updateQIWeightFromPI = true;
				}
				sum = sum+PIResponse.getDouble("score");
			}
			
			if(inputJson.has("improvementActivity")) {
				System.out.println("-------IA START--------");
				JSONObject IAObj= new JSONObject(inputJson.getString("improvementActivity"));
				double IAResponse = getIAScore(IAObj,reports);
				sum = sum+IAResponse;
			}
			if(inputJson.has("costMeasures")) {
				System.out.println("-------CM START--------");
				JSONObject CMObj= new JSONObject(inputJson.getString("costMeasures"));
				double CMSResponse = getCMScore(CMObj,reports);
				sum = sum+CMSResponse;
			}
			if(inputJson.has("qualityImprovement")) {
				System.out.println("-------QI START--------");
				JSONObject QIObj= new JSONObject(inputJson.getString("qualityImprovement"));
				double weightage = 0.45;
				
				if(updateQIWeightFromPI) {
					weightage = weightage+ 0.25;
				}
				double QIResponse = getQIScore(QIObj,weightage,reports);
				sum = sum+QIResponse;
			}
			System.out.println("\nreports"+reports);
			
			
			/*
			while (jsonKeys.hasNext()) {
				System.out.println("===========================");
				String jsonKey = jsonKeys.next();
//				if (!(jsonKey.equals("resourceType") || jsonKey.equals("savedToCloud"))) {
					
					
				JSONObject jsonObj = new JSONObject(inputJson.getString(jsonKey));
				
//					if (jsonKey.equals("qualityImprovement")) {
//						
//						
//					}
//					else if
				
				switch(jsonKey) {
					case "qualityImprovement":{
						sum = sum+getQIScore(jsonObj);
						break;
					}
					case "promotingInteroperability":{
						sum = sum+getPIScore(jsonObj).getDouble("score");
						break;
					}
					case "improvementActivity":{
						sum = sum+getIAScore(jsonObj);
						break;
					}
					case "costMeasures":{
						sum = sum+getCMScore(jsonObj);
						break;
					}
						
					
				}
				
				System.out.println(jsonKey+"-- Sum: " + sum);
				*/
					/*////////////////////////////
					double totalScore = 0;
					System.out.println("Key: " + jsonKey);
//		            System.out.println(jsonObj);
					if (jsonObj.has("measureList")) {

						JSONArray measureList = new JSONArray(jsonObj.getString("measureList"));
//		            	System.out.println(measureList);
						if (jsonKey.equals("qualityImprovement")) {
							if (measureList.length() < 6) {
								throw new RuntimeException("minimum 6 measures !!");
							}
						}
						double bonusPoints = 0;
						for (int i = 0; i < measureList.length(); i++) {
							System.out.println("--------------------------");
							MeasureReport measureReport = new MeasureReport();
							JSONObject measureObj = measureList.getJSONObject(i);
//		            	      System.out.println("MEASUREE OBJBJJ");
							String measureIdStr = measureObj.getString("measureId");
							if (measureIdStr.equals("")) {
//		            	    	  System.out.println("---------------");
								continue;
							}
							System.out.println(measureIdStr + " measureObj");
							
							// {4967,5772,10381,10384,10386}
					
						
							int[] msrArray = new int[] { 4967, 5772, 10381, 10384, 10386 };
							IdType measureId = new IdType(getRandom(msrArray) + "");

							Measure measure = this.getDao().read(measureId);
							System.out.println("random Msr ID " + measure.getIdElement().getIdPart());
							measureReport = evaluateMeasure(measureId, "2018-06-19", "2020-09-25", null, null, null,
									null, null, null, null, null, null);

//		            	      List<MeasureReportGroupComponent> groups = measureReport.getGroup();
							double measureScore = measureReport.getGroup().get(0).getMeasureScore().getValue()
									.doubleValue();
							double maxPoints = 10;
							String measureName = measureObj.getString("measureName");
							System.out.println("MEASURE NAME: " + measureName);

							if (jsonKey.equals("qualityImprovement") && measureObj.has("highPriority")) {

								boolean priority = measureObj.getBoolean("highPriority");
								if (priority) {
									bonusPoints = bonusPoints + 5;
									System.out.println("Priority: " + priority);
								}
							}

							switch (measureName) {
							case "Verify Opioid Treatment Agreement":
							case "Query of the Prescription Drug Monitoring Program (PDMP)": {
								bonusPoints = bonusPoints + 5;
								System.out.println("+5 Bonus for Measure : " + measureName);
								break;
							}
							case "Support Electronic Referral Loops By Receiving and Incorporating Health Information Exclusion":
							case "Support Electronic Referral Loops By Sending Health Information": {
								maxPoints = 20;
								System.out.println("MAx Points 20 for measure : " + measureName);
								break;
							}
							case "Provide Patients Electronic Access to Their Health Information": {
								maxPoints = 40;
								System.out.println("MAx Points 40 for measure : " + measureName);
								break;
							}
							case "Security Risk Analysis": {
								maxPoints = 0;
								System.out.println("MAx Points 0 for measure : " + measureName);
								break;
							}

							}

							System.out.println("Measure Score: " + measureScore);
							measureScore = measureScore * 10;
							if (measureScore > maxPoints) {
								measureScore = maxPoints;
							}
							System.out.println("Measure Score * 10 : " + measureScore);
							totalScore = totalScore + measureScore;
//		            	      System.out.println(measureReport.getGroup().get(0).getMeasureScore().getValue());
//		            	      System.out.println("---------------");
						}
						double avgScore = totalScore;
						System.out.println("Total score before rounding : " + avgScore);
						if (totalScore > 100) {
							avgScore = 100;
						}
						System.out.println("Total score after rounding : " + avgScore);
						avgScore = avgScore + bonusPoints;
						System.out.println("Total bonus : " + bonusPoints);
						System.out.println("Total score after bonus : " + avgScore);

						jsonObj.put("totalScore", avgScore);
						switch (jsonKey) {
						case "qualityImprovement": {
							weightedScore = weightedScore + avgScore * 0.45;
							break;
						}
						case "promotingInteroperability": {
							weightedScore = weightedScore + avgScore * 0.25;
							break;
						}
						case "improvementActivity":
						case "costMeasures": {
							weightedScore = weightedScore + avgScore * 0.15;
							break;
						}

						}

					}
////////////////////////////*/
//				}
				
				
/*
			}
			*/
			MeasureReport.MeasureReportGroupComponent measureReportGroup = new MeasureReport.MeasureReportGroupComponent();
			measureReportGroup.setMeasureScore(new Quantity(round(sum,2)));
			resMeasureReport.addGroup(measureReportGroup);
//			for(MeasureReport report: reports ) {
//				reportsBundle.addEntry(new Bundle.BundleEntryComponent().setResource(report));
//				
//			}
//			IdType bundleId = new IdType(genRandomNum(50000,60000));
//			reportsBundle.setId(bundleId);
//			List<Resource> containedResources = new ArrayList<Resource>();
//			containedResources.add(reportsBundle);
//			resMeasureReport.setContained(containedResources);
////			resMeasureReport.setC
//			List<Reference> referenceList= new ArrayList<Reference>();
//			Reference ref = new Reference();
//			ref.setId(bundleId.getId());
//			referenceList.add(ref);
			
			FhirMeasureBundler bundler = new FhirMeasureBundler();
            org.hl7.fhir.r4.model.Bundle evaluatedResources = bundler.bundle(reports.values());
            evaluatedResources.setId(UUID.randomUUID().toString());
            resMeasureReport.setEvaluatedResource(Collections.singletonList(new Reference('#' + evaluatedResources.getId())));
            resMeasureReport.addContained(evaluatedResources);
//			resMeasureReport.setId(new IdType("23"));

		} catch (JSONException json_ex) {
			errMsg = json_ex.getMessage();
			json_ex.printStackTrace();
		}

		catch (RuntimeException rex) {
			errMsg = rex.getMessage();
			rex.printStackTrace();
			throw rex;
		} catch (Exception ex) {
			errMsg = ex.getMessage();
			ex.printStackTrace();
		}
//        if(!errMsg.equals("")) {
//        	throw new RuntimeException(errMsg);
//        }
		return resMeasureReport;
	}

	public static double round(double value, int places) {
	    if (places < 0) throw new IllegalArgumentException();

	    long factor = (long) Math.pow(10, places);
	    value = value * factor;
	    long tmp = Math.round(value);
	    return (double) tmp / factor;
	}
	
	@Operation(name = "$submit-data-bundle", idempotent = true)
	public Bundle submitDataBundle(RequestDetails details, @IdParam IdType theId,
			@OperationParam(name = "bundle", min = 1, max = 1, type =  Bundle.class) Bundle bundle
			) {
		System.out.println("Bundle");
		Bundle transactionBundle = new Bundle().setType(Bundle.BundleType.TRANSACTION);
		if (bundle != null) {
		    final List<Bundle.BundleEntryComponent> entries = bundle.getEntry();
		    System.out.println("entries");
		    System.out.println(entries);
		    for (Bundle.BundleEntryComponent entry : entries) {
		      Resource resource = entry.getResource();
		      System.out.println("ResType : "+ resource.getResourceType());
		      if(resource.getResourceType().toString().equals("Parameters")) {
		    	  System.out.println("Yes : ");
		    	  MeasureReport report = new MeasureReport();
		    	  List<IAnyResource> resources = new ArrayList<IAnyResource>();
		    	  Parameters paramRes = (Parameters) resource ; 
		    	  System.out.println("ParamsList");
		    	  System.out.println(paramRes.getParameter());
		    	  List<Parameters.ParametersParameterComponent> params = paramRes.getParameter();
		    	  for(Parameters.ParametersParameterComponent param : params) {
		    		  System.out.println(param.getName());
		    		  
		    		  if(param.getName().equals("measure-report")) {
		    			  report = (MeasureReport) param.getResource();
		    		  }
		    		  else if(param.getName().equals("resource")) {
		    			  resources.add(param.getResource());
		    		  }
		    		  
		    	  }
		    	  System.out.println(report);
		    	  System.out.println(resources);
		    	  Bundle submitDataRes = (Bundle) submitData(details,theId,report,resources);
		    	  for (Bundle.BundleEntryComponent bundleEntry : submitDataRes.getEntry()) {
					transactionBundle.addEntry(bundleEntry);
				  }
		      }
		    }
		  }
		return transactionBundle;
		
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
		System.out.println("Resourece");
		System.out.println(resource);
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
