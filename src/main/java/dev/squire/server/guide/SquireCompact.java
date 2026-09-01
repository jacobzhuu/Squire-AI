package dev.squire.server.guide;

import dev.squire.SquireMod;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.WrittenBookItem;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Builds the vanilla written book that introduces Squire before the first summon. */
public final class SquireCompact {

	public static final String TITLE = "侍从之约 / Squire's Compact";
	public static final String AUTHOR = "Squire AI";
	public static final int CURRENT_VERSION = 1;
	public static final int PAGE_COUNT = 7;
	public static final String NBT_VERSION = "SquireCompactVersion";

	/** A fresh, client-localized copy suitable for a join gift or recipe output. */
	public static ItemStack create() {
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		NbtCompound nbt = book.getOrCreateNbt();
		nbt.putString(WrittenBookItem.TITLE_KEY, TITLE);
		nbt.putString(WrittenBookItem.AUTHOR_KEY, AUTHOR);
		nbt.putInt(WrittenBookItem.GENERATION_KEY, 0);
		nbt.putBoolean(WrittenBookItem.RESOLVED_KEY, true);
		nbt.putInt(NBT_VERSION, CURRENT_VERSION);

		NbtList pages = new NbtList();
		for (int page = 1; page <= PAGE_COUNT; page++) {
			pages.add(NbtString.of(Text.Serializer.toJson(page(page))));
		}
		nbt.put(WrittenBookItem.PAGES_KEY, pages);
		return book;
	}

	private static Text page(int number) {
		String key = "book." + SquireMod.MOD_ID + ".compact.page." + number;
		MutableText text = Text.empty()
			.append(Text.translatable(key + ".title")
				.formatted(Formatting.DARK_GREEN, Formatting.BOLD))
			.append("\n\n")
			.append(Text.translatable(key + ".body").formatted(Formatting.BLACK));
		return text;
	}

	/** True only for the current Squire-authored edition, not an arbitrary written book. */
	public static boolean isCurrent(ItemStack stack) {
		return stack != null && stack.isOf(Items.WRITTEN_BOOK) && stack.hasNbt()
			&& stack.getNbt().getInt(NBT_VERSION) == CURRENT_VERSION;
	}

	/** Test/diagnostic helper that returns the serialized page components. */
	public static NbtList pages(ItemStack stack) {
		if (stack == null || !stack.hasNbt()) return new NbtList();
		return stack.getNbt().getList(WrittenBookItem.PAGES_KEY,
			NbtElement.STRING_TYPE);
	}

	private SquireCompact() {
	}
}
