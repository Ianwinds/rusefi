package com.rusefi.ts_plugin;

import com.rusefi.autoupdate.AutoupdateUtil;
import com.rusefi.ui.storage.PersistentConfiguration;
import org.putgemin.VerticalFlowLayout;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.util.Date;

import static com.rusefi.ts_plugin.TsPluginLauncher.VERSION;

public class Updater {
    private static final String PLUGIN_BODY_JAR = "rusefi_plugin_body.jar";
    public static final String LOCAL_JAR_FILE_NAME = PersistentConfiguration.RUSEFI_SETTINGS_FOLDER + File.separator + PLUGIN_BODY_JAR;
    private static final String TITLE = "rusEFI plugin installer " + VERSION;

    private final JPanel content = new JPanel(new VerticalFlowLayout());
    public static final ImageIcon LOGO = AutoupdateUtil.loadIcon("/rusefi_online_color_300.png");

    public Updater() {
        content.add(new JLabel("" + VERSION));

        content.add(new JLabel(LOGO));

        String version = null;
        File localFile = new File(LOCAL_JAR_FILE_NAME);
        if (localFile.exists()) {
            version = getVersion();
        }

        JButton download = new JButton("Update plugin");
        if (version != null) {
            JButton run = new JButton("Run Version " + version);
            run.addActionListener(new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    try {
                        startPlugin();
                    } catch (IllegalAccessException | MalformedURLException | ClassNotFoundException | InstantiationException ex) {
                        run.setText(e.toString());
                    }
                }
            });

            content.add(run);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                AutoupdateUtil.ConnectionAndMeta connectionAndMeta = null;
                try {
                    connectionAndMeta = new AutoupdateUtil.ConnectionAndMeta(PLUGIN_BODY_JAR).invoke();
                } catch (IOException e) {
                    e.printStackTrace();
                    return;
                }
                System.out.println("Server has " + connectionAndMeta.getCompleteFileSize() + " from " + new Date(connectionAndMeta.getLastModified()));

                if (AutoupdateUtil.hasExistingFile(LOCAL_JAR_FILE_NAME, connectionAndMeta.getCompleteFileSize(), connectionAndMeta.getLastModified())) {
                    System.out.println("We already have latest update " + new Date(connectionAndMeta.getLastModified()));
                    SwingUtilities.invokeLater(() -> {
                        download.setText("We have latest plugin version");
                        download.setEnabled(false);
                    });
                    return;
                }

            }
        }).start();

        download.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                new Thread(() -> startDownload(download)).start();
            }
        });

        content.add(download);
    }

    private String getVersion() {
        try {
            Class clazz = getPluginClass();
            Method method = clazz.getMethod(TsPluginBody.GET_VERSION);
            return (String) method.invoke(null);
        } catch (NoSuchMethodException | MalformedURLException | ClassNotFoundException | IllegalAccessException | InvocationTargetException e) {
            return null;
        }
    }

    private void startDownload(JButton download) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                download.setEnabled(false);
            }
        });

        try {
            AutoupdateUtil.ConnectionAndMeta connectionAndMeta = new AutoupdateUtil.ConnectionAndMeta(PLUGIN_BODY_JAR).invoke();

            AutoupdateUtil.downloadAutoupdateFile(LOCAL_JAR_FILE_NAME, connectionAndMeta,
                    TITLE);

            startPlugin();

        } catch (IOException | ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
            download.setEnabled(true);
        }
    }

    private void startPlugin() throws MalformedURLException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        Class clazz = getPluginClass();
        TsPluginBody instance = (TsPluginBody) clazz.newInstance();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                replaceWith(instance);
            }
        });
    }

    private static Class getPluginClass() throws MalformedURLException, ClassNotFoundException {
        URLClassLoader jarClassLoader = AutoupdateUtil.getClassLoaderByJar(LOCAL_JAR_FILE_NAME);
        return Class.forName("com.rusefi.ts_plugin.PluginEntry", true, jarClassLoader);
    }

    private void replaceWith(TsPluginBody instance) {
        content.removeAll();
        content.add(instance.getContent());
        AutoupdateUtil.trueLayout(content.getParent());
        JFrame topFrame = (JFrame) SwingUtilities.getWindowAncestor(content);
        AutoupdateUtil.trueLayout(topFrame);
        topFrame.pack();
        AutoupdateUtil.trueLayout(topFrame);
    }

    public JPanel getContent() {
        return content;
    }
}
