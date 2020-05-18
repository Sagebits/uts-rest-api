/*
 * Copyright 2018 VetsEZ Inc, Sagebits LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 * Contributions from 2015-2017 where performed either by US government
 * employees, or under US Veterans Health Administration contracts.
 *
 * US Veterans Health Administration contributions by government employees
 * are work of the U.S. Government and are not subject to copyright
 * protection in the United States. Portions contributed by government
 * employees are USGovWork (17USC §105). Not subject to copyright.
 * 
 * Contribution by contractors to the US Veterans Health Administration
 * during this period are contractually contributed under the
 * Apache License, Version 2.0.
 *
 * See: https://www.usa.gov/government-works
 */

package net.sagebits.tmp.isaac.rest.api1.data.coordinate;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import net.sagebits.tmp.isaac.rest.api1.data.concept.RestConceptChronology;
import sh.isaac.api.Get;
import sh.isaac.api.coordinate.EditCoordinate;

/**
 * Carries back the individual parts of the edit coordinate
 */
@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY)
public class RestEditCoordinate
{
	/**
	 * the author that will write this edit
	 */
	@XmlElement
	RestConceptChronology editAuthor;
	/**
	 * module where edits are written
	 */
	@XmlElement
	RestConceptChronology editModule;
	
	/**
	 * path where edits are written
	 */
	@XmlElement
	RestConceptChronology editPath;

	public RestEditCoordinate(EditCoordinate ec)
	{
		editAuthor = new RestConceptChronology(Get.concept(ec.getAuthorNid()), false, false, false);
		editModule = new RestConceptChronology(Get.concept(ec.getModuleNid()), false, false, false);
		editPath = new RestConceptChronology(Get.concept(ec.getPathNid()), false, false, false);
	}

	RestEditCoordinate()
	{
		// For JAXB
	}
}
