package com.spikeify.converters;

import com.spikeify.Converter;
import com.spikeify.ConverterFactory;

public class DoubleConverter implements Converter<Double, Long>, ConverterFactory {

	@Override
	public Converter init(Class type) {
		return this;
	}

	public boolean canConvert(Class type) {
		return Double.class.isAssignableFrom(type) || double.class.isAssignableFrom(type);
	}

	public Double fromProperty(Long property) {
		return Double.longBitsToDouble(property);
	}

	public Long fromField(Double fieldValue) {
		return Double.doubleToLongBits(fieldValue);
	}

}
