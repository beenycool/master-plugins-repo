package gg.guab.items;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Shared 26.3 dialog construction for the Guab hub. Every dialog gets the same
 * escape/close behaviour, and every click is moved onto the main thread before
 * it can touch Bukkit state.
 */
public final class GuabDialogFactory {

    private GuabDialogFactory() {
    }

    public static Dialog build(Component title, List<DialogBody> body, List<ActionButton> actions,
                               Component exitLabel, int columns) {
        return build(title, body, List.of(), actions, exitLabel, columns);
    }

    public static Dialog build(Component title, List<DialogBody> body, List<DialogInput> inputs,
                               List<ActionButton> actions, Component exitLabel, int columns) {
        ActionButton exit = ActionButton.builder(exitLabel)
                .width(120)
                .action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> audience.closeDialog(),
                        options()))
                .build();

        DialogBase base = DialogBase.builder(title)
                .externalTitle(title)
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body)
                .inputs(inputs)
                .build();

        return Dialog.create(factory -> factory.empty()
                .base(base)
                .type(DialogType.multiAction(actions, exit, columns)));
    }

    /** A button whose click runs on the main thread with no dialog payload. */
    public static ActionButton button(GuabItemsPlugin plugin, Component label, Component tooltip,
                                      int width, Consumer<Player> action) {
        return button(plugin, label, tooltip, width,
                (player, response) -> action.accept(player));
    }

    /**
     * A button that can read its dialog input. The callback receives the response
     * snapshot on the main thread, so reading the payload is safe there.
     */
    public static ActionButton button(GuabItemsPlugin plugin, Component label, Component tooltip,
                                      int width, BiConsumer<Player, DialogResponseView> action) {
        var builder = ActionButton.builder(label).width(width);
        if (tooltip != null) {
            builder.tooltip(tooltip);
        }
        return builder.action(DialogAction.customClick(
                        (DialogResponseView response, Audience audience) -> {
                            if (audience instanceof Player player) {
                                plugin.getServer().getScheduler().runTask(plugin,
                                        () -> action.accept(player, response));
                            }
                        },
                        options()))
                .build();
    }

    public static ClickCallback.Options options() {
        return ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(10))
                .build();
    }
}
