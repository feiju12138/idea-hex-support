package cn.fj.loli.hexsupport;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

public final class HexSupportConfigurable implements Configurable {
    private JBCheckBox autoSaveHistory;
    private JBCheckBox deleteHistoryOnSave;
    private JPanel panel;

    @Override
    public @Nls String getDisplayName() {
        return HexEditorBundle.message("settings.displayName");
    }

    @Override
    public @Nullable JComponent createComponent() {
        autoSaveHistory = new JBCheckBox(HexEditorBundle.message("settings.autoSaveHistory"));
        deleteHistoryOnSave = new JBCheckBox(HexEditorBundle.message("settings.deleteHistoryOnSave"));
        panel = FormBuilder.createFormBuilder()
                .addComponent(autoSaveHistory, 1)
                .addComponent(deleteHistoryOnSave, 1)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        return panel;
    }

    @Override
    public boolean isModified() {
        HexSupportSettings settings = HexSupportSettings.getInstance();
        return autoSaveHistory.isSelected() != settings.autoSaveHistory()
                || deleteHistoryOnSave.isSelected() != settings.deleteHistoryOnSave();
    }

    @Override
    public void apply() {
        HexSupportSettings settings = HexSupportSettings.getInstance();
        settings.setAutoSaveHistory(autoSaveHistory.isSelected());
        settings.setDeleteHistoryOnSave(deleteHistoryOnSave.isSelected());
    }

    @Override
    public void reset() {
        HexSupportSettings settings = HexSupportSettings.getInstance();
        autoSaveHistory.setSelected(settings.autoSaveHistory());
        deleteHistoryOnSave.setSelected(settings.deleteHistoryOnSave());
    }

    @Override
    public void disposeUIResources() {
        autoSaveHistory = null;
        deleteHistoryOnSave = null;
        panel = null;
    }
}
