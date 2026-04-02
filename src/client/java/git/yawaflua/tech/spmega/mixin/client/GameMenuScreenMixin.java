package git.yawaflua.tech.spmega.mixin.client;

import git.yawaflua.tech.spmega.client.ui.UiOpeners;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameMenuScreen.class)
public abstract class GameMenuScreenMixin extends Screen {
    protected GameMenuScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void spmega$addOpenMenuButton(CallbackInfo ci) {
        int buttonWidth = 96;
        int x = this.width / 2 + 106;
        int y = this.height / 4 + 24 - 16;
        this.addDrawableChild(ButtonWidget.builder(Text.literal("SPMega"), button -> {
            UiOpeners.openMainMenu(this.client);
        }).dimensions(x, y, buttonWidth, 20).build());
    }
}

