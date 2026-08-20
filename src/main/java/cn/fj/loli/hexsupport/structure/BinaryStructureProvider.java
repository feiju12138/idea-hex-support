package cn.fj.loli.hexsupport.structure;

import com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Extension point implemented by plugins that turn a schema/template into a binary structure tree.
 * Providers receive only a read-only document snapshot and must never mutate the source file.
 */
public interface BinaryStructureProvider {
    /** Include this phrase in the Marketplace description so Hex Support can discover the provider. */
    String MARKETPLACE_DISCOVERY_KEYWORD = "Hex Support structure analysis";

    ExtensionPointName<BinaryStructureProvider> EP_NAME =
            ExtensionPointName.create("cn.fj.loli.hexsupport.binaryStructureProvider");

    @NotNull String id();

    @Nls @NotNull String displayName();

    /** Lower-case extensions without a leading dot. */
    @NotNull Collection<String> templateExtensions();

    default boolean supportsTemplate(@NotNull Path template) {
        Path fileName = template.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return templateExtensions().stream().anyMatch(extension::equalsIgnoreCase);
    }

    @NotNull StructureAnalysisResult analyze(
            @NotNull Path template,
            @NotNull BinarySnapshot input,
            @NotNull BooleanSupplier canceled
    );
}
