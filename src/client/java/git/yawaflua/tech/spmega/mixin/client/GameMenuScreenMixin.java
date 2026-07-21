package git.yawaflua.tech.spmega.mixin.client;

import git.yawaflua.tech.spmega.client.ui.UiOpeners;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
    protected GameMenuScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void spmega$addOpenMenuButton(CallbackInfo ci) {
        int buttonWidth = 96;
        int x = this.width / 2 + 106;
        int y = this.height / 4 + 24 - 16;
        this.addRenderableWidget(Button.builder(Component.literal("SPMega"), button -> {
            UiOpeners.openMainMenu(this.minecraft);
        }).bounds(x, y, buttonWidth, 20).build());
    }
}

