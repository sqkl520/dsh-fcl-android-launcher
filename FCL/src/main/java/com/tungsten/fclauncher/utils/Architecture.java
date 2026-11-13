package com.tungsten.fclauncher.utils;

import android.os.Build;

/**
 * This class aims at providing a simple and easy way to deal with the device architecture.
 */
public class Architecture {

    public static final int UNSUPPORTED_ARCH = -1;
    public static final int ARCH_ARM64 = 0x1;
    public static final int ARCH_ARM = 0x2;
    public static final int ARCH_X86 = 0x4;
    public static final int ARCH_X86_64 = 0x8;

    /**
     * Tell us if the device supports 64 bits architecture
     * @return If the device supports 64 bits architecture
     */
    public static boolean is64BitsDevice() {
        return Build.SUPPORTED_64_BIT_ABIS.length != 0;
    }

    /**
     * Tell us if the device supports 32 bits architecture
     * Note, that a 64 bits device won't be reported as supporting 32 bits.
     * @return If the device supports 32 bits architecture
     */
    public static boolean is32BitsDevice() {
        return !is64BitsDevice();
    }

    /**
     * Tells the device supported architecture.
     * Since mips(/64) has been phased out long ago, is isn't checked here.
     *
     * [round31/s10-eh-08] 探测失败必须与"确认是 ARM"可区分：原先非 x86 分支直接落到
     * `return is64BitsDevice() ? ARCH_ARM64 : ARCH_ARM`，于是"ABI 一个都认不出来"与
     * "确认是 ARM64"返回同一个值，{@link #UNSUPPORTED_ARCH} 这个专门为失败设计的哨兵
     * 在本方法出口不可达，调用方失去拒绝执行的机会。
     *
     * @return ARCH_ARM || ARCH_ARM64 || ARCH_X86 || ARCH_86_64 || UNSUPPORTED_ARCH（探测失败）
     */
    public static int getDeviceArchitecture() {
        if (isx86Device()) {
            return is64BitsDevice() ? ARCH_X86_64 : ARCH_X86;
        }
        // isArmDevice() 现在对"认不出的 ABI"返回 false（见该方法），这里才能落到哨兵上。
        if (isArmDevice()) {
            return is64BitsDevice() ? ARCH_ARM64 : ARCH_ARM;
        }
        return UNSUPPORTED_ARCH;
    }

    /**
     * Tell is the device is based on an x86 processor.
     * It doesn't tell if the device is 64 or 32 bits.
     * @return Whether or not the device is x86 based.
     */
    public static boolean isx86Device() {
        //We check the whole range of supported ABIs,
        //Since asus zenfones can place arm before their native instruction set.
        String[] ABI = is64BitsDevice() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        int comparedArch = is64BitsDevice() ? ARCH_X86_64 : ARCH_X86;
        for (String str : ABI) {
            if (archAsInt(str) == comparedArch) return true;
        }
        return false;
    }

    /**
     * Tell is the device is based on an arm processor.
     * It doesn't tell if the device is 64 or 32 bits.
     *
     * [round31/s10-eh-08] 原实现是 `return !isx86Device()` —— 它同时把"x86"与"什么都不认识"
     * 判成 arm，于是 {@link #archAsInt} 认不出的 ABI（返回 {@link #UNSUPPORTED_ARCH}）会被当成
     * "确认是 ARM"，{@link #getDeviceArchitecture()} 里那个专门为失败设计的哨兵在出口不可达。
     *
     * 现在多要求一条"ABI 列表里真有一项能映射到 ARM"：**唯一**的行为差异就是"认不出的 ABI"
     * 由 true 变 false。其余情形刻意保持不变（x86 设备 false；arm 设备 true；同时报 arm 与
     * x86 的多 ABI 设备仍 false —— 与 {@link #getDeviceArchitecture()} 的 x86 优先口径一致，
     * 见 {@link #isx86Device()} 里 asus zenfone 的注释）。
     *
     * @return Whether or not the device is arm based.
     */
    public static boolean isArmDevice() {
        if (isx86Device()) return false;
        String[] ABI = is64BitsDevice() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
        int comparedArch = is64BitsDevice() ? ARCH_ARM64 : ARCH_ARM;
        for (String str : ABI) {
            if (archAsInt(str) == comparedArch) return true;
        }
        return false;
    }

    /**
     * Convert an architecture from a String to an int.
     * @param arch The architecture as a String
     * @return The architecture as an int, can be UNSUPPORTED_ARCH if unknown.
     */
    public static int archAsInt(String arch) {
        arch = arch.toLowerCase().trim().replace(" ", "");
        if (arch.contains("arm64") || arch.equals("aarch64")) return ARCH_ARM64;
        if (arch.contains("arm") || arch.equals("aarch32")) return ARCH_ARM;
        if (arch.contains("x86_64") || arch.contains("amd64")) return ARCH_X86_64;
        if (arch.contains("x86") || (arch.startsWith("i") && arch.endsWith("86"))) return ARCH_X86;
        //Shouldn't happen
        return UNSUPPORTED_ARCH;
    }

    /**
     * Convert to a string an architecture.
     * @param arch The architecture as an int.
     * @return "arm64" || "arm" || "x86_64" || "x86" || "UNSUPPORTED_ARCH"
     */
    public static String archAsString(int arch) {
        if (arch == ARCH_ARM64) return "arm64";
        if (arch == ARCH_ARM) return "arm";
        if (arch == ARCH_X86_64) return "x86_64";
        if (arch == ARCH_X86) return "x86";
        return "UNSUPPORTED_ARCH";
    }

    /**
     * Convert to a string an architecture.
     * @param arch The architecture as an int.
     * @return "arm64" || "arm" || "x86_64" || "x86" || "UNSUPPORTED_ARCH"
     */
    public static String archAsStringAndroid(int arch) {
        if(arch == ARCH_ARM64) return "arm64-v8a";
        if(arch == ARCH_ARM) return "armeabi-v7a";
        if(arch == ARCH_X86_64) return "x86_64";
        if(arch == ARCH_X86) return "x86";
        return "UNSUPPORTED_ARCH";
    }
}