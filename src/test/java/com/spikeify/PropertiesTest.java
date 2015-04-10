package com.spikeify;

import org.junit.Assert;
import org.junit.Test;

import java.util.Date;
import java.util.Map;

public class PropertiesTest {

	@Test
	public void loadProps() {

		EntityOne entityLoad = new EntityOne();
		entityLoad.one = 123;
		entityLoad.two = "a test";
		entityLoad.three = 123.0d;
		entityLoad.four = 123.0f;
		entityLoad.setFive((short) 234);
		entityLoad.setSix((byte) 100);
		entityLoad.ignored = "should be ignored";
		entityLoad.eight = new Date(1420070400);

		ClassMapper<EntityOne> mapper = new ClassMapper<>(EntityOne.class);
		Map<String, Object> props = mapper.getProperties(entityLoad);

		// database saves & returns everything as long, string or byte array
		// we need to convert accordingly
		Assert.assertEquals(123, ((Long) props.get("one")).intValue());
		Assert.assertEquals("a test", props.get("two"));
		Assert.assertEquals(123.0d, Double.longBitsToDouble((long) props.get("three")), 0.1);
		Assert.assertEquals(123.0f, (float) Double.longBitsToDouble((long) props.get("four")), 0.1);
		Assert.assertEquals((short) 234, Long.valueOf((long) props.get("five")).shortValue());
		Assert.assertEquals((byte) 100, Long.valueOf((long) props.get("six")).byteValue());
		Assert.assertEquals(1420070400, (long) props.get("eight"));
		Assert.assertFalse(props.containsKey("ignore"));

		EntityOne entitySave = new EntityOne();
		mapper.setFieldValues(entitySave, props);

		// ignored field was not saved
		Assert.assertNull(entitySave.ignored);

		// set ignored field and compare with original
		entitySave.ignored = "should be ignored";
		Assert.assertEquals(entityLoad, entitySave);
	}
}
