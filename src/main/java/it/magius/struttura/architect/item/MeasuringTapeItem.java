package it.magius.struttura.architect.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.function.Consumer;

/**
 * Measuring Tape - Tool for managing keystone blocks in constructions.
 *
 * Behavior:
 * - Right-click in air: opens Keystone GUI (TODO)
 * - Right-click on block: marks/unmarks block as keystone (TODO)
 */
public class MeasuringTapeItem extends Item {

    public MeasuringTapeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // Right-click in air: open keystone GUI (TODO)
        // For now, just acknowledge the interaction
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        // TODO: Implement keystone block management
        // - Right-click on block: mark/unmark as keystone
        // - Open keystone properties GUI

        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display,
            Consumer<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, display, tooltip, flag);

        tooltip.accept(Component.literal(""));
        tooltip.accept(Component.translatable("item.architect.measuring_tape.tooltip.line1"));
    }
}
