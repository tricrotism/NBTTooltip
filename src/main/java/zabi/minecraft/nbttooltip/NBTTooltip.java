package zabi.minecraft.nbttooltip;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import zabi.minecraft.nbttooltip.config.ModConfig;
import zabi.minecraft.nbttooltip.parse_engine.NbtTagParser;

public class NBTTooltip implements ClientModInitializer {

	public static int ticks = 0;
	public static int line_scrolled = 0;

	public static final String FORMAT = ChatFormatting.ITALIC.toString() + ChatFormatting.DARK_GRAY;

	public static final int WAITTIME_BEFORE_FAST_SCROLL = 10;

	// Since 1.21.9 key categories are KeyMapping.Category objects (registered on creation),
	// not plain translation-key strings. Its label resolves to "key.category.nbttooltip.general".
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("nbttooltip", "general"));

	public static KeyMapping COPY_TO_CLIPBOARD = new KeyMapping("key.nbttooltip.copy", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, CATEGORY);
	public static KeyMapping TOGGLE_NBT = new KeyMapping("key.nbttooltip.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, CATEGORY);
	public static KeyMapping SCROLL_UP = new KeyMapping("key.nbttooltip.scroll_up", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, CATEGORY);
	public static KeyMapping SCROLL_DOWN = new KeyMapping("key.nbttooltip.scroll_down", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, CATEGORY);

	public static boolean flipflop_key_copy = false;
	public static boolean flipflop_key_toggle = false;

	public static boolean nbtKeyToggled = false;
	public static boolean nbtKeyPressed = false;

	public static int fast_scroll_warmup = 0;
	public static int autoscroll_locks = 0;

	@Override
	public void onInitializeClient() {
		ModConfig.init();
		ClientTickEvents.END_CLIENT_TICK.register(NBTTooltip::clientTick);
		ItemTooltipCallback.EVENT.register(NBTTooltip::onInjectTooltip);
		KeyMappingHelper.registerKeyMapping(COPY_TO_CLIPBOARD);
		KeyMappingHelper.registerKeyMapping(TOGGLE_NBT);
		KeyMappingHelper.registerKeyMapping(SCROLL_DOWN);
		KeyMappingHelper.registerKeyMapping(SCROLL_UP);
	}

	public static void clientTick(Minecraft mc) {

		if (mc.level == null) return;

		if (autoscroll_locks > 0) autoscroll_locks--;

		if (!hasShiftDown() && !isPressed(mc, SCROLL_DOWN) && !isPressed(mc, SCROLL_UP) && autoscroll_locks == 0) {
			NBTTooltip.ticks++;
			int factor = 1;
			if (hasAltDown()) {
				factor = 4;
			}
			if (NBTTooltip.ticks >= ModConfig.INSTANCE.ticksBeforeScroll / factor) {
				NBTTooltip.ticks = 0;
				if (ModConfig.INSTANCE.ticksBeforeScroll > 0) {
					NBTTooltip.line_scrolled++;
				}
			}
		}

		if (isPressed(mc, TOGGLE_NBT)) {
			if (!flipflop_key_toggle) {
				nbtKeyToggled = !nbtKeyToggled;
			}
			flipflop_key_toggle = true;
			nbtKeyPressed = true;
		} else {
			flipflop_key_toggle = false;
			nbtKeyPressed = false;
		}


		if (!isPressed(mc, SCROLL_DOWN) && isPressed(mc, SCROLL_UP) && line_scrolled > 0 && cooldownTimeAcceptable()) {
			line_scrolled--;
		}

		if (isPressed(mc, SCROLL_DOWN) && !isPressed(mc, SCROLL_UP) && cooldownTimeAcceptable()) {
			line_scrolled++;
		}

		if (isPressed(mc, SCROLL_DOWN) || isPressed(mc, SCROLL_UP)) {
			if (fast_scroll_warmup < WAITTIME_BEFORE_FAST_SCROLL) fast_scroll_warmup++;
			autoscroll_locks = 2;
		} else {
			fast_scroll_warmup = 0;
		}
	}

	private static boolean cooldownTimeAcceptable() {
		return fast_scroll_warmup == 0 || fast_scroll_warmup >= WAITTIME_BEFORE_FAST_SCROLL;
	}

	private static boolean isPressed(Minecraft mc, KeyMapping key) {
		return !key.isUnbound() && InputConstants.isKeyDown(mc.getWindow(), KeyMappingHelper.getBoundKeyOf(key).getValue());
	}

	// Replacements for the Screen.has*Down() statics removed in 1.21.9+.
	private static boolean isKeyHeld(int keyCode) {
		return InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), keyCode);
	}

	private static boolean hasShiftDown() {
		return isKeyHeld(GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyHeld(GLFW.GLFW_KEY_RIGHT_SHIFT);
	}

	private static boolean hasControlDown() {
		return isKeyHeld(GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyHeld(GLFW.GLFW_KEY_RIGHT_CONTROL);
	}

	private static boolean hasAltDown() {
		return isKeyHeld(GLFW.GLFW_KEY_LEFT_ALT) || isKeyHeld(GLFW.GLFW_KEY_RIGHT_ALT);
	}

	public static ArrayList<Component> transformTtip(ArrayList<Component> ttip, int lines) {
		ArrayList<Component> newttip = new ArrayList<>(lines);
		if (ModConfig.INSTANCE.showSeparator) {
			newttip.add(Component.literal("- NBTTooltip -"));
		}
		if (ttip.size() > lines) {
			if (lines + line_scrolled > ttip.size()) {
				if (isPressed(Minecraft.getInstance(), SCROLL_DOWN)) {
					line_scrolled = ttip.size() - lines;
				} else {
					line_scrolled = 0;
				}
			}
			for (int i = 0; i < lines; i++) {
				newttip.add(ttip.get(i + line_scrolled));
			}
		} else {
			line_scrolled = 0;
			newttip.addAll(ttip);
		}
		return newttip;
	}

    private static CompoundTag removeLoreFromTag(CompoundTag tag) {
        CompoundTag copy = tag.copy();

        if (copy.contains("minecraft:lore")) {
            copy.remove("minecraft:lore");
        }

        return copy;
    }

    private static CompoundTag removeDisplayNameFromTag(CompoundTag tag) {
        CompoundTag copy = tag.copy();

        if (copy.contains("minecraft:custom_name")) {
            copy.remove("minecraft:custom_name");
        }

        return copy;
    }

    public static void onInjectTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag type, List<Component> list) {
		handleClipboardCopy(stack);
		if (ModConfig.INSTANCE.triggerType.shouldShowTooltip(context, type)) {
			if (autoscroll_locks > 0) autoscroll_locks = 2;
			int lines = ModConfig.INSTANCE.maxLinesShown;
			if (ModConfig.INSTANCE.ctrlSuppressesRest && hasControlDown()) {
				lines += list.size();
				list.clear();
			} else {
				list.add(Component.literal(""));
			}

			ArrayList<Component> ttip = new ArrayList<>(lines);
			CompoundTag tag = encodeStack(stack, context.registries().createSerializationContext(NbtOps.INSTANCE));
			if (!tag.isEmpty()) {
                if (ModConfig.INSTANCE.hideLore) {
                    tag = removeLoreFromTag(tag);
                }
                if (ModConfig.INSTANCE.hideDisplayName) {
                    tag = removeDisplayNameFromTag(tag);
                }
				if (ModConfig.INSTANCE.showDelimiters) {
					ttip.add(Component.literal(ChatFormatting.DARK_PURPLE + " - nbt start -"));
				}
                if (ModConfig.INSTANCE.compress) {
                    ttip.add(Component.literal(FORMAT + tag));
                } else {
                    getRenderingEngine().parseTagToList(ttip, tag, ModConfig.INSTANCE.splitLongLines);
                }
				if (ModConfig.INSTANCE.showDelimiters) {
					ttip.add(Component.literal(ChatFormatting.DARK_PURPLE + " - nbt end -"));
				}
				ttip = NBTTooltip.transformTtip(ttip, lines);
				list.addAll(ttip);
			} else {
				list.add(Component.literal(FORMAT + "No NBT data"));
			}
		}
	}

	private static CompoundTag encodeStack(ItemStack stack, DynamicOps<Tag> ops) {
		DataResult<Tag> result = DataComponentPatch.CODEC.encodeStart(ops, stack.getComponentsPatch());
		result.ifError(e->{

		});
		Tag nbtElement = result.getOrThrow();
		// cast here, as soon as this breaks, the mod will need to update anyway
		return (CompoundTag) nbtElement;
	}

	private static void handleClipboardCopy(ItemStack stack) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.screen != null) {
			if (isPressed(mc, COPY_TO_CLIPBOARD)) {
				if (!flipflop_key_copy) {
					flipflop_key_copy = true;
					copyToClipboard(stack, mc);
				}
			} else {
				flipflop_key_copy = false;
			}
		}
	}

	private static void copyToClipboard(ItemStack stack, Minecraft mc) {
		StringBuilder sb = new StringBuilder();
		String name = I18n.get(stack.getItem().getDescriptionId());
		ArrayList<Component> nbtData = new ArrayList<>();
		getCopyingEngine().parseTagToList(nbtData, encodeStack(stack, mc.player.registryAccess().createSerializationContext(NbtOps.INSTANCE)), false);
		nbtData.forEach(t -> {
			sb.append(t.getString().replaceAll("§[0-9a-gk-or]", ""));
			sb.append("\n");
		});
		try {
			mc.keyboardHandler.setClipboard(sb.toString());
			SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.translatable("nbttooltip.copied_to_clipboard"), Component.translatable("nbttooltip.object_details", name));
		} catch (Exception e) {
			SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.translatable("nbttooltip.copy_failed"), Component.literal(e.getMessage()));
			e.printStackTrace();
		}
	}

	private static NbtTagParser getRenderingEngine() {
		return ModConfig.INSTANCE.tooltipEngine.getEngine();
	}

	private static NbtTagParser getCopyingEngine() {
		return ModConfig.INSTANCE.copyingEngine.getEngine();
	}

}
