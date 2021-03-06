package com.rusefi.binaryprotocol;

import com.opensr5.ConfigurationImage;
import com.opensr5.Logger;
import com.opensr5.io.ConfigurationImageFile;
import com.opensr5.io.DataListener;
import com.rusefi.ConfigurationImageDiff;
import com.rusefi.FileLog;
import com.rusefi.Timeouts;
import com.rusefi.composite.CompositeEvent;
import com.rusefi.composite.CompositeParser;
import com.rusefi.config.generated.Fields;
import com.rusefi.core.Pair;
import com.rusefi.core.Sensor;
import com.rusefi.core.SensorCentral;
import com.rusefi.io.*;
import com.rusefi.stream.LogicdataStreamFile;
import com.rusefi.stream.StreamFile;
import com.rusefi.stream.TSHighSpeedLog;
import com.rusefi.stream.VcdStreamFile;
import com.rusefi.tune.xml.Msq;
import com.rusefi.ui.livedocs.LiveDocsRegistry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.rusefi.binaryprotocol.IoHelper.*;

/**
 * This object represents logical state of physical connection.
 *
 * Instance is connected until we experience issues. Once we decide to close the connection there is no restart -
 * new instance of this class would need to be created once we establish a new physical connection.
 *
 * Andrey Belomutskiy, (c) 2013-2020
 * 3/6/2015
 * @see BinaryProtocolHolder
 */
public class BinaryProtocol implements BinaryProtocolCommands {

    private static final String USE_PLAIN_PROTOCOL_PROPERTY = "protocol.plain";
    private static final String CONFIGURATION_RUSEFI_BINARY = "current_configuration.rusefi_binary";
    private static final String CONFIGURATION_RUSEFI_XML = "current_configuration.msq";
    private static final int HIGH_RPM_DELAY = Integer.getInteger("high_speed_logger_time", 10);
    /**
     * This properly allows to switch to non-CRC32 mode
     * todo: finish this feature, assuming we even need it.
     */
    public static boolean PLAIN_PROTOCOL = Boolean.getBoolean(USE_PLAIN_PROTOCOL_PROPERTY);
    static {
        FileLog.MAIN.logLine(USE_PLAIN_PROTOCOL_PROPERTY + ": " + PLAIN_PROTOCOL);
    }

    private final Logger logger;
    private final IoStream stream;
    private final IncomingDataBuffer incomingData;
    private boolean isBurnPending;

    // todo: this ioLock needs better documentation!
    private final Object ioLock = new Object();
    private final Object imageLock = new Object();
    private ConfigurationImage controller;

    private static final int COMPOSITE_OFF_RPM = 300;

    /**
     * Composite logging turns off after 10 seconds of RPM above 300
     */
    private boolean needCompositeLogger = true;
    private boolean isCompositeLoggerEnabled;
    private long lastLowRpmTime = System.currentTimeMillis();

    private List<StreamFile> compositeLogs = new ArrayList<>();

    private void createCompositesIfNeeded() {
        if (!compositeLogs.isEmpty())
            return;
        compositeLogs.addAll(Arrays.asList(
                new VcdStreamFile(getFileName("rusEFI_trigger_log_", ".vcd")),
                new LogicdataStreamFile(getFileName("rusEFI_trigger_log_", ".logicdata")),
                new TSHighSpeedLog(getFileName("rusEFI_trigger_log_"))
        ));
    }

    public boolean isClosed;
    /**
     * Snapshot of current gauges status
     * @see BinaryProtocolCommands#COMMAND_OUTPUTS
     */
    public byte[] currentOutputs;
    private SensorCentral.SensorListener rpmListener = value -> {
        if (value <= COMPOSITE_OFF_RPM) {
            needCompositeLogger = true;
            lastLowRpmTime = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - lastLowRpmTime > HIGH_RPM_DELAY * Timeouts.SECOND) {
            FileLog.MAIN.logLine("Time to turn off composite logging");
            needCompositeLogger = false;
        }
    };

