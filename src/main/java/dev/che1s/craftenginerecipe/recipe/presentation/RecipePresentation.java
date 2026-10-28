package dev.che1s.craftenginerecipe.recipe.presentation;

import dev.che1s.craftenginerecipe.api.RecipeLayout;

/** 与具体配方来源解耦的合成器展示规则。 */
public final class RecipePresentation {
    private static final String API_RECIPE_PREFIX = "cer_api:";
    private static final String API_BACKGROUND = "cer_api_gui:default_recipe_gui";

    private RecipePresentation() {
    }

    public static String backgroundImage(String recipeId, String workstationId) {
        // API 工作站 ID 由来源插件决定，不能逐一声明背景；内部 cer_api 配方统一使用 API 页面。
        if (recipeId != null && recipeId.startsWith(API_RECIPE_PREFIX)) return API_BACKGROUND;
        return backgroundImage(workstationId);
    }

    public static String backgroundImage(String workstationId) {
        if (workstationId == null) return null;
        if (workstationId.startsWith("customcrops:pot/")) {
            return "cer_ccrops_gui:customcrops_gui";
        }
        return switch (workstationId) {
            case "kc:chopping_board" -> "cer_kc_gui:chopping_board_gui";
            case "kc:millstone" -> "cer_kc_gui:millstone_gui";
            case "kc:pot" -> "cer_kc_gui:pot_gui";
            case "kc:shawarma" -> "cer_kc_gui:shawarma_spit_gui";
            case "kc:stockpot" -> "cer_kc_gui:stockpot_gui";
            case "kc:steamer" -> "cer_kc_gui:steamer_full_lid";
            case "kc:teapot" -> "cer_kc_gui:teapot_gui";
            case "farmersdelight:skillet" -> "cer_fd_gui:fd_pot_menu";
            case "minecraft:cultivation" -> "cer_cultivation_gui:cultivation_gui";
            default -> null;
        };
    }

    public static boolean shouldApplyGuiBackground(String workstationId, String rawTitle) {
        return backgroundImage(workstationId) != null
                && (rawTitle == null || !rawTitle.contains("<image:"));
    }

    public static boolean shouldApplyGuiBackground(String recipeId, String workstationId, String rawTitle) {
        return backgroundImage(recipeId, workstationId) != null
                && (rawTitle == null || !rawTitle.contains("<image:"));
    }

    public static String emphasizedFarmerTitle(String workstationId, String title) {
        String fixedTitle = farmerFixedTitle(workstationId);
        if (fixedTitle == null || title == null || title.startsWith(RecipeLayout.JSON_TITLE_PREFIX)
                || title.contains("<bold>" + fixedTitle + "</bold>")) return title;
        return title.replace(fixedTitle, "<bold>" + fixedTitle + "</bold>");
    }

    public static String farmerFixedTitle(String workstationId) {
        if (workstationId == null) return null;
        if (workstationId.equals("farmersdelight:cooking_pot")
                || workstationId.startsWith("farmersdelight:cooking_pot/")) return "农夫乐事·烹饪锅";
        if (workstationId.equals("farmersdelight:cutting_board")) return "农夫乐事·砧板";
        if (workstationId.equals("farmersdelight:special")) return "农夫乐事·特殊配方";
        return null;
    }

    public static boolean usesSourceLoreOnly(String recipeId) {
        return recipeId != null && (recipeId.startsWith("kc:") || recipeId.startsWith("farmersdelight:"));
    }

    public static String title(String background, String label) {
        boolean farmersDelight = background.startsWith("cer_fd_gui:");
        int offset = farmersDelight ? -2 : -8;
        int shift = farmersDelight ? -163 : -168;
        return "<#FFFFFF><shift:" + offset + "><image:" + background
                + "><shift:" + shift + "><#000000><bold>" + label + "</bold></#000000>";
    }

    public static String title(String background, String label, String namePrefix) {
        boolean farmersDelight = background.startsWith("cer_fd_gui:");
        int offset = farmersDelight ? -2 : -8;
        int shift = farmersDelight ? -163 : -168;
        return "<#FFFFFF><shift:" + offset + "><image:" + background
                + "><shift:" + shift + ">" + namePrefix + label;
    }
}
