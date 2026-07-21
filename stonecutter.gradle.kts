plugins {
    id("dev.kikugie.stonecutter")
    id("com.modrinth.minotaur") version "2.9.0" apply false
}

stonecutter active "26.2.x"

stonecutter parameters {
    constants["mc_26"] = node.metadata.version >= "26.1"
    constants["mc_26_2"] = node.metadata.version >= "26.2"
    constants["mc_1_21_11"] = node.metadata.version >= "1.21.11"

    replacements {
        string(node.metadata.version >= "26.1") {
            replace("GuiGraphics", "GuiGraphicsExtractor")
            replace(".drawString(", ".text(")
            replace(".drawCenteredString(", ".centeredText(")
            replace("ScreenEvents.afterRender", "ScreenEvents.afterExtract")
            replace(".getDayTime()", ".getOverworldClockTime()")
            replace("client.keybinding.v1.KeyBindingHelper", "client.keymapping.v1.KeyMappingHelper")
            replace("KeyBindingHelper.registerKeyBinding", "KeyMappingHelper.registerKeyMapping")
        }
        string(node.metadata.version >= "26.2") {
            replace(".setScreen(", ".gui.setScreen(")
            replace("client.screen ==", "client.gui.screen() ==")
            replace("client.screen !=", "client.gui.screen() !=")
            replace("client.screen)", "client.gui.screen())")
            replace("client.screen;", "client.gui.screen();")
            replace("client.getMainRenderTarget()", "client.gameRenderer.mainRenderTarget()")
        }
    }
}
