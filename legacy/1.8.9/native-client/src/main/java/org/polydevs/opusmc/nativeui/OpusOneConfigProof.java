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

public final class OpusOneConfigProof extends Config {
    @Header(text = "Automatic option rendering", category = "Proof")
    public boolean optionHeader;

    @Info(
            text = "This development-only module exercises OneConfig's frozen option model.",
            type = InfoType.INFO,
            category = "Proof")
    public boolean proofInfo;

    @Switch(
            name = "Enable dependent controls",
            description = "The slider and text field depend on this switch.",
            category = "Proof")
    public boolean dependentControls = true;

    @Checkbox(name = "Checkbox", category = "Proof")
    public boolean checkbox = true;

    @Slider(name = "Opacity", min = 0f, max = 1f, category = "Proof", instant = true)
    public float opacity = 0.8f;

    @Number(name = "Count", min = 0f, max = 100f, step = 1, category = "Proof")
    public int count = 24;

    @Dropdown(
            name = "Mode",
            options = {"Baseline", "Elementa first", "OneConfig first"},
            category = "Proof")
    public int mode;

    @Text(
            name = "Label",
            placeholder = "Type, select, copy, and paste",
            category = "Proof",
            size = 2)
    public String label = "Opus native UI";

    @Color(name = "Accent", allowAlpha = true, category = "Proof")
    public OneColor accent = new OneColor(119, 95, 255, 220);

    @KeyBind(name = "Proof keybind", category = "Proof")
    public OneKeyBind proofKeybind = new OneKeyBind(Keyboard.KEY_NONE);

    public OpusOneConfigProof() {
        super(new Mod("Opus Native UI Proof", ModType.UTIL_QOL), "opus-native-ui-proof.json");
        initialize();
        addDependency("opacity", "dependentControls");
        addDependency("label", "dependentControls");
        addListener("dependentControls", () ->
                NativeUiRuntimeDiagnostics.record("oneConfigDependentControls", Boolean.toString(dependentControls)));
        registerKeyBind(proofKeybind, () ->
                NativeUiRuntimeDiagnostics.record("oneConfigProofKeybind", "pressed"));
    }

    @Button(name = "Record callback", text = "Run", category = "Proof")
    public void recordCallback() {
        NativeUiRuntimeDiagnostics.record("oneConfigCallback", "invoked");
    }
}
