package cn.fj.loli.hexsupport;

import com.intellij.AbstractBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

/**
 * Resource bundle for the Hex Support plugin. Delegates to IntelliJ's
 * {@link AbstractBundle}, which reads the JVM default locale. IntelliJ sets
 * {@code -Duser.language} at startup based on the IDE language selected in
 * Settings, so this automatically follows the IDE language.
 */
public final class HexEditorBundle extends AbstractBundle {
    public static final String BUNDLE = "cn.fj.loli.hexsupport.HexEditorBundle";
    private static final HexEditorBundle INSTANCE = new HexEditorBundle();

    private HexEditorBundle() {
        super(BUNDLE);
    }

    public static @NotNull @Nls String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }
}
