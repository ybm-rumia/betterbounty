package com.ybm.betterbounty;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IBookmarkOverlay;
import mezz.jei.api.runtime.IJeiRuntime;
import mezz.jei.gui.bookmarks.BookmarkList;
import mezz.jei.gui.bookmarks.IngredientBookmark;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.util.List;

import static com.ybm.betterbounty.BetterBounty.LOGGER;

@JeiPlugin
public class JEIPlugin implements IModPlugin {
    private static IJeiRuntime runtime;

    @Override
    public ResourceLocation getPluginUid() {
        return new ResourceLocation(BetterBounty.MODID, "jei_plugin");
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
        LOGGER.info("JEI Runtime available");
    }

    public static void bookmarkItems(List<ItemStack> stacks) {
        if (runtime == null) {
            LOGGER.warn("JEI Runtime is null, cannot bookmark items");
            return;
        }

        IBookmarkOverlay overlay = runtime.getBookmarkOverlay();
        if (overlay == null) {
            LOGGER.warn("BookmarkOverlay is null");
            return;
        }

        IIngredientManager ingredientManager = runtime.getIngredientManager();

        try {
            Field bookmarkListField = overlay.getClass().getDeclaredField("bookmarkList");
            bookmarkListField.setAccessible(true);
            Object bookmarkListObj = bookmarkListField.get(overlay);

            if (bookmarkListObj instanceof BookmarkList bookmarkList) {
                for (ItemStack stack : stacks) {
                    // 把 ItemStack 转为 ITypedIngredient，再创建 IBookmark
                    ITypedIngredient<?> typedIngredient = ingredientManager.createTypedIngredient(stack).orElse(null);
                    if (typedIngredient != null) {
                        var bookmark = IngredientBookmark.create(typedIngredient, ingredientManager);
                        bookmarkList.add(bookmark);
                        LOGGER.info("Bookmarked item: {}", stack.getDisplayName().getString());
                    }
                }
            }
        } catch (NoSuchFieldException e) {
            LOGGER.error("Field 'bookmarkList' not found in {}", overlay.getClass().getName());
        } catch (Exception e) {
            LOGGER.error("Failed to bookmark items via reflection", e);
        }
    }
}