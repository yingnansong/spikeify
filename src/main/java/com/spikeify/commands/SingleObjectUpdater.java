package com.spikeify.commands;

import com.aerospike.client.*;
import com.aerospike.client.async.IAsyncClient;
import com.aerospike.client.policy.GenerationPolicy;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.WritePolicy;
import com.spikeify.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A command chain for creating or updating a single object in database.
 * This class is not intended to be instantiated by user.
 *
 * @param <T>
 */
@SuppressWarnings({"unchecked", "WeakerAccess"})
public class SingleObjectUpdater<T> {

	private final T object;
	private boolean forceReplace = false;

	static final int MAX_CREATE_GENERATE_RETRIES = 5;

	/**
	 * Used internally to create a command chain. Not intended to be used by the user directly.
	 * Instead use {@link Spikeify#update(Key, Object)} or similar method.
	 */
	public SingleObjectUpdater(boolean isTx, Class type, IAerospikeClient synClient, IAsyncClient asyncClient,
							   RecordsCache recordsCache, boolean create, String defaultNamespace, T object) {

		this.isTx = isTx;
		this.synClient = synClient;
		this.asyncClient = asyncClient;
		this.recordsCache = recordsCache;
		this.create = create;
		this.defaultNamespace = defaultNamespace;
		this.policy = new WritePolicy();
		this.mapper = MapperService.getMapper(type);
		this.object = object;
	}

	protected final String defaultNamespace;
	protected String setName;
	private final boolean isTx;
	protected final IAerospikeClient synClient;
	protected final IAsyncClient asyncClient;
	protected final RecordsCache recordsCache;
	protected final boolean create;
	protected WritePolicy policy;
	protected final ClassMapper<T> mapper;


	/**
	 * Sets the {@link WritePolicy} to be used when creating or updating the record in the database.
	 * Internally the 'sendKey' property of the policy will always be set to true.
	 * If this method is called within .transact() method then the 'generationPolicy' property will be set to GenerationPolicy.EXPECT_GEN_EQUAL
	 * The 'recordExistsAction' property is set accordingly depending if this is a create or update operation
	 *
	 * @param policy The policy.
	 */
	public SingleObjectUpdater<T> policy(WritePolicy policy) {
		this.policy = policy;
		return this;
	}

	/**
	 * Sets updater to skip cache check for object changes. This causes that all
	 * object properties will be written to database. It also deletes previous saved
	 * properties in database and now not mapped to object.
	 */
	public SingleObjectUpdater<T> forceReplace() {
		this.forceReplace = true;
		return this;
	}

	protected static Key collectKey(Object obj, String namespace) {

		// get metadata for object
		ObjectMetadata meta = MapperService.getMapper(obj.getClass()).getRequiredMetadata(obj, namespace);
		if (meta.userKeyString != null) {
			return new Key(meta.namespace, meta.setName, meta.userKeyString);
		}
		else {
			return new Key(meta.namespace, meta.setName, meta.userKeyLong);
		}
	}

	/**
	 * Synchronously executes a single create or update command and returns the key of the record.
	 *
	 * @return The key of the record.
	 */
	public Key now() {
		// this should be a one-key operation
		// if multiple keys - use the first key

		if (object == null) {
			throw new SpikeifyError("Error: parameter 'objects' must not be null or empty array");
		}

		boolean generatedId = create && IdGenerator.shouldGenerateId(object);
		if (generatedId) {
			IdGenerator.generateId(object);
		}

		Key key = collectKey(object, defaultNamespace);

		this.policy.recordExistsAction = create ? RecordExistsAction.CREATE_ONLY : forceReplace ? RecordExistsAction.REPLACE : RecordExistsAction.UPDATE;
		boolean isReplace = this.policy.recordExistsAction == RecordExistsAction.REPLACE;

		Map<String, Object> props = mapper.getProperties(object);
		Set<String> changedProps = recordsCache.update(key, props, forceReplace);

		List<Bin> bins = new ArrayList<>();
		boolean nonNullField = false;
		for (String propName : changedProps) {
			Object value = props.get(propName);
			if (value == null) {
				if (!isReplace) {
					bins.add(Bin.asNull(propName));
				}
			} else if (value instanceof List<?>) {
				bins.add(new Bin(propName, (List) value));
				nonNullField = true;
			} else if (value instanceof Map<?, ?>) {
				bins.add(new Bin(propName, (Map) value));
				nonNullField = true;
			} else {
				bins.add(new Bin(propName, value));
				nonNullField = true;
			}
		}

		// must be set so that user key can be retrieved in queries
		this.policy.sendKey = true;


		if (!nonNullField && props.size() == changedProps.size()) {
			throw new SpikeifyError("Error: cannot create object with no writable properties. " +
					"At least one object property other then UserKey must be different from NULL.");
		}

		Integer recordExpiration = mapper.getRecordExpiration(object);
		if (recordExpiration != null) {
			policy.expiration = recordExpiration;
		}

		if (isTx) {
			Integer generation = mapper.getGeneration(object);
			policy.generationPolicy = GenerationPolicy.EXPECT_GEN_EQUAL;
			if (generation != null) {
				policy.generation = generation;
			} else {
				throw new SpikeifyError("Error: missing @Generation field in class " + object.getClass() +
						". When using transact(..) you must have @Generation annotation on a field in the entity class.");
			}
		}

		if (generatedId) {
			// retry 5 times in case same id is generated ...
			for (int count = 1; count <= SingleObjectUpdater.MAX_CREATE_GENERATE_RETRIES; count++) {
				try {
					synClient.put(policy, key, bins.toArray(new Bin[bins.size()]));
					break;
				}
				catch (AerospikeException e) {
					// let's retry or not ?
					if (e.getResultCode() != ResultCode.KEY_EXISTS_ERROR ||
						SingleObjectUpdater.MAX_CREATE_GENERATE_RETRIES == count) {
						throw e;
					}
					// regenerate key ...
					IdGenerator.generateId(object);
					key = SingleObjectUpdater.collectKey(object, defaultNamespace);
				}
			}
		}
		else {
			synClient.put(policy, key, bins.toArray(new Bin[bins.size()]));
		}

		// set LDT fields
		mapper.setBigDatatypeFields(object, synClient, key);

		return key;
	}
}
