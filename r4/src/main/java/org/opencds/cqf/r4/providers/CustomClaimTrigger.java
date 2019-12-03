package org.opencds.cqf.r4.providers;

/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2019 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.subscription.ISubscriptionTriggeringSvc;
//import ca.uhn.fhir.jpa.model.util.JpaConstants;
import ca.uhn.fhir.model.dstu2.valueset.ResourceTypeEnum;
import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.OperationParam;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.param.UriParam;
import ca.uhn.fhir.rest.server.IResourceProvider;

import org.hl7.fhir.instance.model.api.IBaseParameters;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Claim;
import org.hl7.fhir.r4.model.ClaimResponse;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.gson.JsonObject;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.List;
import java.util.ArrayList;

import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import java.io.FileWriter;  
import java.io.FileReader;  
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;


public class CustomClaimTrigger implements IResourceProvider {
//	public static final String RESOURCE_ID = "resourceId";
//	public static final String SEARCH_URL = "searchUrl";
	@Autowired
	private FhirContext myFhirContext;
//	@Autowired
//	private ISubscriptionTriggeringSvc mySubscriptionTriggeringSvc;

	
//	@Operation(name = JpaConstants.OPERATION_TRIGGER_SUBSCRIPTION)
//	public IBaseParameters triggerSubscription(
//		@OperationParam(name = RESOURCE_ID, min = 0, max = OperationParam.MAX_UNLIMITED) List<UriParam> theResourceIds,
//		@OperationParam(name = SEARCH_URL, min = 0, max = OperationParam.MAX_UNLIMITED) List<StringParam> theSearchUrls
//	) {
//		return mySubscriptionTriggeringSvc.triggerSubscription(theResourceIds, theSearchUrls, null);
//	}
//
//	@Operation(name = JpaConstants.OPERATION_TRIGGER_SUBSCRIPTION)
//	public IBaseParameters triggerSubscription(
//		@IdParam IIdType theSubscriptionId,
//		@OperationParam(name = RESOURCE_ID, min = 0, max = OperationParam.MAX_UNLIMITED) List<UriParam> theResourceIds,
//		@OperationParam(name = SEARCH_URL, min = 0, max = OperationParam.MAX_UNLIMITED) List<StringParam> theSearchUrls
//	) {
//		return mySubscriptionTriggeringSvc.triggerSubscription(theResourceIds, theSearchUrls, theSubscriptionId);
//	}

//	@Create
	@Operation(name="$submit", idempotent=true)
	public ClaimResponse claimSubmit(
			@OperationParam(name = "claim", min = 1, max = 1, type = Bundle.class) Bundle bundle
		){
		ClaimResponse retVal = new ClaimResponse();
		retVal.setId(new IdType("ClaimResponse", "3746", "1"));
//		ClaimResponse claimRes = new ClaimResponse();
		
		try {
//			JSONObject reqJson = new JSONObject(theRawBody);
//			System.out.println("\n Request  Body \n");
			
//			System.out.println(bundle);
			String basePathOfClass = getClass().getProtectionDomain().getCodeSource().getLocation().getFile();
//			System.out.println(basePathOfClass);
			String[] splitPath = basePathOfClass.split("/target/classes");
//			System.out.println(splitPath);
			if(splitPath.length > 1) {
//		    	  File filesDirectory = new File(splitPath[0]+"src/main/jib/smartAppFhirArtifacts");
		    	  JSONParser parser = new JSONParser();
		    	  Object obj = parser.parse(new FileReader(splitPath[0]+"/src/main/java/org/opencds/cqf/r4/config/claim.json"));
		    	  org.json.simple.JSONObject fileObj = (org.json.simple.JSONObject)obj;
//		    	  System.out.println("Claim JSON");
//		    	  System.out.println(fileObj.toString());
		    	  String jsonStr = fileObj.toString();
		    	  StringBuilder sb = new StringBuilder();
		    	  URL url = new URL("http://cdex.mettles.com:5000/xmlx12");
		    	  byte[] postDataBytes = jsonStr.getBytes("UTF-8");
		    	  HttpURLConnection conn = (HttpURLConnection)url.openConnection();
			      conn.setRequestMethod("POST");
		          conn.setRequestProperty("Content-Type", "application/json");
		          conn.setRequestProperty("Accept","application/json");
		          conn.setDoOutput(true);
		          conn.getOutputStream().write(postDataBytes);
		          BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
		          String line =null;
		          while((line=in.readLine())!= null){
		            sb.append(line);
		          }
		          String result = sb.toString();
		          JSONObject response = new JSONObject(result);
		          
		          if(response.has("x12_response")) {
		        	  String x12_generated = response.getString("x12_response");
		        	  System.out.println("----------X12 Generated---------");
		        	  System.out.println(x12_generated);
		        	  
		        	  ClaimResponse.NoteComponent note = new ClaimResponse.NoteComponent();
		        	  note.setText(x12_generated.replace("\n", "").replace("\r", ""));
		        	  List<ClaimResponse.NoteComponent> theProcessNote = new ArrayList<ClaimResponse.NoteComponent>();
		        	  theProcessNote.add(note);
		        	  //retVal.setProcessNote(theProcessNote);
		        	  
		          }
//		          System.out.println("Output");
//		          System.out.println(result);
//		          
		        
		    	  
			}
//		   retVal.addName().addGiven(reqJson.get("name").toString());
		   // Populate bundle with matching resources
		   
		}
		catch(Exception ex) {
			ex.printStackTrace();
		}
		return retVal;
	}


//	@Override
//	public Class<? extends IBaseResource> getResourceType() {
//		System.out.println( "\n /n solo level \n /n"); 
//		return myFhirContext.getResourceDefinition(ResourceTypeEnum.SUBSCRIPTION.getCode()).getImplementingClass();
//	}

	@Override
	public Class<Claim> getResourceType() {
		
		System.out.println("\n PAtient GET \n");
		
		return Claim.class;
	}
}