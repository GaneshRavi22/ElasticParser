/****
 * 
 * Copyright 2013-2014 Wedjaa <http://www.wedjaa.net/>
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

/**
 * @author Fabio Torchetti
 *
 */

package net.wedjaa.elasticparser.resolver;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.Aggregations;
import org.elasticsearch.search.aggregations.InternalAggregation;

public class AggregateResolver {

	private ClassFinder classFinder;
	
	private static AggregateResolver instance = null;

	private static final Logger logger = Logger.getLogger(AggregateResolver.class);

	protected AggregateResolver() {
		this.classFinder = new ClassFinder();
	}

	public static AggregateResolver getInstance() {

		if (instance == null) {
			instance = new AggregateResolver();
		}

		return instance;

	}

	private Class<?> getAggregationClass(InternalAggregation aggregation) {
		return aggregation.getClass();
	}

	@SuppressWarnings("unchecked")
	private List<Class<?>> getBuckets(InternalAggregation aggregation) {

		List<Class<?>> buckets = null;

		Class<?> aggregationClass = getAggregationClass(aggregation);

		if (aggregationClass == null) {
			logger.warn("Can't get entries for unknown aggregation type: "
					+ aggregation.type().name());
			return null;
		}

		Method getBucketsMethod = classFinder.getMethod("getBuckets", aggregationClass);
		if (getBucketsMethod == null) {
			logger.warn("Seems that " + aggregationClass.getCanonicalName()
					+ " has no getBuckets method. Funny you should call it.");
			return null;
		}

		try {
			buckets = (List<Class<?>>) getBucketsMethod.invoke(aggregation);
			if (buckets.size() > 0) {
				logger.debug("Returning " + buckets.size() + " buckets.");
			}
		} catch (IllegalAccessException e) {
			logger.warn("Failed to get entries on facet of type "
					+ aggregation.getName() + ": " + e.getLocalizedMessage());
		} catch (IllegalArgumentException e) {
			logger.warn("Failed to get entries on facet of type "
					+ aggregation.getName() + ": " + e.getLocalizedMessage());
		} catch (InvocationTargetException e) {
			logger.warn("Failed to get entries on facet of type "
					+ aggregation.getName() + ": " + e.getLocalizedMessage());
		}

		return buckets;
	}



	private Map<String, Object> createBucketsMap(
			InternalAggregation aggregation, Object bucket,
			Class<?> bucketClass, String parentAggregation) {

		Map<String, Object> result = new HashMap<String, Object>();

		logger.debug("entry[" + parentAggregation + "] = " + aggregation.getName());
		result.put(parentAggregation, aggregation.getName());

		List<Method> bucketMethods = classFinder.getClassMethods(bucketClass);

		for (Method method : bucketMethods) {
			if (method.getName().startsWith("get")) {
				String key = method.getName().substring(3);
				if (!key.equals("Class") && !key.equals("Aggregations")) {
					try {
						Object value = method.invoke(bucket);
						if (value != null && !value.toString().equals("NaN")) {
							logger.debug("   entry["  + key + "] = " + value);
							result.put( key , value);
						} else {
							// We need to fill in missing data in aggregations
							// or Jasper will choke on it at times.
							if ( method.getReturnType().getCanonicalName().equals(String.class.getCanonicalName()) ) {
								// Fill in the String
								result.put(key, "");
							} else {
								result.put(key, 0.0);
							}
						}
					} catch (IllegalAccessException e) {
						logger.warn("Failed to execute method "
								+ method.getName() + " on entry: " + bucket);
					} catch (IllegalArgumentException e) {
						logger.warn("Failed to execute method "
								+ method.getName() + " on entry: " + bucket);
					} catch (InvocationTargetException e) {
						logger.warn("Failed to execute method "
								+ method.getName() + " on entry: " + bucket);
					}
				}
			}
		}

		return result;
	}

