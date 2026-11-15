package dev.che1s.craftenginerecipe.dialog;

/** Immutable collection of plugin-managed administrative entry points. */
public record PluginDialogs(
        SearchDialogService search,
        PersonalizationDialogService personalization,
        UnappliedCategoryDialogService unapplied,
        ButtonEditorDialogService buttons,
        ConfigEditorDialogService config) {
}
