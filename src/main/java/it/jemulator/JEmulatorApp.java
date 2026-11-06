package it.jemulator;

import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Path;

public final class JEmulatorApp extends Application {
    private final DeviceModel device = new DeviceModel();
    private final ProtocolProcessor processor = new ProtocolProcessor(device);
    private final Label status = new Label("Fermato");
    private final TextArea log = new TextArea();
    private final ToggleButton[] outputButtons = new ToggleButton[4];
    private final Slider[] pwmSliders = new Slider[2];
    private final Label[] pwmValues = new Label[2];
    private final TextField fifoPath = new TextField(FifoServer.defaultEndpointBase().toString());
    private FifoServer server;

    @Override
    public void start(Stage stage) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(22));
        root.setStyle("-fx-background-color: #f3f5f8;");

        VBox heading = new VBox(4,
                styledLabel("jEmulator", 25, "#15243a", true),
                styledLabel("Simulatore I/O con protocollo testuale su endpoint bidirezionali", 13, "#62738a", false));
        root.setTop(heading);

        VBox content = new VBox(16, createConnectionPanel(), createDevicePanel(), createLogPanel());
        content.setPadding(new Insets(20, 0, 0, 0));
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        root.setCenter(scrollPane);

        Scene scene = new Scene(root, 920, 820);
        stage.setTitle("jEmulator - FIFO Device");
        stage.setMinWidth(720);
        stage.setMinHeight(650);
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> stopServer());

        Timeline refresh = new Timeline(new javafx.animation.KeyFrame(Duration.millis(100), event -> refreshOutputs()));
        refresh.setCycleCount(Timeline.INDEFINITE);
        refresh.play();
        stage.show();
    }

    private VBox createConnectionPanel() {
        VBox panel = panel("Connessione endpoint");
        GridPane fields = new GridPane();
        fields.setHgap(10);
        fields.setVgap(8);
        fields.add(styledLabel("Base endpoint", 12, "#62738a", false), 0, 0);
        fields.add(fifoPath, 1, 0);
        GridPane.setHgrow(fifoPath, Priority.ALWAYS);
        fifoPath.setPrefColumnCount(32);

        Button start = new Button("Avvia");
        start.setDefaultButton(true);
        start.setOnAction(event -> startServer());
        Button stop = new Button("Ferma");
        stop.setOnAction(event -> stopServer());
        HBox controls = new HBox(10, start, stop, status);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(4, 0, 0, 0));
        panel.getChildren().addAll(fields, controls);
        return panel;
    }

    private VBox createDevicePanel() {
        VBox panel = panel("Device I/O");
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);

        for (int i = 0; i < 4; i++) {
            grid.add(createAnalogControl(i), i % 2, i / 2);
            grid.add(createDigitalInput(i), (i % 2) + 2, i / 2);
        }
        for (int i = 0; i < 4; i++) {
            int outputIndex = i;
            ToggleButton button = new ToggleButton();
            outputButtons[i] = button;
            button.setMinWidth(110);
            button.setOnAction(event -> device.setDigitalOutput(
                    outputIndex, button.isSelected() ? 1 : 0));
            styleOutput(button, 0);
            VBox card = smallCard("Uscita digitale out" + i, button);
            grid.add(card, i % 4, 2);
        }
        for (int i = 0; i < 2; i++) {
            grid.add(createPwmControl(i), i, 3);
        }
        panel.getChildren().add(grid);
        return panel;
    }

    private VBox createAnalogControl(int index) {
        Slider slider = new Slider(0, 4095, device.getAnalogInput(index));
        slider.setShowTickMarks(true);
        slider.setMajorTickUnit(1024);
        slider.setBlockIncrement(1);
        Label value = styledLabel(Integer.toString(device.getAnalogInput(index)), 14, "#1976d2", true);
        slider.valueProperty().addListener((observable, oldValue, newValue) -> {
            int reading = newValue.intValue();
            device.setAnalogInput(index, reading);
            value.setText(Integer.toString(reading));
        });
        return smallCard("Potenziometro an" + index, slider, value);
    }

    private VBox createDigitalInput(int index) {
        CheckBox switchControl = new CheckBox("Attivo");
        switchControl.selectedProperty().addListener((observable, oldValue, selected) ->
                device.setDigitalInput(index, selected ? 1 : 0));
        return smallCard("Switch in" + index, switchControl);
    }

    private VBox createPwmControl(int index) {
        Slider slider = new Slider(0, 100, device.getPwmOutput(index));
        pwmSliders[index] = slider;
        slider.setShowTickMarks(true);
        slider.setShowTickLabels(true);
        slider.setMajorTickUnit(25);
        slider.setMinorTickCount(24);
        slider.setSnapToTicks(true);
        slider.setBlockIncrement(1);
        Label value = styledLabel(device.getPwmOutput(index) + "%", 14, "#1976d2", true);
        pwmValues[index] = value;
        slider.valueProperty().addListener((observable, oldValue, newValue) -> {
            int dutyCycle = newValue.intValue();
            device.setPwmOutput(index, dutyCycle);
            value.setText(dutyCycle + "%");
        });
        return smallCard("PWM pwm" + index, slider, value);
    }

    private VBox createLogPanel() {
        VBox panel = panel("Traffico protocollo");
        log.setEditable(false);
        log.setWrapText(true);
        log.setPrefRowCount(5);
        panel.getChildren().add(log);
        return panel;
    }

    private void startServer() {
        if (server != null) {
            return;
        }
        try {
            server = new FifoServer(
                    Path.of(fifoPath.getText().trim()),
                    processor,
                    this::appendLog,
                    this::setStatus);
            server.start();
            fifoPath.setDisable(true);
            appendLog("Endpoint pronti: " + server.endpoints());
        } catch (IOException | RuntimeException exception) {
            server = null;
            setStatus("Errore: " + exception.getMessage());
            appendLog("ERRORE: " + exception.getMessage());
        }
    }

    private void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
        fifoPath.setDisable(false);
    }

    private void refreshOutputs() {
        for (int i = 0; i < outputButtons.length; i++) {
            int value = device.getDigitalOutput(i);
            ToggleButton button = outputButtons[i];
            if (button != null && button.isSelected() != (value == 1)) {
                button.setSelected(value == 1);
            }
            if (button != null) {
                styleOutput(button, value);
            }
        }
        for (int i = 0; i < pwmValues.length; i++) {
            int value = device.getPwmOutput(i);
            Slider slider = pwmSliders[i];
            if (slider != null && (int) slider.getValue() != value) {
                slider.setValue(value);
            }
            if (pwmValues[i] != null) {
                pwmValues[i].setText(value + "%");
            }
        }
    }

    private static void styleOutput(ToggleButton button, int value) {
        button.setText("LED " + (value == 1 ? "ON" : "OFF"));
        button.setStyle(value == 1
                ? "-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;"
                : "-fx-background-color: #dfe5ec; -fx-text-fill: #34495e;");
    }

    private void appendLog(String line) {
        Platform.runLater(() -> {
            log.appendText(line + System.lineSeparator());
            if (log.getLength() > 20000) {
                log.deleteText(0, log.getLength() - 15000);
            }
        });
    }

    private void setStatus(String message) {
        Platform.runLater(() -> status.setText(message));
    }

    private static VBox panel(String title) {
        VBox box = new VBox(12);
        box.setPadding(new Insets(14));
        box.setStyle("-fx-background-color: white; -fx-background-radius: 10;"
                + "-fx-border-color: #e2e8f0; -fx-border-radius: 10;");
        box.getChildren().add(styledLabel(title, 15, "#24364b", true));
        return box;
    }

    private static VBox smallCard(String title, javafx.scene.Node... content) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        box.setMinWidth(180);
        box.setStyle("-fx-background-color: #f7f9fc; -fx-background-radius: 8;"
                + "-fx-border-color: #edf0f4; -fx-border-radius: 8;");
        box.getChildren().add(styledLabel(title, 12, "#62738a", true));
        box.getChildren().addAll(content);
        return box;
    }

    private static Label styledLabel(String text, int size, String color, boolean bold) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: " + size + "px; -fx-text-fill: " + color + ";"
                + (bold ? " -fx-font-weight: bold;" : ""));
        return label;
    }

    @Override
    public void stop() {
        stopServer();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
