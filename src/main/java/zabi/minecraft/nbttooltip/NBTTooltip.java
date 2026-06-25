package zabi.minecraft.nbttooltip;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.SystemToast.Type;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.ComponentChanges;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import zabi.minecraft.nbttooltip.config.ModConfig;
import zabi.minecraft.nbttooltip.parse_engine.NbtTagParser;

public class NBTTooltip implements ClientModInitializer {

	public static int ticks = 0;
	public static int line_scrolled = 0;

	public static final String FORMAT = Formatting.ITALIC.toString() + Formatting.DARK_GRAY;

	public static final int WAITTIME_BEFORE_FAST_SCROLL = 10;

	// Since 1.21.9 key categories are KeyBinding.Category objects (registered on creation),
	// not plain translation-key strings. Its label resolves to "key.category.nbttooltip.general".
	public static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(Identifier.of("nbttooltip", "general"));

	public static KeyBinding COPY_TO_CLIPBOARD = new KeyBinding("key.nbttooltip.copy", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, CATEGORY);
	public static KeyBinding TOGGLE_NBT = new KeyBinding("key.nbttooltip.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, CATEGORY);
	public static KeyBinding SCROLL_UP = new KeyBinding("key.nbttooltip.scroll_up", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UP, CATEGORY);
	public static KeyBinding SCROLL_DOWN = new KeyBinding("key.nbttooltip.scroll_down", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, CATEGORY);

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
		KeyBindingHelper.registerKeyBinding(COPY_TO_CLIPBOARD);
		KeyBindingHelper.registerKeyBinding(TOGGLE_NBT);
		KeyBindingHelper.registerKeyBinding(SCROLL_DOWN);
		KeyBindingHelper.registerKeyBinding(SCROLL_UP);
	}

	public static void clientTick(MinecraftClient mc) {

		if (mc.world == null) return;

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

	private static boolean isPressed(MinecraftClient mc, KeyBinding key) {
		return !key.isUnbound() && InputUtil.isKeyPressed(mc.getWindow(), InputUtil.fromTranslationKey(key.getBoundKeyTranslationKey()).getCode());
	}

	// Replacements for the Screen.has*Down() statics removed in 1.21.9+.
	private static boolean isKeyHeld(int keyCode) {
		return InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow(), keyCode);
	}

	private static boolean hasShiftDown() {
		return isKeyHeld(InputUtil.GLFW_KEY_LEFT_SHIFT) || isKeyHeld(InputUtil.GLFW_KEY_RIGHT_SHIFT);
	}

	private static boolean hasControlDown() {
		return isKeyHeld(InputUtil.GLFW_KEY_LEFT_CONTROL) || isKeyHeld(InputUtil.GLFW_KEY_RIGHT_CONTROL);
	}

	private static boolean hasAltDown() {
		return isKeyHeld(InputUtil.GLFW_KEY_LEFT_ALT) || isKeyHeld(InputUtil.GLFW_KEY_RIGHT_ALT);
	}

	public static ArrayList<Text> transformTtip(ArrayList<Text> ttip, int lines) {
		ArrayList<Text> newttip = new ArrayList<>(lines);
		if (ModConfig.INSTANCE.showSeparator) {
			newttip.add(Text.literal("- NBTTooltip -"));
		}
		if (ttip.size() > lines) {
			if (lines + line_scrolled > ttip.size()) {
				if (isPressed(MinecraftClient.getInstance(), SCROLL_DOWN)) {
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

    private static NbtCompound removeLoreFromTag(NbtCompound tag) {
        NbtCompound copy = tag.copy();

        if (copy.contains("minecraft:lore")) {
            copy.remove("minecraft:lore");
        }

        return copy;
    }

    private static NbtCompound removeDisplayNameFromTag(NbtCompound tag) {
        NbtCompound copy = tag.copy();

        if (copy.contains("minecraft:custom_name")) {
            copy.remove("minecraft:custom_name");
        }

        return copy;
    }

    public static void onInjectTooltip(ItemStack stack, Item.TooltipContext context, TooltipType type, List<Text> list) {
		handleClipboardCopy(stack);
		if (ModConfig.INSTANCE.triggerType.shouldShowTooltip(context, type)) {
			if (autoscroll_locks > 0) autoscroll_locks = 2;
			int lines = ModConfig.INSTANCE.maxLinesShown;
			if (ModConfig.INSTANCE.ctrlSuppressesRest && hasControlDown()) {
				lines += list.size();
				list.clear();
			} else {
				list.add(Text.literal(""));
			}

			ArrayList<Text> ttip = new ArrayList<>(lines);
			NbtCompound tag = encodeStack(stack, context.getRegistryLookup().getOps(NbtOps.INSTANCE));
			if (!tag.isEmpty()) {
                if (ModConfig.INSTANCE.hideLore) {
                    tag = removeLoreFromTag(tag);
                }
                if (ModConfig.INSTANCE.hideDisplayName) {
                    tag = removeDisplayNameFromTag(tag);
                }
				if (ModConfig.INSTANCE.showDelimiters) {
					ttip.add(Text.literal(Formatting.DARK_PURPLE + " - nbt start -"));
				}
                if (ModConfig.INSTANCE.compress) {
                    ttip.add(Text.literal(FORMAT + tag));
                } else {
                    getRenderingEngine().parseTagToList(ttip, tag, ModConfig.INSTANCE.splitLongLines);
                }
				if (ModConfig.INSTANCE.showDelimiters) {
					ttip.add(Text.literal(Formatting.DARK_PURPLE + " - nbt end -"));
				}
				ttip = NBTTooltip.transformTtip(ttip, lines);
				list.addAll(ttip);
			} else {
				list.add(Text.literal(FORMAT + "No NBT data"));
			}
		}
	}

	private static NbtCompound encodeStack(ItemStack stack, DynamicOps<NbtElement> ops) {
		DataResult<NbtElement> result = ComponentChanges.CODEC.encodeStart(ops, stack.getComponentChanges());
		result.ifError(e->{

		});
		NbtElement nbtElement = result.getOrThrow();
		// cast here, as soon as this breaks, the mod will need to update anyway
		return (NbtCompound) nbtElement;
	}

	private static void handleClipboardCopy(ItemStack stack) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc.currentScreen != null) {
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

	private static void copyToClipboard(ItemStack stack, MinecraftClient mc) {
		StringBuilder sb = new StringBuilder();
		String name = I18n.translate(stack.getItem().getTranslationKey());
		ArrayList<Text> nbtData = new ArrayList<>();
		getCopyingEngine().parseTagToList(nbtData, encodeStack(stack, mc.player.getRegistryManager().getOps(NbtOps.INSTANCE)), false);
		nbtData.forEach(t -> {
			sb.append(t.getString().replaceAll("§[0-9a-gk-or]", ""));
			sb.append("\n");
		});
		try {
			mc.keyboard.setClipboard(sb.toString());
			mc.getToastManager().add(new SystemToast(Type.PERIODIC_NOTIFICATION, Text.translatable("nbttooltip.copied_to_clipboard"), Text.translatable("nbttooltip.object_details", name)));
		} catch (Exception e) {
			mc.getToastManager().add(new SystemToast(Type.PERIODIC_NOTIFICATION, Text.translatable("nbttooltip.copy_failed"), Text.literal(e.getMessage())));
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