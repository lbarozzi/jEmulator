package it.jemulator;

/** Entry point for the fat jar: avoids the JavaFX "runtime components are missing" check. */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        JEmulatorApp.main(args);
    }
}
