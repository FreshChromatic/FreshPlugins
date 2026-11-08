package github.freshchromatic.freshlib.gui.inventorygui;

import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.ApiStatus;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

@ApiStatus.Internal
public class GuiView {

    private static final MethodHandle GET_TOP_INVENTORY    = unreflect("getTopInventory");
    private static final MethodHandle GET_BOTTOM_INVENTORY = unreflect("getBottomInventory");
    private static final MethodHandle GET_PLAYER           = unreflect("getPlayer");
    private static final MethodHandle GET_TYPE             = unreflect("getType");
    private static final MethodHandle SET_ITEM             = unreflect("setItem");
    private static final MethodHandle GET_ITEM             = unreflect("getItem");
    private static final MethodHandle SET_CURSOR           = unreflect("setCursor");
    private static final MethodHandle GET_CURSOR           = unreflect("getCursor");
    private static final MethodHandle GET_INVENTORY        = unreflect("getInventory");
    private static final MethodHandle CONVERT_SLOT         = unreflect("convertSlot");
    private static final MethodHandle GET_SLOT_TYPE        = unreflect("getSlotType");
    private static final MethodHandle CLOSE                = unreflect("close");
    private static final MethodHandle COUNT_SLOTS          = unreflect("countSlots");
    private static final MethodHandle SET_PROPERTY         = unreflect("setProperty");
    private static final MethodHandle GET_TITLE            = unreflect("getTitle");

    public static GuiView of(InventoryView view) {
        return new GuiView(view);
    }

    private final InventoryView view;

    public GuiView(InventoryView view) {
        this.view = view;
    }

    public Inventory getTopInventory() {
        return invoke(GET_TOP_INVENTORY);
    }

    public Inventory getBottomInventory() {
        return invoke(GET_BOTTOM_INVENTORY);
    }

    public HumanEntity getPlayer() {
        return invoke(GET_PLAYER);
    }

    public InventoryType getType() {
        return invoke(GET_TYPE);
    }

    public void setItem(int slot, ItemStack item) {
        try {
            SET_ITEM.invoke(view, slot, item);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    public ItemStack getItem(int slot) {
        return invoke(GET_ITEM, slot);
    }

    public void setCursor(ItemStack item) {
        try {
            SET_CURSOR.invoke(view, item);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    public ItemStack getCursor() {
        return invoke(GET_CURSOR);
    }

    public Inventory getInventory(int rawSlot) {
        return invoke(GET_INVENTORY, rawSlot);
    }

    public int convertSlot(int slot) {
        return invoke(CONVERT_SLOT, slot);
    }

    public InventoryType.SlotType getSlotType(int slot) {
        return invoke(GET_SLOT_TYPE, slot);
    }

    public void close() {
        try {
            CLOSE.invoke(view);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    public int countSlots() {
        return invoke(COUNT_SLOTS);
    }

    public boolean setProperty(InventoryView.Property prop, int value) {
        try {
            return (boolean) SET_PROPERTY.invoke(view, prop, value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    public String getTitle() {
        return invoke(GET_TITLE);
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(MethodHandle method) {
        try {
            return (T) method.invoke(view);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(MethodHandle method, int slot) {
        try {
            return (T) method.invoke(view, slot);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable failure) {
            throw invocationFailure(failure);
        }
    }

    private static AssertionError invocationFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        return new AssertionError(failure);
    }

    private static MethodHandle unreflect(String name) {
        for (Method method : InventoryView.class.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                try {
                    return MethodHandles.lookup().unreflect(method);
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return null;
    }
}
