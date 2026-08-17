package com.wmp.processing.imageFormat;

import org.apache.log4j.Logger;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.util.*;

public class ImageLinkFileEditPanel {
    private JTextField NameTextField;
    private JComboBox<String> comboBox;
    private JPanel mainPanel;

    private static final Logger logger = Logger.getLogger(ImageLinkFileEditPanel.class);

    public ImageLinkFileEditPanel(String name, String choose) {
        NameTextField.setText(name);
        comboBox.removeAllItems();
        var readerSuffixes = ImageIO.getReaderFileSuffixes();
        var writerSuffixes = ImageIO.getWriterFileSuffixes();



        Set<String> readerSet = new HashSet<>(Arrays.asList(readerSuffixes));
        Set<String> writerSet = new HashSet<>(Arrays.asList(writerSuffixes));
        logger.info("支持的读取后缀: " + readerSet);
        logger.info("支持的写入后缀: " + writerSet);

        readerSet.retainAll(writerSet);
        var strings = new ArrayList<String>(readerSet);
        logger.info(strings);
        strings.forEach(suffixes -> comboBox.addItem(suffixes));


        if (choose != null) comboBox.setSelectedItem(choose);
        else comboBox.setSelectedIndex(0);
    }

    public String getType(){
        return comboBox.getSelectedItem().toString();
    }

    public String getFileName() {
        return NameTextField.getText();
    }

    public JPanel getMainPanel(){
        return mainPanel;
    }
}
