package com.wmp.windowmask;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

/**
 * Win32 窗口信息读取（仅供「窗口遮挡」内部使用）。
 *
 * <p>零第三方依赖：使用 JDK 25 已转正的 FFM API（{@code java.lang.foreign}）直接调用 {@code user32.dll}，
 * 与主程序的 {@code com.wmp.downloader.tools.ui.WindowBackdrop} 采用同一套做法，不需要 JNA。</p>
 *
 * <p>只做几件事：取当前<strong>前台</strong>窗口（即获得焦点的窗口）句柄、取它的标题、取它的矩形（物理像素），
 * 以及判断窗口归属的进程。</p>
 *
 * <p>所有方法在非 Windows 或原生库不可用时都安全降级（{@link #available} 为 {@code false}，
 * 返回值给空/0），调用方无需自行判空。</p>
 *
 * <p>注意：使用 FFM 需要启动参数 {@code --enable-native-access=ALL-UNNAMED}，不加只会打印警告，不影响功能。</p>
 *
 * @author 无名牌
 */
final class WinNative {

    /**
     * 原生调用是否可用（Windows 且 user32.dll 可用）。
     * 为 {@code false} 时本插件的业务逻辑会直接跳过，不产生任何遮挡。
     */
    static final boolean available;

    /** 本进程 PID：用于排除减速带自己的窗口 */
    private static final long OWN_PID = ProcessHandle.current().pid();

    /** 共享 Arena：句柄在插件整个生命周期内使用，不能是 confined 作用域 */
    private static final Arena ARENA = Arena.ofShared();

    /** 窗口标题缓冲区容量（UTF-16 字符数） */
    private static final int TITLE_CAPACITY = 512;

    private static MethodHandle getForegroundWindow;
    private static MethodHandle getWindowTextW;
    private static MethodHandle getWindowRect;
    private static MethodHandle isWindowVisible;
    private static MethodHandle isIconic;
    private static MethodHandle getWindowThreadProcessId;

    static {
        boolean ok = false;
        try {
            if (isWindows() && !GraphicsEnvironment.isHeadless()) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32.dll", ARENA);

                getForegroundWindow = linker.downcallHandle(
                        user32.find("GetForegroundWindow").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.ADDRESS));
                getWindowTextW = linker.downcallHandle(
                        user32.find("GetWindowTextW").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                getWindowRect = linker.downcallHandle(
                        user32.find("GetWindowRect").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                isWindowVisible = linker.downcallHandle(
                        user32.find("IsWindowVisible").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                isIconic = linker.downcallHandle(
                        user32.find("IsIconic").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                getWindowThreadProcessId = linker.downcallHandle(
                        user32.find("GetWindowThreadProcessId").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                ok = true;
            }
        } catch (Throwable t) {
            // 非 Windows、缺少 user32.dll 或 native access 被拒绝：静默降级
            ok = false;
        }
        available = ok;
    }

    private WinNative() {
    }

    /** 当前系统是否为 Windows */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    /**
     * 取当前前台窗口（获得焦点的窗口）句柄。
     *
     * @return 窗口句柄；取不到返回 {@code 0}
     */
    static long foregroundWindow() {
        if (!available) {
            return 0;
        }
        try {
            MemorySegment hwnd = (MemorySegment) getForegroundWindow.invoke();
            return hwnd == null ? 0L : hwnd.address();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 取窗口标题。
     *
     * <p>说明：{@code GetWindowTextW} 写出的是 UTF-16LE，必须按 UTF-16LE 解码（不能按 UTF-8）。</p>
     *
     * @param hwnd 窗口句柄
     * @return 窗口标题；取不到返回空串
     */
    static String windowTitle(long hwnd) {
        if (!available || hwnd == 0L) {
            return "";
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate((long) TITLE_CAPACITY * 2);
            int length = (int) getWindowTextW.invoke(MemorySegment.ofAddress(hwnd), buffer, TITLE_CAPACITY);
            if (length <= 0) {
                return "";
            }
            byte[] raw = buffer.asSlice(0, (long) length * 2).toArray(ValueLayout.JAVA_BYTE);
            return new String(raw, StandardCharsets.UTF_16LE);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 取窗口矩形，单位是<strong>物理像素</strong>（调用方需按界面缩放换算为 Swing 逻辑坐标）。
     *
     * @param hwnd 窗口句柄
     * @return 窗口矩形；取不到返回 {@code null}
     */
    static Rectangle windowRect(long hwnd) {
        if (!available || hwnd == 0L) {
            return null;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment rect = arena.allocate(16);
            if ((int) getWindowRect.invoke(MemorySegment.ofAddress(hwnd), rect) == 0) {
                return null;
            }
            int left = rect.get(ValueLayout.JAVA_INT, 0);
            int top = rect.get(ValueLayout.JAVA_INT, 4);
            int right = rect.get(ValueLayout.JAVA_INT, 8);
            int bottom = rect.get(ValueLayout.JAVA_INT, 12);
            return new Rectangle(left, top, right - left, bottom - top);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 窗口是否可见 */
    static boolean isVisible(long hwnd) {
        if (!available || hwnd == 0L) {
            return false;
        }
        try {
            return (int) isWindowVisible.invoke(MemorySegment.ofAddress(hwnd)) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 窗口是否已最小化（最小化时矩形不可信，不应遮挡） */
    static boolean isMinimized(long hwnd) {
        if (!available || hwnd == 0L) {
            return true;
        }
        try {
            return (int) isIconic.invoke(MemorySegment.ofAddress(hwnd)) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 取窗口所属进程的 PID。
     *
     * <p>用于排除减速带自己的窗口（插件宿主），避免把主界面一起遮住。</p>
     *
     * @return 进程 PID；取不到返回 {@code -1}
     */
    static long windowProcessId(long hwnd) {
        if (!available || hwnd == 0L) {
            return -1L;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pid = arena.allocate(4);
            getWindowThreadProcessId.invoke(MemorySegment.ofAddress(hwnd), pid);
            return Integer.toUnsignedLong(pid.get(ValueLayout.JAVA_INT, 0));
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 该窗口是否属于减速带自身进程 */
    static boolean isOwnWindow(long hwnd) {
        return hwnd != 0L && windowProcessId(hwnd) == OWN_PID;
    }
}