    private final Thread hook = new Thread(() -> closeComposites());

    protected BinaryProtocol(final Logger logger, IoStream stream) {
        this.logger = logger;
        this.stream = stream;

        incomingData = createDataBuffer(stream, logger);
        Runtime.getRuntime().addShutdownHook(hook);
    }

    public static IncomingDataBuffer createDataBuffer(IoStream stream, Logger logger) {
        IncomingDataBuffer incomingData = new IncomingDataBuffer(logger);
        stream.setInputListener(incomingData::addData);
        return incomingData;
    }

    public static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @NotNull
    public static String getFileName(String prefix) {
        return getFileName(prefix, ".csv");
    }

    @NotNull
    public static String getFileName(String prefix, String fileType) {
        return FileLog.DIR + prefix + FileLog.getDate() + fileType;
    }

    public void doSend(final String command, boolean fireEvent) throws InterruptedException {
        FileLog.MAIN.logLine("Sending [" + command + "]");
        if (fireEvent && LinkManager.LOG_LEVEL.isDebugEnabled()) {
            CommunicationLoggingHolder.communicationLoggingListener.onPortHolderMessage(BinaryProtocol.class, "Sending [" + command + "]");
        }

        Future f = LinkManager.submit(new Runnable() {
            @Override
            public void run() {
                sendTextCommand(command);
            }

            @Override
            public String toString() {
                return "Runnable for " + command;
            }
        });

        try {
            f.get(Timeouts.COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        } catch (TimeoutException e) {
            getLogger().error("timeout sending [" + command + "] giving up: " + e);
            return;
        }
        /**
         * this here to make CommandQueue happy
         */
        CommandQueue.getInstance().handleConfirmationMessage(CommandQueue.CONFIRMATION_PREFIX + command);
    }

    /**
     * this method would switch controller to binary protocol and read configuration snapshot from controller
     *
     * @return true if everything fine
     */
    public boolean connectAndReadConfiguration(DataListener listener) {
//        switchToBinaryProtocol();
        readImage(Fields.TOTAL_CONFIG_SIZE);
        if (isClosed)
            return false;

        startTextPullThread(listener);
        SensorCentral.getInstance().addListener(Sensor.RPM, rpmListener);
        return true;
    }

    private void startTextPullThread(final DataListener listener) {
        if (!LinkManager.COMMUNICATION_QUEUE.isEmpty()) {
            System.out.println("Current queue: " + LinkManager.COMMUNICATION_QUEUE.size());
        }
        Runnable textPull = new Runnable() {
            @Override
            public void run() {
                while (!isClosed) {
//                    FileLog.rlog("queue: " + LinkManager.COMMUNICATION_QUEUE.toString());
                    if (LinkManager.COMMUNICATION_QUEUE.isEmpty()) {
                        LinkManager.submit(new Runnable() {
                            @Override
                            public void run() {
                                if (requestOutputChannels())
                                    HeartBeatListeners.onDataArrived();
                                compositeLogic();
                                String text = requestPendingMessages();
                                if (text != null)
                                    listener.onDataArrived((text + "\r\n").getBytes());
                                LiveDocsRegistry.INSTANCE.refresh(BinaryProtocol.this);
                            }
                        });
                    }
                    sleep(Timeouts.TEXT_PULL_PERIOD);
                }
                FileLog.MAIN.logLine("Stopping text pull");
            }
        };
        Thread tr = new Thread(textPull);
        tr.setName("text pull");
        tr.start();
    }

    private void compositeLogic() {
        if (needCompositeLogger) {
            getComposite();
        } else if (isCompositeLoggerEnabled) {
            byte packet[] = new byte[2];
            packet[0] = Fields.TS_SET_LOGGER_SWITCH;
            packet[1] = Fields.TS_COMPOSITE_DISABLE;
            executeCommand(packet, "disable composite");
            isCompositeLoggerEnabled = false;
            closeComposites();
        }
    }

    private void closeComposites() {
        for (StreamFile composite : compositeLogs) {
            composite.close();
        }
        compositeLogs.clear();
    }

    public Logger getLogger() {
        return logger;
    }

    private void dropPending() {
        synchronized (ioLock) {
            if (isClosed)
                return;
            incomingData.dropPending();
            stream.purge();
        }
    }

    public void uploadChanges(ConfigurationImage newVersion, Logger logger) throws InterruptedException, EOFException {
        ConfigurationImage current = getControllerConfiguration();
        // let's have our own copy which no one would be able to change
        newVersion = newVersion.clone();
        int offset = 0;
        while (offset < current.getSize()) {
            Pair<Integer, Integer> range = ConfigurationImageDiff.findDifferences(current, newVersion, offset);
            if (range == null)
                break;
            int size = range.second - range.first;
            logger.info("Need to patch: " + range + ", size=" + size);
            byte[] oldBytes = current.getRange(range.first, size);
            logger.info("old " + Arrays.toString(oldBytes));

            byte[] newBytes = newVersion.getRange(range.first, size);
            logger.info("new " + Arrays.toString(newBytes));

            writeData(newVersion.getContent(), range.first, size, logger);

            offset = range.second;
        }
        burn(logger);
        setController(newVersion);
    }

    private byte[] receivePacket(String msg, boolean allowLongResponse) throws InterruptedException, EOFException {
        long start = System.currentTimeMillis();
        synchronized (ioLock) {
            return incomingData.getPacket(logger, msg, allowLongResponse, start);
        }
    }

    /**
     * read complete tune from physical data stream
     */
    public void readImage(int size) {
        ConfigurationImage image = getAndValidateLocallyCached();

        if (image == null) {
            image = readFullImageFromController(size);
            if (image == null)
                return;
        }
        setController(image);
        logger.info("Got configuration from controller.");
        ConnectionStatusLogic.INSTANCE.setValue(ConnectionStatusValue.CONNECTED);
    }

    @Nullable
    private ConfigurationImage readFullImageFromController(int size) {
        ConfigurationImage image;
        image = new ConfigurationImage(size);

        int offset = 0;

        long start = System.currentTimeMillis();
        logger.info("Reading from controller...");

        while (offset < image.getSize() && (System.currentTimeMillis() - start < Timeouts.READ_IMAGE_TIMEOUT)) {
            if (isClosed)
                return null;

            int remainingSize = image.getSize() - offset;
            int requestSize = Math.min(remainingSize, BLOCKING_FACTOR);

            byte packet[] = new byte[7];
            packet[0] = COMMAND_READ;
            putShort(packet, 1, 0); // page
            putShort(packet, 3, swap16(offset));
            putShort(packet, 5, swap16(requestSize));

            byte[] response = executeCommand(packet, "load image offset=" + offset);

            if (!checkResponseCode(response, RESPONSE_OK) || response.length != requestSize + 1) {
                String code = (response == null || response.length == 0) ? "empty" : "code " + response[0];
                String info = response == null ? "NO RESPONSE" : (code + " size " + response.length);
                logger.error("readImage: Something is wrong, retrying... " + info);
                continue;
            }

            HeartBeatListeners.onDataArrived();
            ConnectionStatusLogic.INSTANCE.markConnected();
            System.arraycopy(response, 1, image.getContent(), offset, requestSize);

            offset += requestSize;
        }
        try {
            ConfigurationImageFile.saveToFile(image, CONFIGURATION_RUSEFI_BINARY);
            Msq tune = Msq.valueOf(image);
            tune.writeXmlFile(CONFIGURATION_RUSEFI_XML);
        } catch (Exception e) {
            System.err.println("Ignoring " + e);
        }
        return image;
    }

    private ConfigurationImage getAndValidateLocallyCached() {
        ConfigurationImage localCached;
        try {
            localCached = ConfigurationImageFile.readFromFile(CONFIGURATION_RUSEFI_BINARY);
        } catch (IOException e) {
            System.err.println("Error reading " + CONFIGURATION_RUSEFI_BINARY + ": no worries " + e);
            return null;
        }

        if (localCached != null) {
            int crcOfLocallyCachedConfiguration = IoHelper.getCrc32(localCached.getContent());
            System.out.printf("Local cache CRC %x\n", crcOfLocallyCachedConfiguration);

            byte packet[] = new byte[7];
            packet[0] = COMMAND_CRC_CHECK_COMMAND;
            byte[] response = executeCommand(packet, "get CRC32");

            if (checkResponseCode(response, RESPONSE_OK) && response.length == 5) {
                ByteBuffer bb = ByteBuffer.wrap(response, 1, 4);
                // that's unusual - most of the protocol is LITTLE_ENDIAN
                bb.order(ByteOrder.BIG_ENDIAN);
                int crcFromController = bb.getInt();
                System.out.printf("From rusEFI CRC %x\n", crcFromController);
                if (crcOfLocallyCachedConfiguration == crcFromController) {
                    return localCached;
                }
            }
        }
        return null;
    }

    public byte[] executeCommand(byte[] packet, String msg) {
        return executeCommand(packet, msg, false);
    }

    /**
     * Blocking sending binary packet and waiting for a response
     *
     * @return null in case of IO issues
     */
    public byte[] executeCommand(byte[] packet, String msg, boolean allowLongResponse) {
        if (isClosed)
            return null;
        try {
            LinkManager.assertCommunicationThread();
            dropPending();

            sendPacket(packet);
            return receivePacket(msg, allowLongResponse);
        } catch (InterruptedException | IOException e) {
            logger.error(msg + ": executeCommand failed: " + e);
            close();
            return null;
        }
    }

    public void close() {
        if (isClosed)
            return;
        isClosed = true;
        SensorCentral.getInstance().removeListener(Sensor.RPM, rpmListener);
        stream.close();
        closeComposites();
        Runtime.getRuntime().removeShutdownHook(hook);
    }

    public void writeData(byte[] content, Integer offset, int size, Logger logger) {
        if (size > BLOCKING_FACTOR) {
            writeData(content, offset, BLOCKING_FACTOR, logger);
            writeData(content, offset + BLOCKING_FACTOR, size - BLOCKING_FACTOR, logger);
            return;
        }

        isBurnPending = true;

        byte packet[] = new byte[7 + size];
        packet[0] = COMMAND_CHUNK_WRITE;
        putShort(packet, 1, 0); // page
        putShort(packet, 3, swap16(offset));
        putShort(packet, 5, swap16(size));

        System.arraycopy(content, offset, packet, 7, size);

        long start = System.currentTimeMillis();
        while (!isClosed && (System.currentTimeMillis() - start < Timeouts.BINARY_IO_TIMEOUT)) {
            byte[] response = executeCommand(packet, "writeImage");
            if (!checkResponseCode(response, RESPONSE_OK) || response.length != 1) {
                logger.error("writeData: Something is wrong, retrying...");
                continue;
            }
            break;
        }
    }

    public void burn(Logger logger) throws InterruptedException, EOFException {
        if (!isBurnPending)
            return;
        logger.info("Need to burn");

        while (true) {
            if (isClosed)
                return;
            byte[] response = executeCommand(new byte[]{COMMAND_BURN}, "burn");
            if (!checkResponseCode(response, RESPONSE_BURN_OK) || response.length != 1) {
                continue;
            }
            break;
        }
        logger.info("DONE");
        isBurnPending = false;
    }

    public void setController(ConfigurationImage controller) {
        synchronized (imageLock) {
            this.controller = controller.clone();
        }
    }

    /**
     * Configuration as it is in the controller to the best of our knowledge
     */
    public ConfigurationImage getControllerConfiguration() {
        synchronized (imageLock) {
            if (controller == null)
                return null;
            return controller.clone();
        }
    }

    private void sendPacket(byte[] command) throws IOException {
        stream.sendPacket(command, logger);
    }


    /**
     * This method blocks until a confirmation is received or {@link Timeouts#BINARY_IO_TIMEOUT} is reached
     *
     * @return true in case of timeout, false if got proper confirmation
     */
    private boolean sendTextCommand(String text) {
        byte[] command = getTextCommandBytes(text);

        long start = System.currentTimeMillis();
        while (!isClosed && (System.currentTimeMillis() - start < Timeouts.BINARY_IO_TIMEOUT)) {
            byte[] response = executeCommand(command, "execute", false);
            if (!checkResponseCode(response, RESPONSE_COMMAND_OK) || response.length != 1) {
                continue;
            }
            return false;
        }
        return true;
    }

    public static byte[] getTextCommandBytes(String text) {
        byte[] asBytes = text.getBytes();
        byte[] command = new byte[asBytes.length + 1];
        command[0] = Fields.TS_EXECUTE;
        System.arraycopy(asBytes, 0, command, 1, asBytes.length);
        return command;
    }

    private String requestPendingMessages() {
        if (isClosed)
            return null;
        try {
            byte[] response = executeCommand(new byte[]{Fields.TS_GET_TEXT}, "text", true);
            if (response != null && response.length == 1)
                Thread.sleep(100);
            return new String(response, 1, response.length - 1);
        } catch (InterruptedException e) {
            FileLog.MAIN.log(e);
            return null;
        }
    }

    public void getComposite() {
        if (isClosed)
            return;

        byte packet[] = new byte[1];
        packet[0] = Fields.TS_GET_COMPOSITE_BUFFER_DONE_DIFFERENTLY;
        // get command would enable composite logging in controller but we need to turn it off from our end
        // todo: actually if console gets disconnected composite logging might end up enabled in controller?
        isCompositeLoggerEnabled = true;

        byte[] response = executeCommand(packet, "composite log", true);
        if (checkResponseCode(response, RESPONSE_OK)) {
            List<CompositeEvent> events = CompositeParser.parse(response);
            createCompositesIfNeeded();
            for (StreamFile composite : compositeLogs)
                composite.append(events);
        }
    }

    public boolean requestOutputChannels() {
        if (isClosed)
            return false;

        byte packet[] = new byte[5];
        packet[0] = COMMAND_OUTPUTS;
        putShort(packet, 1, 0); // offset
        putShort(packet, 3, swap16(Fields.TS_OUTPUT_SIZE));

        byte[] response = executeCommand(packet, "output channels", false);
        if (response == null || response.length != (Fields.TS_OUTPUT_SIZE + 1) || response[0] != RESPONSE_OK)
            return false;

        currentOutputs = response;

        for (Sensor sensor : Sensor.values()) {
            if (sensor.getType() == null) {
                // for example ETB_CONTROL_QUALITY, weird use-case
                continue;
            }

            ByteBuffer bb = ByteBuffer.wrap(response, 1 + sensor.getOffset(), 4);
            bb.order(ByteOrder.LITTLE_ENDIAN);

            double rawValue = getValueForChannel(bb, sensor);
            double scaledValue = rawValue * sensor.getScale();
            SensorCentral.getInstance().setValue(scaledValue, sensor);
        }
        return true;
    }

    private static double getValueForChannel(ByteBuffer bb, Sensor sensor) {
        switch (sensor.getType()) {
            case FLOAT:
                return bb.getFloat();
            case INT:
                return bb.getInt();
            case UINT16:
                // no cast - we want to discard sign
                return bb.getInt() & 0xFFFF;
            case INT16:
                // cast - we want to retain sign
                return  (short)(bb.getInt() & 0xFFFF);
            case UINT8:
                // no cast - discard sign
                return bb.getInt() & 0xFF;
            case INT8:
                // cast - retain sign
                return (byte)(bb.getInt() & 0xFF);
            default:
                throw new UnsupportedOperationException("type " + sensor.getType());
        }
    }
}
