package it.jemulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolProcessorTest {
    private final DeviceModel device = new DeviceModel();
    private final ProtocolProcessor processor = new ProtocolProcessor(device);

    @Test
    void readsDigitalAndAnalogInputs() {
        assertEquals("0", processor.process("get in0"));
        assertEquals("2046", processor.process("get an0"));
        device.setAnalogInput(0, 4095);
        assertEquals("4095", processor.process("get an0"));
    }

    @Test
    void setsAndReadsOutputs() {
        assertEquals("1", processor.process("set out1 1"));
        assertEquals("1", processor.process("get out1"));
        assertEquals("75", processor.process("set pwm0 75"));
        assertEquals("75", processor.process("get pwm0"));
    }

    @Test
    void reportsInvalidCommandsAndValues() {
        assertEquals("ERR unknown command", processor.process("hello"));
        assertEquals("ERR unknown channel", processor.process("get in4"));
        assertEquals("ERR unknown channel", processor.process("set out4 1"));
        assertEquals("ERR invalid value", processor.process("set out0 2"));
        assertEquals("ERR invalid value", processor.process("set pwm0 101"));
        assertEquals("ERR channel is read-only", processor.process("set an0 10"));
        assertEquals("ERR empty command", processor.process("   "));
    }

    @Test
    void acceptsRepeatedWhitespace() {
        assertEquals("2046", processor.process("  get   an0  "));
    }
}
