package cn.fj.loli.hexsupport;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@State(name = "HexSupportSettings", storages = @Storage("hexSupport.xml"))
public final class HexSupportSettings implements PersistentStateComponent<HexSupportSettings.SettingsState> {
    private SettingsState state = new SettingsState();

    static HexSupportSettings getInstance() {
        return ApplicationManager.getApplication().getService(HexSupportSettings.class);
    }

    @Override
    public @Nullable SettingsState getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        this.state = state;
    }

    boolean autoSaveHistory() {
        return state.autoSaveHistory;
    }

    void setAutoSaveHistory(boolean autoSaveHistory) {
        state.autoSaveHistory = autoSaveHistory;
    }

    boolean deleteHistoryOnSave() {
        return state.deleteHistoryOnSave;
    }

    void setDeleteHistoryOnSave(boolean deleteHistoryOnSave) {
        state.deleteHistoryOnSave = deleteHistoryOnSave;
    }

    public static final class SettingsState {
        public boolean autoSaveHistory = false;
        public boolean deleteHistoryOnSave = false;
    }
}
