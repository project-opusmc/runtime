/*
 * This file is part of OneConfig.
 * OneConfig - Next Generation Config Library for Minecraft: Java Edition
 * Copyright (C) 2021~2023 Polyfrost.
 *   <https://polyfrost.cc> <https://github.com/Polyfrost/>
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *   OneConfig is licensed under the terms of version 3 of the GNU Lesser
 * General Public License as published by the Free Software Foundation, AND
 * under the Additional Terms Applicable to OneConfig, as published by Polyfrost,
 * either version 1.0 of the Additional Terms, or (at your option) any later
 * version.
 *
 *   This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public
 * License.  If not, see <https://www.gnu.org/licenses/>. You should
 * have also received a copy of the Additional Terms Applicable
 * to OneConfig, as published by Polyfrost. If not, see
 * <https://polyfrost.cc/legal/oneconfig/additional-terms>
 */

package cc.polyfrost.oneconfig.internal.assets;

import java.awt.*;

@SuppressWarnings("unused")
public class Colors {
    // the color library
    public static final int TRANSPARENT = new Color(0, 0, 0, 0).getRGB();                // Transparent
    public static final int BLACK = new Color(0, 0, 0, 255).getRGB();                     // Black
    // OpusConfig palette: deep navy surfaces with a violet accent shared by
    // the Elementa shell and the native OneConfig renderer.
    public static final int GRAY_900 = new Color(12, 14, 20, 255).getRGB();
    public static final int GRAY_900_80 = new Color(12, 14, 20, 204).getRGB();
    public static final int GRAY_800 = new Color(28, 31, 42, 255).getRGB();
    public static final int GRAY_800_95 = new Color(28, 31, 42, 242).getRGB();
    public static final int GRAY_850 = new Color(23, 26, 36, 255).getRGB();
    public static final int GRAY_700 = new Color(39, 43, 57, 255).getRGB();
    public static final int GRAY_600 = new Color(49, 54, 72, 255).getRGB();
    public static final int GRAY_500 = new Color(61, 66, 87, 255).getRGB();
    public static final int GRAY_500_80 = new Color(61, 66, 87, 204).getRGB();
    public static final int GRAY_400 = new Color(76, 83, 108, 255).getRGB();
    public static final int GRAY_400_40 = new Color(76, 83, 108, 102).getRGB();
    public static final int GRAY_400_60 = new Color(76, 83, 108, 153).getRGB();
    public static final int GRAY_300 = new Color(92, 100, 126, 255).getRGB();
    public static final int GRAY_400_80 = new Color(76, 83, 108, 204).getRGB();
    public static final int PRIMARY_800 = new Color(91, 70, 210, 255).getRGB();
    public static final int PRIMARY_700 = new Color(105, 84, 230, 255).getRGB();
    public static final int PRIMARY_700_80 = new Color(105, 84, 230, 204).getRGB();
    public static final int PRIMARY_600 = new Color(119, 95, 255, 255).getRGB();
    public static final int PRIMARY_500 = new Color(143, 124, 255, 255).getRGB();
    public static final int PRIMARY_400 = new Color(160, 145, 255, 255).getRGB();
    public static final int WHITE_50 = new Color(255, 255, 255, 127).getRGB();        // White 50%
    public static final int WHITE_60 = new Color(255, 255, 255, 153).getRGB();        // White 60%
    public static final int WHITE_80 = new Color(255, 255, 255, 204).getRGB();        // White 80%
    public static final int WHITE_90 = new Color(255, 255, 255, 229).getRGB();        // White 90%
    public static final int WHITE_95 = new Color(255, 255, 255, 242).getRGB();        // White 95%
    public static final int WHITE = new Color(255, 255, 255, 255).getRGB();           // White 100%
    public static final int SUCCESS_600 = new Color(3, 152, 85).getRGB();
    public static final int SUCCESS_700 = new Color(2, 121, 72).getRGB();
    public static final int WARNING_500 = new Color(247, 144, 9).getRGB();
    public static final int WARNING_600 = new Color(220, 104, 3).getRGB();
    public static final int ERROR_600_80 = new Color(217, 32, 32, 204).getRGB();
    public static final int ERROR_600 = new Color(217, 32, 32).getRGB();
    public static final int ERROR_700 = new Color(180, 24, 24).getRGB();         // Red 700
    public static final int ERROR_800 = new Color(145, 24, 24).getRGB();         // Red 800
    public static final int ERROR_800_80 = new Color(145, 24, 24, 204).getRGB();         // Red 800
    public static final int ERROR_300 = new Color(253, 155, 155).getRGB();
    public static final int ERROR_300_80 = new Color(253, 155, 155, 204).getRGB();

    /** List of all colors used by Minecraft in its code.
     * Source: <a href="https://www.digminecraft.com/lists/color_list_pc.php">Click Here</a>
     * */
    public static class MinecraftColors {
        /** Letter Code used before colors in Minecraft */
        public static final String CODE = "\u00a7";

        public static final String RESET = "\u00a70";

        public static final int DARK_RED = new Color(170, 0, 0).getRGB();           // Mapped to 4
        public static final int RED = new Color(255, 85, 85).getRGB();              // Mapped to c
        public static final int GOLD = new Color(255, 170, 0).getRGB();             // Mapped to 6
        public static final int YELLOW = new Color(255, 255, 85).getRGB();          // Mapped to e
        public static final int DARK_GREEN = new Color(0, 170, 0).getRGB();         // Mapped to 2
        public static final int GREEN = new Color(85, 255, 85).getRGB();            // Mapped to a
        public static final int AQUA = new Color(85, 255, 255).getRGB();            // Mapped to b
        public static final int DARK_AQUA = new Color(0, 170, 170).getRGB();        // Mapped to 3
        public static final int DARK_BLUE = new Color(0, 0, 170).getRGB();          // Mapped to 1
        public static final int BLUE = new Color(85, 85, 255).getRGB();             // Mapped to 9
        public static final int LIGHT_PURPLE = new Color(255, 85, 255).getRGB();    // Mapped to d
        public static final int DARK_PURPLE = new Color(170, 0, 170).getRGB();      // Mapped to 5
        public static final int WHITE = new Color(255, 255, 255).getRGB();          // Mapped to f
        public static final int GRAY = new Color(170, 170, 170).getRGB();           // Mapped to 7
        public static final int DARK_GRAY = new Color(85, 85, 85).getRGB();         // Mapped to 8
        public static final int BLACK = new Color(0, 0, 0).getRGB();                // Mapped to 0
    }
}
