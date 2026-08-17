package com.wmp.processing.imageFormat;

import javax.swing.*;

public class ImageLinkFileEditPanel {
    private JTextField NameTextField;
    private JComboBox comboBox;
    private JPanel mainPanel;

    public ImageLinkFileEditPanel(String name, String choose) {
        NameTextField.setText(name);
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
