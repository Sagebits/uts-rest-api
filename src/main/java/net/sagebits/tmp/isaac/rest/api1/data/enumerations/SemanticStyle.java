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
package net.sagebits.tmp.isaac.rest.api1.data.enumerations;

import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * {@link SemanticStyle}
 * An enumeration that tells you the general style of a semantic assemblage definition.
 * {@link #PROPERTY} Generic semantic assemblage which contains data columns, and isn't the more specific mapset or association styles.  Available 
 *   with the semantic APIS.
 * {@link #REFSET} Generic semantic assemblage which contains 0 data columns.  Available with the semantic APIs.
 * {@link #MAPSET} A specific semantic assemblage that contains data columns, in a pattern defined as a mapset.  Accessible with the mapping APIs, 
 *   or the generic semantic APIs.
 * {@link #ASSOCIATION} A specific semantic assemblage that contains data columns, in a pattern defined as a association.  Accessible with the association APIs, 
 *   or the generic semantic APIs.
 * extended types.
 * 
 * NONE is for internal use only
 * 
 * @author <a href="mailto:daniel.armbrust.list@gmail.com">Dan Armbrust</a>
 */
public enum SemanticStyle
{
	PROPERTY("property"), REFSET("refset"), MAPSET("mapset"), ASSOCIATION("association"), NONE("none");

	private String displayName_;

	private SemanticStyle(String displayName)
	{
		displayName_ = displayName;
	}

	public String getDisplayName()
	{
		return displayName_;
	}

	public static Optional<IdType> parse(String input)
	{
		if (StringUtils.isNotBlank(input))
		{
			String trimmed = input.trim();
			for (IdType idt : IdType.values())
			{
				if (("" + idt.ordinal()).equals(trimmed) || idt.name().equalsIgnoreCase(trimmed) || idt.getDisplayName().equalsIgnoreCase(trimmed))
				{
					return Optional.of(idt);
				}
			}
		}
		return Optional.empty();
	}
}
