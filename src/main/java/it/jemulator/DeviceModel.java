package it.jemulator;

import java.util.concurrent.atomic.AtomicIntegerArray;

public final class DeviceModel {
    private final AtomicIntegerArray analogInputs = new AtomicIntegerArray(4);
    private final AtomicIntegerArray digitalInputs = new AtomicIntegerArray(4);
    private final AtomicIntegerArray digitalOutputs = new AtomicIntegerArray(4);
    private final AtomicIntegerArray pwmOutputs = new AtomicIntegerArray(2);

    public DeviceModel() {
        for (int i = 0; i < analogInputs.length(); i++) {
            analogInputs.set(i, 2046);
        }
    }

    public int getAnalogInput(int index) {
        return analogInputs.get(index);
    }

    public void setAnalogInput(int index, int value) {
        analogInputs.set(index, requireRange(index, 4, value, 0, 4095));
    }

    public int getDigitalInput(int index) {
        return digitalInputs.get(index);
    }

    public void setDigitalInput(int index, int value) {
        digitalInputs.set(index, requireRange(index, 4, value, 0, 1));
    }

    public int getDigitalOutput(int index) {
        return digitalOutputs.get(index);
    }

    public void setDigitalOutput(int index, int value) {
        digitalOutputs.set(index, requireRange(index, 4, value, 0, 1));
    }

    public int getPwmOutput(int index) {
        return pwmOutputs.get(index);
    }

    public void setPwmOutput(int index, int value) {
        pwmOutputs.set(index, requireRange(index, 2, value, 0, 100));
    }

    private static int requireRange(int index, int channelCount, int value, int min, int max) {
        if (index < 0 || index >= channelCount) {
            throw new IndexOutOfBoundsException("Invalid channel index: " + index);
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("Value must be between " + min + " and " + max);
        }
        return value;
    }
}
