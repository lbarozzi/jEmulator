package it.jemulator;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProtocolProcessor {
    private static final Pattern CHANNEL = Pattern.compile("(in|an|out|pwm)(\\d+)");

    private final DeviceModel device;

    public ProtocolProcessor(DeviceModel device) {
        this.device = device;
    }

    public String process(String line) {
        if (line.isBlank()) {
            return "ERR empty command";
        }
        String[] parts = line.trim().split("\\s+");

        return switch (parts[0]) {
            case "get" -> get(parts);
            case "set" -> set(parts);
            default -> "ERR unknown command";
        };
    }

    private String get(String[] parts) {
        if (parts.length != 2) {
            return "ERR expected: get channel";
        }

        Channel channel = parseChannel(parts[1]);
        if (channel == null) {
            return "ERR unknown channel";
        }

        return switch (channel.type()) {
            case "in" -> channel.index() < 4
                    ? Integer.toString(device.getDigitalInput(channel.index())) : "ERR unknown channel";
            case "an" -> channel.index() < 4
                    ? Integer.toString(device.getAnalogInput(channel.index())) : "ERR unknown channel";
            case "out" -> channel.index() < 4
                    ? Integer.toString(device.getDigitalOutput(channel.index())) : "ERR unknown channel";
            case "pwm" -> channel.index() < 2
                    ? Integer.toString(device.getPwmOutput(channel.index())) : "ERR unknown channel";
            default -> "ERR unknown channel";
        };
    }

    private String set(String[] parts) {
        if (parts.length != 3) {
            return "ERR expected: set channel value";
        }

        Channel channel = parseChannel(parts[1]);
        if (channel == null) {
            return "ERR unknown channel";
        }
        int maxIndex = switch (channel.type()) {
            case "in", "an", "out" -> 3;
            case "pwm" -> 1;
            default -> -1;
        };
        if (channel.index() > maxIndex) {
            return "ERR unknown channel";
        }

        final int value;
        try {
            value = Integer.parseInt(parts[2]);
        } catch (NumberFormatException exception) {
            return "ERR invalid value";
        }

        return switch (channel.type()) {
            case "out" -> setDigitalOutput(channel.index(), value);
            case "pwm" -> setPwmOutput(channel.index(), value);
            default -> "ERR channel is read-only";
        };
    }

    private String setDigitalOutput(int index, int value) {
        if (index >= 4) {
            return "ERR unknown channel";
        }
        if (value < 0 || value > 1) {
            return "ERR invalid value";
        }
        device.setDigitalOutput(index, value);
        return Integer.toString(value);
    }

    private String setPwmOutput(int index, int value) {
        if (index >= 2) {
            return "ERR unknown channel";
        }
        if (value < 0 || value > 100) {
            return "ERR invalid value";
        }
        device.setPwmOutput(index, value);
        return Integer.toString(value);
    }

    private static Channel parseChannel(String name) {
        Matcher matcher = CHANNEL.matcher(name.toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new Channel(matcher.group(1), Integer.parseInt(matcher.group(2)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record Channel(String type, int index) {
    }
}