	public List<Map<String, Object>> unrollSimpleAggregation(InternalAggregation aggregation, String parentAggregation) {
		if ( parentAggregation == null ) {
			parentAggregation = "";
		} 

		logger.trace("unrollSimple: " +aggregation.getName() + "; Parent: " + parentAggregation);
		Class<?> aggClass = aggregation.getClass();
		List<Map<String, Object>> subValues = null;
		if ( classFinder.hasMethod("getAggregations", aggClass) ) {
			logger.trace("This is a single bucket aggregation of some sort....");
			Method getAggregationsMethod = classFinder.getMethod("getAggregations", aggClass);
			if ( getAggregationsMethod != null ) {
				try {
					Aggregations subAggregations = (Aggregations) getAggregationsMethod.invoke(aggregation);
					subValues = explode(subAggregations, aggregation.getName() );
				} catch (IllegalArgumentException e) {
					logger.debug("Illegal argument exception calling getAggregations on " + aggClass);
				} catch (IllegalAccessException e) {
					logger.debug("Illegal access exception calling getAggregations on " + aggClass);
				} catch (InvocationTargetException e) {
					logger.debug("Illegal Invocation Targer exception calling getAggregations on " + aggClass);
				}
			}
		}
		List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
		Class<?> aggregationClass = getAggregationClass(aggregation);
		result.add(createBucketsMap(aggregation, aggregation, aggregationClass, parentAggregation));
		if ( subValues != null ) {
			logger.debug("Extending simple " + aggregation.getName() + " with contained aggregations.");
			Iterator<Map<String, Object>> subIter = subValues.iterator();
			while ( subIter.hasNext() ) {
				Map<String, Object> subMap = subIter.next();
				Iterator<Map<String,Object>> resIter = result.iterator();
				while ( resIter.hasNext() ) {
					Map<String,Object> resultMap = resIter.next();
					Iterator<String> resKeyIter = resultMap.keySet().iterator(); 
					while ( resKeyIter.hasNext() ) {
						String resKey = resKeyIter.next();
						// Don't overwrite inner values
						if ( !subMap.containsKey(resKey) ) {
							subMap.put(resKey, resultMap.get(resKey));
						}
					}
				}
			}
			result = subValues;
		}
		logger.debug("Simple Returning: " + result);
		return result;
	}

	public List<Map<String, Object>> unrollAggregationBuckets(InternalAggregation aggregation, String parentAggregation) {

		List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();

		if ( parentAggregation == null ) {
			parentAggregation = aggregation.getName();
		}
		
		logger.debug("unrollBucket: " +aggregation.getName() + "; Parent: " + parentAggregation);

		List<Class<?>> buckets = getBuckets(aggregation);

		if (buckets != null) {

			for (Object bucket : buckets) {
				List<Map<String, Object>> bucketAggsValues = null;
				logger.debug("Bucket Class: " + bucket.getClass());
				Method getAggregationsMethod = classFinder.getMethod("getAggregations", bucket.getClass());
				if ( getAggregationsMethod != null ) {
					try {
						Aggregations bucketAggregations = (Aggregations) getAggregationsMethod.invoke(bucket);
						bucketAggsValues = explode(bucketAggregations, aggregation.getName());
					} catch (IllegalArgumentException e) {
					} catch (IllegalAccessException e) {
					} catch (InvocationTargetException e) {
					}
				}
				Map<String, Object> entryMap = createBucketsMap(aggregation, bucket, bucket.getClass(), parentAggregation);
				if ( bucketAggsValues != null 
						&& bucketAggsValues.size() > 0 ) {
					Iterator<Map<String, Object>> subIter = bucketAggsValues.iterator();
					while ( subIter.hasNext() ) {
						Map<String, Object> subMap = subIter.next();
						subMap.putAll(entryMap);
						result.add(subMap);
					}
				} else {
					result.add(entryMap);	
				}
			}
		}
		logger.debug("Unrolled Returning: " + result);

		return result;
	}

	public List<Map<String, Object>> unrollAggregation(InternalAggregation aggregation, String parentAggregation) {

		List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();

		Class<?> aggregationClass = aggregation.getClass();

		if (aggregationClass == null) {
			logger.warn("Failed to get aggregation class for type: "
					+ aggregation.type().name());
			return result;
		}

		logger.trace("Class for aggregation '" + aggregation.getName() + "': "
				+ aggregationClass.getCanonicalName());
		
		if ( classFinder.getClassMethods(aggregationClass) == null ) {
			logger.warn("Failed to get methods for aggregation class "
					+ aggregationClass.getCanonicalName());
			return result;
		}

		if (classFinder.hasMethod("getBuckets", aggregationClass)) {
			logger.trace("Is a bucket type of aggregation, unrolling it");
			return unrollAggregationBuckets(aggregation, parentAggregation);

		}
		logger.trace("Is a single aggregation - like statistics");
		return unrollSimpleAggregation(aggregation, parentAggregation);
	}

	private List<Map<String, Object>> explode(Aggregations aggregations, String parentAggregation) {
		return explode(aggregations.asMap(), parentAggregation);
	}

	public List<Map<String, Object>> explode(Aggregations aggregations) {
		return explode(aggregations.asMap(), "Aggregation");
	}

	public List<Map<String, Object>> explode(Map<String, Aggregation> aggregations) {
		return  explode(aggregations, "Aggregation");
	}
	
	private List<Map<String, Object>> explode(Map<String, Aggregation> aggregations, String parentAggregation) {

		List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();

		logger.debug("exploder - Parent: " + parentAggregation);

		Set<String> aggregation_names = aggregations.keySet();
		for (String aggregation_name : aggregation_names) {
			entries.addAll(unrollAggregation((InternalAggregation) aggregations.get(aggregation_name), parentAggregation));
		}

		return entries;
	}


}
