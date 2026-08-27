package org.polydevs.opusmc.nativeui;

import cc.polyfrost.oneconfig.config.Config;
import cc.polyfrost.oneconfig.config.annotations.Button;
import cc.polyfrost.oneconfig.config.annotations.Checkbox;
import cc.polyfrost.oneconfig.config.annotations.Color;
import cc.polyfrost.oneconfig.config.annotations.Dropdown;
import cc.polyfrost.oneconfig.config.annotations.Header;
import cc.polyfrost.oneconfig.config.annotations.Info;
import cc.polyfrost.oneconfig.config.annotations.KeyBind;
import cc.polyfrost.oneconfig.config.annotations.Number;
import cc.polyfrost.oneconfig.config.annotations.Slider;
import cc.polyfrost.oneconfig.config.annotations.Switch;
import cc.polyfrost.oneconfig.config.annotations.Text;
import cc.polyfrost.oneconfig.config.core.OneColor;
import cc.polyfrost.oneconfig.config.core.OneKeyBind;
import cc.polyfrost.oneconfig.config.data.InfoType;
import cc.polyfrost.oneconfig.config.data.Mod;
import cc.polyfrost.oneconfig.config.data.ModType;
import org.lwjgl.input.Keyboard;

/** Real OpusConfig module used to validate OneConfig's automatic option model. */
public final class OpusConfigModule extends Config {
    @Header(text = "OpusConfig preferences", category = "Opus")
    public boolean opusHeader;

    @Info(
            text = "OpusConfig is the native module and HUD configuration surface for OPUS.",
            type = InfoType.INFO,
            category = "Opus")
    public boolean opusInfo;

    @Switch(
            name = "Enable advanced controls",
            description = "Controls the advanced settings shown below.",
            category = "Opus")
    public boolean advancedControls = true;

    @Checkbox(name = "Enable Opus UI", category = "Opus")
    public boolean opusUiEnabled = true;

    @Slider(name = "UI opacity", min = 0f, max = 1f, category = "Opus", instant = true)
    public float opacity = 0.92f;

    @Number(name = "HUD scale", min = 50f, max = 200f, step = 1, category = "Opus")
    public int hudScale = 100;

    @Dropdown(
            name = "Shell route",
            options = {"Elementa shell", "Vanilla fallback"},
            category = "Opus")
    public int shellRoute;

    @Text(
            name = "Client label",
            placeholder = "Your OPUS client label",
            category = "Opus",
            size = 2)
    public String clientLabel = "OpusConfig";

    @Color(name = "Accent color", allowAlpha = true, category = "Opus")
    public OneColor accent = new OneColor(119, 95, 255, 235);

    @KeyBind(name = "OpusConfig shortcut", category = "Opus")
    public OneKeyBind shortcut = new OneKeyBind(Keyboard.KEY_RSHIFT);

    public OpusConfigModule() {
        super(new Mod("OpusConfig", ModType.UTIL_QOL), "opus-config.json");
        initialize();
        addDependency("opacity", "advancedControls");
        addDependency("hudScale", "advancedControls");
        addDependency("clientLabel", "advancedControls");
        addListener("advancedControls", () ->
                NativeUiRuntimeDiagnostics.record("opusConfigAdvancedControls", Boolean.toString(advancedControls)));
        registerKeyBind(shortcut, () ->
                NativeUiRuntimeDiagnostics.record("opusConfigShortcut", "pressed"));
    }

    @Button(name = "Record callback", text = "Run", category = "Opus")
    public void recordCallback() {
        NativeUiRuntimeDiagnostics.record("opusConfigCallback", "invoked");
    }
}
